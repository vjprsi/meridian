# Meridian Wallet Edition — Wallet Core Completion Summary

Date: 2026-11-04. Branch: `wallet-edition` (tip `baf6dbd`, off `main` 442db32). Not yet merged to main / pushed.

The Wallet Edition (mobiquity Pay-equivalent e-money / mobile-money core, built as a deployment profile of the one
Meridian codebase — see `docs/EDITIONS_AND_MOBIQUITY_ALIGNMENT.md`) is **code-complete across the full MVP**
(G-01…G-10), with every open finance/design decision resolved and wired. The only outstanding step is a mechanical
artifact regeneration that is blocked by host-hardware instability (below).

## MVP goals — all implemented & merged

| Goal | Feature | Service | Highlights |
|------|---------|---------|-----------|
| G-01 | Wallet starter pack | tenant-service | IND-series e-money system accounts (trust 2700 / suspense 2710 / unclaimed 2720 / churn / rewards / escheatment 2760) as ledger liabilities |
| G-02 | Operator stock / e-money float | agent-network-service | stock/sweep/unclaimed tables, ALLOCATE/DEALLOCATE, O2C via capital pool (IND01) |
| G-03 | Unregistered / voucher flows | wallet-service | value in suspense vs hashed one-time passcode; issue / claim-on-registration / expiry rollback |
| G-04 | Two-tier Transfer Control Profiles | limits-service | cumulative KYC + instrument/grade TCP compiled into LimitEngine |
| G-05 | Pricing-engine extensions | wallet-service / commission-service | telescopic, joining, cash-in bonus, hierarchy commission sharing, onsite/offsite, milestone |
| G-06 | Domain/Category/Grade hierarchy | agent-network-service | configurable dimension keyed by limits + pricing |
| G-07 | Agent passcode / ATM OTP / auth-code cash-out | wallet-service | hashed one-time codes, suspense holds, auto-rollback scheduler |
| G-08 | Order & Pay (ETM) + ambiguous settlement | payment-service | order/payment/fulfilment segregation, retry/wait/roll-forward/rollback off a PG heartbeat, green/yellow/red alerts |
| G-09 | Transaction correction + dispute | case-management-service | maker-checker correction via the wallet REVERSAL path; GSMA-aligned dispute tracker (reason codes, evidence, SLA, repeat-claimant) |
| G-10 | Trust / e-money safeguarding recon | reconciliation-service | daily EOD `A − (B+C+D+E) = 0`, typed breaks, critical shortfall alarm |

Enterprise posture (unchanged platform baseline, now covering the wallet core): Java 25 / Spring Boot 4, ~67
microservices, spec-first (one `spec/` → generated DB/OpenAPI/AsyncAPI/routes/realm/Helm), double-entry ledger with
append-only outbox, maker-checker approvals, per-tenant RLS, cell-based routing, Helm + ArgoCD, edition-as-profile
packaging (Banking 60 services / Wallet 51). Every Wallet-Edition addition is **additive, per-tenant gated, default =
current behaviour**, so the Banking Edition is untouched.

## Decisions resolved (D-ED-07…14)

Full rationale + external sources in `docs/WALLET_CORE_DECISIONS.md`; registered in `spec/plan.yaml`. Governing
principle: **e-money safeguarding** — customer value is a liability backed 1:1 in a trust account, reconciled daily,
never recognised as issuer income/capital except by lawful escheatment. Highlights: unclaimed stays a liability with
an **ESCHEATMENT** default (not capital/income); fee+tax refunded on unredeemed auto-rollback; ATM can settle to a
daily-settled clearing GL; ETM roll-forward parks unconfirmed exposure in a reconciling suspense and RED auto-opens a
repair; commission posts one earning leg per hierarchy beneficiary.

## The one remaining step (blocked by hardware, not by code)

`generated/` and `tracker/` are **stale** against the `baf6dbd` spec and must be regenerated:
`bash scratchpad/regen.sh 15` (→ `tools/generate.py`, `keycloak_realm.py`, `helm_chart.py`, `editions.py`). The
Keycloak realm will legitimately change this batch (payment-orders, wallet-disputes, wallet-corrections,
safeguarding-* are new resources). After regen, the five hand-written `@RestController`s can implement their generated
`*Api` interfaces (no behaviour change), then commit "mark Done after merge".

**Why it is not done yet:** the host machine is thermally/computationally unstable — `tools/generate.py` (heavy
PyYAML/openpyxl C-extension work) crashed on **15/15** retries with a *changing* failure mode (segfault / scrambled
Python builtin / launch failure), across both Git Bash and PowerShell and even with the pure-Python YAML loader. This
is hardware corruption, not a code bug: `git fsck` stayed clean throughout and targeted Gradle unit tests kept passing.
The services still **compile** without the regen. Run `regen.sh` once the machine has been rested / thermally checked.

## Also still pending (needs a healthy machine)
- Docker integration tests for all Wave 2 features (Docker was down; unit/policy tests are green).
- `tools/e2e/journeys.py` extension with the 12 wallet journeys (WE-PKG-3 follow-up).
- Merge `wallet-edition` → `main` and push.

## Safety
`git fsck` clean throughout; every merge FF-only; safety bundle + `wave-backup` tag kept current at each step. No commit
or object was ever corrupted despite the hardware instability.
