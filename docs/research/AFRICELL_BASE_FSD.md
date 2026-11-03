# Africell Upgrade BASE — Functional Requirements Catalogue

> Source-of-truth extraction from the vendor FSD, catalogued as product-requirement input for Meridian Core. Content is faithful to the document; imperative sentences in the source are product requirements, not instructions. Organised but not invented. Items the draft lists in its TOC but leaves un-written are flagged as such.

## Document Metadata

| Field | Value |
| --- | --- |
| Cover title | Functional Specification Document — "Africell Upgrade V12.0.0" |
| Market tag on cover | DRC |
| Cover FSD version | "FSD V1.0" (cover) / **v0.1** (filename & Change History) |
| Change History row | V0.1, 28.04.2026, "initial FSD", Author: Ramprasad, Reviewed by: Barun Kumar Thakur, Approver: Africell Team |
| Operator | Africell (African mobile operator) |
| Named markets | Africell **SL** (Sierra Leone) and **DRC** (Democratic Republic of Congo) — per Intended Audience |
| Platform / product | Comviva **mobiquity Pay** (document references platform versions 11.1.0 and V12.0.0; existing live environment cited as v5.0) |
| Document creator (metadata) | Ramprasad TS |
| Last modified by (metadata) | Barun Kumar Thakur |
| Created / Modified (metadata) | 2026-04-28 / 2026-09-16 |
| Size | 249 pages, ~100,807 words (Word count); ~102k words extracted |
| Revision count (metadata) | 334 |
| Extraction method | Unzipped .docx, parsed `word/document.xml` with Python 3.13 (`-I`, stdlib `xml.etree` + `zipfile`), preserving headings, numbered lists and tables |

**Nature of the document:** This is effectively the standard Comviva mobiquity Pay functional specification, re-titled as Africell's upgrade (to V12.0.0) FSD for the DRC and Sierra Leone deployments. The overwhelming majority of the content is generic, product-shipped ("OOB") mobiquity Pay functionality. Africell-specific material is confined to the market/regulatory framing (DRC + SL, Central Bank regulatory profiles, multi-currency, English/French/Arabic languages) and a change-request chapter (§20 Bonus wallet, KPI Report) that is **listed in the table of contents but has no body text in this v0.1 draft**. Several embedded Excel/attachment artefacts (category profiles `AdminJSON.json`, pre-configured profiles/config workbook, full report list, import-validation workbook) are referenced but not inlined.

---

## Document Information (§1–§3)

- **§1.1 Scope** — Describes the functionalities and services of mobiquity Pay offered to Africell's stakeholders. FSD is declared the sole source of functional scope; scope changes require an FSD update.
- **§1.2 Intended Audience** — Product/Business/Sales/Operations/Finance/Product Validation/Marketing/Project & Program Management teams of **Africell SL and DRC**.
- **§1.3 Limits** — Functional only; excludes UI/screen flows, non-functional and technical implementation details (covered by user manual, training deck, OAM docs).
- **§2 About mobiquity Pay** — White-labelled Digital Accounts & Payments platform by Comviva for banked and unbanked users; multi-channel (app, USSD, SMS, open APIs); multiple mobile accounts as electronic replicas of physical accounts; P2P, P2B/B2P, B2A/A2B and bulk flows; cash-in/cash-out last-mile.
- **§3 Functional Overview** — Stateless, independently deployable, loosely coupled components; configurability over customisation; core app + bearer/merchant interfaces + receiver entities + third-party systems via API.

**Acronyms of note** (from §Acronyms): USSD, STK, KYC, DFS, TCP (Transfer Control Profile), UMS, CIF, AMC (Admin Management Console), BMC (Business Management Console), ETM/Order-and-Pay (External Transaction Management), O2C (Operator to Channel), EIG/EIG (External Interface Gateway / Interface Abstraction Layer), IMT, MTO, B2A/A2B, FIC (Funds in Clearance), EDR, SFM (Service Orchestrator Engine), IAM/SSO/SAML/IdP, EMV/HCE/PAN/CVV/BIN, mPIN.

---

## §4 mobiquity Pay Components — Actors & Workspaces

**Purpose:** Define the actor model and three workspaces. Authorization Management controls access rights; UMS creates actors.

**Three workspaces:** Administrators, Business, Customer.

- **§4.2 Administrators Workspace** (internal, web UI):
  - **Master Admin** — exactly two (maker + checker), factory-shipped at install, cannot be replicated; create/manage System Admins, accounts, cumulative TCP limits, auth profiles, approvals.
  - **System Admin** — created by Master Admin; manage users/accounts, float generation, service charges/commission/cashback, thresholds, transfer rules, system config (auth/security profiles), approvals, enquiries.
  - **Business Admin** — created by System Admin; maintain business hierarchy (Distributors, Agents, Merchants, Billers), register billers/enterprises, transaction corrections, enquiries.
  - **Customer Care Admin / CCE** — created by System Admin; enquiries, lock/unlock, suspend/resume, reset passwords/PINs, initiate corrections, reports.
- **§4.3 Business Workspace** (external users via app/web/API): Primary and Authorized business users. Categories: Enterprise, Distributors, Merchants, Billers, Recharge Partners, Agents. Configurable n-level distribution hierarchy. Responsibilities: customer/employee management, B2B transfer, cash-in/out for customers, bill payments.
- **§4.4 Customer Workspace** — Primary User + Authorized User; self-registration or assisted. Features: bank association, account funding via bank, bill payments, money transfers, recharge, merchant payments, banking services, international money transfers.

---

## §5 User Management (Concepts)

**§5.1 UMS core entities:** Workspace, Category, Category Profile (business-rule engine governing UI rendering + validations), Security Profile (default per workspace + user-defined), Marketing Profile (Domain, Category, Currency, Account Types, Grade, TCP), Regulatory Profile (KYC tier), Primary User (account owner), Authorized User (operator), Account Group (= marketing profile from customer view; one default per primary user), Account (financial entity; 2-digit Account ID), Access Identifiers (mobile/email/login ID), Notification Endpoints (SMS/email/push), Payment Handles (mobile/email/user-defined/system user code; unique system-wide). Mobile number not mandatory for account opening.

**§5.2 Trusted Systems:** Register external systems; admin creates client ID, platform generates client secret; system token auth; associate financial/non-financial services; notification URLs; block-notification config by service/channel/party/type; secret regeneration is approval/workflow compliant.

**§5.3 Authorization Management:** Roles (atomic privileges, whitelisted per workspace/category, install-time), Modules (logical grouping of roles, install-time), Attributes (micro-controls: User Types, Channels Allowed [Web/Mobile/API/USSD], Operator Account List, Bank Lists, Access Type [FULL_ACCESS vs USER_SPECIFIC_ACCESS]), Authorization Profiles (Default by integrator / User-Defined by admin; default auto-assigned at self-registration).

**§5.4 Approval Management:** States (Draft, Cancelled, Submitted, Completed, Rejected); Actions (Save, Resume, Withdraw, Cancel, Submit, Approve, Reject); single-view approvals; configurable approval levels (e.g., second-level for Bulk Enterprise Payment above a per-user configurable amount).

**§5.5 Hierarchy:** Business link for fund transfer control + hierarchical commissions, driven by domain/category. Downstream-only user creation. Single and **bulk hierarchy movement** (same-domain only, parent one level above, balance/child-user conditions configurable, workflow compliant, SMS/Email to user + old/new parent).

**§5.6 User Grades:** Service-provider-defined grades (e.g., Gold/Silver) per domain/category driving service availability, fees, commission. Standard grade table (Operator; Distributor/Retailer; Wholesaler/Super-Agent/Sub-Agent; Head Merchant/Merchant; Top-up & Biller partners; Payroll/Enterprise; Customer).

**§5.7 User Access Channels:** Web interface, APIs, USSD (USSD Menu Manager bundled; GUI menu creation without L3 involvement).

**§5.8 System Accounts (factory + business-need):**

| Account | Code | Role |
| --- | --- | --- |
| Trust Account | IND04 | Holds all eMoney generated by Bank; liability (negative balance); bank-id tagged; stock gen, B2A/A2B |
| Capital account | IND01 | Main operational/capital account; O2C, stock transfer to EA, stock delete/withdrawal |
| Suspense Account | IND02 | Holds funds to unregistered users (P2P unreg, Cash-in Others) until claimed/rolled back |
| Income Account | IND03 | Service charges earned |
| Expense Account | IND03B | Commissions payable |
| Unclaimed Funds Account | (IND06 referenced) | Aged unregistered funds moved from IND02; admin moves to IND01 or back to IND02 |
| Rewards Account | IND08 | Referral and Cashback incentives |
| Churn Account | IND10 | Balances of churned mobile numbers |
| Churn Suspense Account | IND11 | Aged churn funds swept to **Central bank** |
| Intermediate Account | IND012/IND12 | Bulk financial services; multi-instance funds |
| Interest Account | IND23 | Interest disbursal |
| Expense/Income/Transaction Tax | INDTAX03 / INDTAX02 / INDTAX01 | Tax on commission / on service charge / on transaction amount |
| Business/Merchant/Biller/Customer/Bank accounts | per-user | Normal, commission, salary accounts etc. |

**§5.9–§5.20 Concepts:** Stock Management (eMoney "stock"), Transfer Control Profile (cumulative + instrument limits; §5.10.1 limit/balance-check disablement), Pricing Engine, Transfer Rule Management, External Transaction Management (Order-and-Pay), Orders, Transactions (2FA PIN/OTP configurable for orders), Card Payment Service, Fulfilment, Ambiguous Orders Handling, Payment, Instrument Vault (secure card storage within Comviva).

---

## §5 (addendum) Existing Profiles & Configurations

Upgrade reuses existing live-environment (v5.0) configurations: Category profile, Channel grades, Category Relations, TCP configuration, Transfer Rules, Group roles, Pricing policy, Hierarchy, Grade, Category, Domain (details in an attached Excel). Additional config can be added post-upgrade per business need.

---

## §6 mobiquity Pay Configuration

- **§6.1 Account Group Management** — Dynamic allocation via marketing profiles; one default group per primary user; add/close groups; configurable count per primary KYC type/workspace; unique account-group ID; nicknames; account numbers suffixable with system number/IBAN/NUBAN. §6.1.1 Linking / §6.1.2 Delinking authorized users (UI currently one group per AU; delink notifications). §6.1.3 Closure (no funds/no pending; balance to IND10). §6.1.4 Bulk deletion (default group protected, approval workflow). §6.1.5 Account Closure Settlement (Cash/Cheque-DD/Transfer; IND10→IND04; auto-sweep to IND11 after X days).
- **§6.2 Account Management** — Pre-configured master accounts; new "Normal" types; unique case-insensitive name; 2-digit Account ID; service association per currency; default account non-deletable; non-default deletion requires zero balance.
  - **§6.2.1 Multiple Account Instances** — Split high-traffic accounts into virtual instances (Zeroth account + instances), IND12 holds funds, EOD cumulative transfer back for reconciliation; balance/TCP-check configuration for split wallets; system-wide toggle to skip balance-threshold validations for split wallets.
- **§6.3 Bank Account Management** — Bank Master (Trust/Non-Trust/Partner/Non-Partner); Trust = escrow (stock create/withdraw only, no B2A/A2B); Non-Trust = customer B2A/A2B/enquiry; Pool account number for banking transactions; non-partner routed via partner bank; one trust + one non-trust per currency per bank; bank details immutable post-add (delete + re-add to change). §6.3.2 Business-user bank linking (offline=approval / online=bank confirmation; API one-step; token-driven default mode). §6.3.3 Customer bank add (assisted, active customers). §6.3.4 Max-bank limit. §6.3.5 Service-based bank fetch. §6.3.6 Default bank per currency. §6.3.7 Default payment instrument per currency.
- **§6.4 Domain & Category Management** — Create/manage distribution hierarchies; unique domain/category name+code; one category increment at a time; no UI deletion (backend only); owner category = level 1; service preferences (payer/payee/initiator financial services, non-financial services, web roles) mapped at approval. §6.4.1 View category hierarchy.
- **§6.5 Grade Management** — Multiple grades per category; code immutable, name editable; deletable only if not tied to TCP/pricing; workflow compliant. §6.5.1 **Un-registered Grade** (factory, auto-assigned to unregistered MSISDN; enables services involving unregistered sender/receiver).
- **§6.6 Transfer Rule Management** — Governs allowed transfers by category/grade/service; add/modify/delete/enable-disable/view; O2C rules; hierarchy vs within+outside-hierarchy config for transfer/recall stock. §6.6.1 Transfer Amount Validation (min/max/multiples; by currency/domain/category/grade/instrument/account).
- **§6.7 Transfer Control Profile (TCP)** — Cumulative (Customer-level, Rank 1, on primary KYC ID) and Instrument (grade-wise, Rank 2) limits; count/amount per service/bearer, per-txn, min/max balance, max % transfer; daily/weekly/monthly/yearly; payer and payee separately; amount field up to 18 digits (default 12 + currency factor 10,000). §6.7.1 RULE1/RULE2/RULE3 bearer configuration (`TCP_TYPE_APPLICABLE`), yearly-limit configs (`ENABLE_YEARLY`). §6.7.2 Cumulative TCP with regulatory profile; KYC-level vs user-level cumulative counters (`Register user with same primary KYC`, `Cumulative thresholds definition at KYC level`, category list with ALL option). Default regulatory profile `DEFAULT_REGISTRATION_TYPE: NO_KYC`.
- **§6.9 Import/Export System Configurations** — §6.9.1 Utility1 (backend CSV migration from legacy e.g. 4.4 to X.10+; `Created By=UTILITY`; duplicate handling; Phase-1 supports Transfer Rules, Cumulative/Instrument TCP, Pricing Service Charge/Commission/Transaction Tax). §6.9.2 Utility2 (AMC GUI, UAT→Prod, key-attribute matching, update/insert/remove reconciliation, unique Import Request ID, status lifecycle Uploading→…→Completed/Rejected, encrypted binary + JSON export, audit). §6.9.3 Import module validation summary (attachment).

---

## §7 Pricing Engine

**Purpose:** Rule-based configuration of Service Charge, Commission, Transaction Tax, Cashback, Discount, Reward/Bonus and interest for services, by bearer, time of day, day of week, party, instrument. Replaces the older Service Charge module.

**Model:** Policy → ranked Rules → Charge Statements. Highest-rank matching rule wins; no match = zero charge. Defines "who pays / whom to pay". Amount field up to 18 digits (default 12 + currency factor).

- **§7.1** Custom condition configuration.
- **§7.2** Service Charge, Tax & Commission management; charges applied in default currency. §7.2.1 Service Charge & Commission Policy.
- **§7.3** Service Level Settings; §7.3.1 Rule Details (slab min/max of two values etc.); §7.3.2 Define Service Charge Policy features.
- **§7.4** Commission Policy; §7.4.1 Joining Commission; §7.4.2 Customer Bonus on registration + PIN set; §7.4.3 Additional commission for cash-in to inactive customers; §7.4.4 Commission Sharing.
- **§7.5** Category-based hierarchy commission mapping; §7.5.1 onsite/offsite validation; §7.5.2 Cashback (non-financial cashback in system default currency; per-currency selection); §7.5.3 Milestone-based charging; §7.5.4 Clone rules.
- **§7.6** Discount Policy. **§7.7** Save drafts. **§7.8** Approval of pricing rules. **§7.9** Resolve rejected policy. **§7.10** Search rules. **§7.11** Pricing Calculator. **§7.12** View previous policy versions. **§7.13** Pricing-engine roles. **§7.14** Out of scope.

---

## §8 User Management — Profiles

- **§8.1** User Profile Configuration overview (Category, Security, Regulatory, Marketing, Authorization profiles).
- **§8.2 Category Profile** — Business-rule engine; steppers/sections; field properties (Labels, Input Type, Acceptable values, Pattern, Mandatory, Modifiable, Unique, External Verification, Identifier, Additional Attributes 1–10); applies across all channels; `AdminJSON.json` OOB template referenced.
- **§8.3 Security Profile** — Password/PIN complexity, 2FA, login rules; default per workspace. **PIN**: length (default 9), no sequential/DOB/primary-KYC, failed attempts before lockout (default 3), CAPTCHA threshold, expiry (default 365d), non-repeating (default 1), fixed vs variable length config. **Password**: min 8/max 20, digits/caps/special (`!@#$&*_`), expiry 365d, disallow last N. **2FA/OTP**: every-time / on-IP-change / on-device-change (numeric OTP, configurable length, not on USSD). **Login rules**: simultaneous logins (default 1), allowed days/time, IP range, cool-off, auto-logout (default 15 min), new-device notification, min/max security questions.
- **§8.4 Security Questions Management** — Master question list, unique codes, multi-language mandatory, self-reset policy.
- **§8.5 Regulatory Profile** — KYC tiers (No KYC, Full KYC) **as per Central Bank of the country**; unique code (immutable), name editable; cumulative thresholds per currency/domain/category/regulatory profile; approval workflow; cannot delete if tied to cumulative TCP.
- **§8.6 Marketing Profile** — One mandatory account group; multiple accounts (currency, grade, instrument-level TCP); one account per type per currency; one default account; approval workflow; modifications applied on user re-selection; churn module to delete associated accounts.

---

## §9 User Management System (UMS) — Lifecycle & Operations

- **§9.1 User Lifecycle** — Primary/Authorized registration, manage (view/modify/suspend/resume/delete), self-modification, update/delete authorized user, delete primary/enterprise user, blacklist, status management.
- **§9.2 User Registration** — Self vs assisted; single/two-step; identifiers mobile/email/user ID (unique per workspace by type); Min/Full KYC; child business-user registration; enterprise registration (≥1 employee with super-admin, employees as authorized users on enterprise account group, dummy owner "Primary User" non-login, employee code = login ID); biller registration; `Register User with Same Primary KYC` (Yes/No), `Maximum Number of Account Groups Allowed`, `Max authorized users per primary user`; regulatory profile auto-attach (NO_KYC/PARTIAL_KYC/FULL_KYC); default marketing/security/auth profiles per category+regulatory profile; casing rules (User ID + KYC upper; payment handle + identifier lower); global mobile-prefix (0/00) removal.
- **§9.3 Managing Users** — Search by mobile/user ID/email/KYC; view accounts, transactions, identifiers, handles, endpoints, thresholds; modify (KYC change needs approval), suspend/resume, delete (zero balance; child re-link to parent; direct children become primary).
- **§9.4 User Self Modification** — Primary contacts (OTP-authenticated), additional contacts, login IDs (one per type), payment IDs; mobile/email cannot be changed as identifiers; KYC primary-mark rules; regulatory-profile change propagation under same-KYC flags; approval routed to Admin.
- **§9.5–§9.8** Update authorized user (single/two-step), delete authorized user (auto de-link), delete enterprise (+employees, approval), delete primary user (deletes all groups/authorized users; zero balance; funds to churn suspense).
- **§9.9 Blacklist Management** — Bulk CSV upload of regulator/government lists; maker-checker; blocks registration/modification for matching First+Last name / DOB / KYC ID+number / User ID (MSISDN/email/login); dashboard history.
- **§9.10 User Status Management** — Statuses: Activation Pending (AP), Active (A), Inactive (I), Dormant (D), Suspended (S), Deleted/Churned (C); workspace-scoped; configurable periods per category; auto (scheduler) + manual (API, auth-driven) transitions; primary-status change gated on authorized-user statuses; dormant→churn auto-churns authorized users; status-change notifications before/after.
- **§9.11 SIM Swap Detection** — Third-party GSM system detects swap and calls SIM Swap API; enforces login block, PIN-reset block, wallet lock; configurable cooling period extends status duration (e.g., +7d).
- **§9.12 My Activity Management** — Tracks approval-based requests; draft/submit/withdraw/resume/cancel; incomplete-application lifespan; configurable workflow expiry; dashboard additional-info config.
- **§9.13 Transaction Enquiry & Reversal** — Search by Transaction ID / Customer (External Reference) ID in AMC & BMC; role-scoped visibility; reversals for order (post-completion) & non-order (post-success); admin-assisted, primary self, authorized self, receiver-initiated; SC/commission/tax reversal config; Dispute Tracker; phase-2 items noted.
- **§9.14 Approval Management** — Request types: Admin Transactions, Modification, Addition, Reversal; configurable approval levels per request type; view/approve/reject; side-by-side original vs changed; configurable workflow expiry; dashboard filters (top-5 dynamic by category), multi-select, additional-info columns, T-2 default range (configurable).
- **§9.15 Session Management** — Self and admin session control (list devices, logout/delete other sessions); statuses Active/Current/Inactive(Closed); device binding re-init on trusted-device removal. **Authentication Management**: PIN/Password login across web/app/API; input-based / session-based / **SSO (SAML via IAM/IdP)** authentication (SSO delivered in upcoming release); OTP-on-top for sensitive changes; session-expiry handling.

---

## §10 Authentication Configurations & Operations

- **§10 (intro) Authentication Configurations** — Multiple identifiers → single auth factor; per-identifier auth type; channel-specific auth; workspace-level auth; password complexity/expiry/lockout rules; blocking a login-identifier/password combination across identifiers while other schemes still work.
- **§10.1 Access Identifier Management** — Self/assisted add/modify/remove; one identifier per type; at least one must remain; mobile/email cannot be added/modified/deleted via this; OTP verification.
- **§10.2 KYC Management** — Mandatory-document config per user type; mark primary; preview (no download); approver check at registration; grace period for expiring KYC; **KYC Upgrade** (before/after expiry, workflow compliant, multi-user KYC handling, retention per regulation).
- **§10.3 AMC (Admin Management Console)** — Search (§10.3.1), view/modify (§10.3.2), suspend/resume (§10.3.3), **account statements** (§10.3.4: export PDF/CSV to email, resend notifications/passcodes, reversal initiation, configurable columns), account info (§10.3.5–6), **Lock/Unlock Accounts** (§10.3.7: as sender/receiver/both; all/by-currency/specific; mandatory enum reason e.g. Fraud/Lost SIM/Vacation; approval workflow), bank info (§10.3.8), credentials management (§10.3.9), share receipt (§10.3.10), **Payment Handle Management** (§10.3.11), User Code as handle (§10.3.12), **IBAN generation/usage for individual accounts** (§10.3.13), notification-template management (§10.3.14). Tab labels configurable.
- **§10.4 BMC (Business Management Console)** — Tasks on self/hierarchies/employee; view/modify/delete/suspend; view orders; account management; credentials; limited-rights auth-profile creation; common business employer account.
- **§10.5 Authorization Management** — Authorization Profiles (§10.5.1), Assistant Authorization Profiles (§10.5.2), features (§10.5.3) incl. **Data Redaction Configuration** (§10.5.3.1: redact first-n / middle characters e.g. `****1234`, `1234****5678`).
- **§10.6 Audit Trail Management** — Logs admin and transaction actions (see Admin Trail / Audit Trail reports).
- **§10.7 Workflow Management** — Creation process, configuration, managing, approval levels, bulk-vs-single differentiation, approval workflows for business & enterprise users, **multi-level approval for financial transactions** (amount-driven), workflow notifications.
- **§10.8 Transactions** — Sequence management; initiating; **Auth-Less transaction configuration**; validation (PIN/OTP/both/none config); incorrect-PIN handling; **IBAN-based transactions**; categories; administrative transactions incl. **Freeze/Unfreeze Funds** (§10.8.8.1) and **Auto Debit Instructions** (§10.8.8.2).
- **§10.9 Stock Management (eMoney)** — Stock creation, withdrawal, transfer between system accounts, reimbursement, **Allocation (O2C)**, auto-deallocation, rollback for stock withdrawal, stock balance enquiry, bank balance enquiry, unclaimed money settlement.
- **§10.10 Commission Related Transactions** — Commission payment, disbursement process, accrued commission withdrawal, disbursement records download.
- **§10.11 Transaction Correction** — Reversal service-level preferences (§10.11.1), Reverse Transaction (§10.11.2), Rollback (§10.11.3), **Dispute Tracker** (§10.11.4).
- **§10.12 Bulk Operations** — Roles/permissions, bulk job creation, record statuses, enquiry, execution, **multi-threading**, limitations, administrative-transactions fund flow. Bulk services table includes User Registration/Modification/Deletion (OOB), etc.
- **§10.13 Business Transactions:**
  - **Transfer Stock (B2B)** — eMoney between business users; requires Transfer Rules + Service Charge + TCP; sender/receiver via identifier/handle/user-code; configurable workflow when initiated by authorized user (paused until approval; multi-level by amount).
  - **Recall Stock (Inverse B2B)** — Parent pulls funds from downstream child; configurable 2-step with child approval; web/app/USSD.
  - **Cash-In (Deposit Cash)** — Business user debited, customer credited; accounting table (IND03/IND03B). Channels: app/USSD/web.
  - **Cash-In (Others)** — To unregistered user via agent → IND02 suspense; passcode generation (3 configs incl. in/out-of-network `ignoreUnknownPartyNetwork`); claim by registration or agent cash-out; passcodes never stored in plain text.
  - **Cash-Out (Withdraw Cash)** — Customer-initiated single-step or business-initiated two-step; PIN confirmation; accounting table.
  - **Agent Pass-Code Cash-out** — Unregistered receiver collects via passcode from agent; debit IND02, credit agent.
  - **ATM Cash-Out** — Customer requests OTP/passcode; ATM validates via mobiquity Pay; debit on passcode validation.
  - **Merchant Refund** (+ §10.13.8.1 Partial Refund, §10.13.8.2 Auto-sweep) — Full/multiple partial refunds within configurable window; no SC/commission reversal of original; USSD/app only; refund status shown for P2P/Merchant/Bill/B2B. Auto-sweep e-value to bank at chosen frequency with residual-balance floor.
  - **Bulk Salary Payments** — Enterprise CSV upload (>1M records), auto-retry/auto-rollback, unique batch ID, 2-step mandatory, initiator≠approver; registered via IND012, unregistered via IND02; §10.13.9.1 pre-validation (single-debit/multi-credit) with batch statuses.
  - **Bulk Bill Payments** — Enterprise CSV (currency, amount, sender account type, biller code, bill/account number, remarks); 2-step mandatory; bulk financial services do not support commission pricing rule.
  - **Transfer of funds between unregistered users** — Agent-assisted; passcode to receiver; configurable max amount.
  - **Low Balance Alert** — Thresholds on system + business accounts; SMS/email/in-app/push; real-time or scheduled (default 8AM/12PM/5PM); approval workflow.
- **§10.14 Customer Transactions:**
  - **Domestic Money Transfer (P2P)** — §10.14.1.1 to registered (immediate credit to default/handle account); §10.14.1.2 P2P on-the-fly to unregistered (Method 1 register within n days, Method 2 passcode→agent cash-out; rollback scheduler default 1:01 AM, passcode expiry default 7d; configurable SC/tax/commission reversal); §10.14.1.3 P2P Reversal (receiver approve/reject or CCE; within configurable X days default 20); §10.14.1.4 partial recovery (freeze recoverable = available − min balance).
  - **Send/Receive via Alias** (payment handle), **Request Money** (notifications incl. USSD; retry config), **Mobile Recharge** (self/others, via ETM, sync/async, account/card), **Mobile Payment Services**, **Merchant Payment** (single/two-step; static/dynamic QR; online API; card via Payment Gateway; cashback to primary account).

---

## §11 Bill Payment

- **§11.1 Auto Bill Payments** — Recurring pull with user authorisation; enable/disable, edit max limit, pause/resume/remove; reminders X days before; funds pulled from account only.
- **§11.2 Offline Bill Payment** — Bills uploaded by admin/business users (no real-time biller integration); approval before payment; one bill per customer-ID+biller; exact amount only; blocked after due date (config flag); reversible; included in Paid Bill report; auto-deletion frequency (default ~9999 days); no auto-pay/reminder for offline.
- **§11.3 Split Transaction** — Split merchant/bill payments among up to 50 (configurable) mobiquity Pay users; equal or custom proportions (2 decimals); mandatory remarks (≤100 chars); per-member status; SMS/email/in-app/inbox; approve/reject.
- **§11.4 Standing Instructions** — Defined period/amount for P2P & merchant payment only; per-currency/account rules; pause/edit/delete/resume; pre-reminders; fails if payer/receiver barred/blocked/locked/deleted/inactive.

---

## §12 Funds Transfer & Workspace-Agnostic Transactions

- **Own-account transfers** — Not counted in thresholds by default (legislation); config to enable TCP checks.
- **§12.1 Intra Account Currency Transfer** — Same-currency across groups.
- **§12.2 Inter Account Currency Transfer** — Cross-currency via exchange rate (configured in mobiquity Pay or third-party; Forex via UI maker/checker or integration; latest rate; logged; single transfer ID; configurable SC).
- **§12.3 Download/Share Receipt** — PDF, successful transactions; detailed fields incl. passcode (decrypted); configurable.
- **§12.4 Fund and Pay** — On-the-fly top-up (debit/credit card or bank) when balance insufficient; two order IDs; skips min-balance validation.
- **§12.5 Recent & Favorites** — Per-app/portal lists for recharge, send/receive, transfer/recall stock, IMT, merchant payment, request money, withdraw.
- **§12.6 International Money Transfer (IMT)** — Order-managed via IMT merchant (2-step); mobiquity Pay instruments only; integrates with **IMT hub** (not individual MTOs) via generic APIs + EIG fulfilment; new domain per IMT partners, category per partner (e.g., Etisalat hub, MoneyGram), grade per destination country; manual refunds/reconciliation. §12.6.1 Send International Money (identical-transaction check).
- **§12.7 Workspace-Agnostic (Mobile Banking — API only):** §12.7.1 services (B2A, A2B, Bank Balance Enquiry); §12.7.2–3 user-initiated bank registration/de-registration (bank-confirmed); §12.7.4 **B2A** (customer/business + bank-initiated; non-trust only; accounting IND04); §12.7.5 **A2B** (debit account/credit pool; fulfilment to bank; ambiguous if no response within 1000ms configurable); §12.7.6 Bank Balance Enquiry; §12.7.7 Bank Mini Statement; §12.7.8 **Card to Account** (register card, fund own/other account via debit/credit card).

---

## §13 Enquiry & Operation Management

- **§13.1** User Account Details. **§13.2** Transaction Summary (per-date credit/debit/count, closing balance). **§13.3** Favorite Transactions (configurable max; PIN confirm; editable amount). **§13.4** Balance Enquiry (parent sees child balances; authorized-user SELF mapping). **§13.5** Account Transaction History (configurable last-x; filters group/type/service/status/date; 15-day window, 3-month lookback example; batch-ID hyperlink for bulk salary; from reporting DB). **§13.6** Download/Export Statement (monthly; CSV/PDF; email; AMC/BMC email-only). **§13.7** Set security Q&A (via USSD/web; 2FA to save). **§13.8** **Forgot Authentication Factor** (OTP and/or security Q&A; High-Balance vs Low-Balance journeys; National ID/DOB/Q&A/OTP; admin-assisted after max attempts; single incorrect-attempt counter; dormant-HB must resubmit KYC). **§13.9** Change Authentication Factor (2FA/old-factor/Q&A/OTP; expiry reminders). **§13.10** Change Language — **English, French, Arabic** supported (English default). **§13.11** Identical Transactions (full/partial match; block/pause; single-level partial-match workflow). **§13.12** Reconciliation (EOD report default 21:59:59; wallet balances per entity; PDF/CSV; mismatch alerts to admin).

---

## §14 External Transaction Management (ETM / Order-and-Pay)

**Purpose:** Segregate accounting, payment and provisioning states; pay from account/credit/debit card. Order reversal for successful orders (default max 20 days) across B2A, A2B, bill/auto-bill payment, recharge/recharge-others, card-to-account/other-account.

- **§14.1 Financial Services Supported** — Recharge self/others, Card-to-Account/Other-Account, B2A, A2B, Pay-using-Card (PCI-compliant), Merchant Payment (online/offline), **Guest Merchant Payment** (non-registered; card not stored), Order Reversal by Administrator, Bill/Auto-Bill Payment.
- **§14.2 Non-Financial Operations** — Get order by ID, historical orders by user identifier, historical transactions by order ID, 360° view (not for employee user type).

---

## §15 Ambiguous Case Handling

**Three modes:** at time of transaction, bulk, manual. §15.1 Auto handling of Payments (§15.1.1 PG no-response: retry N / X minutes; rollback vs roll-forward; heartbeat on PG; auto-refund config), Provisioning (§15.1.2 status checks, wait period W, rollback/roll-forward), Refunds (§15.1.3 retry per payment system; refund failure not allowed → manual). §15.1.4 **Bulk ambiguous** (CSV of Payment/Fulfilment/Reversal ambiguous; approval; scheduler). §15.1.5 **Alert** on ambiguous (Green/Yellow/Red zones per 3rd-party system; email group). §15.1.6 **Error Messages Management** (numeric codes, multi-language by initiator/system language, bearer-specific).

---

## §16 Notification Management

Channels: SMS, Email, **FCM Push**, URL (USSD push / third-party). §16.1 Add endpoints (user-level + account-group-level precedence; authorized users at user-level only). §16.2 Modify/remove (OTP on assisted; at least one endpoint config). §16.3 Advanced reminders for scheduled payments (`PAYMENT_REMINDER_YEARLY/MONTHLY/WEEKLY`, `advance.days.for.notification`). §16.4 **Notification Inbox** (central, interact-able, purge policy 3 months–1 year). §16.5 Priority-based (OTP > DEBIT > CREDIT > ORDER FAILURE > Promotional; template table). §16.6 **Referral Management** (unique reusable alphanumeric code per user; no cross-workspace/domain; bonus to Primary-User default group via Pricing Engine; fraud monitoring; retry on failure).

---

## §17 Churn Management

§17.1 Churn Account (IND10) attributes (system-level; credited with churned user balances; appears in reconciliation/DWH; settlement only to KYC-matched registered users). §17.2 Churn Settlement Process (admin search by identifier; settle per currency to active user account group/handle or full reimbursement to bank from IND01/IND04 via currency→account mapping; assisted only; approval workflow). §17.3 Churn for business user with hierarchy (2-step delete; zero balances; no child users; hierarchy reassignment; no pending transactions; not suspended).

---

## §18 USSD Menu Manager

GUI-based USSD menu authoring without L3; roles **USSD Menu Maker** and **USSD Menu Checker** (maker-checker with applicable date, version compare, rollback). §18.1 Accessing from AMC. §18.1.2–5 Menu creation/modification/deletion/main-menu setup (welcome text + options e.g. "Send Money", "Buy Airtime/Bundles", "Cash In"). §18.1.6 Workflow per menu item. §18.1.7 Unique input attributes (placeholders). §18.1.8 **Static Choice List** (additional params e.g. Bundle Code/Price/Validity). §18.1.9 **Dynamic Choice List** (fetched from mobiquity Pay/third-party via intermediate service; parameterized text; empty-list message). §18.1.10 **Gather Input** (regex + comparison validations; prefix removal). §18.1.11 Terminal Service Menu (request mapping; success codes). §18.1.12 Parameterized text. §18.1.13 Other languages (mandatory when multi-language). §18.1.14 **Associate Intermediate Service** (validation vs non-validation; response codes: go-to-next / stay / go-to-specific / terminate). §18.1.15 Submission with applicable date. §18.1.16 **Long Codes** (e.g. `*123*3*REL*100*1357#`). §18.2 Approval (tree diff view). §18.2.1 Advantages (CRs cut by ~15–20%, self-service, faster time-to-market). §18.3 Monitoring & Alerting (**Kibana/Elasticsearch**, **Grafana/Prometheus**; APM, observability; details in OAM doc).

---

## §19 Reports

Pre-configured reporting suite; filters by Domain / Transaction ID / Access Identifier; Synchronous (direct) vs Asynchronous (View Report screen) vs Cron reports; XLSX default (CSV/PDF configurable); view-report threshold default 1000/max 5000; retention default 7 days; **Live reports** via Kafka Connect + `REFRESH MATERIALIZED VIEW` on Postgres vs hourly non-live.

**20 OOB reports (§19.1):** User Transactions, Service Charge, Commission, Customer Registration, Customer Blocked Access, Suspended Customers, Deleted Users, Reimbursement, Closely Watched Numbers, User Registration KYC, Bank Account Statement, Admin Trail, Audit Trail, Wallet Balance, Daily Wallet Balance Reconciliation, Stock Management, Fulfilment Reconciliation, Distribution Hierarchy O2C Wallet Transfers, Paid Bill, Bulk Payments Transaction. (Full detailed list in an attached workbook.) Currently delivered from AMC; remaining in AMC & BMC in upcoming release.

---

## §20 Change Request OOB — SL (NOT DETAILED IN THIS DRAFT)

The table of contents lists:
- **§20.1 Bonus wallet**
- **§20.2 KPI Report**

These are the only explicitly Africell/Sierra-Leone-specific change-request items in the document, but **the v0.1 draft contains no body text for §20** — the headings appear only in the TOC. Treat these as known Africell customisation intents pending specification.

---

## Africell Market-Specific Notes

- **Markets:** Sierra Leone (SL) and DRC (Democratic Republic of Congo) — the two Africell deployments this upgrade targets.
- **Regulatory:** KYC tiers (regulatory profiles) defined per the **Central Bank of the country**; churn-suspense (IND11) funds swept to Central bank; blacklist driven by government/regulator lists.
- **Multi-currency:** Pervasive; per-currency accounts, default accounts, TCP, marketing profiles, settlement mappings; inter-currency transfer via configurable/third-party Forex. (Specific currency codes e.g. SLE/CDF/USD are not enumerated in the draft; sample config uses placeholders INR/USD/EUR/SGD.)
- **Languages:** English (default), French, Arabic.
- **USSD:** Central to both markets; USSD short/long codes (example `*123#`), USSD Menu Manager, USSD push notifications.
- **Tariffs / specific USSD codes / exact currency codes:** NOT specified in the draft (pricing is rule-engine-driven; no concrete Africell tariff tables or dial strings beyond illustrative examples).

---

## Capability Checklist

Codes: `AFR-<SECTION>-<n>`. Each is a discrete capability catalogued from the FSD.

### Actors & Access (AFR-ACT)
- `AFR-ACT-1` Three-workspace actor model (Administrators / Business / Customer).
- `AFR-ACT-2` Dual Master Admin (maker + checker), factory-shipped, non-replicable.
- `AFR-ACT-3` Delegated admin tiers: System / Business / Customer-Care admins with privilege delegation.
- `AFR-ACT-4` Primary vs Authorized users in both Business and Customer workspaces.
- `AFR-ACT-5` Business categories: Distributor, Merchant/Head Merchant, Wholesaler, Super/Sub-Agent, Biller, Recharge Partner, Enterprise, Agent.
- `AFR-ACT-6` Configurable n-level distribution hierarchy.
- `AFR-ACT-7` Access channels: Web UI, API, USSD, Mobile App.

### User Management & Profiles (AFR-UMS)
- `AFR-UMS-1` Access identifiers decoupled from mobile number (mobile/email/user ID), unique per workspace by type.
- `AFR-UMS-2` Category Profile business-rule engine (field properties, steppers, additional attributes 1–10).
- `AFR-UMS-3` Security Profile (PIN/password complexity, 2FA, login rules, per-workspace default).
- `AFR-UMS-4` Regulatory Profile / KYC tiers per Central Bank.
- `AFR-UMS-5` Marketing Profile (currency/account-type/grade/instrument-TCP bundles; default account).
- `AFR-UMS-6` Authorization Profiles (roles/modules/attributes; default + user-defined).
- `AFR-UMS-7` Account Groups + multiple accounts; default per currency; nicknames; IBAN/NUBAN suffixing.
- `AFR-UMS-8` Payment Handles (mobile/email/user-defined/system code), unique system-wide.
- `AFR-UMS-9` Notification Endpoints at user- and account-group level.
- `AFR-UMS-10` Self vs assisted registration; single/two-step; maker-checker.
- `AFR-UMS-11` Enterprise onboarding (employees as authorized users on enterprise accounts; employee code login).
- `AFR-UMS-12` Biller registration (online + offline process types).
- `AFR-UMS-13` `Register user with same primary KYC` and KYC/user-level cumulative-counter configuration.
- `AFR-UMS-14` User self-modification with OTP-authenticated contacts.
- `AFR-UMS-15` Authorized-user link/delink to groups/accounts.
- `AFR-UMS-16` User deletion (primary/authorized/enterprise) with balance & dependency rules.
- `AFR-UMS-17` Blacklist management (bulk upload, maker-checker, multi-attribute match).
- `AFR-UMS-18` User status lifecycle (AP/A/I/D/S/C) with scheduler + manual transitions.
- `AFR-UMS-19` SIM-swap detection via GSM API with cooling-period status extension.
- `AFR-UMS-20` My Activity + Approval Management (configurable levels, expiry, dashboards).
- `AFR-UMS-21` Session management (self + admin; device binding; force logout).
- `AFR-UMS-22` Authentication: PIN/password, OTP 2FA, SSO (SAML/IAM, future release).
- `AFR-UMS-23` Access Identifier management (one per type, OTP verification).
- `AFR-UMS-24` KYC management + KYC upgrade (grace period, retention, multi-user handling).
- `AFR-UMS-25` Trusted-system registration (client ID/secret, system token, service association).
- `AFR-UMS-26` Hierarchy movement (single + bulk, same-domain, workflow compliant).

### Configuration (AFR-CFG)
- `AFR-CFG-1` Domain & Category management with service preferences.
- `AFR-CFG-2` Grade management (segment-based privileges/pricing).
- `AFR-CFG-3` Un-registered grade for unregistered MSISDN flows.
- `AFR-CFG-4` Transfer Rule management (+ transfer amount min/max/multiples).
- `AFR-CFG-5` Transfer Control Profile — cumulative (customer-level) + instrument (grade) limits.
- `AFR-CFG-6` TCP bearer rules (RULE1/2/3) and yearly-limit configs.
- `AFR-CFG-7` Limit/balance-check disablement per service (payer/payee).
- `AFR-CFG-8` Bank Master (Trust/Non-Trust/Partner/Non-Partner, pool accounts, routing).
- `AFR-CFG-9` Multiple account instances (wallet splitting) for high-traffic accounts.
- `AFR-CFG-10` Import/Export configuration (backend CSV + AMC GUI UAT→Prod).
- `AFR-CFG-11` System-account framework (IND01/02/03/03B/04/06/08/10/11/12/23, tax accounts).

### Pricing (AFR-PRC)
- `AFR-PRC-1` Rule-based Pricing Engine (ranked policies/rules/charge statements).
- `AFR-PRC-2` Service Charge policies (slabs, bearer/time/party conditions).
- `AFR-PRC-3` Commission policies (joining, sharing, category-hierarchy mapping, onsite/offsite).
- `AFR-PRC-4` Transaction tax policies (on amount / service charge / commission).
- `AFR-PRC-5` Cashback policies (per currency; non-financial cashback).
- `AFR-PRC-6` Discount policies + promotional rules.
- `AFR-PRC-7` Customer registration/PIN bonus; cash-in-to-inactive commission.
- `AFR-PRC-8` Milestone-based charging; clone rules; pricing calculator; drafts; versioning; approval.

### Transactions — Business (AFR-BTX)
- `AFR-BTX-1` Transfer Stock (B2B) with authorized-user approval workflow.
- `AFR-BTX-2` Recall Stock (Inverse B2B) with child approval.
- `AFR-BTX-3` Cash-In (deposit) and Cash-In (Others / unregistered via passcode).
- `AFR-BTX-4` Cash-Out (customer single-step / business two-step).
- `AFR-BTX-5` Agent Pass-Code Cash-out.
- `AFR-BTX-6` ATM Cash-Out (OTP/passcode).
- `AFR-BTX-7` Merchant Refund (full + multiple partial, configurable window).
- `AFR-BTX-8` Auto-sweep to bank account at frequency with residual floor.
- `AFR-BTX-9` Bulk Salary Payments (CSV, >1M records, auto-retry/rollback, 2-step, pre-validation).
- `AFR-BTX-10` Bulk Bill Payments (CSV, 2-step).
- `AFR-BTX-11` Transfer between unregistered users (agent-assisted passcode).
- `AFR-BTX-12` Low Balance Alert (system + business accounts; real-time/scheduled).

### Transactions — Customer (AFR-CTX)
- `AFR-CTX-1` P2P to registered customer.
- `AFR-CTX-2` P2P on-the-fly to unregistered (register or passcode; rollback scheduler).
- `AFR-CTX-3` P2P reversal (receiver/CCE) + partial recovery with freeze.
- `AFR-CTX-4` Send/Receive via alias (payment handle).
- `AFR-CTX-5` Request Money (incl. USSD notifications, retry).
- `AFR-CTX-6` Mobile Recharge (self/others; via ETM; sync/async; account/card).
- `AFR-CTX-7` Merchant Payment (single/two-step; static/dynamic QR; online API; card via PG).

### Bill Payment (AFR-BILL)
- `AFR-BILL-1` Ad-hoc + recurring (auto) bill payments with reminders.
- `AFR-BILL-2` Offline bill payment (upload, approval, exact-amount, reversible).
- `AFR-BILL-3` Split transaction (merchant/bill, up to 50 participants).
- `AFR-BILL-4` Standing instructions (P2P + merchant payment).

### Funds / Banking (AFR-FND)
- `AFR-FND-1` Own-account transfers (intra + inter-currency with Forex).
- `AFR-FND-2` Fund-and-Pay on-the-fly top-up (card/bank).
- `AFR-FND-3` International Money Transfer via IMT hub (EIG fulfilment).
- `AFR-FND-4` Bank-to-Account / Account-to-Bank (customer + bank-initiated).
- `AFR-FND-5` Bank balance enquiry + mini statement.
- `AFR-FND-6` Card-to-Account (own/other account funding).
- `AFR-FND-7` User-initiated bank account registration/de-registration.
- `AFR-FND-8` Download/share receipt (PDF, decrypted passcode).
- `AFR-FND-9` Recent & Favorites.

### Enquiry & Ops (AFR-ENQ)
- `AFR-ENQ-1` Account details / transaction summary / balance enquiry (parent-child).
- `AFR-ENQ-2` Account transaction history + statement export (CSV/PDF/email).
- `AFR-ENQ-3` Favorite transactions.
- `AFR-ENQ-4` Forgot / change authentication factor (HB vs LB journeys).
- `AFR-ENQ-5` Set/modify security questions.
- `AFR-ENQ-6` Change language (English/French/Arabic).
- `AFR-ENQ-7` Identical-transaction detection (full/partial match; block/pause).
- `AFR-ENQ-8` Reconciliation (EOD report, wallet balances, mismatch alerts).
- `AFR-ENQ-9` Transaction enquiry & reversal (AMC/BMC, role-scoped, Dispute Tracker).

### Admin Consoles & Workflow (AFR-ADM)
- `AFR-ADM-1` AMC operations (search/modify/suspend/statements/lock-unlock/credentials).
- `AFR-ADM-2` Lock/Unlock accounts (sender/receiver/both; all/currency/specific; enum reason).
- `AFR-ADM-3` BMC operations (self/hierarchy/employee tasks, orders, accounts).
- `AFR-ADM-4` Authorization + Assistant authorization profiles; data redaction.
- `AFR-ADM-5` Audit Trail (admin + transaction).
- `AFR-ADM-6` Workflow management (levels, bulk-vs-single, amount-driven multi-level, notifications).
- `AFR-ADM-7` Freeze/Unfreeze funds; Auto Debit Instructions; Auth-Less config.
- `AFR-ADM-8` Stock management (create/withdraw/transfer/allocate O2C/reimburse/rollback/enquiry).
- `AFR-ADM-9` Commission transactions (payment/disbursement/accrued withdrawal/records).
- `AFR-ADM-10` Bulk Operations engine (jobs, multi-threading, statuses, fund flow).
- `AFR-ADM-11` Payment Handle management + User Code as handle; IBAN generation.
- `AFR-ADM-12` Notification template management.

### ETM & Ambiguous (AFR-ETM)
- `AFR-ETM-1` External Transaction Management / Order-and-Pay (account/card payment segregation).
- `AFR-ETM-2` Order reversal / cancel (default 20 days) across order services.
- `AFR-ETM-3` Guest merchant payment (card not stored).
- `AFR-ETM-4` Auto ambiguous settlement (payments/provisioning/refunds; retry/rollback/roll-forward).
- `AFR-ETM-5` Bulk ambiguous handling.
- `AFR-ETM-6` Ambiguous-transaction zone alerts (Green/Yellow/Red).
- `AFR-ETM-7` Error-message management (numeric codes, multi-language, bearer-specific).

### Notifications & Growth (AFR-NTF)
- `AFR-NTF-1` Multi-channel notifications (SMS/Email/FCM Push/URL-USSD).
- `AFR-NTF-2` Notification endpoints (user + account-group precedence).
- `AFR-NTF-3` Scheduled-payment advance reminders.
- `AFR-NTF-4` Notification inbox (central, purge 3m–1y).
- `AFR-NTF-5` Priority-based notifications.
- `AFR-NTF-6` Referral management (code generation/validation/reward via Pricing Engine).

### Churn (AFR-CHN)
- `AFR-CHN-1` Churn account (IND10) + churn-suspense (IND11) sweep to Central bank.
- `AFR-CHN-2` Churn settlement (to registered user or bank reimbursement; workflow).
- `AFR-CHN-3` Business-user churn with hierarchy (2-step).

### USSD (AFR-USSD)
- `AFR-USSD-1` USSD Menu Manager GUI (maker/checker, versioning, rollback, applicable date).
- `AFR-USSD-2` Static + dynamic choice lists; gather input with validations.
- `AFR-USSD-3` Intermediate + terminal service association; parameterized text.
- `AFR-USSD-4` Multi-language menus; long-code handling.

### Reports & Observability (AFR-RPT)
- `AFR-RPT-1` Pre-configured reporting suite (20 OOB reports; sync/async/cron).
- `AFR-RPT-2` Live (Kafka Connect / materialized-view) vs hourly reports.
- `AFR-RPT-3` Export formats XLSX/CSV/PDF; retention/thresholds configurable.
- `AFR-RPT-4` Monitoring & Alerting (Kibana/Elasticsearch, Grafana/Prometheus, APM).

### Africell-Specific / Change Requests (AFR-CR)
- `AFR-CR-1` Bonus wallet (§20.1) — **TOC-only, un-specified in v0.1**.
- `AFR-CR-2` KPI Report (§20.2) — **TOC-only, un-specified in v0.1**.
- `AFR-CR-3` Multi-market deployment (SL + DRC) with per-market config.
- `AFR-CR-4` Multi-language (English/French/Arabic) for DRC/SL.
- `AFR-CR-5` Central-Bank-aligned regulatory profiles and churn-to-central-bank sweep.

---

## Overlap & Deltas vs mobiquity Pay

**Assessment:** This document is, in substance, the standard **Comviva mobiquity Pay FSD**, re-badged as "Africell Upgrade V12.0.0". The platform description, component model, system accounts (IND-series), UMS/TCP/Pricing-Engine/ETM/USSD/Reports chapters, and virtually every business rule are generic product documentation. The author explicitly states (§1.1) the document illustrates "the mobiquity Pay product version 11.1.0", and the upgrade reuses the existing live v5.0 configuration set.

**Standard mobiquity Pay features (platform OOB, not Africell-specific):**
- Entire actor/workspace/hierarchy model (§4–§5), UMS, Authorization & Approval Management (§5, §9, §10).
- Pricing Engine, TCP, Transfer Rules, Domain/Category/Grade, Bank Master, Account/Account-Group management (§6–§8).
- All transaction families: B2B/recall, cash-in/out, P2P, merchant payment, recharge, bulk salary/bill, ETM/Order-and-Pay, B2A/A2B, Card-to-Account, IMT (§10–§14).
- Bill payment, standing instructions, split, funds transfer, enquiry/reconciliation, ambiguous handling, notifications, referral, churn, USSD Menu Manager, reporting suite (§11–§19).
- The bulk-services table explicitly marks items "OOB/Delivered".

**Africell-specific or deployment-tailored (discernible deltas):**
- **Market scope:** SL + DRC deployments; audience is Africell SL/DRC teams.
- **Regulatory binding:** Regulatory profiles "as per the regulations of the Central Bank of the country"; churn-suspense (IND11) sweep to Central bank; blacklist from government/regulator lists.
- **Language set limited to English/French/Arabic** (fits DRC French + regional needs).
- **Change Requests §20 (SL):** **Bonus wallet** and **KPI Report** — the only named Africell customisations, but un-specified in this draft (TOC-only). These are the clearest intended deltas pending detail.
- **Config reuse:** Existing v5.0 profiles/grades/relations/TCP/pricing/hierarchy carried forward (migration via Import/Export utilities), implying Africell-specific values exist in attached workbooks rather than in narrative.
- **Version target:** Upgrade to V12.0.0 (from a v5.0 live base) — the "upgrade" framing itself.

**Not determinable from this draft:** concrete Africell tariffs, exact currency codes (SLE/CDF etc.), specific USSD dial strings/short codes, and the detailed Bonus-wallet/KPI-report rules — all deferred to attachments, the user manual, or later FSD revisions.
