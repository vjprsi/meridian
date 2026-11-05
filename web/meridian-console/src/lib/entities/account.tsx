import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** accounts.yaml enums. */
export const ACCOUNT_TYPE = ["CURRENT", "SAVINGS", "CALL", "NOTICE", "MONEY_MARKET", "ESCROW", "BASIC", "STUDENT", "MINOR", "PAYROLL", "NOSTRO", "VOSTRO", "SUSPENSE", "SETTLEMENT"];
export const MANDATE_RULE = ["ANY_ONE", "ANY_TWO", "ALL", "PRIMARY_ONLY", "PRIMARY_PLUS_ONE", "CUSTOM"];
export const SCHEDULE_FREQUENCY = ["ONCE", "DAILY", "WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL"];

/** Full deposit-account opening field set (writable columns of accounts.deposit_account). */
export const accountFormSections: FormSection[] = [
  {
    title: "Classification & product",
    description: "What kind of account this is and the product it is opened on.",
    fields: [
      { key: "account_type", label: "Account type", type: "select", options: ACCOUNT_TYPE, required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "customer_id", label: "Primary customer", type: "text", required: true, span: 2 },
      { key: "product_id", label: "Product", type: "text", required: true },
      { key: "product_version_id", label: "Product version", type: "text", required: true },
      { key: "legal_entity_id", label: "Booking legal entity", type: "text", required: true },
      { key: "branch_id", label: "Home branch", type: "text" },
      { key: "nickname", label: "Nickname", type: "text", hint: "Customer-chosen name" },
      { key: "bundle_id", label: "Product bundle", type: "text" },
    ],
  },
  {
    title: "Account numbering",
    description: "Server-assigned by default; override only for migration or specific IBAN/BBAN.",
    fields: [
      { key: "iban", label: "IBAN", type: "text" },
      { key: "bban", label: "BBAN", type: "text" },
      { key: "routing_code", label: "Routing / sort code", type: "text", hint: "IFSC / sort code / CLABE prefix" },
      { key: "external_ref", label: "External reference", type: "text", hint: "Client / migration reference" },
    ],
  },
  {
    title: "Operating terms",
    fields: [
      { key: "mandate_rule", label: "Signing mandate", type: "select", options: MANDATE_RULE, hint: "Default ANY_ONE" },
      { key: "minimum_balance", label: "Minimum balance", type: "amount" },
      { key: "notice_period_days", label: "Notice period (days)", type: "number", hint: "NOTICE accounts" },
      { key: "statement_frequency", label: "Statement frequency", type: "select", options: SCHEDULE_FREQUENCY, hint: "Default MONTHLY" },
      { key: "statement_cycle_day", label: "Statement cycle day", type: "number", hint: "Day of month (1–28)" },
    ],
  },
  {
    title: "AML profile",
    description: "Expected-activity profile captured at opening for monitoring.",
    fields: [
      { key: "opening_channel", label: "Opening channel", type: "text" },
      { key: "purpose", label: "Account purpose", type: "text", span: 2 },
      { key: "source_of_funds", label: "Source of funds", type: "text", span: 2 },
      { key: "expected_monthly_volume", label: "Expected monthly turnover", type: "amount" },
    ],
  },
];

type A = Record<string, unknown>;

export function accountDetailSections(a: A): DetailSection[] {
  const ccy = a.currency as string;
  return [
    {
      title: "Identity & numbering",
      rows: [
        { label: "Account number", value: a.account_number, kind: "mono" },
        { label: "IBAN", value: a.iban, kind: "mono" },
        { label: "BBAN", value: a.bban, kind: "mono" },
        { label: "Routing code", value: a.routing_code, kind: "mono" },
        { label: "BIC", value: a.bic, kind: "mono" },
        { label: "Nickname", value: a.nickname },
        { label: "External ref", value: a.external_ref, kind: "mono" },
      ],
    },
    {
      title: "Classification & product",
      rows: [
        { label: "Type", value: a.account_type, kind: "badge" },
        { label: "Status", value: a.status, kind: "badge" },
        { label: "Currency", value: a.currency },
        { label: "Primary customer", value: a.customer_id, kind: "mono" },
        { label: "Product", value: a.product_id, kind: "mono" },
        { label: "Product version", value: a.product_version_id, kind: "mono" },
        { label: "Legal entity", value: a.legal_entity_id, kind: "mono" },
        { label: "Branch", value: a.branch_id, kind: "mono" },
        { label: "Mandate rule", value: a.mandate_rule },
      ],
    },
    {
      title: "Balances & interest",
      rows: [
        { label: "Minimum balance", value: a.minimum_balance, kind: "money", currency: ccy },
        { label: "Overdraft limit", value: a.overdraft_limit, kind: "money", currency: ccy },
        { label: "Accrued credit interest", value: a.accrued_credit_interest, kind: "money", currency: ccy },
        { label: "Accrued debit interest", value: a.accrued_debit_interest, kind: "money", currency: ccy },
        { label: "Interest paid YTD", value: a.interest_paid_ytd, kind: "money", currency: ccy },
        { label: "Tax withheld YTD", value: a.tax_withheld_ytd, kind: "money", currency: ccy },
        { label: "Next capitalisation", value: a.next_capitalisation_date, kind: "date" },
      ],
    },
    {
      title: "Servicing & AML",
      rows: [
        { label: "Statement frequency", value: a.statement_frequency },
        { label: "Statement cycle day", value: a.statement_cycle_day },
        { label: "Notice period (days)", value: a.notice_period_days },
        { label: "Opening channel", value: a.opening_channel },
        { label: "Purpose", value: a.purpose },
        { label: "Source of funds", value: a.source_of_funds },
        { label: "Expected monthly volume", value: a.expected_monthly_volume, kind: "money", currency: ccy },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Opened at", value: a.opened_at, kind: "datetime" },
        { label: "Last activity", value: a.last_customer_activity_at, kind: "datetime" },
        { label: "Dormant since", value: a.dormant_since, kind: "date" },
        { label: "Status reason", value: a.status_reason },
        { label: "Closed at", value: a.closed_at, kind: "datetime" },
      ],
    },
  ];
}
