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
     * Reserves seats all-or-nothing.
     * The seat claim is a single conditional UPDATE guarded on status='available';
     * if fewer rows than requested are updated, the transaction rolls back.
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, String idempotencyKey, ReserveRequest req) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "show not found"));

        List<String> labels = req.seats().stream().map(String::trim).distinct().sorted().toList();

        if (seatRepository.countByShowIdAndLabelIn(showId, labels) != labels.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown seat");
        }

        Reservation reservation = new Reservation(UUID.randomUUID(), showId, userId, idempotencyKey,
                requestHash(showId, labels), labels.toArray(String[]::new),
                show.getPricePaise() * labels.size());
        reservationRepository.saveAndFlush(reservation); // must exist before seats reference it (FK)

        int claimed = seatRepository.claimIfAvailable(showId, labels, reservation.getId());
        if (claimed != labels.size()) {
            // rollback undoes the reservation row and any seats claimed by this statement
            throw new ResponseStatusException(HttpStatus.CONFLICT, "seat_taken");
        }

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