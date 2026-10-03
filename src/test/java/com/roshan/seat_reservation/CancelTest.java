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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CancelTest {

    @Autowired ShowService showService;
    @Autowired ReservationService reservationService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyOwnerCanCancelAndCancelNeverResurrectsAnotherUsersSeat() {
        ShowResponse show = showService.create(new CreateShowRequest("cancel", List.of("A1", "A2"), 25_000L, 4));
        String run = UUID.randomUUID().toString().substring(0, 8);
        String alice = "alice-" + run;
        String bob = "bob-" + run;

        UUID aliceRes = reservationService.reserve(show.id(), alice, "k1",
                new ReserveRequest(List.of("A1"), null)).reservation().reservationId();

        // Bob cannot cancel Alice's reservation
        assertThatThrownBy(() -> reservationService.cancel(aliceRes, bob))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reservation not found");
        assertThat(seatStatus(show.id(), "A1")).isEqualTo("confirmed");

        // Alice cancels -> seat freed, quota returned
        reservationService.cancel(aliceRes, alice);
        assertThat(seatStatus(show.id(), "A1")).isEqualTo("available");
        assertThat(quota(show.id(), alice)).isZero();

        // Bob books the freed seat
        reservationService.reserve(show.id(), bob, "k1", new ReserveRequest(List.of("A1"), null));
        assertThat(seatStatus(show.id(), "A1")).isEqualTo("confirmed");

        // Alice cancels again -> no-op, Bob keeps A1
        reservationService.cancel(aliceRes, alice);
        assertThat(seatStatus(show.id(), "A1")).as("cancel must not resurrect Bob's seat").isEqualTo("confirmed");
        assertThat(quota(show.id(), bob)).isEqualTo(1);
    }

    private String seatStatus(UUID showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND label = ?",
                String.class, showId, label);
    }

    private Integer quota(UUID showId, String userId) {
        return jdbc.queryForObject("SELECT held_count FROM user_show_quota WHERE show_id = ? AND user_id = ?",
                Integer.class, showId, userId);
    }
}