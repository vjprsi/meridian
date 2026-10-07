# ISO/IEC 27001:2022 Annex A - Statement of Applicability (draft)

Last reviewed: 2026-10-07 · Owner: TBD (ISMS manager) · Version: draft 0.1 (not yet approved by management)

ISMS scope (proposed): design, development, operation and support of the Meridian Core banking platform - the
services, libraries, web apps and infrastructure-as-code in this repository, the CI pipeline, and the AWS cells and
regions operated by Meridian for SHARED and DEDICATED deployment models (`docs/MULTI_REGION.md` section 6).
SELF_HOSTED installations are out of scope (customer-operated). AWS physical and infrastructure controls are inherited.

Prerequisites before this SoA can be certified: clauses 4-10 (context, leadership, risk assessment and treatment
plan, objectives, competence, documented information, internal audit, management review) - none exist yet
(GAP-24 in the [gap register](README.md#gap-register)).

Status: **Implemented**, **Partial**, **Gap**, **Inherited** (AWS, evidence via AWS Artifact). All 93 controls are
applicable unless stated; exclusions are justified inline.

## 5 Organizational controls (37)

| Control | Applicable | Implementation reference | Status | Gap |
|---|---|---|---|---|
| 5.1 Policies for information security | Yes | No policy set in repo; engineering rules in `docs/CONVENTIONS.md` | Gap | GAP-24 |
| 5.2 Information security roles and responsibilities | Yes | Owner placeholders in compliance docs; DR roles (IC, operators A/B, scribe) in `docs/MULTI_REGION.md` section 5 | Partial | GAP-24 |
| 5.3 Segregation of duties | Yes | Maker-checker via workflow-service for ~60 resource types (G11), owner-service registry (SEC-03), `FourEyes.require` fail-closed (SEC-35), identity SoD on elevation, business / merchant matrices with no self-approval | Implemented | GAP-16 (service identity) |
| 5.4 Management responsibilities | Yes | Not documented | Gap | GAP-24 |
| 5.5 Contact with authorities | Yes | Regulatory reporting packs (`regulatory-reporting-service`, KE_CBK / NG_CBN / GB_PRA); regulator incident contacts not documented | Partial | GAP-24 |
| 5.6 Contact with special interest groups | Yes | None (e.g. FS-ISAC, scheme security bulletins) | Gap | GAP-24 |
| 5.7 Threat intelligence | Yes | Sanctions lists ingestion (sanctions-service); no cyber threat-intel feed | Partial | GAP-24 |
| 5.8 Information security in project management | Yes | Security review gate per roadmap step (`SEC-REVIEW-2026-10` in `spec/plan.yaml`); threat model | Partial | GAP-24 (formal gate) |
| 5.9 Inventory of information and other associated assets | Yes | Service inventory (`docs/SERVICES.md`, `generated/services.json`), data dictionary, data classification catalogue (archive-service), tofu state | Implemented | |
| 5.10 Acceptable use of information | Yes | Not documented | Gap | GAP-24 |
| 5.11 Return of assets | Yes | HR process not documented | Gap | GAP-24 |
| 5.12 Classification of information | Yes | `pii` / `secret` / `hide` column markers; `data_classification.sensitivity` | Partial | GAP-09 |
| 5.13 Labelling of information | Yes | `[PII]` / `[SECRET]` tags in `generated/docs/data-dictionary.md`; documents carry restricted flags (DSAR exports) | Partial | |
| 5.14 Information transfer | Yes | TLS, HMAC-signed webhooks and callbacks, signed expiring download links, file-gateway signed links (SEC-36) | Implemented | GAP-15 |
| 5.15 Access control | Yes | Scope model per domain, channel BFFs with object-level authorization (404 + audit on denial), RLS | Implemented | |
| 5.16 Identity management | Yes | identity-service users / roles / sessions; IdP clients per app (SEC-14); M2M business clients bound to technical users (`docs/BUSINESS_M2M_API.md`) | Partial | GAP-13 |
| 5.17 Authentication information | Yes | Secrets only from KMS / Secrets Manager via `secretKeyRef`; `RequiredSecrets` refuses dev defaults; API keys and client secrets SHA-256 hashed, shown once; customer PINs PBKDF2 210k | Partial | GAP-14 |
| 5.18 Access rights | Yes | JIT elevation with approval and expiry; session revocation | Partial | GAP-13 (periodic review) |
| 5.19 Information security in supplier relationships | Yes | No supplier register | Gap | GAP-28, GAP-24 |
| 5.20 Addressing security within supplier agreements | Yes | Callback signature v2 spec to providers (`docs/SERVICE_IMPLEMENTATION_GUIDE.md`); no contract clauses | Gap | GAP-24 |
| 5.21 Managing security in the ICT supply chain | Yes | Gradle version catalog; CycloneDX SBOMs (Gradle + npm), Trivy SCA, Dependabot, cosign-signed images with SBOM attestations (`ci.yml`) | Partial | GAP-04 residual (admission-time verification) |
| 5.22 Monitoring, review and change of supplier services | Yes | Not in place | Gap | GAP-24 |
| 5.23 Information security for use of cloud services | Yes | Tofu modules: KMS CMK with rotation, private subnets, EKS secrets encryption, IRSA, flow logs | Partial | GAP-24 (cloud policy), GAP-23 |
| 5.24 Incident management planning and preparation | Yes | Severity matrix and runbooks ([RUNBOOKS.md](../operations/RUNBOOKS.md)) | Partial | GAP-24 (IR plan), GAP-06 |
| 5.25 Assessment and decision on information security events | Yes | Severity matrix; `platform.audit_chain.broken`, `channels.*_action.denied` events | Partial | GAP-06 |
| 5.26 Response to information security incidents | Yes | Key compromise runbook; break-glass cell OFFLINE | Partial | GAP-24 |
| 5.27 Learning from information security incidents | Yes | Security review remediation table pattern (finding -> fix -> regression test -> residual risk) | Partial | Post-mortem template in RUNBOOKS |
| 5.28 Collection of evidence | Yes | Hash-chained audit with daily verification and daily WORM seals (S3 Object Lock, chained manifests), append-only financial records, audit exports under four-eyes (`/v1/data-exports`), legal holds | Implemented | - |
| 5.29 Information security during disruption | Yes | Security controls identical in standby cells (same chart); break-glass actions audited | Partial | GAP-11 |
| 5.30 ICT readiness for business continuity | Yes | Cells, region pairs, failover runbook ([BCP_DR.md](../operations/BCP_DR.md)) | Partial | GAP-11 |
| 5.31 Legal, statutory, regulatory and contractual requirements | Yes | Regulatory packs, AML retention blocker, PCI scope ([PCI_DSS_SCOPE.md](PCI_DSS_SCOPE.md)), DPIA ([DATA_PROTECTION.md](DATA_PROTECTION.md)) | Partial | GAP-24 (legal register) |
| 5.32 Intellectual property rights | Yes | Licence data per component in the CycloneDX SBOMs (CI artifact `sbom`); no licence policy review yet | Partial | Proposed (licence policy) |
| 5.33 Protection of records | Yes | Append-only tables (`immutable: true`, trigger), audit retention policies (10 y default), archive manifests with WORM retain-until | Partial | GAP-07, GAP-08 |
| 5.34 Privacy and protection of PII | Yes | [DATA_PROTECTION.md](DATA_PROTECTION.md): consents, DSAR, erasure, pseudonymisation | Partial | GAP-08, GAP-09 |
| 5.35 Independent review of information security | Yes | Internal static review only | Gap | GAP-25 |
| 5.36 Compliance with policies and standards | Yes | CI enforces spec / contract / chart conformance; ArchUnit layering | Partial | GAP-24 |
| 5.37 Documented operating procedures | Yes | `docs/operations/*`, `docs/MULTI_REGION.md`, README runbooks | Partial | Procedures not yet exercised |

## 6 People controls (8)

| Control | Applicable | Implementation reference | Status | Gap |
|---|---|---|---|---|
| 6.1 Screening | Yes | HR process (outside repo) | Gap | GAP-24 |
| 6.2 Terms and conditions of employment | Yes | HR process | Gap | GAP-24 |
| 6.3 Information security awareness, education and training | Yes | Secure coding guidance in `docs/SERVICE_IMPLEMENTATION_GUIDE.md` "Security defaults and helpers"; no training programme | Partial | GAP-24 |
| 6.4 Disciplinary process | Yes | HR process | Gap | GAP-24 |
| 6.5 Responsibilities after termination or change | Yes | Session revocation and user suspension exist; leaver process not defined | Partial | GAP-13, GAP-24 |
| 6.6 Confidentiality or non-disclosure agreements | Yes | Legal process | Gap | GAP-24 |
| 6.7 Remote working | Yes | Policy absent; staff apps behind OIDC with step-up | Partial | GAP-24 |
| 6.8 Information security event reporting | Yes | Vulnerability disclosure / internal reporting channel not defined | Gap | GAP-24 |

## 7 Physical controls (14)

Meridian operates no data centre. Cloud facilities are inherited from AWS; office and end-user equipment controls
apply to Meridian's own premises and are organisational.

| Control | Applicable | Implementation reference | Status | Gap |
|---|---|---|---|---|
| 7.1 Physical security perimeters | Yes | AWS regions (data centres); offices: TBD | Inherited / Gap (offices) | GAP-24 |
| 7.2 Physical entry | Yes | AWS; offices TBD | Inherited / Gap | GAP-24 |
| 7.3 Securing offices, rooms and facilities | Yes | Offices TBD | Gap | GAP-24 |
| 7.4 Physical security monitoring | Yes | AWS | Inherited | |
| 7.5 Protecting against physical and environmental threats | Yes | AWS multi-AZ; region pairs | Inherited | |
| 7.6 Working in secure areas | Yes | AWS | Inherited | |
| 7.7 Clear desk and clear screen | Yes | Office policy TBD; web apps keep tokens in `sessionStorage`, PWAs cache no account data | Partial | GAP-24 |
| 7.8 Equipment siting and protection | Yes | AWS | Inherited | |
| 7.9 Security of assets off-premises | Yes | Laptops (policy TBD); agent devices: encrypted offline outbox, wipe after 10 wrong PINs (SEC-23) | Partial | GAP-18, GAP-24 |
| 7.10 Storage media | Yes | AWS media handling; S3 / EBS / Aurora encrypted with the cell CMK | Inherited | |
| 7.11 Supporting utilities | Yes | AWS | Inherited | |
| 7.12 Cabling security | Yes | AWS | Inherited | |
| 7.13 Equipment maintenance | Yes | AWS (managed services) | Inherited | |
| 7.14 Secure disposal or re-use of equipment | Yes | AWS media sanitisation; laptops TBD | Inherited / Gap | GAP-24 |

## 8 Technological controls (34)

| Control | Applicable | Implementation reference | Status | Gap |
|---|---|---|---|---|
| 8.1 User endpoint devices | Yes | Staff laptops (MDM TBD); agent app device binding with ES256 / Ed25519 keys, signed offline batches, `X-Agent-Device-Proof` | Partial | GAP-18, GAP-24 |
| 8.2 Privileged access rights | Yes | `platform:operator` limited to operator tenant (SEC-15); `meridian:admin` does not imply it; privileged roles via JIT elevation and four-eyes; cell `/offline` break-glass audited | Partial | GAP-16, GAP-22 |
| 8.3 Information access restriction | Yes | RLS per tenant, scopes, object-level authorization in all four channel BFFs, operator-only global tables | Implemented | |
| 8.4 Access to source code | Yes | GitHub repository; `.github/CODEOWNERS` per area (placeholder teams); branch protection not evidenced | Partial | GAP-04 residual |
| 8.5 Secure authentication | Yes | OIDC auth code + PKCE (S256), no password grant for staff apps; audience validation; step-up (`auth_time` / `acr` within 5 min) for money movement, approvals, vault reveal; TOTP with lockout (SEC-25); customer PIN PBKDF2 | Partial | GAP-13 (MFA enforced at IdP) |
| 8.6 Capacity management | Yes | HPA / PDB; ledger benchmark; pool sizing | Partial | GAP-12, GAP-10 |
| 8.7 Protection against malware | Yes | File-gateway scanning of inbound files; read-only container root FS; document virus-scan status | Partial | GAP-04 (image scanning); document fields do not check scan status ([SPEC_BACKLOG customer channel](../SPEC_BACKLOG.md#customer-channel-security-critical-in-progress)) |
| 8.8 Management of technical vulnerabilities | Yes | Static security review with remediation tracking; Semgrep, Trivy (dependencies, images) and gitleaks in CI; Dependabot | Partial | GAP-25; no patch SLA defined |
| 8.9 Configuration management | Yes | Generated Helm chart diffed in CI; IaC in OpenTofu with locked providers; per-tenant config in config-service with approvals | Implemented | GAP-05 |
| 8.10 Information deletion | Yes | DSAR erasure (customer-service), purge requests with certificates, idempotency TTL | Partial | GAP-08, GAP-27 |
| 8.11 Data masking | Yes | Masked contact values in search, masked customer lookup in agent channel, card `last4` / tokens only outside the vault, fraud pseudonyms, OTP redaction (SEC-10), ISO 8583 `raw_message` sanitised | Partial | GAP-07, GAP-09 |
| 8.12 Data leakage prevention | Yes | No API returns `secret` columns; SSRF read-back removed (SEC-08/09); webhook log stores status only | Partial | GAP-23 (egress) |
| 8.13 Information backup | Yes | Aurora automated backups (35 d prod / 7 d other), S3 versioning + object lock, cross-region replicas | Partial | Restore tests not yet run ([BCP_DR.md](../operations/BCP_DR.md)) |
| 8.14 Redundancy of information processing facilities | Yes | 3-AZ cells, warm standby cell per region pair, Route 53 failover | Partial | GAP-11 |
| 8.15 Logging | Yes | Audit chain of domain events, channel action logs, vault access log, EKS audit logs, VPC flow logs | Partial | GAP-06, GAP-07 |
| 8.16 Monitoring activities | Yes | OTLP metrics and traces; Prometheus endpoint, SLO / saturation / security alert rules and dashboards (`infra/observability`) | Partial | GAP-10 residual (stack and paging not deployed) |
| 8.17 Clock synchronization | Yes | AWS Time Sync (inherited); application uses `Clocks.now()` (microsecond, DB clock where it matters, e.g. OTP cooldown SEC-16) | Inherited / Implemented | |
| 8.18 Use of privileged utility programs | Yes | Schema-owner (DDL) credentials only in the pre-install / pre-upgrade migration Jobs, never in running pods (GAP-22 closed); `kubectl exec` / DB superuser access not governed in repo | Partial | GAP-24 |
| 8.19 Installation of software on operational systems | Yes | Immutable images, read-only root FS, no package manager use at runtime | Partial | GAP-04 (signature verification at admission) |
| 8.20 Networks security | Yes | Private subnets, SG rules (DB / Kafka from EKS only), TLS, NetworkPolicies | Partial | GAP-23 |
| 8.21 Security of network services | Yes | Route 53 health checks, ALB / ingress TLS (values `ingress.tls`), MSK IAM | Partial | GAP-17 |
| 8.22 Segregation of networks | Yes | VPC per cell; NetworkPolicy admits only Meridian pods + monitoring namespace; cardholder data environment default-deny with named callers, optional own namespace / database cluster / egress policy, no gateway route to the vault | Partial | GAP-02 residual |
| 8.23 Web filtering | Partly | Outbound: `OutboundUrlPolicy` (https, public addresses, pinned DNS, no redirects); staff browsing filtering is organisational | Partial | GAP-24 |
| 8.24 Use of cryptography | Yes | AES-256-GCM with random IV and AAD, HMAC-SHA256 constant-time, PBKDF2, RSA-OAEP-256 for reveals, KMS CMK with rotation, TLS | Partial | GAP-01, GAP-09 |
| 8.25 Secure development life cycle | Yes | Spec-first contracts, generated authz, security review, required tests per service | Partial | GAP-04 |
| 8.26 Application security requirements | Yes | Conventions (idempotency, If-Match, problem+json, scopes, step-up), callback v2 spec | Implemented | |
| 8.27 Secure system architecture and engineering principles | Yes | Hexagonal layering (ArchUnit), one DB per service, channel BFFs (customers never call domain services), cells | Implemented | |
| 8.28 Secure coding | Yes | Parameterised SQL with allow-listed identifiers, `SafeRegex`, `AdvisoryLocks`, `FourEyes`, `OutboundHttp` helpers | Implemented | GAP-04 (no automated SAST) |
| 8.29 Security testing in development and acceptance | Yes | Security regression tests per finding (see SECURITY_REVIEW Regression tests column); e2e journeys (`tools/e2e/journeys.py`) | Partial | GAP-25, GAP-04 |
| 8.30 Outsourced development | Conditional | Applies if contractors / AI agents contribute; same CI gates; contributor agreements TBD | Partial | GAP-24 |
| 8.31 Separation of development, test and production | Yes | Dev mode (headers) only with explicit flag and refused inside Kubernetes (SEC-13); separate tofu envs (`staging-eu1a`, `prod-eu1`) and state keys | Implemented | |
| 8.32 Change management | Yes | CI gates; business configuration changes under maker-checker | Partial | GAP-05 |
| 8.33 Test information | Yes | Synthetic data generators (`ReconBenchmarkGenerator`, load test random accounts), demo tenant / starter packs; no rule forbidding production data in test | Partial | GAP-24 (policy) |
| 8.34 Protection of information systems during audit testing | Yes | Pen test rules of engagement ([PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md)) | Partial | |

## Summary

| Theme | Implemented | Partial | Gap | Inherited (incl. mixed) |
|---|---:|---:|---:|---:|
| 5 Organizational (37) | 4 | 22 | 11 | 0 |
| 6 People (8) | 0 | 3 | 5 | 0 |
| 7 Physical (14) | 0 | 2 | 1 | 11 |
| 8 Technological (34) | 6 | 25 | 2 | 1 |

Mixed rows (for example "Inherited / Gap") are counted by their first status; recompute when the SoA is approved.

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-24 | ISMS clauses 4-10, policy set, HR, supplier management, physical (offices) | TBD (CISO) | Proposed |
| GAP-04 | Residual: branch protection evidence, admission-time signature verification, IaC scanning | TBD (DevSecOps) | PLT-CI (Done); residual proposed |
| GAP-06 / GAP-10 | Logging and monitoring | TBD (SecOps / SRE) | PLT-OBSERVABILITY |
| GAP-08 / GAP-27 | Information deletion across services | TBD (Data platform) | [SPEC_BACKLOG per-domain operations](../SPEC_BACKLOG.md#per-domain-notes), [resume point](../SPEC_BACKLOG.md#resume-point-paused-2026-10-07) |
| GAP-09 | Field-level encryption / masking of `pii` columns | TBD (Platform) | Proposed |
| GAP-13 | MFA at the IdP, access reviews | TBD (IAM) | Proposed |
| GAP-01 / GAP-02 | Card key management and CDE segregation | TBD (Cards) | [SPEC_BACKLOG Wave B Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-11 | Continuity testing | TBD (SRE) | [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs) |
| GAP-23 | Egress (GAP-22, DDL creds in pods, closed 2026-10-07) | TBD (Platform) | Proposed |
| GAP-25 | Independent review / pen test | TBD (Security) | S22 |
