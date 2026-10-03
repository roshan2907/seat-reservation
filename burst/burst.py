#!/usr/bin/env python3
"""
On-sale stampede against a live seat-reservation service.

  python burst/burst.py https://your-app.up.railway.app [--seats 300 --hot-users 500 ...]

Phase 1 fires everything at once: hot-seat storm, spread load, concurrent same-key
retries, per-user-limit abuse and a spoofed-identity request.
Phase 2 reuses successful idempotency keys with a different body (must be 409).
Then it verifies the correctness bar and prints the outcome distribution.
"""
import argparse
import asyncio
import random
import statistics
import sys
import time
import uuid
from collections import Counter, defaultdict

import httpx


def parse_args():
    p = argparse.ArgumentParser(description="Seat reservation burst test")
    p.add_argument("base_url")
    p.add_argument("--seats", type=int, default=300, help="seats in the show")
    p.add_argument("--hot-users", type=int, default=500, help="users storming the hot seat")
    p.add_argument("--spread-users", type=int, default=1500, help="users booking random seats")
    p.add_argument("--replays", type=int, default=200, help="concurrent same-key duplicates")
    p.add_argument("--limit-requests", type=int, default=10, help="parallel requests from one user")
    p.add_argument("--concurrency", type=int, default=500, help="max in-flight requests")
    return p.parse_args()


def auth(token):
    return {"Authorization": f"Bearer {token}"}


async def get_token(client, user_id, role="user", attempts=5):
    """Setup helper: retried, because a dropped keep-alive connection is not what we are testing."""
    for attempt in range(attempts):
        try:
            r = await client.post("/auth/token", json={"user_id": user_id, "role": role})
            r.raise_for_status()
            return r.json()["token"]
        except (httpx.HTTPError, KeyError):
            if attempt == attempts - 1:
                raise
            await asyncio.sleep(0.5 * (attempt + 1))


async def gather_limited(coros, limit):
    sem = asyncio.Semaphore(limit)

    async def run(coro):
        async with sem:
            return await coro

    return await asyncio.gather(*(run(c) for c in coros))


async def metrics_snapshot(client):
    try:
        r = await client.get("/actuator/prometheus")
        if r.status_code != 200:
            return {}
    except httpx.HTTPError:
        return {}
    out = {}
    for line in r.text.splitlines():
        if line.startswith("reservations_") and not line.startswith("#"):
            name, value = line.rsplit(" ", 1)
            out[name] = float(value)
    return out


def pct(values, q):
    if not values:
        return 0.0
    if len(values) == 1:
        return values[0]
    return statistics.quantiles(values, n=100)[q - 1]


async def main():
    args = parse_args()
    base = args.base_url.rstrip("/")
    run = uuid.uuid4().hex[:6]
    limits = httpx.Limits(max_connections=args.concurrency, max_keepalive_connections=args.concurrency)

    async with httpx.AsyncClient(base_url=base, timeout=60.0, limits=limits) as c:
        # ---------- setup ----------
        ready = await c.get("/actuator/health/readiness")
        print(f"readiness: {ready.status_code} {ready.text[:80]}")

        admin = await get_token(c, f"admin-{run}", "admin")
        labels = [f"S{i}" for i in range(1, args.seats + 1)]
        hot_seat = labels[0]
        # Dedicated seats so the limit and spoof checks are never masked by other buyers.
        limit_seats = labels[-args.limit_requests:]
        spoof_seat = labels[-args.limit_requests - 1]
        others = labels[1:-args.limit_requests - 1]
        r = await c.post("/shows", headers=auth(admin),
                         json={"name": f"burst-{run}", "seats": labels, "price_paise": 25000})
        r.raise_for_status()
        show_id = r.json()["id"]
        reserve_path = f"/shows/{show_id}/reserve"
        print(f"show {show_id}: {args.seats} seats, hot seat {hot_seat}")

        user_ids = [f"u-{run}-{i}" for i in range(args.hot_users + args.spread_users)]
        greedy, spoofer = f"greedy-{run}", f"spoof-{run}"
        all_users = user_ids + [greedy, spoofer]
        t0 = time.perf_counter()
        tokens = dict(zip(all_users, await gather_limited([get_token(c, u) for u in all_users],
                                                          min(args.concurrency, 50))))
        print(f"minted {len(tokens)} tokens in {time.perf_counter() - t0:.1f}s")

        metrics_before = await metrics_snapshot(c)

        # ---------- phase 1 plan ----------
        plan = []
        for u in user_ids[:args.hot_users]:
            plan.append(("hot", u, {"seats": [hot_seat], "idempotency_key": f"hot-{u}"}))

        spread = []
        for u in user_ids[args.hot_users:]:
            body = {"seats": random.sample(others, k=random.choice([1, 1, 2])), "idempotency_key": f"sp-{u}"}
            spread.append((u, body))
            plan.append(("spread", u, body))

        for u, body in random.sample(spread, min(args.replays, len(spread))):
            plan.append(("replay", u, dict(body)))  # identical body, same key, fired concurrently

        for i, seat in enumerate(limit_seats):
            plan.append(("limit", greedy, {"seats": [seat], "idempotency_key": f"g-{i}"}))

        plan.append(("spoof", spoofer, {"seats": [spoof_seat], "idempotency_key": "spoof-1",
                                        "user_id": "someone-else"}))
        random.shuffle(plan)

        gate = asyncio.Event()
        sem = asyncio.Semaphore(args.concurrency)

        async def fire(kind, user, body):
            await gate.wait()
            async with sem:
                start = time.perf_counter()
                try:
                    resp = await c.post(reserve_path, json=body, headers=auth(tokens[user]))
                    status = resp.status_code
                    try:
                        data = resp.json()
                    except ValueError:
                        data = {}
                    replayed = resp.headers.get("idempotent-replayed") == "true"
                except httpx.HTTPError as e:
                    status, data, replayed = 0, {"code": type(e).__name__}, False
                return {"kind": kind, "user": user, "body": body, "status": status, "data": data,
                        "replayed": replayed, "ms": (time.perf_counter() - start) * 1000}

        print(f"\nphase 1: firing {len(plan)} requests (concurrency {args.concurrency}) ...")
        tasks = [asyncio.create_task(fire(*p)) for p in plan]
        await asyncio.sleep(0.2)
        t0 = time.perf_counter()
        gate.set()
        results = await asyncio.gather(*tasks)
        elapsed = time.perf_counter() - t0

        # ---------- phase 2: same key, different body ----------
        ok_spread = [r for r in results if r["kind"] == "spread" and r["status"] == 201]
        reuse_plan = []
        for r in random.sample(ok_spread, min(50, len(ok_spread))):
            other = random.choice([s for s in others if s not in r["body"]["seats"]])
            reuse_plan.append(("key_reuse", r["user"],
                               {"seats": [other], "idempotency_key": r["body"]["idempotency_key"]}))
        results += await asyncio.gather(*(fire(*p) for p in reuse_plan))

        await asyncio.sleep(3)  # let seat gauges refresh (2s interval)
        state = (await c.get(f"/shows/{show_id}")).json()
        metrics_after = await metrics_snapshot(c)

    # ---------- report ----------
    print(f"\n=== OUTCOMES (phase 1 in {elapsed:.2f}s, {len(plan) / elapsed:.0f} req/s) ===")
    dist = Counter((r["status"], r["data"].get("code", "replayed" if r["replayed"] else "ok")) for r in results)
    for (status, code), n in sorted(dist.items()):
        print(f"  {status:>3} {code:<26} {n}")
    server_errors = [r for r in results if r["status"] >= 500 or r["status"] == 0]
    lat = sorted(r["ms"] for r in results if r["status"] > 0)
    print(f"  latency ms: p50={pct(lat, 50):.0f}  p95={pct(lat, 95):.0f}  p99={pct(lat, 99):.0f}")

    checks = []

    def check(name, ok, detail=""):
        checks.append(ok)
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")

    print("\n=== CORRECTNESS ===")
    check("zero 5xx / transport errors", not server_errors, f"(got {len(server_errors)})")

    hot_wins = [r for r in results if r["kind"] == "hot" and r["status"] == 201]
    hot_409 = [r for r in results if r["kind"] == "hot" and r["status"] == 409]
    check(f"hot seat {hot_seat}: exactly one winner", len(hot_wins) == 1,
          f"(winners={len(hot_wins)}, 409s={len(hot_409)} of {args.hot_users})")

    reservations = {}
    for r in results:
        if r["status"] == 201:
            reservations[r["data"]["reservation_id"]] = r["data"]
    seat_owners = defaultdict(set)
    for res in reservations.values():
        for s in res["seats"]:
            seat_owners[s].add(res["reservation_id"])
    double_sold = {s: ids for s, ids in seat_owners.items() if len(ids) > 1}
    check("no seat sold twice", not double_sold, f"(double-sold seats={len(double_sold)})")

    counts = state["counts"]
    total = counts["available"] + counts["held"] + counts["confirmed"]
    check("available + held + confirmed == total_seats", total == state["total_seats"] == args.seats,
          f"({counts['available']} + {counts['held']} + {counts['confirmed']} = {total} / {state['total_seats']})")
    check("confirmed seats in API == seats in 201 responses", counts["confirmed"] == len(seat_owners),
          f"(api={counts['confirmed']}, responses={len(seat_owners)})")

    greedy_res = {r["data"]["reservation_id"] for r in results if r["kind"] == "limit" and r["status"] == 201}
    greedy_limit = sum(1 for r in results if r["kind"] == "limit" and r["data"].get("code") == "per_user_limit")
    expected_wins = min(4, args.limit_requests)
    check("per-user limit (exactly 4 of N parallel requests on free seats)",
          len(greedy_res) == expected_wins and greedy_limit == args.limit_requests - expected_wins,
          f"(won={len(greedy_res)}, per_user_limit declines={greedy_limit} of {args.limit_requests})")

    by_key = defaultdict(set)
    for r in results:
        if r["status"] == 201 and r["kind"] in ("spread", "replay"):
            by_key[(r["user"], r["body"]["idempotency_key"])].add(r["data"]["reservation_id"])
    multi = {k: v for k, v in by_key.items() if len(v) > 1}
    replays = sum(1 for r in results if r["replayed"])
    check("same key -> one reservation", not multi,
          f"(keys with >1 reservation={len(multi)}, replays served={replays})")

    reuse = [r for r in results if r["kind"] == "key_reuse"]
    reuse_ok = all(r["status"] == 409 and r["data"].get("code") == "idempotency_key_reused" for r in reuse)
    check("same key + different seats -> 409", reuse_ok, f"({len(reuse)} attempts)")

    spoof = next(r for r in results if r["kind"] == "spoof")
    if spoof["status"] == 201:
        check("identity comes from token, not body", spoof["data"]["user_id"] == spoofer,
              f"(body said someone-else, reservation user={spoof['data']['user_id']})")
    else:
        print(f"  [SKIP] spoof check (request declined: {spoof['data'].get('code')})")

    print("\n=== METRICS DELTA (/actuator/prometheus) ===")
    if metrics_after:
        for name in sorted(metrics_after):
            delta = metrics_after[name] - metrics_before.get(name, 0.0)
            if delta:
                print(f"  {name}  +{delta:.0f}")
        confirmed_delta = sum(v - metrics_before.get(k, 0.0) for k, v in metrics_after.items()
                              if k.startswith("reservations_confirmed_total"))
        check("metrics: confirmed counter delta == confirmed reservations",
              int(confirmed_delta) == len(reservations),
              f"(metric +{confirmed_delta:.0f}, observed {len(reservations)}; assumes no other traffic)")
    else:
        print("  /actuator/prometheus not reachable")

    print(f"\nRESULT: {'ALL CHECKS PASSED' if all(checks) else 'SOME CHECKS FAILED'}")
    sys.exit(0 if all(checks) else 1)


if __name__ == "__main__":
    asyncio.run(main())