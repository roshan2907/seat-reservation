package com.roshan.seat_reservation.show;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class Show {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Show() { } // for JPA

    public Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        this.id = id;
        this.name = name;
        this.pricePaise = pricePaise;
        this.perUserLimit = perUserLimit;
        this.totalSeats = totalSeats;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public long getPricePaise() { return pricePaise; }
    public int getPerUserLimit() { return perUserLimit; }
    public int getTotalSeats() { return totalSeats; }
    public Instant getCreatedAt() { return createdAt; }
}