package com.roshan.seat_reservation.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "service", "seat-reservation",
                "health", "/actuator/health",
                "readiness", "/actuator/health/readiness",
                "metrics", "/actuator/prometheus",
                "get_token", "POST /auth/token {\"user_id\":\"u1\"}",
                "show_state", "GET /shows/{id}");
    }
}