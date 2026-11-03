# Meridian Core — Editions & mobiquity Pay Alignment

> **Status:** Draft v0.1 for review · **Date:** 2026-11-03 · **Owner:** Platform
> **Purpose:** Define the two commercial editions of Meridian Core (one codebase, two deployments), map the Comviva mobiquity Pay 12.0 feature set against the current platform, and scope a demo-ready **Wallet Edition MVP** that can be put in front of a mobile-money / fintech client.
>
> **Source inputs:**
> - `docs/research/MOBIQUITY_PAY_V12_FDD.md` — Comviva mobiquity Pay 12.0 FDD, 301 capabilities (`MQ-*`).
> - `docs/research/AFRICELL_BASE_FSD.md` — Africell Upgrade v12 FSD (SL + DRC), 134 capabilities (`AFR-*`); in substance the same mobiquity Pay FDD, re-badged.
> - `docs/research/WALLETS_PLATFORM_COVERAGE.md` — prior benchmark of Meridian vs 12 vendors incl. mobiquity.
> - `docs/SERVICES.md`, `spec/plan.yaml`, `spec/apps.yaml`, `generated/services.json` — current platform (67 services).

---

## 1. Executive summary

1. **The platform keeps its name: Meridian Core.** All code is `io.meridian.*`; renaming would be costly and buys nothing. We package **two editions off the one codebase**, selected by a *deployment profile* (service set + feature flags + tenant starter pack + channel/app set + Helm values). No fork, no divergent branch.

2. **Two editions, one code, different go-to-market:**
   - **Meridian Core — Banking Edition** → banks and larger fintechs. Full core: deposits, cards, lending, treasury, open banking, payments hub, regulatory packs.
   - **Meridian Core — Wallet Edition** → MNOs, mobile-money operators and smaller fintechs. The mobiquity-equivalent: wallets, agent & distributor hierarchy, merchants, billers, USSD, cash-in/out, e-money float, commissions. Optional add-ons (cards, lending) switch on per client.

3. **The Wallet Edition is ~70% already present.** The wallet/agent/merchant/biller/interop/disbursement/channel services exist and are marked done; the app surface already exposes P2P quote/confirm, alias resolve, QR pay, request-to-pay, bill pay and airtime. Reaching a credible mobiquity-equivalent MVP is mainly: **(a)** a wallet-specific tenant starter pack (e-money chart of accounts + limits + fees + commissions), **(b)** a deployment profile, **(c)** closing ~12 targeted functional gaps, **(d)** a demo tenant + scripted journeys. It is **not** a rebuild.

4. **mobiquity coverage today (by module group, 25 groups):** ~11 **Covered**, ~11 **Partial**, ~3 **Gap**. Detailed map in §5; prioritized gap register in §6; MVP scope in §7; backlog (`WE-*` tasks for `spec/plan.yaml`) in §8.

---

## 2. Product & edition model

### 2.1 Naming

| Layer | Name | Notes |
|---|---|---|
| Platform / codebase | **Meridian Core** | Unchanged. `io.meridian.*`, one monorepo, one CI. |
| Edition A (go-to-market) | **Meridian Core — Banking Edition** | Target: banks, large/licensed fintechs, BaaS sponsors. |
| Edition B (go-to-market) | **Meridian Core — Wallet Edition** | Target: MNOs, mobile money, agent-network operators, smaller fintechs. The competitor to mobiquity Pay. |
| (Optional) Wallet sub-brand | *e.g.* "Meridian Flow" / "Meridian Pulse" | Only if sales wants a distinct wallet brand; **not required**. Recommendation: keep `Wallet Edition` until there is a sales reason to brand it. **← open question Q1.** |

Both editions ship from the same artifact registry (same container images, same Helm chart). An edition is a *values profile*, not a build.

### 2.2 Who gets what (positioning)

| Dimension | Banking Edition | Wallet Edition |
|---|---|---|
| Primary buyer | Bank / licensed fintech | MNO / mobile-money operator / small fintech |
| Money model | DDA/savings/term, GL, cards, loans | Stored-value e-money ("float"), trust-backed |
| Hero journeys | Account opening, payments, cards, lending | Onboard → cash-in → P2P → bill/airtime → merchant pay → cash-out |
| Channels | Web, mobile app, API, open banking | **USSD + app + agent app** first; web/API |
| Distribution | Branch / digital | **Agent & distributor hierarchy**, merchants |
| Regulatory | Banking returns (CBK/CBN/PRA packs) | E-money / central-bank wallet returns, trust reconciliation |
| Deployment | Multi-region cells, high TPS | Single/dual cell, dedicated or self-hosted; license-gated |

---

## 3. How an edition is realized (technical mechanism)

An **Edition = a named deployment profile** composed of five existing levers. Nothing here forks the code.

| # | Lever | Where it lives today | What the edition sets |
|---|---|---|---|
| 1 | **Service enablement set** | `generated/services.json` (has `domain`, `phase`), Helm cell chart (`PLT-HELM-CELL`), `deploy/base/meridian.yaml` | Which of the 67 services are deployed/scaled. Add an `editions: [banking, wallet]` tag per service in the spec; generator emits per-edition Helm values + ArgoCD ApplicationSet selectors. |
| 2 | **Feature flags / config** | `config-service` (per-tenant namespaces already used for ledger routing) | Edition-level defaults: enable USSD menu, e-money mode, unregistered/voucher flows, cards on/off, etc. |
| 3 | **Tenant starter pack** | `tenant-service` StarterPack API + `PLT-STARTER-PACKS` (generic + KE packs exist) | **New `wallet` starter pack**: e-money chart of accounts (trust/suspense/churn/unclaimed/rewards/tax), KYC-tier limits, wallet fee + commission rules, biller catalogue seed, EOD pack, approval policies. |
| 4 | **Channel / app set** | `spec/apps.yaml` (apps have `phase`), BFFs: customer/agent/business/merchant channels | Which front-ends ship. Wallet Edition foregrounds agent-app + USSD + customer-app; Banking Edition foregrounds console + web banking + business portal. |
| 5 | **Helm values + license** | `deploy/{dev,staging,prod}` cells, `PLT-GITOPS` | `values-banking.yaml` / `values-wallet.yaml`; resource sizing; `MQ-OPS-3` software-license gate (new) for self-hosted Wallet Edition clients. |

**Spec change to introduce:** add an `editions` attribute to each service in `spec/domains/*.yaml` and to each app in `spec/apps.yaml`; teach `tools/generate.py` / `tools/helm_chart.py` to emit `values-<edition>.yaml` and an ArgoCD ApplicationSet per edition. This is decision **D-ED-01** (see §9) and task **WE-PKG-1** (§8).

### 3.1 Edition → service matrix (indicative)

**Shared platform core (both editions, always on):** tenant, identity, config, reference-data, document, workflow, notification, webhook, audit, billing, search, archive, business-day, file-gateway, product-catalog, pricing-rules, ledger, gl, reconciliation, customer, onboarding, kyc, fraud, transaction-monitoring, sanctions, case-management, limits, analytics, ai-platform, regulatory-reporting, crm, campaign, rewards, insights, inbox, payment, routing, fx. *(39)*

| Service group | Banking Edition | Wallet Edition |
|---|---|---|
| deposit, statement | **On** | Optional (bank-linked accounts) |
| card-* (7 services) | **On** | Add-on (companion/virtual cards) |
| loan-origination, loan, collateral, collections | **On** | Add-on (nano-loans / overdraft) |
| treasury-deal, liquidity, alm | **On** | Off |
| openbanking | **On** | Optional |
| wallet, agent-network, commission, biller, merchant, disbursement, interop, channel | Optional | **On (core)** |
| customer-channel, agent-channel, business-channel, merchant-channel BFFs | console + business | **customer + agent + merchant** first |

The split is **default enablement**, not capability removal — any client can toggle add-ons. Same images, same APIs.

---

## 4. Reading the mobiquity feature set

mobiquity Pay 12.0 is a **component-based, multi-tenant, stored-value wallet core** with three workspaces (Administrators / Business / Customer), a configurable **Domain → Category → Grade** hierarchy, five profile types (Category / Security / Marketing / Regulatory / Authorization), a factory **system-account ledger** (the `IND*` series: trust, suspense, income, expense, rewards, churn, unclaimed, interest, tax), a **two-tier limit model** (cumulative KYC-level + instrument/grade), a **rule-based pricing engine** (service charge + commission + 3 taxes + cashback + telescopic), and a broad **financial-services catalogue** (stock/float, cash-in/out incl. unregistered-passcode and ATM, P2P incl. on-the-fly, merchant QR, bill/airtime, bulk salary, B2B, IMT, bank↔wallet, card-to-account), wrapped in **Order & Pay (ETM)** with ambiguous-case settlement, plus referral / churn / savings-circle / banner engagement, a self-service **USSD Menu Manager**, AI e-float optimizer + conversational analytics, and a reporting suite with schedulers.

Meridian already models most of these as first-class domains. The deltas are concentrated in: **the e-money/float accounting template, the configurable domain/category/grade + profile model, unregistered/voucher flows, the pricing-engine's telescopic & hierarchy-commission extras, ambiguous-case settlement, a few engagement modules (savings circle, banner), the USSD visual menu builder, and commercial packaging (license, config replication).**

---

## 5. Capability map — mobiquity Pay 12.0 → Meridian Core

Legend: **✅ Covered** (exists, demo-ready or near) · **🟡 Partial** (core exists; specific mobiquity behaviour missing) · **⛔ Gap** (not modelled). Codes reference `MQ-*` in the FDD catalogue.

| mobiquity module group | Meridian status | Where it lives | Notable deltas to close |
|---|---|---|---|
| Actors & workspaces (MQ-ACTOR) | 🟡 Partial | identity-service, 4 channel BFFs | 3-workspace naming over BFFs; Primary/Authorized-user concept on wallet accounts |
| Access channels (MQ-CHAN) | 🟡 Partial | channel-service (USSD/SMS), apps | STK/SIM-toolkit; configurable Txn-ID format; mobile-prefix normalization |
| System accounts / e-money ledger (MQ-ACCT) | 🟡 Partial | ledger, gl, starter packs | **IND-series e-money account template** (trust/suspense/churn/unclaimed/rewards/tax) as a wallet starter pack |
| User management & onboarding (MQ-UMS) | 🟡 Partial | customer, onboarding, kyc, identity | Account **groups**, payment handles (alias resolve exists), KYC-tier limits (tier upgrade exists); **5-profile model**, data redaction, SIM-swap, forgot-factor HB/LB journeys, govt blacklist bulk upload |
| Authorization/Approval/Workflow (MQ-AUTH/APPR/WF) | ✅ Covered | workflow-service, `libs/meridian-approvals`, audit-service | Amount-bracket & cumulative-bulk routing; assistant auth profiles (minor) |
| Audit trail (MQ-AUD) | ✅ Covered | audit-service (WORM seals) | — |
| Hierarchy / Domain / Category / Grade (MQ-HIER/DOM/GRADE) | 🟡 Partial | agent-network-service | **Generic configurable Domain→Category→Grade** with service preferences + un-registered grade (today: fixed agent levels) |
| Account & bank management (MQ-AG/ACM/BANK) | 🟡 Partial | deposit, wallet, payment | Account-group model; **trust/non-trust/partner bank master + pool account routing**; default bank/instrument per currency |
| Limits & transfer rules (MQ-TCP/TR) | 🟡 Partial | limits-service | **Two-tier TCP** (cumulative KYC-ID counters + instrument/grade); transfer-rule matrix; telescopic-compatible counters |
| Pricing engine (MQ-PE) | 🟡 Partial | pricing-rules-service, commission-service | **Telescopic charging**, joining commission, cash-in-to-inactive bonus, onsite/offsite (cell-ID/LAC), milestone charging, commission-sharing (Merchant→Aggregator→SP) |
| Stock / e-money float (MQ-STK) | 🟡 Partial | agent-network (float), commission | **Operator stock creation from trust, O2C allocation, auto-deallocation sweeps, reimbursement, unclaimed settlement**; freeze/unfreeze funds |
| Fin-services: business (MQ-FIN 1–15) | 🟡 Partial | agent, wallet, merchant, disbursement | ✅ cash-in/out, B2B, bulk salary, low-balance alert; **gaps:** cash-in-others (unregistered passcode), agent passcode cash-out, ATM cash-out, transfer-between-unregistered, auto-sweep, partial refund |
| Fin-services: customer (MQ-FIN 16–37) | ✅/🟡 | wallet, biller, payment, fx | ✅ P2P, alias, request-to-pay, QR merchant pay, bill, airtime, standing orders, own-account, IMT corridors; **gaps:** P2P on-the-fly/unregistered, split transaction, fund-and-pay |
| Banking / workspace-agnostic (MQ-BNK) | ✅ Covered | wallet (B2A/A2B), payment | Card-to-account 🟡; bank mini-statement 🟡 |
| External Txn Mgmt / Order & Pay (MQ-ETM) | 🟡 Partial | payment-service, card-vault | **Order/payment/fulfilment state segregation**, guest merchant pay, **ambiguous-case settlement** (retry/rollback/roll-forward + green/yellow/red alerts) |
| Correction / reversal / dispute (MQ-REV) | 🟡 Partial | ledger, payment, card-dispute, case-mgmt | Wallet **transaction-correction workflow** + **dispute tracker** for non-card wallet txns |
| Bulk operations (MQ-BULK) | 🟡 Partial | disbursement-service, file-gateway | Generic templatized bulk framework + **throttling / multi-threading** controls |
| Multi-tenant (MQ-MT) | ✅ Covered | tenant-service, cell-router | PAM/PAC/ITA is role naming over existing model |
| Notifications (MQ-NOT) | ✅ Covered | notification-service, inbox-service | Priority ordering + group-endpoint precedence (minor) |
| Referral (MQ-REF) | ✅ Covered | rewards-service | — |
| Churn (MQ-CHURN) | 🟡 Partial | deposit dormancy, wallet | **IND10/IND11 churn + churn-suspense sweep + churn settlement** workflow |
| Banner management (MQ-BAN) | 🟡 Partial | campaign-service | In-app banner sets, scheduling, approval |
| Savings circle / chama (MQ-SC) | ⛔ Gap | — | Group-savings sub-wallets, roles, request+approval withdrawals |
| USSD Menu Manager (MQ-USSD) | ⛔ Gap | channel-service (runtime only) | **Self-service visual menu builder** (maker/checker, versioning, simulation, live-flows) |
| AI e-float optimizer (MQ-AI-1/2) | ⛔ Gap | ai-platform-service (hosts models) | Float-prediction model + dashboard |
| Conversational analytics (MQ-AI-3) | 🟡 Partial | ai-platform, analytics | LLM NL→SQL over tenant data |
| Monitoring & observability (MQ-OBS) | ✅ Covered | Grafana LGTM, Prometheus | — |
| Event data pipeline (MQ-EDP) | ✅ Covered | Debezium/Kafka CDC, outbox, 97 event types | Field-removal-for-external config (minor) |
| Reporting & dashboards (MQ-RPT) | 🟡 Partial | regulatory-reporting, analytics, business-channel | Wallet **reporting suite** (OOB report set) + **email/SFTP schedulers** + **business KPI dashboard** (Float In/Out, commission, MTD vs LMTD) |
| Config import/export (MQ-OPS-1/2) | 🟡 Partial | starter packs | **UAT↔Prod config replication** utility (encrypted export, maker-checker) |
| Software license mgmt (MQ-OPS-3) | ⛔ Gap | — | **License Enforcement Engine** for self-hosted Wallet Edition clients |
| Error messages mgmt (MQ-OPS-4) | 🟡 Partial | `meridian-common` (RFC 9457) | Numeric-coded, multi-language, bearer-contextual catalogue |

Plus the GSMA/Daraja/MTN **API-partner patterns** from the prior benchmark (`WALLETS_PLATFORM_COVERAGE.md` top-20): per-request callback URLs, harmonized wallet error catalogue, C2B validation/confirmation URLs, authorisation codes / cardless cash-out, e-money safeguarding reconciliation, international remittance KYC snapshot (travel rule).

---

## 6. Prioritized gap register (Wallet Edition)

Priority: **M** = MVP (needed to demo a credible mobiquity-equivalent) · **S** = Should (fast-follow, pre-GA) · **C** = Could (post-GA / per-client).

| ID | Gap | mobiquity ref | Priority | Primary service(s) |
|---|---|---|---|---|
| G-01 | E-money system-account template (trust/suspense/churn/unclaimed/rewards/tax) as **wallet starter pack** | MQ-ACCT-* | **M** | tenant (starter pack), ledger, gl |
| G-02 | Operator **stock/float**: creation from trust, O2C allocation, auto-deallocation sweeps, reimbursement, unclaimed settlement | MQ-STK-1..14 | **M** | agent-network, ledger, commission |
| G-03 | **Unregistered / voucher flows**: cash-in-others, P2P on-the-fly, transfer-between-unregistered, passcode issue/claim, suspense rollback scheduler | MQ-FIN-4/17/14, MQ-ACCT-3 | **M** | wallet, agent-network |
| G-04 | **Two-tier TCP** (cumulative KYC-ID counters + instrument/grade) + transfer-rule matrix | MQ-TCP-*, MQ-TR-* | **M** | limits-service |
| G-05 | Pricing-engine extensions: telescopic charging, joining commission, cash-in-to-inactive bonus, **commission sharing**, onsite/offsite, milestone | MQ-PE-7/11/13/14/16/18 | **M** | pricing-rules, commission |
| G-06 | Configurable **Domain→Category→Grade** + un-registered grade (generalize agent hierarchy) | MQ-DOM/GRADE, MQ-HIER | **M** | agent-network, config |
| G-07 | **Agent/ATM cash-out**: agent passcode cash-out, ATM OTP cash-out (+ auto-rollback), authorisation codes (cardless) | MQ-FIN-6/7, GSMA authcodes | **M** | agent-network, wallet |
| G-08 | **Order & Pay (ETM)** segregation + **ambiguous-case settlement** (retry/rollback/roll-forward, green/yellow/red alerts) | MQ-ETM-1/8/9/10/13 | **M** | payment-service |
| G-09 | Wallet **transaction-correction + dispute tracker** (non-card) | MQ-REV-1/6 | **M** | case-management, wallet |
| G-10 | **Trust / e-money safeguarding reconciliation** (EOD stock recon A–(B+C+D+E) + mismatch alarm) | MQ-ENQ-9, safeguarding | **M** | reconciliation, wallet |
| G-11 | **USSD Menu Manager** — self-service visual builder (maker/checker, versioning, simulation, live-flows) | MQ-USSD-1..10 | **S** | channel-service (+ new admin UI) |
| G-12 | Business **KPI dashboard** + wallet **reporting suite** + email/SFTP schedulers | MQ-RPT-1..5 | **S** | business-channel, regulatory-reporting, analytics |
| G-13 | **Churn management** (IND10/IND11 sweep + churn settlement workflow) | MQ-CHURN-1/2/3 | **S** | wallet, deposit (dormancy) |
| G-14 | **Split transaction**, **fund-and-pay**, **auto-sweep**, wallet standing instructions | MQ-FIN-30/35/10/31 | **S** | wallet, payment |
| G-15 | 5-profile UMS model (category/security/marketing/regulatory/authorization) + data redaction + SIM-swap + govt blacklist | MQ-UMS-9..12/18/19/34 | **S** | identity, customer, kyc |
| G-16 | Trust/non-trust/partner **bank master + pool routing**; card-to-account | MQ-BANK-1, MQ-BNK-6 | **S** | payment, wallet |
| G-17 | Harmonized **wallet error catalogue** + **per-request callback URLs** + C2B validation/confirmation URLs | GSMA/Daraja/MTN | **S** | wallet, webhook, edge-gateway |
| G-18 | **Savings circle / chama** | MQ-SC-1..6 | **C** | wallet (new sub-domain) |
| G-19 | **Banner management** | MQ-BAN-1 | **C** | campaign |
| G-20 | **AI e-float optimizer** + conversational analytics | MQ-AI-1/2/3 | **C** | ai-platform, analytics |
| G-21 | **Software license mgmt (LEE)** for self-hosted clients | MQ-OPS-3 | **C** | new platform concern |
| G-22 | **Config UAT↔Prod replication** utility | MQ-OPS-2 | **C** | tenant, config |

---

## 7. Wallet Edition MVP — definition of "demo-ready"

**Goal:** a single demo tenant on the Wallet Edition profile that runs the full mobile-money lifecycle on **USSD + customer app + agent app + back-office console**, good enough to present to an MNO/fintech prospect as a mobiquity Pay alternative.

**MVP = current covered capabilities + gaps G-01 … G-10** (the ten **M** items). Everything else (G-11+) is fast-follow.

### 7.1 Demo journeys (the script)
1. **Operator setup:** provision tenant via **wallet starter pack** (e-money chart, tiers, fees, commissions, biller seed) — G-01.
2. **Float supply chain:** operator creates stock from trust → allocates float to a distributor → distributor to agent (O2C) — G-02.
3. **Customer onboarding:** self-register on USSD at Tier-0, upgrade KYC in app to Tier-2 (limits change via TCP) — existing + G-04.
4. **Cash-in:** agent deposits cash to customer wallet; commission accrues to agent (telescopic/joining) — existing + G-05.
5. **Cash-in to unregistered → claim:** send to a non-subscriber via passcode; recipient registers and claims (or agent cash-out) — G-03.
6. **P2P:** registered P2P and on-the-fly P2P; alias/handle send — existing + G-03.
7. **Bill & airtime:** pay a biller and buy airtime from app and USSD — existing.
8. **Merchant pay:** scan static/dynamic QR; merchant settlement + commission sharing — existing + G-05.
9. **Cash-out:** customer cash-out at agent via passcode; ATM OTP cash-out with auto-rollback demo — G-07.
10. **Order & Pay resilience:** force a fulfilment timeout → ambiguous settlement auto-resolves (roll-forward/rollback) — G-08.
11. **Ops:** reverse a mis-send via transaction correction + dispute tracker in console — G-09.
12. **Close of day:** EOD trust/e-money safeguarding reconciliation report with a clean A–(B+C+D+E) balance — G-10.

### 7.2 MVP exit criteria
- Wallet Edition deploy profile + `values-wallet.yaml` boots the wallet service set in a cell.
- Demo tenant seeded idempotently from the wallet starter pack.
- All 12 journeys pass as **live end-to-end** (extend `tools/e2e/journeys.py`).
- Back-office console shows wallet ops, agent network, float, approvals, reconciliation.
- Build green; ledger G1 load unchanged.

---

## 8. Backlog to fold into `spec/plan.yaml`

New step and `WE-*` tasks (to be appended to `spec/plan.yaml` `tasks:` and traced into the tracker via `python tools/generate.py`). Waves ordered for the MVP.

**Step:** `S23 — Wallet Edition (mobiquity-equivalent) packaging + MVP` (phase 2).

**Wave 0 — packaging (unblocks everything):**
- `WE-PKG-1` Add `editions` attribute to services (`spec/domains/*.yaml`) and apps (`spec/apps.yaml`); generator emits `values-banking.yaml` / `values-wallet.yaml` + per-edition ArgoCD ApplicationSet. *(D-ED-01)*
- `WE-PKG-2` **Wallet starter pack** (`tenant-service`): e-money chart of accounts (IND-series mapping), KYC-tier limits, wallet fee + commission rules, biller seed, EOD pack, approval policies — idempotent/resumable. *(G-01)*
- `WE-PKG-3` Wallet Edition demo tenant + seed script + README.

**Wave 1 — e-money core (M):**
- `WE-FLOAT-1` Operator stock/float: creation-from-trust, O2C allocation, auto-deallocation sweep, reimbursement, unclaimed settlement. *(G-02)*
- `WE-LIMIT-1` Two-tier TCP (cumulative KYC-ID + instrument/grade) + transfer-rule matrix. *(G-04)*
- `WE-PRICE-1` Pricing-engine extensions: telescopic, joining commission, cash-in-to-inactive, commission-sharing, onsite/offsite, milestone. *(G-05)*
- `WE-HIER-1` Configurable Domain→Category→Grade + un-registered grade over agent-network. *(G-06)*

**Wave 2 — transactions (M):**
- `WE-TXN-1` Unregistered/voucher flows: cash-in-others, P2P on-the-fly, transfer-between-unregistered, passcode issue/claim, suspense rollback scheduler. *(G-03)*
- `WE-TXN-2` Agent/ATM cash-out + authorisation codes (cardless). *(G-07)*
- `WE-ETM-1` Order & Pay segregation + ambiguous-case settlement + green/yellow/red alerts. *(G-08)*
- `WE-OPS-1` Wallet transaction-correction + dispute tracker. *(G-09)*
- `WE-RECON-1` Trust / e-money safeguarding EOD reconciliation + mismatch alarm. *(G-10)*

**Wave 3 — channels, ops & engagement (S):**
- `WE-USSD-1` USSD Menu Manager self-service builder. *(G-11)*
- `WE-RPT-1` Business KPI dashboard + wallet reporting suite + email/SFTP schedulers. *(G-12)*
- `WE-CHURN-1` Churn management (IND10/IND11 + settlement). *(G-13)*
- `WE-TXN-3` Split transaction, fund-and-pay, auto-sweep, wallet standing instructions. *(G-14)*
- `WE-UMS-1` 5-profile UMS model + data redaction + SIM-swap + govt blacklist. *(G-15)*
- `WE-BANK-1` Trust/non-trust/partner bank master + pool routing + card-to-account. *(G-16)*
- `WE-API-1` Harmonized wallet error catalogue + per-request callbacks + C2B validation/confirmation. *(G-17)*

**Wave 4 — differentiators (C):**
- `WE-SC-1` Savings circle / chama *(G-18)* · `WE-BAN-1` Banner mgmt *(G-19)* · `WE-AI-1` e-float optimizer + conversational analytics *(G-20)* · `WE-LIC-1` Software license mgmt *(G-21)* · `WE-CFG-1` Config UAT↔Prod replication *(G-22)*.

---

## 9. Decisions & open questions

**Decisions proposed (to record in `spec/plan.yaml` `decisions:`):**
- **D-ED-01** — Editions are deployment profiles over one codebase (service set + flags + starter pack + channel set + Helm values); no fork. Add `editions` attribute to spec.
- **D-ED-02** — Platform name stays **Meridian Core**; editions are **Banking Edition** and **Wallet Edition**.
- **D-ED-03** — Wallet Edition e-money accounting uses an **IND-series system-account template** mapped onto the existing double-entry ledger (no new ledger engine).
- **D-ED-04** — Reuse/extend existing services for wallet gaps rather than new services, except where a genuinely new concern exists (savings-circle sub-domain, license engine).

**Resolved with the user (2026-11-03):**
- **Q1 → Keep "Wallet Edition".** No separate wallet sub-brand for now.
- **Q2 → The wallet starter pack is country-neutral, configurable, English-first.** No country values are baked into the base pack. Country-specific values (currency, KYC-tier thresholds, USSD codes, tariffs/fees) are configuration, supplied per deployment as an optional **country overlay** layered on the generic pack (mirrors the existing `generic` + `KE` starter-pack pattern). Base/display currency and language are parameters, defaulting to a placeholder currency and English.
- **Q3 → Defer software license management (`WE-LIC-1`, G-21).** Not in early scope.
- **Q4 → Defer cards & nano-loans** from the Wallet MVP; ship as fast-follow add-ons.

---

## 10. Next actions

1. **(this session, proceeding)** Append `S23` + `WE-*` tasks and `D-ED-*` decisions to `spec/plan.yaml`; run `python tools/generate.py` to refresh the tracker.
2. Build **Wave 0** (packaging + wallet starter pack + demo tenant).
3. Build **Wave 1 → Wave 2** gap closures; extend `tools/e2e/journeys.py` with the 12 wallet journeys; validate live.
4. Review this doc together and resolve Q1–Q4.
