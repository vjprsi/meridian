import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** accounts.yaml enums for the deposit account sub-flows (term deposits, overdrafts, standing orders). */
export const TERM_DEPOSIT_STATUS = ["PENDING_FUNDING", "ACTIVE", "MATURED", "ROLLED_OVER", "BROKEN", "CLOSED", "CANCELLED"];
export const ROLLOVER_INSTRUCTION = ["ROLL_PRINCIPAL_AND_INTEREST", "ROLL_PRINCIPAL_PAY_INTEREST", "PAY_OUT_ALL"];
export const INTEREST_PAYOUT_MODE = ["AT_MATURITY", "MONTHLY_TO_ACCOUNT", "QUARTERLY_TO_ACCOUNT", "ANNUALLY_TO_ACCOUNT", "UPFRONT"];
export const OVERDRAFT_TYPE = ["ARRANGED", "UNARRANGED", "TEMPORARY", "SALARY_ADVANCE"];
export const OVERDRAFT_FACILITY_STATUS = ["PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "EXPIRED", "CANCELLED"];
export const SCHEDULE_FREQUENCY = ["ONCE", "DAILY", "WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL"];
export const STANDING_ORDER_STATUS = ["ACTIVE", "PAUSED", "COMPLETED", "CANCELLED", "FAILED_SUSPENDED"];
export const HOLIDAY_RULE = ["NEXT", "PREVIOUS", "SKIP"];

/* ============================================================ Term deposits */

/** Writable column set of accounts.term_deposit (ro / server-derived columns excluded). */
export const termDepositFormSections: FormSection[] = [
  {
    title: "Customer & product",
    description: "Who the deposit belongs to and the product it is placed on.",
    fields: [
      { key: "customer_id", label: "Customer", type: "text", required: true, span: 2 },
      { key: "product_id", label: "Product", type: "text", required: true },
      { key: "product_version_id", label: "Product version", type: "text", required: true },
    ],
  },
  {
    title: "Principal & term",
    fields: [
      { key: "principal_amount", label: "Principal amount", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "interest_rate", label: "Interest rate", type: "number", required: true, hint: "Contracted annual rate" },
      { key: "day_count", label: "Day count", type: "text", hint: "Default ACT_365", placeholder: "ACT_365" },
      { key: "term_days", label: "Term (days)", type: "number", required: true },
      { key: "start_date", label: "Start date", type: "date", required: true },
      { key: "maturity_date", label: "Maturity date", type: "date", required: true, hint: "Holiday-adjusted" },
    ],
  },
  {
    title: "Interest & rollover",
    fields: [
      { key: "interest_payout_mode", label: "Interest payout", type: "select", options: INTEREST_PAYOUT_MODE, hint: "Default AT_MATURITY" },
      { key: "rollover_instruction", label: "Rollover instruction", type: "select", options: ROLLOVER_INSTRUCTION, hint: "Default PAY_OUT_ALL" },
      { key: "premature_penalty_rate", label: "Premature penalty rate", type: "number", hint: "Rate reduction / penalty for breaking" },
      { key: "allow_partial_withdrawal", label: "Allow partial withdrawal", type: "bool", hint: "Partial withdrawals permitted" },
    ],
  },
  {
    title: "Settlement accounts",
    description: "Accounts the deposit is funded from and paid out to.",
    fields: [
      { key: "funding_account_id", label: "Funding account", type: "text", hint: "Account funded from" },
      { key: "payout_account_id", label: "Payout account", type: "text", hint: "Account receiving interest and principal" },
    ],
  },
  {
    title: "References",
    fields: [
      { key: "certificate_document_id", label: "Certificate document", type: "text" },
      { key: "previous_deposit_id", label: "Previous deposit", type: "text", hint: "Deposit this rolled over from (migration)" },
      { key: "metadata", label: "Metadata", type: "json", hint: "Client-defined key/value metadata" },
    ],
  },
];

type TD = Record<string, unknown>;

export function termDepositDetailSections(t: TD): DetailSection[] {
  const ccy = t.currency as string;
  return [
    {
      title: "Identity & references",
      rows: [
        { label: "Deposit number", value: t.deposit_number, kind: "mono" },
        { label: "Status", value: t.status, kind: "badge" },
        { label: "Customer", value: t.customer_id, kind: "mono" },
        { label: "Product", value: t.product_id, kind: "mono" },
        { label: "Product version", value: t.product_version_id, kind: "mono" },
        { label: "Rolled over from", value: t.previous_deposit_id, kind: "mono" },
        { label: "Certificate document", value: t.certificate_document_id, kind: "mono" },
      ],
    },
    {
      title: "Principal & term",
      rows: [
        { label: "Principal amount", value: t.principal_amount, kind: "money", currency: ccy },
        { label: "Currency", value: t.currency },
        { label: "Interest rate", value: t.interest_rate },
        { label: "Day count", value: t.day_count },
        { label: "Term (days)", value: t.term_days },
        { label: "Start date", value: t.start_date, kind: "date" },
        { label: "Maturity date", value: t.maturity_date, kind: "date" },
      ],
    },
    {
      title: "Interest & rollover",
      rows: [
        { label: "Interest payout mode", value: t.interest_payout_mode },
        { label: "Rollover instruction", value: t.rollover_instruction },
        { label: "Expected interest", value: t.expected_interest, kind: "money", currency: ccy },
        { label: "Accrued interest", value: t.accrued_interest, kind: "money", currency: ccy },
        { label: "Auto rollover count", value: t.auto_rollover_count },
        { label: "Premature penalty rate", value: t.premature_penalty_rate },
      ],
    },
    {
      title: "Settlement & lien",
      rows: [
        { label: "Funding account", value: t.funding_account_id, kind: "mono" },
        { label: "Payout account", value: t.payout_account_id, kind: "mono" },
        { label: "Ledger account", value: t.ledger_account_id, kind: "mono" },
        { label: "Allow partial withdrawal", value: t.allow_partial_withdrawal, kind: "bool" },
        { label: "Pledged as collateral", value: t.has_lien, kind: "bool" },
      ],
    },
  ];
}

/* ============================================================ Overdraft facilities */

/** Writable column set of accounts.overdraft_facility (ro / server-derived columns excluded). */
export const overdraftFormSections: FormSection[] = [
  {
    title: "Facility",
    description: "The account and kind of overdraft being arranged.",
    fields: [
      { key: "deposit_account_id", label: "Account", type: "text", required: true, span: 2 },
      { key: "overdraft_type", label: "Overdraft type", type: "select", options: OVERDRAFT_TYPE, required: true },
      { key: "limit_amount", label: "Approved limit", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
    ],
  },
  {
    title: "Pricing",
    fields: [
      { key: "interest_rate", label: "Interest rate", type: "number", hint: "Annual debit interest (empty = product rate)" },
      { key: "arrangement_fee", label: "Arrangement fee", type: "amount", hint: "One-off fee" },
      { key: "credit_decision_ref", label: "Credit decision ref", type: "text", span: 2, hint: "Credit decision / scoring reference" },
    ],
  },
  {
    title: "Term",
    fields: [
      { key: "starts_on", label: "Start date", type: "date", required: true },
      { key: "expires_on", label: "Expiry / review date", type: "date" },
    ],
  },
];

type OD = Record<string, unknown>;

export function overdraftDetailSections(o: OD): DetailSection[] {
  const ccy = o.currency as string;
  return [
    {
      title: "Facility",
      rows: [
        { label: "Type", value: o.overdraft_type, kind: "badge" },
        { label: "Status", value: o.status, kind: "badge" },
        { label: "Account", value: o.deposit_account_id, kind: "mono" },
        { label: "Currency", value: o.currency },
      ],
    },
    {
      title: "Limit & utilisation",
      rows: [
        { label: "Approved limit", value: o.limit_amount, kind: "money", currency: ccy },
        { label: "Utilised", value: o.utilised_amount, kind: "money", currency: ccy },
        { label: "Days in excess", value: o.days_in_excess },
        { label: "Interest rate", value: o.interest_rate },
        { label: "Arrangement fee", value: o.arrangement_fee, kind: "money", currency: ccy },
      ],
    },
    {
      title: "Approval & dates",
      rows: [
        { label: "Approved by", value: o.approved_by, kind: "mono" },
        { label: "Credit decision ref", value: o.credit_decision_ref, kind: "mono" },
        { label: "Start date", value: o.starts_on, kind: "date" },
        { label: "Expiry / review", value: o.expires_on, kind: "date" },
      ],
    },
  ];
}

/* ============================================================ Standing orders */

/** Writable column set of accounts.standing_order (ro / server-derived columns excluded). */
export const standingOrderFormSections: FormSection[] = [
  {
    title: "Debit & beneficiary",
    description: "The account debited and where each execution pays to.",
    fields: [
      { key: "deposit_account_id", label: "Debit account", type: "text", required: true, span: 2 },
      { key: "payee_id", label: "Saved payee", type: "text", hint: "External payee" },
      { key: "beneficiary_account_id", label: "Internal beneficiary account", type: "text", hint: "Internal transfer" },
    ],
  },
  {
    title: "Amount & reference",
    fields: [
      { key: "amount", label: "Amount per execution", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "reference", label: "Payment reference", type: "text" },
    ],
  },
  {
    title: "Schedule",
    fields: [
      { key: "frequency", label: "Frequency", type: "select", options: SCHEDULE_FREQUENCY, required: true },
      { key: "day_of_period", label: "Day of period", type: "number", hint: "Day of week / month" },
      { key: "start_date", label: "First execution date", type: "date", required: true },
      { key: "end_date", label: "Last execution date", type: "date" },
      { key: "max_executions", label: "Max executions", type: "number", hint: "Stop after this many" },
      { key: "holiday_rule", label: "Holiday rule", type: "select", options: HOLIDAY_RULE, hint: "Default NEXT" },
    ],
  },
  {
    title: "Advanced",
    fields: [
      { key: "retry_policy", label: "Retry policy", type: "json", hint: "Retry attempts and spacing on insufficient funds" },
    ],
  },
];

type SO = Record<string, unknown>;

export function standingOrderDetailSections(s: SO): DetailSection[] {
  const ccy = s.currency as string;
  return [
    {
      title: "Instruction",
      rows: [
        { label: "Status", value: s.status, kind: "badge" },
        { label: "Debit account", value: s.deposit_account_id, kind: "mono" },
        { label: "Saved payee", value: s.payee_id, kind: "mono" },
        { label: "Beneficiary account", value: s.beneficiary_account_id, kind: "mono" },
        { label: "Reference", value: s.reference },
      ],
    },
    {
      title: "Amount & schedule",
      rows: [
        { label: "Amount per execution", value: s.amount, kind: "money", currency: ccy },
        { label: "Currency", value: s.currency },
        { label: "Frequency", value: s.frequency },
        { label: "Day of period", value: s.day_of_period },
        { label: "Holiday rule", value: s.holiday_rule },
        { label: "First execution", value: s.start_date, kind: "date" },
        { label: "Last execution", value: s.end_date, kind: "date" },
      ],
    },
    {
      title: "Progress",
      rows: [
        { label: "Max executions", value: s.max_executions },
        { label: "Executions done", value: s.executions_done },
        { label: "Next execution", value: s.next_execution_date, kind: "date" },
        { label: "Consecutive failures", value: s.consecutive_failures },
      ],
    },
  ];
}
