package com.roshan.seat_reservation.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record TokenRequest(
            @NotBlank String userId,
            @Pattern(regexp = "user|admin") String role) {}

    /** Dev/test endpoint: issues a token for any user id. */
    @PostMapping("/token")
    public Map<String, String> token(@Valid @RequestBody TokenRequest req) {
        String role = req.role() == null ? "user" : req.role();
        return Map.of("token", jwtService.issue(req.userId(), role), "user_id", req.userId(), "role", role);
    }

    /** Shows who the token belongs to. Proves identity comes from the token. */
    @GetMapping("/me")
    public AuthUser me(@RequestAttribute(AuthUser.REQUEST_ATTR) AuthUser user) {
        return user;
    }
}