# Meridian Core

Cloud-native, API-first core banking platform: real-time ledger, deposits, lending, payments,
wallets and mobile money, card issuing and processing, risk and compliance, with web and mobile apps.

- Functional specification: shared Claude doc ("Banking Core Platform — Functional Specification")
- Engineering conventions: [docs/CONVENTIONS.md](docs/CONVENTIONS.md)
- Backend: Java 25 + Spring Boot 4, one service per bounded context, one database per service

## Repository layout

| Path | Contents |
| --- | --- |
| `spec/domains/*.yaml` | Source of truth: services, databases, tables, columns, enums, APIs, events |
| `spec/apps.yaml` | Apps and screens, mapped to the API operations they call |
| `spec/plan.yaml` | Roadmap steps, cross-cutting tasks, requirements, decisions, risks |
| `tools/generate.py` | Validates the spec and generates everything below |
| `generated/db/<database>/V001__init.sql` | PostgreSQL 18 DDL (RLS, append-only triggers, outbox) |
| `generated/openapi/*.openapi.yaml` | OpenAPI 3.1 per domain + bundled `meridian.openapi.yaml` |
| `generated/asyncapi/meridian.asyncapi.yaml` | AsyncAPI 3.0 event catalogue |
| `generated/docs/data-dictionary.md` | Every table and column, with API field mapping |
| `tracker/Meridian_Tracker.xlsx` | Project tracker (dashboard, roadmap, backlog, inventory, traceability) |

## Commands

```bash
pip install pyyaml openpyxl
python tools/generate.py                  # strict: validate + regenerate everything
python tools/generate.py --domain cards   # validate one domain while editing it
python tools/generate.py --lenient        # generate even with unresolved cross-domain refs
npx @redocly/cli lint generated/openapi/meridian.openapi.yaml
```

Edit status, owner, dates and notes directly in the tracker (yellow cells); they are preserved
when the tracker is regenerated.

## Local environment

Toolchain (portable, no admin): `powershell -ExecutionPolicy Bypass -File tools\setup\install-dev-tools.ps1`
installs JDK 25, Gradle, kubectl, Helm, OpenTofu, Temporal CLI, k9s and DBeaver under `D:\DevTools`.
JDK 25 is used by the build through Gradle toolchains; the system JDK and `JAVA_HOME` are left unchanged.

Infrastructure runs on Docker Engine inside WSL (Ubuntu-22.04):

```bash
wsl -d Ubuntu-22.04
cd "/mnt/d/Claude-Projects/Banking Core/infra/local" && docker compose up -d
cd ../.. && bash tools/db/apply-ddl.sh          # (re)create all 52 service databases from generated DDL
```

| Service | Address | Credentials |
| --- | --- | --- |
| PostgreSQL 18 | `localhost:15432` | admin `meridian/meridian`, app role `meridian_app/meridian_app` |
| Kafka (KRaft) | `localhost:19092` | — |
| Schema Registry | http://localhost:18081 | — |
| Kafka console | http://localhost:15080 | — |
| Valkey (Redis) | `localhost:16379` | — |
| Temporal | gRPC `localhost:17233`, UI http://localhost:17280 | namespace `meridian` |
| Keycloak | http://localhost:15180 | `admin/admin` |
| OpenSearch | http://localhost:19200, Dashboards http://localhost:15601 | security disabled (dev) |
| Grafana + OTLP | http://localhost:13000, OTLP `localhost:14317` (gRPC) / `14318` (HTTP) | `admin/admin` |
| Mailpit | UI http://localhost:18025, SMTP `localhost:11025` | — |
| S3 (SeaweedFS) | http://localhost:19000 | anonymous (dev) |

Services must connect as `meridian_app` (not the superuser) so row-level tenant isolation applies.
`tools/db/smoke-test.sql` verifies RLS, append-only tables, optimistic locking, enums and custom fields.

> WSL note: the Ubuntu distro stops when no Windows-side WSL process is attached, taking Docker (and every
> Testcontainers run) with it. Keep it alive while working: `wsl -d Ubuntu-22.04 -e sleep infinity` in a spare
> terminal (or background). Symptom when it stops: "Could not find a valid Docker environment" / connection
> refused to 127.0.0.1:2375 or to a container's mapped port.

## Running services locally

```bash
python tools/dev.py up ledger-service gateway   # build jars, create DBs, start on JDK 25, wait for health
python tools/dev.py up --phase 1                # every phase-1 service + the gateway
python tools/dev.py status | logs <name> | down # inspect / stop
```

The edge gateway serves every API at `http://localhost:18000/v1/...` (routes generated from the spec) and the
bundled contract at `http://localhost:18000/openapi.yaml`. Local mode uses `X-Tenant-Id: <uuid>` instead of tokens:
the launcher starts services and gateway with `MERIDIAN_SECURITY_MODE=dev`; without it every component runs in `jwt`
mode (the default) and the gateway drops identity headers from clients.
When a service's generated schema changes, the launcher recreates that local database (dev data only).

## Developer portal

`web/developer-portal` (Next.js 16, same stack and look as the ops console in `web/meridian-console`) serves the public
API docs and the developer console on http://localhost:13200:

```bash
cd web/developer-portal
npm install
npm run dev          # or: npm run build && npm start   (both run sync:specs first)
npm run sync:specs   # copy generated/openapi + generated/asyncapi into public/specs (+ search index, catalogue)
npm run gen:api      # regenerate typed clients for identity-service and webhook-service
```

- Public (no login): guides (getting started, auth & scopes, idempotency, pagination, errors, webhooks with HMAC
  verification, versioning & changelog), API reference per domain (`/reference/<domain>`), event catalogue
  (`/events`) and client-side full-text search over every operation and event.
- Console (Keycloak SSO via client `meridian-console`, or dev mode with `X-Tenant-Id`): apps = API clients (create,
  scopes, rotate secret, suspend), API keys (one-time reveal, rotate, revoke), webhook endpoints, subscriptions,
  test events, delivery log with retry, event replays, and a sandbox try-it console calling the gateway as the user.
- `/v1/*` is proxied to the edge gateway (`MERIDIAN_GATEWAY_URL`, default `http://localhost:18000`).

## Customer web banking

`web/customer-web` (same stack as the ops console) is the retail customer app on http://localhost:13300: mobile-first,
installable PWA (app-shell caching only, never account data), WCAG 2.2 AA, English and Kiswahili, light/dark.

```bash
cd web/customer-web
npm install
npm run dev          # or: npm run build && npm start
npm run gen:api      # regenerate typed clients (customer channel + sign-up services)
npm run test:e2e     # Playwright smoke with a mocked/absent backend (uses the local Chrome)
```

- Sign in with Keycloak (public PKCE client `meridian-customer-web`; demo user `demo.customer` /
  `Meridian#Customer2026`, set its `customer_id` attribute to a customer of the demo tenant) or in dev mode by picking a
  tenant and customer on `/login`.
- Every signed-in screen calls only the customer channel `/v1/me/*` (`customer-channel-service`, see below); sign-up
  still uses the onboarding services before a customer exists.
- Screens: sign-up (journey-driven onboarding, document upload, KYC status), home, account detail with transactions and
  statements, transfers / payees / domestic payments (validate, step-up, Idempotency-Key, receipt), standing orders,
  cards (freeze, channel controls, limits, lost/stolen), loans (schedule, repay), insights and budgets, rewards, secure
  inbox, profile & security (contacts with OTP, notification preferences, devices). Remaining gaps:
  `web/customer-web/README.md`.

## Customer channel (/v1/me)

Customers never call the domain services. `customer-channel-service` (domain `channels`, scope `channels:customer`)
is the experience API behind `/v1/me/*`; the 60+ domain services keep their operator APIs.

- Identity: a customer token carries scope `channels:customer` and a `customer_id` claim (Keycloak client
  `meridian-customer-web`: admin-managed user attributes `customer_id` / `tenant_id`, plus `auth_time` / `acr`). It has
  no domain scope, so every operator endpoint refuses it (403 `insufficient_scope`); `/v1/me` accepts nothing else.
  `libs/meridian-web` binds it as `CustomerContext` (dev mode: `X-Customer-Id`, `X-Auth-Time`).
- Calls downstream use the channel's service identity for the customer's tenant (`meridian.service-auth.*` required in
  JWT mode) with `X-On-Behalf-Of-Customer`; the customer's token is never forwarded.
- Object-level authorization on every reference (accounts: owner or ACTIVE holder with `can_view` / `can_transact`;
  cards, loans, wallets, payments, payees, budgets, insights, threads, KYC: `customer_id`; notifications: recipient;
  documents: owner; standing orders and statements: their account). Not theirs = 404, audited in `customer_action`
  and published as `channels.customer_action.denied`.
- Bodies are rebuilt server-side (customer, debtor account and currency, creditor details from the payee, sender /
  opener types, document owner, KYC status are never taken from the client); downstream Idempotency-Keys are derived
  per customer.
- Step-up: payments, standing orders (create, resume), loan repayment, payee add / trust, card unfreeze and limits,
  new contact points and device revocation need `auth_time` within `meridian.channels.step-up-max-age` (5 min; optional
  `step-up-acr`), else 401 `step_up_required` with `WWW-Authenticate: Bearer error="insufficient_user_authentication"`.

## Agent app (PWA, works offline)

`web/agent-app` is the cash-in/cash-out agent outlet app (same stack as the console; installable PWA, English and
Swahili) on http://localhost:13400:

```bash
cd web/agent-app
npm install
npm run dev            # or: npm run build && npm start (the service worker registers in production builds)
npm run gen:api        # typed client for the agent channel (/v1/agent, agent-channel-service) only
npm test               # outbox unit tests (ordering, idempotency keys, reconciliation, encryption, limits) + device-key payloads
npm run test:e2e       # Playwright smoke against the build with the API mocked (offline toggling, device proof, PWA shell)
```

- Sign in with Keycloak (realm `meridian`, public PKCE client `meridian-agent-app`; demo user `demo.agent` /
  `Meridian#Agent2026`, set its `agent_id` attribute to an ACTIVE agent of the demo tenant) or dev mode (`X-Tenant-Id` /
  `X-User` / `X-Agent-Id`); the agent comes from the sign-in, the agent picks an outlet, the device is registered with
  a WebCrypto signing key (non-extractable private key in IndexedDB) and a 6-digit app PIN is set. Every call goes to
  `/v1/agent/*` only (see "Agent channel" below and `web/agent-app/README.md` for remaining gaps).
- Screens: home (float and commission balances, today's KPIs, alerts such as low float), cash-in, cash-out (new or by
  reference/QR, customer confirms with PIN or OTP), bills, airtime, assisted onboarding (ID photo from the camera),
  float top-up / rebalance and cash delivery/pickup requests, till open/close with cash count, commission statement,
  history with printable/shareable receipts, sync queue, settings (device heartbeat, language, training offline mode).
- Offline: the service worker serves the app shell and reference data (billers, journeys, agent profile). Cash-in,
  bills, airtime, float requests and onboarding are captured into an outbox in IndexedDB encrypted with AES-GCM under
  a key derived (PBKDF2) from the app PIN and bound to tenant + agent; the key and the agent's wallet PIN live only in
  memory, customer PINs are never stored. Each operation gets its `Idempotency-Key` at capture and is replayed in
  capture order when back online (online event, Background Sync, timer, or "Sync now"); server answers reconcile to
  synced (including idempotent replays), rejected (problem+json shown), or needs-review. Cash-out and anything needing
  live verification is refused offline. Limits (`NEXT_PUBLIC_OFFLINE_MAX_AMOUNT` / `_MAX_TOTAL` / `_MAX_COUNT` /
  `_MAX_HOURS`, defaults 5000 / 20000 / 10 / 12 h) and the last known float bound what can be queued.

## Business banking portal

`web/business-portal` is the business banking portal for SME owners and finance teams (Next.js / React Query /
openapi-fetch / oidc-client-ts, staff-style security headers per SEC-22) on http://localhost:13500:

```bash
cd web/business-portal
npm install
npm run dev            # or: npm run build && npm start
npm run gen:api        # typed client for the business channel (/v1/business, business-channel-service) only
npm run test:e2e       # Playwright smoke with the API mocked (two-approver flow with step-up, entitlement denial)
```

- Sign in with Keycloak (realm `meridian`, public PKCE client `meridian-business-portal`, no password grant) or dev
  mode (`X-Tenant-Id` / `X-Business-Customer-Id` / `X-Business-User-Id` / `X-Auth-Time`). Demo users
  `demo.business.admin` / `Meridian#Business2026` and `demo.business.approver` / `Meridian#Approver2026`: set their
  admin-only `business_customer_id` attribute to a COMPANY customer of the demo tenant. The first user who signs in for
  a business without portal users becomes its ADMIN; the admin adds the approver (Users, role APPROVER) and an IdP
  administrator sets the returned id as the approver's `business_user_id` attribute.
- Screens: cash position, accounts (transactions, statements, CSV / camt.053 export), payments (draft, submit, approval
  queue, approve / reject with step-up, release retry), bulk and payroll CSV upload, payees, direct-debit mandates and
  collections, FX quotes and international payments, virtual accounts and payment links, expense cards, loans and
  credit lines, users / roles / entitlements / approval matrix, API clients, keys and webhooks, security log.

## Business channel (/v1/business)

Business users never call the domain services. `business-channel-service` (domain `channels`, service scope
`channels:business`) is the experience API behind `/v1/business/*`, built like the customer and agent channels.

- Identity: a business token carries scope `channels:business` and the claims `business_customer_id` (the business, a
  non-individual customer) and `business_user_id` (Keycloak client `meridian-business-portal`: admin-only user
  attributes, plus `auth_time` / `acr`). `libs/meridian-web` binds it as `BusinessContext` (dev mode:
  `X-Business-Customer-Id`, `X-Business-User-Id`, `X-Auth-Time`; never for internal, customer or agent tokens; the edge
  gateway drops these headers in jwt mode). The channel's own `business_user` record is authoritative: the user must
  exist for that business and be ACTIVE (403 otherwise); roles and entitlements never come from the token.
- Business-user model (owned by the channel, not customer-service, which only has signatories and relationships):
  roles ADMIN / MAKER / APPROVER / VIEWER, per-user account entitlements (view, initiate, approve, per-payment cap),
  per-user daily limits, and the approval matrix (amount bands per currency for single and/or bulk payments, number of
  approvers, any-order or sequential named approvers; bands may not overlap; self-approval is never possible).
- Payments, bulk payments and payroll: drafted (body rebuilt server-side, content checksum) and submitted by an
  entitled MAKER / ADMIN (cap and daily limit checked under a lock), approved by entitled APPROVER / ADMIN users with
  step-up, released to payment-service only after the final approval, as the maker (`X-User: business-user:<id>`) with
  the key `bzp-release:<id>` (a retry never creates a second payment); business approvals are relayed to
  payment-service's own approval levels as the approvers. FX quotes are priced for the business and bound to one
  payment (SEC-04).
- Object-level authorization on every reference (accounts, payees, payments, mandates, collections, virtual accounts,
  payment links, cards, loans, credit lines, FX quotes, statements; API clients and webhook endpoints through the
  channel's ownership table): another business's object answers 404, audited (`business_action`) and published
  (`channels.business_action.denied`). Webhook subscriptions always carry `customer_id == "<business>"`.

## Agent channel (/v1/agent)

Agents never call the domain services. `agent-channel-service` (domain `channels`, service scope `channels:agent`) is
the experience API behind `/v1/agent/*`, built like the customer channel.

- Identity: an agent token carries scope `channels:agent` and an `agent_id` claim (Keycloak client
  `meridian-agent-app`: admin-only user attributes `agent_id` / `tenant_id`, plus `auth_time` / `acr`). It has no domain
  scope (operator endpoints answer 403 `insufficient_scope`), and `/v1/agent` accepts nothing else (staff, service,
  customer tokens: 403 `agent_identity_required`). `libs/meridian-web` binds it as `AgentContext` (dev mode:
  `X-Agent-Id`, `X-Auth-Time`; never for internal or customer tokens).
- Calls downstream use the channel's service identity for the agent's tenant (`X-User: agent:<id>`); the agent's token
  is never forwarded. Downstream Idempotency-Keys are derived per agent and operation from the client's key.
- Object-level authorization on every reference: outlets and devices (`agent_id`), tills and cash requests (their
  outlet), float requests (`requesting_agent_id`), transactions (`agent_id` or the agent's own wallets), bill payments
  and top-ups (paid from the agent's float wallet), commissions (`beneficiary_agent_id`), onboarding applications
  (`agent_id`, channel AGENT) and their documents, offline batches (`agent_id`). Direct sub-agents are readable (profile,
  KPIs) when `meridian.agent-channel.hierarchy-read` is on, never writable. Not theirs = 404, audited in `agent_action`
  and published as `channels.agent_action.denied`. Customer lookup returns masked data, is logged and rate-limited.
- Bodies are rebuilt server-side (float wallet, currency, channel `AGENT_APP`, requesting agent, rebalance source =
  parent agent, till operator = signed-in user, onboarding channel and agent, document owner).
- Device binding: devices register a public key (ES256 or Ed25519, `agent_device_key`); offline onboarding batches must
  be signed by the device's ACTIVE key over a canonical text (404 for other agents' devices, 422 `device_key_revoked` /
  `device_signature_invalid`); the verified signature is forwarded as `payload_signature`.
- Money movement (cash-in, cash-out and confirmation, bills, airtime, float requests) needs `auth_time` within
  `meridian.agent-channel.step-up-max-age` (5 min) **or** an `X-Agent-Device-Proof` signed by the registered device
  key (timestamp within 5 min), so the offline outbox can replay without a fresh login; device registration needs a
  recent login. Float requests and offline batches store their Idempotency-Key downstream, and the channel's list
  endpoints accept `idempotency_key` to reconcile unknown outcomes.

## Deploying to Kubernetes

One Helm chart deploys a Meridian cell: all 63 domain services plus the edge gateway
(`infra/helm/meridian`, generated by `python tools/helm_chart.py` from `generated/services.json`).

- Images: `tools/build-images.sh <tag> <registry>` (set `PUSH=1` to push) builds one image per component from
  `infra/docker/Dockerfile`: layered jar, JRE 25, non-root uid 10001, read-only root filesystem with `/tmp` as the only
  writable path.
- Install: `helm upgrade --install meridian infra/helm/meridian -n meridian -f values-<env>.yaml`.
  PostgreSQL, Kafka, the OIDC provider and the OTel collector are external; point `global.*` at them. Create the
  secrets listed in the chart notes first (runtime DB role, schema-owner role for Flyway, service client).
- Each component gets a Deployment (zone/host spread, startup/liveness/readiness probes, graceful drain), Service,
  ServiceAccount (no token mounted), HPA, PDB, a NetworkPolicy that only admits Meridian pods and the monitoring
  namespace, and optionally a ServiceMonitor. Services call each other through cluster DNS
  (`MERIDIAN_SERVICES_URL_TEMPLATE=http://{name}`), run in JWT mode, and use central approvals.
- Validate: `helm lint infra/helm/meridian` and `helm template m infra/helm/meridian | kubeconform -strict -summary`
  (CI runs both and checks the chart matches the manifest).
