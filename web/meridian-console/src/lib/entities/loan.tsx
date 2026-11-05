import type { FormSection, FormValues } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** lending.yaml enums (kept in sync with spec/domains/lending.yaml). */
export const LOAN_CHANNEL = ["MOBILE", "WEB", "BRANCH", "AGENT", "API", "USSD", "MERCHANT_POS"];
export const LOAN_TYPE = ["INSTALMENT", "BNPL", "NANO", "MERCHANT_FINANCING", "MORTGAGE", "VEHICLE", "OVERDRAFT_TERM", "SME_TERM"];
export const LOAN_PURPOSE = ["PERSONAL", "HOME", "VEHICLE", "EDUCATION", "MEDICAL", "BUSINESS_WORKING_CAPITAL", "BUSINESS_ASSET",
  "DEBT_CONSOLIDATION", "PURCHASE_FINANCING", "AGRICULTURE", "OTHER"];
export const REPAYMENT_FREQUENCY = ["DAILY", "WEEKLY", "FORTNIGHTLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL", "AT_MATURITY"];
export const SCHEDULE_TYPE = ["ANNUITY", "EQUAL_PRINCIPAL", "BULLET", "BALLOON", "INTEREST_ONLY", "CUSTOM"];

/** Loan types that are driven by a merchant / point-of-sale flow. */
const MERCHANT_TYPES = ["BNPL", "MERCHANT_FINANCING"];
/** Loan types scored on wallet transaction data. */
const WALLET_TYPES = ["NANO"];
const isMerchant = (v: FormValues) => MERCHANT_TYPES.includes(v.loan_type) || v.channel === "MERCHANT_POS";
const isWallet = (v: FormValues) => WALLET_TYPES.includes(v.loan_type);

/**
 * The full loan-origination application field set — every writable column on
 * loan_origination.loan_application (server-derived / `ro` / `hide` columns excluded).
 * The primary create flow in lending: a loan, credit-line, BNPL or nano-loan application.
 */
export const loanApplicationFormSections: FormSection[] = [
  {
    title: "Applicant & product",
    description: "Who is borrowing and which lending product and legal entity the application is for.",
    fields: [
      { key: "customer_id", label: "Primary applicant (customer)", type: "text", required: true, span: 2, hint: "Customer id of the primary borrower" },
      { key: "product_id", label: "Lending product", type: "text", required: true, hint: "Product applied for" },
      { key: "product_version_id", label: "Product version", type: "text", hint: "Version in force at submission (defaults to current)" },
      { key: "legal_entity_id", label: "Legal entity", type: "text", hint: "Lending legal entity" },
      { key: "branch_id", label: "Originating branch", type: "text" },
      { key: "agent_id", label: "Assisting agent", type: "text", hint: "Agent who assisted the application" },
      { key: "channel", label: "Channel", type: "select", options: LOAN_CHANNEL, required: true },
      { key: "loan_type", label: "Loan type", type: "select", options: LOAN_TYPE, required: true },
    ],
  },
  {
    title: "Requested terms",
    description: "Amount, purpose and the repayment shape the applicant has asked for.",
    fields: [
      { key: "purpose", label: "Purpose", type: "select", options: LOAN_PURPOSE, required: true },
      { key: "purpose_detail", label: "Purpose detail", type: "textarea", hint: "Free-text description of what the loan is for" },
      { key: "requested_amount", label: "Requested amount", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "requested_term_months", label: "Requested term (months)", type: "number" },
      { key: "requested_frequency", label: "Preferred repayment frequency", type: "select", options: REPAYMENT_FREQUENCY },
      { key: "requested_schedule_type", label: "Preferred schedule type", type: "select", options: SCHEDULE_TYPE, hint: "Defaults to ANNUITY" },
    ],
  },
  {
    title: "Merchant & wallet",
    description: "Point-of-sale financing and wallet-scored nano-loans.",
    fields: [
      { key: "merchant_id", label: "Merchant", type: "text", span: 2, when: isMerchant, hint: "Merchant for BNPL / point-of-sale financing" },
      { key: "wallet_id", label: "Scored wallet", type: "text", span: 2, when: isWallet, hint: "Wallet scored for nano-loans" },
    ],
  },
  {
    title: "Disbursement & repayment",
    description: "Where the funds are paid and the account auto-debited for instalments.",
    fields: [
      { key: "disbursement_account_id", label: "Disbursement account", type: "text", hint: "Deposit account to receive the funds" },
      { key: "repayment_account_id", label: "Repayment account", type: "text", hint: "Deposit account auto-debited for instalments" },
    ],
  },
  {
    title: "Consent & validity",
    description: "Credit-bureau consent and application expiry.",
    fields: [
      { key: "consent_bureau", label: "Bureau check consented", type: "bool", hint: "Applicant consented to a credit bureau check" },
      { key: "consent_at", label: "Consent captured at", type: "datetime" },
      { key: "expires_at", label: "Application expiry", type: "datetime" },
    ],
  },
];

type A = Record<string, unknown>;

/** The full application record grouped into sections for the detail / 360 view. */
export function loanApplicationDetailSections(a: A): DetailSection[] {
  return [
    {
      title: "Application",
      rows: [
        { label: "Application number", value: a.application_number, kind: "mono" },
        { label: "Status", value: a.status, kind: "badge" },
        { label: "Loan type", value: a.loan_type, kind: "badge" },
        { label: "Channel", value: a.channel, kind: "badge" },
        { label: "Purpose", value: a.purpose, kind: "badge" },
        { label: "Purpose detail", value: a.purpose_detail },
        { label: "Risk grade", value: a.risk_grade, kind: "badge" },
        { label: "Assigned underwriter", value: a.assigned_to, kind: "mono" },
      ],
    },
    {
      title: "Applicant & product",
      rows: [
        { label: "Primary applicant", value: a.customer_id, kind: "mono" },
        { label: "Product", value: a.product_id, kind: "mono" },
        { label: "Product version", value: a.product_version_id, kind: "mono" },
        { label: "Legal entity", value: a.legal_entity_id, kind: "mono" },
        { label: "Branch", value: a.branch_id, kind: "mono" },
        { label: "Agent", value: a.agent_id, kind: "mono" },
      ],
    },
    {
      title: "Requested terms",
      rows: [
        { label: "Requested amount", value: a.requested_amount, kind: "money", currency: a.currency as string },
        { label: "Currency", value: a.currency },
        { label: "Requested term (months)", value: a.requested_term_months },
        { label: "Repayment frequency", value: a.requested_frequency },
        { label: "Schedule type", value: a.requested_schedule_type },
      ],
    },
    {
      title: "Merchant & wallet",
      hideWhenEmpty: true,
      rows: [
        { label: "Merchant", value: a.merchant_id, kind: "mono" },
        { label: "Scored wallet", value: a.wallet_id, kind: "mono" },
      ],
    },
    {
      title: "Disbursement & repayment",
      hideWhenEmpty: true,
      rows: [
        { label: "Disbursement account", value: a.disbursement_account_id, kind: "mono" },
        { label: "Repayment account", value: a.repayment_account_id, kind: "mono" },
      ],
    },
    {
      title: "Consent & lifecycle",
      rows: [
        { label: "Bureau consent", value: a.consent_bureau, kind: "bool" },
        { label: "Consent captured at", value: a.consent_at, kind: "datetime" },
        { label: "Submitted", value: a.submitted_at, kind: "datetime" },
        { label: "Decided", value: a.decided_at, kind: "datetime" },
        { label: "Expires", value: a.expires_at, kind: "datetime" },
        { label: "Booked loan", value: a.loan_id, kind: "mono" },
        { label: "Booking error", value: a.booking_error },
        { label: "Withdrawal reason", value: a.withdrawal_reason },
        { label: "Created", value: a.created_at, kind: "datetime" },
      ],
    },
  ];
}

/** The booked-loan record grouped into sections for the loan detail view. */
export function loanDetailSections(l: A): DetailSection[] {
  const ccy = l.currency as string;
  return [
    {
      title: "Loan",
      rows: [
        { label: "Loan number", value: l.loan_number, kind: "mono" },
        { label: "Status", value: l.status, kind: "badge" },
        { label: "Loan type", value: l.loan_type, kind: "badge" },
        { label: "Currency", value: l.currency },
        { label: "Primary borrower", value: l.customer_id, kind: "mono" },
        { label: "Originating application", value: l.loan_application_id, kind: "mono" },
        { label: "Accepted offer", value: l.loan_offer_id, kind: "mono" },
        { label: "Product", value: l.product_id, kind: "mono" },
        { label: "Legal entity", value: l.legal_entity_id, kind: "mono" },
        { label: "Branch", value: l.branch_id, kind: "mono" },
        { label: "Risk grade", value: l.risk_grade, kind: "badge" },
        { label: "Nano-loan", value: l.is_nano, kind: "bool" },
      ],
    },
    {
      title: "Balances",
      rows: [
        { label: "Contracted principal", value: l.principal_amount, kind: "money", currency: ccy },
        { label: "Disbursed to date", value: l.disbursed_amount, kind: "money", currency: ccy },
        { label: "Outstanding principal", value: l.outstanding_principal, kind: "money", currency: ccy },
        { label: "Outstanding interest", value: l.outstanding_interest, kind: "money", currency: ccy },
        { label: "Outstanding fees", value: l.outstanding_fees, kind: "money", currency: ccy },
        { label: "Outstanding penalties", value: l.outstanding_penalties, kind: "money", currency: ccy },
        { label: "Total outstanding", value: l.total_outstanding, kind: "money", currency: ccy },
        { label: "Overdue amount", value: l.overdue_amount, kind: "money", currency: ccy },
        { label: "Credit balance", value: l.credit_balance, kind: "money", currency: ccy },
      ],
    },
    {
      title: "Pricing & schedule",
      rows: [
        { label: "Interest rate type", value: l.interest_rate_type, kind: "badge" },
        { label: "Interest rate", value: l.interest_rate },
        { label: "Rate index", value: l.rate_index },
        { label: "Rate margin", value: l.rate_margin },
        { label: "Penalty rate", value: l.penalty_rate },
        { label: "Day count", value: l.day_count },
        { label: "Schedule type", value: l.schedule_type, kind: "badge" },
        { label: "Repayment frequency", value: l.repayment_frequency },
        { label: "Business-day convention", value: l.business_day_convention },
        { label: "Term (months)", value: l.term_months },
        { label: "Balloon amount", value: l.balloon_amount, kind: "money", currency: ccy },
        { label: "Interest-only periods", value: l.interest_only_periods },
        { label: "Grace period (days)", value: l.grace_period_days },
      ],
    },
    {
      title: "Dates",
      rows: [
        { label: "Start date", value: l.start_date, kind: "date" },
        { label: "First repayment", value: l.first_repayment_date, kind: "date" },
        { label: "Next due", value: l.next_due_date, kind: "date" },
        { label: "Maturity", value: l.maturity_date, kind: "date" },
        { label: "Cooling-off until", value: l.cooling_off_until, kind: "date" },
        { label: "Last accrual", value: l.last_accrual_date, kind: "date" },
        { label: "Paid off", value: l.paid_off_at, kind: "datetime" },
        { label: "Closed", value: l.closed_at, kind: "datetime" },
      ],
    },
    {
      title: "Delinquency & risk",
      rows: [
        { label: "Days past due", value: l.days_past_due },
        { label: "Delinquency bucket", value: l.delinquency_bucket, kind: "badge" },
        { label: "ECL stage", value: l.ecl_stage, kind: "badge" },
        { label: "Forborne", value: l.is_forborne, kind: "bool" },
      ],
    },
    {
      title: "Disbursement & collection",
      rows: [
        { label: "Disbursement account", value: l.disbursement_account_id, kind: "mono" },
        { label: "Disbursement wallet", value: l.disbursement_wallet_id, kind: "mono" },
        { label: "Repayment account", value: l.repayment_account_id, kind: "mono" },
        { label: "Auto-debit enabled", value: l.auto_debit_enabled, kind: "bool" },
        { label: "Ledger account", value: l.ledger_account_id, kind: "mono" },
        { label: "Merchant", value: l.merchant_id, kind: "mono" },
        { label: "Credit line", value: l.credit_line_id, kind: "mono" },
      ],
    },
  ];
}
