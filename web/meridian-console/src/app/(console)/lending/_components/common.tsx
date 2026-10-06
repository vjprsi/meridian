"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Badge, Money } from "@/components/ui";
import type { Row } from "@/components/ops";

/** Enumerations from the lending contracts (loan-origination, loan and collections services). */
export const APPLICATION_STATUSES = ["DRAFT", "SUBMITTED", "IN_REVIEW", "BUREAU_PENDING", "SCORED", "REFERRED", "APPROVED",
  "COUNTER_OFFERED", "DECLINED", "OFFER_ACCEPTED", "SIGNED", "BOOKED", "DISBURSED", "WITHDRAWN", "EXPIRED", "CANCELLED"] as const;
export const LOAN_TYPES = ["INSTALMENT", "BNPL", "NANO", "MERCHANT_FINANCING", "MORTGAGE", "VEHICLE", "OVERDRAFT_TERM", "SME_TERM"] as const;
export const CHANNELS = ["MOBILE", "WEB", "BRANCH", "AGENT", "API", "USSD", "MERCHANT_POS"] as const;
export const LOAN_STATUSES = ["PENDING_DISBURSEMENT", "ACTIVE", "IN_ARREARS", "RESTRUCTURED", "PAID_OFF", "CLOSED", "WRITTEN_OFF",
  "CHARGED_OFF", "CANCELLED"] as const;
export const BUCKETS = ["CURRENT", "DPD_1_29", "DPD_30_59", "DPD_60_89", "DPD_90_119", "DPD_120_179", "DPD_180_PLUS"] as const;
export const ECL_STAGES = ["STAGE_1", "STAGE_2", "STAGE_3", "POCI"] as const;
export const FREQUENCIES = ["DAILY", "WEEKLY", "FORTNIGHTLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL", "AT_MATURITY"] as const;
export const REPAYMENT_SOURCES = ["MANUAL", "AUTO_DEBIT", "WALLET", "CARD", "EXTERNAL_TRANSFER", "CASH", "COLLATERAL_LIQUIDATION",
  "RECOVERY", "PAYROLL"] as const;
export const DISBURSE_METHODS = ["DEPOSIT_ACCOUNT", "WALLET", "EXTERNAL_TRANSFER", "MERCHANT_SETTLEMENT", "CASH", "CARD"] as const;
export const RESTRUCTURE_TYPES = ["RESCHEDULE", "REFINANCE", "PAYMENT_HOLIDAY", "TERM_EXTENSION", "RATE_REDUCTION", "CAPITALISATION",
  "SETTLEMENT"] as const;
export const CASE_STATUSES = ["OPEN", "IN_PROGRESS", "PROMISE_ACTIVE", "PLAN_ACTIVE", "LEGAL", "RESOLVED", "CLOSED", "WRITTEN_OFF"] as const;
export const EXPOSURE_TYPES = ["LOAN", "CREDIT_LINE", "CARD", "OVERDRAFT", "BNPL"] as const;
export const CONTACT_ACTIONS = ["CALL", "SMS", "PUSH", "EMAIL", "WHATSAPP", "FIELD_VISIT", "LETTER", "LEGAL_NOTICE", "AUTO_DEBIT_RETRY",
  "SKIP_TRACE"] as const;
export const CONTACT_OUTCOMES = ["RIGHT_PARTY_CONTACT", "NO_ANSWER", "WRONG_PARTY", "PROMISE_MADE", "REFUSED", "PAID", "DISPUTED", "SENT",
  "DELIVERED", "FAILED", "SUPPRESSED"] as const;
export const RESOLUTIONS = ["CURED", "PAID_OFF", "RESTRUCTURED", "SETTLED", "WRITTEN_OFF", "SOLD", "OTHER"] as const;
export const LEGAL_STATUSES = ["INITIATED", "FILED", "HEARING", "JUDGEMENT", "ENFORCEMENT", "SETTLED", "CLOSED"] as const;

/**
 * Builds an action body from dialog inputs: drops empty values and converts the listed integer / boolean fields
 * (dialog inputs are strings; decimals stay strings, as the contracts carry money as decimal strings).
 */
export function typed(ints: string[] = [], bools: string[] = [], extra: Record<string, unknown> = {}) {
  return (v: Record<string, string>) => {
    const out: Record<string, unknown> = { ...extra };
    for (const [k, val] of Object.entries(v)) {
      if (val === "") continue;
      out[k] = ints.includes(k) ? Number.parseInt(val, 10) : bools.includes(k) ? val === "true" : val;
    }
    return out;
  };
}

/** Days-past-due bucket chip: current is good, early buckets warn, 90+ bad. */
export function Bucket({ value }: { value?: unknown }) {
  const v = typeof value === "string" ? value : undefined;
  const tone = !v ? "muted" : v === "CURRENT" ? "good" : /DPD_(1_|30|60)/.test(v) ? "warn" : "bad";
  return <Badge value={v} tone={tone} />;
}

export function money(r: Row, amountKey: string, currencyKey = "currency") {
  return <Money amount={r[amountKey] as string | null | undefined} currency={r[currencyKey] as string | null | undefined} />;
}

export function CustomerLink({ id }: { id?: unknown }) {
  if (!id) return <span className="text-muted">—</span>;
  return <Link href={`/customers/${id}`} className="font-mono text-xs text-accent hover:underline">{String(id).slice(0, 8)}…</Link>;
}

export function Section({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-2">{children}</div>;
}
