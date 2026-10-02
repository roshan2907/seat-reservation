package com.roshan.seat_reservation.show;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seats")
@IdClass(SeatId.class)
public class Seat {

    @Id
    @Column(name = "show_id")
    private UUID showId;

    @Id
    private String label;

    @Column(nullable = false)
    private String status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected Seat() { }

    public boolean isAvailable() { return "available".equals(status); }
    

    public UUID getShowId() { return showId; }
    public String getLabel() { return label; }
    public String getStatus() { return status; }
    public UUID getReservationId() { return reservationId; }
}