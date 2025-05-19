package me.nikonorovrs.gateway.config;

import me.nikonorovrs.gateway.security.CustomRedirectServerAuthenticationEntryPoint;
import me.nikonorovrs.gateway.security.LockingReactiveOAuth2AuthorizedClientManager;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;

import java.net.URI;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenTokenResponseClient() {
        return new WebClientReactiveRefreshTokenTokenResponseClient();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/**", "/login/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2Login(oauth2 -> {})
                .oauth2Client(oauth2 -> {})
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new CustomRedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak"))
                )
                .logout(logout -> logout
                        .logoutSuccessHandler(logoutSuccessHandler())
                )
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .build();
    }

    @Bean
    public ServerLogoutSuccessHandler logoutSuccessHandler() {
        RedirectServerLogoutSuccessHandler handler = new RedirectServerLogoutSuccessHandler();
        handler.setLogoutSuccessUrl(URI.create("/"));
        return handler;
    }

    @Bean
    public ReactiveOAuth2AuthorizedClientManager reactiveOAuth2AuthorizedClientManager(
            ReactiveOAuth2AuthorizedClientService clientService,
            RedissonClient redissonClient) {

        ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient =
                new WebClientReactiveRefreshTokenTokenResponseClient();

        return new LockingReactiveOAuth2AuthorizedClientManager(
                clientService,
                refreshTokenClient,
                redissonClient
        );
    }
}