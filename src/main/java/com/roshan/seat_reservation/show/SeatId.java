package com.roshan.seat_reservation.show;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class SeatId implements Serializable {
    private UUID showId;
    private String label;

    public SeatId() { }
    public SeatId(UUID showId, String label) { this.showId = showId; this.label = label; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeatId other)) return false;
        return Objects.equals(showId, other.showId) && Objects.equals(label, other.label);
    }
    @Override public int hashCode() { return Objects.hash(showId, label); }
}