package me.nikonorovrs.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.ACCESS_TOKEN;

@Service
@Slf4j
public class SessionService {

    private final WebClient webClient;
    private final String keycloakLogoutUrl;
    private final String backendUrl;
    private final String gatewayUrl;

    public SessionService(WebClient webClient,
                          @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}") String keycloakLogoutUrl,
                          @Value("${app.backend.url}") String backendUrl,
                          @Value("${app.frontend.url}") String gatewayUrl) {
        this.webClient = webClient;
        this.keycloakLogoutUrl = keycloakLogoutUrl;
        this.backendUrl = backendUrl;
        this.gatewayUrl = gatewayUrl;
    }

    /**
     * Performs logout by invalidating the session and calling the backend logout endpoint.
     *
     * @param session      the web session
     * @param fflSessionId the FFL session ID
     * @return a Mono that emits the response entity
     */
    public Mono<ResponseEntity<Object>> performLogout(final WebSession session, final String fflSessionId) {
        String accessToken = session.getAttribute(ACCESS_TOKEN);
        return callBackendLogout(accessToken, fflSessionId)
                .then(session.invalidate())
                .thenReturn(ResponseEntity
                        .status(HttpStatus.UNAUTHORIZED)
                        .header("Location", keycloakLogoutUrl + "/protocol/openid-connect/logout?redirect_uri=" + gatewayUrl)
                        .build());
    }

    /**
     * Calls the backend logout endpoint to invalidate the session.
     *
     * @param token        the access token
     * @param fflSessionId the FFL session ID
     * @return a Mono that completes when the logout is done
     */
    public Mono<Void> callBackendLogout(String token, final String fflSessionId) {
        if (token == null) {
            return Mono.empty();
        }

        return webClient.get()
                .uri(backendUrl + "/api/v1/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .cookie("SESSION", fflSessionId)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> {
                    log.error("Error calling backend logout: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
