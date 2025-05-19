package me.nikonorovrs.gateway.security;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import reactor.core.publisher.Mono;

public class RedisReactiveOAuth2AuthorizedClientService implements ReactiveOAuth2AuthorizedClientService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final String keyPrefix;

    public RedisReactiveOAuth2AuthorizedClientService(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    private String buildKey(String principalName, String clientRegistrationId) {
        return keyPrefix + principalName + ":" + clientRegistrationId;
    }

    @Override
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(String clientRegistrationId, String principalName) {
        String key = buildKey(principalName, clientRegistrationId);
        return redisTemplate.opsForValue().get(key).cast(OAuth2AuthorizedClient.class).map(client -> (T) client);
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
        String key = buildKey(principal.getName(), authorizedClient.getClientRegistration().getRegistrationId());
        return redisTemplate.opsForValue().set(key, authorizedClient).then();
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String clientRegistrationId, String principalName) {
        String key = buildKey(principalName, clientRegistrationId);
        return redisTemplate.delete(key).then();
    }
}