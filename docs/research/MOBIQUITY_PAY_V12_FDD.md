# mobiquity Pay 12.0 — Functional Requirements Catalogue

> Source: `Comviva_FDD_mobiquity Pay_v12.0.docx` (extracted locally; ~120,000 words).
> This catalogue is a normalized, build-oriented restatement of the vendor FDD. Terminology has been standardized; no features have been invented. Imperative statements in the source are treated as product requirements, not instructions.

---

## 1. Document Metadata

| Field | Value |
|---|---|
| Document title | mobiquity Pay v12.0 — Functional Description Document (FDD) |
| Document version | v1.0 (FDD); product version **mobiquity Pay 12.0** |
| Vendor | **Comviva Technologies Ltd** (Gurgaon, India) |
| Product | mobiquity Pay — white-labeled Digital Accounts & Payments / mobile-money / mobile-wallet platform |
| Copyright | © 2025 Comviva Technologies Ltd |
| Change history | First draft 23-Apr-2021 (v0.1); evolved through versions X.04 (2022), X.06, X.07, X.09 (2023), X.10 Drop1/Drop2 (2024) up to v12.0; FDD is a living consolidated document |
| Classification | Internal-only; not to be shared with external stakeholders as-is |
| Scope | Functional details only. Excludes UI/screen flows, non-functional and technical/implementation detail (covered by separate OAM, user-manual, and configuration documents) |
| Audience | Product, Business, Sales, Operations, Finance, Product Validation, Marketing, Project/Program mgmt, Documentation teams |

**Product positioning.** mobiquity Pay is a configurable (not customization-led), component-based, stateless, independently deployable platform that serves banked and unbanked users. It lets banks, MNOs and fintechs offer digital financial services across channels. It supports P2P, P2B/B2P, B2A/A2B and bulk payment flows, multiple mobile accounts per user (electronic replicas of physical accounts funded by cash-in at retailers or bank transfer), and integrates with third-party systems via APIs.

---

## 2. Executive Overview

mobiquity Pay 12.0 is an end-to-end mobile-money/wallet core covering: multi-workspace user management (Administrators, Business, Customer) with KYC-tiered onboarding (regulatory profiles), multi-account "account groups", authorized users, and flexible access identifiers / payment handles / notification endpoints; a rule-based Pricing Engine (service charge, commission, three tax types, cashback, discount/waive, milestone & telescopic charging, referral/joining/registration bonuses, commission sharing & hierarchy routing); limits and velocity via two-tier Transfer Control Profiles (cumulative/customer-level and instrument/grade-level) plus Transfer Rules; e-money ("stock") management across factory system accounts; a full catalogue of financial services — stock creation/withdrawal/transfer/allocation/reimbursement, cash-in/cash-out (incl. unregistered, passcode, agent and ATM cash-out), P2P (registered, on-the-fly/unregistered, reversal, partial recovery), merchant payment (QR, online/offline, refund), bill payment (auto, offline, bulk), airtime/data recharge, bulk salary/enterprise disbursements, B2B transfer-stock / inverse-B2B recall, auto-sweep, standing instructions, own-account (intra/inter-currency) transfers, fund-and-pay, international money transfer, bank↔wallet (B2A/A2B), card-to-account, bank balance/mini-statement; External Transaction Management (Order & Pay) with payment/fulfilment segregation and ambiguous-case settlement; transaction correction/reversal/rollback with dispute tracking; bulk operations with throttling and multi-threading; multi-tenant operation with data isolation; notification management (SMS/email/FCM push/USSD/URL, inbox, priority); referral, churn, savings-circle (chama), banner management; a self-service USSD Menu Manager; AI-based e-float optimizer and conversational (LLM) analytics; monitoring/alerting (Kibana/Grafana); an event data pipeline; and a reporting suite with schedulers (email + SFTP) and a business dashboard. It supports multi-currency, multi-language (English/French/Arabic in current scope), maker-checker/workflow governance throughout, and auditability (admin trail + audit trail).

---

## 3. Platform Foundations & Core Concepts

### 3.1 Actors & Workspaces
Users are grouped into three **workspaces**, each with **categories** (roles) governed by **category profiles** (field/UI/validation rules) and **authorization profiles**.

- **Administrators Workspace** (internal/back-office, web): **Master Admin** (two pre-shipped maker+checker, cannot be replicated; manage system admins, accounts, cumulative TCP, auth profiles, approvals); **System Admin** (user/account mgmt, float generation, charges/commission/cashback, limits, transfer rules, system configs, approvals, enquiries); **Business Admin** (business hierarchy, biller/enterprise registration, transaction corrections, enquiries); **Customer Care Admin/Executive (CCE)** (enquiries, lock/unlock, suspend/resume, reset passwords/PINs, transaction-correction initiation, reports).
- **Business Workspace** (external, via app/web/API): Primary & Authorized business users. Categories: **Distributors** (multi-level), **Enterprise/Payroll** (bulk disbursement), **Merchants** (head/sub merchant), **Billers**, **Recharge/Top-up Partners**, **Agents/Super-Agents/Sub-Agents/Wholesalers/Retailers**. Responsibilities: customer & employee mgmt, B2B transfer, cash-in/cash-out for customers, bill payment.
- **Customer Workspace**: Primary & Authorized users; features include bank association, wallet funding via bank, bill payments, money transfers, recharge, merchant payments, banking services, international transfers. Self or assisted registration.

### 3.2 System Accounts (factory + business-need)
Factory/system account ledger with fixed codes — **Trust (IND04, liability, negative balance, per-bank-ID)**, **Capital/stock (IND01)**, **Suspense (IND02, unregistered holding)**, **Income (IND03, service charges)**, **Expense (IND03B, commissions)**, **Unclaimed Funds (IND06)**, **Rewards (IND08, cashback/referral)**, **Churn (IND10)**, **Churn Suspense (IND11)**, **Intermediate (IND12, bulk)**, **Interest (IND23)**, **Tax accounts — transaction (INDTAX01), income/service-charge (INDTAX02), expense/commission (INDTAX03)**. Plus per-entity accounts: business-user normal+commission, merchant normal+commission, biller, customer normal+salary, bank accounts (trust/non-trust/pool). IND04 non-trust account pre-funded from IND04 trust for B2A/A2B.

### 3.3 Access Channels
Web (admin/business/consumer portals), mobile apps (customer + business, Android & iOS, SDUI), USSD (+ STK via Menu Manager), API (external trusted systems). Agnostic amount-format/locale and configurable Transaction-ID generation (numeric or alphanumeric, length 11–22, configurable prefix/node/separator/sequence reset).

### 3.4 Transaction Lifecycle & Integrity
Statuses: Transaction Initiated (TI), Pending (TP), Success (TS), Failed (TF). Transaction Sequence Management (sequential EDRs, daily reset, DB-driven, stateful-pod uniqueness, SOX-oriented audit). Two-factor configuration (PIN and/or OTP) per order; auth-less transaction config for trusted systems; incorrect-PIN handling (pause vs fail, per bearer/service); IBAN-based transactions; transaction validation (dry-run preview of outcome/charges).

---

## 4. User Management & Onboarding (UMS)

**Purpose.** Unified lifecycle (onboarding, modification, deletion), operations (PIN/password reset, suspend/resume, lock/unlock), and security (authentication, channel assignment, credentials, KYC). Mobile number not mandatory; multiple access identifiers (mobile/email/user-ID). Introduces Primary Users (owners), Authorized Users (operators), Account Groups, Accounts, payment handles, notification endpoints.

**Profiles.** Category Profile (UI/validation rule engine), Security Profile (default per workspace or user-defined: PIN/password complexity, sessions, day/time whitelisting, password-history N, expiry), Marketing Profile (Domain/Category/Currency/Account Types/Grade/instrument-TCP — mandatory one account group; drives account creation), Regulatory Profile (**KYC tiers** e.g. No-KYC, Full-KYC — defines cumulative limits; per central-bank regulation).

**Registration.** Self (minimum KYC = name+primary KYC ID, or full KYC with doc upload) and Assisted (maker-checker, admin or parent business user on behalf of child; enterprise + at least one super-admin employee; biller registration). Single- or two-step. Access identifier unique per workspace per type; wallet-number uniqueness enforced. Authorized-user addition (inherits primary's auth profile, KYC & cumulative TCP; PIN/password shared via SMS/email).

**KYC Management.** Configurable mandatory documents per user type; primary-KYC marking; preview (no download); approver KYC check at registration; grace period for expiring/expired KYC; **KYC Upgrade** (before/after expiry, workflow-compliant, retention of old docs, secondary KYC, same KYC ID across workspaces, multi-user-per-KYC handling).

**User Status Management.** Statuses: Activation Pending (AP), Active (A), Inactive (I), Dormant (D), Suspended (S), Deleted/Churned (C). Configurable transition periods (per category), scheduler-driven auto-transitions + manual (API, auth-profile-driven). Credit-transactions-as-activity toggle; excluded services; primary↔authorized status propagation rules; auto-churn on prolonged dormancy. Status-change notifications (before/after, reminders).

**SIM Swap Detection.** On GSM-detected swap, configurable cooling period extends status durations and restricts login/PIN-reset/account use by swap type (upgrade/replacement/fraud); requires third-party SIM-Swap API.

**Blacklist Management.** Bulk CSV upload (maker-checker) of regulator/government lists; blocks registration/modification matched on name+DOB+KYC ID/number+user ID; dashboard of upload history/active lists.

**Other UMS features.** My Activity Management (draft/submitted/rejected/intermediate/expired; withdraw; workflow expiry). Transaction Enquiry & Reversal (by Transaction ID / External Reference ID; AMC & BMC; role-scoped; admin/self/receiver reversal for cash-in, B2B, inverse-B2B). Session Management (device list, force-logout, trusted-device binding, location/IP). Authentication Management (PIN or password; multi-identifier→single or per-identifier/per-channel/per-workspace auth factor; OTP step-up for sensitive changes; SSO/SAML via IdP — upcoming; password complexity/expiry; per-identifier lockout). Access Identifier Management (add/modify/remove self/assisted, OTP-verified, one per type, cannot remove all; mobile/email types restricted). Payment Handle Management (mobile/email/user-ID/system user-code as handle; default per currency; IBAN/NUBAN generation for individual accounts via extensibility). Notification Template Management (per service/channel/party/language, char limits, workflow-driven). Data Redaction Configuration (PII/SPII masking by role/auth-profile via RESPONSE_FIELD attribute; numeric→NULL, non-numeric→asterisk patterns; `is.redaction.enabled`). Credentials management, share transaction receipt, search user, view/modify user details, suspend/resume, view account statements, account info, lock/unlock accounts, bank info. Business Management Console (BMC) tasks on self/employee/hierarchy/other users; auth-profile creation with limited rights; common business employer account. Administrator Management Console (AMC) equivalent admin tasks.

**Trusted Systems.** Registration of external systems (client ID + client secret, system token), notification-URL config, service association (financial/non-financial), secret regeneration, block-notification config; all workflow/auth-profile compliant.

---

## 5. Authorization, Approval & Workflow

- **Authorization Management.** Roles (atomic privileges, whitelisted per workspace/category, install-time), Modules (logical role groupings), Attributes (micro-controls: Users Allowed, Channels Allowed, Operator Account List, Bank Lists, Access Type FULL/USER_SPECIFIC). Authorization Profiles (default by integrator / user-defined; auto-assigned on self-registration). Assistant Authorization Profiles (delegated sub-profiles with ≤ parent rights). Profile create/modify/delete workflow-driven with checker.
- **Approval Management.** States (Draft, Cancelled, Submitted, Completed, Rejected); actions (Save, Resume, Withdraw, Cancel, Submit, Approve, Reject); single-view approvals; request types (Admin Transactions, Modification, Addition, Reversal); configurable approval levels per request type; level-specific approver rights; multi-select approve/reject role (MULTI_APPROVE_OR_REJECT); configurable dashboard columns; workflow expiry.
- **Workflow Management.** Admin-defined workflows per service with transaction conditions (payer/payee type, initiator, instrument, amount brackets), priority ordering, single-step or multi-level (1–5 levels, default 2); bulk-vs-single differentiation; amount-based approval for bulk (cumulative file amount); business & enterprise approval matrices; multi-level approval for financial transactions (internal amount fetch for Churn Settlement / Txn Correction / Ambiguous); currency-specific priority; workflow notifications to next-level approvers (configurable). Approvers must be same domain.
- **Audit Trail Management.** Admin Trail (all financial + non-financial admin actions incl. login/logout and system-token admin APIs) and Audit Trail (all financial transactions via user/system tokens).

---

## 6. Hierarchy, Domain/Category & Grades

- **Hierarchy.** Business hierarchy links controlling fund transfer and hierarchical commission; domain/category driven; downstream-only user creation; single + bulk hierarchy movement (same-domain, parent one level up, balance-transfer rules, child reassignment thresholds, workflow-compliant, notifications).
- **Domain & Category Management.** Create/manage distribution hierarchies (domain name/code, categories, owner category, grades, service preferences as payer/payee/initiator for financial & non-financial services per bearer); unique codes; one category per domain per approval; business-workspace only; no deletion from UI.
- **Grade Management (user profiles/segments).** Multiple grades per domain-category; privileges, fees, commission, service availability by grade; special **Un-registered Grade** (factory, auto-assigned to unregistered MSISDN; used for cash-in-others, P2P-to-unregistered). Standard profile list (Operator, Distributor Gold/Silver, Retailer, Wholesaler, Super-Agent/Sub-Agent, Head Merchant/Merchant, Top-up/Biller partners, Payroll/Enterprise, Customer Gold/Silver).

---

## 7. Account & Account Group Management

- **Account Group Management.** Marketing-profile-driven account groups (default + additional; configurable count by primary KYC type/workspace); nicknames; default-per-currency; link/delink authorized users (account-group or account level, access-identifier based); closure (no funds/pending, move balance to churn); bulk deletion (maker-checker, default cannot be deleted); account-closure settlement (cash/cheque-DD/transfer-to-account, scheduler to churn-suspense).
- **Account Management.** Pre-configured master accounts + new account types (normal), unique names, 2-digit IDs, service & currency association, modify services, delete (zero balance), rollback routed to default.
- **High Transacting Wallets.** Per-account config: Overdraft Allowed, Balance-Check Required, Balance-Cache Required — for high-TPS accounts; applied to system/bank/business-user accounts with OOB baseline configs.
- **Bank Account Management.** Bank master (trust/non-trust/partner/non-partner, pool account, routing of non-partner via trust); add/modify/delete; currency linking; service association; bank-account linking/delinking for business users (offline/online modes, API); add bank account for customers (assisted); max-bank-per-user limit; service-based bank list fetch. Default Bank Account Management (auto-default per currency, backend/API only). Default Payment Instrument Management (default outgoing instrument per currency).

---

## 8. Limits, Velocity & Transfer Rules

- **Transfer Control Profile (TCP).** Two tiers: **Customer/Cumulative-level TCP** (per currency/domain/category/regulatory-profile; cumulative KYC-ID counters across all account groups; daily/weekly/monthly/yearly debit & credit count+amount, max balance) ranked Rank-1; **Instrument/Grade-level TCP** (per payment-instrument+grade+service; per-bearer and all-bearer count/amount, per-transaction limits, min remaining balance, max balance, min/max txn amount, max % transfer) ranked Rank-2 (subset of Rank-1). Maker-checker; TCP rules RULE1/RULE2/RULE3 (all-service-all-bearer → per-service-per-bearer); yearly-enable configs; KYC/entity-level cumulative aggregation for selected services & categories; service-limit disablement (max limit / max balance per payer/payee).
- **Transfer Rule Management.** Governs allowed transfers between sender/receiver by currency/domain/category/grade/instrument/account-type/bank for a service; add/modify/delete/enable-disable/view; hierarchy vs outside-hierarchy transfer config for stock transfer/recall; transfer amount validation (min/max/multiple); maker-checker.

---

## 9. Pricing Engine

**Purpose.** Highly configurable rule-based engine computing **service charge, commission, transaction tax (3 types), cashback, discount/waive, bonus/reward and interest** for financial & non-financial services, across bearer/time-of-day/day-of-week/party and other conditions; promotional pricing.

**Structure.** Policies (Service Charge: charge/waive-discount/tax rules; Commission: commission/tax rules) → ranked rules → conditions (groups/subgroups with AND/OR; vocabulary: sender/receiver role+grade, SVA type, bearer, transaction time, day of week, party/parent/owner, country code, payment instrument, bank name, remote transaction type onsite/offsite/not-classified, cashback service, service/daily/weekly/monthly count, non-financial service, transaction amount; 10 generic custom-condition fields) → charge statements (who pays / whom to pay, account types, waived party, slabbed basis, tax statements). Pre-conditions for commission (hierarchy payouts).

**Pricing mechanics.** Flat / slabbed / fixed+percentage (sum/min/max); inclusive vs exclusive of transaction amount (with formulas); **telescopic charging** (progressive across slabs, instrument- or cumulative-level, debit/credit); service-level vs rule-level min/max override; multiple taxation (luxury/platform tax); save drafts (multi-user), approval of pricing rules (per-change accept/reject, resolve rejected), clone rules, search rules, pricing calculator, previous-version view; roles: Initiator/Approver/Validator.

**Specialized charging/commission.**
- Service charge rules on non-financial services (balance enquiry, change PIN, statement, etc.; discount/tax on them; self-mode only).
- Commission Payment (bonus from expense account IND03B to business user).
- **Joining Commission** (business user rewarded for registering + first cash-in + first debit of new customer; split payout).
- **Customer Registration Bonus** on PIN set (flat, default account group, factory-config).
- **Additional Commission for Cash-in to Inactive Customers** (bonus to agent/parent/owner; immediate / after-first-debit / after-duration).
- **Commission Sharing** (Merchant→Aggregator→Service-Provider revenue split; CP/CR entries; full reversal rollback).
- **Commission Rule via Category-Based Hierarchy Mapping** (dynamic payer/payee/intermediary resolution, 2–4 levels, validation flag).
- **Onsite/Offsite Commission** (cell-ID/LAC-ID detection of remote cash-in/cash-out; block offsite/not-classified by config).
- **Cashback Commission Policy** (registration, add-bank, standing instructions, auto-bill-pay enablement; from Rewards IND08; flat/slab; no limits).
- **Milestone-Based Charging** (nth-transaction pricing, time-bound daily/weekly/monthly, per service).
- **Discount Policy** (percentage/amount on transaction value; net-payable adjustment).

---

## 10. Stock / e-Money ("Float") Management — Administrative Transactions

**Purpose.** Manage e-money inventory at operator and business-user levels. Covers Stock Creation, Deletion/Withdrawal, Transfer, Allocation, Reimbursement, Balance Enquiry.

- **Stock Creation** (trust bank → capital IND01, or direct to system accounts; single-step from external system; auth-profile controlled; pre-funding of IND04 non-trust from IND04 trust).
- **Stock Withdrawal / Deallocation** (user or system account → IND04 trust, bypassing capital; single + bulk; amount frozen on initiation; forced withdrawal bypassing user/wallet status; routing of non-trust/non-partner via trust; LTA on payer only).
- **Stock Transfer between System Accounts** (any system/bank accounts, transfer rules, workflow).
- **Stock Reimbursement** (e-money → physical cash; agent/biller/merchant/customer/operator; debit entity → credit IND01).
- **Allocation (O2C / Owner-to-Business)** (capital IND01 → business user; 2-step via trust→capital→user; cheque/DD/cash instruments).
- **Auto Deallocation** (scheduled sweeps from user/system/non-trust to IND04 trust; maker-checker; min residual balance precedence).
- **Rollback for Stock Withdrawal** (external-system initiated reversal, single-step, forced).
- **Stock Balance Enquiry** & **Bank Balance Enquiry** (per currency/account/bank).
- **Auto Debit Instructions Setup** (admin-assisted for transfer-stock/recall-stock/own-account; frequency daily/weekly/monthly/yearly; fixed/percentage/full amount; OTP optional; end-date defaults; TCP/transfer-rule/pricing applied).
- **Freeze/Unfreeze Funds** (temporary hold / pre-authorization; expiry by service; configurable over-freeze limit).
- **Commission Related**: Commission Payment, Commission Disbursement (commission account → main, via bulk, maker-checker), Accrued Commission Withdrawal (to IND03), Commission Disbursement Records Download.
- **Unclaimed Money Settlement** (suspense IND02 → unclaimed IND06 after n days → capital IND01 or income IND03).
- **Administrative Transactions Fund Flow** (documented debit/credit matrix for stock creation, O2C, transfer, commission/churn settlement, cash-in-others expiry, unclaimed processing, reimbursement, withdrawal, B2A/A2B, bank-to-bank, allocation/deallocation).

---

## 11. Financial Services Catalogue

### 11.1 Business Transactions
- **Transfer Stock (B2B)** — e-money between two business users; transfer rules + service charge + TCP; child-user search; configurable workflow for authorized-user-initiated B2B (multi-level by amount).
- **Recall Stock (Inverse B2B)** — pull funds from downstream business user; configurable 2-step approval; web/app/USSD.
- **Cash-In (Deposit Cash)** — business user debits own account, credits customer; both customers & merchants can cash-in from agents.
- **Cash-In (Others)** — send to unregistered user via agent; funds in suspense IND02; passcode generation (depositor/receiver/both, same/different, network-aware config); claim on registration or agent cash-out; receipt with decrypted passcode.
- **Cash-Out (Withdraw Cash)** — customer-initiated (single step) or business-user-initiated (2-step with customer PIN confirmation); agent provides cash.
- **Agent Pass-Code Cash-out** — unregistered-transfer recipient cashes out at agent via passcode (debit IND02 → agent).
- **ATM Cash-Out** — OTP passcode at registered ATM; debit on passcode validation; auto-rollback if ATM fails to dispense.
- **Merchant Refund / Partial Refund** — full or multiple partial refunds within configurable window; account-instrument only; refund status visibility (P2P, merchant pay, bill pay, B2B/pay-business).
- **Auto-sweep** — business-user scheduled sweep of balance to linked bank account (daily/weekly/monthly; min residual; pause/resume/edit; PIN/OTP).
- **Bulk Salary Payments (Enterprise Payment)** — single-debit multi-credit to customers/business users/unregistered; CSV upload or server drop; 2-step mandatory; pre-validation; auto-retry/auto-rollback; intermediate account IND12; unregistered via suspense IND02 + passcode.
- **Bulk Bill Payments** — enterprise bulk bill payment (template with currency/amount/account/biller/bill number); 2-step.
- **Transfer of Funds Between Unregistered Users** — assisted via agent; passcode-based claim at receiver agent.
- **Low Balance Alert** — thresholds on system & business-user accounts (by user or domain/category/grade); real-time or scheduled; SMS/email/push/inbox; approval workflow.

### 11.2 Customer Transactions
- **Domestic Money Transfer (P2P)** — to registered (immediate credit to default/handle account) or **P2P On-the-Fly / Unregistered** (suspense + passcode; Method 1 register-to-claim, Method 2 agent cash-out; rollback scheduler on expiry; configurable SC/tax/commission reversal).
- **P2P Reversal** — sender-initiated reversal request (receiver approve/reject or CCE); configurable window/expiry.
- **Partial Recovery in P2P Reversal** — recover ≤ original amount; freeze recoverable amount; receiver/admin approval.
- **Send/Receive via Alias (payment handle)**; **Request Money** (with retry notifications, accept/reject).
- **Mobile Recharge** — self/others, prepaid top-up via ETM; operator & plan by currency; sync/async; recharge operator credited.
- **Merchant Payment** — single-step (customer) / two-step (merchant-initiated); **QR static & dynamic**; online (merchant API + fulfilment) & offline; card (debit/credit) via payment gateway; timeout handling.
- **Bill Payment** — ad-hoc & recurring; dynamic biller/category/logo fetch. **Auto Bill Payments** (authorized recurring pull, max-limit edit, pause/resume/remove, reminders). **Offline Bill Payment** (manual/bulk bill upload, approval, exact-amount, due-date config, reversal).
- **Split Transaction** — split merchant/bill payments among up to 50 mobiquity users (equal/custom proportion, remarks mandatory, approve/reject, status tracking).
- **Standing Instructions** — recurring P2P & merchant payment (defined period/amount; pause/resume/edit/delete; reminders; pricing applies).
- **Funds Transfer Between Own Accounts** — Intra-currency (same currency, any group) and Inter-currency (forex via config or third party; UI maker-checker or integration; single transfer ID; TCP config toggle).
- **Download & Share Receipt** (PDF, order/non-order, decrypted passcode).
- **Fund and Pay** — real-time top-up (debit/credit card, bank) during insufficient-balance payment; two order IDs.
- **Recent and Favorites** — per channel/service favorites and recents.
- **International Money Transfer (IMT)** — cross-border via IMT hub merchant (2-step order mgmt, EIG fulfilment); domain/category/grade per partner/destination; generic hub APIs; prefund via stock transfer; identical-transaction check.

### 11.3 Workspace-Agnostic / Mobile Banking Services
- **User-Initiated Bank Account Registration / De-Registration** (bank verifies; non-financial, chargeable).
- **Bank to Account (B2A)** — customer/business-user-initiated and bank-initiated (bank admin credits mobiquity account); non-trust banks; TCP/transfer-rule/charges apply; manual reconciliation on no-response.
- **Account to Bank (A2B)** — wallet → linked bank; debit wallet credit pool, fulfilment to bank; ambiguous on timeout; auto-rollback on failure; ~1000ms expected response.
- **Bank Account Balance Enquiry** & **Mini Statement** (non-financial; linked accounts only; per-bank availability).
- **Card to Account (C2A)** — register card, load wallet (own/other/business accounts) via debit/credit card through gateway.
- **Enquiry & Operation Services** — User Account Details; Transaction Summary; Favorite Transactions; **Balance Enquiry** (parent/child hierarchy, authorized-user scopes); **Agent Locator** (GPS map, configurable radius); **Account Transaction History** (filters; successful only; enterprise/batch-ID view); **Download/Export Statement** (CSV/PDF, email, duration filters, redaction); Set Security Questions; **Forgot Authentication Factor** (OTP/Q&A; HB vs LB user journeys; admin-assisted; incorrect-attempt counter); **Change Authentication Factor**; **Change Language** (English/French/Arabic); **Identical Transactions** (full/partial-match detection, block/pause, workflow up to 5 levels); **Reconciliation** (EOD stock reconciliation report A-(B+C+D+E), mismatch alarm, CSV/PDF, scheduled/manual email).

---

## 12. External Transaction Management (ETM / Order & Pay)

**Purpose.** Segregate accounting / payment / provisioning for a business transaction, maintaining three event states separately; pay from account, credit/debit card; enables order-based flows.

- **Orders / Transactions / Payment / Fulfilment / Card Payment Service / Instrument Vault** (PCI-compliant secure card storage).
- **Financial services via ETM**: Recharge self/others, Card-to-Account / Card-to-Other-Account, Bank-to-Account, Account-to-Bank, Pay-using-Card (PCI service), Merchant Payment (online/offline), **Guest Merchant Payment** (non-registered, card not stored), Order Reversal by admin, Bill Payment, Auto Bill Payments.
- **Non-financial operations**: get order details by order ID; historical orders by user identifier; transactions by order ID; 360° transaction view.
- **Ambiguous Case Handling**: at-transaction, bulk, and manual. **Auto Ambiguous** settlement of Payments / Provisioning / Refunds (configurable retries N, wait W, rollback vs roll-forward, PG heartbeat, auto-refund toggle). **Bulk Ambiguous Case Handling** (payment/fulfilment/reversal ambiguous; CSV phase/party/status; approval; scheduler). **Alert on Ambiguous Transactions** (green/yellow/red thresholds per 3rd-party/day; email group).
- **Cancel Order** (reversal of successful orders; configurable max 20 days; B2A/A2B/bill/auto-bill/recharge/card-to-account).

---

## 13. Transaction Correction, Reversal, Rollback & Dispute

- **Transaction Correction** — reverse erroneous transaction by ID (amount, service charge, commission, tax; LTA reversal); workflow single/two-step (sender→receiver+admin approval branch); configurable timeout; rollback SC/commission with tax; forced rollback/reversal bypassing status.
- **Transaction Reversal Service-Level Preferences** — per-service or ALL-services reversal prefs (limits, SC+tax, commission+tax for parties, transaction tax); override option; bulk template.
- **Reverse Transaction** — admin-initiated for successful orders/transactions; freeze receiver amount; approval timeout (default 20 days); rejection handling; reinitiation of rejected correction (new ID).
- **Rollback of Transaction** — automatic rollback of failed transactions (e.g., ATM cash-out failure); reverses SC/commission/TCP.
- **Dispute Tracker** — track/comment on disputed corrections/reversals; statuses (pending/successful/failed/rejected/expired); dispute callback feedback; configurable columns; history.

---

## 14. Bulk Operations

**Purpose.** Templatized web-based bulk processing of financial & non-financial services (SFM-flow driven, role-based BULK_SERVICE_INITIATE/APPROVE/VIEW).

- Supported bulk services: user registration/modification/deletion, hierarchy management, lock/unlock, suspend/resume, PIN/password reset, C2C transfer/withdraw, O2C transfer/withdraw, enterprise salary disbursement, commission disbursement/withdraw, cash-in/cash-in-others/cash-out, ambiguous settlement, bill payment, recharge, transaction correction, blacklist, merchant payment, bulk notification, account-group/account deletion, enterprise user/employee creation/modification, stock creation/withdrawal, add account.
- Job creation (download template/sample, upload, verify, confirm), batch ID, status lifecycle (Uploading→Uploaded→Submitted→Processing→Completed/Rejected/Cancelled/Expired), enquiry, execution (processor step), **throttling** (peak/non-peak TPS split, multi-batch distribution), **multi-threading** (high/low priority TPS allocation, dynamic reallocation). Email notifications; multilingual/RTL/UTF-8; configurable downloadable fields; SFM skip-checks in bulk pause flow.

---

## 15. Multi-Tenant Management

Logically isolated tenants (own configs/users/branding/currency/language/data). Roles: Provider Admin Maker (PAM), Provider Admin Checker (PAC, single, backend-provisioned), Individual Tenant Administrator (ITA, per-tenant super-admin). Tenant lifecycle (add/view/edit/suspend/resume/delete, single-level PAC approval); ITA assignment; `X-TenantID` header on all requests; sub-domain-per-tenant or common-login dropdown; tenant-isolated reporting/dashboards/branding; deletion blocked if active users/pending transactions.

---

## 16. Notifications, Engagement & Community

- **Notification Management** — channels SMS / Email / **FCM Push** / URL (USSD push / 3rd-party). Endpoint addition at user-level & account-group level (group-level precedence); authorized-user endpoint linkage; modify/remove (OTP-verified); advanced notifications for scheduled payments (yearly/monthly/weekly reminders); **Notification Inbox** (central, interactable, purge policy 3mo–1yr); **Priority-based Notifications** (OTP > DEBIT > CREDIT > ORDER FAILURE > Promotional).
- **Referral Management** — unique reusable alphanumeric codes per user (workspace-scoped, no cross-domain); capture at registration; bonus (pricing-engine-evaluated) to referrer & referee default account groups (primary users only); fraud monitoring; retry on failure; events logged; visible in statements.
- **Churn Management** — Churn Account (IND10) attributes; Churn Settlement (search by identifier; settle to active user account-group/payment-ID or complete reimbursement to bank from IND01/IND04 per currency-account mapping; assisted only; workflow approval); Churn for business user with hierarchy (2-step deletion, zero balance, no child/pending transactions, hierarchy reassignment).
- **Banner Management** — advertisement banners for customer/business apps; 3-step create (details/preview/confirm); multi-language per set; frequency (display-always or scheduled); default-banner rules; reorder; approval for add/modify/delete.
- **Savings Circle (chama / group savings)** — create (self via app or admin-assisted; unique 5-digit code; chairperson; ≤100 circles/customer); invite members (registered/active, same currency, social-media share, bulk ≤10); join (circle code, official approval); leave (chairperson must reassign); transactions (deposit direct, withdrawal via request + official approval — 2-secretary or 50% config; SC/tax apply; cancellation); approvals by chairperson/secretary (withdrawal/join/removal).

---

## 17. USSD Menu Manager

Self-service, code-free USSD menu design/governance (eliminates L3 dependency). Roles: USSD Menu Maker & USSD Menu Checker (maker-checker with applicable date). Multi-level menus; context-based resolution by domain/category/grade/hierarchy; static + dynamic menus; versioning/rollback/scheduled activation. Node types: Start, Menu, Dynamic Menu, Input, Composite Code, Pattern (input validation), Action (API/service call), End. Flow controls: API template management, JSON export (business-logic-only + full graph) / import; menu testing & simulation; flow validation & optimization; flow metrics/complexity. Approval of changes; **Live Flows** (deployed-flow management, full traceability, schedule/pause/resume); handling of long codes; multi-language.

---

## 18. Intelligence, Analytics & Observability

- **AI-Based e-Float Optimizer** — predicts future float for business users, system & bank accounts (historical/behavioral); user-level / domain-category-grade / system-account configs; daily/weekly prediction (≥3 data points); notifications; anomaly alerts; e-Float Prediction Dashboard (net-position ranges, debit/credit-heavy/mixed classification, historical validation); reports (E-float Prediction, System Account Prediction, Historical).
- **Conversational Analytics** — LLM-based natural-language querying of enterprise data (user mgmt, transactions, wallet/account, notifications) → dynamic SQL via Python module → charts/tables/text; tenant-isolated; role-based; audit-logged.
- **Monitoring & Alerting (M&AS)** — Kibana (Elasticsearch log/APM/metrics visualization) and Grafana (time-series analytics, dashboards, Prometheus); observability (logs, metrics, uptime, traces), real-time alerting (SMS/email/Slack), reporting, anomaly/forecast.
- **Event Data Pipeline (EDP)** — real-time event capture/processing; config-driven, versioned, low-code, async via SFM orchestration; agreed contract schemas; field-removal config for external sharing. OOB events: order-and-pay (success/paused/failed), transaction details, subscriber registration/de-registration, KYC upgrade/modification, bank association/de-association, user registration/deletion/modification, account-group add/modify/delete, payment-handle add/modify/delete, notification-endpoint add/modify/delete, add account, user account modification, user bar/blacklist, churn user, authentication change, churn settlement, admin audit trail, user hierarchy change.

---

## 19. Reporting & Dashboards

- **Reporting Suite** — pre-configured reports with filters (Domain, Transaction ID, Access Identifier); synchronous (Generate) & asynchronous (View) reports; cron reports; XLSX default (CSV/PDF configurable); live (real-time via Kafka Connect + materialized-view refresh) vs non-live (hourly). ~25 OOB reports incl. User Transactions, Service Charge, Commission, Customer Registration, Customer Blocked Access, Suspended Customers, Deleted Users, Reimbursement, Closely Watched Numbers, User Registration KYC, Bank Account Statement, Admin Trail, Audit Trail, Wallet Balance, Daily Wallet Balance Reconciliation, Stock Management, Fulfilment Reconciliation, Distribution Hierarchy O2C, Paid Bill, E-float Prediction (x4), Bulk Payments Transaction, User Modification.
- **Email Scheduler** — automated report delivery (daily/weekly/monthly; attachment or secure OTP-protected download link; whitelisted domains; unique scheduler ID; active/suspended).
- **File Upload Scheduler** — SFTP delivery to client/partner servers (immediate or scheduled; PDF/Excel/CSV; predefined servers; secure transfer).
- **Business Dashboard** — BMC KPI dashboard (role/hierarchy scoped; T-1 default; Float In/Out, Commission Earned, Transaction Performance; My Hierarchy / Self tabs; filters by category/user/reportee; chart types; MTD vs LMTD; export JPG/CSV/XLSX; access control USER_SPECIFIC/FULL; tenant-specific; includes ambiguous/reversal).

---

## 20. Platform Operations & Configuration

- **Importing/Exporting System Configurations** — Utility1 (backend CSV migration from legacy versions; validation, duplicate handling; transfer rules, TCP, pricing, domain/category/grade, marketing profile) and Utility2 (AMC GUI; UAT↔Production replication; key-attribute match; update/insert/remove reconciliation; encrypted binary + JSON export; import request IDs; status lifecycle; auth-profile-controlled, audit-logged).
- **Software License Management** — License Enforcement Engine (LEE); validity + buffer period; runtime validation; deployment suspension on expiry; encrypted+signed license (platform public key / Comviva private key); IP-whitelisted generation; expiry notifications (30/7/1 days); tamper/copy/reuse → auto-suspend.
- **Error Messages Management** — numeric error codes, multi-language by initiator/system language, bearer-contextual (system/user/business).
- **Stock/Reconciliation Alarms** — real-time mismatch alerts to configured admin numbers/emails (change-based, not repeated).

---

## Capability Checklist

> One bullet per discrete capability for diffing against an existing platform. Codes are stable: `MQ-<MODULE>-<n>`.

### Actors, Workspaces & Access
- MQ-ACTOR-1: Three-workspace model (Administrators, Business, Customer) with categories & category profiles
- MQ-ACTOR-2: Master Admin pair (pre-shipped maker+checker, non-replicable)
- MQ-ACTOR-3: System Admin role with delegable privileges
- MQ-ACTOR-4: Business Admin role (hierarchy, biller/enterprise registration, corrections)
- MQ-ACTOR-5: Customer Care Admin/Executive role (support, lock/unlock, resets, corrections)
- MQ-ACTOR-6: Business categories — Distributor (multi-level), Wholesaler, Super-Agent/Sub-Agent, Retailer, Agent
- MQ-ACTOR-7: Merchant category (head + sub merchant hierarchy)
- MQ-ACTOR-8: Biller category
- MQ-ACTOR-9: Recharge/Top-up partner category
- MQ-ACTOR-10: Enterprise/Payroll category (bulk disbursement)
- MQ-ACTOR-11: Primary User (account owner) & Authorized User (account operator) concepts
- MQ-CHAN-1: Web portals (Admin/AMC, Business/BMC, Consumer)
- MQ-CHAN-2: Mobile apps — customer + business, Android & iOS (SDUI)
- MQ-CHAN-3: USSD channel
- MQ-CHAN-4: STK / SIM-toolkit via USSD Menu Manager
- MQ-CHAN-5: API channel for external trusted systems
- MQ-CHAN-6: Configurable locale amount formatting across portals
- MQ-CHAN-7: Configurable Transaction-ID generation (numeric/alphanumeric, length 11–22, prefix/node/separator/sequence)
- MQ-CHAN-8: Global mobile-number prefix (0/00) add/remove configuration

### System Accounts & Ledger
- MQ-ACCT-1: Trust account IND04 (liability, negative balance, per-bank-ID)
- MQ-ACCT-2: Capital/stock account IND01
- MQ-ACCT-3: Suspense account IND02 (unregistered holding)
- MQ-ACCT-4: Income account IND03 (service charges)
- MQ-ACCT-5: Expense account IND03B (commissions)
- MQ-ACCT-6: Unclaimed Funds account IND06
- MQ-ACCT-7: Rewards account IND08 (cashback/referral)
- MQ-ACCT-8: Churn account IND10 & Churn Suspense IND11
- MQ-ACCT-9: Intermediate account IND12 (bulk financial services)
- MQ-ACCT-10: Interest account IND23
- MQ-ACCT-11: Tax accounts — transaction INDTAX01, income/SC INDTAX02, expense/commission INDTAX03
- MQ-ACCT-12: Per-entity accounts (business normal+commission, merchant, biller, customer normal+salary, bank/pool)
- MQ-ACCT-13: IND04 non-trust account for B2A/A2B, pre-funded from IND04 trust

### User Management & Onboarding
- MQ-UMS-1: Unified user lifecycle (onboard/modify/delete)
- MQ-UMS-2: Multiple access identifiers (mobile/email/user-ID), mobile not mandatory
- MQ-UMS-3: Self-registration (minimum-KYC and full-KYC)
- MQ-UMS-4: Assisted registration (maker-checker, admin or parent on behalf)
- MQ-UMS-5: Child business-user registration by parent/primary
- MQ-UMS-6: Enterprise registration (+ mandatory super-admin employee, KYB + employee KYC)
- MQ-UMS-7: Biller registration/modification (online & offline process types)
- MQ-UMS-8: Authorized-user addition (inherits auth profile, KYC, cumulative TCP)
- MQ-UMS-9: Category profiles (field/UI/validation rule engine)
- MQ-UMS-10: Security profiles (default/user-defined; PIN/password complexity, sessions, day/time whitelist, history, expiry)
- MQ-UMS-11: Marketing profiles (domain/category/currency/account-types/grade/instrument-TCP)
- MQ-UMS-12: Regulatory profiles (KYC tiers, e.g. No-KYC/Full-KYC) driving cumulative limits
- MQ-UMS-13: KYC management (configurable docs, primary marking, preview, approver check, grace period, secondary KYC)
- MQ-UMS-14: KYC upgrade (before/after expiry, retention, same KYC across workspaces)
- MQ-UMS-15: User status model AP/A/I/D/S/C with configurable transition periods per category
- MQ-UMS-16: Scheduler-driven auto status transitions + manual API changes
- MQ-UMS-17: Primary↔authorized status propagation & auto-churn rules
- MQ-UMS-18: SIM-swap detection with cooling-period restrictions (3rd-party SIM-swap API)
- MQ-UMS-19: Blacklist management (bulk upload, maker-checker, registration/modification block)
- MQ-UMS-20: My Activity management (draft/submit/withdraw/expire)
- MQ-UMS-21: Transaction Enquiry & Reversal (by Txn ID / External Ref; AMC & BMC; role-scoped)
- MQ-UMS-22: Session management (device list, force-logout, trusted-device binding, location/IP)
- MQ-UMS-23: Authentication — PIN or password login
- MQ-UMS-24: Flexible auth-factor config (multi-identifier→single, per-identifier, per-channel, per-workspace)
- MQ-UMS-25: OTP step-up for sensitive changes (mobile/email change, withdrawal, add money)
- MQ-UMS-26: SSO/SAML via corporate IdP (roadmap)
- MQ-UMS-27: Per-identifier lockout on invalid attempts
- MQ-UMS-28: Access-identifier management (add/modify/remove, OTP, one-per-type, cannot remove all)
- MQ-UMS-29: Payment-handle management (mobile/email/user-ID; default per currency)
- MQ-UMS-30: System user-code as payment handle (configurable prefix/length/charset)
- MQ-UMS-31: IBAN/NUBAN generation for individual accounts (extensibility)
- MQ-UMS-32: Notification endpoint management (user-level & account-group level; group precedence)
- MQ-UMS-33: Notification template management (per service/channel/party/language, char limits)
- MQ-UMS-34: Data redaction configuration (PII/SPII masking by role/auth-profile)
- MQ-UMS-35: Credentials management (view/reset PIN & password, unblock)
- MQ-UMS-36: Share transaction receipt (email, OTP for non-registered address)
- MQ-UMS-37: Search user (mobile/email/user-ID/KYC/name)
- MQ-UMS-38: View & modify user details (personal/contact/KYC/profile; workflow)
- MQ-UMS-39: Suspend/resume user (assisted, workflow, notifications)
- MQ-UMS-40: View/export account statements (AMC/BMC, email, resend notifications/passcodes)
- MQ-UMS-41: Lock/unlock accounts (sender/receiver/both; all/currency/specific; reason enum; workflow)
- MQ-UMS-42: BMC tasks on self/employee/hierarchy/other users
- MQ-UMS-43: Common business employer account
- MQ-UMS-44: Trusted-systems registration (client ID/secret, system token, notification URL, service association, block-notification)
- MQ-UMS-45: Forgot authentication factor (OTP/Q&A; HB vs LB journeys; admin-assisted; attempt counter)
- MQ-UMS-46: Change authentication factor (with 2FA/old-factor validation; expiry reminders)
- MQ-UMS-47: Change language (English/French/Arabic in current scope)
- MQ-UMS-48: Set/modify security questions & answers (2FA-protected)

### Authorization, Approval & Workflow
- MQ-AUTH-1: Roles (atomic privileges, whitelisted per workspace/category)
- MQ-AUTH-2: Modules (logical role groupings)
- MQ-AUTH-3: Attributes (Users Allowed, Channels Allowed, Operator Account List, Bank Lists, Access Type)
- MQ-AUTH-4: Authorization profiles (default + user-defined; auto-assign on self-registration)
- MQ-AUTH-5: Assistant authorization profiles (delegated sub-profiles, ≤ parent rights)
- MQ-APPR-1: Approval states/actions model (Draft/Cancelled/Submitted/Completed/Rejected)
- MQ-APPR-2: Configurable approval levels per request type with level-specific approvers
- MQ-APPR-3: Multi-select approve/reject (MULTI_APPROVE_OR_REJECT role)
- MQ-APPR-4: Configurable approval dashboard (columns, additional-info, date range)
- MQ-WF-1: Workflow management (per-service conditions, priority, 1–5 levels)
- MQ-WF-2: Single vs bulk workflow differentiation (distinct levels/approvers)
- MQ-WF-3: Amount-bracket & cumulative-bulk-amount-based approval routing
- MQ-WF-4: Business & enterprise approval matrices
- MQ-WF-5: Multi-level approval for amount-less financial txns (internal amount fetch)
- MQ-WF-6: Currency-specific workflow priority management
- MQ-WF-7: Workflow notifications to next-level approvers (configurable)
- MQ-WF-8: Workflow expiry (factory config)
- MQ-AUD-1: Admin Trail (all admin financial/non-financial actions incl. login/logout, system-token)
- MQ-AUD-2: Audit Trail (all financial transactions via user/system tokens)

### Hierarchy, Domain/Category & Grades
- MQ-HIER-1: Business hierarchy links (downstream-only user creation)
- MQ-HIER-2: Single business-user hierarchy movement (same-domain, balance/child rules, workflow)
- MQ-HIER-3: Bulk hierarchy movement (admin, logs/status reports)
- MQ-DOM-1: Domain & category management (n-level, service preferences payer/payee/initiator)
- MQ-DOM-2: View category hierarchy
- MQ-GRADE-1: Grade management (multiple grades per domain-category; fee/commission/service by grade)
- MQ-GRADE-2: Un-registered grade (factory, auto-assigned to unregistered MSISDN)

### Account & Bank Management
- MQ-AG-1: Account-group management (marketing-profile-driven, default + additional, nicknames, default-per-currency)
- MQ-AG-2: Link/delink authorized users (group or account level)
- MQ-AG-3: Account-group/account closure (no funds/pending; move balance to churn)
- MQ-AG-4: Bulk deletion of account groups/accounts (maker-checker)
- MQ-AG-5: Account-closure settlement (cash/cheque-DD/transfer; scheduler to churn-suspense)
- MQ-ACM-1: Account management (new account types, service/currency association, delete)
- MQ-ACM-2: High-transacting-wallet config (overdraft/balance-check/balance-cache)
- MQ-BANK-1: Bank master (trust/non-trust/partner/non-partner, pool account, routing)
- MQ-BANK-2: Bank-account linking/delinking for business users (offline/online, API)
- MQ-BANK-3: Add bank account for customers (assisted)
- MQ-BANK-4: Max-bank-per-user limit
- MQ-BANK-5: Service-based bank-list fetch
- MQ-BANK-6: Default bank account per currency (auto)
- MQ-BANK-7: Default payment instrument per currency

### Limits & Transfer Rules
- MQ-TCP-1: Customer/cumulative-level TCP (per currency/domain/category/regulatory-profile; KYC-ID counters; daily/weekly/monthly/yearly debit+credit, max balance)
- MQ-TCP-2: Instrument/grade-level TCP (per-bearer & all-bearer, per-txn, min/max balance, max % transfer)
- MQ-TCP-3: TCP ranking (cumulative Rank-1 superset of instrument Rank-2)
- MQ-TCP-4: TCP rule modes RULE1/RULE2/RULE3 (all-service-all-bearer → per-service-per-bearer)
- MQ-TCP-5: Yearly-limit enable configs
- MQ-TCP-6: KYC/entity-level cumulative aggregation for selected services & categories
- MQ-TCP-7: Service-limit & max-balance disablement (payer/payee)
- MQ-TR-1: Transfer rule management (per service, sender/receiver attributes; enable/disable)
- MQ-TR-2: Hierarchy vs outside-hierarchy transfer config (transfer/recall stock)
- MQ-TR-3: Transfer amount validation (min/max/multiple)

### Pricing Engine
- MQ-PE-1: Rule-based policies (Service Charge, Commission) with ranked rules & conditions
- MQ-PE-2: Condition vocabulary (role/grade, SVA type, bearer, time, day, party/parent/owner, country, instrument, bank, remote type, counts)
- MQ-PE-3: 10 generic custom-condition fields
- MQ-PE-4: Charge statements (who-pays / whom-to-pay, account types, waived party)
- MQ-PE-5: Flat / slabbed / fixed+percentage (sum/min/max) pricing
- MQ-PE-6: Inclusive vs exclusive of transaction amount
- MQ-PE-7: Telescopic charging (instrument or cumulative, debit/credit)
- MQ-PE-8: Three tax types (on service charge, on commission, on transaction amount) + multiple taxation
- MQ-PE-9: Service charge on non-financial services (+ discount/tax)
- MQ-PE-10: Commission payment (bonus from expense account)
- MQ-PE-11: Joining commission (register + first cash-in + first debit; split payout)
- MQ-PE-12: Customer registration bonus on PIN set
- MQ-PE-13: Additional commission for cash-in to inactive customers
- MQ-PE-14: Commission sharing (Merchant→Aggregator→SP; CP/CR entries; full reversal)
- MQ-PE-15: Category-based hierarchy commission routing (2–4 levels)
- MQ-PE-16: Onsite/offsite commission via cell-ID/LAC-ID (block offsite/not-classified config)
- MQ-PE-17: Cashback policy (registration/add-bank/SI/auto-bill-pay; from IND08)
- MQ-PE-18: Milestone-based charging (nth transaction, time-bound)
- MQ-PE-19: Discount policy (net-payable adjustment)
- MQ-PE-20: Save/discard/revert policy drafts (multi-user)
- MQ-PE-21: Approval of pricing rules (per-change accept/reject, resolve rejected)
- MQ-PE-22: Clone rules
- MQ-PE-23: Search rules (name/conditions)
- MQ-PE-24: Pricing calculator (full charge breakup, rule trace, versions)
- MQ-PE-25: Policy versioning & previous-version view
- MQ-PE-26: Roles — Initiator/Approver/Validator
- MQ-PE-27: Interest-rate configuration / interest disbursal (IND23)

### Stock / e-Money (Admin Transactions)
- MQ-STK-1: Stock creation (trust→capital or direct; pre-fund non-trust from trust)
- MQ-STK-2: Stock withdrawal/deallocation (single + bulk; freeze-on-initiation; forced bypass status)
- MQ-STK-3: Stock transfer between system/bank accounts
- MQ-STK-4: Stock reimbursement (e-money → cash)
- MQ-STK-5: Allocation O2C (capital → business user; cheque/DD/cash)
- MQ-STK-6: Auto deallocation (scheduled sweeps to IND04 trust)
- MQ-STK-7: Rollback for stock withdrawal (external-system, single-step, forced)
- MQ-STK-8: Stock balance enquiry
- MQ-STK-9: Bank balance enquiry
- MQ-STK-10: Auto debit instructions setup (transfer/recall stock, own-account; frequency; amount modes)
- MQ-STK-11: Freeze/unfreeze funds (hold/pre-authorization; over-freeze limit)
- MQ-STK-12: Commission payment / disbursement / accrued withdrawal / records download
- MQ-STK-13: Unclaimed money settlement (IND02→IND06→IND01/IND03)
- MQ-STK-14: Administrative fund-flow matrix (documented debit/credit per service)

### Financial Services — Business
- MQ-FIN-1: Transfer Stock B2B (+ configurable workflow for authorized-user-initiated)
- MQ-FIN-2: Recall Stock / Inverse B2B (configurable 2-step)
- MQ-FIN-3: Cash-In / Deposit Cash (to registered customer)
- MQ-FIN-4: Cash-In (Others) to unregistered (suspense + passcode, network-aware config)
- MQ-FIN-5: Cash-Out / Withdraw Cash (customer single-step & business-user two-step)
- MQ-FIN-6: Agent pass-code cash-out
- MQ-FIN-7: ATM cash-out (OTP passcode; auto-rollback on dispense failure)
- MQ-FIN-8: Merchant refund (full)
- MQ-FIN-9: Partial refund (multiple; ≤ order amount; window)
- MQ-FIN-10: Auto-sweep (business user → bank; frequency; min residual)
- MQ-FIN-11: Bulk salary payments / enterprise payment (single-debit multi-credit; 2-step; retry/rollback)
- MQ-FIN-12: Pre-validation for enterprise salary payments
- MQ-FIN-13: Bulk bill payments (enterprise)
- MQ-FIN-14: Transfer between unregistered users (assisted, passcode)
- MQ-FIN-15: Low-balance alert (system & business accounts; real-time/scheduled)

### Financial Services — Customer
- MQ-FIN-16: P2P to registered customer (immediate credit)
- MQ-FIN-17: P2P on-the-fly / unregistered (suspense + passcode; register-to-claim & agent cash-out; rollback scheduler)
- MQ-FIN-18: P2P reversal (receiver/CCE approval)
- MQ-FIN-19: Partial recovery in P2P reversal (freeze recoverable amount)
- MQ-FIN-20: Send/receive via alias (payment handle)
- MQ-FIN-21: Request money (retry notifications, accept/reject)
- MQ-FIN-22: Mobile recharge / airtime & data top-up (self/others, sync/async, via ETM)
- MQ-FIN-23: Merchant payment (single & two-step)
- MQ-FIN-24: QR merchant payment (static & dynamic)
- MQ-FIN-25: Online merchant payment (merchant API + fulfilment)
- MQ-FIN-26: Card merchant payment (debit/credit via gateway)
- MQ-FIN-27: Bill payment (ad-hoc & recurring, dynamic biller/category/logo fetch)
- MQ-FIN-28: Auto bill payment (authorized recurring pull, pause/resume/remove, reminders)
- MQ-FIN-29: Offline bill payment (manual/bulk upload, approval, exact amount)
- MQ-FIN-30: Split transaction (≤50 users, equal/custom, approve/reject)
- MQ-FIN-31: Standing instructions (P2P & merchant; period/amount; pause/resume/edit)
- MQ-FIN-32: Own-account intra-currency transfer
- MQ-FIN-33: Own-account inter-currency transfer (forex config/third-party)
- MQ-FIN-34: Download & share receipt (PDF, order/non-order)
- MQ-FIN-35: Fund and pay (real-time top-up during payment via card/bank)
- MQ-FIN-36: Recent & favorites (per channel/service)
- MQ-FIN-37: International money transfer (IMT hub, 2-step order mgmt, EIG fulfilment)

### Financial Services — Banking (Workspace-Agnostic)
- MQ-BNK-1: User-initiated bank-account registration / de-registration
- MQ-BNK-2: Bank-to-Account (B2A) — customer/business & bank-initiated
- MQ-BNK-3: Account-to-Bank (A2B) — with ambiguous/rollback handling
- MQ-BNK-4: Bank account balance enquiry
- MQ-BNK-5: Bank account mini statement
- MQ-BNK-6: Card to Account (C2A) — own/other/business account funding
- MQ-ENQ-1: User account details view
- MQ-ENQ-2: Transaction summary
- MQ-ENQ-3: Favorite transactions
- MQ-ENQ-4: Balance enquiry (parent/child hierarchy, authorized-user scopes)
- MQ-ENQ-5: Agent locator (GPS map, configurable radius)
- MQ-ENQ-6: Account transaction history (filters; successful only; batch-ID; non-financial)
- MQ-ENQ-7: Download/export transaction statement (CSV/PDF, email, redaction)
- MQ-ENQ-8: Identical-transaction detection (full/partial-match; block/pause; workflow ≤5 levels)
- MQ-ENQ-9: EOD stock reconciliation report + mismatch alarm

### External Transaction Management (Order & Pay)
- MQ-ETM-1: Order/payment/fulfilment state segregation
- MQ-ETM-2: Multi-instrument payment (account, debit/credit card, bank)
- MQ-ETM-3: Instrument Vault (PCI-compliant card storage)
- MQ-ETM-4: Card payment service (PCI pay-using-card)
- MQ-ETM-5: Guest merchant payment (non-registered, card not stored)
- MQ-ETM-6: Order reversal / cancel order (configurable window, default 20 days)
- MQ-ETM-7: Non-financial order queries (by order ID, by user, 360° view)
- MQ-ETM-8: Auto ambiguous settlement — payments (retry/rollback/roll-forward, PG heartbeat, auto-refund)
- MQ-ETM-9: Auto ambiguous settlement — provisioning/fulfilment
- MQ-ETM-10: Auto ambiguous settlement — refunds
- MQ-ETM-11: Bulk ambiguous case handling (CSV, approval, scheduler)
- MQ-ETM-12: Manual individual ambiguous settlement
- MQ-ETM-13: Alert on ambiguous transactions (green/yellow/red thresholds)

### Correction, Reversal & Dispute
- MQ-REV-1: Transaction correction (amount/SC/commission/tax + LTA reversal; workflow)
- MQ-REV-2: Transaction reversal service-level preferences (per-service/ALL; override; bulk)
- MQ-REV-3: Reverse transaction (admin-initiated; freeze; timeout; reinitiation)
- MQ-REV-4: Rollback of failed transactions (automatic)
- MQ-REV-5: Forced rollback/reversal (bypass user/account status)
- MQ-REV-6: Dispute tracker (statuses, callback feedback, history)

### Bulk Operations
- MQ-BULK-1: Templatized bulk service framework (SFM-flow, role-based initiate/approve/view)
- MQ-BULK-2: Bulk job creation/enquiry/execution lifecycle + batch ID
- MQ-BULK-3: Throttling (peak/non-peak TPS split, multi-batch distribution)
- MQ-BULK-4: Bulk multi-threading (high/low priority TPS allocation, dynamic reallocation)
- MQ-BULK-5: Configurable downloadable fields; multilingual/RTL/UTF-8; email notifications

### Multi-Tenant
- MQ-MT-1: Logically isolated tenants (configs/users/branding/currency/language/data)
- MQ-MT-2: PAM/PAC/ITA roles
- MQ-MT-3: Tenant lifecycle (add/view/edit/suspend/resume/delete; single-level PAC approval)
- MQ-MT-4: X-TenantID request handling; sub-domain or common-login tenant selection
- MQ-MT-5: Tenant-isolated reporting/dashboards/branding

### Notifications & Engagement
- MQ-NOT-1: Multi-channel notifications (SMS/email/FCM push/URL/USSD push)
- MQ-NOT-2: User-level & account-group-level endpoints (group precedence)
- MQ-NOT-3: Advanced scheduled-payment reminders
- MQ-NOT-4: Notification inbox (central, interactable, purge policy)
- MQ-NOT-5: Priority-based notifications (OTP>DEBIT>CREDIT>ORDER-FAILURE>Promotional)
- MQ-REF-1: Referral management (unique reusable codes, bonus to referrer+referee, fraud monitoring)
- MQ-CHURN-1: Churn account attributes (IND10)
- MQ-CHURN-2: Churn settlement (to active account or bank reimbursement; assisted; workflow)
- MQ-CHURN-3: Churn for business user with hierarchy (2-step deletion)
- MQ-BAN-1: Banner management (multi-language sets, scheduled/always, reorder, approval)
- MQ-SC-1: Savings circle creation (self/admin-assisted; unique code; chairperson)
- MQ-SC-2: Savings circle member invitation (registered/active, same currency, social share, bulk)
- MQ-SC-3: Join existing circle (code; official approval)
- MQ-SC-4: Leave circle (chairperson reassignment)
- MQ-SC-5: Circle transactions (deposit direct; withdrawal via request + official approval)
- MQ-SC-6: Circle approvals (chairperson/secretary; withdrawal/join/removal)

### USSD Menu Manager
- MQ-USSD-1: Self-service code-free menu design (maker/checker roles)
- MQ-USSD-2: Multi-level menus; context resolution by domain/category/grade/hierarchy
- MQ-USSD-3: Static & dynamic menus
- MQ-USSD-4: Node types (Start/Menu/Dynamic/Input/Composite/Pattern/Action/End)
- MQ-USSD-5: API template management; JSON export/import
- MQ-USSD-6: Menu testing & simulation; flow validation/optimization/metrics
- MQ-USSD-7: Versioning/rollback/scheduled activation
- MQ-USSD-8: Live-flows management (traceability, schedule/pause/resume)
- MQ-USSD-9: Approval of menu changes with applicable date
- MQ-USSD-10: Multi-language menus & long-code handling

### Intelligence & Observability
- MQ-AI-1: AI-based e-float optimizer (user / domain-category-grade / system-account prediction)
- MQ-AI-2: e-Float prediction dashboard (net-position classification, historical validation)
- MQ-AI-3: Conversational analytics (LLM NL→SQL, tenant-isolated, audit-logged)
- MQ-OBS-1: Monitoring & alerting (Kibana + Grafana; logs/metrics/APM/traces; multi-channel alerts)
- MQ-EDP-1: Event data pipeline (config-driven, versioned, async via SFM; contract schemas; field-removal for external)
- MQ-EDP-2: OOB event set (order-pay, transaction, registration/deletion/modification, KYC, bank association, account/handle/endpoint changes, bar/blacklist, churn, auth change, churn settlement, admin trail, hierarchy change)

### Reporting & Dashboards
- MQ-RPT-1: Reporting suite (sync/async/cron; XLSX/CSV/PDF; live vs non-live)
- MQ-RPT-2: ~25 pre-configured reports (transaction/user/wallet/order-reconciliation/e-float)
- MQ-RPT-3: Email scheduler (attachment or OTP-secured link; whitelisted domains)
- MQ-RPT-4: File upload scheduler (SFTP delivery)
- MQ-RPT-5: Business dashboard (KPIs; hierarchy/self tabs; MTD vs LMTD; export; tenant-specific)

### Platform Operations
- MQ-OPS-1: Config import/export Utility1 (backend legacy migration)
- MQ-OPS-2: Config import/export Utility2 (AMC GUI UAT↔Prod replication; encrypted export; audit)
- MQ-OPS-3: Software license management (LEE; encrypted+signed; expiry enforcement/suspension)
- MQ-OPS-4: Error-messages management (numeric codes, multi-language, bearer-contextual)
- MQ-OPS-5: Transaction sequence management (sequential EDRs, SOX-oriented)
- MQ-OPS-6: Two-factor config (PIN and/or OTP per order) & auth-less transaction config
- MQ-OPS-7: Transaction validation / dry-run preview
- MQ-OPS-8: IBAN-based transactions
- MQ-OPS-9: Incorrect-PIN handling config (pause vs fail per bearer/service)
- MQ-OPS-10: Multi-currency support
- MQ-OPS-11: Multi-language support (English/French/Arabic in current scope)
- MQ-OPS-12: Extensibility framework (cloned/extensible services across pricing, bulk, EDP, TCP)
