# Compliance and certification pack (roadmap S22)

Last reviewed: 2026-10-07 · Scope: the Meridian repository at `main` (services, libs, platform, web, infra, spec) ·
Owner: TBD (Head of Security / Compliance)

S22 ("GA hardening and certification", `spec/plan.yaml`) targets SOC 2 Type II, ISO/IEC 27001 and a pen test. These
documents map what the repository **actually implements** to those frameworks. Where a control does not exist, it is
marked **Gap**, given a gap id below, an owner placeholder and a link to the backlog item that tracks it (or flagged
"proposed" when nothing in [`docs/SPEC_BACKLOG.md`](../SPEC_BACKLOG.md) covers it yet).

Ground rules for reading these documents:

- "Implemented" means code, configuration or a test exists in this repository and the evidence path is given.
  It does **not** mean the control has operated over an audit period: SOC 2 Type II needs 3-12 months of operating
  evidence (tickets, logs, approvals, reviews) that only a running production environment produces.
- Organisational controls (policies, HR, vendor management, physical security) are outside a code repository. They
  are listed so the Statement of Applicability is complete, and are marked Gap until the policy set exists.
- Cloud infrastructure controls (data centres, hardware, hypervisor) are inherited from AWS (`infra/tofu`): the
  evidence is AWS's own SOC 2 / ISO 27001 / PCI DSS attestation, collected through AWS Artifact.

| Document | Purpose |
|---|---|
| [SOC2_CONTROL_MATRIX.md](SOC2_CONTROL_MATRIX.md) | Trust Services Criteria -> Meridian controls -> evidence -> status |
| [ISO27001_ANNEX_A.md](ISO27001_ANNEX_A.md) | Statement of Applicability for the 93 Annex A controls (ISO/IEC 27001:2022) |
| [PCI_DSS_SCOPE.md](PCI_DSS_SCOPE.md) | Cardholder data environment, data flows, PCI DSS v4.0.1 requirement status |
| [DATA_PROTECTION.md](DATA_PROTECTION.md) | Personal data inventory, lawful bases, retention, DSAR / erasure, transfers, DPIA outline |
| [THREAT_MODEL.md](THREAT_MODEL.md) | STRIDE per trust boundary, mapped to mitigations and SECURITY_REVIEW findings |
| [PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md) | Scope, environments, accounts, rules of engagement, priority scenarios |
| [../operations/RUNBOOKS.md](../operations/RUNBOOKS.md) | Operational runbooks (deploy, rotation, incidents, EOD, payments, recon, Kafka, DB) |
| [../operations/SLO_SLA.md](../operations/SLO_SLA.md) | SLIs / SLOs / error budgets per critical journey and alert mapping |
| [../operations/BCP_DR.md](../operations/BCP_DR.md) | Business continuity and DR plan, RTO / RPO per tier, tests and game days |

Related engineering evidence: [SECURITY_REVIEW.md](../SECURITY_REVIEW.md) (40 findings and their remediation),
[MULTI_REGION.md](../MULTI_REGION.md) (cells, DR, RPO statement), [PERFORMANCE.md](../PERFORMANCE.md),
[SERVICE_IMPLEMENTATION_GUIDE.md](../SERVICE_IMPLEMENTATION_GUIDE.md), [ML_FRAUD_TRAINING.md](../ML_FRAUD_TRAINING.md),
[BUSINESS_M2M_API.md](../BUSINESS_M2M_API.md).

## Gap register

One list for all documents; each document's "Open gaps" section refers to these ids. "Proposed" = no backlog item
exists yet; add it to `docs/SPEC_BACKLOG.md` (or the tracker) when the owner accepts it.

| Id | Gap (verified in the repo on 2026-10-07) | Frameworks blocked | Owner | Backlog |
|---|---|---|---|---|
| GAP-01 | **No real HSM / KMS for card keys.** card-vault uses `SoftwareHsm` (every PAN DEK, CVK, ZPK, PIN key derived in-process from one 32-byte master key supplied as `MERIDIAN_CARDS_VAULT_MASTER_KEY` or a file, `MasterKeyProviders`). No KMS/HSM adapter bean exists, and the master key itself cannot be rotated in place (only DEK versions via `rotate-keys`). | PCI 3.6/3.7, ISO 8.24, SOC2 CC6.1 | TBD (Cards platform) | [SPEC_BACKLOG Wave B - Cards "real HSM adapter + ZPK grace period"](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-02 | **CDE not segmented.** card-vault runs in the same namespace and chart as every service; its NetworkPolicy is ingress-only and admits any pod labelled `app.kubernetes.io/part-of: meridian`; `/v1/card-vault/*` is routed by the edge gateway; it shares the Aurora cluster, the runtime DB role secret (`meridian-db-app`), the cell KMS key and the platform service client with all services. | PCI 1.x, 7.x, ISO 8.22 | TBD (Platform / Cards) | [SECURITY_REVIEW SEC-11 residual](../SECURITY_REVIEW.md#remediation-status) (vault via gateway); proposed (dedicated namespace / cluster) |
| GAP-03 | **Sensitive authentication data retained.** `card_data_vault.cvv_encrypted` keeps CVV2 after issuance and `pin_encrypted` keeps a reversibly encrypted PIN; reveal without `public_key` returns clear PAN/CVV. Needs an issuer justification under PCI DSS 3.3.3 or removal (recompute CVV2 under the CVK; store a PVV / offset, not the PIN). | PCI 3.3 | TBD (Cards) | [SECURITY_REVIEW SEC-11](../SECURITY_REVIEW.md#findings) residual |
| GAP-04 | **CI has no security scanning or supply-chain controls.** `.github/workflows/ci.yml` runs spec checks, DDL, build/tests, Helm lint and one image build only: no SAST (Semgrep), SCA, container scan (Trivy), secret scanning, SBOM, image signing, Spotless / Error Prone, Dependabot or CODEOWNERS - although `spec/plan.yaml` marks PLT-CI ("Semgrep, Trivy, SBOM, image signing") Done. | SOC2 CC7.1/CC8.1, ISO 8.8/8.25-8.29, PCI 6.2/6.3/11.3 | TBD (DevSecOps) | Proposed (correct PLT-CI status) |
| GAP-05 | **No deployment pipeline / change record.** Releases are manual `helm upgrade`; PLT-GITOPS (Argo CD + Rollouts) is open; no environment promotion workflow, no deploy approvals, no release log. | SOC2 CC8.1, ISO 8.32, PCI 6.5 | TBD (Platform) | `spec/plan.yaml` PLT-GITOPS |
| GAP-06 | **No central logging / SIEM.** Services log plain text to stdout (no structured logging config, no log shipping in the chart or tofu); the shipped Keycloak realm does not enable login or admin events; `platform.audit_event.recorded` is published "for SIEM streaming" but no SIEM exists; no log retention policy for application logs. | SOC2 CC7.2/CC7.3, ISO 8.15/8.16, PCI 10.x | TBD (SecOps) | Proposed; `spec/plan.yaml` PLT-OBSERVABILITY |
| GAP-07 | **Audit trail integrity operations and PII masking.** audit-service hash-chains every domain event, but chain verification runs only on demand (`POST /v1/audit-events/verify-chain`, max 100k events per call; not in the EOD pack); `audit_event` lives in PostgreSQL (append-only trigger), not on WORM storage as the spec states; `after_state` stores the raw event payload (spec says "PII masked"; no masking code exists). Reads of personal data are not audited except in channel action tables and the card-vault access log. | SOC2 CC7.2, ISO 8.15, PCI 10.3/10.5, GDPR 5(1)(f) | TBD (Platform) | Proposed; [SPEC_BACKLOG per-domain platform "audit_event lacks consumed-event id"](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-08 | **Retention, archival and erasure are not end to end.** archive-service calls `/v1/internal/data-lifecycle/{count,extract,confirm,purge}` on owning services, but no service implements them; DSAR erasure anonymises only customer-service data (+ search contacts) and is not propagated to kyc, onboarding, document, crm, notification, payment (payees), wallet, audit copies or Kafka. | GDPR 5(1)(e)/17, ISO 5.33/8.10, SOC2 C1.2/P4.2 | TBD (Data platform) | [SPEC_BACKLOG per-domain operations "every data-owning service needs /v1/internal/data-lifecycle"](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-09 | **`pii` column marker is documentation only.** CONVENTIONS says `pii` columns are "encrypted at field level, masked in logs and consoles"; `tools/generate.py` only tags them in the data dictionary/tracker. 121 `pii` columns in 29 services are stored in clear (protected by RLS, TLS and storage encryption only). | GDPR 32, ISO 8.11/8.24, SOC2 C1.1 | TBD (Platform) | Proposed (fix the convention or implement it) |
| GAP-10 | **No SLO monitoring or alerting.** Domain services have no Prometheus registry (only `platform/cell-router` does), yet the chart's optional ServiceMonitor scrapes `/actuator/prometheus`; metrics leave only via OTLP. No dashboards, alert rules or business SLIs (payment, card-auth outcome) are defined in the repo. | SOC2 A1.1/CC7.2, ISO 8.16, PCI 10.7 | TBD (SRE) | `spec/plan.yaml` PLT-OBSERVABILITY |
| GAP-11 | **DR not yet proven.** No game day has been run; no data mover for tenant moves, no fault-injection templates, no outbox retention/replay for unplanned failover, no control-plane DR, Kafka MSK IAM client settings missing from the chart. | SOC2 A1.2/A1.3, ISO 5.29/5.30/8.14 | TBD (SRE) | [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs) |
| GAP-12 | **Database connection budget.** 63 services x 2-10 replicas x pool 20 exceed one Aurora cluster; PgBouncer transaction pooling is unsafe with `TenantAwareDataSource` session-level `set_config`. | SOC2 A1.1, ISO 8.6 | TBD (Platform) | [SPEC_BACKLOG Connection budget](../SPEC_BACKLOG.md#connection-budget) |
| GAP-13 | **Staff MFA and access reviews.** The shipped realm (`tools/keycloak_realm.py`) defines password policy and brute-force protection but no MFA authentication flow; production IdP configuration is not in the repo. identity-service has TOTP factors, privileged roles with just-in-time elevation, SoD and session revocation, but no periodic access recertification. IdP clients for business M2M are provisioned manually. | SOC2 CC6.1-CC6.3, ISO 5.18/8.2/8.5, PCI 8.4/7.2.4 | TBD (IAM) | Proposed; [BUSINESS_M2M_API "Not covered yet"](../BUSINESS_M2M_API.md#not-covered-yet) |
| GAP-14 | **Secrets not guarded in customer-service and analytics-service.** `MERIDIAN_CUSTOMER_SECRET_KEY`, `MERIDIAN_CONTACT_LOOKUP_KEY` (customer-service) and `MERIDIAN_ANALYTICS_EMBED_SECRET` fall back to committed dev values, are not in `meridian.required-secrets` and are not injected by Helm. A stock install therefore hashes identifiers/contacts with a public key, and customer-service and search-service compute different contact lookup hashes. | SOC2 CC6.1, ISO 8.24, PCI 3.6 | TBD (Party domain) | [SECURITY_REVIEW SEC-02 residual](../SECURITY_REVIEW.md#findings) |
| GAP-15 | **Legacy (replayable) callback signatures still accepted** by default (`global.callbacks.acceptLegacySignatures: true`); one default rail secret serves all rails. | PCI 6.2.4, SOC2 CC6.6 | TBD (Payments) | [SECURITY_REVIEW SEC-17 residual](../SECURITY_REVIEW.md#remediation-status) |
| GAP-16 | **One shared service identity and no Kafka authorisation.** All services use the `meridian-service` client (`meridian:internal`), so "owning service" checks trust a header asserted by the caller; any service can produce to any `meridian.<domain>.v1` topic (no per-service topic ACLs). | SOC2 CC6.3, ISO 8.2/8.3, PCI 7.2 | TBD (Platform) | [SECURITY_REVIEW SEC-03 / SEC-12 residual](../SECURITY_REVIEW.md#remediation-status); [SPEC_BACKLOG wave A "service-to-service tokens"](../SPEC_BACKLOG.md#cross-cutting-items-raised-in-wave-a-open) |
| GAP-17 | **No edge rate limiting / WAF.** The edge gateway and cell router have no per-IP or per-client limiter; no WAF or Shield in `infra/tofu`. | SOC2 CC6.6/A1.1, PCI 6.4.2 | TBD (Platform) | [SECURITY_REVIEW SEC-16 residual](../SECURITY_REVIEW.md#remediation-status); [BUSINESS_M2M_API](../BUSINESS_M2M_API.md#not-covered-yet) |
| GAP-18 | **Device attestation and offline device crypto.** Onboarding attestation is a placeholder (presence only); agent device attestation (Play Integrity / App Attest) missing; offline outbox PIN can be brute-forced from a storage copy. | SOC2 CC6.8 | TBD (Channels) | [SPEC_BACKLOG customer channel](../SPEC_BACKLOG.md#customer-channel-security-critical-in-progress), [agent channel](../SPEC_BACKLOG.md#agent-channel-follow-up-of-customer-channel); SEC-23 |
| GAP-19 | **No operator action for in-doubt money movements.** A payment return or wallet reversal whose ledger call ended with 5xx stays INITIATED/SUBMITTED/PROCESSING (blocking further returns, 409 `return_in_progress`) with no resolve endpoint. | SOC2 PI1.4/PI1.5 | TBD (Payments / Wallets) | [SECURITY_REVIEW SEC-06 / SEC-07 residual](../SECURITY_REVIEW.md#remediation-status) |
| GAP-20 | **Vault operational controls.** `public_key` still optional on reveal; no endpoint to clear a CVV2 block and no alert event (SEC-24); no audit/alert stream from `card_vault_access_log` to a SIEM. | PCI 3.5, 10.2 | TBD (Cards) | [SECURITY_REVIEW SEC-11 / SEC-24 residual](../SECURITY_REVIEW.md#remediation-status) |
| GAP-21 | **Encryption keys without rotation path.** `MERIDIAN_WEBHOOKS_KEY` (encrypts endpoint signing secrets, format `v1:`) and `MERIDIAN_IDENTITY_MFA_SEED_KEY` are single keys with no previous-key decrypt fallback and no re-encryption job, so rotating them makes stored secrets / TOTP seeds unreadable. (Endpoint signing-secret rotation itself is implemented: `POST /v1/webhook-endpoints/{id}/rotate-secret`, old secret valid 24 h - the SPEC_BACKLOG "webhook needs previous-secret column" entry is stale.) | ISO 8.24, PCI 3.7, SOC2 CC6.1 | TBD (Platform) | Proposed; [SPEC_BACKLOG per-domain platform](../SPEC_BACKLOG.md#per-domain-notes) (stale entry to close) |
| GAP-22 | **Runtime pods hold DDL credentials.** Every Deployment mounts the Flyway schema-owner secret (`meridian-db-owner`) next to the RLS-bound runtime role, so a compromised pod can bypass RLS as table owner. | PCI 7.2, ISO 8.2/8.18 | TBD (Platform) | Proposed (migrations as a Job / init container) |
| GAP-23 | **No egress control.** NetworkPolicies declare `policyTypes: [Ingress]` only; outbound restrictions rely on the in-app `OutboundUrlPolicy` (SEC-08). | PCI 1.3.2, ISO 8.20/8.22 | TBD (Platform) | Proposed |
| GAP-24 | **Organisational ISMS artefacts absent.** No ISMS scope, risk register, policy set, supplier register, HR security, training, incident response plan, BCP policy or management review in the repo or tracker. | SOC2 CC1-CC5, ISO clauses 4-10, 5.x, 6.x | TBD (CISO) | Proposed |
| GAP-25 | **No independent testing yet.** The 2026-10-06 security review was static, read-only; no dynamic pen test, ASV scan, or segmentation test has been performed. | SOC2 CC4.1, ISO 5.35/8.29, PCI 11.3/11.4 | TBD (Security) | `spec/plan.yaml` S22; [PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md) |
| GAP-26 | **Fraud device registry keeps SDK fingerprints as sent**; events published before pseudonymisation age out only with retention. | GDPR 25/32 | TBD (Risk) | [SPEC_BACKLOG fraud follow-ups](../SPEC_BACKLOG.md#fraud-and-routing-follow-ups-2026-10-07) |
| GAP-27 | **Search journal erasure and source backfill** missing in search-service. | GDPR 17 | TBD (Operations domain) | [SPEC_BACKLOG resume point "search gaps"](../SPEC_BACKLOG.md#resume-point-paused-2026-10-07) |
| GAP-28 | **Simulated external adapters** (credit bureau, e-signature, billers, interop switch, regulator gateway, data-share transport, HSM): supplier security due diligence and contracts cannot start until real providers are chosen. | ISO 5.19-5.22, SOC2 CC9.2 | TBD (Vendor management) | [SPEC_BACKLOG Wave B cross-cutting](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |

## Top blockers by framework

- **SOC 2 Type II**: GAP-24 (policies and governance), GAP-04/05 (change management evidence), GAP-06/07/10 (monitoring
  and logging evidence), GAP-13 (access reviews and MFA), GAP-25. An observation window cannot start before GAP-05,
  GAP-06 and GAP-10 produce evidence continuously.
- **ISO/IEC 27001**: GAP-24 (clauses 4-10 and the risk treatment plan are prerequisites), then the Annex A gaps above.
- **PCI DSS (issuer, Level 1 service provider)**: GAP-01, GAP-02, GAP-03, GAP-06, GAP-20, GAP-22, GAP-23, GAP-25.
