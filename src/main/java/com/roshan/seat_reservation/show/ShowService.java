package com.roshan.seat_reservation.show;

import com.roshan.seat_reservation.show.ShowDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ShowService {

    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository showRepository;
    private final JdbcTemplate jdbc;

    public ShowService(ShowRepository showRepository, JdbcTemplate jdbc) {
        this.showRepository = showRepository;
        this.jdbc = jdbc;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest req) {
        List<String> labels = req.seats().stream().map(String::trim).toList();
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate_seat_labels");
        }
        int limit = req.perUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.perUserLimit();

        Show show = new Show(UUID.randomUUID(), req.name().trim(), req.pricePaise(), limit, labels.size());
        // Flush now: the seat inserts below reference shows(id) via FK
        showRepository.saveAndFlush(show);

        // Batch insert all seats as 'available' in one round-trip per 500 rows
        jdbc.batchUpdate(
                "INSERT INTO seats (show_id, label, status) VALUES (?, ?, 'available')",
                labels, 500,
                (ps, label) -> {
                    ps.setObject(1, show.getId());
                    ps.setString(2, label);
                });

        return get(show.getId());
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "show_not_found"));

        // ONE query => one consistent snapshot, so the counts always add up to the total
        List<SeatView> seats = jdbc.query(
                "SELECT label, status FROM seats WHERE show_id = ? ORDER BY label",
                (rs, i) -> new SeatView(rs.getString("label"), rs.getString("status")),
                showId);

        long available = seats.stream().filter(s -> s.status().equals("available")).count();
        long held = seats.stream().filter(s -> s.status().equals("held")).count();
        long confirmed = seats.stream().filter(s -> s.status().equals("confirmed")).count();

        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(),
                show.getPerUserLimit(), show.getTotalSeats(),
                new SeatCounts(available, held, confirmed, seats.size()), seats);
    }
}