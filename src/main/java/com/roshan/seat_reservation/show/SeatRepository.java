package com.roshan.seat_reservation.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    long countByShowIdAndLabelIn(UUID showId, Collection<String> labels);

    /**
     * Atomic claim: the availability check and the write happen in one statement.
     * Returns the number of seats actually claimed.
     */
    @Modifying
    @Query(value = """
            UPDATE seats
               SET status = 'confirmed', reservation_id = :reservationId
             WHERE show_id = :showId
               AND label IN (:labels)
               AND status = 'available'
            """, nativeQuery = true)
    int claimIfAvailable(@Param("showId") UUID showId,
                         @Param("labels") Collection<String> labels,
                         @Param("reservationId") UUID reservationId);
}