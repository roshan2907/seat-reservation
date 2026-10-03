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
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PerUserLimitConcurrencyTest {

    @Autowired ShowService showService;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void oneUserFiringTenParallelRequestsGetsAtMostFourSeats() throws Exception {
        List<String> seatLabels = IntStream.rangeClosed(1, 10).mapToObj(i -> "A" + i).toList();
        ShowResponse show = showService.create(new CreateShowRequest("limit", seatLabels, 10_000L, 4));

        String userId = "greedy-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger limitDeclines = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String seat = seatLabels.get(i);             // a different free seat each time
            String key = "key-" + i;
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    reservationService.reserve(show.id(), userId, key, new ReserveRequest(List.of(seat), null));
                    wins.incrementAndGet();
                } catch (ResponseStatusException e) {
                    if ("per_user_limit".equals(e.getReason())) limitDeclines.incrementAndGet();
                    else errors.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        Integer seatsHeld = jdbc.queryForObject(
                "SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id " +
                        "WHERE s.show_id = ? AND r.user_id = ?", Integer.class, show.id(), userId);
        Integer quota = jdbc.queryForObject(
                "SELECT held_count FROM user_show_quota WHERE show_id = ? AND user_id = ?",
                Integer.class, show.id(), userId);

        System.out.printf("wins=%d limitDeclines=%d errors=%d seatsHeld=%d quota=%d%n",
                wins.get(), limitDeclines.get(), errors.get(), seatsHeld, quota);

        assertThat(errors.get()).isZero();
        assertThat(wins.get()).isEqualTo(4);
        assertThat(limitDeclines.get()).isEqualTo(6);
        assertThat(seatsHeld).isEqualTo(4);
        assertThat(quota).as("quota counter matches real seats").isEqualTo(4);
    }
}