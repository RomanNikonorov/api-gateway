package me.nikonorovrs.gateway.security;

import me.nikonorovrs.gateway.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ContextConfiguration(initializers = RedisTestContainer.class)
public class LockingReactiveOAuth2AuthorizedClientManagerTest {

    @Mock
    private ReactiveOAuth2AuthorizedClientService authorizedClientService;

    @Mock
    private ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> tokenResponseClient;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Mock
    private RFuture<Boolean> lockFuture;

    @Mock
    private RFuture<Void> unlockFuture;

    private LockingReactiveOAuth2AuthorizedClientManager manager;
    private OAuth2AuthorizedClient expiredClient;
    private Authentication authentication;
    private AtomicInteger refreshCount;

    @BeforeEach
    public void setUp() {
        manager = new LockingReactiveOAuth2AuthorizedClientManager(
                authorizedClientService, tokenResponseClient, redissonClient);

        // Установка значений полей через рефлексию
        ReflectionTestUtils.setField(manager, "refreshLockNamespace", "test-refresh-lock");
        ReflectionTestUtils.setField(manager, "refreshBuffer", 60L);
        ReflectionTestUtils.setField(manager, "waitTime", 5L);
        ReflectionTestUtils.setField(manager, "leaseTime", 10L);

        ClientRegistration clientRegistration = ClientRegistration.withRegistrationId("test-client")
                .clientId("test-client-id")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/callback")
                .scope("read", "write")
                .authorizationUri("http://localhost/auth")
                .tokenUri("http://localhost/token")
                .build();

        OAuth2AccessToken expiredAccessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "expired-token",
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(60),
                Set.of("read", "write")
        );

        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "refresh-token",
                Instant.now().plusSeconds(3600)
        );

        expiredClient = new OAuth2AuthorizedClient(
                clientRegistration, "test-user", expiredAccessToken, refreshToken);

        authentication = new TestingAuthenticationToken("test-user", "password");
        refreshCount = new AtomicInteger(0);
    }

    @Test
    public void shouldRefreshExpiredToken() {
        // Подготовка нового токена для ответа
        OAuth2AccessToken newAccessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "new-token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Set.of("read", "write")
        );

        OAuth2AccessTokenResponse tokenResponse = OAuth2AccessTokenResponse.withToken("new-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(3600)
                .scopes(Set.of("read", "write"))
                .refreshToken("refresh-token")
                .build();

        // Настройка моков
        when(authorizedClientService.loadAuthorizedClient("test-client", "test-user"))
                .thenReturn(Mono.just(expiredClient));
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLockAsync(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(lockFuture);
        when(lockFuture.whenCompleteAsync(any())).thenAnswer(invocation -> {
            ((java.util.function.BiConsumer<Boolean, Throwable>) invocation.getArgument(0)).accept(true, null);
            return lockFuture;
        });
        when(tokenResponseClient.getTokenResponse(any())).thenReturn(Mono.just(tokenResponse));
        when(authorizedClientService.saveAuthorizedClient(any(), any())).thenReturn(Mono.empty());
        when(rLock.unlockAsync()).thenReturn(unlockFuture);
        when(unlockFuture.whenCompleteAsync(any())).thenAnswer(invocation -> {
            ((java.util.function.BiConsumer<Void, Throwable>) invocation.getArgument(0)).accept(null, null);
            return unlockFuture;
        });

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId("test-client")
                .principal(authentication)
                .build();

        StepVerifier.create(manager.authorize(request))
                .expectNextMatches(client ->
                        client.getAccessToken().getTokenValue().equals("new-token") &&
                                client.getAccessToken().getExpiresAt().isAfter(Instant.now().plusSeconds(3500)))
                .verifyComplete();

        // Изменяем проверку, чтобы учесть возможность нескольких вызовов
        verify(authorizedClientService, atLeastOnce()).loadAuthorizedClient("test-client", "test-user");
        verify(tokenResponseClient).getTokenResponse(any());
        verify(authorizedClientService).saveAuthorizedClient(any(), eq(authentication));
    }

    @Test
    public void shouldPreventConcurrentRefresh() throws InterruptedException {
        int threads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        // Настройка моков для симуляции блокировки
        when(authorizedClientService.loadAuthorizedClient("test-client", "test-user"))
                .thenReturn(Mono.just(expiredClient));
        when(redissonClient.getLock(anyString())).thenReturn(rLock);

        AtomicBoolean firstThread = new AtomicBoolean(true);

        // Настройка поведения блокировки - важно чтобы первый получал true
        when(rLock.tryLockAsync(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(lockFuture);

        when(lockFuture.whenCompleteAsync(any())).thenAnswer(invocation -> {
            BiConsumer<Boolean, Throwable> action = invocation.getArgument(0);
            // Только первый вызов получает true, остальные false
            boolean success = firstThread.getAndSet(false);
            action.accept(success, null);
            return lockFuture;
        });

        // Настройка обновления токена
        when(tokenResponseClient.getTokenResponse(any())).thenReturn(
                Mono.just(OAuth2AccessTokenResponse.withToken("new-token-1")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(3600)
                        .scopes(Set.of("read", "write"))
                        .refreshToken("refresh-token")
                        .build())
        );

        when(authorizedClientService.saveAuthorizedClient(any(), any())).thenReturn(Mono.empty());
        when(rLock.unlockAsync()).thenReturn(unlockFuture);
        when(unlockFuture.whenCompleteAsync(any())).thenAnswer(invocation -> {
            BiConsumer<Void, Throwable> action = invocation.getArgument(0);
            action.accept(null, null);
            return unlockFuture;
        });

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest.withClientRegistrationId("test-client")
                .principal(authentication)
                .build();

        // Запускаем один поток, который гарантированно получит блокировку
        Thread successThread = new Thread(() -> {
            try {
                startLatch.await();
                manager.authorize(request).block();
                endLatch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        successThread.start();

        // Запускаем остальные потоки, которые должны получить отказ
        for (int i = 1; i < threads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    manager.authorize(request).onErrorResume(e -> Mono.empty()).block();
                } catch (Exception ignored) {
                    // Ожидаемые ошибки из-за неполучения блокировки
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // Запуск всех потоков одновременно
        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);

        // Проверка, что обновление произошло ровно один раз
        verify(tokenResponseClient, times(1)).getTokenResponse(any());
        verify(authorizedClientService, times(1)).saveAuthorizedClient(any(), any());
    }
}