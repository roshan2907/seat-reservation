package com.roshan.seat_reservation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Business counters. Exposed as reservations_confirmed_total, reservations_declined_total{reason}, ... */
@Component
public class ReservationMetrics {

    public static final List<String> DECLINE_REASONS = List.of(
            "seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_reused", "seat_contended");

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter cancelled;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations confirmed (committed)").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled by their owner").register(registry);
        DECLINE_REASONS.forEach(this::declinedCounter);   // pre-register so they show as 0
    }

    public void confirmed() { confirmed.increment(); }

    public void cancelled() { cancelled.increment(); }

    public void declined(String reason) { declinedCounter(reason).increment(); }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined")
                .description("Reservation requests declined, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}