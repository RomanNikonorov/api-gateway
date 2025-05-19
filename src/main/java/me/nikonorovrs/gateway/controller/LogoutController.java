package me.nikonorovrs.gateway.controller;

import lombok.RequiredArgsConstructor;
import me.nikonorovrs.gateway.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;



@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LogoutController {

    private final SessionService sessionService;

    @GetMapping("logout")
    public Mono<ResponseEntity<Object>> logout(ServerHttpRequest request, WebSession session) {
        final var fflSessionId = request
                .getCookies()
                .get("SESSION")
                .getFirst()
                .getValue();
        return sessionService.performLogout(session, fflSessionId);
    }
}
