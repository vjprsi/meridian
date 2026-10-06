# Meridian Core — Engineering Conventions

The `spec/` folder is the single source of truth. Database DDL, OpenAPI, AsyncAPI, the data
dictionary and the Excel tracker are all generated from it by `tools/generate.py`.
Never hand-edit files under `generated/` — change the spec and regenerate.

```
python tools/generate.py          # validate spec + regenerate everything
```

## 1. Spec layout

| Path | Holds |
| --- | --- |
| `spec/domains/<domain>.yaml` | One business domain: services, databases, tables, columns, enums, APIs, events |
| `spec/apps.yaml` | Web/mobile apps, their screens, and which API operations each screen calls |
| `spec/plan.yaml` | Roadmap steps, decisions, risks (feeds the tracker) |

## 2. Domain file format

```yaml
domain: ledger                      # short id, kebab/snake case
title: Ledger and Accounting
phase: 1                            # roadmap phase that delivers it
enums:
  EntryStatus: [PENDING, POSTED, REVERSED]
services:
  - name: ledger-service
    database: ledger_db             # one database per service, schema = database name minus _db
    language: java                  # always java (Spring Boot); field kept for tooling
    description: Posting engine ...
    tables:
      - name: journal_entry
        prefix: je                  # public id prefix shown in docs (je_01H...)
        description: One balanced set of postings
        immutable: true             # append-only: no updated_* cols, UPDATE/DELETE blocked by trigger
        fr: [LED-01, LED-06]        # functional requirement ids from the spec doc
        columns:
          - "status enum:EntryStatus nn default=PENDING | Lifecycle status"
          - "account_id uuid nn fk=ledger_account idx | Account posted to"
        unique: [[tenant_id, idempotency_key]]
        indexes: [[account_id, booked_at]]
    apis:
      - resource: journal-entries   # URL segment and resource name
        path: /v1/ledger/journal-entries
        table: journal_entry
        ops: [list, get, create]    # list | get | create | update | delete
        filters: [status, account_id]
        actions:
          - "POST /{id}/reverse | Reverse a posted entry | reason string nn"
    events:
      - "ledger.journal_entry.posted | journal_entry | A journal entry was posted"
```

### Column notation

`"<name> <type> [modifiers...] | <description>"`

| Type | PostgreSQL | OpenAPI |
| --- | --- | --- |
| `uuid` | uuid | string, format uuid |
| `string(n)` / `string` | varchar(n) / varchar(255) | string, maxLength |
| `text` | text | string |
| `int` / `bigint` | integer / bigint | integer int32 / int64 |
| `amount` | numeric(28,8) | string, format decimal (never float) |
| `rate` | numeric(18,10) | string, format decimal |
| `bool` | boolean | boolean |
| `date` / `ts` | date / timestamptz | string date / date-time |
| `json` | jsonb | object |
| `currency` / `country` | char(3) ISO 4217 / char(2) ISO 3166 | string |
| `email` / `phone` | varchar(320) / varchar(20) E.164 | string |
| `enum:Name` | varchar(40) + CHECK | string enum |
| `bytes` | bytea | (never exposed) |
| `inet` | inet | string |

| Modifier | Meaning |
| --- | --- |
| `pk` | primary key (only for tables with `std: false`) |
| `nn` | NOT NULL (otherwise nullable) |
| `uq` | unique within tenant |
| `idx` | btree index |
| `fk=<table>` | real foreign key to a table in the same database |
| `ref=<table>` | logical reference to another service's table (no FK; resolved by API/events) |
| `default=<v>` | column default (`now`, numbers, enum values, `true/false`) |
| `pii` | personal data: encrypted at field level, masked in logs and consoles |
| `secret` | sensitive (keys, hashes): never returned by the API |
| `ro` | read-only in the API (server-computed) |
| `hide` | never exposed in the API |

### Advanced notation (contract hardening)

| Need | Notation | Generates |
| --- | --- | --- |
| A JSON list column (ids, codes, rules, rows) | `"rules json[] | ..."` | `jsonb` column; OpenAPI `array` (never wrap lists in objects) |
| A JSON object column | `"settings json | ..."` | `jsonb`; OpenAPI `object` |
| A typed list of scalars (ids, codes, currencies, dates, enum values) | `"account_ids uuid[] | ..."`, `"channels enum:Channel[] | ..."` | `jsonb`; OpenAPI `array` with typed items (Java `List<UUID>` etc.) |
| Any JSON value (scalar, list or object, e.g. a rule operand) | `"value json* | ..."` | `jsonb`; OpenAPI untyped (Java `Object`) |
| Child rows sent with the parent on create | table `nested: ["entries list:ledger_entry nn | ..."]` | `entries` array of `<Child>Create` in the create body and of `<Child>` in the response |
| Input accepted on create but not stored as a column (file content, a secret to vault, an initial PIN) | table `request_only: ["content text nn | ..."]` | write-only property on `<Table>Create` |
| A value returned once, never stored (API secret, TOTP seed) | table `response_only: ["client_secret string(128) | ..."]` | read-only response property; no column |
| Action query parameters | `"GET /{id}/balance-at?at:ts!&currency:currency | ..."` (`!` = required) | query params on the operation |
| Action returning a computed JSON result | 4th part `object`: `"POST /calculate | ... | body fields | object"` | `200` with a free-form JSON object |
| Action returning a file/text | 4th part MIME type: `"GET /{id}/download | ... | | text/csv"` | `200` with that content type (string body) |
| Action returning a list of rows | 4th part `table[]` | `<Table>Page` (cursor paging) |
| True no-content action | 4th part `none` | `204` (use only when nothing is returned) |
| Provider callback without bearer token | summary starts with `[public]` | `security: []`, no Idempotency-Key, permitted by the service's security chain; the service must verify the provider signature and resolve the tenant from the referenced entity |

API surface rules (checked by the generator): resource names are unique platform-wide (they become tags and
`*Api` class names); never nest a sub-resource under a path that also has `/{id}/...` actions
(`/v1/campaigns/variants/{id}` vs `/v1/campaigns/{id}/submit` is ambiguous) — use `/v1/campaign-variants` or
`/v1/campaigns/{campaign_id}/variants`.

Rule of thumb: an action whose purpose is to compute, preview, simulate, test, export or download something must
declare `object`, a MIME type or a table — never `none`.

### Standard columns (added automatically)

| Column | Type | Notes |
| --- | --- | --- |
| `id` | uuid PK, `DEFAULT uuidv7()` | time-ordered ids (PostgreSQL 18) |
| `tenant_id` | uuid NOT NULL | row-level security on every table; never in API payloads |
| `created_at` / `created_by` | timestamptz / varchar(64) | |
| `updated_at` / `updated_by` | timestamptz / varchar(64) | omitted when `immutable: true` |
| `row_version` | integer | optimistic locking (`If-Match` header) |

Set `tenant: false` for platform-global tables (e.g. currencies) and `std: false` to define all columns yourself.

## 3. Database rules

- One database per service; services never read each other's tables. Cross-service references use `ref=`.
- Every tenant table has RLS: `tenant_id = current_setting('app.tenant_id')::uuid`.
- Money is `numeric(28,8)` + a `currency` column. Never floats.
- Financial records are append-only (`immutable: true`); corrections are new rows.
- Every service has an `outbox` table (generated) for publish-after-commit events.
- Migrations are forward-only, expand-and-contract (Flyway naming `V001__init.sql`).

## 4. API rules

- REST/JSON, OpenAPI 3.1, `snake_case` fields matching column names 1:1 (the mapping is the column name).
- `/v1/...` versioning; additive changes only within a version.
- `POST` requires `Idempotency-Key`; `PATCH` requires `If-Match: <row_version>`.
- Lists: cursor pagination `?limit=&cursor=` → `{ "data": [...], "next_cursor": "...", "has_more": true }`.
- Errors: RFC 9457 `application/problem+json` with stable `code`.
- Money in JSON: `"amount": "125.50"` (decimal string) + `"currency": "USD"`.
- Auth: OAuth 2.1 (client credentials for servers, auth code + PKCE for apps), FAPI 2.0 for open banking, scopes `<domain>:read` / `<domain>:write`.

## 5. Events

- CloudEvents 1.0 envelope, Kafka topic per domain `meridian.<domain>.v1`, key = aggregate id.
- Name: `<domain>.<entity>.<past_tense_verb>`; payload = the entity's API representation.

## 6. Backend: Java + Spring Boot (all services)

| Concern | Standard |
| --- | --- |
| Language / runtime | Java 25 LTS, virtual threads on (`spring.threads.virtual.enabled=true`), ZGC |
| Framework | Spring Boot 4.x (Spring Framework 7): Web MVC, Validation, Actuator, Security (OAuth2 resource server) |
| Build | Gradle (Kotlin DSL) multi-module monorepo, version catalog `gradle/libs.versions.toml`, Spotless + Error Prone |
| Persistence | Spring Data JDBC / jOOQ for the ledger (explicit SQL, no ORM magic on money paths); Spring Data JPA allowed for non-ledger CRUD services; Flyway migrations copied from `generated/db/<database>/` |
| API | Contract-first: `openapi-generator` (spring, interfaceOnly, useSpringBoot3=true, useJakartaEe=true) generates interfaces + DTOs from `generated/openapi/<domain>.openapi.yaml`; controllers implement them |
| Internal sync calls | gRPC (grpc-spring) on the authorisation and posting paths; Spring `RestClient` elsewhere |
| Messaging | Spring Kafka; outbox relay via Debezium; consumers idempotent by `ce_id` |
| Workflows | Temporal Java SDK (sagas, long-running flows) |
| Resilience | Resilience4j (timeouts, circuit breakers, bulkheads) |
| Observability | Micrometer + OpenTelemetry (traces, metrics, logs), structured JSON logs, `traceparent` propagated |
| Security | Spring Security, JWT validation, method security on scopes, tenant resolved from token → `SET app.tenant_id` per transaction |
| Money | `java.math.BigDecimal` only, `MathContext.DECIMAL128`, explicit `RoundingMode.HALF_EVEN` per product; JSR 354 (Moneta) for currency metadata |
| Testing | JUnit 5, AssertJ, jqwik (property-based), Testcontainers (Postgres, Kafka), Pact JVM, ArchUnit (layer rules), Spring Boot slice tests |
| Packaging | Container images via Buildpacks / Jib; GraalVM native image for gateways where startup time matters |

### Service module layout (hexagonal)

```
services/<service-name>/
  build.gradle.kts
  src/main/java/io/meridian/<domain>/<service>/
    api/            # controllers implementing generated OpenAPI interfaces, mappers DTO <-> domain
    application/    # use cases (command/query handlers), transactions, authorization
    domain/         # entities, value objects, domain services, domain events (no Spring imports)
    infrastructure/ # repositories (JDBC/JPA), Kafka publishers/consumers, outbox, external adapters
    config/         # Spring configuration
  src/main/resources/
    application.yml
    db/migration/   # V001__init.sql from generated/db
  src/test/java/... # unit, slice, integration (Testcontainers), contract tests
libs/               # shared: meridian-common (errors, money, ids, tenancy), meridian-events, meridian-test
```

Package root `io.meridian`. ArchUnit enforces: `domain` depends on nothing; `api` → `application` → `domain`; `infrastructure` implements ports declared in `application`/`domain`.
