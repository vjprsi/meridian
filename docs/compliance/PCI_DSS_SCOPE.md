# PCI DSS scope and readiness (PCI DSS v4.0.1)

Last reviewed: 2026-10-07 (GAP-01 / GAP-02 / GAP-03 update) · Owner: TBD (Cards platform lead / PCI program owner) · Entity type: issuer processor and
service provider to issuing banks (Level 1 expected) · Assessment: none performed yet (GAP-25)

Related: PIN handling also falls under **PCI PIN Security**; tokenisation under the scheme token-service rules
(VTS / MDES); 3-D Secure ACS under **PCI 3DS Core**. Those are separate assessments and are not covered here beyond
noting the dependencies.

## 1. Cardholder data environment (CDE)

### Components that store, process or transmit account data

| Component | Role | Account data handled | Stores CHD / SAD? | Evidence |
|---|---|---|---|---|
| card-vault-service (`card_vault_db`) | Vault: tokenises PANs, keeps PAN / expiry encrypted (AES-256-GCM under per-tenant data keys wrapped by the CDE KMS key, or keys inside a PKCS#11 HSM), derives CVV2 on demand under the CVK, stores the PIN only as a PIN block encrypted under the PIN key, verifies CVV2 / PIN blocks, reveals for authorised staff, key rotation and master key re-wrap | PAN, expiry, CVV2 (derived, transient), PIN blocks | **Yes** (PAN, expiry; PIN block under the PIN encryption key; **no CVV2**, GAP-03 closed) | `spec/domains/cards.yaml` `card_data_vault`; `EnvelopeHsm`, `AwsKmsKeyManagement`, `Pkcs11Hsm`, `VaultService`, `CardVaultController` |
| card-service | Generates PANs (`PanGenerator`) and sends them to the vault (`Peers.tokenize`); forwards initial PIN blocks to the vault; keeps only `pan_token`, `pan_fingerprint`, BIN, `last4` | PAN and PIN block in transit (memory only, "never logged") | No (token + masked); CDE member (processes PAN) | `services/card-service/.../infrastructure/Peers.java` |
| card-authorization-service | Real-time authorisation from the card gateway; CVV2 and PIN block (DE48 / DE52) passed to vault `verify`; `raw_message` sanitised (PAN, CVV, PIN, track removed recursively) | SAD in transit (`request_only`, never stored) | No | `AuthorizationService.SENSITIVE`, `sanitize` |
| card-tokenization-service | Token-service provisioning requests (`[public]` `/network/provision-requests`) carry PAN + expiry; card resolved through vault `lookup` | PAN in transit | No (DPAN `last4` only) | `TokenService`, `Downstream` |
| card-threeds-service | ACS: AReq from the directory server carries PAN; resolved through vault `lookup`; CAVV generated with a service key | PAN in transit | No (`card_last4`) | `ThreedsService`, `Downstream` |
| card-clearing-service | Scheme clearing files; **refuses** files containing PAN / CVV / track / PIN columns (`ClearingFileParser.FORBIDDEN`) and matches on card id, ARN, RRN | None (by design) | No | `ClearingFileParser` |
| card-dispute-service | Disputes on card id / authorisation | None expected | No | spec |
| edge-gateway, cell-router | Stream request bodies (including PAN in 3DS / token callbacks and vault calls) | PAN / SAD in transit | No (streaming proxy, bodies not logged) | `ProxyController`, `RouterController` |
| Kubernetes nodes, Aurora cluster (or the dedicated CDE cluster), MSK, CDE KMS key | Host and store the above | Encrypted PAN at rest (Aurora) | Yes (ciphertext) | `infra/tofu/modules/cell` (`cde.tf`) |
| Ops console (`web/meridian-console`) | Staff reveal of PAN / CVV is **not reachable** since GAP-02 (vault APIs are internal; no console screen uses them); a staff reveal needs an internal BFF inside the CDE | None today | No | GAP-02, GAP-20 |

CDE members (GAP-02, Helm `cde` values group, `zone: cde`): card-vault, card, card-authorization, card-tokenization and
card-threeds. card-clearing (refuses PAN files) and card-dispute (card ids only) stay outside.

Connected-to / security-impacting systems (in scope for the controls that apply to them): Keycloak / IdP, CI pipeline
and image registry, the edge gateway and cell router (transmit 3DS / token-provisioning PANs), the callers admitted by
the CDE NetworkPolicies (below), workflow-service (approvals for card program go-live), audit-service (receives card
events, no PAN), identity-service, the OTel collector.

### Out of scope (by design, to be confirmed by segmentation testing)

Services that only see `card_id`, `pan_token`, BIN or `last4`: ledger, deposit, payment, fraud (card numbers are
pseudonymised as `psn:` HMACs before storage), customer-channel (shows masked cards; no reveal endpoint), rewards,
insights, notification (OTPs redacted, SEC-10). Since GAP-02 they cannot connect to the vault (default-deny
NetworkPolicy, named callers only) and the vault has no edge-gateway route; with `cde.postgres.host` /
`cde_dedicated_db` they no longer share its database cluster or credentials either. Confirming "out of scope" still
needs a segmentation test (11.4.5, GAP-25). Services that call card-service / card-authorization (business-channel,
customer-channel, business-day, card-clearing, card-dispute, insights, regulatory-reporting, archive, customer) are
connected-to systems.

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
Staff reveal:  card-vault POST /reveal (user token relayed by an internal caller, cards:pan_reveal, auth_time <= 300 s)
               -> clear PAN / derived CVV2 / expiry (or RSA-OAEP-256 encrypted with a caller public key); access
               logged. Internal only since GAP-02: the edge gateway has no vault route, no console screen calls it.
Keys:          card-vault --GenerateDataKey / Decrypt / ReEncrypt (context tenant, purpose, key)--> CDE KMS key
               (or every operation inside the PKCS#11 HSM)
```

Not built: card production bureau PAN export (bureau callbacks carry status only), PIN mailer, PIN change at ATM
(PIN translation between ZPKs is not implemented), real scheme connectivity (the card gateway is external).

## 3. Segmentation and key management

The boundary: the five CDE members run with `zone: cde` (Helm `cde` values group, generated by `tools/helm_chart.py`),
optionally in their own namespace (`cde.namespace`, e.g. `meridian-cde`), with default-deny NetworkPolicies that admit
only the callers found in the code. Callers (as generated):

| CDE service | Admitted callers |
|---|---|
| card-vault-service | card-service (tokenize, reissue, PIN set / unblock), card-authorization-service (verify), card-tokenization-service and card-threeds-service (lookup) - nothing else, not the edge gateway |
| card-service | edge-gateway, card-authorization, card-clearing, card-dispute, card-threeds, card-tokenization, business-channel, customer-channel, business-day (EOD jobs), insights, regulatory-reporting, archive / customer (data lifecycle, DSAR) |
| card-authorization-service | edge-gateway (card gateway messages), card-service, card-clearing, business-day, archive / customer |
| card-tokenization-service | edge-gateway (token-service callbacks), card-service, archive / customer |
| card-threeds-service | edge-gateway (directory-server AReq / callbacks), archive / customer |

| Control | Current state | Status |
|---|---|---|
| Network | CDE members: default-deny ingress, only the callers above plus the monitoring namespace (`templates/services.yaml`); with `cde.namespace` a namespace-wide default-deny policy too; optional default-deny egress (`cde.egress.enabled`: DNS, Meridian pods, monitoring, `cde.egress.allowedCidrs` for the database, Kafka, IdP and KMS / HSM endpoints). Non-CDE services keep the ingress-only Meridian-wide policy | Implemented (egress opt-in; GAP-23 outside the CDE) |
| Internet exposure | The vault APIs are `internal: true` in the spec: `generate.py` leaves them out of `generated/routes.json`, so neither the edge gateway nor the cell router can route `/v1/card-vault/*`; callers inside the cluster use ServiceClient | Implemented |
| Identity | Vault lookup / verify / PIN set / tokenize / reissue require a service token; a service that names itself (`X-Meridian-Service`) must be the operation's named caller (`VaultAccessPolicy`, `meridian.cards.vault.callers.*`, 403 `caller_not_allowed`); `allowed-clients` (azp) configurable per cell (`cde.allowedClients`). The service client itself is still shared, so the name is asserted, not proven | Partial (GAP-16) |
| Database | Separate database `card_vault_db`, RLS, non-owner runtime role; CDE members use their own credentials (`cde.postgres.appSecret` / `migrationSecret`) and optionally a dedicated Aurora cluster encrypted with the CDE key (`cde.postgres.host`, tofu `cde_dedicated_db`); owner credentials only in the migration Jobs (GAP-22) | Implemented (dedicated cluster optional; no cross-region replica for it yet) |
| Secrets | `meridian-card-vault-service-secrets` (software master key, development / migration only), CDE database Secrets, HSM PIN Secret; startup refuses a missing / dev key outside dev mode (SEC-01) | Implemented |
| KMS | Dedicated CDE key per cell (`aws_kms_key.cde`, alias `alias/meridian-<cell>-cde`, yearly rotation, multi-region with a replica in the DR cell) used only by the vault's IRSA role and only with the vault's encryption context (`tenant`, `purpose`, `key`), separate from the cell key | Implemented |
| Card keys | Envelope encryption (`EnvelopeHsm`): one data key per tenant, purpose and version from KMS `GenerateDataKey`, stored wrapped with its master key reference, cached 5 min; master key rotation = re-wrap (`POST /v1/card-vault/keys/rewrap`, daily `MasterKeyRotationJob`, KMS `ReEncrypt`; no data re-encryption); legacy derived keys adopted by the re-wrap. HSM mode (`Pkcs11Hsm`, SunPKCS11 for CloudHSM / Luna): keys generated on the token, non-extractable, PIN translation inside the HSM. DEK / PIN key rotation with batched re-encryption / re-translation (SEC-40) | Implemented; key ceremony / dual control is organisational (GAP-01 residual) |
| SAD | CVV2 never stored (derived on demand under the card's CVK, `cvk_key_id`; migration `V001_60` removed stored values); PIN stored only as a PIN block encrypted under the PIN key (`pin_key_id`), compared as ciphertext; in HSM mode the clear PIN block never reaches the JVM | Implemented (GAP-03 closed) |

Remaining gaps of the boundary: no dedicated node pool (CDE pods share EKS nodes with other pods; the node security
group admits all pods to Aurora / MSK), in-cluster traffic is plain HTTP (no mTLS inside the CDE), the vault's
caller identity is asserted with the shared service client until per-service clients exist (GAP-16; set
`cde.allowedClients` to the CDE callers' clients then), CDE egress default-deny is opt-in, staff reveal has no
internal BFF yet (GAP-20), and the segmentation has not been tested (GAP-25).

## 4. Requirement-by-requirement status (high level)

| Req | Topic | Meridian implementation | Status | Gap |
|---|---|---|---|---|
| 1 | Network security controls | VPC per cell, private subnets, SGs for Aurora / MSK, router / gateway as the only entry; CDE members default-deny with named callers, optional CDE namespace and default-deny egress, no gateway route to the vault | Partial | GAP-02 residual (node pool, mTLS), GAP-23, GAP-17 |
| 2 | Secure configurations | Hardened pods (non-root, read-only FS, no capabilities, seccomp, no SA token); dev mode refused in-cluster (SEC-13); no default credentials in prod (`RequiredSecrets`, every service secret declared since GAP-14 was closed) | Partial | GAP-14 |
| 3.2 / 3.3 | Account data retention; SAD after authorisation | Authorisation SAD never stored (card-authorization); CVV2 never stored by the vault (derived under the CVK), stored values removed by migration; PIN only as an encrypted PIN block (PIN Security: kept under the PIN key, HSM-translated in HSM mode) | Implemented | GAP-03 closed |
| 3.4 | PAN display masking | Only BIN + `last4` outside the vault; full PAN only with `cards:pan_reveal` + step-up | Implemented | |
| 3.5 | PAN unreadable at rest | AES-256-GCM per record with AAD under per-tenant data keys wrapped by the CDE KMS key (or HSM keys), keyed fingerprint (HMAC) for lookup, Aurora encryption with KMS | Implemented | |
| 3.6 / 3.7 | Key management | Envelope encryption under a dedicated KMS key (or PKCS#11 HSM), encryption context per tenant and purpose, data key cache TTL, DEK / PIN key rotation, master key rotation by re-wrap, key versions per record; key-custodian procedures, dual control and split knowledge (3.7.6) are organisational | Partial | GAP-01 residual (ceremony, dual control) |
| 4 | Strong cryptography in transit | TLS to Aurora / MSK; HMAC-signed callbacks; ingress TLS per values; **cluster-internal HTTP between services is plain** | Partial | Proposed (mTLS / mesh inside the CDE) |
| 5 | Malware protection | Immutable read-only containers; no anti-malware on nodes defined | Partial | GAP-04 (image scanning); node anti-malware / EKS GuardDuty runtime TBD |
| 6.2 | Bespoke software developed securely | Conventions, security review, regression tests per finding | Partial | GAP-04 |
| 6.3 | Vulnerabilities identified and patched | SBOM-based SCA and image scanning in CI (fixable CRITICAL blocks), Dependabot; no patch SLA yet | Partial | GAP-04 residual (patch SLA) |
| 6.4 | Public-facing web apps protected | No WAF; CSP / frame headers on staff apps (SEC-22, no script-src yet) | Gap | GAP-17 |
| 6.5 | Change management | CI gates; GitOps promotion with change records (impact, rollback), signature / SBOM / vulnerability gates and separation of duties on prod (two approvals excluding author and requester, release-manager sync role) - `deploy/README.md`; not yet operating | Partial | GAP-05 residual |
| 7 | Restrict access by need to know | Exact scopes `cards:pan_reveal` / `cards:vault_admin`, service-only lookup / verify / tokenize / PIN, named callers per vault operation, `allowed-clients` | Partial | GAP-16, GAP-13 (role reviews, 7.2.4) |
| 8 | Identify and authenticate | OIDC, step-up (`auth_time` / `acr` / `amr`) for reveal; MFA for all CDE access (8.4.2) not enforced in the shipped IdP | Partial | GAP-13 |
| 9 | Physical access | AWS | Inherited | |
| 10.2 | Audit logs | `card_vault_access_log` (who, purpose, caller service from token, success / denied); audit chain of card events; EKS audit logs | Partial | GAP-06, GAP-20 |
| 10.3 / 10.5 | Log protection and retention (12 months, 3 online) | Audit tables append-only, hash-chained, verified daily and sealed daily to an S3 Object Lock (COMPLIANCE) bucket with chained manifests (GAP-07); no central log store or retention for application logs | Partial | GAP-06 |
| 10.4 / 10.7 | Log review; failure of controls detected | Alert rules for control failures (EOD, audit consumer lag, outbox, scrape targets) defined; no log review process, alerting stack not deployed | Gap | GAP-06, GAP-10 residual |
| 11.3 | Vulnerability scans (internal, ASV external) | None | Gap | GAP-04, GAP-25 |
| 11.4 | Penetration testing incl. segmentation | Planned ([PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md)) | Gap | GAP-25 |
| 11.5 | Change / intrusion detection | Audit chain, ledger hash verification; no FIM / IDS | Gap | GAP-06 |
| 11.6 | Payment page tampering | Not applicable today (Meridian hosts no e-commerce payment page; 3DS challenge UI TBD) | N/A (review when 3DS challenge pages ship) | |
| 12 | Policies, risk assessment, TPSP management, incident response | Not in repo | Gap | GAP-24 |
| A1 | Multi-tenant service provider | Per-tenant RLS, per-tenant data keys bound to the tenant by the KMS encryption context (SEC-37, GAP-01), tenant-bound access logs; tenant isolation tests | Partial | GAP-25 (segmentation test) |

## 5. Specific notes

- **CVV2 storage (3.3.1 / 3.3.3) - closed.** The vault no longer stores CVV2: `cvv_encrypted` is gone from the
  spec, tokenize no longer accepts a `cvv`, and reveal / verify recompute it under the CVK recorded on the record
  (`cvk_key_id`; a CVK rotation applies to newly issued cards). Migration `V001_60` overwrote and dropped stored values
  on existing databases; `VACUUM FULL` and backup ageing remove the remaining copies (RUNBOOKS 2.2). The CVV2
  algorithm is the vault's HMAC derivation, not the scheme CVV algorithm: issuing real cards needs the scheme
  algorithm in the HSM (payment-HSM adapter, GAP-01 residual).
- **PIN storage - closed for storage, open for a payment HSM.** `pin_encrypted` holds only the ISO 9564 format 0 PIN
  block encrypted under the PIN encryption key with deterministic AES-KWP (`pin_key_id`); verification translates the
  incoming block (zone key -> PIN key) and compares ciphertexts; weak PINs are refused by comparing with encrypted
  reference blocks. In HSM mode the translation runs inside the HSM (the JVM never sees a clear block); in KMS /
  software mode it runs in JVM memory. A payment HSM (Thales payShield, AWS Payment Cryptography) with PVV / IBM
  offset verification is the PCI PIN target.
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
| GAP-01 | Residual: key ceremony, dual control / split knowledge, HSM image variant, key import under a ZMK in envelope modes, payment-HSM adapter (scheme CVV, PVV) | TBD (Cards platform) | [SPEC_BACKLOG Wave B - Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
| GAP-02 | Residual: dedicated node pool, mTLS inside the CDE, default-deny egress on by default, segmentation test | TBD (Platform / Cards) | Proposed |
| GAP-03 | Closed 2026-10-07 (CVV2 derived, PIN blocks under the PIN key) | - | - |
| GAP-04 | Residual: patch SLA, admission policies installed (written: `deploy/policies`), branch protection evidence | TBD (DevSecOps) | PLT-CI (Done); residual proposed |
| GAP-06 / GAP-07 | Central logging, log retention, audit integrity checks | TBD (SecOps) | Proposed |
| GAP-13 | MFA for all CDE access, access reviews | TBD (IAM) | Proposed |
| GAP-16 | Per-service identity for CDE callers | TBD (Platform) | [SPEC_BACKLOG wave A](../SPEC_BACKLOG.md#cross-cutting-items-raised-in-wave-a-open) |
| GAP-17 | WAF for public endpoints | TBD (Platform) | Proposed |
| GAP-20 | Mandatory `public_key`, CVV block admin / alert | TBD (Cards) | [SECURITY_REVIEW SEC-24](../SECURITY_REVIEW.md#remediation-status) |
| GAP-23 | Egress policy (GAP-22, DDL credentials in pods, closed 2026-10-07) | TBD (Platform) | Proposed |
| GAP-24 | PCI policies, TPSP register, incident response | TBD (CISO) | Proposed |
| GAP-25 | ASV scans, internal scans, pen test with segmentation test | TBD (Security) | S22 |
| - | Card limit counters read without lock; vault void endpoint for failed issue | TBD (Cards) | [SPEC_BACKLOG Wave B - Cards](../SPEC_BACKLOG.md#wave-b-results-and-open-items) |
