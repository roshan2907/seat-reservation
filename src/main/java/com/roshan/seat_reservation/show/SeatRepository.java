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

    /** Locks the seats owned by a reservation, in label order (same order as reserve). */
    @Query(value = """
            SELECT label FROM seats
             WHERE reservation_id = :reservationId
             ORDER BY label
               FOR UPDATE
            """, nativeQuery = true)
    List<String> lockByReservationInOrder(@Param("reservationId") UUID reservationId);

    /** Frees only seats still owned by this reservation - can never touch someone else's seat. */
    @Modifying
    @Query(value = """
            UPDATE seats
               SET status = 'available', reservation_id = NULL
             WHERE reservation_id = :reservationId
               AND status = 'confirmed'
            """, nativeQuery = true)
    int releaseByReservation(@Param("reservationId") UUID reservationId);
}