# Seat Reservation Service

Assigned-seat booking API built for correctness under high concurrency:
no double-sell, per-user limits, idempotent retries.

## Run locally
```bash
docker compose up --build
```
- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- Postgres is exposed on host port **15432** to avoid clashing with local Postgres installs.

## Status
Work in progress.

## Live
- Base URL: https://seat-reservation-production-fe29.up.railway.app
- Health: https://seat-reservation-production-fe29.up.railway.app/actuator/health
- Hosted on Railway: Dockerfile build + managed Postgres; DB credentials and JWT secret injected as environment variables.
## Burst test (one command)
```bash
./burst/burst.sh https://seat-reservation-production-fe29.up.railway.app
# Windows: pip install -r burst/requirements.txt && python burst/burst.py <BASE_URL>
```
Creates a fresh show, then fires a hot-seat storm (500 users on one seat), spread load,
concurrent same-key retries, per-user-limit abuse and a spoofed identity at the same time,
followed by same-key-different-body reuse. Prints the outcome distribution and p50/p95/p99,
then verifies: exactly one winner on the hot seat, zero 5xx, no double-sell,
`available + held + confirmed == total`, per-user limit, idempotency, token-derived identity,
and that Prometheus counters match. Exits 1 if any check fails.

Options: `--seats`, `--hot-users`, `--spread-users`, `--replays`, `--concurrency`.
Latest live run: [`burst/last-run.txt`](burst/last-run.txt).

To run it from the cloud: GitHub → Actions → **burst** → Run workflow (uses `.github/workflows/burst.yml`).