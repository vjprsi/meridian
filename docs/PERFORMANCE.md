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

Precision guards: a candidate is auto-confirmed only at or above the threshold (0.90; 0.93 for fee-tolerant fuzzy)
and when it leads the runner-up by the rule's margin (0.05) for every line and item it covers; subset-sum only
auto-matches a unique solution found within its node budget (equal amounts count as distinct solutions);
duplicates are never matched. Production numbers per run, source and rule are exposed by
`GET /v1/reconciliation/runs/metrics` and on every `ledger.recon_run.completed` / `ledger.recon_eod.completed` event.

Caveat: the synthetic internal items carry what payment-service / card-clearing know (UETR, end-to-end id,
counterparty, batch id). Ledger entries read through ledger-service today carry only the journal `reference` and
the line description, so for nostro sources the exact-key rules match on the reference (end-to-end id / bank
reference) and the name / batch rules only apply once postings carry counterparty and batch data (SPEC_BACKLOG).
