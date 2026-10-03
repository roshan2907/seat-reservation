package com.roshan.seat_reservation.reservation;

import com.roshan.seat_reservation.reservation.ReservationDtos.*;
import com.roshan.seat_reservation.show.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserQuotaRepository userQuotaRepository;
    private final JdbcTemplate jdbc;

    public ReservationService(ShowRepository showRepository, SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              UserQuotaRepository userQuotaRepository, JdbcTemplate jdbc) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userQuotaRepository = userQuotaRepository;
        this.jdbc = jdbc;
    }

    /**
     * Reserves seats all-or-nothing, exactly once per (user, idempotency key).
     * Lock order (same for every write path): reservation row -> quota row -> seats (sorted).
     */
    @Transactional
    public ReserveResult reserve(UUID showId, String userId, String idempotencyKey, ReserveRequest req) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "show not found"));

        List<String> labels = req.seats().stream().map(String::trim).distinct().sorted().toList();
        String hash = requestHash(showId, labels);
        UUID reservationId = UUID.randomUUID();
        long amountPaise = show.getPricePaise() * labels.size();

        // 1. Idempotency claim. A concurrent request with the same key blocks here
        //    on the unique index until the first one commits or rolls back.
        int inserted = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO reservations
                        (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'confirmed', now())
                    ON CONFLICT (user_id, idempotency_key) DO NOTHING
                    """);
            ps.setObject(1, reservationId);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, idempotencyKey);
            ps.setString(5, hash);
            ps.setArray(6, con.createArrayOf("text", labels.toArray()));
            ps.setLong(7, amountPaise);
            return ps;
        });

        if (inserted == 0) {
            Reservation existing = reservationRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency row vanished"));
            if (!existing.getRequestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "idempotency_key_reused");
            }
            return new ReserveResult(ReservationResponse.from(existing), true);
        }

        if (labels.size() > show.getPerUserLimit()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "per_user_limit");
        }

        // 2. Per-user limit: atomic conditional increment on one row
        if (!userQuotaRepository.tryAcquire(showId, userId, labels.size(), show.getPerUserLimit())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "per_user_limit");
        }

        // 3. Lock seats in sorted order, then claim atomically
        List<String> locked = seatRepository.lockInOrder(showId, labels);
        if (locked.size() != labels.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown seat");
        }
        int claimed = seatRepository.claimIfAvailable(showId, labels, reservationId);
        if (claimed != labels.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "seat_taken");
        }

        return new ReserveResult(
                new ReservationResponse(reservationId, showId, userId, labels, amountPaise, "confirmed"),
                false);
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