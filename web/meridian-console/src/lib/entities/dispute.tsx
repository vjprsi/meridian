import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** card-dispute-service enums (kept in sync with spec/domains/cards.yaml). */
export const DISPUTE_CATEGORY = ["FRAUD", "AUTHORIZATION", "PROCESSING_ERROR", "CONSUMER_DISPUTE", "NOT_RECEIVED",
  "NOT_AS_DESCRIBED", "DUPLICATE", "CANCELLED_RECURRING", "CREDIT_NOT_PROCESSED", "ATM_DISPUTE"];
export const DISPUTE_SCHEME = ["VISA", "MASTERCARD", "AMEX", "UNIONPAY", "JCB", "DISCOVER", "RUPAY", "ELO", "LOCAL"];

/**
 * The full dispute-intake field set — every writable column on card_dispute when a dispute /
 * chargeback is raised. Server-derived columns (stage, status, the provisional-credit and
 * outcome amounts, deadlines, assignee, copilot output) are excluded: they are set by the
 * lifecycle actions on the detail page, not at intake. Evidence is attached after creation
 * through the separate card-dispute-evidence endpoint.
 */
export const cardDisputeFormSections: FormSection[] = [
  {
    title: "Transaction & card",
    description: "The card being disputed and the specific authorisation / presentment in question. The cardholder and funding account are derived from the card.",
    fields: [
      { key: "card_id", label: "Card", type: "text", required: true, span: 2, hint: "Card the disputed transaction was made on" },
      { key: "scheme", label: "Scheme", type: "select", options: DISPUTE_SCHEME, required: true },
      { key: "card_authorization_id", label: "Disputed authorisation", type: "text", hint: "Authorisation being disputed" },
      { key: "clearing_record_id", label: "Disputed presentment", type: "text", hint: "Cleared presentment / clearing record" },
      { key: "transaction_date", label: "Transaction date", type: "date", hint: "Drives the scheme time limits" },
    ],
  },
  {
    title: "Dispute reason & amount",
    description: "Why the cardholder is disputing, the amount in question and the scheme reason / case reference.",
    fields: [
      { key: "category", label: "Category", type: "select", options: DISPUTE_CATEGORY, required: true },
      { key: "reason_code", label: "Reason code", type: "text", hint: "Scheme reason code, e.g. 10.4, 4837" },
      { key: "disputed_amount", label: "Disputed amount", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "scheme_case_id", label: "Scheme case ID", type: "text", hint: "Scheme case number, if already opened" },
    ],
  },
  {
    title: "Cardholder claim",
    description: "The cardholder's own statement of the dispute (treated as PII).",
    fields: [
      { key: "customer_description", label: "Customer description", type: "textarea", hint: "Cardholder statement in their own words" },
    ],
  },
];

type D = Record<string, unknown>;

/** The full dispute record, grouped into sections, for the detail page. */
export function cardDisputeDetailSections(d: D): DetailSection[] {
  const ccy = d.currency as string;
  return [
    {
      title: "Dispute",
      rows: [
        { label: "Category", value: d.category, kind: "badge" },
        { label: "Scheme", value: d.scheme, kind: "badge" },
        { label: "Stage", value: d.stage, kind: "badge" },
        { label: "Status", value: d.status, kind: "badge" },
        { label: "Reason code", value: d.reason_code, kind: "mono" },
        { label: "Suggested reason code", value: d.suggested_reason_code, kind: "mono" },
      ],
    },
    {
      title: "Transaction & card",
      rows: [
        { label: "Card", value: d.card_id, kind: "mono" },
        { label: "Cardholder", value: d.customer_id, kind: "mono" },
        { label: "Cardholder account", value: d.ledger_account_id, kind: "mono" },
        { label: "Disputed authorisation", value: d.card_authorization_id, kind: "mono" },
        { label: "Disputed presentment", value: d.clearing_record_id, kind: "mono" },
        { label: "Transaction date", value: d.transaction_date, kind: "date" },
        { label: "Scheme case ID", value: d.scheme_case_id, kind: "mono" },
      ],
    },
    {
      title: "Amounts & outcome",
      rows: [
        { label: "Disputed amount", value: d.disputed_amount, kind: "money", currency: ccy },
        { label: "Chargeback amount", value: d.chargeback_amount, kind: "money", currency: ccy },
        { label: "Representment amount", value: d.representment_amount, kind: "money", currency: ccy },
        { label: "Provisional credit", value: d.provisional_credit_amount, kind: "money", currency: ccy },
        { label: "Provisional credit reversed", value: d.provisional_credit_reversed, kind: "bool" },
        { label: "Recovered amount", value: d.recovered_amount, kind: "money", currency: ccy },
        { label: "Written off amount", value: d.written_off_amount, kind: "money", currency: ccy },
        { label: "Outcome", value: d.outcome, kind: "badge" },
      ],
    },
    {
      title: "Cardholder claim & copilot",
      hideWhenEmpty: true,
      rows: [
        { label: "Customer description", value: d.customer_description },
        { label: "Copilot summary", value: d.ai_summary },
      ],
    },
    {
      title: "Workflow & deadlines",
      rows: [
        { label: "Assigned to", value: d.assigned_to },
        { label: "Stage deadline", value: d.stage_deadline, kind: "date" },
        { label: "Regulatory deadline", value: d.regulatory_deadline, kind: "date" },
        { label: "Submitted", value: d.submitted_at, kind: "datetime" },
        { label: "Closed", value: d.closed_at, kind: "datetime" },
        { label: "Opened", value: d.created_at, kind: "datetime" },
      ],
    },
  ];
}
