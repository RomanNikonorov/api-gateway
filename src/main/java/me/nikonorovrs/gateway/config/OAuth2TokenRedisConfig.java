package me.nikonorovrs.gateway.config;

import me.nikonorovrs.gateway.security.RedisReactiveOAuth2AuthorizedClientService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;


@Configuration
public class OAuth2TokenRedisConfig {

    private final String tokenNamespace;

    public OAuth2TokenRedisConfig(@Value("${spring.oauth2.redis.namespace}") String tokenNamespace) {
        this.tokenNamespace = tokenNamespace;
    }

    @Bean
    public ReactiveRedisTemplate<String, Object> tokenRedisTemplate(ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        JdkSerializationRedisSerializer valueSerializer = new JdkSerializationRedisSerializer();

        RedisSerializationContext<String, Object> context = RedisSerializationContext
                .<String, Object>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .hashKey(keySerializer)
                .hashValue(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public ReactiveOAuth2AuthorizedClientService authorizedClientService(
            ReactiveRedisTemplate<String, Object> tokenRedisTemplate) {
        return new RedisReactiveOAuth2AuthorizedClientService(tokenRedisTemplate, tokenNamespace + ":");
    }


}