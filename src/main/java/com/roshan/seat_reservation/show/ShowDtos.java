package com.roshan.seat_reservation.show;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class ShowDtos {
    private ShowDtos() { }

    public record CreateShowRequest(
            @NotBlank String name,
            @NotEmpty @Size(max = 10_000) List<@NotBlank String> seats,
            @NotNull @PositiveOrZero Long pricePaise,
            @Positive Integer perUserLimit) { }

    public record SeatView(String label, String status) { }

    public record SeatCounts(long available, long held, long confirmed, long total) { }

    public record ShowResponse(
            UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
            SeatCounts counts, List<SeatView> seats) { }
}