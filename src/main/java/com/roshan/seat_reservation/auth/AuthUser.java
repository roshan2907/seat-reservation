package com.roshan.seat_reservation.auth;
import com.fasterxml.jackson.annotation.JsonIgnore;


public record AuthUser(String userId, String role) {
    public static final String REQUEST_ATTR = "authUser";

    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}