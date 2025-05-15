package me.nikonorovrs.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static me.nikonorovrs.gateway.security.SecurityUtils.getRedirectHttpStatus;

@Component
public class CustomAuthRedirectFilterFactory extends AbstractGatewayFilterFactory<CustomAuthRedirectFilterFactory.Config> {

    public CustomAuthRedirectFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            return ReactiveSecurityContextHolder.getContext()
                    .map(SecurityContext::getAuthentication)
                    .filter(Authentication::isAuthenticated)
                    .filter(auth -> !(auth instanceof AnonymousAuthenticationToken))
                    .flatMap(auth -> chain.filter(exchange))
                    .switchIfEmpty(handleUnauthorized(exchange));
        };
    }

    private Mono<Void> handleUnauthorized(ServerWebExchange exchange) {

        ServerHttpResponse response = exchange.getResponse();

        // Определяем URL для редиректа на Keycloak
        String redirectUrl = "/oauth2/authorization/keycloak";

        // Устанавливаем соответствующий HTTP-код
        response.setStatusCode(getRedirectHttpStatus(exchange));

        response.getHeaders().add("Location", redirectUrl);
        return response.setComplete();
    }

    public static class Config {
        // Пустая конфигурация, так как все параметры берутся из метаданных маршрута
    }
}