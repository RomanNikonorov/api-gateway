package me.nikonorovrs.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        if (exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String traceId = exchange.getRequest().getHeaders().getFirst("trace-id");
        log.info("Request: {} {} with trace-id: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                traceId != null ? traceId : "");

        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    log.info("Response status: {} with trace-id: {}",
                            exchange.getResponse().getStatusCode(),
                            traceId != null ? traceId : "");
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}