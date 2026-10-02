package com.roshan.seat_reservation.show;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    /**
     * Row-locks the requested seats in label order. A fixed global lock order
     * means two multi-seat requests can never wait on each other in a cycle.
     */
    @Query(value = """
            SELECT label FROM seats
             WHERE show_id = :showId AND label IN (:labels)
             ORDER BY label
               FOR UPDATE
            """, nativeQuery = true)
    List<String> lockInOrder(@Param("showId") UUID showId,
                             @Param("labels") Collection<String> labels);

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