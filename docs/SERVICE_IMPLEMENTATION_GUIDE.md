# Service Implementation Guide

How to turn a scaffolded service into a working one. The reference implementation is
`services/ledger-service` (posting engine, holds, reversals, 10 integration tests).

## What you get for free

- **Contract:** `generated/openapi/services/<service>.openapi.yaml` → generated interfaces `<pkg>.api.generated.*Api`
  and models `<pkg>.api.generated.model.*` (snake_case JSON, money as `BigDecimal` serialised as strings).
- **Generic CRUD:** every `list/get/create/update/delete` operation already works through `CrudEngine`
  (tenant-scoped via row-level security, keyset paging, filters incl. path params, `ETag`/`If-Match`,
  idempotent create). Only override a CRUD method when it needs business rules.
- **Actions:** every other operation (e.g. `POST /{id}/approve`) throws `501 not_implemented` until you
  override it in the matching `api/<Tag>Controller`.
- **Schema:** Flyway applies `generated/db/<database>/V001__init.sql` on start (owner role); the service
  connects as `meridian_app`, so RLS is enforced.

## Layering (hexagonal)

| Package | Contents | Rules |
| --- | --- | --- |
| `api` | Controllers implementing generated `*Api` interfaces | Thin: parse request, call application, map response |
| `application` | `@Service` use cases, `@Transactional` boundaries, outbox events | One public method per business action |
| `domain` | State machines, rules, value objects | No Spring, no SQL; pure and unit-testable |
| `infrastructure` | `JdbcClient` SQL beyond generic CRUD, adapters to external providers | Parameterised SQL only |

## Building blocks (from `libs/`)

| Need | Use |
| --- | --- |
| Current tenant / user | `TenantContext.require()`, `TenantContext.principal()` |
| Errors | `throw ApiException.unprocessable("code", "message")` (also `notFound`, `conflict`, `badRequest`); codes are snake_case and stable |
| Generic row ops | `CrudEngine`: `findOne(table, Map)`, `insert(table, ObjectNode)`, `updateRow(table, keys, values, version)`, `toColumns(table, model)`, `read(json, ModelClass)`, `list(...)` |
| Idempotent POST | `Idempotency.execute(key, body, ResponseType.class, () -> ...)` |
| Events | `OutboxWriter.publish("<domain>.<entity>.<verb>", "<table>", id, payloadJsonNode)` inside the transaction; event names must exist in the domain spec `events:` |
| Other services | `ServiceClient.post("ledger-service", "/v1/journal-entries", body, idemKey)` (propagates tenant/user/token) |
| IDs / money | `Ids.newId()` (UUIDv7), `Money`, `BigDecimal` with explicit `RoundingMode.HALF_EVEN` |

Request bodies: convert models with `JsonNode req = json.valueToTree(body)` and read snake_case fields; this avoids
coupling to generated getter names. Responses: `crud.read(rowJson, Model.class)`.

## State changes

- Status changes go through explicit actions (`/activate`, `/suspend`, `/approve`), never through PATCH. When an
  action exists for a status field, validate allowed transitions in a `domain` state machine and reject others
  with `409 invalid_transition` (or a more specific code).
- Maker-checker: approval actions must reject approval by the same principal that created/submitted
  (`422 four_eyes_violation`) where the spec describes approval.
- Every state change publishes its domain event via the outbox in the same transaction.
- Money movements never update balances directly: they post to `ledger-service` (journal entries / holds).

## Maker-checker (central approvals, `libs/meridian-approvals`)

Every four-eyes decision is also routed through workflow-service so checkers work from one inbox with policies,
levels, thresholds and SLAs. Keep the service's own `submit` / `approve` / `reject` endpoints and lifecycle rules;
add the central path on top (reference: pricing-rules `DecisionTableService` + `DecisionTableApprovalHandler`):

1. On submit, call `approvals.request(ApprovalSpec.of(RESOURCE, id, action, title, payload), key)` inside the
   transaction. Put in `payload` what approvers need and a content fingerprint (checksum / row_version) so stale
   decisions can be detected. Use `.withAmount(amount, ccy)` when policies route by amount. Deterministic `key`.
2. Implement `ApprovalOutcomeHandler` for `RESOURCE`: if the resource is no longer waiting for this submission
   (status or fingerprint changed), return; APPROVED calls the same `approve` method as the endpoint (the principal
   is the deciding approver, so the four-eyes check still applies); REJECTED / CHANGES_REQUESTED call `reject`;
   EXPIRED / CANCELLED leave it pending. Must be idempotent (at-least-once delivery).
3. After a direct decision on the service's own endpoint, call `approvals.cancelOpen(RESOURCE, id, null, reason)`;
   inside a transaction it runs after commit.
4. Mode: `meridian.approvals.mode=workflow` (set by `tools/dev.py`) or `local` (default; existing tests unchanged).
   Add one `*WorkflowApprovalIT` with `meridian.approvals.mode=workflow`, a mocked `ServiceClient`, and decisions fed
   through `ApprovalEventsDispatcher.dispatch(...)`.

Lessons from the rollout (all 63 services):
- Fingerprint: something that changes when the submission changes but not on unrelated writes (checksum of
  content, `submitted_at`, amount). `row_version` works only if nothing else writes the row while pending. Put the
  submission marker in the idempotency key too, so resubmitting identical content after a rejection opens a new
  request instead of returning the decided one.
- Resubmission: open the new request, then `cancelOpenExcept(type, id, null, newRequestId, "superseded")`.
- No local rejected state: map REJECTED to the closest existing transition (cancel, back to DRAFT, archive) or
  leave it pending and log; say which in the handler Javadoc. CHANGES_REQUESTED may map to a "request info" step.
- Where the request id has a home (`approval_request_id` column) store it and match decisions on it.
- Multi-level approvals: one request per level (fingerprint = approvals received so far); open the next level
  outside the transaction that cancels the previous one.
- Payloads carry ids, amounts and what a checker needs to decide; never PAN/CVV/PIN, secrets, or tipping-off
  sensitive narrative (SAR).
- Flows without a maker step (a checker acting alone, e.g. activating an editable rule) stay local until the spec
  gets a submit action.

## Service identity (JWT mode)

- A call made while handling a user request forwards the user's token (the user's permissions apply).
- A call with no user context (scheduled job, provider callback, Kafka consumer) uses a client-credentials token
  with scope `meridian:internal`; set `meridian.service-auth.token-uri / client-id / client-secret`
  (local: `http://localhost:15180/realms/meridian/protocol/openid-connect/token`, `meridian-service`,
  `meridian-service-secret`). Bind the tenant first (`TenantContext.callAs(tenant, "job:<name>", ...)`);
  ServiceClient sends it as `X-Tenant-Id`, which receivers honour only for internal tokens.
- Records written this way carry `created_by = svc:<client>[/<on-behalf-of>]`.


Acting on behalf of a user: with an internal token plus `X-User`, the principal is that user (so four-eyes compares
people) and `TenantContext.via()` names the relaying service client. Without `X-User` the principal is
`svc:<client>`. Never compare principals with `startsWith("svc:")` to grant rights; use `via()` for "a service relayed
this" decisions.

### Acting for a customer (`X-On-Behalf-Of-Customer`)

The customer channel service (BFF) relays customer requests with its internal token, `X-Tenant-Id` and
`X-On-Behalf-Of-Customer: <customer id>`. A domain service treats the caller as that customer **only** when
`TenantContext.via()` is present (internal token); the same header from any other token is refused with 403
`on_behalf_not_allowed` (a staff or customer token can never pick a customer). In dev mode (no tokens) the header is
taken as the relay would send it, which is how flow tests act as a customer.

- Who is speaking is derived, never read from the body: fields such as `sender_type`, `opened_by`, `reader_type`
  and the staff user ids are `ro` in the spec; a body value that disagrees with the caller is 422 (`sender_mismatch`,
  `reader_mismatch`, `customer_mismatch`).
- Customer callers are deny-by-default: an interceptor allows only the operations meant for customers; each of them
  checks ownership and answers 404 for another customer's object. Staff-only data (internal notes,
  `include_internal=true`) is 403 `staff_only`.
- Put the caller identity in idempotency fingerprints so a key replay never crosses callers.
- Reference implementation: inbox-service (`Callers`, `Caller`, `CustomerAccess`).

### Public applicant surface (onboarding-service)

Unauthenticated prospects use `/v1/onboarding/public/applications/*` (`[public]` actions, tenant from `?tenant_id`).

- `start` takes a published journey code, a `device_fingerprint` and an optional `attestation_token`
  (`meridian.onboarding.public.attestation-required` makes it mandatory; the verdict check itself is a placeholder).
  Starts are rate-limited per client IP and per device fingerprint per tenant (429 `rate_limited`); behind the
  gateway set `meridian.onboarding.public.client-ip-header=X-Forwarded-For` (the gateway must overwrite that header).
- It returns an opaque applicant token (`oat_…`, 32 random bytes) once; only its SHA-256 is stored
  (`onboarding_applicant_token`). Send it as `X-Applicant-Token` (not `Authorization`, which the resource server
  would try to parse as a JWT). Tokens are short-lived, bound to one application (404 for any other), rotated by
  `token/refresh` and revoked on submit. Check the token before anything else.
- Contact steps (PHONE_ / EMAIL_VERIFICATION) complete only through `otp/send` + `otp/verify`: 6-digit code sent via
  notification-service, HMAC-SHA256 at rest (`onboarding_otp`), expiry, attempt limit with lockout, resend cooldown
  and a per-application send limit. Failed attempts are returned (not thrown) from the transactional method so the
  counter commits before the 422.
- Applicant step input accepts only the step's declared fields; bank-managed values (`kyc_profile_id`,
  `kyc_document_id`, `customer_id`) are never accepted from the applicant. Documents go through document-service
  with owner `ONBOARDING_APPLICATION`/<application id>; DOCUMENT fields must reference the application's own documents.
- Typed step fields in the journey definition (`{name, type, label_key, required, pattern, enum, min_length,
  max_length, min, max}`; plain names stay valid) are returned by next-step and validated server-side for staff
  and applicants (422 `invalid_fields` / `missing_fields` with `input.<field>` errors).
- `application_number` is server-generated (`APP-<yyyymmdd>-<8 base32>`); client values are ignored.

## Per-tenant ledger settings (config-service namespace `ledger`)

Ledger books, internal (GL / clearing / income / funding) accounts, treasury wallets and similar ids are tenant
data, never deployment constants. Read them through `io.meridian.web.config.TenantSettings`, normally by overriding
the accessor of the service's `@ConfigurationProperties` record, e.g.
`TenantSettings.uuid("payment-service", "gl.clearing", gl.clearing())`.

- Source: config-service namespace `ledger` (approval-gated, versioned), resolved per tenant and cached 30 s. Each
  service has one key (its name). The value is a JSON object whose paths mirror the service's `meridian.*` properties
  in snake_case. The optional `default.ledger_id` is the tenant's main book for every service.
- Precedence: a value on the business record itself, where one exists (a rewards program's `ledger_id`, a redress's
  `funding_account_id`). Then the tenant's `<service>.<path>`. For `ledger_id` only, `default.ledger_id` comes
  next. The deployment property is the last fallback, for single-tenant installs and tests.
- Never ship placeholder UUIDs in `application.yml`. When nothing is configured, the operation fails with
  422 `ledger_not_configured` (missing book) or `ledger_account_not_configured` (or the service's own
  `*_not_configured` code). It never posts to a made-up account.
- In tests, stub `config-service` through the mocked `ServiceClient`; an empty
  `{"namespace":"ledger","values":{}}` means the deployment properties apply.

| Key (path in namespace `ledger`) | Owning service | Meaning |
|---|---|---|
| `default.ledger_id` | all | Tenant's main ledger book, used when a service has no `ledger_id` of its own |
| `deposit-service.ledger_id`, `.gl.{clearing,fee_income,interest_expense,interest_income,tax_payable}` | deposit-service | Book and GL accounts for deposit postings |
| `payment-service.ledger_id`, `.gl.{clearing,fee_income,direct_debit_clearing}` | payment-service | Book, default rail clearing, fee income and direct-debit clearing accounts |
| `wallet-service.ledger_id`, `.gl.{bank_clearing,fee_income,tax_payable}` | wallet-service | Book and internal accounts of wallet transactions |
| `loan-service.ledger_id`, `.gl.{clearing,interest_income,interest_receivable,interest_suspense,fee_income,fee_receivable,penalty_income,penalty_receivable,overpayment,waiver_expense,write_off_expense,recovery_income,agency_commission,tax_payable}` | loan-service | Book and loan GL accounts |
| `card-clearing-service.ledger_id`, `.clearing.settlement_accounts.<SCHEME>`, `.clearing.{fee_income_account,interchange_income_account,nostro_account,tax_account}` | card-clearing-service | Book, scheme settlement accounts and clearing income, nostro and tax accounts |
| `card-dispute-service.ledger_id`, `.dispute.{suspense_account,loss_expense_account,scheme_receivable_account}` | card-dispute-service | Book, provisional-credit suspense, absorbed-loss expense and chargeback receivable |
| `fx-service.ledger_id`, `.position_accounts.<CCY>`, `.clearing_accounts.<CCY>` | fx-service | Book and per-currency FX position and clearing accounts used at deal settlement |
| `routing-service.ledger_id`, `.gl.treasury_funding` | routing-service | Book and treasury account debited when a nostro is topped up |
| `treasury-deal-service.ledger_id`, `.accounts.<CCY>.<role>` | treasury-deal-service | Book and treasury accounts per currency. Roles are snake_case: `nostro`, `mm_placements`, `mm_borrowings`, `interest_receivable`, `interest_payable`, `interest_income`, `interest_expense`, `fx_position`, `securities`, `realised_pnl` |
| `crm-service.redress.ledger_id`, `.redress.funding_account_id` | crm-service | Book and redress expense / suspense account for monetary complaint redress, unless the redress sets its own |
| `rewards-service.ledger_id`, `.funding_account_id` | rewards-service | Book and funding account for cashback, redemption and referral payouts, unless the program or campaign sets its own |
| `biller-service.settlement_clearing_account_id` | biller-service | Bank clearing / nostro account credited when a biller settlement is paid out |
| `merchant-service.fee_income_account_id` | merchant-service | Account credited with MDR and refund fees |
| `commission-service.payout_source_wallet_id` | commission-service | Commission pool wallet funding agent commission payouts |
| `agent-network-service.treasury_wallet_id` | agent-network-service | Treasury wallet funding bank-funded float top-ups |
| `channel-service.airtime_biller_id` | channel-service | Biller used by the USSD AIRTIME action when the menu gives none |

## Tenant starter packs (tenant-service)

A fresh tenant has no legal entity, book, routing, products, tariffs, limits, templates or EOD jobs. A starter pack
creates them through the owning services' public APIs. Packs live in `services/tenant-service/src/main/resources/starter-packs/`
(one YAML file per pack version; `GENERIC` plus `KE`, which `extends` it). Every value in them is a default to review.

- Apply: `POST /v1/starter-pack-runs/apply` (the caller's tenant), `POST /v1/platform/tenants/{id}/apply-starter-pack`
  (operator, `meridian:admin`), or `starter_pack` on provisioning (applied after commit). `GET /v1/starter-pack-runs/catalog`
  lists the packs, their parameters and steps. `dry_run` predicts the outcomes and makes no calls.
- Runs execute on a background thread with the target tenant bound. ServiceClient therefore uses the service token
  (`meridian.service-auth.*` must be set in JWT mode) and sends `X-Tenant-Id`. The acting user travels as `X-User` and is the
  maker of every approval request, so that user cannot approve them.
- Idempotent: each item is recorded in `starter_pack_item` with the checksum of its definition. Keys are deterministic.
  Before a create, the body and the Idempotency-Key are stored, so a resume replays them unchanged. Re-running skips
  applied items. A resource that already exists (found by the item's `lookup`) is recorded as EXISTING and never changed.
- Upgrade: in a newer pack version, a pack-owned item whose definition changed is updated as its `update` field says:
  `PATCH` (If-Match), `RECREATE` (new version plus resubmit, used for config entries) or `NONE` (reported as UPGRADE_SKIPPED).
- Maker-checker: follow-up actions depend on the resource status (`skip_status`, `requires_status`, `after`). Submissions
  open approval requests, and the run lists their ids. Checker-only steps (`manual`, e.g. limit rule activation) are
  reported as AWAITING_ACTIVATION. A later run publishes or activates what was approved. The first failure stops the
  run; `POST /v1/starter-pack-runs/{id}/resume` continues it.
- A new pack item must pass `StarterPackDefinitionTest`: the route must exist in `generated/routes.json` and the
  rendered body must match the request schema in `generated/openapi`.
- Data-driven keys and repetition: object keys may hold placeholders (`"${param.currency}": ...`). An item with
  `for_each: "${param.<list>}"` becomes one item per value (key `<key>.<value lower case>`, `${each}` / `${each.lower}`
  inside, also in references such as `${ref.ledger_book/fx_position.${each.lower}}`); an object with a
  `$for_each: "${param.<list>}"` entry becomes one copy of its other entries per value, so maps keyed by currency can be
  rendered (fx-service `position_accounts.<CCY>` from `fx_currencies`). Repetition is expanded with the run's
  parameters before the run; the catalog validates packs expanded with the defaults.

## Contact search (party.customer_contact.*)

Customer events never carry e-mail addresses or phone numbers. A contact point has `masked_value` and `lookup_hash`
(HMAC-SHA256 of the normalised value: e-mail lower-cased, phone digits only without a leading `00`) under the key
`meridian.contact-lookup.key` (`MERIDIAN_CONTACT_LOOKUP_KEY`), shared by customer-service and search-service only.
Verified contacts are published with `party.customer_contact.verified`; deletion, a changed value, merge and erasure
publish `party.customer_contact.removed`; both payloads drop `phone` / `email`. search-service indexes doc kind
`customer_contact` (masked title, hash as identifier, `customer_id`) and hashes a query that is an e-mail or phone with
the same key, so exact contact search works without indexing the contact.

## Time

- Validity windows, expiries and "is it effective now" checks must use one clock. When SQL compares with `now()`,
  write the timestamps from the database clock too (`select now()`), never `OffsetDateTime.now()` in the JVM —
  the JVM and database clocks differ by milliseconds and cause flaky "not yet valid" bugs.
- Never persist `OffsetDateTime.now()`: use `io.meridian.common.time.Clocks.now()` (UTC, microseconds). PostgreSQL rounds
  sub-microsecond digits, so a raw JVM timestamp can read back in the future and break effective-date checks.
- Store and compare timestamps in UTC; business dates come from business-day-service, not the wall clock.

### Business date

"Today" for value dates, due dates and accruals is the business date from business-day-service, not the UTC
calendar date: inject `io.meridian.web.time.BusinessDates` and call `today(legalEntityId, fallback)` when the
record has a legal entity, else `today(fallback)` (tenant-wide, used when all entities agree). Pass the service's own
clock as the fallback so tests keep controlling dates. Timestamps stay `Clocks.now()`. Pattern: one `today()` helper
per service (`Rows.today()` or the service's support class) wrapping the old clock / `select current_date`, plus a
`today(legalEntityId)` / `today(record)` overload where the record has a legal entity (loan-service, treasury-deal);
SQL validity filters bind the date (`valid_from <= :today`) instead of using `current_date`. Tests that stub
business-day-service clear the cache with `BusinessDates.invalidate()`; a test without a mocked `ServiceClient` sets
`meridian.business-date.source=clock`. Adopted in payment, deposit, loan, wallet, billing, collections, treasury-deal,
statement, fx, routing, rewards, insights and campaign services.

## Tests (required per service)

- Keep the generated `<Name>ApplicationTests` (context + migration).
- Add `<Name>FlowIT` (see `LedgerPostingIT`): `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
  {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})`, `@Import(MeridianTestContainers.class)`,
  plain `java.net.http.HttpClient` with `X-Tenant-Id` and `Idempotency-Key` headers. Cover: happy path of each
  main flow, invalid transitions, validation errors, idempotent replay, tenant isolation.
- Stub other services with `@MockitoBean ServiceClient` (`org.springframework.test.context.bean.override.mockito`).
- Run: `./gradlew :services:<service>:test`.
