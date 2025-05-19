package me.nikonorovrs.gateway.exception;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ErrorHandlerConfig {

    @Bean
    public WebProperties.Resources resources() {
        return new WebProperties.Resources();
    }

    @Component
    @Order(-2)
    public static class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

        public GlobalErrorWebExceptionHandler(ErrorAttributes errorAttributes,
                                              WebProperties.Resources resources,
                                              ApplicationContext applicationContext,
                                              ServerCodecConfigurer serverCodecConfigurer) {
            super(errorAttributes, resources, applicationContext);
            this.setMessageWriters(serverCodecConfigurer.getWriters());
        }

        @Override
        protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
            return RouterFunctions.route(RequestPredicates.all(), this::renderErrorResponse);
        }

        private Mono<ServerResponse> renderErrorResponse(ServerRequest request) {
            Map<String, Object> errorPropertiesMap = getErrorAttributes(request,
                    ErrorAttributeOptions.defaults());

            Map<String, Object> customResponse = new HashMap<>();
            customResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
            customResponse.put("status", errorPropertiesMap.get("status"));
            customResponse.put("error", errorPropertiesMap.get("error"));
            customResponse.put("path", request.path());

            return ServerResponse.status(HttpStatus.valueOf((Integer) customResponse.get("status")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(customResponse));
        }
    }
}