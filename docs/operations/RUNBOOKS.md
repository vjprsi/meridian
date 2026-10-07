# Operational runbooks

Last reviewed: 2026-10-07 · Owner: TBD (SRE lead) · Applies to: cells deployed with `infra/helm/meridian` and routers
deployed with `infra/helm/meridian-router` (see README "Deploying to Kubernetes")

Conventions: `$NS` = cell namespace (`meridian`), `$REL` = Helm release (`meridian`), `$CELL` = cell code, API calls go
to the cell gateway (or through the router) with an operator token unless stated. Every write needs an
`Idempotency-Key`; use `uuidgen` and record it in the incident ticket. Commands that need a second person are marked
**[4-eyes]**. Steps that rely on something not built yet are marked **[gap]** with the gap id from
[docs/compliance/README.md](../compliance/README.md#gap-register).

## 1. Deploy and rollback (Helm)

Pre-deploy checklist:

1. CI green on the release commit (spec, DDL on PostgreSQL 18, build + ITs, chart lint / kubeconform, image build).
2. Images built and pushed for the tag: `PUSH=1 tools/build-images.sh <tag> <registry>`.
3. Chart rendered and diffed against the running release:
   `helm diff upgrade $REL infra/helm/meridian -n $NS -f values-<env>.yaml --set global.image.tag=<tag>` (helm-diff plugin).
4. Migrations: Flyway runs forward-only, expand-and-contract (`docs/CONVENTIONS.md` section 3). A release must not
   contain a "contract" step (drop / rename) for a column that the previous release still reads. Migrations run in the
   `<service>-migrate` hook Jobs before any Deployment changes (GAP-22, see below), so the previous pods keep serving
   on the migrated schema while the new ones roll out.
5. Change record with approver **[gap: GAP-05, no pipeline; record in the ticket]**.

Deploy:

```bash
helm upgrade --install $REL infra/helm/meridian -n $NS -f values-<env>.yaml --set global.image.tag=<tag> \
  --atomic --timeout 20m --history-max 20
kubectl -n $NS rollout status deploy --timeout=15m          # every Deployment: maxUnavailable 0, maxSurge 1
```

Migration Jobs (GAP-22): `helm upgrade` first runs one `<service>-migrate` Job per service (hook `pre-upgrade`,
weight -10; Flyway as `meridian-db-owner`, then the process exits). Hooks run one after another, and `--timeout`
applies to each hook. If one fails, Helm stops before touching any Deployment (with `--atomic` the release is rolled
back; the schema change of an already finished Job stays, which is safe because migrations are expand-only):

```bash
kubectl -n $NS get jobs -l app.kubernetes.io/component=db-migration
kubectl -n $NS logs job/<service>-migrate           # Flyway output: "Migrate-only run finished: N migration(s)"
```

Fix the cause (lock held by a long transaction, owner password rotated without updating the Secret, failed
statement) and re-run `helm upgrade`; the hook policy `before-hook-creation` replaces the failed Job. To migrate one
service on its own, run its image with the argument `migrate` and the same three variables (`SPRING_DATASOURCE_URL`,
`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`). Running pods never migrate (`SPRING_FLYWAY_ENABLED=false`) and never
hold the owner credentials.

Verify: router `meridian.router.requests{outcome}` error share back to baseline; `http.server.requests` 5xx per
service; `GET /actuator/health/readiness` of changed services; run `python tools/e2e/journeys.py` against staging (all
8 journeys) before production.

Rollback:

- Application only (no schema change): `helm rollback $REL <previous revision> -n $NS --wait` (`helm history $REL -n $NS`).
- With a migration: forward-only, so rolling back the image is safe only if the migration was additive (expand). If not,
  roll **forward** with a fix. Never run down-migrations by hand.
- Router: `helm rollback router <rev> -n meridian-edge`; routers are stateless.
- Standby cells: deploy the same tag to the standby (`global.cell.role=standby`) right after the primary so a failover
  does not change versions.

## 2. Secret and key rotation

General rule: values live in AWS Secrets Manager (encrypted with the cell KMS key, replicated to the DR region by the
region module) and reach pods as Kubernetes Secrets named in the Helm NOTES (`meridian-<service>-secrets`, keys per
`secretEnv` in `values.yaml`). Services refuse to start with a missing, short (< 32 chars) or development value
(`RequiredSecrets`); a bad rotation therefore shows up as CrashLoopBackOff of that service only, not as silent
weakening. After updating a Secret: `kubectl -n $NS rollout restart deploy/<service>` and watch readiness.

| Secret | Env var / Secret key | Rotation impact | Procedure |
|---|---|---|---|
| DB runtime role | `meridian-db-app` username / password | Pods reconnect after restart | Create new password in Secrets Manager -> `ALTER ROLE meridian_app PASSWORD ...` -> update Secret -> rolling restart all |
| DB owner (Flyway) | `meridian-db-owner` | Only used by the `<service>-migrate` hook Jobs (GAP-22 closed for pods: no Deployment mounts it) | Same as above (`ALTER ROLE meridian ...`); no restart needed, the next release's Jobs pick it up |
| Service client | `meridian-service-client` (`client-id`, `client-secret`) | All service-to-service calls | Add a second secret in the IdP if supported, update Secret, rolling restart, revoke old |
| Cell router client | `meridian-cell-router-client` | Router registry reads (stale-if-error caches 15 min) | Rotate in IdP, update Secret in `meridian-edge`, restart routers one region at a time |
| Callback HMAC secrets | `MERIDIAN_ROUTING_CALLBACK_SECRET`, `MERIDIAN_CARDS_CALLBACK_SECRET`, `MERIDIAN_LOAN_ORIGINATION_CALLBACK_SECRET`, `MERIDIAN_ESIGN_CALLBACK_SECRET`, `MERIDIAN_CARDS_AUTH_JIT_SECRET` | Only one secret is accepted at a time: callbacks signed with the old secret fail during the cut-over (providers retry with a new nonce) | Agree a cut-over minute with the provider; update Secret; restart the service; provider switches; watch callback 401 rate. Prefer per-rail / per-provider secrets (`callback-secrets.<CODE>`) so one provider rotates alone |
| Link signing keys | `MERIDIAN_FILES_SIGNING_KEY`, `MERIDIAN_STATEMENT_DOWNLOAD_SECRET`, `MERIDIAN_AUDIT_DOWNLOAD_SIGNING_KEY` | Outstanding download links become invalid (short TTL) | Rotate any time; announce to ops that open links must be re-issued |
| OTP / token keys | `MERIDIAN_CARDS_TOKENS_OTP_KEY`, `MERIDIAN_CARDS_TOKENS_PAYLOAD_KEY`, `MERIDIAN_CARDS_THREEDS_OTP_KEY`, `MERIDIAN_CARDS_THREEDS_CAVV_KEY`, `MERIDIAN_ONBOARDING_PUBLIC_OTP_SECRET`, `MERIDIAN_ONBOARDING_RESUME_TOKEN_SECRET` | In-flight OTPs / resume tokens / CAVVs fail verification | Rotate in a low-traffic window; applicants re-request codes |
| Counterparty reference key | `MERIDIAN_REFS_COUNTERPARTY_KEY` (Secrets of ledger-service and reconciliation-service, key `counterparty-key`; same value in both) | `counterparty_account_hash` on append-only journal / ledger entries cannot be recomputed: entries posted before the rotation stop matching counterparty-account rules | Rotate only on compromise; update both services together; expect lower auto-match on older open items until they clear |
| Webhook endpoint signing secrets (per endpoint) | stored encrypted in webhook-service | None: old secret valid 24 h | `POST /v1/webhook-endpoints/{id}/rotate-secret` (tenant or developer portal) |
| Webhook encryption key | `MERIDIAN_WEBHOOKS_KEY` | Stored endpoint secrets become unreadable | **Do not rotate** until a re-encryption job exists **[gap: GAP-21]**; on compromise rotate every endpoint secret right after |
| MFA seed key | `MERIDIAN_IDENTITY_MFA_SEED_KEY` | Encrypted TOTP seeds become unreadable | **Do not rotate** without re-encryption **[gap: GAP-21]**; on compromise rotate and force TOTP re-enrolment of all staff |
| API keys / API client secrets (tenants) | identity-service | Grace period for API keys | `POST /v1/api-clients/{api_client_id}/keys/{id}/rotate` (grace hours), `POST /v1/api-clients/{id}/rotate-secret` |

### 2.2 Card-vault keys

Key hierarchy: one master key (`MERIDIAN_CARDS_VAULT_MASTER_KEY` or `..._MASTER_KEY_FILE`, Secret
`meridian-card-vault-service-secrets/master-key`, 32 random bytes base64) -> versioned keys derived per label
(PAN_DEK, CVV_KEY, PIN_KEY tenant-scoped `<label>@<tenant>`; ZPK, CVK, MDK shared) by `SoftwareHsm`.

- **DEK rotation (routine, e.g. yearly)** **[4-eyes]**: a user with `cards:vault_admin` and a recent step-up (or the
  service identity) calls `POST /v1/card-vault/rotate-keys {"key_type":"PAN_DEK"}`. Records are re-encrypted in
  500-record transactions; after 20 batches the rest continues in the background (`status: IN_PROGRESS`). If the pod
  dies mid-way, records stay readable under the old version; run the same call again to finish. Event
  `cards.card_vault.keys_rotated`. Check `GET /v1/card-vault/keys?key_type=PAN_DEK` shows one ACTIVE version.
- **ZPK / PIN key rotation**: coordinate with the card gateway / scheme (zone keys are shared with the counterparty);
  there is no grace period for the old ZPK **[gap: SPEC_BACKLOG Cards "ZPK grace period"]**. Schedule a cut-over.
- **Master key rotation**: **not supported in place** - every key derives from the master, and no tool re-encrypts the
  vault under a new master **[gap: GAP-01]**. Until a real HSM / KMS adapter exists, a master key change requires a
  dedicated migration (decrypt with old, encrypt with new, verify counts and fingerprints) run under dual control in a
  maintenance window. Treat any suspicion of master key exposure as a PCI incident (section 3).

### 2.4 Fraud pseudonym key (`MERIDIAN_FRAUD_PSEUDONYM_KEY`)

Pseudonyms are one-way (`HMAC(tenant key, digest)`): stored `h1:` values cannot be re-keyed, so rotating the key means
**fraud lists stop matching and feature-store history restarts** for every tenant. Rotate only on compromise:

1. Export list definitions from the source systems (the clear values are not in fraud-service).
2. Update Secret `meridian-fraud-service-secrets/pseudonym-key` (>= 32 chars), restart fraud-service.
3. Re-import fraud list entries (they are hashed with the new key on write).
4. Expect a temporary drop in velocity-rule effectiveness; tighten fail-closed thresholds for a few days if needed.

`POST /v1/risk/fraud/scores/pseudonymise-stored` is **not** a rotation tool: it only converts legacy plain SHA-256
values and clear contexts to `h1:` under the current key (`{"dry_run": true}` first, repeat until `complete: true`).

### 2.5 Contact lookup key (`MERIDIAN_CONTACT_LOOKUP_KEY`) and customer secret key (`MERIDIAN_CUSTOMER_SECRET_KEY`)

The contact lookup key is shared by customer-service and search-service: both read the **one** Secret
`meridian-contact-lookup` (key `key`, chart `sharedSecretEnv`), so the values cannot drift apart. Both services
declare it in `meridian.required-secrets` and refuse to start outside dev mode when it is missing, short or a
committed default. customer-service also requires `MERIDIAN_CUSTOMER_SECRET_KEY` (Secret
`meridian-customer-service-secrets`, key `secret-key`: identifier hashes, OTP peppering, passkey challenges).

Customer secret key rotation: identifier `value_hash` values (duplicate detection) are keyed with it, so rotate only on
compromise; pending OTPs and passkey challenges fail and are re-requested.

Contact lookup key rotation:

1. Update `meridian-contact-lookup`, restart customer-service and search-service together (contact search by
   exact value returns nothing until step 2 completes).
2. Per tenant, repeat until `done: true`:
   `POST /v1/customers/contact-search-backfill {"rehash_all": true, "batch_size": 2000, "after_id": <next_after_id>}`
   (resumable; republishes `party.customer_contact.verified` with the new hash; search-service re-indexes).
3. Verify: search a known contact; check `meridian.search.indexer.lag` returns to normal.

## 3. Key or secret compromise

Severity SEV1 if card keys, callback secrets of a payment rail, the service client or DB credentials are involved.

1. Declare incident, IC + security lead; open a restricted channel; start the timeline.
2. Contain: rotate the secret (section 2); for callback secrets also set the provider's endpoint to refuse traffic
   until rotated; for the service client revoke it in the IdP (all services will fail calls until restarted with the
   new secret - plan a coordinated restart).
3. Card keys: freeze reveal (`cards:pan_reveal` removed from roles in the IdP), assess data exposure via
   `GET /v1/card-vault/access-logs`, notify the PCI program owner, schemes and the acquirer / issuer banks per contract;
   re-encrypt under new keys; consider card reissue for exposed PANs.
4. Callback secret: review inbound messages since the suspected exposure (`routing-service` inbound log,
   `payments.payment.*` inbound credits) and reconcile with the rail's statement (section 6).
5. If any non-local environment ever ran with committed dev keys (pre SEC-01 / SEC-02): follow the SECURITY_REVIEW
   remediation follow-ups (new values, re-encrypt `card_data_vault`, purge OTP-bearing `notification.body` rows and their
   audit / webhook copies).
6. Post-incident review within 5 business days (section 12).

## 4. End-of-day (EOD) failure handling

EOD is orchestrated by business-day-service: standard job pack (54 jobs, version 2026.10.3) installed per tenant,
started per legal entity (`POST /v1/operations/business-days/{id}/start-eod`) or by the cross-tenant trigger
(`POST /v1/operations/eod-sweeps/run`, platform operator; scheduler `meridian.business-day.auto-eod.enabled`).

Signals: events `operations.eod_run.failed`, `operations.eod_job_run.failed`, `operations.eod_sweep.tenant_failed`;
metrics `meridian.eod.sweep.runs{trigger,status}`, `meridian.eod.sweep.duration`, `meridian.eod.sweep.skipped`,
`meridian.eod.trigger.decisions{decision}`.

1. Find the run and failing jobs: `GET /v1/operations/eod-runs?status=FAILED`, then `GET /v1/operations/eod-runs/{id}/jobs`.
2. Read the job error, check the owning service (job pack entry: owning service, method, path) and its logs.
3. Fix the cause (data, downstream outage, missing config: missing ledger config answers 422 everywhere).
4. Retry the job: `POST /v1/operations/eod-job-runs/{id}/retry`; or restart the run from failed jobs:
   `POST /v1/operations/eod-runs/{id}/restart` (jobs are idempotent per business date).
5. A non-critical job that cannot be fixed tonight: `POST /v1/operations/eod-job-runs/{id}/skip` **[4-eyes]** with a
   reason; open a ticket to run it later.
6. Roll the date only after a successful (or explicitly accepted) EOD: `POST /v1/operations/business-days/{id}/roll`
   (`forced: true` requires a reason and is audited). Reopening a closed date: `POST .../reopen` (approval).
7. If `LEDGER_EOD` or `RECON_DAILY` failed, do not roll: downstream reports (statements, regulatory) would use
   incomplete balances.

## 5. Stuck payments

| Symptom | Cause | Action |
|---|---|---|
| Payment `ON_HOLD`, `hold_reason FRAUD_REVIEW`, `fraud_degraded = true` | Fraud scoring was unavailable at or above `fail-closed-amount` | Automatic: `FraudRescoreJob` re-scores with exponential backoff (1 min doubling to 1 h, max 12 attempts). Manual: `POST /v1/payments/{id}/fraud-rescore` (same key `pay-fraud:<id>`). After 12 attempts an analyst decides: `POST /v1/payments/{id}/fraud-review-decision {"decision":"RELEASE"|"REJECT"}`. First restore fraud-service (check its health and `payments.payment.fraud_degraded` rate) |
| `ON_HOLD` with a fraud alert (REVIEW / STEP_UP) | Genuine review | Resolve the alert in fraud case handling (`risk.fraud_alert.resolved` releases or rejects automatically) or use `fraud-review-decision` |
| Business payment `RELEASE_FAILED` | payment-service refused or was unreachable at release | Check `downstream_status` / error on `business_payment`; fix cause; `POST /v1/business/payments/{payment_id}/release` (approver or ADMIN; idempotent with `bzp-release:<id>`, never creates a second payment) |
| Return refused with 409 `return_in_progress` | A previous return's ledger call ended 5xx; return stays INITIATED / SUBMITTED | Verify in ledger-service whether journal `prt-post:<payment>` / `prt-credit:<payment>` exists. **[gap: GAP-19]** there is no resolve endpoint: escalate to engineering for a controlled data fix (ticket + 4-eyes), never insert ledger entries by hand |
| Wallet reversal `PROCESSING` | Ledger 5xx after reservation (amount stays reserved - safe direction) | Same as above **[gap: GAP-19]** |
| Payments past execution date still APPROVED | `PAYMENT_EXECUTE_DUE` job not run | Check EOD (section 4) or call `POST /v1/payments/execute-due` |

## 6. Reconciliation breaks

`RECON_DAILY` (after `LEDGER_EOD`) calls `POST /v1/reconciliation/runs/eod?business_date=` per tenant: records
processed / failed, auto-match rate, breaks raised. Production metrics: `GET /v1/reconciliation/runs/metrics` and the
`ledger.recon_run.completed` / `ledger.recon_eod.completed` events. Benchmark baseline: 98.93 % auto-match, 0 false
matches (`docs/PERFORMANCE.md`).

1. Auto-match rate below 98 % for a source: check whether the file arrived complete (`/v1/reconciliation/files`), rule
   set changes, or postings without payment references (`external_refs` on journal entries - UETR, end-to-end id,
   batch id, counterparty - sent by payment, card-clearing, wallet and deposit services since RECON-REAL-REFS).
   Settlement postings book on the capture day, so rail settlement lags must fit the rules' date windows
   ([SPEC_BACKLOG](../SPEC_BACKLOG.md#wave-b-results-and-open-items)).
2. Work exceptions: `GET /v1/reconciliation/exceptions?status=OPEN`; assign (`/{id}/assign`), accept suggestions
   (`/{id}/accept-suggestion`), or resolve with a note (`/{id}/resolve`).
3. Postings to fix a break go through `/v1/reconciliation/adjustments` and need approval by a different user
   **[4-eyes]** (`/{id}/approve` posts the journal entry).
4. Wrong match: `POST /v1/reconciliation/matches/{id}/unmatch` with reason.
5. Breaks older than 2 business days or above the materiality threshold (TBD per tenant) escalate to finance (SEV3).

## 7. Kafka consumer lag (audit catch-up)

audit-service consumes every `meridian.<domain>.v1` topic (group `audit-service`) and appends to a per-tenant hash
chain; search-service indexes ~97 event types (`meridian.search.indexer.lag`, `meridian.search.indexer.last_lag_ms`).

1. Measure: consumer group lag per partition (`kafka-consumer-groups --describe --group audit-service`, or MSK
   metrics `SumOffsetLag` / `MaxOffsetLag`); Micrometer Kafka client metrics if exported (verify name in the collector).
2. Causes: audit DB slow (chain append is sequential per tenant), a poison record (non-CloudEvents records are skipped
   and logged at debug), pod restarts.
3. Catch up: scale audit-service replicas up to the number of partitions (more does not help); check
   `hikaricp.connections.pending`; do **not** reset offsets forward - audit must see every event. Events are
   de-duplicated by `source_event_id`, so replays are safe.
4. After catch-up, verify the chain for the affected range per tenant:
   `POST /v1/audit-events/verify-chain {"from_sequence": n, "to_sequence": m}` (max 100,000 per call). A break publishes
   `platform.audit_chain.broken` -> SEV1 (possible tampering).
5. Standby cells must not consume (listeners off when `global.cell.role=standby`); if lag appears there, a promotion
   happened or the config is wrong.

## 8. Database connection exhaustion

Symptoms: `hikaricp.connections.pending` > 0 sustained, `hikaricp.connections.timeout` increasing, 5xx with
`SQLTransientConnectionException`, Aurora `DatabaseConnections` near `max_connections`.

Pool sizing note: default `global.postgres.poolSize` 20 per pod; 63 services x 2 replicas x 20 = 2,520 connections,
and HPA up to 10 replicas per service could request 12,600, all against one Aurora cluster per cell
([SPEC_BACKLOG connection budget](../SPEC_BACKLOG.md#connection-budget), GAP-12). Size per service with `dbPool`
(e.g. 4-6 for low-traffic services, 20-64 for ledger / payment / card-authorization; the G1 ledger benchmark used 64)
so that `sum(replicas_max x dbPool) < 0.8 x max_connections`.

1. Identify the consumer: Aurora Performance Insights by user / database; `pg_stat_activity` grouped by
   `datname, state` (idle-in-transaction = application bug).
2. Immediate relief: cap HPA `maxReplicas` of the noisy service, or lower its `dbPool` and restart; kill
   idle-in-transaction sessions older than 5 min.
3. Do **not** put PgBouncer in transaction mode in front of the services: `TenantAwareDataSource` sets
   `app.tenant_id` at session level and would leak tenants between transactions. Session mode only, until the
   per-transaction `set_config(..., true)` change is made and RLS tests pass.
4. Longer term: per-domain Aurora clusters, or the transaction-scoped tenant setting + PgBouncer.

## 9. Tenant move and cell failover

Full procedure, timings and roles: [docs/MULTI_REGION.md](../MULTI_REGION.md) sections 2 (tenant moves) and 5
(failover runbook). Summary:

- Tenant move **[4-eyes]**: plan (`POST /v1/platform/tenant-moves`) -> approve in workflow-service -> `/freeze` ->
  `/start-copy` -> data mover `/confirm-copy` (not before freeze + 45 s) -> `/switch` -> `/unfreeze`; `/cancel` until
  the switch. **[gap: GAP-11, no data mover implementation yet]**
- Unplanned regional failover (break-glass, single operator, audited): `POST /v1/platform/cells/{id}/offline` (or
  router `cellOverrides` if the control plane is down) -> `aws rds failover-global-cluster ... --allow-data-loss` ->
  `helm upgrade ... --set global.cell.role=primary` on the standby -> `POST /v1/platform/cells/{standby}/online`. Budget
  < 300 s.
- Planned evacuation (RPO 0): `/read-only`, drain outbox and MSK Replicator lag, `switchover-global-cluster`, swap roles,
  `/offline` + `/online`.
- After an unplanned failover: run reconciliation for `[failover - lag, failover]` against rail / scheme files
  (section 6) and check idempotent client retries.

## 10. Audit chain broken / ledger integrity mismatch

Signals: `platform.audit_chain.broken`, `platform.audit_seal.tampered`, an increase of `meridian.audit.chain.breaks` or
`meridian.audit.seal.tampered` (alert on any increase), an `audit_chain_verification` row with `valid = false`, or a
`ledger_verification` with `hash_mismatches > 0` (`POST /v1/ledgers/{id}/verify`): SEV1. Preserve evidence (DB
snapshot, no fixes in place), restrict DB access, involve security and internal audit. Never "repair" the chain.

Audit integrity jobs (GAP-07): audit-service runs daily at 01:15 UTC per tenant (`meridian.audit.integrity.cron`): a full
hash-chain verification, then the seal of yesterday to the Object Lock bucket (`audit/<tenant>/<yyyy>/<mm>/<dd>/` with
`events.jsonl` and `manifest.json`). To investigate, with the tenant's operator token:

```bash
GET  /v1/audit-chain-verifications?valid=false                  # failed runs: first_broken_sequence, break_reason
POST /v1/audit-chain-verifications/run {"full": true}           # re-verify everything now (on demand)
GET  /v1/audit-seals?seal_date=<day>                            # seal of a day: hashes, sequence range, storage URIs
POST /v1/audit-seals/{id}/verify                                # re-hash storage + database rows of the sealed range
```

The sealed objects are the reference copy: compare the database with them (`verify` lists every mismatch: storage
object changed, manifest changed, chain inside the batch, database row differs). A missing seal for a day (job
failure, bucket unreachable) is caught up by `POST /v1/audit-seals/run {"seal_date": "<day>"}`, oldest day first (days
are sealed in order; a later sealed day blocks an earlier one). A seal is refused while the chain is broken.

Bucket and keys: `MERIDIAN_AUDIT_WORM_ENDPOINT` / `_BUCKET` / `_REGION` (cell output `audit_bucket`), credentials in
`meridian-audit-service-secrets` (`worm-access-key`, `worm-secret-key`); `MERIDIAN_AUDIT_PSEUDONYM_KEY` (`pseudonym-key`)
keys the `psn:` pseudonyms of personal data in stored audit states: rotate only on compromise (pseudonyms recorded
before and after the rotation no longer correlate).

## 11. Card authorisation degradation

card-authorization approves in stand-in when the ledger or JIT funding is unavailable (`card_stand_in_record`,
reasons LEDGER_UNAVAILABLE / JIT_TIMEOUT / JIT_ERROR) and replays later. Watch the stand-in count; when the dependency
is back, confirm replays complete (`replay_result`) and investigate failed replays (possible overdraft exposure).

## 12. Post-incident review

Template: summary, impact (tenants, customers, money, data), timeline (UTC), detection (how / how fast), root cause,
what went well / badly, action items with owners and due dates, regulator / customer notifications made. Store in the
incident tracker; link new findings in `docs/SECURITY_REVIEW.md` style when security-relevant.

## 13. Incident severity matrix and on-call

| Severity | Definition (any of) | Examples | Response | Comms |
|---|---|---|---|---|
| **SEV1** | Money integrity at risk; card data or keys exposed; cross-tenant data exposure; region / cell down; payments or card auth unavailable > 5 min for any tenant | audit chain broken; ledger hash mismatch; forged callbacks; failover | Page on-call primary + secondary + IC immediately; 24x7; updates every 30 min | Tenant banks within 1 h (TBD contract); regulators per tenant obligations; PCI / schemes for card data; DPO for personal data (72 h controller deadline) |
| **SEV2** | Critical journey degraded (SLO burn > 10x), EOD failed for a tenant, security control disabled, single service down without workaround | fraud scoring unavailable (degraded holds), RELEASE_FAILED spike, audit lag > 1 h | Page on-call primary; 24x7; updates hourly | Affected tenants within 4 h |
| **SEV3** | Non-critical function degraded with workaround; recon breaks above threshold; one job skipped | search lag, statement delays | Business hours, next day | Ticket / status page |
| **SEV4** | Cosmetic, no customer impact | dashboard gaps | Backlog | None |

On-call (placeholders): primary SRE `TBD`, secondary SRE `TBD`, incident commander rota `TBD`, security on-call `TBD`,
database `TBD`, payments domain `TBD`, cards domain `TBD`, DPO `TBD`. Paging tool: TBD **[gap: GAP-10, no alerting
wired]**. Escalation: primary 5 min -> secondary 10 min -> IC 15 min -> CTO 30 min (SEV1).

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-05 | No deploy pipeline / change records | TBD (Platform) | `spec/plan.yaml` PLT-GITOPS |
| GAP-01 | Master key rotation not supported; no HSM | TBD (Cards) | [SPEC_BACKLOG Wave B Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-19 | No resolve action for in-doubt returns / wallet reversals | TBD (Payments / Wallets) | [SECURITY_REVIEW SEC-06 / SEC-07](../SECURITY_REVIEW.md#remediation-status) |
| GAP-21 | Webhook encryption key and MFA seed key cannot be rotated | TBD (Platform) | Proposed |
| GAP-12 | Connection budget / PgBouncer | TBD (Platform) | [SPEC_BACKLOG connection budget](../SPEC_BACKLOG.md#connection-budget) |
| GAP-10 | Alerting / paging not wired; on-call names TBD | TBD (SRE) | PLT-OBSERVABILITY |
| GAP-11 | Data mover for tenant moves; game day | TBD (SRE) | [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs) |
| - | Card ZPK grace period | TBD (Cards) | [SPEC_BACKLOG Wave B Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| - | No EOD jobs for ledger verification or archive runs (audit chain verification and seals run daily since GAP-07) | TBD (Operations) | Proposed |
