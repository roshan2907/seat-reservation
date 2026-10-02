package com.roshan.seat_reservation.reservation;

import com.roshan.seat_reservation.reservation.ReservationDtos.*;
import com.roshan.seat_reservation.show.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;

    public ReservationService(ShowRepository showRepository, SeatRepository seatRepository,
                              ReservationRepository reservationRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * NAIVE VERSION: load -> check -> save.
     * Intentionally racy: two concurrent transactions can both see a seat as
     * 'available' and both confirm it. Exposed by the next commit's test, then fixed.
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, String idempotencyKey, ReserveRequest req) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "show not found"));

        List<String> labels = req.seats().stream().map(String::trim).distinct().sorted().toList();

        // READ
        List<Seat> seats = seatRepository.findByShowIdAndLabelIn(showId, labels);
        if (seats.size() != labels.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown seat");
        }

        // CHECK  (race window: another transaction can confirm the seat right now)
        for (Seat seat : seats) {
            if (!seat.isAvailable()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "seat_taken");
            }
        }

        // WRITE
        Reservation reservation = new Reservation(UUID.randomUUID(), showId, userId, idempotencyKey,
                requestHash(showId, labels), labels.toArray(String[]::new),
                show.getPricePaise() * labels.size());
        reservationRepository.saveAndFlush(reservation); // seats.reservation_id has FK to reservations
        seats.forEach(seat -> seat.confirm(reservation.getId()));

        return ReservationResponse.from(reservation);
    }

    static String requestHash(UUID showId, List<String> sortedLabels) {
        try {
            String canonical = showId + ":" + String.join(",", sortedLabels);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}