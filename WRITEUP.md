# WRITEUP — Seat Reservation at Scale

Live: https://seat-reservation-production-fe29.up.railway.app
Stack: Java 21 · Spring Boot 4.1 · PostgreSQL 16 (Flyway) · JPA + JdbcTemplate · Railway (Dockerfile build)

---

## 1. The atomic decision

**Mechanism: a single conditional `UPDATE` guarded on current state, checked by affected-row count.**

```sql
UPDATE seats
SET status = 'confirmed', reservation_id = :reservationId
WHERE show_id = :showId
  AND label IN (:labels)
  AND status = 'available'
```

If the affected row count is not equal to the number of seats requested, the request is declined (`409 seat_taken`) and the whole transaction rolls back.

**Why it is race-free.** The availability check *is* the write, so there is no gap between "is it free?" and "take it". When 500 transactions hit seat `A12`:

1. The first `UPDATE` takes the row lock on `A12`.
2. Every other `UPDATE` on that row blocks on the lock.
3. When the winner commits, PostgreSQL (READ COMMITTED) re-evaluates the `WHERE` clause of each waiter against the **new** row version. `status = 'available'` is now false, so each waiter updates 0 rows.
4. Each loser sees `0 != 1`, throws, rolls back, and returns a clean `409`.

**Evidence that this matters.** I first implemented the obvious JPA version (load seats → check `available` → save) and wrote a concurrency test before fixing it (see the git history):

| Version | 500 users storm one seat |
|---|---|
| load → check → save | **10 winners**, 10 reservations owning the same seat |
| conditional `UPDATE` | **1 winner**, 499 × `409 seat_taken`, 0 errors |

The 10 winners equalled the Hikari pool size: every transaction in the first concurrent wave read `available` before any of them wrote.

**Database constraints as a safety net**, so even an application bug cannot corrupt state:
- `PRIMARY KEY (show_id, label)` — a seat exists exactly once.
- `CHECK ((status = 'available') = (reservation_id IS NULL))` — no confirmed seat without an owner, no available seat with one.
- `UNIQUE (user_id, idempotency_key)` — exactly-once per key.
- `CHECK (held_count >= 0)` on the quota.

### Multi-seat requests and deadlock avoidance

Partial requests are **all-or-nothing**: if any requested seat is unavailable, nothing is reserved.

Two users asking for `[A1, A2]` and `[A2, A1]` could deadlock if they lock rows in different orders. Before the conditional update, the requested seats are locked in a **deterministic order**:

```sql
SELECT label FROM seats WHERE show_id = ? AND label IN (...) ORDER BY label FOR UPDATE
```

Every write path takes locks in the same global order — **reservation row → quota row → seats (sorted by label)** — and `cancel` follows the same order, so a wait-for cycle cannot form. A test fires 400 overlapping pair requests in opposite orders: 0 errors, no seat owned twice, every winner got exactly 2 seats.

Honest note: the labels were already sorted before this change and PostgreSQL usually scans them in order, so the test may also pass without the explicit `FOR UPDATE`. The explicit lock turns "usually ordered by the planner" into a guarantee.

### Per-user limit

Each `(show, user)` has a counter row, incremented atomically:

```sql
UPDATE user_show_quota SET held_count = held_count + :n
WHERE show_id = ? AND user_id = ? AND held_count + :n <= :limit
```

0 rows → `409 per_user_limit`. A user's parallel requests serialise on that single row. If the later seat claim fails, the rollback also undoes the increment. Test: one user, 10 parallel requests for 10 free seats, limit 4 → exactly 4 confirmed, 6 declined, counter = 4.

---

## 2. Idempotency

- **Where the key lives:** on the reservation row itself — `reservations.idempotency_key` with `UNIQUE (user_id, idempotency_key)`. Keys are scoped per user (taken from the token), so two users can use the same key string. The key is sent in the `idempotency_key` body field.
- **How exactly-once is enforced:** the reservation row is the **first** write in the transaction:
  ```sql
  INSERT INTO reservations (...) VALUES (...) ON CONFLICT (user_id, idempotency_key) DO NOTHING
  ```
  - 1 row inserted → new request, continue to quota and seats.
  - 0 rows → the key exists; load it.
  - Concurrent retries with the same key block on the unique index until the first transaction finishes. If it committed, they see the conflict and replay; if it rolled back (e.g. seat taken), the next one proceeds as a fresh attempt.
- **Same key, different body:** a `request_hash` (SHA-256 of `show_id + ":" + sorted seat labels`) is stored with the reservation. On a key hit: same hash → return the original reservation (`201`, `Idempotent-Replayed: true`); different hash → `409 idempotency_key_reused`.
- **Replays move nothing:** no new row, no quota change, no seat change. Test: 50 concurrent retries with one key → 1 created, 49 replayed, 1 row, quota 1.
- **Design choice:** declined attempts are not stored (the transaction rolls back), so retrying a declined key is re-evaluated rather than replaying the decline. For a reservation that is acceptable — a retry after a seat is freed should be allowed to succeed.

---

## 3. Holds & expiry

**Chosen model: confirm immediately + explicit owner-only cancel** (`POST /reservations/{id}/cancel`).

- Cancel flips status only where `id`, `user_id` (from the token) and `status = 'confirmed'` all match. Non-owners get `404` (not `403`, so the existence of another user's reservation is not leaked).
- Seats are freed with `WHERE reservation_id = :id AND status = 'confirmed'`, so a cancel can only release seats it still owns — it can never resurrect a seat that has since been re-booked by someone else. Cancelling twice is a no-op. Quota is returned in the same transaction.

**Why not TTL holds:** the exercise has no payment step, so a hold would only add an expiry path to get right without changing the correctness story. The schema already allows a `held` status so the extension is small:

- add `held_until` to seats; reserve writes `status='held', held_until = now() + interval '5 min'`;
- the claim predicate becomes `status = 'available' OR (status = 'held' AND held_until < now())`, so an expired hold is reclaimed atomically by the next buyer (no sweeper required for correctness);
- a `confirm` endpoint flips `held → confirmed` with `WHERE reservation_id = ? AND status = 'held' AND held_until >= now()`;
- a background sweeper only tidies up and fixes the gauges.

---

## 4. Consistency vs availability under a partition

The system is **CP**. PostgreSQL is the single system of record and every seat decision is made inside it. There is no cache or second store that could disagree.

If the service cannot reach the database:
- `/actuator/health/readiness` includes the `db` check and returns `503 DOWN` — readiness **fails closed**, so a load balancer stops routing traffic. Liveness does not include the DB, so the platform does not restart-loop the app during a DB outage.
- Write requests fail (connection acquisition times out → `503` with `Retry-After`) instead of guessing.

Overselling a seat is worse than turning buyers away for a few seconds, so the service gives up availability rather than correctness. If read availability mattered more, `GET /shows/{id}` could be served from a read replica (possibly stale), but confirmations would still require the primary.

---

## 5. Observability — what I would get paged for at 2am

**What is exposed**
- `/actuator/health/liveness`, `/actuator/health/readiness` (DB-checked, fails closed).
- `/actuator/prometheus`:
  - `reservations_confirmed_total`
  - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_key_reused|seat_contended"}`
  - `reservations_cancelled_total`
  - `seats{status}` and `show_seats{show_id,status}` — read from the database every 2 s, so they reconcile with `GET /shows/{id}`.
  - Hikari pool metrics (`hikaricp_connections_active`, `_pending`, acquire time), HTTP server and JVM metrics.
- Counters are recorded **after** the transaction commits, so they only count real outcomes. The burst script compares counter deltas with the responses it observed (e.g. `+251` confirmed = 251 observed).
- Structured JSON logs (logstash format) in the container; every request gets an `X-Request-Id` (accepted from the client or generated), echoed in the response and attached to every log line via MDC together with `user_id`.

**Page someone (2am-worthy)**
1. Any `5xx` rate above zero for more than a minute.
2. Readiness `DOWN` / database unreachable.
3. Reconciliation drift: `sum(show_seats{show_id=X}) != total_seats`, or a periodic check that finds a seat owned by more than one confirmed reservation. This should be impossible; if it fires, stop sales.
4. `hikaricp_connections_pending` high for minutes, or connection acquire time near the timeout — the next step is 503s.
5. p99 reserve latency far above normal during an on-sale.

**Dashboards, not pages:** `seat_taken` spikes (expected during an on-sale), `seat_contended`, replay rate.

---

## 6. Load results (live)

Burst script: `burst/burst.py` (see README). Fresh 300-seat show; hot-seat storm of 500 users on one seat, 1,500 users on random seats, 200 concurrent same-key retries, one user firing 10 parallel requests, a spoofed `user_id` in the body, then 50 same-key-different-seat requests.

| Concurrency | Requests | 5xx | Hot seat | Double-sold | Reconciled | Throughput | p50 | p99 |
|---|---|---|---|---|---|---|---|---|
| 200 | 2,211 | 0 | 1 / 500 | 0 | 300 / 300 | 46 req/s | 2.9 s | 16.9 s |
| 500 | 2,211 | 0 | 1 / 500 | 0 | 300 / 300 | 41 req/s | 7.1 s | 48.2 s |

All correctness checks passed in both runs, and metrics matched the API.

**Throughput ceiling.** Throughput stays around 45 req/s regardless of concurrency; extra concurrency only lengthens the queue (≈ 500 in flight ÷ 41/s ≈ 12 s average wait). The ceiling comes from a small trial instance, a 15-connection pool, ~6–7 database round trips per reserve, and the fact that ~87% of requests are losers that still do the full write path before rolling back. Runs were made from a single machine in Riyadh against a US-West deployment; at 50+ new TLS connections a few requests occasionally failed to connect on the client side (no HTTP response at all) — the service itself returned 0 5xx in every run.

At ~45 req/s, a 20k-request burst would queue for minutes; requests waiting past the 30 s connection-acquire timeout would get `503` with `Retry-After` rather than an incorrect result. Fast-path declines (section 8.1) are the fix for this.

**Cold start.** The service does not scale to zero on Railway. After a restart or redeploy, Railway's healthcheck holds traffic until `/actuator/health/readiness` reports `UP` (Flyway validation + DB connectivity), so no request reaches an instance that cannot decide seats.

---

## 7. AI usage — directed vs decided

I used Claude (Anthropic) as a pair-programmer throughout, in a chat session.

**What the AI did**
- Proposed the overall design: conditional `UPDATE` as the atomic decision, sorted `FOR UPDATE` for multi-seat, the quota row for the per-user limit, the idempotency-key-first insert with a request hash, and the schema constraints.
- Generated most of the code, tests, the Dockerfile/compose files, the burst script and first drafts of the README and this write-up.
- Helped diagnose environment problems (local Postgres instances occupying ports 5432/5433, Windows reserved ports, Railway variable references, Spring Boot 4 module changes).

**What I directed / decided / verified**
- Build order: naive version first, prove the race with a failing test, then fix — so the history shows the reasoning, not just the answer.
- Technology choices: Java 21 + Spring Boot (what I work in daily), JPA for CRUD with native SQL for the contended path, `.properties` config, Railway.
- Ran every test and every burst myself and read the results; the failing naive test (`wins=10`) and all live numbers are from my runs.
- Did the deployment and debugged it (missing database, empty password reference, deploys not triggering).
- Caught issues during review, e.g. test runs sharing a database (idempotency key collisions between test classes), commit messages that read as staged, and burst checks that were not actually exercising the per-user limit.

**Where I relied on AI more than I would like**
- PostgreSQL locking semantics (EvalPlanQual re-check under READ COMMITTED) and Spring Boot 4 specifics — I verified behaviour through the tests and the live burst rather than from prior knowledge.

---

## 8. What I would do next

1. **Fast-path declines (measured bottleneck).** Most burst requests are losers, yet each inserts a reservation row, bumps the quota, takes locks and rolls back. Check the idempotency key, then `SELECT count(*) ... WHERE status <> 'available'` before any write: a seat already sold is declined with one indexed read and no locks. Winner selection stays with the atomic `UPDATE`. Expect a large drop in p99.
2. **Capacity:** larger instance, pool sized against Postgres `max_connections` (or PgBouncer), multiple app replicas behind the same database (correctness already lives in the DB; counters would then be aggregated per instance).
3. **Holds with TTL + a payment/confirm step** as described in section 3.
4. **Alerting rules** for the signals in section 5, plus a scheduled reconciliation job.
5. **Auth hardening:** replace the open `/auth/token` dev endpoint with a real identity provider (or disable it by flag), and add Spring Security with asymmetric JWT validation.
6. **Per-user rate limiting** at the edge so a single client cannot monopolise the queue during an on-sale.
7. **Bounded metrics cardinality:** only emit `show_seats` for shows currently on sale.
8. **Outbox events** (`reservation.confirmed/cancelled`) for downstream systems (payments, notifications) without dual writes.