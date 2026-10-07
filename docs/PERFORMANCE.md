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

### Re-run with payment references (RECON-REAL-REFS, 2026-10-07)

Journal entries and ledger entries gained ten typed payment-reference columns (+ `entry_type` on ledger entries)
and seven partial indexes on `journal_entry` (`WHERE <ref> IS NOT NULL`, so postings without references add no index
entries); references are written by the existing journal / line inserts (no extra round trip), the counterparty
account is pseudonymised in memory (HMAC-SHA-256). Measured A/B on the same machine and session: a fresh `ledger_db`
per run, ledger-service started like `tools/dev.py` with `MERIDIAN_DB_POOL=64`, a 30 s warm-up run (not recorded),
then the 60 s measured run (`--seconds 60 --concurrency 64 --accounts 200`); baseline = jar built from `81007ef`
(before the change). The machine was shared with other agents' Testcontainers suites during part of the session
(CPU 10-70% outside the test), which is why absolute numbers are below the 1,792/s of the earlier run.

| Build | Throughput | p50 / p95 / p99 / max (ms) | Integrity check after run (entries) |
| --- | --- | --- | --- | --- |
| baseline `81007ef` (busy machine) | 1,320/s | 34.3 / 121.1 / 185.9 / 590.2 | passed (84,833) |
| baseline `81007ef` | 1,426/s | 35.2 / 107.2 / 163.6 / 468.3 | passed (91,810) |
| payment refs, postings without refs | **1,478/s** | 36.7 / 97.7 / 140.6 / 440.7 | passed (94,601) |
| payment refs, postings without refs | **1,461/s** | 35.0 / 101.5 / 151.0 / 577.5 | passed (93,784) |
| payment refs, **every posting with full refs** (`--with-refs true`) | 1,305/s | 35.5 / 124.6 / 191.8 / 524.8 | passed (84,529) |
| payment refs, every posting with full refs | 1,317/s | 43.0 / 109.6 / 161.4 / 450.6 | passed (85,135) |

0 errors in every run; every post-run integrity verification passed (0 balance / version / balancing / hash
mismatches). No regression for postings without references (1,461-1,478/s vs baseline 1,320-1,426/s, within
run-to-run noise); a posting carrying all ten references costs ~10% throughput in this setup (three wider rows and
up to seven partial-index entries per posting), still above the G1 target. In an earlier warm-JVM pass of the same
session (no fresh DB, second/third run of one process) the branch reached 2,733/s with refs and 2,420/s without,
which shows the size of machine noise here. Postings with refs: `--with-refs true` (UETR, end-to-end id,
instruction id, bank / scheme references, counterparty name + account, purpose).

Changes that took throughput from 798 → 1,782/s: Hikari pool 20 → 64; batched entry inserts
(`json_populate_recordset`), single `UPDATE … FROM unnest(...)` for balances, response assembled from
`RETURNING` rows, batched outbox writes.

Next: per-cell target is 10,000 TPS (NFR). Levers: horizontal service replicas, Postgres on dedicated hosts
(no WSL relay), account-level sharding of the ledger database, prepared statements, removing the post-insert
balance read for events.

## S19 — daily reconciliation auto-match rate (2026-10-07)

Exit criterion: daily reconciliation auto-match rate above 98%. Measured on the reconciliation-service matching
engine (`MatchingEngine` with the standard rule set `RuleSet.standard()`, the template every new rule set starts
from) against a deterministic synthetic day range with known ground truth.

| Setting | Value |
| --- | --- |
| Test | `./gradlew :services:reconciliation-service:test --tests "*ReconBenchmarkTest"` (prints the tables below) |
| Generator | `ReconBenchmarkGenerator` (test helper), seed 20261006: 10,000 statement lines, 13,061 internal items over 6 value days, EUR / USD / GBP |
| Scenarios | UETR, end-to-end id, instruction id, bank reference, remittance-only invoice / RF references in varied formats, truncated end-to-end ids, no reference (counterparty name only, with typos / truncation / initials / legal-form changes / reordering), fee deductions (charges stated or not), FX differences (same currency, or a foreign-currency line carrying the instructed amount), value-date shifts, supplier batches (1 line : 3-12 items), card scheme settlements net of 0.3-1.8% interchange (1 : 5-30), bulk receipts split by the bank (2-4 lines : 1), reversals (statement-only, ledger-only, both sides), duplicate lines, bank-only lines, in-flight items, same-amount decoys and identical twins |
| Hardware | developer laptop (Windows, JDK 25), single thread |

| Result | Value |
| --- | --- |
| Auto-match rate (lines with a counterpart: 9,834) | **98.93%** (9,729 lines) |
| Auto-match rate over all 10,000 lines (incl. genuine breaks: bank-only lines, duplicates) | 97.29% |
| False auto-matches | **0** (precision 1.0000 for every rule) |
| Suggested for review | 70 lines (twins, unflagged reversals, low-confidence names) |
| Duplicates flagged | 100 (all) |
| Engine time | ~250 ms for 10k lines x 13k items |

Per rule (auto-confirmed proposals; "lines" = statement lines covered):

| rule | auto matches | lines | correct | precision |
|---|---:|---:|---:|---:|
| exact_uetr | 2,804 | 2,804 | 2,804 | 1.0000 |
| exact_end_to_end | 3,097 | 3,097 | 3,097 | 1.0000 |
| exact_bank_ref | 611 | 611 | 611 | 1.0000 |
| batch_keyed (1:n, incl. card nets) | 369 | 369 | 369 | 1.0000 |
| bulk_keyed (n:1) | 103 | 305 | 103 | 1.0000 |
| reversal (pairs) | 161 | 218 | 161 | 1.0000 |
| remittance_ref | 1,251 | 1,251 | 1,251 | 1.0000 |
| fuzzy_exact_amount | 1,005 | 1,005 | 1,005 | 1.0000 |
| fuzzy_fee | 69 | 69 | 69 | 1.0000 |

Recall per scenario: 100% for keyed, remittance, partial-reference, fee, batch, card-net, bulk, date-shift and
duplicate scenarios; fuzzy names 99.5% (4 lines below the 0.90 threshold go to suggestions); FX 99.7%;
statement-only reversals 87.2% (returns without a reversal flag are only suggested: an unflagged opposite booking
may be a genuine refund); identical twins 0% by design (two same-name, same-amount, same-day payments without
references are indistinguishable, so both are suggested, never auto-matched). Seeds 1, 42 and 4711 (5k lines) hold
the same bar (`benchmarkIsDeterministic`).

**With real ledger postings (RECON-REAL-REFS, 2026-10-07).** `ReconRealRefsIT` posts a week of nostro activity the
way payment-service and card-clearing post it (240 outbound settlements, 180 inbound credits, 3 batch-booked bulk
payments of 12 items, 8 pacs.004 returns, 2 scheme net settlements, a credit reversed in the ledger, 3 payments in
flight), each with `reference` + `external_refs`, into a stateful ledger stand-in that stores them like
ledger-service, builds the correspondent's camt.053 from the same payments (all refs / UETR only / clearing reference
only / end-to-end id only / remittance only / NOTPROVIDED with payer name + account / batch booking / bank charges),
imports it and runs the published standard rule set: **433 of 433 matchable lines auto-matched correctly (100%), 0
false matches** (exact_uetr 259, exact_end_to_end 98, exact_bank_ref 32, remittance_ref 20, fuzzy_exact_amount 21 via
the pseudonymised counterparty account, batch_keyed 3, reversal 1); the 5 bank charge lines and the 3 in-flight
payments are raised as breaks, the ledger reversal pair is not.

Precision guards: a candidate is auto-confirmed only at or above the threshold (0.90; 0.93 for fee-tolerant fuzzy)
and when it leads the runner-up by the rule's margin (0.05) for every line and item it covers; subset-sum only
auto-matches a unique solution found within its node budget (equal amounts count as distinct solutions);
duplicates are never matched. Production numbers per run, source and rule are exposed by
`GET /v1/reconciliation/runs/metrics` and on every `ledger.recon_run.completed` / `ledger.recon_eod.completed` event.

Caveat: the synthetic internal items carry what payment-service / card-clearing know (UETR, end-to-end id,
counterparty, batch id). Ledger entries read through ledger-service today carry only the journal `reference` and
the line description, so for nostro sources the exact-key rules match on the reference (end-to-end id / bank
reference) and the name / batch rules only apply once postings carry counterparty and batch data (SPEC_BACKLOG).
