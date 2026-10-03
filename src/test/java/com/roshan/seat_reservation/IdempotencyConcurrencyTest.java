package com.roshan.seat_reservation;

import com.roshan.seat_reservation.reservation.ReservationDtos.ReserveRequest;
import com.roshan.seat_reservation.reservation.ReservationDtos.ReserveResult;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyConcurrencyTest {

    @Autowired ShowService showService;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fiftyConcurrentRetriesWithSameKeyCreateExactlyOneReservation() throws Exception {
        ShowResponse show = showService.create(new CreateShowRequest("idem", List.of("A1", "A2"), 25_000L, 4));
        String userId = "retry-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "same-key";

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Set<UUID> reservationIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                try {
                    ReserveResult r = reservationService.reserve(show.id(), userId, key,
                            new ReserveRequest(List.of("A1"), null));
                    reservationIds.add(r.reservation().reservationId());
                    if (r.replayed()) replayed.incrementAndGet(); else created.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                return null;
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, userId, key);
        Integer quota = jdbc.queryForObject(
                "SELECT held_count FROM user_show_quota WHERE show_id = ? AND user_id = ?",
                Integer.class, show.id(), userId);

        System.out.printf("created=%d replayed=%d errors=%d distinctIds=%d rows=%d quota=%d%n",
                created.get(), replayed.get(), errors.get(), reservationIds.size(), rows, quota);

        assertThat(errors.get()).isZero();
        assertThat(created.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(49);
        assertThat(reservationIds).as("every caller got the same reservation").hasSize(1);
        assertThat(rows).isEqualTo(1);
        assertThat(quota).as("retries don't consume extra quota").isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentSeatsIsRejected() {
        ShowResponse show = showService.create(new CreateShowRequest("idem2", List.of("A1", "A2"), 25_000L, 4));
        String userId = "reuse-" + UUID.randomUUID().toString().substring(0, 8);

        reservationService.reserve(show.id(), userId, "k1", new ReserveRequest(List.of("A1"), null));

        assertThatThrownBy(() ->
                reservationService.reserve(show.id(), userId, "k1", new ReserveRequest(List.of("A2"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("idempotency_key_reused");

        Integer a2Confirmed = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND label = 'A2' AND status = 'confirmed'",
                Integer.class, show.id());
        assertThat(a2Confirmed).as("rejected request moved nothing").isZero();
    }
}