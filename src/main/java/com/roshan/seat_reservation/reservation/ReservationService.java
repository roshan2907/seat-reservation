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
    private final UserQuotaRepository userQuotaRepository;

    public ReservationService(ShowRepository showRepository, SeatRepository seatRepository,
                              ReservationRepository reservationRepository , UserQuotaRepository userQuotaRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userQuotaRepository = userQuotaRepository;
    }

    /**
     * Reserves seats all-or-nothing.
     * Lock order (same for every write path): reservation row -> quota row -> seats (sorted).
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, String idempotencyKey, ReserveRequest req) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "show not found"));

        List<String> labels = req.seats().stream().map(String::trim).distinct().sorted().toList();
        if (labels.size() > show.getPerUserLimit()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "per_user_limit");
        }

        // 1. Reservation row (must exist before seats can reference it)
        Reservation reservation = new Reservation(UUID.randomUUID(), showId, userId, idempotencyKey,
                requestHash(showId, labels), labels.toArray(String[]::new),
                show.getPricePaise() * labels.size());
        reservationRepository.saveAndFlush(reservation);

        // 2. Per-user limit: atomic conditional increment on one row
        if (!userQuotaRepository.tryAcquire(showId, userId, labels.size(), show.getPerUserLimit())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "per_user_limit");
        }

        // 3. Lock seats in sorted order, then claim atomically
        List<String> locked = seatRepository.lockInOrder(showId, labels);
        if (locked.size() != labels.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown seat");
        }
        int claimed = seatRepository.claimIfAvailable(showId, labels, reservation.getId());
        if (claimed != labels.size()) {
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