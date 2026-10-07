# Spec & Contract Backlog (from service implementation)

Defects found while implementing services against the generated contracts. Services worked around them;
fixing them in the spec/generator makes the contracts honest. Fix systemic items in `tools/generate.py`
first, then per-domain items in `spec/domains/*.yaml`, then remove the service-side workarounds.

## Systemic (generator)

| # | Defect | Fix | Status |
| --- | --- | --- | --- |
| G1 | `json` columns always generate `type: object`; list-valued fields must be wrapped (`{"levels":[...]}`) | Add `json[]` column type → `array` of any; and `json:<scalar>` where scalar values allowed; migrate list fields | Done (json[] / json*) |
| G2 | Actions declared with response `none` but which must return data (presigned URLs, previews, test-send, summaries, rotate-secret, invoice generate) | Audit every `| none` action; give it a response table or a new `result` schema; reserve `none` for true 204s | Done (object / MIME / table[]; domains migrating) |
| G3 | GET actions missing query params (`?date`, `?from/to`, `?base/quote`, …) | Action notation now supports `?name:type!`; update affected actions | Done (?name:type!) |
| G4 | One-time secrets (API key, client secret, TOTP seed, webhook secret) have no response field | Add `once` column modifier → response-only `writeOnly:false, readOnly:true` field not stored; or explicit `secret_once` schema | Done (response_only) |
| G5 | Missing statuses/events found during implementation (CHANGES_REQUESTED, CANCELLED, PENDING_APPROVAL, reinstate, archive, legal hold, invoice void/overdue, cell/branding events) | Add enum values / events in the domain specs | Open |
| G6 | Shared enums (e.g. `RunStatus`) defined in one domain file but used everywhere | Move cross-domain enums to `spec/domains/_shared.yaml` | Open |
| G7 | Platform-global reference tables (fx_rate, holiday_calendar ownership) RLS semantics unclear | Decide global vs tenant-override model; add `tenant: false` + override tables | Open |
| G8 | Scheduled jobs cannot enumerate tenants under RLS (workaround: tenants seen in idempotency_key) | Business-day-service drives per-tenant jobs; services expose job endpoints; or a tenant registry fed by `platform.tenant.*` events | Done (business-day-service, see note) |

**G8 note.** business-day-service ships a versioned *standard EOD job pack* (`eod/standard-pack.json`, 53 jobs in banking
order, checked against `generated/routes.json` and the per-service OpenAPI by `StandardJobPackTest`), installed per tenant
with `POST /v1/operations/eod-jobs/install-standard-pack` (idempotent upgrade; tenant-defined jobs never overwritten).
A cross-tenant trigger (`eod_sweep`, `eod_trigger_policy`) lists ACTIVE tenants from tenant-service with the service token
and starts EOD per legal entity once its local cut-off has passed (optional auto-roll after a fully successful EOD):
scheduler `meridian.business-day.auto-eod.enabled` (off by default) or operator `POST /v1/operations/eod-sweeps/run`
(`meridian:admin`). Remaining: ledger EOD needs a tenant-wide endpoint (pack job `LEDGER_EOD` needs `parameters.ledger_id`),
no tenant EOD endpoints yet for reconciliation runs, fee/billing charging or GL period close; most domain EOD endpoints
are tenant-wide, not per legal entity; the generator cannot express operator-only reads of global tables.
| G9 | Callbacks from external providers require `Idempotency-Key` and tenant headers they cannot send | Mark provider callback actions as `public` with signature auth; tenant resolved from the referenced entity | Done ([public]) |
| G10 | Unsupported HTTP method returned 500 | Fixed in libs ProblemHandler (405/415) | Done |
| G11 | Maker-checker pending changes have no home (stored in `metadata._pending_*`) | Standardise: every maker-checker action creates a workflow-service approval_request; services apply on `platform.approval_request.approved` | Done: libs/meridian-approvals in all services (see G11 notes) |
| G12 | Read-style operations declared 204 (view, ownership-tree, exposure, evaluate, simulate, test-connection, registration-options) | Same as G2: response schemas | Done (same as G2) |
| G13 | Search endpoints lack `q` parameter | Add `?q:string!` to search actions | Open |

Domain hardening status: product ✔, ledger/gl/recon ✔ (merged); platform, party, accounts, operations in progress.

## Cross-cutting items raised in wave A (open)

- Operator (platform-admin) scope notation for global catalogues: sanctions lists, bank directory, price plans, MCC codes.
- Service-to-service tokens for internal calls in JWT mode (rail callback → payment-service, EOD jobs).
- Public-callback tenant resolution: standardise (routing uses a SECURITY DEFINER lookup, document/notification use signed tenant ids).
- Maker-checker via workflow-service everywhere (G11): onboarding journey publish, crm redress, risk four-eyes still local.
- FX conversion for limits/velocity/approval thresholds (amounts compared in transaction currency today).
- Kafka feed to transaction monitoring needs customer/counterparty on posting events.

## Wave B results and open items

All 63 services implemented (no 501s). Generator: nested-path parent ids are no longer required in create bodies;
duplicate resource names and ambiguous paths are now hard validation errors; writes to global tables need
`meridian:admin`; all persisted timestamps use `Clocks.now()` (PostgreSQL microsecond precision).

| Area | Open item |
|---|---|
| Cross-cutting | Four-eyes is checked locally by principal everywhere; move to workflow-service approvals (G11). |
| Cross-cutting | ~~EOD / scan / expiry endpoints exist per tenant but nothing schedules them yet~~ — done: standard EOD job pack + cross-tenant trigger in business-day-service (G8 note). |
| Cross-cutting | Simulated adapters only: credit bureau, e-signature, billers, interop switch, regulator gateway, data-share transport, HSM. |
| Cross-cutting | Large jobs (disbursement batches, commission payouts, exports) run synchronously or in-memory; move to Temporal workflows. |
| Cross-cutting | Report/export outputs stored inline; archive via document-service. Shared helpers duplicated in reporting services: move to libs. |
| Cards | Limit counters read without lock (brief overshoot under concurrent auths); vault void endpoint for failed issue; real HSM adapter + ZPK grace period; multi-presentment clearing; 3DS OTP sent inside DB transaction; ATTEMPTED result path; rename `card_control.values`. |
| Lending | `loan_financial_item` lacks `loan_application_id`; credit lines expose no overdue amount / late fee; collections fees not charged and contacts not sent through notification-service; bulk repayments endpoint for collections EOD. |
| Wallets | PENDING_PROVIDER bill payments vs 10-minute hold window; `commission_amount` not written back; agent `onboarding_count`; micro-deposit delivery adapter; deposit account holder name for name enquiry. |
| Treasury | Revaluation / fair-value changes not posted to ledger; holiday calendars not applied to value dates; settled-flows feed for liquidity EOD; ALM multi-currency and NII floor; FX list omits legs. |
| Engagement | customer-service segment member listing; points-earn liability accrual not posted; card-service handling of MERCHANT_DENY `merchant_ids` shape; `CAMPAIGN_MESSAGE` default template; card/wallet enrichment consumers. |
| Reporting/AI | Export 10k-row cap; LLM usage attribution follows current policy; visibility filtering only on saved queries and dashboards. |

## G11 notes (central approvals rollout)

Converted in workflow mode (local mode unchanged): pricing tables, product versions/scripts, onboarding referrals,
KYC documents, KYC tier (customer, central-only), deposit opening/overdraft/closure, CRM redress and articles, GL
journals and period close, payments and bulk payments (per level), limit overrides, risk-rating overrides, AML
dispositions and SARs, sanctions true matches, credit decisions, document verification, loan write-offs and
restructures, collateral, legal proceedings, out-of-policy plans, treasury counterparties/limits/SSIs, LCR/NSFR,
stress scenarios, ALM assumptions, card program go-live, dispute write-off, wallet reversals/bank links/PIN resets,
agent float and cash management, agent KYC, commission plans and payout runs, biller and merchant settlements,
merchant refunds and onboarding, disbursement batches (per level), rewards adjustments, campaigns, report
versions/adjustments/submissions, KPI certification, model versions, copilot actions, purge requests, and the
central-only platform flows (tenant modules, identity, config activation, document/notification templates, audit
exports).

Spec gaps (flows still local or with no place to record a rejection):
- Submit-for-approval actions missing: fraud rules, limit rules, TM scenario versions, FTP curve publish, onboarding
  journey publish, USSD menu publish, card fee waiver (needs a waiver request), program/agent/merchant reactivation.
- Rejected states missing: SAR (MLRO return-to-draft), gl_journal (REJECTED or rejection record), biller/merchant
  settlements, alm assumptions, liquidity LCR/NSFR, report definition versions, KPIs.
- Decide: merchant onboarding rejection currently terminates (irreversible); alternative is leaving it ONBOARDING.
- Library follow-ups: decision comment in `ApprovalDecision`; `diff` on `ApprovalSpec`; `Approvals.get(id)`;
  re-open a request after an approved decision fails a business check (e.g. insufficient funding).
- Known limitation: a late redelivered tenant-module enable after a later disable re-enables the module.

## Resume point (paused 2026-10-07)

- Full build after the e2e fixes: only billing-service BillingFlowIT fails (issueAndPayInvoice,
  overdueInvoiceMakesSubscriptionPastDueUntilPaid). Investigate first (likely the ProblemHandler/TenantSettings or
  contact-directory changes from the e2e pass, or load flakiness: rerun alone first).
- Done since resume: search indexing feed (97 event types, 17 kinds); per-tenant ledger settings in all services;
  X-Roles forwarding; BusinessDates (payment, deposit); EOD job results; missing ledger config is 422 everywhere.
- Done: tenant starter packs (tenant-service; generic + KE); service-on-behalf principal is the human (four-eyes safe in JWT mode).
- Next: run e2e setup S live with the starter pack (untested live); starter pack gaps (fx routing keys, retire removed items, stale RUNNING runs, approver roles); search gaps (source backfill action,
  dead-letter store, journal erasure, customer contact fields in events, loan status events, spec says OpenSearch but
  service uses PostgreSQL FTS); BusinessDates in more services.
- E2E: `python tools/e2e/journeys.py` (all 8 journeys passed on a fresh tenant with the full stack up).

## Connection budget

63 services x pool 20 exceed a single PostgreSQL's connections. Local launcher uses pool 6 (`MERIDIAN_DB_POOL`);
clusters need PgBouncer (transaction mode) or per-domain Aurora clusters, and pool sizes per service in Helm values
(`dbPool`). Caution: `TenantAwareDataSource` sets `app.tenant_id` at session level (`set_config(..., false)` on
checkout), which is NOT safe behind PgBouncer transaction pooling (the setting could leak to another client's
transactions). Use PgBouncer session mode, or first switch to a per-transaction `set_config(..., true)` issued at
transaction start (and verify RLS tests still pass) before enabling transaction pooling.

## Developer portal gaps (from portal v1)

- API clients: no revoke/delete action (status is read-only); no developer/organisation ownership, so "my apps" are
  all tenant clients; no self-service sandbox tenant provisioning; usage response untyped.
- Webhooks: no per-event replay (only time-window replays and per-delivery retry).
- Contracts don't declare response headers (ETag, Idempotent-Replayed, rate limits); `/v1/oauth-scopes` may be empty.
- Open banking: no TPP self-registration endpoint for a portal screen.
- Sandbox simulators (card auths, test clocks) not built yet.

## Per-domain notes

- platform: `ssoProviderCreate` lacks `client_secret`; `config_entry` lacks submitter; `audit_event` lacks consumed-event id;
  `price_plan` has no tenant (global catalogue editable by any tenant — needs operator-only scope); billing has no payment table;
  notification provider `credentials_ref` is `secret` but required on create; webhook needs previous-secret column (rotation grace);
  generated-document create lacks `data`; send-templated lacks destination; workflow task has no direct create.
- party: no OTP state table (uses customer_credential SMS_OTP rows); kyc_profile has no create op (implicit creation);
  `KycProviderCreate` omits required `credentials_ref`; `OnboardingApplicationCreate` lacks `legal_entity_id`;
  missing events for privacy reject, journey retire, request-info, document accept/reject; close/deceased cannot
  check product holdings (needs a customer-holdings query across deposit/loan/card/wallet services).
- ledger/gl: `gl_journal` is append-only but approval must set `approved_by` (service-local migration
  V001_1 allows one NULL->value update — move approvals to a separate append-only `gl_journal_approval` table and
  drop the hack); `gl_journal` needs `nested: lines list:gl_journal_line`; ECL calculate needs `exposures` body and
  GL accounts; simulate needs inputs; recon file create needs `content`/`records`; missing events for matches and
  ERP export; P&L close to retained earnings not modelled; ledger entries lack value_date/reference for recon.
- product: event `product.product_version.migration_requested` missing; calculators declared 204 (fee calculate,
  effective-rate, eligibility-check, quote, diff, simulation export, script test, DMN test/export) → response schemas;
  eligibility-check/quote/fee-calculate need applicant/context bodies; diff `?against=`; rate-index values `?from&to`;
  decision table inputs/outputs/rules are lists (G1); `product_script` wasm_module_uri/sha required+ro without default;
  no rejection-reason/submitted_by columns; decision-table version lacks APPROVED/REJECTED; `version_no` should be ro.
- accounts: list fields (bulk items, approver_groups, formats) → G1; mandate evaluate needs `signatories`; many
  read actions declared 204 (G2) and missing query params (G3); account/deposit/virtual-account/statement numbers
  should be server-generated (`ro`); deposit account needs a product-terms snapshot column; statements need a
  document reference + failure reason; cheque book dispatch/activate actions; ledger needs an idempotent
  set-overdraft-limit action and idempotent freeze/unfreeze; EOD jobs (accrual, maturity, standing orders,
  dormancy) need endpoints callable by business-day-service (G8); external payouts should go via payment-service.
- operations: list fields (G1); none-responses (G2); undeclared query params (G3); service added endpoints not in
  spec — `POST /v1/operations/business-days` (open first day), `POST/DELETE /v1/search/documents`,
  `GET /v1/files/blobs`, purge approve/reject → add to spec; file content and secret refs missing from create
  models; purge needs CANCELLED + reason; batch-lock force-release reason column; every data-owning service needs
  `/v1/internal/data-lifecycle/{count,extract,confirm,purge}` for archive/purge (new cross-cutting capability);
  background schedulers blocked by RLS (G8).
