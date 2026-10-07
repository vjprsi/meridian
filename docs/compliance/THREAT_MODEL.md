# Threat model (STRIDE per trust boundary)

Last reviewed: 2026-10-07 · Owner: TBD (Security architect) · Method: STRIDE per trust boundary, mitigations traced to
code / config, cross-referenced with [SECURITY_REVIEW.md](../SECURITY_REVIEW.md) findings (SEC-nn) and the
[gap register](README.md#gap-register) (GAP-nn). Review on every roadmap step that adds a boundary or an external party.

## System overview and trust boundaries

```
 Customers / agents / business users / merchants        Staff (bank, Meridian operators)      External providers
   web apps (customer-web, agent-app PWA offline,         meridian-console, developer-portal     rails, card gateway, DS (3DS),
   business-portal, merchant-app), M2M clients                                                   VTS/MDES, KYC, bureaus, e-sign
        |  B1 internet (TLS, OIDC tokens)                       |                                     |  B6 [public] callbacks (HMAC)
        v                                                       v                                     v
   Route 53 -> cell router (JWT tenant, header strip) --> cell edge gateway (routes, header strip, body caps)
                                                                   |
        B2 channel BFFs: /v1/me, /v1/agent, /v1/business, /v1/merchant   |   domain services (operator APIs, scopes)
                                                                   |
        B3 service-to-service: http://{name}, client-credentials token (meridian:internal), X-Meridian-Service
                                                                   |
        B4 Kafka: outbox relay -> meridian.<domain>.v1 -> consumers (audit, search, fraud, channels, ...)
        B5 PostgreSQL / Aurora: one DB per service, RLS (app.tenant_id), runtime role meridian_app
        B7 admin paths: platform:operator APIs, kubectl / Helm, DB owner role, tofu, CI
        B8 offline agent devices: IndexedDB outbox (AES-GCM, PIN-derived key), device signing key
        B9 outbound: tenant-configured URLs (webhooks, KYC, rails, JIT, SMTP, file fetch)
```

Assets ranked: (1) money movement integrity (ledger, payments, wallets, cards), (2) card data and keys, (3) tenant
isolation, (4) customer personal data, (5) privileged identity (roles, approvals), (6) availability of payments and
card authorisation, (7) audit trail.

## B1 Internet -> cell router -> edge gateway -> services

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Forged tenant or identity headers (`X-Tenant-Id`, `X-User`, channel ids) | jwt mode default (SEC-13); router and gateway strip internal headers (`HeaderPolicy`, `HeaderPolicyParityTest`); tenant only from verified `tenant_id` claim; `X-Tenant-Id` honoured only with `meridian:internal` tokens (`TenantFilterTest`) | Dev mode outside Kubernetes still open (SEC-13 residual) |
| S | Token from another app / audience used against operator APIs | Audience validation (`AudienceValidator`, SEC-14); per-app clients with minimal scopes; channel scopes exact-only (`channels:customer` etc.) | Platform-wide audience (no per-service audiences) |
| T | Request smuggling / oversized bodies | Body caps at gateway (16 MB, 64 MB file services, chunked counted) and in services (`BodySizeFilter`, SEC-26) | No Jackson `maxDocumentLength` |
| R | Client denies an action | Idempotency-Key per POST with stored fingerprint; channel action logs; audit chain of resulting events | Reads not audited (GAP-07) |
| I | Reconnaissance via actuator / errors | Gateway exposes health / info only (SEC-31); problem+json with stable codes | Service actuators (`metrics`, `prometheus`) reachable from any Meridian pod |
| D | Volumetric / credential-stuffing / SMS pumping | Onboarding per-destination and per-tenant OTP quotas (SEC-16); IdP brute-force protection; HPA | No edge rate limiting or WAF (GAP-17) |
| E | Unscoped hand-written endpoint | Deny-by-default `ScopeInterceptor` (SEC-29) | - |

## B2 Channel BFFs (customer, agent, business, merchant)

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Customer token used on operator APIs (or vice versa) | Channel tokens carry no domain scope (403 `insufficient_scope`); `/v1/me` etc. accept only their exact scope; staff / service tokens refused on channels | - |
| S | Business / merchant user impersonation via claims | Channel's own `business_user` / `merchant_user` record is authoritative (ACTIVE, same business); roles never from token; first-admin bootstrap off in jwt mode (provisioning by bank staff) | Trust in IdP admin-only attributes; merchant first-owner bootstrap still on first sign-in ([SPEC_BACKLOG merchant](../SPEC_BACKLOG.md#merchant-channel-s19-merchant-app)) |
| T | BOLA: referencing another customer's / business's object | Object-level authorization on every reference -> 404, audited (`*_action`) and published (`channels.*_action.denied`); bodies rebuilt server-side; derived per-user Idempotency-Keys | Wallet transactions carry no store (merchant scoping approximate) |
| T | Approval bypass in business / merchant flows | Approval matrix (bands, number of approvers, sequential), no self-approval, step-up per approval, release once (`bzp-release:<id>`), checksum of rebuilt request; merchant refunds / payout changes to bank's central inbox | Payment-service approval levels the business cannot satisfy stay with bank staff |
| R | User disputes a payment | `business_action` log, approvals with step-up timestamps, audit chain | - |
| I | Data leakage in responses | fraud fields stripped from customer responses; masked lookups; KYB risk fields never returned | - |
| D | Abuse of list endpoints | Limits capped (1..200), cursor paging | Merchant history reads whole pages (`max-transaction-pages`) |
| E | Machine client acting as approver / admin | M2M technical users MAKER / VIEWER only; m2m token must match TECHNICAL user and `client_id` (`docs/BUSINESS_M2M_API.md`) | IdP client provisioning manual; no mTLS / FAPI for business clients |

## B3 Service-to-service

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Compromised service claims to be another (`X-Meridian-Service`) | Header honoured only with an internal token (`TenantFilterTest.callingServiceHeaderIsHonouredOnlyWithAnInternalToken`); approval creation restricted to owner service (SEC-03); ledger limit changes only by owning service (SEC-12) | **One shared client `meridian-service` for all services**: the calling-service name is asserted, not proven (SEC-03 / SEC-12 residual, GAP-16) |
| T | Tampering in transit inside the cluster | NetworkPolicy ingress from Meridian pods only | Plain HTTP `http://{name}` (no mTLS); no egress policies (GAP-23) |
| R | Actions performed "as service" with no human | On-behalf-of principal is the human (`X-User`), service principals `svc:` / `service:` recognised by `FourEyes.isService` (SEC-35) | - |
| I | Card data reachable from any pod | Vault lookup / verify / PIN / tokenize service-only, exact scopes, named callers per operation; CDE NetworkPolicy admits only card-service, card-authorization, card-tokenization and card-threeds; no gateway route | A compromised CDE caller with the shared client can still call them (GAP-16); no mTLS inside the CDE (GAP-02 residual) |
| D | Cascading failures, retries | Timeouts (e.g. fraud 2 s budget), fraud fail-open / fail-closed thresholds, card stand-in | Resilience4j not uniformly applied (not verified) |
| E | Customer token forwarded downstream | Channels call with their own service identity + `X-On-Behalf-Of-Customer`; customer token never forwarded | - |

## B4 Kafka (events)

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Forged event on a domain topic (e.g. `platform.approval_request.approved`) | Consumers bind tenant from `ce_tenantid`; approval dispatcher ignores decisions whose `owner_service` is another service (SEC-03); handlers match stored approval ids for most types | No topic ACLs per producer; MSK IAM settings not in the chart (GAP-16, [MULTI_REGION section 7](../MULTI_REGION.md#7-what-a-real-game-day-still-needs)) |
| T | Replay / duplicate delivery | Consumers idempotent by `ce_id`; audit `source_event_id` unique; outbox written in the business transaction | - |
| R | Event history lost | Audit-service consumes every `meridian.*.v1` topic into a hash chain | Outbox rows deleted after relay (no replay window for unplanned failover) |
| I | Personal data in events and their retention | Contact events carry masked value + hash; OTPs redacted (SEC-10); fraud pseudonyms | Most events carry the full entity representation incl. personal data on Kafka (GAP-09); audit copies are pseudonymised since GAP-07 |
| D | Consumer lag (audit, search, fraud features) | Lag metrics (Kafka consumer `records_lag_max`, `meridian.search.indexer.lag`, outbox backlog) with alert rules (`infra/observability`) | Alerting stack not deployed (GAP-10 residual) |
| E | - | - | - |

## B5 Databases and RLS

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Cross-tenant query | RLS policy on every tenant table, runtime role non-owner, `TenantAwareDataSource` stamps nil UUID when unbound; smoke test in CI | PgBouncer transaction pooling would leak `app.tenant_id` ([SPEC_BACKLOG connection budget](../SPEC_BACKLOG.md#connection-budget), GAP-12) |
| T | Direct modification of financial history | Append-only tables (trigger), ledger hash chain + `verify`, audit hash chain + `verify-chain`, optimistic locking | Owner secret readable in the namespace with Secret read access (RBAC, GAP-24); daily full verification and WORM seals detect but do not prevent rewrites |
| T | Money races (double reversal, return, refund, FX reuse, duplicate inbound) | Row locks + unique indexes / CHECKs (SEC-04..07), advisory locks for inbound (SEC-28) | No DB unique index for inbound dedupe (advisory lock only) |
| I | SQL injection | Bound parameters, allow-listed identifiers (`CrudEngine`), no user SQL | - |
| I | Backup / snapshot theft | KMS-encrypted Aurora, snapshots, S3; card data under data keys wrapped by the CDE KMS key (or HSM keys), no CVV2 stored | A backup plus the vault's IAM role can unwrap; key ceremony / dual control organisational (GAP-01 residual) |
| D | Connection exhaustion | Per-service pools, `dbPool` override | GAP-12 |
| E | Escalation to table owner | Flyway as owner only in the pre-install / pre-upgrade migration Job; running pods hold the RLS-bound runtime role only | Owner secret readable in the namespace by anyone with Secret read access (RBAC, GAP-24) |

## B6 Provider callbacks (`[public]` routes)

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Forged rail / 3DS / tokenisation / KYC / e-sign / bureau callback (e.g. `pacs.008` crediting any account) | HMAC secrets required at startup (SEC-02); v2 signature over `ts.nonce.tenant.body` with skew and single-use nonce (`CallbackVerifier`, SEC-17); per-provider secrets possible | Legacy body-only signatures accepted by default; one default rail secret (GAP-15) |
| T | Altered fields outside the signature | v2 covers the full canonical payload (KYC score / reasons, 3DS auth id) | Until legacy is off |
| R | Provider denies sending | Nonce store, routing inbound message log | - |
| I | Tenant enumeration via `tenant_id` parameter | Tenant resolved from signed entity or registered URL; unknown -> generic error | - |
| D | Large unauthenticated bodies | Raw-body cache size-limited; gateway caps | No per-source rate limiting (GAP-17) |
| E | Callback used to approve card authorisations (JIT over http) | `jit_webhook_url` checked by `OutboundUrlPolicy` (SEC-18) | JIT responses not signed (SEC-18 residual) |

## B7 Admin and operator paths

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Tenant admin acting as platform operator | `platform:operator` only for the operator tenant or internal tokens, never implied by `meridian:admin` (SEC-15) | - |
| T | Forged approval request (self-elevation) | Owner-service-only creation, execution from recorded `privileged_change`, re-check of elevation policy (SEC-03); amount-less requests get every level | Handlers not yet all bound to stored approval id (SEC-03 residual) |
| T | Tenant move / cell status abuse | Tenant moves maker-checker (`tenant_move`); cell `/offline` single-operator break-glass, audited | Break-glass by design; needs alerting (GAP-06) |
| R | Operator denies change | Approval records, audit chain, EKS audit logs | No central log store (GAP-06) |
| I | Staff bulk data access (PAN reveal, exports) | Exact `cards:pan_reveal` + step-up + access log; audit / DSAR exports under four-eyes | Reads of personal data via operator APIs not audited (GAP-07) |
| D | Destructive infra change | tofu `deletion_protection` in prod; PDBs; deployments only via reviewed promotion PRs, prod manual sync in windows, ApplicationSets never delete prod Applications, Argo CD project kind allow-list | GitOps not yet installed (GAP-05 residual) |
| E | kubectl / DB superuser access | EKS private endpoint option (`eks_public_endpoint`), IRSA | No documented privileged-access process (GAP-24); MFA at IdP not enforced (GAP-13) |
| E | Supply chain (dependency / image / CI) | Pinned versions, CI checks, SBOM + Trivy SCA, image scan, cosign signatures + SBOM attestations, CODEOWNERS, Dependabot | No admission-time signature verification; branch protection not evidenced (GAP-04 residual) |

## B8 Offline agent devices

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| S | Stolen device replays operations | Device registration with non-extractable WebCrypto key; `X-Agent-Device-Proof` within 5 min; device revocation (`device_key_revoked`) | Device attestation missing (GAP-18) |
| T | Tampered offline batch | Batches signed by the ACTIVE device key over canonical text; server verifies (`device_signature_invalid`); Idempotency-Key per operation from capture | - |
| R | Agent disputes a queued transaction | Signed payload forwarded as `payload_signature`; reconciliation of unknown outcomes by `idempotency_key` | - |
| I | Queued PII / ID photos from a lost device | AES-GCM outbox, PBKDF2 210k from 6-digit PIN, wipe after 10 wrong PINs, customer PINs never stored, reference cache cleared on identity change (SEC-23 / SEC-33) | Offline brute force from a storage copy (SEC-23 residual, GAP-18) |
| D | Queue flooding | Offline limits (amount, total, count, hours) and last known float | Client-side limits only; server re-validates on replay |
| E | Cash-out offline | Refused offline (needs live verification) | - |

## B9 Outbound calls to tenant-configured endpoints

| STRIDE | Threat | Mitigations (evidence) | Residual / reference |
|---|---|---|---|
| I / E | SSRF to metadata / internal services (webhooks, file fetch, KYC base URL, rail endpoints, JIT, SMTP, sanctions sources) | `OutboundUrlPolicy` / `OutboundHttp`: https, public addresses only, pinned resolution, no redirects, size / time limits; status-only delivery logs (SEC-08/09/18/39) | SMTP resolution not pinned per connection; no network egress policy (GAP-23) |
| S | Spoofed sender on shared SMTP relay | Platform sender domains, Reply-To for others (SEC-38) | No DNS domain verification per tenant |

## Abuse cases to keep in regression tests

Each has an existing test (keep green) or is a pen-test scenario ([PEN_TEST_SCOPE.md](PEN_TEST_SCOPE.md)):
cross-tenant read (RLS smoke test), BOLA per channel (channel flow ITs), self-approval (`FourEyesTest`,
`WorkflowFlowIT`), forged approval (`onlyTheOwningServiceOpensRequestsForItsResourceTypes`), double reversal / return /
refund (`LedgerPostingIT`, `PaymentFlowIT`, `WalletFlowIT`), FX quote reuse (`FxFlowIT`), callback replay
(`CallbackVerifierTest`, `RoutingFlowIT`), SSRF (`DownstreamPolicyTest`, `SmtpEmailAdapterSecurityTest`), idempotency key
reuse (`IdempotencyFingerprintTest`), oversized body (`ProxyControllerIT`), CVV2 brute force (`CardVaultFlowIT`).

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-16 | Per-service identities; Kafka topic ACLs | TBD (Platform) | [SPEC_BACKLOG wave A](../SPEC_BACKLOG.md#cross-cutting-items-raised-in-wave-a-open); [SEC-03 residual](../SECURITY_REVIEW.md#remediation-status) |
| GAP-15 | Turn off legacy callback signatures; per-rail secrets | TBD (Payments) | [SEC-17](../SECURITY_REVIEW.md#remediation-status) |
| GAP-17 | Edge rate limiting / WAF | TBD (Platform) | [SEC-16](../SECURITY_REVIEW.md#remediation-status) |
| GAP-02 / GAP-23 | CDE segmentation, egress policies, in-cluster mTLS | TBD (Platform) | Proposed |
| GAP-18 | Device attestation; offline PIN brute force | TBD (Channels) | [SPEC_BACKLOG agent channel](../SPEC_BACKLOG.md#agent-channel-follow-up-of-customer-channel) |
| GAP-06 / GAP-07 | Detection: central logs, read auditing (scheduled integrity checks done 2026-10-07) | TBD (SecOps) | Proposed |
| - | JIT response signing | TBD (Cards) | [SEC-18 residual](../SECURITY_REVIEW.md#remediation-status) |
| - | Inbox: any bank-side caller may send as SYSTEM / BOT | TBD (Engagement) | [SPEC_BACKLOG customer channel](../SPEC_BACKLOG.md#customer-channel-security-critical-in-progress) |
| - | Outbox retention for replay after unplanned failover | TBD (Platform) | [MULTI_REGION section 4](../MULTI_REGION.md#4-data-replication-and-rpo) |
