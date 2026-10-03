package com.roshan.seat_reservation;

import com.roshan.seat_reservation.show.ShowDtos.CreateShowRequest;
import com.roshan.seat_reservation.show.ShowDtos.ShowResponse;
import com.roshan.seat_reservation.show.ShowService;
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
class ErrorHandlingTest {

    @Value("${local.server.port}") int port;
    @Autowired ShowService showService;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void everyFailureIsACleanJson4xx() throws Exception {
        ShowResponse show = showService.create(new CreateShowRequest("errors", List.of("A1"), 100L, 4));
        String run = UUID.randomUUID().toString().substring(0, 8);
        String alice = token("alice-" + run);
        String bob = token("bob-" + run);
        String reserve = "/shows/" + show.id() + "/reserve";

        assertError(send("POST", reserve, null, "{}"), 401, "missing_token");
        assertError(send("POST", reserve, alice, "{not json"), 400, "malformed_json");
        assertError(send("POST", reserve, alice, "{\"seats\":[],\"idempotency_key\":\"k\"}"), 400, "validation_failed");
        assertError(send("POST", reserve, alice, "{\"seats\":[\"A1\"]}"), 400, "idempotency_key_required");
        assertError(send("POST", reserve, alice, "{\"seats\":[\"Z9\"],\"idempotency_key\":\"k0\"}"), 422, "unknown_seat");

        assertThat(send("POST", reserve, alice, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}").statusCode()).isEqualTo(201);
        assertError(send("POST", reserve, bob, "{\"seats\":[\"A1\"],\"idempotency_key\":\"k2\"}"), 409, "seat_taken");
        assertError(send("POST", reserve, alice, "{\"seats\":[\"A1\",\"B1\"],\"idempotency_key\":\"k1\"}"), 409, "idempotency_key_reused");

        assertError(send("GET", "/shows/not-a-uuid", null, null), 400, "invalid_parameter");
        assertError(send("GET", "/shows/" + UUID.randomUUID(), null, null), 404, "show_not_found");
        assertError(send("POST", "/reservations/" + UUID.randomUUID() + "/cancel", alice, null), 404, "reservation_not_found");
    }

    private void assertError(HttpResponse<String> resp, int status, String code) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
        assertThat(resp.body()).contains("\"code\":\"" + code + "\"");
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String userId) throws Exception {
        String body = send("POST", "/auth/token", null, "{\"user_id\":\"" + userId + "\"}").body();
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }
}