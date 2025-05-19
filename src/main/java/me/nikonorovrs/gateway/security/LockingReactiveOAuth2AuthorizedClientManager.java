package me.nikonorovrs.gateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class LockingReactiveOAuth2AuthorizedClientManager implements ReactiveOAuth2AuthorizedClientManager {

    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;
    private final ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> tokenResponseClient;
    private final RedissonClient redissonClient;

    @Value("${spring.refresh-lock.namespace}")
    private String refreshLockNamespace;

    @Value("${spring.refresh-lock.buffer:60}")
    private long refreshBuffer;

    @Value("${spring.refresh-lock.wait:5}")
    private long waitTime;

    @Value("${spring.refresh-lock.lease:10}")
    private long leaseTime;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    @Override
    public Mono<OAuth2AuthorizedClient> authorize(OAuth2AuthorizeRequest request) {
        Authentication principal = request.getPrincipal();
        String clientRegistrationId = request.getClientRegistrationId();

        return authorizedClientService.loadAuthorizedClient(clientRegistrationId, principal.getName())
                .doOnNext(client -> log.debug("Клиент загружен: clientId={}, principal={}, token={}",
                        clientRegistrationId, principal.getName(),
                        client.getAccessToken().getTokenValue().substring(0, 5) + "..."))
                .flatMap(client -> {
                    if (tokenNeedsRefresh(client)) {
                        log.debug("Требуется обновление токена: clientId={}, principal={}, expires={}",
                                clientRegistrationId, principal.getName(), formatInstant(client.getAccessToken().getExpiresAt()));
                        return refreshToken(client, principal);
                    }
                    log.debug("Токен актуален: clientId={}, principal={}, expires={}",
                            clientRegistrationId, principal.getName(), formatInstant(client.getAccessToken().getExpiresAt()));
                    return Mono.just(client);
                })
                .doOnError(error -> log.error("Ошибка при авторизации: clientId={}, principal={}, error={}",
                        clientRegistrationId, principal.getName(), error.getMessage()));
    }

    private boolean tokenNeedsRefresh(OAuth2AuthorizedClient client) {
        OAuth2AccessToken accessToken = client.getAccessToken();
        Instant now = Instant.now();
        // Подстраховка на refreshBuffer секунд до истечения
        return accessToken.getExpiresAt() != null &&
                accessToken.getExpiresAt().isBefore(now.plus(Duration.ofSeconds(refreshBuffer)));
    }

    private Mono<OAuth2AuthorizedClient> refreshToken(OAuth2AuthorizedClient client, Authentication principal) {
        String clientId = client.getClientRegistration().getRegistrationId();
        String username = principal.getName();
        String lockKey = refreshLockNamespace + ":" + clientId + ":" + username;
        RLock lock = redissonClient.getLock(lockKey);

        log.debug("Начало обновления токена: clientId={}, principal={}, lockKey={}",
                clientId, username, lockKey);

        // Получение блокировки
        Mono<RLock> acquireLock = monoFromRFuture(lock.tryLockAsync(waitTime, leaseTime, TimeUnit.SECONDS))
                .doOnNext(locked -> log.debug("Получение блокировки: lockKey={}, locked={}", lockKey, locked))
                .flatMap(locked -> {
                    if (!locked) {
                        log.warn("Не удалось получить блокировку: lockKey={}", lockKey);
                        return Mono.error(new IllegalStateException("Не удалось получить блокировку для обновления токена"));
                    }
                    return Mono.just(lock);
                });

        // Использование паттерна usingWhen для корректного управления ресурсом
        return Mono.usingWhen(
                // Получение ресурса (блокировки)
                acquireLock,

                // Использование ресурса (выполнение обновления токена)
                acquiredLock -> authorizedClientService
                        .loadAuthorizedClient(clientId, username)
                        .flatMap(currentClient -> {
                            if (!tokenNeedsRefresh(currentClient)) {
                                log.debug("Токен уже был обновлен другим процессом: clientId={}, principal={}",
                                        clientId, username);
                                return Mono.just(currentClient);
                            }

                            log.debug("Выполняется обновление токена: clientId={}, principal={}", clientId, username);

                            OAuth2RefreshTokenGrantRequest refreshRequest =
                                    new OAuth2RefreshTokenGrantRequest(
                                            client.getClientRegistration(),
                                            currentClient.getAccessToken(),
                                            currentClient.getRefreshToken());

                            return tokenResponseClient.getTokenResponse(refreshRequest)
                                    .map(response -> {
                                        log.debug("Токен успешно обновлен: clientId={}, principal={}, expires={}",
                                                clientId, username, formatInstant(response.getAccessToken().getExpiresAt()));
                                        return new OAuth2AuthorizedClient(
                                                client.getClientRegistration(),
                                                username,
                                                response.getAccessToken(),
                                                response.getRefreshToken());
                                    })
                                    .flatMap(updatedClient ->
                                            authorizedClientService
                                                    .saveAuthorizedClient(updatedClient, principal)
                                                    .doOnSuccess(v -> log.debug("Обновленный токен сохранен: clientId={}, principal={}",
                                                            clientId, username))
                                                    .thenReturn(updatedClient))
                                    .doOnError(error -> log.error("Ошибка при обновлении токена: clientId={}, principal={}, error={}",
                                            clientId, username, error.getMessage(), error));
                        }),

                // Освобождение при успешном завершении
                acquiredLock -> releaseLock(acquiredLock, lockKey),

                // Освобождение при ошибке
                (acquiredLock, error) -> releaseLock(acquiredLock, lockKey),

                // Освобождение при отмене
                acquiredLock -> releaseLock(acquiredLock, lockKey)
        );
    }

    // Отдельный метод для асинхронного освобождения блокировки
    private Mono<Void> releaseLock(RLock lock, String lockKey) {
        return monoFromRFuture(lock.unlockAsync())
                .doOnSuccess(v -> log.debug("Блокировка освобождена: lockKey={}", lockKey))
                .doOnError(error -> log.error("Ошибка при освобождении блокировки: lockKey={}", lockKey, error))
                .onErrorResume(e -> Mono.empty()) // Даже если ошибка, продолжаем выполнение
                .then();
    }

    private static <T> Mono<T> monoFromRFuture(RFuture<T> rFuture) {
        return Mono.create(sink -> {
            rFuture.whenCompleteAsync((res, ex) -> {
                if (ex != null) {
                    sink.error(ex);
                } else {
                    sink.success(res);
                }
            });
        });
    }

    private String formatInstant(Instant instant) {
        return instant != null ? instant.atZone(java.time.ZoneId.systemDefault()).format(DATE_TIME_FORMATTER) : "null";
    }
}