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
bundled contract at `http://localhost:18000/openapi.yaml`. Local mode uses `X-Tenant-Id: <uuid>` instead of tokens.
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
