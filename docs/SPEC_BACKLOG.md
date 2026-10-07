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

**G8 note.** business-day-service ships a versioned *standard EOD job pack* (`eod/standard-pack.json`, 54 jobs in banking
order, checked against `generated/routes.json` and the per-service OpenAPI by `StandardJobPackTest`), installed per tenant
with `POST /v1/operations/eod-jobs/install-standard-pack` (idempotent upgrade; tenant-defined jobs never overwritten).
A cross-tenant trigger (`eod_sweep`, `eod_trigger_policy`) lists ACTIVE tenants from tenant-service with the service token
and starts EOD per legal entity once its local cut-off has passed (optional auto-roll after a fully successful EOD):
scheduler `meridian.business-day.auto-eod.enabled` (off by default) or operator `POST /v1/operations/eod-sweeps/run`
(`meridian:admin`). Ledger EOD is tenant-wide (`POST /v1/ledgers/end-of-day`, per legal entity of the EOD run; pack
`LEDGER_EOD` ships active, an optional `parameters.ledger_id` still pins one book; done). Reconciliation: done —
`RECON_DAILY` (pack 2026.10.3, after `LEDGER_EOD`) calls `POST /v1/reconciliation/runs/eod?business_date=` which
runs every active linked source over its look-back window, idempotent per business date and source, and returns
`records_processed` / `records_failed` plus the auto-match rate. Remaining: no tenant EOD endpoints yet for
fee/billing charging or GL period close; most domain EOD endpoints
are tenant-wide, not per legal entity; the generator cannot express operator-only reads of global tables.
| G9 | Callbacks from external providers require `Idempotency-Key` and tenant headers they cannot send | Mark provider callback actions as `public` with signature auth; tenant resolved from the referenced entity | Done ([public]) |
| G10 | Unsupported HTTP method returned 500 | Fixed in libs ProblemHandler (405/415) | Done |
| G11 | Maker-checker pending changes have no home (stored in `metadata._pending_*`) | Standardise: every maker-checker action creates a workflow-service approval_request; services apply on `platform.approval_request.approved` | Done: libs/meridian-approvals in all services (see G11 notes) |
| G12 | Read-style operations declared 204 (view, ownership-tree, exposure, evaluate, simulate, test-connection, registration-options) | Same as G2: response schemas | Done (same as G2) |
| G13 | Search endpoints lack `q` parameter | Add `?q:string!` to search actions | Open |
| G14 | One scope per domain access level cannot express a narrow client (cell router) or an operator-grade action | Api `scopes:` map: `{list|get|create|update|delete|"METHOD /path": [required, accepted...]}` -> `x-required-scope`, `x-accepted-scopes`, `@RequiredScope(value, anyOf)` | Done (tenant routing / cells: `platform:routing-read` + `platform:operator`; fraud `pseudonymise-stored`: `risk:*`) |

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
| Reconciliation | Matching engine (S19) is in place, but the internal side read from ledger-service carries only the journal `reference` and line description: payment-service posts without `reference` (should be the end-to-end id) and no posting carries UETR / instruction id / counterparty / batch id, so the UETR, name-based and keyed-batch rules cannot fire on nostro sources yet. Needed: payment-service and card-clearing set `reference` and journal `metadata` (uetr, instruction_id, counterparty_name/account, batch_ref), ledger-entries list exposes them; file-gateway route type to deliver inbound statements to `POST /v1/reconciliation/files/import`; BAI2 / Visa Base II / Mastercard T112 parsers. |
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
- Done (2026-10-07): submit + reject actions for fraud rules (`submitted_mode`), limit rules, TM scenario versions
  (`/scenario-versions/{id}/submit|reject`), FTP curves (payload checksum over the points) and USSD menus (checksum
  over menu + nodes); decisions match on `approval_request_id` where the table has one. The rule keeps its status
  while submitted; an edit withdraws the submission; direct activate/publish still works (local mode unchanged).
- Done (2026-10-07): rejected / return states: SAR `return-to-draft` (MLRO; inbox rejection returns to DRAFT),
  `gl_journal_rejection` (append-only; a rejected journal never posts and no longer blocks the period close),
  biller/merchant settlements REJECTED (biller releases its payments; the merchant period can be settled again),
  ALM assumption rejection (PATCH rework clears it and resubmits), LCR/NSFR REJECTED (recalculation resubmits),
  report definition versions REJECTED (final; maker adds a version), KPI `reject-certification` (definition change
  clears it and resubmits).
- Done (2026-10-07): merchant onboarding rejection no longer terminates: the merchant stays ONBOARDING with the
  reason in `status_reason` (`onboarding_rejected_by`), activation is refused until a PATCH reworks it, which
  resubmits (row_version fingerprint). Direct `POST /merchants/{id}/reject-onboarding`.
- Still open: onboarding journey publish (onboarding-service, other worker), card fee waiver (needs a waiver
  request), program/agent/merchant reactivation submit actions.
- Library follow-ups: decision comment in `ApprovalDecision`; `diff` on `ApprovalSpec`; `Approvals.get(id)`;
  re-open a request after an approved decision fails a business check (e.g. insufficient funding).
- Known limitation: a late redelivered tenant-module enable after a later disable re-enables the module.

## Customer channel security (critical, in progress)

Found while building web/customer-web: no customer identity and no customer-scoped API; domain services trust
customer_id from filters/bodies and do no ownership checks, so customer scoping was browser-only. Fix in progress:
`channels` domain with customer-channel-service (`/v1/me/*` BFF, object-level authorization, server-built bodies,
step-up for money movement), Keycloak customer client + customer_id claim, customer tokens rejected by operator
APIs. Follow-ups: customer sessions; customer MFA/passkeys.

Done: inbox derives the caller (`X-On-Behalf-Of-Customer` honoured only for internal tokens; sender/reader/opened_by
server-set; include_internal staff-only; customers confined to their own threads, deny-by-default). Onboarding public
applicant surface (`/v1/onboarding/public/applications`: rate-limited start, hashed applicant tokens bound to the
application with refresh/rotation, OTP contact verification with lockout, documents via document-service, typed step
fields, server-generated application_number). Remaining gaps:

- customer-channel-service must send `X-On-Behalf-Of-Customer` with its internal token for inbox calls; thread
  create still requires `customer_id` in the body (must equal the relayed customer).
- Inbox: any bank-side caller may declare `SYSTEM` / `BOT` as sender (no separate bot identity).
- `[public]` operations get the generator's "provider callback" description (tools/generate.py); the applicant
  `documents` actions answer untyped `object`.
- Applicant start rate limits are per tenant (RLS) and per instance clock window; no global edge limit, and the
  attestation verdict check is a placeholder (presence only, when required).
- No applicant re-authentication after the token expires or after submit (e.g. OTP to the verified phone to resume
  or to follow the decision); staff `resume` links are not wired to the public surface.
- Self-service KYC: `kyc_profile_id` is bank-managed, so journeys whose steps run a `kyc_check` cannot be completed
  by applicants until kyc-service can create a prospect profile from the application.
- Document fields check ownership, not the virus-scan status (`AVAILABLE`) of the referenced document.
- AUTO_APPROVE without a legal entity (neither on the application nor the journey) is downgraded to REFER
  (`LEGAL_ENTITY_REQUIRED`).

## Observed in live e2e (2026-10-07)

- Done (2026-10-07): deposit accounts of a KE tenant got GB IBANs (`GB..MERI..`, BIC MERIGB2LXXX). Account
  identifiers now follow the booking legal entity (country / BIC from tenant-service, per-entity settings in
  config-service namespace `account_numbering`, IBAN lengths from reference-data): IBAN with mod 97 where the
  country uses IBAN, else the national format (KE bank + branch + serial with Luhn, NG NUBAN, generic configurable
  pattern); `deposit_account.bic`; no GB defaults; unique under concurrency (advisory lock per candidate). Starter
  packs seed the entry (KE: NATIONAL). See the guide's "Account numbering". Remaining: payment-service
  (`meridian.payment.bank-bic` MERIGB2LXXX) and treasury-deal-service (`our-bic`) still use deployment BICs instead of
  the legal entity's; national check digits inside some IBAN BBANs (FR, ES, IT, BE, PT) are not generated.
- Done (2026-10-07): nothing seeded the public sign-up journey customer-web expects (`RETAIL_ONLINE`). The GENERIC and
  KE starter packs create it with typed step fields (phone OTP, personal details, address, ID document, terms /
  consent); a checker publishes it in onboarding-service.

## Agent channel (follow-up of customer channel)

Done (2026-10-07): `agent-channel-service` (/v1/agent, scope channels:agent, agent_id claim, Keycloak client
meridian-agent-app), object-level authorization with denial audit/events, server-rebuilt bodies, device binding
(ES256/Ed25519 public keys, signed offline batches, X-Agent-Device-Proof as an alternative to step-up),
idempotency_key stored on agent_float_request and onboarding_offline_batch, web/agent-app on /v1/agent only.
Remaining: larger offline batch payloads (upload target instead of a 500-character data: URI), typed quote and
commission-summary responses, a gateway health endpoint, device attestation (Play Integrity / App Attest) and a
server-verifiable PIN-unlock proof, a reference filter on agent transactions, mid-shift till counts, write access for
super-agents over their sub-agents (today read-only), Ed25519 in the app once WebCrypto support is universal, and
gateway stripping of dev headers (X-Agent-Id, X-Auth-Time) in front of JWT-mode services.

## Merchant channel (S19 merchant app)

Done (2026-10-07): `merchant-channel-service` (/v1/merchant, scope channels:merchant, merchant_id + merchant_user_id
claims, Keycloak client meridian-merchant-app), merchant-user model in the channel (OWNER / MANAGER / CASHIER / VIEWER,
store scoping, first-owner bootstrap), sales with one dynamic EMVCo QR each and live status, payment links, transaction
history scoped to stores, refunds with four-eyes and cumulative cap, payout account change with step-up and approval,
settlements, reports and fee statement, API clients and webhooks, object-level authorization with denial audit/events,
web/merchant-app (PWA) on /v1/merchant only. Remaining:
- Disputes: the wallets domain has no merchant disputes (card-dispute-service is cardholder-side); a merchant dispute /
  chargeback view needs a domain model first.
- Wallet transactions carry no store: a payment is attributed to a store through its sale or the QR it paid
  (`metadata.merchant_qr_id`); payment-link payments and static QRs without a store are visible to all-stores users
  only. A `merchant_store_id` on wallet transactions would make scoping exact and history queries cheaper.
- Transaction history reads merchant-service pages whole and filters them in the channel (at most
  `meridian.merchant.max-transaction-pages` per request); a page may hold fewer rows than asked.
- Done (2026-10-07): merchant refunds no longer bypass the bank's checkers. Refunds requested in the app open no
  central request at creation; after the merchant's four-eyes, merchant-service sends them to the bank's central inbox
  (`PENDING_BANK_APPROVAL`) when `meridian.merchant.refund-bank-review` applies (above the threshold, default 1000, or a
  HIGH-risk merchant); only bank checkers decide them; the channel never approves the bank-side request and syncs the
  outcome on read (a push from `wallets.merchant_refund.*` would avoid the read-time call).
- The first owner is bootstrapped on first sign-in (trust in the IdP's admin-only attributes); a bank-side
  "provision merchant owner" operation would remove that reliance. Merchant onboarding stays on the staff APIs.
- Done (2026-10-07): payout account changes approved inside the merchant wait for the bank's review in central
  approvals (`merchant_payout_change`, central only, `meridian.merchant.payout-bank-review` default true) and are
  applied as the deciding bank checker after eligibility is checked again.
- Sale status is polled (`GET /sales/{id}`); a push channel (SSE / web push from `wallets.merchant_qr.paid`) is not
  built.

## Fraud and routing follow-ups (2026-10-07)

- Done: fraud PII. fraud-service pseudonymises identifiers (account numbers / IBANs, card numbers or fragments, phone,
  e-mail, device ids, national ids) with a tenant-keyed HMAC (required secret `meridian.fraud.pseudonym-key`) before
  rules, features and storage: `fraud_score.features` context (`psn:`), list entries (`h1:`), feature-store beneficiary
  keys (`acct:h1:` / `msisdn:h1:`); payment-service sends `beneficiary_account_digest` only; one-off operator migration
  `POST /v1/risk/fraud/scores/pseudonymise-stored` (scope `risk:*`). See ML_FRAUD_TRAINING.md section 7. Open: the
  device registry keeps the SDK fingerprint as sent; events already published with clear values age out with
  retention.
- Done: degraded fraud holds are re-scored automatically with exponential backoff (`FraudRescoreJob`: internal
  scheduler over tenants with activity + `execute-due`), same key `pay-fraud:<id>`.
- Done: wallet payouts are no longer scored twice: wallet-service creates them with its own identity, with the wallet
  transaction's ALLOW (`upstream_fraud_score_id`, request-only) and the clearing account
  (`debtor_clearing_ledger_account_id`); payment-service reuses a recent ALLOW of the same amount / currency / customer
  once, only from `wallet-service` over an internal token. Wallet-funded payouts were refused by payment-service before
  (`wallet_not_supported`); they are accepted from wallet-service now. Open: limits are still reserved by both
  services for the same payout.
- Done: the cell router has its own Keycloak client `meridian-cell-router` (scope `platform:routing-read` only,
  platform-operator tenant), Helm `router.auth.clientSecret` `meridian-cell-router-client` (G14).

## Business channel (S19 business banking portal)

Done (2026-10-07): `business-channel-service` (/v1/business, scope channels:business, business_customer_id +
business_user_id claims, Keycloak client meridian-business-portal), business-user model in the channel (roles,
entitlements, approval matrix, daily limits), multi-level approvals with release only after the final approval
(idempotent), bulk / payroll CSV validation, FX binding, object-level authorization with denial audit/events,
web/business-portal on /v1/business only. Remaining:
- First admin is bootstrapped on first sign-in (trust in the IdP's admin-only attributes); a bank-side
  "provision business admin" operation (staff scope) would remove that reliance.
- API clients created for a business get only `meridian.business.api-client-scopes` (default channels:business, which
  must exist in identity-service's scope catalogue); machine-to-machine business access (client-credentials tokens
  bound to a business) needs a business-scoped API surface first.
- Webhook subscriptions are filtered on the payload's `customer_id`; events without it (collections, virtual account
  credits) are never delivered to business endpoints.
- Loan repayments, credit-line draws, mandates, collections, virtual accounts and payment links act immediately (role +
  entitlement + step-up) instead of going through the approval matrix.
- The daily limit counts submissions per UTC day (not the business date) in the limit currency only.
- PAYROLL batches are sent as CSV, so payment-service does not get category purpose SALA; per-line execution status is
  only available through the report.
- Payment-service approval levels the business's approvers cannot satisfy remain with the bank's staff; the portal
  shows `downstream_status`.
- KYB onboarding screens still use the staff/public onboarding APIs (not the channel).

## Resume point (paused 2026-10-07)

- Full build after the e2e fixes: only billing-service BillingFlowIT fails (issueAndPayInvoice,
  overdueInvoiceMakesSubscriptionPastDueUntilPaid). Investigate first (likely the ProblemHandler/TenantSettings or
  contact-directory changes from the e2e pass, or load flakiness: rerun alone first).
- Done since resume: search indexing feed (97 event types, 17 kinds); per-tenant ledger settings in all services;
  X-Roles forwarding; BusinessDates (payment, deposit); EOD job results; missing ledger config is 422 everywhere.
- Done (2026-10-07): BusinessDates in loan (per loan legal entity), wallet, billing, collections, treasury-deal (per deal
  entity), statement, fx, routing, rewards, insights, campaign (card-clearing has no "today": scheme file dates only);
  `lending.loan.status_changed` on every loan status transition (search maps it); customer contact search via
  `party.customer_contact.verified` / `.removed` with masked value + keyed lookup hash (doc kind `customer_contact`,
  entity type CUSTOMER_CONTACT); starter pack `for_each` repetition and placeholder object keys, fx-service routing per
  `fx_currencies` in GENERIC and KE; tenant-wide ledger EOD (`POST /v1/ledgers/end-of-day`).
- Done: tenant starter packs (tenant-service; generic + KE); service-on-behalf principal is the human (four-eyes safe in JWT mode).
- Done (2026-10-07): BusinessDates in agent-network (KYB expiry, contract end), commission (clawback due date, plan
  retirement), crm ({{today}}, knowledge review due), disbursement (program period, batch reference), limits
  (calendar counter windows follow the business date; rolling windows stay on the clock), sanctions (delisting date,
  active-entry filters), product-catalog (default as-of dates, past/future checks); customer contact-search backfill
  (`POST /v1/customers/contact-search-backfill`, batched and resumable, `rehash_all` after a lookup key rotation).
- Next: run e2e setup S live with the starter pack (untested live); starter pack gaps (retire removed items, stale RUNNING
  runs, approver roles); search gaps (generic source backfill action, dead-letter store, journal erasure, spec says
  OpenSearch but service uses PostgreSQL FTS); BusinessDates in the remaining services (customer-service validity
  filters still use `current_date`: addresses, relationships, groups, segments, identifiers; then check the rest).
- Generator defect found: two actions on one resource with the same last path segment (`POST /{id}/eod` and
  `POST /eod`) generate the same `<Resource><Action>Request` schema and the second silently overwrites the first; the
  generator should reject it (worked around by naming the tenant-wide action `/end-of-day`).
- Contact lookup key rotation (`MERIDIAN_CONTACT_LOOKUP_KEY`, shared by customer-service and search-service): rotate
  both, then run `POST /v1/customers/contact-search-backfill {"rehash_all":true}` per tenant until `done`;
  national-format phone queries (0712...) do not match E.164 contacts.
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
