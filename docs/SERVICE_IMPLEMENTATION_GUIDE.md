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

## Service identity (JWT mode)

- A call made while handling a user request forwards the user's token (the user's permissions apply).
- A call with no user context (scheduled job, provider callback, Kafka consumer) uses a client-credentials token
  with scope `meridian:internal`; set `meridian.service-auth.token-uri / client-id / client-secret`
  (local: `http://localhost:15180/realms/meridian/protocol/openid-connect/token`, `meridian-service`,
  `meridian-service-secret`). Bind the tenant first (`TenantContext.callAs(tenant, "job:<name>", ...)`);
  ServiceClient sends it as `X-Tenant-Id`, which receivers honour only for internal tokens.
- Records written this way carry `created_by = svc:<client>[/<on-behalf-of>]`.

## Time

- Validity windows, expiries and "is it effective now" checks must use one clock. When SQL compares with `now()`,
  write the timestamps from the database clock too (`select now()`), never `OffsetDateTime.now()` in the JVM —
  the JVM and database clocks differ by milliseconds and cause flaky "not yet valid" bugs.
- Never persist `OffsetDateTime.now()`: use `io.meridian.common.time.Clocks.now()` (UTC, microseconds). PostgreSQL rounds
  sub-microsecond digits, so a raw JVM timestamp can read back in the future and break effective-date checks.
- Store and compare timestamps in UTC; business dates come from business-day-service, not the wall clock.

## Tests (required per service)

- Keep the generated `<Name>ApplicationTests` (context + migration).
- Add `<Name>FlowIT` (see `LedgerPostingIT`): `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
  {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})`, `@Import(MeridianTestContainers.class)`,
  plain `java.net.http.HttpClient` with `X-Tenant-Id` and `Idempotency-Key` headers. Cover: happy path of each
  main flow, invalid transitions, validation errors, idempotent replay, tenant isolation.
- Stub other services with `@MockitoBean ServiceClient` (`org.springframework.test.context.bean.override.mockito`).
- Run: `./gradlew :services:<service>:test`.
