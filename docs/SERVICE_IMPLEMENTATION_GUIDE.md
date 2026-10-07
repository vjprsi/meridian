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
4. Ownership (SEC-03): register every new resource type in workflow-service `meridian.workflow.approval-owners`
   (`<type>: <your-service>`; `ApprovalOwnersRegistryTest` fails otherwise). `Approvals` opens requests with the
   service's own identity; workflow-service refuses a type from any other caller, records `owner_service`, and the
   dispatcher only hands a decision to the owner. Execute from your own pending record, never from the payload alone.
5. Mode: `meridian.approvals.mode=workflow` (set by `tools/dev.py`) or `local` (default; existing tests unchanged).
   Add one `*WorkflowApprovalIT` with `meridian.approvals.mode=workflow`, a mocked `ServiceClient`, and decisions fed
   through `ApprovalEventsDispatcher.dispatch(...)`.

Lessons from the rollout (all 63 services):
- Fingerprint: something that changes when the submission changes but not on unrelated writes (checksum of
  content, `submitted_at`, amount). `row_version` works only if nothing else writes the row while pending. Put the
  submission marker in the idempotency key too, so resubmitting identical content after a rejection opens a new
  request instead of returning the decided one.
- Resubmission: open the new request, then `cancelOpenExcept(type, id, null, newRequestId, "superseded")`.
- Every REJECTED must land somewhere: a REJECTED status, a return to DRAFT, or recorded rejection columns
  (`rejected_by`, `rejection_reason`); for append-only tables a separate append-only rejection record (e.g.
  `gl_journal_rejection`). Never leave a rejected item looking pending, and never make a rework impossible
  (merchant onboarding returns to the maker instead of terminating). Pair it with a direct `reject` endpoint under
  the same four-eyes rule. CHANGES_REQUESTED maps to the same rework path (or a "request info" step).
- Live resources whose status must not change while a change is pending (fraud/limit rules, scenario versions, FTP
  curves, USSD menus): add `POST /{id}/submit` recording `submitted_by` / `submitted_at` (plus what is requested,
  e.g. `submitted_mode`), keep the status, and let the existing activate/publish be the approval; an edit withdraws
  the submission; `reject` clears it and records the rejection.
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
- "Only the owning service may do this" (opening its approval requests, ledger `POST /ledger-accounts/{id}/overdraft-limit`):
  the caller wraps the call in `ServiceClient.asService(() -> client.post(...))` (service token +
  `X-Meridian-Service: <spring.application.name>`, `X-User` still the acting user); the receiver checks
  `TenantContext.callingService()`, which is set only with an internal token (dev mode: the header alone).


Customer requests (`customer-channel-service`, `/v1/me/*`): a customer token (scope `channels:customer`, claim
`customer_id`) is bound as `io.meridian.web.security.CustomerContext` (`CustomerContext.customerId()`, `authTime()`,
`acr()`; dev mode: `X-Customer-Id` / `X-Auth-Time`). ServiceClient never forwards it: those calls use the service token
(so `meridian.service-auth.*` is mandatory there), `X-User: customer:<id>` and `X-On-Behalf-Of-Customer: <id>`.
A domain service that serves customer data relayed this way may honour `X-On-Behalf-Of-Customer` only with an internal
token (inbox-service does, to derive sender / reader types). Customer tokens carry no domain scope, so operator endpoints
refuse them; never add `channels:customer` to a domain operation.

Agent requests (`agent-channel-service`, `/v1/agent/*`): an agent token (scope `channels:agent`, claim `agent_id`) is
bound as `io.meridian.web.security.AgentContext` (`AgentContext.agentId()`, `require().subject()` / `authTime()` /
`userId()`; dev mode: `X-Agent-Id` / `X-User` / `X-Auth-Time`; never bound for internal or customer tokens).
ServiceClient never forwards it either (service token, `X-User: agent:<id>`). `channels:agent` is an exact scope (no
wildcard or admin shortcut); never add it to a domain operation. A service in a channel domain can declare its own
`scope:` in the spec (generate.py), which overrides the domain scope for its operations.

Business requests (`business-channel-service`, `/v1/business/*`): a business portal token (scope `channels:business`,
claims `business_customer_id` and `business_user_id`) is bound as `io.meridian.web.security.BusinessContext`
(`BusinessContext.businessCustomerId()`, `businessUserId()`, `require().authTime()`; dev mode: `X-Business-Customer-Id`
/ `X-Business-User-Id` / `X-Auth-Time`; never for internal, customer or agent tokens). ServiceClient never forwards it
(service token, `X-User: business-user:<id>`). The channel acts as another portal user of the same business (the maker
of a payment it releases, its approvers) with `TenantContext.callAs`, so downstream four-eyes compares real people.
`channels:business` is exact like the other channel scopes; never add it to a domain operation.

Merchant requests (`merchant-channel-service`, `/v1/merchant/*`): a merchant app token (scope `channels:merchant`,
claims `merchant_id` and `merchant_user_id`) is bound as `io.meridian.web.security.MerchantContext`
(`MerchantContext.merchantId()`, `merchantUserId()`, `require().authTime()`; dev mode: `X-Merchant-Id` /
`X-Merchant-User-Id` / `X-Auth-Time`; never for internal, customer, agent or business tokens). ServiceClient never
forwards it (service token, `X-User: merchant-user:<id>`). Approved refunds are created in merchant-service as the
requester and approved as the approver (`TenantContext.callAs`), so its four-eyes rule compares real people. The
merchant's four-eyes does not replace the bank's: merchant-service opens no central request for a refund requested by a
merchant user; when the merchant user's approval meets `meridian.merchant.refund-bank-review` (above `threshold`, per
currency `currency-thresholds`, or a merchant `risk_rating` in `risk-ratings`) the refund becomes
`PENDING_BANK_APPROVAL` and a request opens in the bank's central inbox, which only bank checkers decide (a merchant
user gets 409 `awaiting_bank_approval`); the channel request follows it on read. Payout account changes approved in
the app go to the bank too (`meridian.merchant.payout-bank-review`, default true): central-only request
`merchant_payout_change` (owner merchant-channel-service), applied to merchant-service as the deciding bank checker.
`channels:merchant` is exact like the other channel scopes; never add it to a domain operation.

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

## Account numbering (deposit-service, config-service namespace `account_numbering`)

Account identifiers follow the account's booking legal entity, never a platform default. deposit-service
(`AccountNumbering`, `domain/NumberingRule`) resolves them per account opening (deposit and virtual accounts):

- Country and BIC: the legal entity in tenant-service (`GET /v1/legal-entities/{id}`: `country`, `swift_bic`). An
  unknown legal entity is 422 `legal_entity_not_found`. A BIC must be of the entity's country (else 422
  `account_numbering_invalid`); 8-character BICs get `XXX`. The BIC is stored on `deposit_account.bic`.
- Settings: config-service namespace `account_numbering` (approval-gated, cached 30 s like the ledger settings). One
  entry per legal entity keyed by its code (or id), plus an optional `default`; per field id beats code beats
  `default`; empty strings mean "not set". Fields: `scheme` (`AUTO` = IBAN where the country has an IBAN length, else
  national; `IBAN`; `NATIONAL`), `bank_code`, `branch_code`, `bic` (overrides the entity's), `pattern`,
  `serial_length`, `check_digit` (`LUHN`, `MOD97`, `NUBAN`, `NONE`), `account_number_length`, `iban_length`.
  A home branch (`branch_id`) whose `routing_code` has the scheme's branch-code length replaces `branch_code`.
- IBAN length: `iban_length` setting, else reference-data-service `country.iban_length`, else the built-in SWIFT
  registry table (`AccountNumbers.IBAN_LENGTHS`). Client-supplied IBANs are checked for structure, country length and
  mod 97.
- IBAN scheme: account number = account part of the BBAN (digits, last one Luhn); BBAN = bank + branch + account in
  the country's layout (`AccountNumbers.IBAN_COUNTRIES`: GB, IE, DE, NL, AT, CH, BE, LU), or bank + branch + account
  filling the registered length elsewhere; IBAN = country + ISO 7064 mod 97-10 digits + BBAN. Where the layout's bank
  code is alphabetic (GB, IE, NL) and no `bank_code` is set, the BIC's first four letters are used. National check
  digits inside some BBANs (FR RIB key, ES, IT CIN, BE, PT) are not generated: configure such countries as `NATIONAL`
  or set the full rule before go-live. `routing_code` = branch (sort) code, else bank code.
- National scheme: number = `pattern` with `{bank}`, `{branch}`, `{serial}` (random digits, first not 0) and
  `{check}`; the check digit(s) are computed over bank code + branch code + serial. Known formats:
  - KE (no IBAN): CBK bank code 2 digits, branch code 3 digits, number = branch + 9-digit serial + Luhn digit over
    bank + branch + serial (13 digits); BBAN = bank + number; `routing_code` = bank + branch (the 5-digit bank-branch
    code used by KEPSS / PesaLink). Kenyan banks have no single national check-digit standard; this is Meridian's.
  - NG: NUBAN, bank code 3 digits, serial 9 + NUBAN check digit (weights 3, 7, 3 over bank + serial).
  - Elsewhere: generic `{serial}{check}` with Luhn, `account_number_length` (default 10) digits, configurable.
- Unconfigured: a country whose format needs a bank / branch code that is not set is 422
  `account_numbering_not_configured` (no fallback to another country's values). The deployment properties
  `meridian.deposit.country`, `bank-code`, `branch-code`, `account-number-length` (unset by default) apply only to
  legal entities of that country, for single-tenant installs and tests.
- Uniqueness: numbers and IBANs are unique per tenant across deposit and virtual accounts. Each candidate is locked
  with `pg_advisory_xact_lock` before the existence check, so concurrent openings drawing the same number serialise;
  up to 50 candidates are tried (409 `account_number_exhausted`).
- Starter packs create the main entity's entry (`numbering_main`, key = `legal_entity_code`, parameters
  `account_number_scheme`, `bank_code`, `branch_code`, `bic`); KE defaults to `NATIONAL` with placeholder codes.
- wallet-service wallet numbers (12 digits, Luhn) carry no country and are not affected.

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
- Public sign-up journey: both packs create the onboarding journey `RETAIL_ONLINE` (customer-web's
  `NEXT_PUBLIC_SIGNUP_JOURNEY` default; channels WEB and MOBILE_APP; typed fields per step: phone OTP, personal
  details, address, identity document upload, terms and marketing consent; KE adds national ID, KRA PIN, +254 numbers
  and county) for the main legal entity and the current and savings products. It is created as DRAFT; publication is
  a four-eyes checker action in onboarding-service (`manual`, AWAITING_ACTIVATION). A published journey is left alone.
- A new pack item must pass `StarterPackDefinitionTest`: the route must exist in `generated/routes.json` and the
  rendered body must match the request schema in `generated/openapi`.
- Regulatory reporting: KE adds a `regulatory_pack` CALL item (step `end_of_day`) that installs the `KE_CBK`
  regulatory pack in regulatory-reporting-service for the main legal entity (see "Regulatory packs" below). Its report
  versions are DRAFTs whose publication requests have the pack's acting user as maker; `install_regulatory_pack=false`
  makes the install a no-op (`enabled=false`).
- Data-driven keys and repetition: object keys may hold placeholders (`"${param.currency}": ...`). An item with
  `for_each: "${param.<list>}"` becomes one item per value (key `<key>.<value lower case>`, `${each}` / `${each.lower}`
  inside, also in references such as `${ref.ledger_book/fx_position.${each.lower}}`); an object with a
  `$for_each: "${param.<list>}"` entry becomes one copy of its other entries per value, so maps keyed by currency can be
  rendered (fx-service `position_accounts.<CCY>` from `fx_currencies`). Repetition is expanded with the run's
  parameters before the run; the catalog validates packs expanded with the defaults.

## Regulatory packs (regulatory-reporting-service)

Versioned regulatory report packs ship as data in `services/regulatory-reporting-service/src/main/resources/regulatory-packs/`
(one YAML per pack; the file carries its current version): `KE_CBK` (Kenya: CBK prudential, FRC CTR/STR, complaints,
agent and mobile money), `GB_PRA` (United Kingdom: COREP / FINREP-like own funds, LCR, NSFR, large exposures and
statements as XBRL-like instances, FCA complaints and financial-crime returns) and `NG_CBN` (Nigeria: CBN capital,
liquidity ratio + LCR / NSFR, single obligor limit, monthly balance sheet return, NFIU CTR/STR, complaints, agent
banking). **Every layout, cell code, threshold, risk weight, deadline and holiday list is ILLUSTRATIVE**, not a copy
of a regulator's form or taxonomy: validate each report against the regulator's current taxonomy and instructions
before a filing (the UK packs reference a placeholder taxonomy).

- Pack file: `calendar` (business-day convention, holidays), `gl_mapping_tables`, `source_templates` (reused as
  `{name, use: <template>}`), `reports` (definition fields + `version` with `data_sources`, `field_mappings`,
  `validation_rules`, `sample.expect`) and `sample_data`. Templates and GL tables are expanded at install, so each
  installed version is self-contained.
- API: `GET /v1/reporting/pack-installations/catalog`; `POST /v1/reporting/pack-installations/install`
  `{pack_code, version?, legal_entity_id?, dry_run?, enabled?}`. Idempotent: missing reports are created (DRAFT
  definition + DRAFT version whose publication request goes to central approvals, so a checker publishes); pack-owned
  definitions (`pack_code`) whose managed fields changed are updated (`filing_offset_days` and `owner` stay tenant-tuned);
  changed version content (checksum on the version) adds a DRAFT version (older pack DRAFTs are withdrawn); a version a
  checker rejected is not re-submitted; tenant-defined reports with the same code are never touched; pack-owned reports
  no longer shipped are deprecated. One `regulatory_pack_installation` row per pack keeps the last result.
- Engine features the packs use: SERVICE sources with `resolve` (look up a missing run parameter, e.g. the GL
  accounting period whose `end_date` is the period end) and `gl_mapping` (trial-balance rows get `gl_map.line`,
  `gl_map.sign` and the rule's attributes such as `risk_weight`, first matching code / prefix / range wins); `COMBINE`
  sources (group earlier sources by a key: exposures per counterparty across loans, collateral and money-market deals);
  aggregates `COUNT_DISTINCT` and `TOP` (rank-th largest by `order_by`); `DIV(a, b)` (NULL on division by zero);
  placeholders `{period_start_ts}` / `{period_end_ts}` (exclusive) for `created_after` / `created_before` filters;
  run built-ins available to expressions as `param_legal_entity_id`, `param_as_of_date`, `param_period_start`,
  `param_period_end`.
- GL mapping overrides per tenant: config-service namespace `regulatory_reporting`, key `gl_mapping.<table lower case>`
  (e.g. `gl_mapping.ke_cbk_gl`), value `{rules: [...], replace: false}`; tenant rules match before the pack's (or replace
  them). An invalid override or an unreachable config-service fails the run (never a silent fallback).
- Filing calendar: definitions carry `business_day_convention` (FOLLOWING, MODIFIED_FOLLOWING, PRECEDING, NONE) and
  `holidays`; business-day deadlines (`PERIOD_END+7BD`) skip weekends and holidays.
- Tests: `RegulatoryPackDefinitionTest` (routes, query parameters and enum values, mapped fields against the generated
  response schemas, compilation, calendars, sample expectations) and `RegulatoryPackFlowIT` (KE install, publication,
  capital / liquidity / large-cash runs on stateful fakes, validation failure, drilldown, GL override, upgrades).
- Limits: list sources read at most 50 pages of 200 rows per run (loans, agents, outlets, wallets, monitored
  transactions); large portfolios need pre-aggregated sources. Large exposures rank the five largest counterparties
  without connected-client grouping; market risk comes from the run parameter `market_risk_rwa`.

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
`meridian.business-date.source=clock`. Calendar-period counters (daily/weekly/monthly limits) follow the business
date; rolling windows stay on the clock. Adopted in payment, deposit, loan, wallet, billing, collections,
treasury-deal, statement, fx, routing, rewards, insights, campaign, agent-network, commission, crm, disbursement,
limits, sanctions and product-catalog services.

## Security defaults and helpers (security wave 3)

- **Security mode.** `meridian.security-mode` is `jwt` everywhere by default. Dev mode (headers instead of tokens) is
  opt-in: `tools/dev.py` sets `MERIDIAN_SECURITY_MODE=dev` for services and gateway, and the Gradle test tasks of every
  service set it too (a test can still pick `jwt` in `@SpringBootTest(properties=...)`). Dev mode refuses to start in a
  Kubernetes pod unless `meridian.security.allow-dev-mode-in-cluster=true`. In jwt mode the edge gateway strips the
  internal identity headers (`X-Tenant-Id`, `X-User`, `X-Roles`, `X-MFA-Verified`, `X-Customer-Id`, `X-Agent-Id`,
  `X-Business-Customer-Id`, `X-Business-User-Id`, `X-Merchant-Id`, `X-Merchant-User-Id`,
  `X-Auth-Time`, `X-Acr`, `X-On-Behalf-Of-Customer`, `X-Meridian-Service`, ...) and owns `X-Forwarded-For`.
- **Tokens.** Every access token must carry the audience `meridian-api` (or the service name):
  `meridian.security.audiences`; restrict clients per service with `meridian.security.allowed-clients`.
- **Scopes.** Every `/v1` handler needs `@RequiredScope` (generated) or `@PublicEndpoint`; spec-`[public]` routes are
  the only other exception. `platform:operator` (generated for platform-global writes and `operator_only: true` tables)
  is honoured only for internal tokens or users of `meridian.security.platform-operator-tenant`; `meridian:admin` is a
  tenant superuser and does not imply it.
- **Provider callbacks.** Verify with `CallbackVerifier.verify(provider, tenantOrEntity, body, signature,
  CallbackVerifier.Headers.of(request), signer)`, ship `V001_50__callback_nonce.sql`, and document the v2 scheme to the
  provider: HMAC over `<X-Callback-Timestamp>.<X-Callback-Nonce>.<tenant or entity>.<body>`, nonce unique per delivery
  attempt. `CallbackVerifier.cachedBody()` gives the raw body of `[public]` requests.
- **Idempotency.** The stored fingerprint covers method, path, caller and body: never reuse a key for another resource,
  operation or user (422 `idempotency_key_reused`). Deterministic keys must embed the resource id.
- **Four-eyes.** Use `io.meridian.common.tenancy.FourEyes.require(maker, checker, message)`; it fails closed when the
  maker is unknown (422 `four_eyes_maker_unknown`).
- **Tenant regexes.** Match with `io.meridian.common.text.SafeRegex` and validate on save with `SafeRegex.problem`.
- **Check-then-act across replicas** (inbound messages, external callbacks): `AdvisoryLocks.withLock(key, wait, work)`.
- **Body size.** `meridian.http.max-body-size` (16MB default); raise it only for upload services.

## Governed ML models (fraud-service, ai-platform-service)

Models are trained outside the platform, registered in ai-platform-service (version + `artifact_sha256`, inline
artefact via `POST /v1/ai/model-versions/{id}/artifact`, four-eyes approval, shadow -> canary -> champion) and run
in-process by the consuming service. See `docs/ML_FRAUD_TRAINING.md` for the fraud runtime, artefact format and
retraining loop. Rules for any service doing the same:

- Load artefacts with the service identity, verify `sha256(content) == artifact_sha256` and the feature list, compile
  once per (version, checksum); never score with an artefact that failed either check.
- Only the deciding deployment (champion, or the canary of the subject's slice) may influence the outcome; challengers
  and shadows are scored and logged, never decide.
- Fail safe: a missing / failing / slow model falls back to the rule-based path and the result says `degraded`.
- Features used online must be catalogued in ai-platform (`/v1/ai/features`, `definition` = exact computation,
  `feature_set`); consumers of domain events de-duplicate on `ce_id` in the same statement as their writes.
- Reload on `reporting.model_deployment.*` events with a per-instance consumer group (every replica must reload).

## Fraud scoring of payments (payment-service -> fraud-service)

Every outbound and internal payment (single, bulk item, refund, request-to-pay, link and QR payments; inbound credits
excluded) is scored after validation and sanctions screening and before limits, approvals and funds reservation:

- Request: `POST /v1/risk/fraud/scores/evaluate`, `subject_type PAYMENT`, `subject_id` = payment id, Idempotency-Key
  `pay-fraud:<payment id>` (a retry replays the stored evaluation), `customer_id`, `account_id` (debtor ledger
  account), amount / currency, `channel` (RiskChannel mapping of the payment channel), `counterparty_country`, the
  channel session from `payment.risk_context` (`device_fingerprint`, `ip_address`, `ip_country`, `session_id`; set
  server-side by customer-channel-service) and `context` keyed as fraud-service's feature store expects
  (`debtor_account_id`, `payee_id`, `beneficiary_account_digest` + `beneficiary_bic`, `merchant_id`,
  `bulk_payment_id`, ...; no names, and the account number only as its canonical digest
  `sha256("BENEFICIARY_ACCOUNT:" + number without spaces/dashes, upper case)`). Budget
  `meridian.payment.fraud.timeout-ms` (2000).
- fraud-service pseudonymises every identifier of an evaluation (account numbers / IBANs, card numbers or fragments,
  phone, e-mail, device ids, national ids; by field name and by shape) with a tenant-keyed HMAC before rules, features
  and storage (`fraud_score.features`, list entries, feature-store keys); the key comes from the required secret
  `meridian.fraud.pseudonym-key` (`RequiredSecrets`, Helm `meridian-fraud-service-secrets/pseudonym-key`). Send
  identifiers minimised where you can (`<name>_digest`). See `docs/ML_FRAUD_TRAINING.md` section 7 (including the
  one-off `POST /v1/risk/fraud/scores/pseudonymise-stored`, scope `risk:*`).
- ALLOW continues unchanged. REVIEW (and STEP_UP, `step-up-action: REVIEW`) moves the payment to `ON_HOLD`
  (`hold_reason FRAUD_REVIEW`, history reason code `FRAD`, event `payments.payment.held`) with `fraud_alert_id`.
  BLOCK rejects it (`rejection_code FRAD`, `rejection_reason FRAUD_BLOCKED: ...`) before any hold or posting.
- `risk.fraud_alert.resolved` of a PAYMENT alert (FraudAlertListener, topic `meridian.risk.v1`) releases the hold
  (FALSE_POSITIVE, CLOSED: `payments.payment.released`, then limits / approvals / execution; bulk items keep skipping
  business approval and use the batch hold) or rejects it (CONFIRMED_FRAUD: `FRAUD_CONFIRMED`). Analysts can do the same
  with `POST /v1/payments/{id}/fraud-review-decision` (RELEASE / REJECT).
- Unavailable scoring (timeout, error, fraud-service `fallback: true`): `fraud_degraded = true`, a status-history row
  and `payments.payment.fraud_degraded`; the payment is held for review (fail-closed) at or above
  `fail-closed-amount` (1000, per channel `channel-fail-closed-amount.<CHANNEL>`) and continues below it (fail-open).
  `POST /v1/payments/{id}/fraud-rescore` scores a degraded hold again with the same key.
- Degraded holds are also re-scored automatically (`FraudRescoreJob`): `fraud_next_rescore_at` is set when the hold is
  placed (`auto-rescore.initial-backoff` 1 min), each still-unavailable attempt doubles the delay up to `max-backoff`
  (1 h) and counts `fraud_rescore_attempts`; after `max-attempts` (12) the hold waits for an analyst. The internal
  scheduler (`auto-rescore.interval-ms`, every tenant with activity from the RLS-free `idempotency_key` table) and
  `POST /v1/payments/execute-due` (result `fraud_rescore`) both run it; a hold is claimed with a conditional update so
  replicas never re-score the same payment twice, and the key `pay-fraud:<id>` makes fraud-service replay an
  evaluation it already completed.
- Wallet payouts (wallet-to-bank to another bank) are created by wallet-service with its own identity
  (`ServiceClient.asService`): `debtor_wallet_id` + `debtor_clearing_ledger_account_id` (the clearing account the wallet
  transaction credited; `meridian.payment.wallet-funding-services`) and `upstream_fraud_score_id` = the wallet
  transaction's ALLOW evaluation. payment-service reads that evaluation from fraud-service and reuses it instead of
  scoring again only when it is an ALLOW of a `WALLET_TRANSACTION` for the same amount and currency (and customer when
  both name one), at most `upstream.max-age-seconds` (900) old and not used by another payment; otherwise it scores
  normally (history row says why). Both fields are honoured only from `upstream.services` (wallet-service) over an
  internal token (`TenantContext.callingService()`); from a user, customer or channel token the upstream id is
  dropped and wallet funding is refused (`wallet_not_supported`).
- The payment keeps `fraud_score` (0..1), `fraud_score_id`, `fraud_decision`, `fraud_alert_id`, `fraud_degraded`; the
  tracker shows them under `fraud` (stage `UNDER_REVIEW` while held). customer-channel-service strips them from
  customer responses.

## Tests (required per service)

- Keep the generated `<Name>ApplicationTests` (context + migration).
- Add `<Name>FlowIT` (see `LedgerPostingIT`): `@SpringBootTest(webEnvironment = RANDOM_PORT, properties =
  {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})` (the test task already sets dev mode; keep it
  explicit in ITs), `@Import(MeridianTestContainers.class)`,
  plain `java.net.http.HttpClient` with `X-Tenant-Id` and `Idempotency-Key` headers. Cover: happy path of each
  main flow, invalid transitions, validation errors, idempotent replay, tenant isolation.
- Stub other services with `@MockitoBean ServiceClient` (`org.springframework.test.context.bean.override.mockito`).
- Run: `./gradlew :services:<service>:test`.
