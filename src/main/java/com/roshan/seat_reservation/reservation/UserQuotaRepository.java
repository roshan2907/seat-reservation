package com.roshan.seat_reservation.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Per-user, per-show seat counter. All changes are single atomic statements. */
@Repository
public class UserQuotaRepository {

    private final JdbcTemplate jdbc;

    public UserQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically adds n seats to the user's count if it stays within the limit.
     * Returns false if the limit would be exceeded.
     */
    public boolean tryAcquire(UUID showId, String userId, int n, int limit) {
        jdbc.update("""
                INSERT INTO user_show_quota (show_id, user_id, held_count)
                VALUES (?, ?, 0)
                ON CONFLICT (show_id, user_id) DO NOTHING
                """, showId, userId);

        int updated = jdbc.update("""
                UPDATE user_show_quota
                   SET held_count = held_count + ?
                 WHERE show_id = ? AND user_id = ?
                   AND held_count + ? <= ?
                """, n, showId, userId, n, limit);
        return updated == 1;
    }
}