# Business continuity and disaster recovery plan (summary)

Last reviewed: 2026-10-07 · Owner: TBD (Head of SRE / BCM coordinator) · Design reference:
[docs/MULTI_REGION.md](../MULTI_REGION.md) (topology, replication, RPO statement, failover runbook) · Status: designed,
**not yet exercised** (no game day run, GAP-11)

## 1. Scope and assumptions

- Covers Meridian-operated cells (SHARED and DEDICATED models) built from `infra/tofu/modules/{cell,region}` and the
  Helm charts; SELF_HOSTED customers run their own BCP using the same runbooks.
- A cell = full stack in one region over 3 AZs (EKS, Aurora PostgreSQL, MSK, S3, KMS). Region pairs (e.g.
  `infra/tofu/envs/prod-eu1`: eu-west-1 primary, eu-west-2 warm standby) replicate with Aurora Global Database, MSK
  Replicator and S3 CRR (both ways); Route 53 fails over the router tier.
- External dependencies with their own continuity: IdP, card gateway / schemes, payment rails, KYC and bureau
  providers, SMS / e-mail providers, OTel / monitoring backend. Their failure modes are handled by stand-in, fail-open /
  fail-closed rules and queues, not by Meridian DR.

## 2. Service tiers, RTO and RPO

| Tier | Services | RTO (region loss) | RPO (unplanned region loss) | RPO (AZ loss / planned) | Degraded mode |
|---|---|---|---|---|---|
| **0 - money and access** | ledger, payment, routing, card-authorization, card-vault, wallet, deposit, limits, identity (+ IdP), edge gateway, cell router, tenant-service (registry), workflow-service (approvals) | < 5 min (S20 exit target) | Replication lag (typically < 1 s; bounded by `rds.global_db_rpo` when enabled); see [MULTI_REGION section 4](../MULTI_REGION.md#4-data-replication-and-rpo) | 0 | Card auth stand-in (`card_stand_in_record`) when ledger / JIT unavailable; router stale-if-error placements for 15 min and break-glass `cellOverrides` |
| **1 - customer-facing** | customer-channel, agent-channel, business-channel, merchant-channel, customer, onboarding, kyc, fraud, sanctions, transaction-monitoring, fx, card-service, card-threeds, card-tokenization, notification, document, merchant, agent-network, biller, interop, channel, openbanking, webhook, business-day | < 15 min | Same as tier 0 | 0 | Fraud unavailable: payments below `fail-closed-amount` continue, above are held and re-scored automatically; agent app queues offline (cash-in, bills, airtime, float, onboarding) |
| **2 - back office** | loan / loan-origination / collateral / collections, card-clearing / card-dispute, gl, reconciliation, statement, treasury-deal / liquidity / alm, commission, disbursement, crm, inbox, rewards, campaign, insights, search, file-gateway, archive, audit, config, reference-data, pricing-rules, product-catalog, billing | < 4 h | Same as tier 0 | 0 | Batch work (EOD, recon, statements) delayed; audit and search catch up from Kafka (consumers idempotent) |
| **3 - analytical / tooling** | analytics, regulatory-reporting, ai-platform, developer portal, sandbox | < 24 h | Same as tier 0 | 0 | Reports regenerated after recovery |

All tiers share the same cell, so in a regional failover they move together; the tiers set the **order of
verification** and the acceptable time to full function, not separate infrastructure.

Other scenarios:

| Scenario | Detection | Recovery | RTO / RPO |
|---|---|---|---|
| Pod / node failure | Readiness probes, PDB, HPA | Kubernetes reschedules (zone / host spread, maxUnavailable 0) | Seconds / 0 |
| AZ loss | Aurora reader promotion, MSK ISR | Automatic | ~30 s / 0 |
| Cell control plane (tenant-service) down | Router `placement.lookups{result="stale"}` rising | Router serves cached placements up to 15 min; break-glass `cellOverrides` via Helm | Routing unaffected for 15 min; no new placements |
| Bad release | Error budget burn, e2e journeys | Rollback PR via the `promote` workflow, `mode=rollback` ([RUNBOOKS section 1](RUNBOOKS.md#1-deploy-and-rollback-gitops)) | Minutes / 0 |
| Logical data corruption (bug, operator error, ransomware) | Ledger `verify`, audit `verify-chain`, recon breaks | Aurora point-in-time restore to a new cluster, extract affected rows, correct through compensating postings (financial tables are append-only; never overwrite) | Hours / restore point chosen (PITR within retention: 35 d prod, 7 d other) |
| KMS key deletion / loss | Decrypt failures | KMS keys have a 30-day deletion window (`deletion_window_in_days = 30`); cancel deletion | Minutes / 0 |
| Card-vault master key loss | Vault refuses to start / decrypt | `aws-kms`: the CDE key is a multi-region key with a replica in the DR cell (tofu `cde_kms_replica_of`), deletion has a 30-day window (cancel it); data keys replicate with the database and unwrap in either region (`mrk-` reference). `software`: restore the master key from the secret store. `pkcs11`: HSM backup / cluster restore **[GAP-01: no HSM key backup ceremony documented]** | Minutes / 0 |
| Kafka cluster loss (single region) | Producer errors, outbox backlog grows | Outbox rows stay in each service database until relayed; recreate MSK, relays resume; consumers de-duplicate by `ce_id` | Events delayed, 0 loss |
| IdP outage | Login SLI (J4) | Existing tokens valid until expiry (access token lifespan 900 s in the shipped realm); IdP DR is the IdP's | Logins blocked until IdP back |

## 3. Backups

| Data | Mechanism | Retention | Evidence |
|---|---|---|---|
| Aurora (all service databases of a cell) | Continuous automated backups + PITR; Global Database secondary | 35 days prod, 7 days other (`backup_retention_period`), deletion protection in prod | `infra/tofu/modules/cell/main.tf`, `infra/tofu/modules/region/main.tf` |
| S3 documents | Versioning, optional object lock (GOVERNANCE, `object_lock_days`), CRR both ways with KMS re-encryption | Per object lock / lifecycle (lifecycle rules not defined - proposed) | `infra/tofu/modules/cell`, `region` |
| Archive objects | WORM (`archived_object.worm_retain_until`), SHA-256 manifests | Per retention policy | archive-service (owning services answer `/v1/internal/data-lifecycle/*`) |
| Secrets | Secrets Manager with replicas in the DR region (`secret_replicas`, DR KMS key) | Versions kept by Secrets Manager | `infra/tofu/modules/region` (`aws_kms_key.dr_secrets`) |
| Kafka | Not backed up: events are reproducible from service outboxes during the relay window; consumers idempotent | Topic retention (cluster config) | Outbox rows are deleted after relay (follow-up: keep relayed rows for a window, MULTI_REGION section 4) |
| Infrastructure state | OpenTofu state in S3 (`meridian-tofu-state`, encrypted, lock file) | Bucket versioning to confirm (bucket not managed in repo) | `infra/tofu/envs/*/main.tf` |
| Configuration | Helm values per environment, tenant config in config-service (database) | Git history; DB backups | - |

## 4. Restore verification procedure (quarterly, per environment)

1. Restore a PITR copy of the cell's Aurora cluster to a scratch cluster at T-1 h (`aws rds restore-db-cluster-to-point-in-time`).
2. Apply `tools/db/smoke-test.sql` as `meridian_app` (RLS, append-only, optimistic locking) - must report no `FAIL`.
3. Point a scratch ledger-service and audit-service at the copy (jwt mode, read-only role) and run
   `POST /v1/ledgers/{id}/verify` for each ledger of a sample tenant (balances, versions, hash chain: 0 mismatches) and
   `POST /v1/audit-events/verify-chain` over the last 100k events.
4. Restore one S3 object version and one archived object; compare the SHA-256 with the manifest.
5. Record duration (restore RTO), data point (RPO), issues; destroy the scratch resources.

## 5. Test and game-day schedule

| When | Exercise | Success criteria | Owner |
|---|---|---|---|
| Monthly (staging) | Pod / node kill during load (`platform/load-tests`), rolling deploy under load | No 5xx budget breach; ledger integrity check passes | TBD (SRE) |
| Quarterly (staging, then prod) | Restore verification (section 4) | Smoke test, ledger verify, audit chain OK; restore time recorded | TBD (SRE / DBA) |
| Quarterly (staging) | AZ failure simulation (FIS: stop one AZ's nodes, Aurora reader failover) | RTO < 1 min, RPO 0 | TBD (SRE) |
| Semi-annual (staging region pair) | **Planned regional failover** per [MULTI_REGION section 5](../MULTI_REGION.md#5-failover-runbook-game-day) "Planned" | RTO < 5 min, RPO 0 measured with the synthetic write probe | TBD (IC rota) |
| Semi-annual (staging region pair) | **Unplanned regional failover** (simulated region loss) | RTO < 5 min; measured lag < 1 s; reconciliation of the lag window | TBD (IC rota) |
| Annual (production, planned window) | Planned regional failover and failback with regulator notice | As above; customer impact = announced READ_ONLY window only | TBD (CTO) |
| Annual | Tabletop: card key compromise, ransomware / logical corruption, IdP outage, control-plane loss, loss of a payment rail | Decisions and comms per [RUNBOOKS](RUNBOOKS.md) within target times | TBD (BCM coordinator) |
| After major change | Targeted DR test of the changed component | - | Change owner |

Prerequisites for the first regional game day ([MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs)):
FIS templates and the synthetic tenant probe; dashboards for the RTO timeline; MSK IAM client settings in the chart;
reverse MSK Replicator for failback; runtime promotion without rolling restart (RTO margin); outbox retention for
replay; control-plane DR (tenant-service and workflow-service in their own region pair); fast config channel for router
break-glass overrides.

## 6. Business continuity (people and process)

- Roles during a disaster: incident commander, platform operators A / B (maker / checker), scribe, comms lead, domain
  leads (payments, cards, ledger), DPO / compliance for regulator and customer notices (placeholders in
  [RUNBOOKS section 13](RUNBOOKS.md#13-incident-severity-matrix-and-on-call)).
- Break-glass: cell `/offline` is deliberately single-operator (audited) so failover is not blocked by an approval
  round; every other platform change keeps four-eyes.
- Communication: status page (TBD), tenant bank contacts per contract, regulators via the tenant (and directly where
  Meridian is regulated as a critical third party / outsourcing provider - TBD per market).
- Manual fallbacks for tenants: cut-off extension, forced date roll with reason (`POST /business-days/{id}/roll`
  `forced: true`), agent offline mode, card stand-in limits.
- Staff unavailability: on-call rota with secondary; runbooks executable by any trained operator; access via the IdP
  from any region (IdP DR required).

## 7. Maintenance of this plan

Review after every game day, every SEV1, every new region or cell type, and at least annually. Record test results in
the tracker and in this document's history.

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-11 | No game day yet; data mover, FIS, outbox retention, control-plane DR, MSK IAM config, reverse replicator | TBD (SRE) | [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs) |
| GAP-01 | No HSM key backup / ceremony for card keys (KMS mode covered by the multi-region CDE key); a dedicated CDE database cluster (`cde_dedicated_db`) has no cross-region replica yet | TBD (Cards) | [SPEC_BACKLOG Wave B Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-10 | Dashboards / alerts for detection and RTO measurement | TBD (SRE) | PLT-OBSERVABILITY |
| - | S3 lifecycle rules and tofu-state bucket versioning not managed in the repo | TBD (Platform) | Proposed |
| - | Restore verification has never been run; no automation | TBD (SRE / DBA) | Proposed |
| - | Large jobs run synchronously / in memory (disbursements, payouts, exports): not resumable after failover | TBD (Platform) | [SPEC_BACKLOG Wave B cross-cutting](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-24 | BCP policy, BIA sign-off, crisis communications plan | TBD (BCM coordinator) | Proposed |
