import type { FormSection, FormValues } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/**
 * Treasury deal capture + detail field sets (kept in sync with spec/domains/treasury.yaml,
 * treasury-deal-service). Covers the three creatable deal families the deals page exposes:
 * money market deals, treasury FX deals (with legs) and securities trades. Read-only /
 * server-derived columns (deal_ref, status, accruals, MTM, journal postings, lifecycle
 * timestamps, four-eyes verifier) are excluded from the create forms and shown on detail.
 */

export type DealKind = "mm" | "fx" | "sec";

/** treasury.yaml enums. */
export const MM_DEAL_TYPE = ["PLACEMENT", "BORROWING", "CALL_PLACEMENT", "CALL_BORROWING", "REPO", "REVERSE_REPO", "CD_ISSUED", "CD_PURCHASED"];
export const FX_DEAL_TYPE = ["SPOT", "FORWARD", "SWAP", "NDF"];
export const FX_LEG_TYPE = ["NEAR", "FAR"];
export const RATE_TYPE = ["FIXED", "FLOATING"];
export const ROLLOVER_TYPE = ["NONE", "PRINCIPAL_ONLY", "PRINCIPAL_AND_INTEREST"];
export const DAY_COUNT = ["ACT_360", "ACT_365", "ACT_365L", "ACT_ACT_ISDA", "THIRTY_360", "THIRTY_E_360"];
export const BUY_SELL = ["BUY", "SELL"];
export const ACCOUNTING_CLASS = ["HTM", "AFS", "FVTPL", "AMORTISED_COST", "FVOCI"];

const isFloating = (v: FormValues) => v.rate_type === "FLOATING";
const isCall = (v: FormValues) => v.is_call === "true";
const isNdf = (v: FormValues) => v.deal_type === "NDF" || v.non_deliverable === "true";

/** Money market deal — writable columns of money_market_deal. */
export const moneyMarketDealFormSections: FormSection[] = [
  {
    title: "Deal & counterparty",
    description: "Who booked the deal, with whom and under which legal entity.",
    fields: [
      { key: "deal_type", label: "Deal type", type: "select", options: MM_DEAL_TYPE, required: true },
      { key: "legal_entity_id", label: "Booking legal entity", type: "text", required: true },
      { key: "treasury_counterparty_id", label: "Counterparty", type: "text", required: true, span: 2 },
      { key: "dealer_id", label: "Dealer", type: "text", required: true, hint: "Capturing dealer (app user)" },
    ],
  },
  {
    title: "Instrument & dates",
    fields: [
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "principal", label: "Principal", type: "amount", required: true },
      { key: "trade_date", label: "Trade date", type: "date", required: true },
      { key: "value_date", label: "Value (start) date", type: "date", required: true },
      { key: "is_call", label: "Call (open-ended) deal", type: "bool", hint: "No fixed maturity until called" },
      { key: "maturity_date", label: "Maturity date", type: "date", hint: "Required unless a call deal" },
      { key: "notice_days", label: "Notice period (days)", type: "number", when: isCall, hint: "Call deals" },
    ],
  },
  {
    title: "Amounts & rates",
    fields: [
      { key: "rate_type", label: "Rate type", type: "select", options: RATE_TYPE, required: true, hint: "Default FIXED" },
      { key: "interest_rate", label: "All-in rate (%)", type: "number", required: true, hint: "Annual, decimal" },
      { key: "rate_index", label: "Rate index", type: "text", when: isFloating, placeholder: "e.g. SOFR" },
      { key: "spread", label: "Spread over index (%)", type: "number", when: isFloating },
      { key: "day_count", label: "Day-count basis", type: "select", options: DAY_COUNT, required: true, hint: "Default ACT_360" },
    ],
  },
  {
    title: "Settlement & terms",
    fields: [
      { key: "rollover_type", label: "Rollover instruction", type: "select", options: ROLLOVER_TYPE, hint: "Applied at maturity; default NONE" },
      { key: "nostro_account_id", label: "Nostro account", type: "text", hint: "Defaults to the SSI nostro for the currency" },
      { key: "collateral_description", label: "Collateral (repo)", type: "textarea", placeholder: "Repo collateral details" },
    ],
  },
];

/** Treasury FX deal — writable columns of treasury_fx_deal plus its required legs. */
export const fxDealFormSections: FormSection[] = [
  {
    title: "Deal & counterparty",
    description: "Spot, forward, swap or NDF. An ISDA (and CSA for collateralised) must be in place for forwards, swaps and NDFs.",
    fields: [
      { key: "deal_type", label: "Deal type", type: "select", options: FX_DEAL_TYPE, required: true },
      { key: "trade_date", label: "Trade date", type: "date", required: true },
      { key: "legal_entity_id", label: "Booking legal entity", type: "text", required: true },
      { key: "treasury_counterparty_id", label: "Counterparty", type: "text", required: true },
      { key: "dealer_id", label: "Dealer", type: "text", required: true, hint: "Capturing dealer (app user)" },
    ],
  },
  {
    title: "NDF settlement",
    description: "Non-deliverable forwards net-settle in a single currency at the fixing.",
    fields: [
      { key: "non_deliverable", label: "Non-deliverable (NDF)", type: "bool", hint: "Always set for deal type NDF" },
      { key: "settlement_currency", label: "Settlement currency", type: "currency", when: isNdf },
      { key: "fixing_date", label: "Fixing date", type: "date", when: isNdf },
      { key: "fixing_source", label: "Fixing source", type: "text", when: isNdf, placeholder: "e.g. WMR, ECB" },
    ],
  },
  {
    title: "Legs",
    description: "One leg for spot / forward / NDF; NEAR and FAR legs for swaps. JSON array of leg objects.",
    fields: [
      { key: "legs", label: "Legs", type: "json", required: true,
        placeholder: '[{"leg_type":"NEAR","buy_sell":"BUY","base_currency":"EUR","base_amount":"1000000","quote_currency":"USD","deal_rate":"1.0850","value_date":"2026-11-10"}]',
        hint: "Per leg: leg_type, buy_sell, base_currency, base_amount, quote_currency, deal_rate, spot_rate, forward_points, value_date" },
    ],
  },
];

/** Securities trade — writable columns of securities_trade (currency, accrued, yield and holding are server-derived). */
export const securitiesTradeFormSections: FormSection[] = [
  {
    title: "Deal & counterparty",
    fields: [
      { key: "buy_sell", label: "Side", type: "select", options: BUY_SELL, required: true },
      { key: "legal_entity_id", label: "Booking legal entity", type: "text", required: true },
      { key: "treasury_counterparty_id", label: "Counterparty / broker", type: "text", required: true },
      { key: "dealer_id", label: "Dealer", type: "text", required: true, hint: "Capturing dealer (app user)" },
    ],
  },
  {
    title: "Security & book",
    fields: [
      { key: "treasury_security_id", label: "Security", type: "text", required: true, span: 2, hint: "Security master record" },
      { key: "portfolio", label: "Portfolio / book", type: "text", required: true },
      { key: "accounting_class", label: "Accounting class", type: "select", options: ACCOUNTING_CLASS, required: true },
    ],
  },
  {
    title: "Amounts & dates",
    fields: [
      { key: "nominal", label: "Nominal", type: "amount", required: true, hint: "Face value traded" },
      { key: "clean_price", label: "Clean price (% of nominal)", type: "number", required: true },
      { key: "trade_date", label: "Trade date", type: "date", required: true },
      { key: "settlement_date", label: "Settlement date", type: "date", required: true },
    ],
  },
];

/** Form sections per deal family (the deals page renders the set for the active tab). */
export const treasuryDealFormSections: Record<DealKind, FormSection[]> = {
  mm: moneyMarketDealFormSections,
  fx: fxDealFormSections,
  sec: securitiesTradeFormSections,
};

/** Create endpoint, labels and sensible defaults per deal family. */
export const treasuryDealCreate: Record<DealKind, { label: string; submitLabel: string; path: string; initial: FormValues }> = {
  mm: { label: "New money market deal", submitLabel: "Capture deal", path: "/v1/treasury/money-market-deals",
    initial: { rate_type: "FIXED", day_count: "ACT_360", rollover_type: "NONE", is_call: "false" } },
  fx: { label: "New FX deal", submitLabel: "Capture deal", path: "/v1/treasury/fx-deals",
    initial: { deal_type: "SPOT", non_deliverable: "false" } },
  sec: { label: "New securities trade", submitLabel: "Capture trade", path: "/v1/treasury/securities-trades",
    initial: { buy_sell: "BUY" } },
};

type D = Record<string, unknown>;

/** Infer the deal family from the record when the caller does not pass it explicitly. */
function inferKind(d: D): DealKind {
  if (d.buy_sell !== undefined || d.treasury_security_id !== undefined || d.nominal !== undefined) return "sec";
  if (d.legs !== undefined || d.non_deliverable !== undefined || FX_DEAL_TYPE.includes(String(d.deal_type))) return "fx";
  return "mm";
}

/** The full deal record grouped into labelled sections for the detail page. */
export function treasuryDealDetailSections(d: D, kind: DealKind = inferKind(d)): DetailSection[] {
  if (kind === "fx") {
    const mtmCcy = d.mtm_currency as string;
    const setCcy = d.settlement_currency as string;
    return [
      {
        title: "Deal & counterparty",
        rows: [
          { label: "Deal ref", value: d.deal_ref, kind: "mono" },
          { label: "Type", value: d.deal_type, kind: "badge" },
          { label: "Status", value: d.status, kind: "badge" },
          { label: "Trade date", value: d.trade_date, kind: "date" },
          { label: "Legal entity", value: d.legal_entity_id, kind: "mono" },
          { label: "Counterparty", value: d.treasury_counterparty_id, kind: "mono" },
          { label: "Dealer", value: d.dealer_id, kind: "mono" },
        ],
      },
      {
        title: "NDF settlement",
        hideWhenEmpty: true,
        rows: [
          { label: "Non-deliverable", value: d.non_deliverable, kind: "bool" },
          { label: "Settlement currency", value: d.settlement_currency },
          { label: "Fixing date", value: d.fixing_date, kind: "date" },
          { label: "Fixing source", value: d.fixing_source },
          { label: "Fixing rate", value: d.fixing_rate },
          { label: "NDF settlement amount", value: d.ndf_settlement_amount, kind: "money", currency: setCcy },
        ],
      },
      {
        title: "Valuation",
        rows: [
          { label: "Mark-to-market", value: d.mtm_value, kind: "money", currency: mtmCcy },
          { label: "MTM currency", value: d.mtm_currency },
          { label: "Valuation date", value: d.mtm_date, kind: "date" },
        ],
      },
      {
        title: "Lifecycle",
        rows: [
          { label: "Verified by", value: d.verified_by },
          { label: "Verified at", value: d.verified_at, kind: "datetime" },
          { label: "Confirmed at", value: d.confirmed_at, kind: "datetime" },
          { label: "Cancel reason", value: d.cancel_reason },
          { label: "Created", value: d.created_at, kind: "datetime" },
        ],
      },
    ];
  }
  if (kind === "sec") {
    const ccy = d.currency as string;
    return [
      {
        title: "Deal & counterparty",
        rows: [
          { label: "Deal ref", value: d.deal_ref, kind: "mono" },
          { label: "Side", value: d.buy_sell, kind: "badge" },
          { label: "Status", value: d.status, kind: "badge" },
          { label: "Legal entity", value: d.legal_entity_id, kind: "mono" },
          { label: "Counterparty", value: d.treasury_counterparty_id, kind: "mono" },
          { label: "Dealer", value: d.dealer_id, kind: "mono" },
        ],
      },
      {
        title: "Security & book",
        rows: [
          { label: "Security", value: d.treasury_security_id, kind: "mono" },
          { label: "Portfolio", value: d.portfolio },
          { label: "Accounting class", value: d.accounting_class, kind: "badge" },
          { label: "Holding", value: d.securities_holding_id, kind: "mono" },
        ],
      },
      {
        title: "Amounts & rates",
        rows: [
          { label: "Nominal", value: d.nominal, kind: "money", currency: ccy },
          { label: "Clean price", value: d.clean_price, kind: "pct" },
          { label: "Dirty price", value: d.dirty_price, kind: "pct" },
          { label: "Accrued interest", value: d.accrued_interest, kind: "money", currency: ccy },
          { label: "Settlement amount", value: d.settlement_amount, kind: "money", currency: ccy },
          { label: "Simple yield", value: d.simple_yield, kind: "pct" },
          { label: "Realised P&L", value: d.realised_pnl, kind: "money", currency: ccy },
        ],
      },
      {
        title: "Settlement & lifecycle",
        rows: [
          { label: "Currency", value: d.currency },
          { label: "Trade date", value: d.trade_date, kind: "date" },
          { label: "Settlement date", value: d.settlement_date, kind: "date" },
          { label: "Settled at", value: d.settled_at, kind: "datetime" },
          { label: "Journal entry", value: d.journal_entry_id, kind: "mono" },
          { label: "Cancel reason", value: d.cancel_reason },
          { label: "Created", value: d.created_at, kind: "datetime" },
        ],
      },
    ];
  }
  const ccy = d.currency as string;
  return [
    {
      title: "Deal & counterparty",
      rows: [
        { label: "Deal ref", value: d.deal_ref, kind: "mono" },
        { label: "Type", value: d.deal_type, kind: "badge" },
        { label: "Status", value: d.status, kind: "badge" },
        { label: "Legal entity", value: d.legal_entity_id, kind: "mono" },
        { label: "Counterparty", value: d.treasury_counterparty_id, kind: "mono" },
        { label: "Dealer", value: d.dealer_id, kind: "mono" },
      ],
    },
    {
      title: "Instrument & dates",
      rows: [
        { label: "Currency", value: d.currency },
        { label: "Principal", value: d.principal, kind: "money", currency: ccy },
        { label: "Trade date", value: d.trade_date, kind: "date" },
        { label: "Value date", value: d.value_date, kind: "date" },
        { label: "Maturity date", value: d.maturity_date, kind: "date" },
        { label: "Call deal", value: d.is_call, kind: "bool" },
        { label: "Notice days", value: d.notice_days },
      ],
    },
    {
      title: "Amounts & rates",
      rows: [
        { label: "Rate type", value: d.rate_type },
        { label: "All-in rate", value: d.interest_rate, kind: "pct" },
        { label: "Rate index", value: d.rate_index },
        { label: "Spread", value: d.spread, kind: "pct" },
        { label: "Day count", value: d.day_count },
        { label: "Interest amount", value: d.interest_amount, kind: "money", currency: ccy },
        { label: "Maturity amount", value: d.maturity_amount, kind: "money", currency: ccy },
        { label: "Accrued interest", value: d.accrued_interest, kind: "money", currency: ccy },
        { label: "Accrued through", value: d.accrued_through, kind: "date" },
      ],
    },
    {
      title: "Settlement & accounting",
      rows: [
        { label: "Rollover", value: d.rollover_type },
        { label: "Rolled from", value: d.rolled_from_deal_id, kind: "mono" },
        { label: "Rolled into", value: d.rolled_into_deal_id, kind: "mono" },
        { label: "Collateral", value: d.collateral_description },
        { label: "Nostro account", value: d.nostro_account_id, kind: "mono" },
        { label: "Ledger account", value: d.ledger_account_id, kind: "mono" },
        { label: "Settlement posting", value: d.settlement_journal_entry_id, kind: "mono" },
        { label: "Maturity posting", value: d.maturity_journal_entry_id, kind: "mono" },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Verified by", value: d.verified_by },
        { label: "Verified at", value: d.verified_at, kind: "datetime" },
        { label: "Confirmed at", value: d.confirmed_at, kind: "datetime" },
        { label: "Settled at", value: d.settled_at, kind: "datetime" },
        { label: "Matured at", value: d.matured_at, kind: "datetime" },
        { label: "Cancel reason", value: d.cancel_reason },
        { label: "Created", value: d.created_at, kind: "datetime" },
      ],
    },
  ];
}
