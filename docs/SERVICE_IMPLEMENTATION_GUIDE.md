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
clock as the fallback so tests keep controlling dates. Timestamps stay `Clocks.now()`. Adopted in payment-service
and deposit-service; others to follow when they touch date logic.

## Tests (required per service)

- Keep the generated `<Name>ApplicationTests` (context + migration).
- Add `<Name>FlowIT` (see `LedgerPostingIT`): `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
  {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})`, `@Import(MeridianTestContainers.class)`,
  plain `java.net.http.HttpClient` with `X-Tenant-Id` and `Idempotency-Key` headers. Cover: happy path of each
  main flow, invalid transitions, validation errors, idempotent replay, tenant isolation.
- Stub other services with `@MockitoBean ServiceClient` (`org.springframework.test.context.bean.override.mockito`).
- Run: `./gradlew :services:<service>:test`.
