package com.roshan.seat_reservation;

import com.roshan.seat_reservation.observability.SeatGauges;
import com.roshan.seat_reservation.show.ShowDtos.CreateShowRequest;
import com.roshan.seat_reservation.show.ShowDtos.ShowResponse;
import com.roshan.seat_reservation.show.ShowService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ObservabilityTest {

    @Value("${local.server.port}") int port;
    @Autowired MeterRegistry registry;
    @Autowired SeatGauges seatGauges;
    @Autowired ShowService showService;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readinessChecksDbAndRequestIdIsEchoed() throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/actuator/health/readiness"))
                .header("X-Request-Id", "trace-123").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"db\"").contains("UP");
        assertThat(resp.headers().firstValue("X-Request-Id")).contains("trace-123");
    }

    @Test
    void metricsReconcileWithApiState() throws Exception {
        ShowResponse show = showService.create(new CreateShowRequest("metrics", List.of("A1", "A2", "A3"), 100L, 4));
        String run = UUID.randomUUID().toString().substring(0, 8);
        String reserve = "/shows/" + show.id() + "/reserve";

        double confirmedBefore = counter("reservations.confirmed", null);
        double seatTakenBefore = counter("reservations.declined", "seat_taken");

        assertThat(post(reserve, token("m1-" + run), "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}").statusCode()).isEqualTo(201);
        assertThat(post(reserve, token("m2-" + run), "{\"seats\":[\"A1\"],\"idempotency_key\":\"k\"}").statusCode()).isEqualTo(409);

        assertThat(counter("reservations.confirmed", null) - confirmedBefore).isEqualTo(1.0);
        assertThat(counter("reservations.declined", "seat_taken") - seatTakenBefore).isEqualTo(1.0);

        seatGauges.refresh();
        assertThat(showGauge(show, "available")).isEqualTo(2.0);
        assertThat(showGauge(show, "confirmed")).isEqualTo(1.0);
        assertThat(showGauge(show, "held")).isEqualTo(0.0);
    }

    private double counter(String name, String reason) {
        var search = registry.find(name);
        if (reason != null) search = search.tag("reason", reason);
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    private double showGauge(ShowResponse show, String status) {
        return registry.find("show.seats").tag("show_id", show.id().toString()).tag("status", status).gauge().value();
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String userId) throws Exception {
        String body = post("/auth/token", null, "{\"user_id\":\"" + userId + "\"}").body();
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}