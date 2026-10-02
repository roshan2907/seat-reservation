package com.roshan.seat_reservation.reservation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private String[] seats;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Reservation() { }

    public Reservation(UUID id, UUID showId, String userId, String idempotencyKey,
                       String requestHash, String[] seats, long amountPaise) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.seats = seats;
        this.amountPaise = amountPaise;
        this.status = "confirmed";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getShowId() { return showId; }
    public String getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String[] getSeats() { return seats; }
    public long getAmountPaise() { return amountPaise; }
    public String getStatus() { return status; }
}