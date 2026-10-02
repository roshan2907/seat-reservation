package com.roshan.seat_reservation;

import com.roshan.seat_reservation.reservation.ReservationDtos.ReserveRequest;
import com.roshan.seat_reservation.reservation.ReservationService;
import com.roshan.seat_reservation.show.ShowDtos.CreateShowRequest;
import com.roshan.seat_reservation.show.ShowDtos.ShowResponse;
import com.roshan.seat_reservation.show.ShowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MultiSeatConcurrencyTest {

    @Autowired ShowService showService;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void overlappingMultiSeatRequestsNeverDoubleSellOrError() throws Exception {
        ShowResponse show = showService.create(
                new CreateShowRequest("multi", List.of("A1", "A2", "A3", "A4"), 10_000L, 4));

        // Overlapping pairs, deliberately requested in opposite orders
        List<List<String>> patterns = List.of(
                List.of("A1", "A2"), List.of("A2", "A1"),
                List.of("A2", "A3"), List.of("A3", "A2"),
                List.of("A3", "A4"), List.of("A4", "A3"),
                List.of("A4", "A1"), List.of("A1", "A4"));

        int requests = 400;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger declines = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String userId = "multi-" + run + "-" + i;
            List<String> seats = patterns.get(i % patterns.size());
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    reservationService.reserve(show.id(), userId, "key-" + userId,
                            new ReserveRequest(seats, null));
                    wins.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() == 409) declines.incrementAndGet();
                    else errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();   // a deadlock would land here
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // Every seat is owned by at most one confirmed reservation
        Integer doubleSold = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT s.label
                    FROM reservations r, unnest(r.seats) AS s(label)
                   WHERE r.show_id = ? AND r.status = 'confirmed'
                   GROUP BY s.label HAVING count(*) > 1
                ) x""", Integer.class, show.id());

        Integer confirmedSeats = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND status = 'confirmed'",
                Integer.class, show.id());

        System.out.printf("wins=%d declines=%d errors=%d confirmedSeats=%d doubleSold=%d%n",
                wins.get(), declines.get(), errors.get(), confirmedSeats, doubleSold);

        assertThat(errors.get()).as("no deadlocks or server errors").isZero();
        assertThat(doubleSold).as("no seat owned by two reservations").isZero();
        assertThat(wins.get() + declines.get()).isEqualTo(requests);
        assertThat(confirmedSeats).isEqualTo(wins.get() * 2);   // all-or-nothing: each win = 2 seats
    }
}