package me.nikonorovrs.gateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

@Slf4j
public class SecurityUtils {

    public SecurityUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static HttpStatus getRedirectHttpStatus(final ServerWebExchange exchange) {

        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        final int redirectCode;
        HttpStatus redirectStatus = HttpStatus.FOUND;

        if (route != null && route.getMetadata().containsKey("redirect-code")) {
            try {
                redirectCode = Integer.parseInt(route.getMetadata().get("redirect-code").toString());
                redirectStatus = HttpStatus.valueOf(redirectCode);
            } catch (NumberFormatException ex) {
                log.error("Ошибочный код ответа для редиректа: {}", ex.getMessage());
            }
        }
        return redirectStatus;
    }
}
