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
class HotSeatConcurrencyTest {

    @Autowired ShowService showService;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exactlyOneWinnerWhen500UsersStormOneSeat() throws Exception {
        ShowResponse show = showService.create(
                new CreateShowRequest("storm", List.of("A1", "A2", "A3"), 25_000L, 4));

        int users = 500;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch startGate = new CountDownLatch(1);   // release all threads at once
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger declines = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            String userId = "user-" + i;
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    reservationService.reserve(show.id(), userId, "key-" + userId,
                            new ReserveRequest(List.of("A1"), null));
                    wins.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() == 409) declines.incrementAndGet();
                    else errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }

        startGate.countDown();                       // GO!
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        Integer reservationsHoldingA1 = jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ? AND 'A1' = ANY(seats) AND status = 'confirmed'",
                Integer.class, show.id());

        System.out.printf("wins=%d declines=%d errors=%d reservationsHoldingA1=%d%n",
                wins.get(), declines.get(), errors.get(), reservationsHoldingA1);

        assertThat(wins.get()).as("exactly one user may win seat A1").isEqualTo(1);
        assertThat(declines.get()).as("everyone else gets a clean 409").isEqualTo(users - 1);
        assertThat(errors.get()).as("no 5xx-style errors").isZero();
        assertThat(reservationsHoldingA1).as("only one reservation may own A1").isEqualTo(1);
    }
}