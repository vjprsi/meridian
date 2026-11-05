# Full Back-Office — MovilRed Admin FSD parity plan

Date: 2026-11-04. Reference: `docs/Comviva_MovilRed_Functional Specification Document-Admin_v1.0_06062022 (1).docx`
(reviewed on the cloud build box), the mobiquity Pay FDD, and the existing `web/meridian-console` (Next.js 16 /
React 19, typed off the generated OpenAPI). Goal: the back office must cover **everything** MovilRed/mobiquity admin
does — and more, never less.

## Module mapping: MovilRed admin → Meridian backend → console UI

| MovilRed Admin module | Backend (API) | Console page today | Action |
|---|---|---|---|
| Account (mWallet) Management | wallet-service ✓ | wallets, wallets/[id] ✓ | extend (lifecycle, limits, barring) |
| Bank Management | accounts/treasury (partner/settlement banks) | — | add page |
| Preferences Management | config-service ✓ | — | add page |
| Geography Management | party/platform | — | add page |
| **Domain / Category / Grade** | agent-network (WE-HIER-1) ✓ | — | **add** (hierarchy admin) |
| **Transfer Rules / TCP** | limits-service (WE-LIMIT-1) ✓ | — | **add** (TCP + rule matrix) |
| System User Management | platform/party (operators, roles) | partial (approvals) | add admin-user page |
| Channel User Management | agent-network ✓ | wallets/agents ✓ | extend |
| Biller Management | payments/channels ✓ | — | add page |
| Recharge System Mgmt | payments (airtime) ✓ | — | add page |
| **Stock Management** (limit/create/approve/transfer-EA/reimburse/unclaimed/O2C/reward) | agent-network (WE-FLOAT-1 + escheatment) ✓ | — | **add** (the big one) |
| **C2C Transfer** | agent-network float transfer ✓ | — | add |
| **Commission disbursement / Clawback** | commission-service ✓ (clawback in wallets.yaml) | — | **add** |
| Bulk Payout Tool | payments bulk ✓ | wallets/disbursements (partial) | extend |
| Identical/Duplicate Transactions | — (gap) | — | add detection + page |
| Global Search | cross-service | — | add |
| Audit Trail | audit ✓ | audit ✓ | ✓ (extend scope) |
| Customer Care Portal (CCE) | customer/wallet/txn read APIs ✓ | — | **add** (support console) |
| **Transaction Correction** | case-management (WE-OPS-1) ✓ | — | **add** |
| System-Initiated Rollback | payment-service ETM / schedulers ✓ | — | add monitor |
| **Reconciliation (safeguarding)** | reconciliation-service (WE-RECON-1) ✓ | — (no recon section) | **add** (safeguarding dashboard + breaks) |

**Finding:** the *backend* already covers the MovilRed admin surface (the Wave 2 work was built to it). The real gap
is the **back-office web app** surfacing these wallet-admin modules, plus a few small backend additions.

## Backend gaps to close (small)
- **Duplicate / identical-transaction detection** ("Identical Transactions" in MovilRed) — add a detector + admin review.
- Confirm **Bank Management** (partner/settlement bank registry) and **Recharge** (vs airtime) naming parity; add thin
  specs where a distinct admin entity is expected.

## Back-office build waves (console, typed off the regenerated OpenAPI)
Prereq: regenerate `web/meridian-console/src/api/schema.d.ts` from the new `generated/openapi` (done on the cloud box).

1. **Wave A — safeguarding & money control:** Reconciliation (safeguarding `A−(B+C+D+E)`, breaks, shortfall alarm),
   Stock Management suite, Transaction Correction + Disputes, Commission disbursement/clawback.
2. **Wave B — configuration:** Domain/Category/Grade, Transfer Rules/TCP, Account/Bank/Preferences/Geography.
3. **Wave C — operations:** Vouchers, Cash-out authorisations, Order & Pay (ETM) monitor, C2C, Bulk payout, Biller/
   Recharge, System-initiated rollback monitor.
4. **Wave D — support & cross-cutting:** Customer Care (CCE) portal, Global Search, Audit extension, Identical-txn review.

## Environment
Built/tested on an AWS EC2 box (AL2023, r6i.2xlarge) because the local workstation has failing RAM (corrupts heavy
Python/Gradle). The box holds the regenerated, compiling (`BUILD SUCCESSFUL`) wallet-edition; the full test suite runs
there. The console will be schema-regenerated and `next build`-verified on the same box.

## Production-readiness (honest scope)
"Production-ready" for a 67-service banking core is a program, not a single pass. Delivered/か in-progress here:
code-complete wallet MVP + decisions + regen + green build + test run + back-office plan. Still a program beyond this (Delivered vs. in-progress tracked per wave):
full integration-test green across all services, security hardening + pen test, infra/GitOps deploy, data migration,
performance/soak, DR game day, and the back-office waves above. This plan is driven in waves, each merged + verified.
