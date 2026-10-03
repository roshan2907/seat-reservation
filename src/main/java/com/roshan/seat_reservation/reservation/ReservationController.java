package com.roshan.seat_reservation.reservation;

import com.roshan.seat_reservation.auth.AuthUser;
import com.roshan.seat_reservation.observability.ReservationMetrics;
import com.roshan.seat_reservation.reservation.ReservationDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationMetrics metrics;

    public ReservationController(ReservationService reservationService, ReservationMetrics metrics) {
        this.reservationService = reservationService;
        this.metrics = metrics;
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId,
                                                       @RequestAttribute(AuthUser.REQUEST_ATTR) AuthUser user,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                       @Valid @RequestBody ReserveRequest req) {
        String key = headerKey != null ? headerKey : req.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotency_key_required");
        }
        // user id comes ONLY from the token, never from the body
        ReserveResult result = reservationService.reserve(showId, user.userId(), key, req);
        if (result.replayed()) {
            metrics.declined("idempotent_replay");
        } else {
            metrics.confirmed();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.reservation());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId,
                                      @RequestAttribute(AuthUser.REQUEST_ATTR) AuthUser user) {
        ReservationResponse response = reservationService.cancel(reservationId, user.userId());
        metrics.cancelled();
        return response;
    }
}