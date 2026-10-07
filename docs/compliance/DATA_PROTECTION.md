# Data protection (GDPR / UK GDPR / Kenya DPA / Nigeria NDPA - common baseline)

Last reviewed: 2026-10-07 · Owner: TBD (Data Protection Officer) · Role of Meridian: **processor** for tenant banks
(controllers) in the SHARED and DEDICATED models; controller only for its own staff and developer-portal accounts.
SELF_HOSTED tenants process data entirely in their own environment.

The lawful basis, retention period and notice for customer data are the tenant bank's decisions (controller). The
platform provides the mechanisms; placeholders below show what each tenant must fill in.

## 1. Personal data inventory

Source: columns marked `pii` in `spec/domains/*.yaml` (121 columns in 29 services, listed in
`generated/docs/data-dictionary.md` with `[PII]`) plus personal data that the spec does not mark (noted "unmarked").
Important: the `pii` marker is **documentation only** today; it does not encrypt or mask anything (GAP-09).

| Domain / service | Personal data (tables -> columns) | Data subjects | Special / sensitive |
|---|---|---|---|
| customer-service | `customer` (display / first / middle / last / legal name, title, name in local script, date and place of birth, deceased_on), `customer_identifier.identifier_value` (+ keyed `value_hash`), `customer_address`, `customer_contact` (phone, e-mail; `lookup_hash`, masked value), consents with capture evidence (IP, device) | Retail and business customers, signatories, UBOs | National ids; deceased data |
| onboarding-service | `onboarding_application` (phone, e-mail, `collected_data`), `onboarding_step.input`, `onboarding_otp.destination` | Applicants (pre-customer) | ID photos via document-service |
| kyc-service | `kyc_document` (document number, extracted data), `kyc_screening.screened_name` | Customers, UBOs | Biometric / liveness results if a provider returns them; PEP / sanctions status |
| document-service | `document.document_number`, `generated_document.data`; stored files in S3 (ID scans, contracts, statements, DSAR packages) | All | ID images |
| identity-service | `app_user` (e-mail, first / last name, phone), `mfa_factor.destination`, sessions (unmarked: IP / user agent) | Staff of tenant banks and Meridian | |
| payment-service | `payment` (debtor / creditor name, account numbers, creditor address, `risk_context` with device fingerprint, IP, IP country, session id), `payee` (name, account number, address, e-mail, phone), `payee_alias`, `payee_verification`, `bulk_payment_item`, `payment_mandate`, `request_to_pay.payer_alias_value` | Customers and counterparties (third parties) | Financial data |
| fraud-service | Identifiers only as tenant-keyed pseudonyms (`psn:`, `h1:`); device registry keeps the SDK fingerprint as sent (unmarked) | Customers, counterparties | Profiling (automated decisions: ALLOW / REVIEW / BLOCK) |
| sanctions-service, transaction-monitoring, case-management | `screening_request` (full name, date of birth, identifiers); alerts and SAR cases (unmarked narrative fields) | Customers, counterparties | Criminal-offence-related data (SARs) |
| wallet-service, agent-network, merchant, biller, channel, disbursement, interop | MSISDNs, payee names, bank-link account numbers, agent / merchant tax ids and contacts, USSD sessions, SMS bodies, beneficiary national ids | Wallet holders, agents, merchants, beneficiaries | |
| card-service / card-vault | Cardholder name, delivery address and phone (`card_order`); PAN etc. in the vault (see [PCI_DSS_SCOPE.md](PCI_DSS_SCOPE.md)) | Cardholders | |
| loan-origination, collections, collateral | Applicant date of birth, employer; collection contact addresses; (unmarked) bureau results, income | Borrowers, guarantors | Credit data |
| crm-service, inbox-service, card-dispute | Case descriptions, comments, complaints, interaction summaries, notes, copilot prompts / outputs, secure messages, dispute descriptions | Customers, staff | Free text may contain anything (health, etc.) |
| notification-service | `notification.destination`, `body` (security codes redacted, SEC-10) | Customers, staff | |
| regulatory-reporting | CRS / FATCA records (holder name, address, date of birth, TIN) | Account holders | Tax data |
| openbanking-service | Payment initiation creditor name / account; consents (unmarked) | PSUs | |
| rewards, insights, campaign | Partner member ids, voucher codes, referee contacts; (unmarked) spending insights, segments | Customers | Profiling (marketing) |
| channel BFFs | Action / denial logs (`customer_action`, `agent_action`, `business_action`, `merchant_action`) with IP (right-most forwarded hop) | Channel users | |
| audit-service | `audit_event.after_state` = full domain event payload (personal data **not masked**, GAP-07), `ip_address`, `user_agent` | Everyone | Copies of all of the above |
| search-service | Indexed documents (masked contact values + lookup hashes; names) | Customers | |
| Kafka topics, outbox | Every event payload (entity API representation) for the topic retention period | Everyone | |
| ai-platform-service / fraud training sets | Numeric features only (`docs/ML_FRAUD_TRAINING.md`) | Customers | |

## 2. Purposes and lawful bases (tenant placeholders)

| Processing | Typical lawful basis (GDPR art. 6) | Tenant to confirm | Platform support |
|---|---|---|---|
| Account opening, KYC / KYB, payments, cards, lending | Contract (6(1)(b)); legal obligation (6(1)(c)) for KYC / AML | TBD per tenant | `consent.lawful_basis` enum: CONSENT, CONTRACT, LEGAL_OBLIGATION, LEGITIMATE_INTEREST |
| Sanctions screening, transaction monitoring, SAR filing | Legal obligation; art. 10 / national AML law for criminal data | TBD | Case management under four-eyes |
| Fraud scoring and holds (automated decision) | Legitimate interest / legal obligation (PSD2 SCA, fraud prevention); art. 22 safeguards: human review of REVIEW / BLOCK, appeal via servicing | TBD | Fraud holds reviewed by analysts (`fraud-review-decision`) |
| Credit scoring and decisions | Contract + art. 22(2)(a); right to human intervention | TBD | Credit decisions under central approvals |
| Marketing, campaigns, insights, rewards profiling | Consent | TBD | Consents per purpose; OBJECTION / RESTRICTION withdraw them |
| Regulatory reporting (CRS / FATCA, CBK / CBN / PRA returns) | Legal obligation | TBD | regulatory-reporting packs |
| Staff identity, audit of staff actions | Legitimate interest / legal obligation | Meridian (controller for its staff) | |

## 3. Retention

| Mechanism | What it does | Status |
|---|---|---|
| `audit_retention_policy` (audit-service) | Years to retain audit and business records per resource type and country (default 10), archive after N days | Configuration only; no job enforces it (GAP-07 / GAP-08) |
| archive-service `retention_policy` / `archive_job` / `archived_object` | Per data class and jurisdiction: online days, total years, ARCHIVE_THEN_PURGE / ANONYMISE, Parquet to WORM object storage with SHA-256 manifests and `worm_retain_until`; legal holds override | Implemented in archive-service; **no owning service implements `/v1/internal/data-lifecycle/*`**, so jobs cannot extract or purge anything yet (GAP-08) |
| `purge_request` | Purge / anonymise at end of retention, four-eyes, blocked by legal holds, certificate of destruction with hash | Implemented (archive side) |
| AML record keeping | DSAR erasure keeps identity and KYC data 5 years after the relationship ends (`PrivacyService.AML_RETENTION_YEARS`) | Implemented (fixed 5 y; should be per jurisdiction, e.g. 7 y in some markets) |
| Idempotency keys (may contain response bodies with personal data) | Purged after 30 days (`meridian.idempotency.retention`, SEC-34) | Implemented |
| Kafka topics, application logs, OTel traces | Retention set by the cluster / collector, not by Meridian | Gap: define per environment |
| S3 documents | Versioning + optional object lock (GOVERNANCE, `object_lock_days`) | Implemented (infra) |

Retention schedule (placeholder, per tenant and jurisdiction): transactions TBD y, KYC TBD y after relationship end,
communications TBD, audit 10 y, marketing consents until withdrawal + TBD, CCTV / call recordings N/A.

## 4. Data subject rights (DSAR)

Flow (customer-service, `/v1/privacy-requests`, statutory deadline in `due_at`):

1. **Receive**: request created by staff (or via servicing), type ACCESS / EXPORT / RECTIFICATION / ERASURE /
   OBJECTION / RESTRICTION, status RECEIVED.
2. **Verify identity**: `POST /{id}/verify-identity` (method recorded) -> IN_PROGRESS.
3. **Execute**: `POST /{id}/execute`
   - ACCESS / EXPORT: all customer-service data of the customer (secrets excluded) compiled into a JSON package stored as
     a restricted document in document-service (`result_document_id`).
   - ERASURE: consent-based processing stops at once (MARKETING, PROFILING, ANALYTICS, LOCATION, DATA_SHARING); if the
     relationship is open -> blocker CONTRACT, nothing else erased; if ended but within AML retention -> devices and
     credentials revoked, contacts deleted (and removed from search), identity kept with blocker LEGAL_OBLIGATION and
     `retain_until`; otherwise full anonymisation (names, dates, addresses, contacts deleted; identifiers set to ERASED
     with a random hash; customer CLOSED with reason ERASED_ON_REQUEST; `erased_at` set). Blockers listed in
     `retention_blockers` and the request completes PARTIALLY_COMPLETED when any apply.
   - OBJECTION / RESTRICTION: consents withdrawn, marketing flags off.
   - RECTIFICATION: changes made through normal update APIs, then confirmed.
4. **Reject**: `POST /{id}/reject` with reason. Events `party.privacy_request.completed` / `.rejected`.

Audit-side DSAR exports: `POST /v1/data-exports` (`export_type`, `subject_type CUSTOMER`, `subject_id`, `due_date`) under
four-eyes approval, signed download URL with expiry.

Limits (GAP-08, GAP-27): ACCESS / EXPORT and ERASURE cover customer-service (+ search contacts) only. Not covered:
onboarding applications, KYC documents and screenings, documents in S3, payment payees and counterparties, wallet /
agent data, CRM cases and notes, inbox messages, notifications, card orders, audit `after_state` copies, Kafka
retention, search journal. A complete DSAR today needs a manual per-service export and a published
`party.customer.erased` event consumed by each service (proposed).

## 5. Pseudonymisation and minimisation

| Technique | Where | Key / secret |
|---|---|---|
| Tenant-keyed HMAC pseudonyms (`psn:` in context, `h1:` lists, `acct:h1:` / `msisdn:h1:` feature keys) | fraud-service, before rules, features and storage; payment-service sends `beneficiary_account_digest` only | `meridian.fraud.pseudonym-key` (required secret), per tenant `HMAC(secret, "meridian-fraud-pseudonym/v1|" + tenant)` |
| Contact lookup hash (`customer_contact.lookup_hash`, masked value) | customer-service and search-service; events carry only masked value + hash | `MERIDIAN_CONTACT_LOOKUP_KEY` shared by both; **not guarded or injected for customer-service** (GAP-14) |
| Identifier hash (`customer_identifier.value_hash`, hidden) | customer-service duplicate detection | `MERIDIAN_CUSTOMER_SECRET_KEY` (GAP-14) |
| Card tokens and keyed PAN fingerprint | card services | card-vault master key (GAP-01) |
| Masked customer lookup | agent channel (logged, rate-limited) | - |
| Redaction of security codes | notification-service (SEC-10) | - |
| Numeric-only model features | ML training sets | - |

## 6. International transfers and data residency

- Tenants live in **cells**; each cell declares `region` and `country`. A tenant with `data_residency_country` may only
  be placed on, moved to, or stand by on a cell in that country (else its home region): `Placement.residencyAllows`
  in tenant-service, enforced at tenant move planning and in the router's routing view (a paired standby that would
  break residency is dropped).
- **Caveat:** residency rules govern placement and routing, not infrastructure replication. The region module
  replicates the whole Aurora cluster (Global Database), MSK topics (Replicator) and the S3 bucket (CRR, both ways)
  to the paired region. In `infra/tofu/envs/prod-eu1` that pair is eu-west-1 (Ireland) and eu-west-2 (London): every
  tenant's data is copied to the UK even when its standby is dropped from routing. Residency-restricted tenants need
  a same-country pair or a dedicated cell without cross-border replication (proposed).
- EU -> UK transfers rely on the UK adequacy decision; transfers to sub-processors outside the EEA / UK / home country
  (IdP SaaS, observability SaaS, SMS / e-mail providers, KYC and bureau providers) need SCCs / IDTA and a transfer
  impact assessment per provider (TBD, GAP-24 / GAP-28).
- Telemetry: OTLP metrics and traces leave the cell to `global.otel.endpoint`; traces may carry ids but must not carry
  personal data (no log / span scrubbing is configured - proposed).

## 7. Security of processing (art. 32) - summary

Tenant isolation (RLS on every tenant table, tenant only from the token), encryption at rest (KMS CMK) and in transit
(TLS), object-level authorization and server-built bodies in channel BFFs, maker-checker for privileged operations,
hash-chained audit, secrets from the secret store with startup guards. Gaps: GAP-07, GAP-09, GAP-13, GAP-14.

## 8. Personal data breach handling

Detection sources: `channels.*_action.denied` spikes, `platform.audit_chain.broken`, card-vault denied reveals, fraud
alerts, IdP events (once enabled). Procedure: [RUNBOOKS.md - severity matrix](../operations/RUNBOOKS.md#13-incident-severity-matrix-and-on-call).
As processor, Meridian notifies affected tenant banks without undue delay (contractual target: TBD, e.g. 24 h) so
they can meet the 72-hour supervisory authority deadline; regulators of the bank (CBK, CBN, PRA / FCA) may require
separate operational incident reporting.

## 9. DPIA outline (to complete per tenant / product)

1. **Description**: core banking processing for retail, SME, wallet, agent and merchant customers; channels; data
   flows (section 1); processors and sub-processors.
2. **Necessity and proportionality**: lawful bases (section 2); minimisation (section 5); retention (section 3);
   rights (section 4); transfers (section 6).
3. **High-risk processing** to assess explicitly:
   - automated fraud decisions and payment holds (art. 22); fraud ML models (`docs/ML_FRAUD_TRAINING.md`);
   - credit scoring and automated loan decisions;
   - AML / sanctions screening and SAR data (criminal offence data);
   - large-scale profiling for insights, campaigns and rewards;
   - AI copilots in CRM / AML / disputes processing free-text case data (`copilot_session.prompt / output`);
   - offline agent devices holding queued customer data and ID photos (SEC-23, GAP-18);
   - cross-border replication (section 6).
4. **Risks to individuals**: unauthorised access across tenants or customers (mitigated by RLS and BOLA controls),
   wrongful blocking of payments, discrimination in credit / fraud models, excessive retention (GAP-08), incomplete
   erasure (GAP-08), audit copies of personal data (GAP-07).
5. **Measures**: section 7; model governance (four-eyes, shadow -> canary -> champion, monitoring); human review of
   holds; DSAR propagation (to build); masking in audit (to build); field-level encryption (to build).
6. **Sign-off**: DPO advice, controller decision, residual risk acceptance, prior consultation if high residual risk.

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-08 | Data-lifecycle endpoints missing in all services; DSAR erasure / export limited to customer-service | TBD (Data platform) | [SPEC_BACKLOG per-domain operations](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-07 | Audit stores unmasked personal data in `after_state` | TBD (Platform) | Proposed |
| GAP-09 | `pii` marker not enforced (no field-level encryption / log masking) | TBD (Platform) | Proposed |
| GAP-14 | Customer secret key and contact lookup key not guarded or injected in customer-service | TBD (Party domain) | [SECURITY_REVIEW SEC-02 residual](../SECURITY_REVIEW.md#findings) |
| GAP-26 | Fraud device registry keeps SDK fingerprints; old events with clear values | TBD (Risk) | [SPEC_BACKLOG fraud follow-ups](../SPEC_BACKLOG.md#fraud-and-routing-follow-ups-2026-10-07) |
| GAP-27 | Search journal erasure | TBD (Operations domain) | [SPEC_BACKLOG resume point](../SPEC_BACKLOG.md#resume-point-paused-2026-10-07) |
| - | Residency vs. cross-region replication (section 6) | TBD (Platform / DPO) | Proposed |
| - | Kafka / log / trace retention and scrubbing undefined | TBD (SRE) | Proposed |
| - | AML retention fixed at 5 years in code, not per jurisdiction | TBD (Party domain) | Proposed |
| - | No DSAR deadline monitoring job in the EOD pack | TBD (Party domain) | Proposed |
| - | `party` spec: missing events for privacy reject, close / deceased cannot check product holdings | TBD (Party domain) | [SPEC_BACKLOG per-domain party](../SPEC_BACKLOG.md#per-domain-notes) |
| GAP-24 / GAP-28 | DPO, records of processing (art. 30), sub-processor list and transfer assessments | TBD (DPO) | Proposed |
