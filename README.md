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