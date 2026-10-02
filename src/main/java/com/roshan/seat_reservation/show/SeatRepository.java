package com.roshan.seat_reservation.show;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {
    List<Seat> findByShowIdAndLabelIn(UUID showId, Collection<String> labels);
}