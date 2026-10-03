package com.roshan.seat_reservation.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class ReservationDtos {
    private ReservationDtos() { }

    public record ReserveRequest(
            @NotEmpty @Size(max = 10) List<@NotBlank String> seats,
            String idempotencyKey) { }

    public record ReservationResponse(
            UUID reservationId, UUID showId, String userId,
            List<String> seats, long amountPaise, String status) {

        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getId(), r.getShowId(), r.getUserId(),
                    List.of(r.getSeats()), r.getAmountPaise(), r.getStatus());
        }
    }

    /** Service result: the reservation plus whether it was an idempotent replay. */
    public record ReserveResult(ReservationResponse reservation, boolean replayed) { }
}