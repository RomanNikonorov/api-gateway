package me.nikonorovrs.gateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static me.nikonorovrs.gateway.security.SecurityUtils.getRedirectHttpStatus;

@Slf4j
public class CustomRedirectServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final URI location;

    public CustomRedirectServerAuthenticationEntryPoint(String location) {
        this.location = URI.create(location);
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException e) {

        ServerHttpResponse response = exchange.getResponse();

        // Устанавливаем HTTP-код редиректа
        response.setStatusCode(getRedirectHttpStatus(exchange));

        response.getHeaders().setLocation(this.location);
        return response.setComplete();
    }
}