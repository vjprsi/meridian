# SOC 2 control matrix (Trust Services Criteria 2017, revised points of focus 2022)

Last reviewed: 2026-10-07 · Owner: TBD (Compliance lead) · System: Meridian Core multi-tenant banking platform
(63 domain services, edge gateway, cell router, web apps; AWS cells per `infra/tofu`)

Status: **Implemented** = control designed and present in the repo with the evidence given (operating effectiveness
over a period is still to be evidenced for Type II); **Partial** = present but incomplete or not yet enforced by
default; **Gap** = absent. Gap ids refer to the [gap register](README.md#gap-register).

System boundary for the report: everything deployed by `infra/helm/meridian` and `infra/helm/meridian-router` into
AWS cells built by `infra/tofu/modules/{cell,region}`, the identity provider (Keycloak or the bank's IdP), the web
apps under `web/`, and the CI pipeline. Carve-outs: AWS (infrastructure, Aurora, MSK, S3, KMS, EKS), the IdP if
customer-hosted, card schemes and payment rails. Complementary user-entity controls (CUECs) for tenant banks are
listed at the end.

## Common criteria (Security)

### CC1 Control environment

| Criterion | Meridian control (design) | Evidence | Status | Gap |
|---|---|---|---|---|
| CC1.1 Integrity and ethical values | Code of conduct, acceptable use | Not in repo | Gap | GAP-24 |
| CC1.2 Board oversight | Security steering / risk committee charter | Not in repo | Gap | GAP-24 |
| CC1.3 Structure, reporting lines | Service ownership (one service per bounded context, `docs/SERVICES.md`); owners per tracker row (`tracker/Meridian_Tracker.xlsx` owner column) | `docs/SERVICES.md`, tracker | Partial | GAP-24 (named owners) |
| CC1.4 Competence | Engineering conventions and implementation guide | `docs/CONVENTIONS.md`, `docs/SERVICE_IMPLEMENTATION_GUIDE.md`; no training records | Partial | GAP-24 |
| CC1.5 Accountability | Maker-checker on privileged and money operations makes individuals accountable (`approval_request.maker`, `approved_by`) | workflow-service `approval_request`; audit `approval_request_id` | Partial | GAP-24 (HR accountability) |

### CC2 Communication and information

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC2.1 Quality information | Spec-first single source of truth; generated, CI-checked contracts | `spec/domains/*.yaml`, `tools/generate.py --check`, CI job `spec` | Implemented | |
| CC2.2 Internal communication | Security review and remediation table; runbooks | `docs/SECURITY_REVIEW.md`, `docs/operations/RUNBOOKS.md` | Partial | GAP-24 (security awareness) |
| CC2.3 External communication | Public developer portal (auth, idempotency, webhooks HMAC guides); system description for customers | `web/developer-portal`; no published security / vulnerability disclosure page | Partial | GAP-24 |

### CC3 Risk assessment

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC3.1-CC3.2 Objectives, risk identification | Risks section of the plan; STRIDE threat model | `spec/plan.yaml` (risks), [THREAT_MODEL.md](THREAT_MODEL.md) | Partial | GAP-24 (formal risk register, annual assessment) |
| CC3.3 Fraud risk | Fraud scoring of every outbound payment and card auth, transaction monitoring, sanctions; internal fraud via four-eyes | `docs/SERVICE_IMPLEMENTATION_GUIDE.md` "Fraud scoring of payments"; `FourEyes.require`; SEC-03/12/20/21 fixes | Implemented | |
| CC3.4 Change impact | Security review re-run after major changes; perf re-run after hardening | `docs/SECURITY_REVIEW.md`, `docs/PERFORMANCE.md` "Re-run after security hardening" | Partial | GAP-05 |

### CC4 Monitoring activities

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC4.1 Ongoing / separate evaluations | Static security review (2026-10-06); planned pen test | `docs/SECURITY_REVIEW.md`; [PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md) | Partial | GAP-25 |
| CC4.2 Deficiency communication | Findings table with status, regression tests and residual risk | `docs/SECURITY_REVIEW.md#remediation-status` | Implemented | |

### CC5 Control activities

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC5.1 Control selection | Platform guardrails in shared libs (scopes, tenancy, idempotency, approvals, callbacks, outbound policy) so every service inherits them | `libs/meridian-web`, `libs/meridian-approvals`, `libs/meridian-common` | Implemented | |
| CC5.2 Technology general controls | Deny-by-default scopes (`ScopeInterceptor`, SEC-29), jwt default (SEC-13), startup secret guard (`RequiredSecrets`, SEC-02) | `ScopeInterceptorTest`, `SecurityModeGuardTest`, `RequiredSecrets` | Partial | GAP-14 |
| CC5.3 Policies and procedures | Runbooks, conventions | `docs/operations/*`, `docs/CONVENTIONS.md` | Partial | GAP-24 (policy set) |

### CC6 Logical and physical access

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC6.1 Access security software, infrastructure, architecture | OAuth 2.1 / OIDC tokens validated in every service (issuer, signature, expiry, audience `meridian-api` SEC-14); tenant only from the token (`TenantFilter`); PostgreSQL RLS on every tenant table, services connect as non-owner `meridian_app`; per-service databases; encryption at rest with a customer-managed KMS key (Aurora, MSK, S3, EKS secrets, Secrets Manager) and TLS in transit (`rds.force_ssl`, MSK TLS, `sslmode=require`) | `generated/db/*/V001__init.sql` (RLS), `tools/db/smoke-test.sql` + CI job `ddl-postgres`; `AudienceValidatorTest`, `TenantFilterTest`; `infra/tofu/modules/cell/main.tf` (`aws_kms_key.cell` with rotation, `storage_encrypted`, `encryption_info`) | Implemented | GAP-09 (field-level encryption of `pii` columns), GAP-01 (card keys) |
| CC6.2 Registration and authorisation of users | Staff users in identity-service and the IdP; tenant from admin-only `tenant_id` attribute; channel users bound by admin-only claims; business / merchant first-admin provisioning by bank staff (`POST /v1/business-provisioning/admins`) | `tools/keycloak_realm.py`; README "Business channel" | Partial | GAP-13 (joiner/mover/leaver, M2M IdP clients manual) |
| CC6.3 Role-based access, least privilege, SoD | Scopes per domain (`<domain>:read/write`), `platform:operator` only for the operator tenant (SEC-15), narrow `platform:routing-read`, exact scopes `cards:pan_reveal` / `cards:vault_admin` (SEC-11); privileged roles need just-in-time elevation with approval (`POST /v1/user-roles/elevate`, `privileged_change`); maker-checker through workflow-service for ~60 resource types (G11) with owner-service registry (SEC-03) | `ScopeInterceptorTest.everyGeneratedOperationScopeKeepsCustomersAndStaffApart`, `ApprovalOwnersRegistryTest`, `IdentityFlowIT.approvedChangesAreExecutedOnlyFromTheRecordedSubmission`, `WorkflowFlowIT`, `FourEyesTest` | Partial | GAP-16 (shared service client), GAP-13 (access recertification) |
| CC6.4 Physical access | AWS data centres | AWS SOC 2 report (carve-out) | Inherited | |
| CC6.5 Disposal | Purge with certificate of destruction (archive-service `purge_request.certificate_document_id`) | archive-service `PurgeService`, `ArchiveWorkflowApprovalIT` | Partial | GAP-08 (owning services do not implement purge) |
| CC6.6 Boundary protection | Edge gateway strips internal identity headers and owns `X-Forwarded-For` (SEC-13/16), body-size caps (SEC-26), actuator limited to health/info (SEC-31); cell router verifies JWTs and strips the same headers (`HeaderPolicyParityTest`); provider callbacks HMAC v2 with timestamp + nonce (SEC-17); SSRF policy for all tenant-supplied URLs (SEC-08/09/18/39); private subnets, security groups for Aurora/MSK; Kubernetes NetworkPolicy per service | `HeaderPolicyTest`, `ProxyControllerIT`, `CallbackVerifierTest`, `DownstreamPolicyTest`; `infra/helm/meridian/templates/services.yaml` (NetworkPolicy) | Partial | GAP-15, GAP-17, GAP-23 |
| CC6.7 Data transmission and movement | TLS to Aurora / MSK / S3; webhook payloads HMAC-signed; signed, expiring download links (file-gateway SEC-36, audit, statements); outbound HTTP pinned resolution, no redirects | `OutboundHttp`, `FileGatewayFlowIT`; tofu MSK `client_broker = "TLS"` | Partial | Cluster-internal service-to-service HTTP is plain (`http://{name}`; no mesh mTLS) - proposed |
| CC6.8 Malware / unauthorised software | Minimal JRE base image (`eclipse-temurin:25-jre`), non-root uid 10001, read-only root FS, all capabilities dropped, seccomp RuntimeDefault, no service-account token; inbound files virus-scanned before routing (file-gateway) | `infra/docker/Dockerfile`, `services.yaml` securityContext; `FileGatewayFlowIT.inboundFileIsScannedValidatedRoutedAndAcknowledged`; CI Trivy image scan, cosign signature + SBOM attestation per image on main (`ci.yml` jobs `deploy-artifacts`, `release-images`) | Partial | GAP-04 residual (no signature verification at admission), GAP-18 |

### CC7 System operations

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC7.1 Configuration and vulnerability detection | Helm chart regenerated and diffed in CI, `kubeconform -strict` (monitoring CRs against the CRD catalog); tofu `validate`; SAST (Semgrep OWASP / Java / secrets), secret scanning (gitleaks), SCA (Trivy on the CycloneDX SBOM and npm lockfiles), container image scan (Trivy), Dependabot update PRs | CI jobs `deploy-artifacts`, `sast`, `secrets`, `sbom`; code-scanning SARIF; `.github/dependabot.yml` | Partial | GAP-04 residual (no IaC scanning of `infra/tofu`) |
| CC7.2 Anomaly monitoring | Tamper-evident audit chain per tenant (`audit_event.hash/prev_hash`), `platform.audit_chain.broken` event; ledger integrity verification (`ledger_verification`, hash mismatches); denied object access published as `channels.*_action.denied`; card-vault access log; fraud / TM alerts; Prometheus metrics on every service with SLO burn-rate, saturation and security alerts (step-up spikes, MFA failures, IdP login failures, audit consumer lag); EKS audit logs; VPC flow logs | `HashChainTest`, `AuditFlowIT`, `AuditIntegrityIT`; daily `AuditIntegrityJobs` (full chain verification, `platform.audit_chain.broken`, metric `meridian.audit.chain.breaks`), `POST /v1/audit-chain-verifications/run`, `POST /v1/audit-events/verify-chain`; ledger `POST /v1/ledgers/{id}/verify`; `infra/tofu/modules/cell/main.tf` (`enabled_log_types`, `enable_flow_log`); `infra/observability/prometheus/rules/*.yml` | Partial | GAP-06, GAP-07 residual (read auditing), GAP-10 residual (no monitoring stack / paging deployed) |
| CC7.3 Security event evaluation | Incident severity matrix and triage | [RUNBOOKS.md - Incident severity](../operations/RUNBOOKS.md#13-incident-severity-matrix-and-on-call) | Partial | GAP-06 (no SIEM correlation), GAP-24 (IR plan) |
| CC7.4 Incident response | Runbooks for key compromise, failover, stuck payments; break-glass cell OFFLINE (single operator, audited) | `docs/operations/RUNBOOKS.md`, `docs/MULTI_REGION.md` section 5 | Partial | GAP-24 (tested IR plan, regulator notification) |
| CC7.5 Recovery | DR runbook with RTO budget; backups 35 days in prod, deletion protection, S3 versioning + object lock | `docs/MULTI_REGION.md`, [BCP_DR.md](../operations/BCP_DR.md), tofu `backup_retention_period` | Partial | GAP-11 |

### CC8 Change management

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC8.1 Authorised, tested, approved changes | PR-based workflow with CI (spec validation, generated artefacts up to date, OpenAPI/AsyncAPI lint, DDL on PostgreSQL 18, full build with Testcontainers ITs, Helm lint/kubeconform, image build, SAST, secret scan, SBOM + SCA, image scan; signed and attested images on main); code owners per area; forward-only migrations; business configuration changes (products, pricing, rules, regulatory packs, EOD jobs) through maker-checker | `.github/workflows/ci.yml`; `.github/CODEOWNERS`; `docs/CONVENTIONS.md` section 3; G11 notes in `docs/SPEC_BACKLOG.md` | Partial | GAP-04 residual (branch protection requiring CI + code-owner review not evidenced; placeholder teams), GAP-05 (no deploy approvals or release log) |

### CC9 Risk mitigation

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| CC9.1 Business disruption | Multi-AZ cells, warm standby cells, cell router failover, card-auth stand-in (`card_stand_in_record`), fraud fail-open/closed thresholds | `docs/MULTI_REGION.md`; card-authorization spec | Partial | GAP-11 |
| CC9.2 Vendor / business partner risk | Provider callbacks authenticated (SEC-17), openbanking TPP eIDAS PKIX validation (SEC-27), outbound URL policy for provider endpoints | `CertificateTrustTest`, `CallbackVerifierTest` | Partial | GAP-28, GAP-24 (supplier register, due diligence) |

## Availability

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| A1.1 Capacity | HPA per service (2-10 replicas, CPU 70%), PDB, zone/host spread; ledger benchmark 1,792 postings/s; per-service pool sizing (`dbPool`) | `infra/helm/meridian/values.yaml`; `docs/PERFORMANCE.md`; saturation alerts and dashboards (DB pools, JVM heap, Kafka lag, outbox backlog) in `infra/observability` | Partial | GAP-12, GAP-10 residual; 10,000 TPS per cell target not yet tested (S22) |
| A1.2 Environmental protection, backups, recovery infrastructure | Aurora 6-way storage across 3 AZs, Global Database, MSK RF 3 / Replicator, S3 CRR, Route 53 failover; PITR backups (35 d prod) | `infra/tofu/modules/region`, `docs/MULTI_REGION.md` section 4 | Implemented (design) | GAP-11 |
| A1.3 Recovery testing | Game-day runbook (RTO < 5 min, RPO 0 planned / lag unplanned) | `docs/MULTI_REGION.md` section 5; [BCP_DR.md](../operations/BCP_DR.md#5-test-and-game-day-schedule) | Gap (not yet executed) | GAP-11 |

## Confidentiality

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| C1.1 Identify and protect confidential information | Spec markers `pii` / `secret` / `hide` (secrets never returned by the API); data classification catalogue (archive-service `data_classification`, `POST /v1/archive/classifications/scan`); tenant isolation (RLS, per-tenant keys for card DEKs SEC-37, fraud pseudonyms); OTPs never stored in clear (SEC-10); TOTP seeds encrypted (SEC-25); API keys / client secrets hashed | `docs/CONVENTIONS.md` column modifiers; `generated/docs/data-dictionary.md`; `IdentityFlowIT`; `FraudPseudonymisationIT` | Partial | GAP-09 |
| C1.2 Disposal of confidential information | Retention policies, legal holds, purge with four-eyes and certificate of destruction executed in the owning service (`/v1/internal/data-lifecycle/purge`); idempotency rows purged after 30 d (SEC-34) | archive-service; `DataLifecycleService`, `DataLifecycleIT` (12 services); `IdempotencyJanitor` | Partial | GAP-08 residuals (S3 file content, search journal) |

## Processing integrity

| Criterion | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| PI1.1 Processing objectives / definitions | Contract-first OpenAPI / AsyncAPI, data dictionary, enums with CHECK constraints | `generated/openapi`, `generated/asyncapi`, `generated/docs/data-dictionary.md` | Implemented | |
| PI1.2 Input completeness and accuracy | Money as decimal strings / `numeric(28,8)`, currency per amount, Luhn / IBAN validation, server-rebuilt bodies in channel BFFs, mass-assignment guards (`ro` fields, SEC-12/20) | `LedgerPostingIT`, channel flow ITs, `DepositFlowIT` | Implemented | |
| PI1.3 Processing accuracy | Double-entry ledger balanced per currency, accounts locked in sorted order, append-only journals with hash chain; idempotency fingerprint (method + path + caller + body, SEC-19); concurrency invariants for reversals / returns / refunds / FX quotes (SEC-04..07, SEC-28) | `LedgerPostingIT.concurrentReversalsOfOneEntryPostExactlyOnce`, `PaymentFlowIT.concurrentReturnsAndRefundsMoveMoneyAtMostOnce`, `WalletFlowIT`, `FxFlowIT`, `IdempotencyFingerprintTest`; load-test integrity check 0 mismatches (`docs/PERFORMANCE.md`) | Implemented | |
| PI1.4 Output completeness and accuracy | Daily reconciliation (`RECON_DAILY` in EOD pack) with 98.93% auto-match and 0 false matches; breaks to exceptions with four-eyes adjustments; statements | `ReconBenchmarkTest`; `POST /v1/reconciliation/runs/eod` | Partial | GAP-19; postings now carry payment references (RECON-REAL-REFS, `ReconRealRefsIT` 433/433 lines matched, 0 false) but inbound statement delivery route, Visa Base II / Mastercard T112 parsers and value dates are open ([SPEC_BACKLOG](../SPEC_BACKLOG.md#wave-b-results-and-open-items)) |
| PI1.5 Stored data integrity | Append-only tables enforced by trigger, optimistic locking (`row_version`), ledger `verify`, audit `verify-chain`, archive manifests with SHA-256 | `tools/db/smoke-test.sql`; ledger / audit verification endpoints | Partial | Audit verification scheduled daily and sealed to WORM (`AuditIntegrityIT`); ledger `EntryHasher` does not cover the new payment-reference columns ([SPEC_BACKLOG](../SPEC_BACKLOG.md#wave-b-results-and-open-items)) |

## Privacy

| Principle | Meridian control | Evidence | Status | Gap |
|---|---|---|---|---|
| P1 Notice | Tenant branding `privacy_url`; consent capture with evidence (IP, device, screen version, text hash) | `spec/domains/platform.yaml` (`privacy_url`), `spec/domains/party.yaml` (`consent`) | Partial | Notice content is the tenant bank's (CUEC) |
| P2 Choice and consent | Consents per purpose with `lawful_basis`, withdrawal; OBJECTION / RESTRICTION stop marketing and profiling | customer-service `ConsentService`, `PrivacyService` | Implemented | |
| P3 Collection | Minimisation in fraud scoring (digests, pseudonyms); channel BFFs strip fraud fields from customer responses | `docs/ML_FRAUD_TRAINING.md` section 7 | Partial | GAP-26 |
| P4 Use, retention, disposal | Retention policies per data class / jurisdiction, legal holds (checked before every DSAR erasure), AML 5-year blocker on erasure, per-service retain-on-erasure obligations | archive-service; `PrivacyService` (`AML_RETENTION_YEARS = 5`), `DataSubjectFanout`, `PrivacyFanoutIT` | Partial | GAP-08 residuals (per-jurisdiction retention) |
| P5 Access | DSAR ACCESS / EXPORT compile the customer's data from customer-service and 12 other services into a restricted document (fails rather than delivering an incomplete package); audit data exports under four-eyes with `due_date` | `/v1/privacy-requests/{id}/execute`, `PrivacyFanoutIT`; `/v1/data-exports` | Partial | Services outside the fan-out list (GAP-08 residual) |
| P6 Disclosure and notification | Webhook subscriptions bound to the owning customer / business / merchant; data-share transport simulated; breach notification procedure | README channel sections; [RUNBOOKS.md](../operations/RUNBOOKS.md#13-incident-severity-matrix-and-on-call) | Partial | GAP-24, GAP-28 |
| P7 Quality | RECTIFICATION handled through update APIs and confirmed on the request | `PrivacyService` | Implemented | |
| P8 Monitoring and enforcement | Privacy request deadlines (`due_at`); DPIA | [DATA_PROTECTION.md](DATA_PROTECTION.md) | Partial | No DSAR deadline job in the EOD pack; GAP-24 (DPO) |

## Complementary user-entity controls (tenant banks)

- Configure their IdP (or Meridian's) with MFA for staff and approvers; review staff role assignments quarterly.
- Assign distinct makers and checkers; configure approval policies and amount thresholds per resource type.
- Supply customer-facing privacy notices, lawful bases and retention periods per jurisdiction (`retention_policy`).
- Manage their providers' callback secrets and move providers to v2 signatures.
- Review reconciliation exceptions and fraud / AML alerts daily.

## Type II evidence to collect per period (once in production)

| Control area | Evidence artefact | Source |
|---|---|---|
| Change management | Merged PRs with green CI runs, deploy records per environment | GitHub, deployment pipeline (GAP-05) |
| Maker-checker | Sample of `approval_request` rows with distinct maker / checker and decision times | workflow-service `GET /v1/approval-requests` |
| Privileged access | `privileged_change` records and elevation expiries; session revocations | identity-service |
| Audit integrity | Scheduled `verify-chain` results per tenant (none broken), ledger `ledger_verification` results | audit-service, ledger-service |
| Card data access | `card_vault_access_log` samples (purpose, caller, step-up) | card-vault-service |
| Backups / DR | Restore test reports, game-day timelines | [BCP_DR.md](../operations/BCP_DR.md) |
| Monitoring | Alert history, incident tickets, post-mortems | Prometheus / Alertmanager from `infra/observability` rules (GAP-10 residual: stack not deployed), GAP-06 |
| Supply chain | Green `sast` / `secrets` / `sbom` runs per merged PR; SBOM artifacts; cosign signatures and attestations in Rekor per released image | GitHub Actions, code scanning, GHCR / Rekor |

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-04 | Closed in CI (SAST, secrets, SBOM, SCA, image scan, signing, CODEOWNERS); residual: branch protection evidence, admission-time signature verification, IaC scanning | TBD (DevSecOps) | PLT-CI (Done); residual proposed |
| GAP-05 | No deployment pipeline or change records | TBD (Platform) | `spec/plan.yaml` PLT-GITOPS |
| GAP-06 | No central logging / SIEM, IdP events off in shipped realm | TBD (SecOps) | Proposed; PLT-OBSERVABILITY |
| GAP-07 | Partially closed 2026-10-07 (masking, daily verification, WORM seals); open: read auditing | TBD (Platform) | Proposed; [SPEC_BACKLOG per-domain platform](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-08 | Largely closed 2026-10-07; residuals: S3 file content, search journal, Kafka retention, services outside the DSAR fan-out | TBD (Data platform) | [SPEC_BACKLOG per-domain operations](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-10 | Metrics, dashboards and alert rules exist; monitoring stack, paging and on-call not deployed | TBD (SRE) | PLT-OBSERVABILITY (In progress) |
| GAP-11 | DR not yet tested | TBD (SRE) | [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs) |
| GAP-13 | Staff MFA flow and access recertification | TBD (IAM) | Proposed |
| GAP-16 | Shared service identity, no Kafka ACLs | TBD (Platform) | [SPEC_BACKLOG wave A](../SPEC_BACKLOG.md#cross-cutting-items-raised-in-wave-a-open) |
| GAP-24 | Policies, risk register, HR, vendor management | TBD (CISO) | Proposed |
| GAP-25 | No independent pen test yet | TBD (Security) | S22 |
