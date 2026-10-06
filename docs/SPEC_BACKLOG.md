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
| G8 | Scheduled jobs cannot enumerate tenants under RLS (workaround: tenants seen in idempotency_key) | Business-day-service drives per-tenant jobs; services expose job endpoints; or a tenant registry fed by `platform.tenant.*` events | Open |
| G9 | Callbacks from external providers require `Idempotency-Key` and tenant headers they cannot send | Mark provider callback actions as `public` with signature auth; tenant resolved from the referenced entity | Done ([public]) |
| G10 | Unsupported HTTP method returned 500 | Fixed in libs ProblemHandler (405/415) | Done |
| G11 | Maker-checker pending changes have no home (stored in `metadata._pending_*`) | Standardise: every maker-checker action creates a workflow-service approval_request; services apply on `platform.approval_request.approved` | Open |
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
