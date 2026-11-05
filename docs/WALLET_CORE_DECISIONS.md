# Wallet Core — Pending Finance & Design Decisions (resolved)

Date: 2026-11-04. Owner: Meridian Wallet Edition. Status: **decided** (autonomous resolution,
grounded in the Comviva mobiquity Pay v12.0 FDD, the mobiquity alignment doc
`docs/EDITIONS_AND_MOBIQUITY_ALIGNMENT.md`, and external best practice — see Sources).

These close the FINANCE / design flags left open by WE-FLOAT-1, WE-PRICE-1, WE-TXN-2 and WE-ETM-1.
The governing principle for an e-money / mobile-money core is **safeguarding**: outstanding e-money is a
**customer liability** backed 1:1 by funds in a **trust (safeguarding) account**, reconciled every business
day. No decision below may let customer value leak into the issuer's income or capital except by a lawful,
audited escheatment to the regulator.

---

## D-ED-07 — Unclaimed e-money does NOT sweep to capital/income (stays a liability)

**Question (WE-FLOAT-1):** on expiry of unclaimed value, post `unclaimed -> capital GL(3000)` or hold in a
capital-pool wallet?

**Decision:** Neither. Unclaimed e-money **remains a liability** in `2720 / IND06 E-money unclaimed funds`
indefinitely until one of: (a) the owner is found and it is returned, or (b) a lawful **escheatment** to the
state/regulator per the tenant's jurisdiction (3–5 year dormancy windows are typical, with prior customer
notice). It is **never** recognised as issuer income or swept to share capital `3000`. The `IND01` capital-pool
→ `3000` mapping stays reserved strictly for the operator's **own** capital injections into float, not customer
value.

**Why:** unclaimed/dormant customer funds do not change ownership on dormancy; recognising them as income
would both misstate the P&L and breach safeguarding (the 1:1 backing must still cover them). Escheatment, where
it applies, moves them to a regulator account — not to the issuer.

**Wiring:** confirm no code path sweeps `2720 -> 3000`. Add an (default-off, per-tenant) **escheatment**
settlement action in `agent-network-service` / reconciliation that moves aged unclaimed to a configured
`gl.escheatment` account only when enabled; otherwise funds age in `2720` and are included in the safeguarding
reconciliation (D-ED-12).

## D-ED-08 — Auto-rollback of an unredeemed authorisation refunds fee + tax (service not rendered)

**Question (WE-TXN-2, also voucher WE-TXN-1):** when an ATM OTP / agent passcode / auth-code (or voucher)
expires unredeemed and is auto-rolled-back, keep the fee/tax (as the voucher MVP did) or refund it?

**Decision:** **Refund fee + tax** on automatic rollback of an **unredeemed** (never-delivered) authorisation —
i.e. return `total_debit_amount`, not just principal — because the chargeable service was never rendered. This
is gated by a per-tenant flag `refund-fee-on-expiry` (**default true**). A fee is only *earned* once the cash is
dispensed / the value is claimed. Manual reversals of a *completed* transaction follow the dispute outcome
(D-ED-10), not this rule.

**Why:** consumer-fairness and most DFS regulators require that a fee for an uncompleted service be returned;
retaining it on a system-driven expiry is a conduct risk. Voucher WE-TXN-1 is brought into line for consistency.

## D-ED-09 — Cardless ATM dispense settles suspense → a clearing GL (daily-settled), wallet path optional

**Question (WE-TXN-2):** should an ATM OTP dispense settle to a bank-clearing GL (GL→GL) or to a settlement
float wallet?

**Decision:** The **recommended** path is `suspense(2710) -> ATM settlement/clearing GL` (the reserved
`settlement_ledger_account_id`), a liability-side clearing account that is **settled daily** to the ATM
acquirer/provider via the safeguarding/settlement run and reconciled in WE-RECON-1. The **settlement float
wallet** path (`settlement_wallet_id`) remains a supported, per-tenant default for **agent-operated / branch**
cash-out where a wallet genuinely holds the counter-value. Config `atm-settlement-mode: CLEARING_GL | WALLET`
(default `WALLET` to preserve current behaviour; operators with a real ATM acquirer switch to `CLEARING_GL`).

**Why:** a true ATM/acquirer leg is money-in-transit to an external party and belongs in a clearing account
drained by daily settlement, matching the patent-described prefunded/settlement-account model; agent/branch cash
stays wallet-routed so every leg keeps a wallet counterparty and the engine's idempotency.

## D-ED-10 — Ambiguous Order & Pay roll-forward parks the unconfirmed amount in a recon suspense; RED auto-opens a repair case

**Question (WE-ETM-1):** (a) roll-forward's accounting when the payment may never have settled (residual
merchant exposure); (b) RED/needs-intervention → auto-open a `payment_repair` vs alert-only; (c) settled-order
rollback = recall vs direct refund.

**Decision:**
- (a) **Roll-forward** completes fulfilment and recognises the payment, but routes any **not-yet-confirmed**
  amount through a dedicated **ETM settlement-suspense** GL so the merchant exposure is a *tracked, reconciling*
  balance (cleared by WE-RECON-1 against the gateway/rail settlement file), never a silent write-off. If the
  rail later confirms failure, the compensating reversal clears the suspense.
- (b) A **RED** order **auto-opens a `payment_repair`** item (compensating workflow), in addition to the alert —
  the "unknown after threshold → manual reconciliation / compensating workflow" best practice. No new approval
  resource type; it reuses the existing repair/recall flow.
- (c) A **settled** order rollback uses the existing **camt.056 recall** (`ReturnService.recall`); an **in-flight**
  one uses `PaymentProcessor.cancel`. A direct refund posting is only for already-captured funds where recall is
  not applicable (configurable), keeping a single reversal path.

**Why:** treating a timeout as *unknown* (not failed), confirming via the status/heartbeat probe, forward-only
logged transitions, idempotency keys, and an age threshold that escalates to a compensating workflow is the
documented resilient-settlement pattern; parking the unconfirmed leg in a reconciling suspense keeps the ledger
honest about merchant exposure.

## D-ED-11 — Commission sharing (CP/CR) posts each share as its own commission_earning leg via payable wallets

**Question (WE-PRICE-1):** confirm the CP (Commission Payable / contribution) vs CR (Commission Receivable /
share) fund-flow for the Merchant → Aggregator → Service-Provider chain vs FDD §9/10.

**Decision:** A shared commission is decomposed by `CommissionEngine` into **one `commission_earning` row per
beneficiary** in the hierarchy (Merchant/Agent → Aggregator/Super-agent → Service Provider), each carrying its
`share_basis` (percentage or fixed of the parent commission) and all keyed to the originating transaction. Each
earning is **payable from the commission wallet** (`gl`/wallet commission routing already seeded) to the
beneficiary's wallet on the commission payout cycle — i.e. CP (the total charged/pooled) is split into CR legs
that sum to CP. No net-new money is created; the split is a partition of the already-charged commission pool.
Milestone/joining/telescopic earnings follow the same per-leg model.

**Why:** per-leg earnings give auditable, independently-reversible commission accounting and match the
hierarchy-commission model; a single blended posting would be unreconcilable per beneficiary.

## D-ED-12 — Safeguarding reconciliation is EOD, daily, `A − (B+C+D+E) = 0`, with typed breaks and a shortfall alarm

**Decision (WE-RECON-1):** Each business day the reconciliation-service computes **A − (B + C + D + E)** where
`A` = trust/safeguarding **bank balance**, and `B..E` = the outstanding e-money **liabilities**: customer wallet
liability `2700`, suspense `2710`, unclaimed `2720`, plus **agent/distributor float** (agent float is part of the
e-money liability). Target = **0** (100% backing). Differences are classified as **timing** (held, auto-clears
when the offsetting entry arrives), **amount/missing/duplicate** (escalated to an exception queue with owner,
priority, SLA countdown, resolution notes, audit trail), with **ageing** (fresh = normal; aged beyond N business
days = escalate). A **shortfall** (`A < liabilities`, i.e. result negative) raises a **critical safeguarding
alarm** (regulator-reportable). Reconciliation is itself immutable/audited.

**Why:** Kenya (daily by 16:00), MAS PSA, and FCA CASS 15 all require daily reconciliation of relevant funds /
e-money float to the trust account with documented break remediation; a shortfall is a safeguarding breach.

## D-ED-13 — Wallet transaction correction & dispute workflow (GSMA-aligned)

**Decision (WE-OPS-1):** Non-card wallet transactions get (1) a **correction workflow** — maker-checker initiated
reversal/correction for the GSMA-recognised reasons (**wrong recipient, duplicate, fraudulent/erroneous
deduction**), executing through the existing wallet `REVERSAL` path; and (2) a **dispute tracker** case type in
case-management — lifecycle `RAISED → UNDER_INVESTIGATION → RESOLVED | REJECTED | WITHDRAWN`, with reason codes,
an **evidence file** (transaction ids, receipts, counterparty), published **SLA timers**, and a **repeat-claimant
flag** for manual review. Outcomes drive the correction (or none). No bank-style auto-chargeback; the provider
initiates reversals under a written policy.

**Why:** the GSMA Code of Conduct requires documented reversal policies; disputes are evidence-decided (no
card-network chargeback on mobile money); clear criteria, timelines, and repeat-claim screening are the
recommended controls, and align with Financial-Ombudsman expectations on fair reversals.

## D-ED-14 — All new wallet-core behaviour stays additive, per-tenant gated, default = current behaviour

**Decision:** every item above ships behind a per-tenant config flag whose default preserves today's behaviour
(e.g. `atm-settlement-mode: WALLET`, escheatment off), except conduct-driven fixes that are safe-by-default
(`refund-fee-on-expiry: true`). Spec-first; regression gate = existing ITs + journeys. This keeps the Banking
Edition untouched and lets a Wallet-Edition tenant opt into the full safeguarding posture.

---

### Sources
- Safeguarding / daily reconciliation: CGAP *Technical Note: Safeguarding Funds* (2018); The Payments Association *CASS 15*; MAS PSA s.23; FCA safeguarding changes; Tufts/Greenacre *Safeguarding Funds Stored in Mobile Money Systems*.
- Unclaimed/dormant funds: general dormant-account/escheatment guidance (ownership does not transfer; 3–5y windows, prior notice).
- Ambiguous settlement: "treat API timeouts as unknown" state-machine guidance; gateway status-API-before-retry; forward-only logged transitions; age-threshold → manual/compensating workflow; idempotency keys.
- Disputes/reversals: GSMA Code of Conduct for Mobile Money Providers; UK Financial Ombudsman e-money services; mobile-money reversal-criteria analyses.
- Reconciliation breaks: nostro/trust reconciliation break typing (timing/fees/FX/missing/duplicate), ageing, exception-queue SLAs.
