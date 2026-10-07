# Performance results

## Gate G1 — ledger posting throughput (2026-10-06)

| Setting | Value |
| --- | --- |
| Tool | `./gradlew :platform:load-tests:run --args="--seconds 60 --concurrency 64 --accounts 200"` |
| Topology | 1 ledger-service instance (JDK 25, ZGC, virtual threads, Hikari 64) on Windows; PostgreSQL 18 in Docker on WSL2 |
| Workload | Random balanced 2-line transfers between 200 customer accounts, unique Idempotency-Key per request |

| Result | Value |
| --- | --- |
| Postings | 106,892 in 60 s, 0 errors |
| Throughput | **1,782 postings/s** (target 1,000) |
| Latency p50 / p95 / p99 / max | 26.6 / 85.6 / 128.2 / 399.3 ms |
| Integrity check after run | passed: 201 accounts, 111,587 entries, 0 balance/version/balancing/hash mismatches |

### Re-run after security hardening (2026-10-07)

Same setup, after central approvals, business dates, scoped idempotency (method/path/caller fingerprint), reversal
row locks and the jwt-default/security fixes: **1,792 postings/s**, 0 errors, p50/p95/p99/max
28.8 / 80.2 / 117.9 / 336.8 ms, integrity check passed (201 accounts, 113,331 entries, 0 mismatches). No regression:
the new locks are on reversal paths only, not on normal postings.

Changes that took throughput from 798 → 1,782/s: Hikari pool 20 → 64; batched entry inserts
(`json_populate_recordset`), single `UPDATE … FROM unnest(...)` for balances, response assembled from
`RETURNING` rows, batched outbox writes.

Next: per-cell target is 10,000 TPS (NFR). Levers: horizontal service replicas, Postgres on dedicated hosts
(no WSL relay), account-level sharding of the ledger database, prepared statements, removing the post-insert
balance read for events.
