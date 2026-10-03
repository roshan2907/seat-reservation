# Seat Reservation Service

A JSON API for selling assigned seats. It is built so that under heavy concurrent load:

- no seat is ever sold twice,
- each user can hold at most 4 seats per show,
- client retries are idempotent.

Stack: Java 21 · Spring Boot 4.1 · PostgreSQL 16 (Flyway) · Docker · Railway.
Design decisions, trade-offs and load results are in **[WRITEUP.md](WRITEUP.md)**.

## Live

| | |
|---|---|
| Base URL | https://seat-reservation-production-fe29.up.railway.app |
| Liveness | [/actuator/health/liveness](https://seat-reservation-production-fe29.up.railway.app/actuator/health/liveness) |
| Readiness (includes DB) | [/actuator/health/readiness](https://seat-reservation-production-fe29.up.railway.app/actuator/health/readiness) |
| Metrics (Prometheus) | [/actuator/prometheus](https://seat-reservation-production-fe29.up.railway.app/actuator/prometheus) |
| Logs under load (recording) | [Watch on Google Drive](https://drive.google.com/file/d/1wrAgJ81P15Kj31Tx6kYRQ9yYIM5sMgHz/view) |

The service runs on Railway, built from the Dockerfile, with a managed Postgres. The DB credentials and the JWT secret are injected as environment variables and are never stored in the repo.

## Run locally

```bash
docker compose up --build
```

- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- Postgres is on host port **15432**, so it doesn't clash with a local Postgres install.

The tests use Testcontainers, so Docker must be running:

```bash
./mvnw test
```

The suite covers:

- 500 users racing for one seat
- overlapping multi-seat requests
- the per-user limit under concurrency
- concurrent idempotent retries
- owner-only cancel
- error mapping
- metrics

## API

All request and response bodies are JSON with `snake_case` fields. Every write needs `Authorization: Bearer <token>`. The user identity always comes from the token. A `user_id` sent in the request body is ignored.

### Get a token (dev/test only)

```bash
curl -X POST $BASE/auth/token -H 'Content-Type: application/json' \
     -d '{"user_id":"u1"}'                        # normal user
curl -X POST $BASE/auth/token -H 'Content-Type: application/json' \
     -d '{"user_id":"admin1","role":"admin"}'     # admin
# -> {"token":"eyJ..."}
```

This endpoint issues HS256 JWTs (24h TTL) so the service can be exercised without an identity provider. In production it would be replaced by a real IdP. `GET /auth/me` shows who a token belongs to.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/shows` | admin | Create a show with its seat labels |
| `GET` | `/shows/{id}` | public | Show details with seat counts |
| `POST` | `/shows/{id}/reserve` | user | Reserve one or more seats |
| `POST` | `/reservations/{id}/cancel` | owner | Cancel your reservation and free its seats |
| `GET` | `/actuator/health/liveness` | public | Process is alive (no DB check) |
| `GET` | `/actuator/health/readiness` | public | Ready to serve. Fails closed if the DB is down |
| `GET` | `/actuator/prometheus` | public | Metrics |

### Create a show (admin)

```bash
curl -X POST $BASE/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"name":"Evening show","seats":["A1","A2","A3","A4"],"price_paise":25000}'
```

### Reserve seats

```bash
curl -i -X POST $BASE/shows/$SHOW/reserve -H "Authorization: Bearer $U1" -H 'Content-Type: application/json' \
     -d '{"seats":["A1","A2"],"idempotency_key":"order-123"}'
```

`201 Created` returns the reservation, including `reservation_id`, `seats`, `amount_paise` and `status: "confirmed"`.

How a reservation request is decided:

- **All-or-nothing.** If any requested seat is unavailable, nothing is reserved and the request gets `409`. A partial booking never happens.
- **Idempotency.** `idempotency_key` is required and is scoped per user.
  - Same key and same body: you get the original reservation back with `201` and the header `Idempotent-Replayed: true`. No new seats are taken and no quota is used.
  - Same key with a different body: `409 idempotency_key_reused`.
- **Per-user limit.** A user can hold at most 4 seats per show, counted across all their reservations. The limit is enforced atomically, so it holds even when requests arrive in parallel.

### Cancel

```bash
curl -X POST $BASE/reservations/$RES/cancel -H "Authorization: Bearer $U1"
```

- Only the owner can cancel. Anyone else gets `404`, so the API doesn't reveal that the reservation exists.
- Cancelling frees the seats and returns the user's quota in the same transaction.
- Cancelling an already-cancelled reservation is a no-op.

### Show state

```bash
curl $BASE/shows/$SHOW
```

The response includes `total_seats` and `counts: {available, held, confirmed}`. The invariant `available + held + confirmed == total_seats` always holds, because each seat is one row with one status.

### Errors

Every error has the same shape: `{"code": "...", "message": "..."}`. The service never returns a 5xx for a lost race.

| Status | When |
|---|---|
| `400` / `422` | Invalid body, missing `idempotency_key`, unknown seat label |
| `401` | Missing or invalid token (`missing_token`) |
| `403` | Non-admin calling `POST /shows` |
| `404` | Unknown show, or a reservation that doesn't exist or isn't yours |
| `409` | `seat_taken`, `per_user_limit`, `idempotency_key_reused`, `seat_contended` (lock wait timed out, safe to retry) |
| `503` | Database unavailable. Sent with a `Retry-After` header |

## Observability

**Metrics** (`/actuator/prometheus`):

- `reservations_confirmed_total`
- `reservations_declined_total{reason}`
- `reservations_cancelled_total`
- `seats{status}` and `show_seats{show_id,status}`: refreshed from the DB every 2s, so they reconcile with `GET /shows/{id}`.

**Logs:** JSON (logstash format) in the container.

- Every request has a `request_id`. It is taken from the `X-Request-Id` header or generated, and echoed back in the response.
- The authenticated `user_id` is also on every log line.

**Health:**

- Liveness doesn't check the DB, so a DB outage never causes restart loops.
- Readiness includes the DB, so traffic stops reaching an instance that can't decide seats correctly.

## Burst test (one command)

```bash
./burst/burst.sh https://seat-reservation-production-fe29.up.railway.app
# Windows: pip install -r burst/requirements.txt && python burst/burst.py <BASE_URL>
```

The script creates a fresh show, then fires these at the same time:

- a hot-seat storm: 500 users on one seat
- spread load
- concurrent same-key retries
- per-user-limit abuse
- a spoofed identity

After that it sends same-key-different-body reuse.

It prints the outcome distribution and p50/p95/p99, then checks:

- exactly one winner on the hot seat
- zero 5xx
- no double-sell
- `available + held + confirmed == total`
- the per-user limit
- idempotency
- token-derived identity
- that the Prometheus counters match the API

It exits 1 if any check fails.

Options: `--seats`, `--hot-users`, `--spread-users`, `--replays`, `--concurrency`.

To run it from a cloud runner instead of your own network, go to GitHub → Actions → **burst** → Run workflow.

**Latest live runs**, 2,211 requests each ([burst/last-run.txt](burst/last-run.txt)):

| Concurrency | 5xx | Hot seat | Double-sold | Throughput | p50 | p99 | Result |
|---|---|---|---|---|---|---|---|
| 200 | 0 | 1 winner / 499 declined | 0 | 46 req/s | 2.9 s | 16.9 s | all checks pass |
| 500 | 0 | 1 winner / 499 declined | 0 | 41 req/s | 7.1 s | 48.2 s | all checks pass |

Recording of the live JSON logs and Railway metrics during a burst:
[Google Drive](https://drive.google.com/file/d/1wrAgJ81P15Kj31Tx6kYRQ9yYIM5sMgHz/view)

![Railway metrics during the burst: 2xx and 4xx only, no 5xx](docs/railway-metrics.png)

Correctness held at both levels. The latency comes from the small Railway instance and its connection pool, not from errors. WRITEUP.md explains why and what would come next.

## Project layout

```
src/main/java/com/roshan/seat_reservation/
  auth/           JWT issue/verify, auth filter
  show/           shows, seats, seat counts
  reservation/    reserve, idempotency, per-user quota, cancel
  common/         error handling, index
  observability/  request-id filter, metrics, seat gauges
src/main/resources/db/migration/   Flyway schema
burst/                             load + correctness script
```
