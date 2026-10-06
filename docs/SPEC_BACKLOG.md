# Spec & Contract Backlog (from service implementation)

Defects found while implementing services against the generated contracts. Services worked around them;
fixing them in the spec/generator makes the contracts honest. Fix systemic items in `tools/generate.py`
first, then per-domain items in `spec/domains/*.yaml`, then remove the service-side workarounds.

## Systemic (generator)

| # | Defect | Fix | Status |
| --- | --- | --- | --- |
| G1 | `json` columns always generate `type: object`; list-valued fields must be wrapped (`{"levels":[...]}`) | Add `json[]` column type → `array` of any; and `json:<scalar>` where scalar values allowed; migrate list fields | Open |
| G2 | Actions declared with response `none` but which must return data (presigned URLs, previews, test-send, summaries, rotate-secret, invoice generate) | Audit every `| none` action; give it a response table or a new `result` schema; reserve `none` for true 204s | Open |
| G3 | GET actions missing query params (`?date`, `?from/to`, `?base/quote`, …) | Action notation now supports `?name:type!`; update affected actions | Open |
| G4 | One-time secrets (API key, client secret, TOTP seed, webhook secret) have no response field | Add `once` column modifier → response-only `writeOnly:false, readOnly:true` field not stored; or explicit `secret_once` schema | Open |
| G5 | Missing statuses/events found during implementation (CHANGES_REQUESTED, CANCELLED, PENDING_APPROVAL, reinstate, archive, legal hold, invoice void/overdue, cell/branding events) | Add enum values / events in the domain specs | Open |
| G6 | Shared enums (e.g. `RunStatus`) defined in one domain file but used everywhere | Move cross-domain enums to `spec/domains/_shared.yaml` | Open |
| G7 | Platform-global reference tables (fx_rate, holiday_calendar ownership) RLS semantics unclear | Decide global vs tenant-override model; add `tenant: false` + override tables | Open |
| G8 | Scheduled jobs cannot enumerate tenants under RLS (workaround: tenants seen in idempotency_key) | Business-day-service drives per-tenant jobs; services expose job endpoints; or a tenant registry fed by `platform.tenant.*` events | Open |
| G9 | Callbacks from external providers require `Idempotency-Key` and tenant headers they cannot send | Mark provider callback actions as `public` with signature auth; tenant resolved from the referenced entity | Open |
| G10 | Unsupported HTTP method returned 500 | Fixed in libs ProblemHandler (405/415) | Done |

## Per-domain notes

- platform: `ssoProviderCreate` lacks `client_secret`; `config_entry` lacks submitter; `audit_event` lacks consumed-event id;
  `price_plan` has no tenant (global catalogue editable by any tenant — needs operator-only scope); billing has no payment table;
  notification provider `credentials_ref` is `secret` but required on create; webhook needs previous-secret column (rotation grace);
  generated-document create lacks `data`; send-templated lacks destination; workflow task has no direct create.
- Product/accounts/party/ledger/operations: pending reports.
