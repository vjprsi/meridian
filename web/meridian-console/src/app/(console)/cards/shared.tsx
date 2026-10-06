"use client";

import type { Row } from "@/components/ops";

/** Enumerations from the card, card-authorization and card-dispute contracts (generated/openapi/cards.openapi.yaml). */
export const CARD_STATUSES = ["CREATED", "ACTIVE", "FROZEN", "BLOCKED", "LOST", "STOLEN", "EXPIRED", "CLOSED", "REPLACED"] as const;
export const FORM_FACTORS = ["VIRTUAL", "PHYSICAL"] as const;
export const SCHEMES = ["VISA", "MASTERCARD", "AMEX", "UNIONPAY", "JCB", "DISCOVER", "RUPAY", "ELO", "LOCAL"] as const;
export const FUNDING_MODELS = ["DEBIT", "PREPAID", "CREDIT", "JIT"] as const;
export const PROGRAM_TYPES = ["CONSUMER", "BUSINESS", "CORPORATE", "EXPENSE", "GIFT", "PAYROLL", "GOVERNMENT"] as const;
export const PROGRAM_STATUSES = ["DRAFT", "CERTIFICATION", "ACTIVE", "SUSPENDED", "RETIRED"] as const;
export const CHANNELS = ["ECOM", "POS", "ATM", "CONTACTLESS", "MOTO", "RECURRING", "ANY"] as const;
export const CONTROL_TYPES = ["MCC_ALLOW", "MCC_DENY", "MERCHANT_ALLOW", "MERCHANT_DENY", "COUNTRY_ALLOW", "COUNTRY_DENY",
  "CHANNEL_ALLOW", "CHANNEL_DENY", "AMOUNT_MAX", "TIME_WINDOW", "CURRENCY_ALLOW"] as const;
export const LIMIT_PERIODS = ["PER_TRANSACTION", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "LIFETIME"] as const;
export const REPLACE_REASONS = ["LOST", "STOLEN", "DAMAGED", "EXPIRING", "FRAUD", "NAME_CHANGE", "OTHER"] as const;
export const SHIPPING_METHODS = ["STANDARD", "EXPRESS", "COURIER", "BRANCH_PICKUP"] as const;
export const AUTH_STATUSES = ["PENDING", "APPROVED", "DECLINED", "REVERSED", "PARTIALLY_REVERSED", "EXPIRED", "CLEARED",
  "PARTIALLY_CLEARED"] as const;
export const AUTH_DECISIONS = ["APPROVED", "DECLINED", "PARTIAL"] as const;
export const DISPUTE_STATUSES = ["OPEN", "AWAITING_CUSTOMER", "AWAITING_MERCHANT", "SUBMITTED", "WON", "LOST", "WITHDRAWN",
  "EXPIRED"] as const;
export const DISPUTE_STAGES = ["INQUIRY", "RETRIEVAL", "FIRST_CHARGEBACK", "REPRESENTMENT", "PRE_ARBITRATION", "ARBITRATION",
  "CLOSED"] as const;
export const DISPUTE_CATEGORIES = ["FRAUD", "AUTHORIZATION", "PROCESSING_ERROR", "CONSUMER_DISPUTE", "NOT_RECEIVED",
  "NOT_AS_DESCRIBED", "DUPLICATE", "CANCELLED_RECURRING", "CREDIT_NOT_PROCESSED", "ATM_DISPUTE"] as const;
export const DISPUTE_OUTCOMES = ["WON", "LOST", "SPLIT", "WITHDRAWN", "EXPIRED"] as const;
export const EVIDENCE_TYPES = ["CUSTOMER_STATEMENT", "RECEIPT", "CORRESPONDENCE", "POLICE_REPORT", "DELIVERY_PROOF",
  "SCREENSHOT", "SCHEME_DOCUMENT", "OTHER"] as const;

/** Masked card number: the console never shows a full PAN, only BIN-less last four digits. */
export function masked(r: Row): string {
  return r.last4 ? `•••• ${String(r.last4)}` : "••••";
}

export function expiry(r: Row): string {
  if (!r.expiry_month || !r.expiry_year) return "—";
  return `${String(r.expiry_month).padStart(2, "0")}/${String(r.expiry_year).slice(-2)}`;
}

/** Drops empty dialog inputs and converts "true"/"false" selects into booleans. */
export function clean(v: Record<string, string>, bools: string[] = []): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, val] of Object.entries(v)) {
    if (val === "") continue;
    out[k] = bools.includes(k) ? val === "true" : val;
  }
  return out;
}

/** Comma/space separated list input -> string array. */
export function list(v: string | undefined): string[] | undefined {
  const items = (v ?? "").split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);
  return items.length ? items : undefined;
}
