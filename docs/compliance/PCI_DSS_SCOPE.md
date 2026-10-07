# PCI DSS scope and readiness (PCI DSS v4.0.1)

Last reviewed: 2026-10-07 · Owner: TBD (Cards platform lead / PCI program owner) · Entity type: issuer processor and
service provider to issuing banks (Level 1 expected) · Assessment: none performed yet (GAP-25)

Related: PIN handling also falls under **PCI PIN Security**; tokenisation under the scheme token-service rules
(VTS / MDES); 3-D Secure ACS under **PCI 3DS Core**. Those are separate assessments and are not covered here beyond
noting the dependencies.

## 1. Cardholder data environment (CDE)

### Components that store, process or transmit account data

| Component | Role | Account data handled | Stores CHD / SAD? | Evidence |
|---|---|---|---|---|
| card-vault-service (`card_vault_db`) | Vault: tokenises PANs, keeps PAN / expiry / CVV2 / PIN encrypted (AES-256-GCM), verifies CVV2 / PIN blocks, reveals for authorised staff, key rotation | PAN, expiry, CVV2, PIN, PIN blocks | **Yes** (PAN, expiry; **SAD: CVV2 and PIN**, see GAP-03) | `spec/domains/cards.yaml` `card_data_vault`; `SoftwareHsm`, `VaultService`, `CardVaultController` |
| card-service | Generates PANs (`PanGenerator`) and sends them to the vault (`Peers.tokenize`); forwards initial PIN blocks to the vault; keeps only `pan_token`, `pan_fingerprint`, BIN, `last4` | PAN and PIN block in transit (memory only, "never logged") | No (token + masked) | `services/card-service/.../infrastructure/Peers.java` |
| card-authorization-service | Real-time authorisation from the card gateway; CVV2 and PIN block (DE48 / DE52) passed to vault `verify`; `raw_message` sanitised (PAN, CVV, PIN, track removed recursively) | SAD in transit (`request_only`, never stored) | No | `AuthorizationService.SENSITIVE`, `sanitize` |
| card-tokenization-service | Token-service provisioning requests (`[public]` `/network/provision-requests`) carry PAN + expiry; card resolved through vault `lookup` | PAN in transit | No (DPAN `last4` only) | `TokenService`, `Downstream` |
| card-threeds-service | ACS: AReq from the directory server carries PAN; resolved through vault `lookup`; CAVV generated with a service key | PAN in transit | No (`card_last4`) | `ThreedsService`, `Downstream` |
| card-clearing-service | Scheme clearing files; **refuses** files containing PAN / CVV / track / PIN columns (`ClearingFileParser.FORBIDDEN`) and matches on card id, ARN, RRN | None (by design) | No | `ClearingFileParser` |
| card-dispute-service | Disputes on card id / authorisation | None expected | No | spec |
| edge-gateway, cell-router | Stream request bodies (including PAN in 3DS / token callbacks and vault calls) | PAN / SAD in transit | No (streaming proxy, bodies not logged) | `ProxyController`, `RouterController` |
| Kubernetes nodes, Aurora cluster, MSK, KMS key of the cell | Host and store the above | Encrypted PAN at rest (Aurora) | Yes (ciphertext) | `infra/tofu/modules/cell` |
| Ops console (`web/meridian-console`) | Staff reveal of PAN / CVV (`cards:pan_reveal`, step-up) | Clear PAN / CVV on screen | No | SEC-11 fix |

Connected-to / security-impacting systems (in scope for the controls that apply to them): Keycloak / IdP, CI pipeline
and image registry, workflow-service (approvals for card program go-live), audit-service (receives card events, no
PAN), identity-service, the OTel collector, every other Meridian pod (because of GAP-02 they can reach the vault).

### Out of scope (by design, to be confirmed by segmentation testing)

Services that only see `card_id`, `pan_token`, BIN or `last4`: ledger, deposit, payment, fraud (card numbers are
pseudonymised as `psn:` HMACs before storage), customer-channel (shows masked cards; no reveal endpoint), rewards,
insights, notification (OTPs redacted, SEC-10). They are out of scope only once GAP-02 is closed; today they share the
network, database cluster and credentials with the vault.

## 2. Account data flows

```
Issuance:      card-service --(PAN generated in memory)--> card-vault POST /v1/card-vault  (encrypt, token, fingerprint)
                         <-- pan_token, fingerprint, bin, last4 --
               card-service --(PIN block wrapped under ZPK)--> card-vault POST /v1/card-vault/pin
Authorisation: card gateway --ISO 8583 (parsed JSON)--> [router] -> [gateway] -> card-authorization
               card-authorization --(pan_token, CVV2, PIN block)--> card-vault POST /verify  -> M/N/P, pin_result
               stored: card_authorization (no PAN, raw_message sanitised); cvv / pin block are request_only
3DS:           directory server --AReq with PAN (HMAC)--> [router] -> [gateway] -> card-threeds
               card-threeds --PAN--> card-vault POST /lookup (keyed fingerprint) -> pan_token, card
Tokenisation:  VTS / MDES --provision request with PAN (HMAC)--> card-tokenization --PAN--> card-vault /lookup
Clearing:      scheme files --> file-gateway -> card-clearing (PAN columns refused; ARN / RRN / card id only)
Staff reveal:  console (user token, cards:pan_reveal, auth_time <= 300 s) --> card-vault POST /reveal
               -> clear PAN / CVV / expiry (or RSA-OAEP-256 encrypted with a caller public key); access logged
```

Not built: card production bureau PAN export (bureau callbacks carry status only), PIN mailer, PIN change at ATM
(PIN translation between ZPKs is not implemented), real scheme connectivity (the card gateway is external).

## 3. Segmentation and key management today

| Control | Current state | Status |
|---|---|---|
| Network | Every service has an ingress-only NetworkPolicy admitting all pods labelled `app.kubernetes.io/part-of: meridian` plus the monitoring namespace; the vault has no stricter policy and no egress policy | Gap (GAP-02, GAP-23) |
| Internet exposure | `/v1/card-vault/*` is in `generated/routes.json`, so the edge gateway routes it; protected by exact scopes, `allowed-clients` (console + service client, SEC-14) and step-up, but reachable from the internet with a token | Partial (GAP-02) |
| Identity | Vault lookup / verify / PIN set require a service token; the service client is shared by all 63 services, so any compromised service can call `lookup` / `verify` | Partial (GAP-16) |
| Database | Separate database `card_vault_db`, RLS, non-owner runtime role; same Aurora cluster, same runtime and owner credentials as every service; owner credentials mounted in the pod | Partial (GAP-02, GAP-22) |
| Secrets | Separate Kubernetes Secret `meridian-card-vault-service-secrets` (`master-key`); startup refuses a missing / dev key outside dev mode (SEC-01) | Implemented |
| KMS | One customer-managed KMS key per cell encrypts Aurora, MSK, S3, EKS secrets and Secrets Manager; no dedicated key for the CDE | Partial |
| Card keys | `SoftwareHsm`: all keys (PAN_DEK, CVV_KEY, PIN_KEY, ZPK, CVK, MDK) derived as HMAC-SHA256(master key, "key:<label>:v<version>") from one master key held in JVM memory; tenant-scoped labels for PAN DEK / PIN key (SEC-37); DEK rotation with background re-encryption (SEC-40); no HSM, no dual control / split knowledge, master key not rotatable in place | Gap (GAP-01) |

Target design (proposed): dedicated namespace (or node pool / cluster) `meridian-cde` with default-deny ingress and
egress, ingress only from card-service, card-authorization, card-tokenization, card-threeds and the console BFF;
vault removed from the public route table (staff reveal via a dedicated internal BFF); per-service Keycloak client for
each CDE caller; dedicated Aurora cluster and KMS key for `card_vault_db`; network HSM (AWS CloudHSM or a payment HSM)
behind the existing `Hsm` interface (`application/Hsm.java`), keys under dual control; PIN stored as PVV / IBM offset;
CVV2 not retained after production.

## 4. Requirement-by-requirement status (high level)

| Req | Topic | Meridian implementation | Status | Gap |
|---|---|---|---|---|
| 1 | Network security controls | VPC per cell, private subnets, SGs for Aurora / MSK, NetworkPolicies (ingress-only, not CDE-specific), router / gateway as the only entry | Partial | GAP-02, GAP-23, GAP-17 |
| 2 | Secure configurations | Hardened pods (non-root, read-only FS, no capabilities, seccomp, no SA token); dev mode refused in-cluster (SEC-13); no default credentials in prod (`RequiredSecrets`) - except GAP-14 | Partial | GAP-14 |
| 3.2 / 3.3 | Account data retention; SAD after authorisation | Authorisation SAD never stored (card-authorization); issuer storage of CVV2 and PIN in the vault | Gap | GAP-03 |
| 3.4 | PAN display masking | Only BIN + `last4` outside the vault; full PAN only with `cards:pan_reveal` + step-up | Implemented | |
| 3.5 | PAN unreadable at rest | AES-256-GCM per record with AAD, keyed fingerprint (HMAC) for lookup, Aurora encryption with KMS | Implemented | GAP-01 (key protection) |
| 3.6 / 3.7 | Key management | DEK rotation, tenant-scoped DEKs; software key derivation from one master key | Gap | GAP-01 |
| 4 | Strong cryptography in transit | TLS to Aurora / MSK; HMAC-signed callbacks; ingress TLS per values; **cluster-internal HTTP between services is plain** | Partial | Proposed (mTLS / mesh inside the CDE) |
| 5 | Malware protection | Immutable read-only containers; no anti-malware on nodes defined | Partial | GAP-04 (image scanning); node anti-malware / EKS GuardDuty runtime TBD |
| 6.2 | Bespoke software developed securely | Conventions, security review, regression tests per finding | Partial | GAP-04 |
| 6.3 | Vulnerabilities identified and patched | SBOM-based SCA and image scanning in CI (fixable CRITICAL blocks), Dependabot; no patch SLA yet | Partial | GAP-04 residual (patch SLA) |
| 6.4 | Public-facing web apps protected | No WAF; CSP / frame headers on staff apps (SEC-22, no script-src yet) | Gap | GAP-17 |
| 6.5 | Change management | CI gates; no deploy pipeline or separation of duties on deploys | Partial | GAP-05 |
| 7 | Restrict access by need to know | Exact scopes `cards:pan_reveal` / `cards:vault_admin`, service-only lookup / verify, `allowed-clients` | Partial | GAP-16, GAP-13 (role reviews, 7.2.4) |
| 8 | Identify and authenticate | OIDC, step-up (`auth_time` / `acr` / `amr`) for reveal; MFA for all CDE access (8.4.2) not enforced in the shipped IdP | Partial | GAP-13 |
| 9 | Physical access | AWS | Inherited | |
| 10.2 | Audit logs | `card_vault_access_log` (who, purpose, caller service from token, success / denied); audit chain of card events; EKS audit logs | Partial | GAP-06, GAP-20 |
| 10.3 / 10.5 | Log protection and retention (12 months, 3 online) | Audit tables append-only; no central log store or retention for application logs | Gap | GAP-06, GAP-07 |
| 10.4 / 10.7 | Log review; failure of controls detected | Alert rules for control failures (EOD, audit consumer lag, outbox, scrape targets) defined; no log review process, alerting stack not deployed | Gap | GAP-06, GAP-10 residual |
| 11.3 | Vulnerability scans (internal, ASV external) | None | Gap | GAP-04, GAP-25 |
| 11.4 | Penetration testing incl. segmentation | Planned ([PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md)) | Gap | GAP-25 |
| 11.5 | Change / intrusion detection | Audit chain, ledger hash verification; no FIM / IDS | Gap | GAP-06 |
| 11.6 | Payment page tampering | Not applicable today (Meridian hosts no e-commerce payment page; 3DS challenge UI TBD) | N/A (review when 3DS challenge pages ship) | |
| 12 | Policies, risk assessment, TPSP management, incident response | Not in repo | Gap | GAP-24 |
| A1 | Multi-tenant service provider | Per-tenant RLS, tenant-scoped DEKs (SEC-37), tenant-bound access logs; tenant isolation tests | Partial | GAP-02 |

## 5. Specific notes

- **CVV2 storage (3.3.1 / 3.3.3).** `card_data_vault.cvv_encrypted` keeps the CVV2 after issuance. Issuers may keep
  SAD only with a documented business need and strong protection (3.3.3). CVV2 can be recomputed from the CVK
  (`SoftwareHsm.deriveCvv`) whenever needed (reveal, verify), so the stored value is not required: drop the column
  for new cards and purge existing values after a key-holder-approved migration. Dynamic-CVV cards already leave it
  empty.
- **PIN storage.** `pin_encrypted` holds the PIN reversibly encrypted under the DEK. PCI PIN requires PINs to be
  processed only in an HSM; store a PIN verification value (PVV) or offset instead and verify inside the HSM.
- **Reveal.** `public_key` is optional, so the API can return clear PAN / CVV in the JSON response. Make it mandatory
  for every caller except a dedicated, logged staff path.
- **Former dev master key.** Any non-local environment that ran with the committed dev master key or callback
  secrets before SEC-01 / SEC-02 is a PCI incident: rotate and re-encrypt (see
  [RUNBOOKS.md - card-vault key](../operations/RUNBOOKS.md#22-card-vault-keys)).
- **Fraud data.** fraud-service pseudonymises card numbers and fragments (`card_number`, `pan`, `card_last4`) with a
  tenant-keyed HMAC before storage, which keeps it out of CHD storage scope (truncated / hashed PAN rules, 3.5.1.1:
  keyed hash - compliant).

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-01 | Real HSM / KMS, dual control, master key rotation | TBD (Cards platform) | [SPEC_BACKLOG Wave B - Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-02 | CDE segmentation (namespace, network, DB, KMS, identity, no public vault route) | TBD (Platform / Cards) | [SECURITY_REVIEW SEC-11](../SECURITY_REVIEW.md#remediation-status); proposed |
| GAP-03 | CVV2 and reversible PIN stored in the vault | TBD (Cards) | [SECURITY_REVIEW SEC-11](../SECURITY_REVIEW.md#findings) |
| GAP-04 | Residual: patch SLA, admission-time signature verification, branch protection evidence | TBD (DevSecOps) | PLT-CI (Done); residual proposed |
| GAP-06 / GAP-07 | Central logging, log retention, audit integrity checks | TBD (SecOps) | Proposed |
| GAP-13 | MFA for all CDE access, access reviews | TBD (IAM) | Proposed |
| GAP-16 | Per-service identity for CDE callers | TBD (Platform) | [SPEC_BACKLOG wave A](../SPEC_BACKLOG.md#cross-cutting-items-raised-in-wave-a-open) |
| GAP-17 | WAF for public endpoints | TBD (Platform) | Proposed |
| GAP-20 | Mandatory `public_key`, CVV block admin / alert | TBD (Cards) | [SECURITY_REVIEW SEC-24](../SECURITY_REVIEW.md#remediation-status) |
| GAP-22 / GAP-23 | DDL credentials in pods; egress policy | TBD (Platform) | Proposed |
| GAP-24 | PCI policies, TPSP register, incident response | TBD (CISO) | Proposed |
| GAP-25 | ASV scans, internal scans, pen test with segmentation test | TBD (Security) | S22 |
| - | Card limit counters read without lock; vault void endpoint for failed issue | TBD (Cards) | [SPEC_BACKLOG Wave B - Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
