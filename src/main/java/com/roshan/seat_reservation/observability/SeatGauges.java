package com.roshan.seat_reservation.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seat gauges read from the database (the source of truth), refreshed every 2s:
 *   seats{status}                    - totals across all shows
 *   show_seats{show_id,status}       - per show, to reconcile with GET /shows/{id}
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final List<String> STATUSES = List.of("available", "held", "confirmed");

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> totals = new HashMap<>();
    private final MultiGauge perShow;

    public SeatGauges(MeterRegistry registry, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        for (String status : STATUSES) {
            AtomicLong value = new AtomicLong();
            totals.put(status, value);
            Gauge.builder("seats", value, AtomicLong::get)
                    .description("Seats across all shows by status")
                    .tag("status", status)
                    .register(registry);
        }
        this.perShow = MultiGauge.builder("show.seats")
                .description("Seats per show by status")
                .register(registry);
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT s.id AS show_id, st.status, count(se.label) AS n
                      FROM shows s
                     CROSS JOIN (VALUES ('available'), ('held'), ('confirmed')) AS st(status)
                      LEFT JOIN seats se ON se.show_id = s.id AND se.status = st.status
                     GROUP BY s.id, st.status
                    """);
            Map<String, Long> sums = new HashMap<>();
            List<MultiGauge.Row<?>> gaugeRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String status = (String) row.get("status");
                long n = ((Number) row.get("n")).longValue();
                sums.merge(status, n, Long::sum);
                gaugeRows.add(MultiGauge.Row.of(
                        Tags.of("show_id", row.get("show_id").toString(), "status", status), n));
            }
            totals.forEach((status, value) -> value.set(sums.getOrDefault(status, 0L)));
            perShow.register(gaugeRows, true);
        } catch (Exception e) {
            log.warn("seat gauge refresh failed: {}", e.getMessage());
        }
    }
}