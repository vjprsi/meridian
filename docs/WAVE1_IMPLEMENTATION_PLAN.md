# Wallet Edition — Wave 1 implementation plan

> **Status:** Design complete (2026-11-03) · **Scope:** the four MVP gap-closures in Wave 1 of `S23`
> (`spec/plan.yaml`). Each section is implement-ready: spec-first additions, service logic, tests, ordering, risks.
> Produced by four parallel architect passes over the real services. See `docs/EDITIONS_AND_MOBIQUITY_ALIGNMENT.md`
> for the gap register and `docs/research/MOBIQUITY_PAY_V12_FDD.md` for the `MQ-*` requirement traces.

## Cross-cutting findings & sequencing

1. **Land `WE-HIER-1` first.** Configurable Domain→Category→Grade is the dimension that `WE-LIMIT-1` (TCP keys) and
   `WE-PRICE-1` (pricing/commission conditions) consume. All three live in the `wallets` domain.
2. **`WE-PRICE-1` placement correction (verified in code):** mobile-money **fees** are computed in **wallet-service**
   (`FeeCalculator` over `wallet_fee_rule`) and **commissions** in **commission-service** (`CommissionEngine` over
   `commission_rule`). `pricing-rules-service` (`product` domain) is a generic DMN engine for loan/eligibility/routing
   and is **not** on the money path. ⇒ `WE-PRICE-1` lands in `spec/domains/wallets.yaml`, **not** `product.yaml`.
   (Update the `WE-PRICE-1` task's `domain/service` accordingly.)
3. **All four are additive & spec-first:** edit `spec/domains/*.yaml` → `python tools/generate.py` → implement the
   generated Spring interfaces. New columns are nullable/defaulted and new matcher conditions treat empty = "any", so
   the seeded `SP-*` fee rules, `SP-AGENT-STD` commissions, fixed agent levels and existing journeys keep producing
   identical results (regression gate = re-run the current ITs + journey D).
4. **Maker-checker** for every new config/rule table goes through `libs/meridian-approvals` + workflow-service with a
   new `ApprovalOutcomeHandler` per resource type (clone the existing handlers), `allow_self_approval:false`, and
   registration in workflow-service approval-owners (SEC-03). **Money moves only** via wallet-service transactions
   (one wallet side; `counterparty_ledger_account_id` for the GL side) or, for GL↔GL, ledger-service
   `POST /v1/journal-entries`. Idempotent retry on 5xx (keep REQUESTED/RUNNING, stable transfer keys).

Recommended order: **WE-HIER-1 → WE-LIMIT-1 → WE-PRICE-1 → WE-FLOAT-1** (FLOAT is independent of HIER and can run in
parallel; it only needs the e-money accounts already seeded by the WALLET pack).

---

## WE-HIER-1 — Configurable Domain → Category → Grade (G-06)

**Service/domain:** agent-network-service, `wallets`. **Closes:** MQ-HIER-1..3, MQ-DOM-1/2, MQ-GRADE-1/2.

**Spec (`wallets.yaml`):**
- Enums: `HierarchyConfigStatus` (DRAFT…RETIRED), `BearerRole [PAYER,PAYEE,INITIATOR]`, `ServiceClass [FINANCIAL,NON_FINANCIAL]`, `NonFinancialService [...]`. Keep `AgentLevel` (becomes a compat shadow).
- Tables: `distribution_domain` (ddm), `domain_category` (dct; n-level via `parent_category_id`, derived `level`, one owner=level 1), `category_service_preference` (csp; payer/payee/initiator service map), `grade` (grd; priority, `is_unregistered`, `default_commission_plan_id`), `grade_service` (grs). All with maker-checker columns + `approval_request_id`.
- FK columns (nullable ⇒ non-breaking): `agent.{distribution_domain_id,category_id,grade_id}`, `wallet.{grade_id,category_id}`, `merchant.{grade_id,category_id}`, `commission_rule.{category_id,grade_id}` (keep `agent_level` empty=any).
- APIs: `distribution-domains`, `domain-categories` (+`/{id}/categories` tree, `/service-preferences`), `grades` (+`/unregistered`, `/{id}/services`), `grade-services`, `category-service-preferences`; extend `agents` with `POST /{id}/assign-grade` and `POST /{id}/move` (single move; bulk `POST /move-bulk` is fast-follow). Events: domain/category/grade activated/retired, `agent.grade_assigned`, `agent.hierarchy_moved`.

**Logic:** `HierarchyConfigService` + `GradeService` (mirror `AgentService`/`CommissionPlanService`): immutable codes, derive level, cycle guard, one `is_unregistered` grade/tenant, grade-delete blocked when referenced. Rewrite `domain/Hierarchy.java` to validate parent by category chain (not the hardcoded SUPER_AGENT/AGENT/SUB_AGENT switch) with a compat path mapping the 3 legacy levels onto seeded factory categories. `GradeResolver` (cache ~30 s; resolve-by-agent/wallet/unregistered) is what pricing/limits/commission call. Factory seeding of the "Distribution" domain + standard categories/grades + the **un-registered grade** goes in the WALLET starter pack (plus a lazy bootstrap so `/grades/unregistered` never 404s on the hot path).

**Migration:** backfill `agent.{domain,category,grade}` from `agent_level`; keep `agent_level` as a derived shadow so commission/EOD and journey D stay green.

**Tests:** owner/level/cycle rules, singleton un-registered grade, four-eyes, `HierarchyConfigFlowIT`, `AgentHierarchyMigrationIT`, starter-pack idempotency, e2e create+approve domain/category/grade and assign to agents.

**Critical files:** `spec/domains/wallets.yaml`; `agent-network-service/.../domain/Hierarchy.java`, `.../application/AgentService.java`; `starter-packs/wallet-2026.11.0.yaml`.

---

## WE-LIMIT-1 — Two-tier Transfer Control Profile (G-04)

**Service/domain:** limits-service, `risk`. **Closes:** MQ-TCP-1..7, MQ-TR-1..3.

**Key design (reuse, don't fork):** the existing `LimitEngine.select()` already groups rules by counting subject, applies most-specific/lowest-priority within a group, never supersedes across *different* subjects, and never supersedes regulatory rules — exactly the Rank-1 (cumulative) vs Rank-2 (instrument) "both apply" semantics. So model the two tiers + transfer matrix as **authoring tables with maker-checker**, **compile activated profiles into in-memory `LimitModel.Rule`s** (deterministic UUID v5 ids so counters are stable across refreshes), and **reuse `limit_usage` as the one counter store** (extended with `direction`). Cross-account-group aggregation is free by counting against the **regulatory-profile (KYC) id** as subject.

**Spec (`risk.yaml`):** enums `TransferDirection`, `TcpTier`, `TcpRuleMode [RULE1..3]`, `PaymentInstrumentType`, `TransferRuleStatus`, `HierarchyScope`; extend `RiskLimitScope` with `REGULATORY_PROFILE, INSTRUMENT_GRADE`. Tables `transfer_control_profile` (tcp; cumulative, per regulatory_profile/currency/domain/category/direction, D/W/M/Y amount+count, max_balance, `yearly_enabled`, `services[]`), `instrument_tcp` (itc; per instrument+grade+service+bearer, per-txn min/max, min remaining balance, max %, amount multiple, windows), `transfer_rule` (trf; sender/receiver domain/category/grade/instrument/account/bank, `hierarchy_scope`, allow/deny, min/max/multiple) — all maker-checker. **Extend `limit_usage`** with `direction`, `tier`, `source_profile_id`. APIs under `/v1/risk/limits/tcp/cumulative`, `/tcp/instrument`, `/transfer-rules` (+`/evaluate`); extend `limit-checks` request with `direction`, `regulatory_profile_code`, `domain/category/grade_code`, `payment_instrument`, and a **`payee` credit block** (today only the payer/debit side is reserved — a real gap).

**Logic:** `TcpCompiler` in `LimitRuleCache` expands profiles into synthetic windowed rules; extend `LimitEngine.matches/specificity/evaluate` for direction + dimensions + `max_transfer_pct` (vs current_balance) + `amount_multiple` + `min_remaining_balance`. Rank-2 ⊆ Rank-1 enforced at **activation validation** (not runtime). Credit-side reservation added to `LimitCheckService`/`LimitStore` in the existing single-statement reserve/commit (no new hot path). Callers (`payment-service RiskClient`, `wallet-service Downstream`) send the new fields behind existing flags.

**wallet-tier-limit relationship:** `wallet_tier_limit` is a per-wallet subset of instrument-TCP; cumulative-TCP is new. Migrate each tier-limit → an `instrument_tcp` row; keep wallet-service local enforcement as a non-counting pre-check during cut-over (authoritative reserve stays central) to avoid double-counting.

**Risks:** deterministic synthetic ids (else counters orphan on refresh), no double-counting, hot-path latency (keep compiled rules cached, all counter keys in one round-trip), regulatory_profile_code must be supplied (degraded fallback to party id, never silently fail-open on regulatory limits).

**Critical files:** `spec/domains/risk.yaml`; limits-service `domain/LimitEngine.java`, `application/{LimitCheckService,LimitRuleCache}.java`, `infrastructure/LimitStore.java`; `wallet-service/.../Downstream.java`, `payment-service/.../RiskClient.java`; starter packs.

---

## WE-PRICE-1 — Pricing-engine extensions (G-05)

**Service/domain:** wallet-service (fees) + commission-service (commissions), both `wallets` (NOT `product`/pricing-rules — see cross-cutting note 2). **Closes:** MQ-PE-7/11/13/14/16/18.

**Spec (`wallets.yaml`):** enums `SlabMode [BAND,TELESCOPIC]`, `ChargeScope [INSTRUMENT,CUMULATIVE]`, `AccumulationPeriod`, `ChargeDirection`, `RemoteTxnType [ONSITE,OFFSITE,NOT_CLASSIFIED]`, `MilestonePeriod`, `JoiningMilestone`, `InactiveRewardTrigger`; extend `CommissionBeneficiary` with `AGGREGATOR,SERVICE_PROVIDER`.
- **Telescopic (PE-7):** add `tier_mode`, `charge_scope`, `accumulation_period`, `charge_direction` to **both** `wallet_fee_rule` and `commission_rule` (reuse existing `tiers[]`; TELESCOPIC reinterprets them progressively; default BAND = today).
- **Milestone (PE-18):** add `milestone_from_nth`, `milestone_to_nth`, `milestone_period`, `milestone_count_scope` to both rule tables.
- **Onsite/offsite (PE-16):** add `cell_id`, `lac_id`, `remote_txn_type` to `wallet_transaction` (+ on `/cash-in`,`/cash-out`,`/quote` bodies) and `cell_id`,`lac_id` to `agent_outlet`; `remote_txn_type` condition on both rule tables; block policy is per-tenant config.
- **Joining commission (PE-11):** tables `joining_commission` (register/first-cash-in/first-debit amounts, split payout) + `customer_milestone_state` (one row/wallet; each `*_earning_id` set-once ⇒ fire-once).
- **Inactive-customer (PE-13):** `commission_rule` gains `inactive_customer_only`, `inactive_days`, `reward_trigger`, `reward_delay_days`; new `pending_commission_reward` table for deferred release (EOD `POST /pending-commission-rewards/release-due`).
- **Commission sharing (PE-14):** `commission_earning` gains `share_group_id`, `ledger_entry_id`, `share_role`; model Merchant→Aggregator→SP as commission_rule rows whose `beneficiary_share` sums to 1; post CP/CR journal at accrual, full reversal via clawback.
- Extend `commission-earnings POST /accrue` + `/simulate` with `remote_txn_type`, `txn_count_{daily,weekly,monthly,lifetime}`, `payee_*`, `cumulative_amount_before` (commission-service has no wallet_db — wallet-service supplies these on the event/payload).

**Logic:** add a `tier_mode` branch to `FeeCalculator` and `CommissionEngine` (BAND unchanged; TELESCOPIC = sum of per-slice charges; CUMULATIVE = f(after)−f(before)); milestone gating via count-in-window (+1 specificity so it beats generic on the Nth txn). `MilestoneService` (commission-service) tracks REGISTER (on wallet-activation/PIN event) / FIRST_CASH_IN / FIRST_DEBIT under a per-wallet advisory lock. Inactive rewards: IMMEDIATE accrue / AFTER_FIRST_DEBIT + AFTER_DURATION via `pending_commission_reward`. Sharing: resolve merchant chain, split by share, post one balanced CP/CR journal via a new `Downstream.postJournal` (idempotent key `share:<txn>`), reverse the whole `share_group_id` on clawback. Onsite/offsite classified in wallet-service `prepare()` against the outlet's cell/lac; optional config block; threaded into fee context + accrue payload + event.

**Risks:** matcher regressions (null condition = any, `tier_mode` null = BAND — guard with parity tests on seeded rules); cross-service enrichment via additive optional payload fields; CP/CR adds a synchronous ledger call only for share-enabled rules (isolate + idempotent); Nth-txn concurrency (advisory lock). Confirm a wallet-activation/PIN event exists for REGISTER and that `wallet.opened_by_agent_id` identifies the registering business user.

**Critical files:** `spec/domains/wallets.yaml`; wallet-service `domain/FeeCalculator.java`, `application/{Tariffs,TransactionEngine}.java`; commission-service `domain/CommissionEngine.java`, `application/AccrualService.java` (+ `Hierarchy`, `Downstream`, new `MilestoneService`).

---

## WE-FLOAT-1 — Operator stock / e-money float (G-02)

**Service/domain:** agent-network-service, `wallets`. **Closes:** MQ-STK-1..14. **Depends on:** the WALLET pack's e-money accounts (2700 trust, 2710 suspense, 2720 unclaimed, 1150 bank clearing) and the treasury wallet (= IND01 capital pool).

**Spec (`wallets.yaml`):** extend `FloatRequestType` with `ALLOCATE` (O2C capital/parent→business-user float) and `DEALLOCATE` (float→trust) — reuse `agent_float_request` + `FloatService` + its approval policy. New operator-level tables: `stock_operation` (sto; STOCK_CREATION/REIMBURSEMENT, instrument CASH/CHEQUE/DD/BANK_TRANSFER), `stock_sweep_run`/`stock_sweep_line` (auto-deallocation batch), `unclaimed_settlement_run`/`unclaimed_settlement_line` (aging IND02→IND06→IND01/IND03). APIs `stock-operations`, `stock-sweep-runs` (+`/plan`,`/{id}/lines`), `unclaimed-settlements` (+`/plan`), all maker-checker. Events `stock_operation.completed/rejected`, `stock_sweep_run.completed`, `unclaimed_settlement.completed`.

**Logic / postings:** `Downstream` gains `walletToGl(...)` (one wallet side + GL counterparty) and `postJournal(...)` (GL↔GL via ledger-service). `StockOperationService` (mirrors `FloatService` retry/idempotency): **stock creation** DEBIT 1150 / CREDIT capital wallet; **reimbursement** float→capital then capital→1150. `ALLOCATE`/`DEALLOCATE` added to `FloatService` switch (ALLOCATE = capital/parent→child float, same-super-agent-network via `Hierarchy.root`; DEALLOCATE = float→trust 2700). `StockSweepService` builds a PENDING_APPROVAL run of float wallets above min-residual, approve → per-line float→2700 (idempotent per line); scheduler builds the plan but a checker still approves before money moves. `UnclaimedSettlementService` ages suspense and posts 2710→2720→(2700|IND03) journals. Three new `ApprovalOutcomeHandler`s (`stock_operation`, `stock_sweep_run`, `unclaimed_settlement`) + policies cloned from `agent_float_request`.

**Risks:** confirm the exact DEBIT/CREDIT per FDD §10 fund-flow matrix (MQ-STK-14) with finance (highest risk; keep account roles as config keys); idempotent retry (5xx keeps REQUESTED/RUNNING); GL↔GL only via ledger journal entries (wallet-service rejects two-GL-side txns); four-eyes everywhere; scheduler gated + per-tenant + plan-only (money never moves unattended); min-residual precedence `agent_float.min_balance`.

**Critical files:** `spec/domains/wallets.yaml`; agent-network-service `application/FloatService.java`, `infrastructure/Downstream.java`; `starter-packs/wallet-2026.11.0.yaml` (+ generic approvals step); `tools/e2e/journey_d_wallet.py`.

---

## Shared next steps after implementation
- `python tools/generate.py` (DDL/OpenAPI/AsyncAPI), then per-module build + targeted tests; regenerate helm + editions (`tools/helm_chart.py`, `tools/editions.py`).
- Extend `tools/e2e/journey_d_wallet.py` (or new `journey_e_stock.py`) and run the live wallet journeys on the stack (supervised — heavy).
- Regression gate: existing limits/commission/agent ITs + journey D unchanged (all new behaviour is opt-in via defaults).
