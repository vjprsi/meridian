import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** collections-service enums (kept in sync with spec/domains/lending.yaml). */
export const COLLECTION_EXPOSURE_TYPE = ["LOAN", "CREDIT_LINE", "CARD", "OVERDRAFT", "BNPL"];
export const COLLECTION_ACTION_TYPE = ["SMS", "PUSH", "EMAIL", "WHATSAPP", "CALL", "FIELD_VISIT", "LETTER", "LEGAL_NOTICE",
  "AUTO_DEBIT_RETRY", "SKIP_TRACE"];
export const COLLECTION_ACTION_OUTCOME = ["SENT", "DELIVERED", "FAILED", "NO_ANSWER", "RIGHT_PARTY_CONTACT", "WRONG_PARTY",
  "PROMISE_MADE", "REFUSED", "PAID", "DISPUTED", "SUPPRESSED"];

/**
 * The full writable field set to open (or refresh) a collections case for a delinquent exposure —
 * POST /v1/collections/cases/open. Server-derived columns (case number, status, DPD bucket, priority,
 * strategy/queue assignment, timestamps) are excluded; they appear in the detail view instead.
 * This is the primary create flow in collections.
 */
export const collectionCaseFormSections: FormSection[] = [
  {
    title: "Exposure & customer",
    description: "Which delinquent exposure the case is opened for, and the customer who owes it.",
    fields: [
      { key: "customer_id", label: "Customer", type: "text", required: true, span: 2, hint: "Customer id of the borrower" },
      { key: "exposure_type", label: "Exposure type", type: "select", options: COLLECTION_EXPOSURE_TYPE, required: true },
      { key: "exposure_id", label: "Exposure id", type: "text", required: true, span: 2, hint: "Loan, credit line, card account or overdraft id" },
      { key: "product_id", label: "Product", type: "text", hint: "Product of the exposure" },
      { key: "risk_grade", label: "Risk grade", type: "text", hint: "Risk grade of the exposure" },
    ],
  },
  {
    title: "Delinquency",
    description: "The arrears snapshot driving the case — the server buckets DPD and scores priority from these.",
    fields: [
      { key: "days_past_due", label: "Days past due", type: "number", required: true },
      { key: "overdue_amount", label: "Overdue amount", type: "amount", required: true },
      { key: "total_outstanding", label: "Total outstanding", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
    ],
  },
];

/**
 * The writable field set for recording a promise to pay on a case —
 * POST /v1/collections/cases/{id}/promise. Paid amount, status, collector and resolution are server-derived.
 */
export const promiseToPayFormSections: FormSection[] = [
  {
    title: "Promise to pay",
    description: "An amount the customer has committed to pay by a date.",
    fields: [
      { key: "promised_amount", label: "Promised amount", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", hint: "Defaults to the case currency" },
      { key: "promised_date", label: "Promised date", type: "date", required: true },
      { key: "channel", label: "Channel", type: "text", hint: "How the promise was made" },
    ],
  },
];

/**
 * The writable field set for logging a contact or action on a case —
 * POST /v1/collections/cases/{id}/log-action. Performed-by, strategy step and timestamps are server-derived.
 */
export const collectionActionFormSections: FormSection[] = [
  {
    title: "Log contact / action",
    description: "A manual contact attempt or action recorded against the case (append-only).",
    fields: [
      { key: "action_type", label: "Action", type: "select", options: COLLECTION_ACTION_TYPE, required: true },
      { key: "outcome", label: "Outcome", type: "select", options: COLLECTION_ACTION_OUTCOME, required: true },
      { key: "contact_channel_address", label: "Phone / email used", type: "text", span: 2 },
      { key: "notes", label: "Notes", type: "textarea" },
    ],
  },
];

type C = Record<string, unknown>;

/** The full collection_case record grouped into sections for the case detail / profile view. */
export function collectionCaseDetailSections(c: C): DetailSection[] {
  const ccy = c.currency as string;
  return [
    {
      title: "Case",
      rows: [
        { label: "Case number", value: c.case_number, kind: "mono" },
        { label: "Status", value: c.status, kind: "badge" },
        { label: "Exposure type", value: c.exposure_type, kind: "badge" },
        { label: "Exposure id", value: c.exposure_id, kind: "mono" },
        { label: "Customer", value: c.customer_id, kind: "mono" },
        { label: "Product", value: c.product_id, kind: "mono" },
        { label: "Risk grade", value: c.risk_grade, kind: "badge" },
        { label: "Currency", value: c.currency },
      ],
    },
    {
      title: "Delinquency",
      rows: [
        { label: "Days past due", value: c.days_past_due },
        { label: "Delinquency bucket", value: c.delinquency_bucket, kind: "badge" },
        { label: "Max days past due", value: c.max_days_past_due },
        { label: "Overdue amount", value: c.overdue_amount, kind: "money", currency: ccy },
        { label: "Total outstanding", value: c.total_outstanding, kind: "money", currency: ccy },
        { label: "Priority score", value: c.priority_score },
        { label: "Broken promises", value: c.broken_promises },
      ],
    },
    {
      title: "Assignment & strategy",
      rows: [
        { label: "Strategy", value: c.collection_strategy_id, kind: "mono" },
        { label: "Queue", value: c.collection_queue_id, kind: "mono" },
        { label: "Collector", value: c.assigned_to, kind: "mono" },
        { label: "Last strategy step", value: c.last_step_no },
        { label: "Agency", value: c.agency_name },
        { label: "Agency assigned", value: c.agency_assigned_at, kind: "datetime" },
      ],
    },
    {
      title: "Flags & activity",
      rows: [
        { label: "Disputed", value: c.is_disputed, kind: "bool" },
        { label: "Vulnerable customer", value: c.is_vulnerable, kind: "bool" },
        { label: "Last contact", value: c.last_contact_at, kind: "datetime" },
        { label: "Last payment", value: c.last_payment_at, kind: "datetime" },
        { label: "Next action", value: c.next_action_at, kind: "datetime" },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Opened", value: c.opened_at, kind: "datetime" },
        { label: "Resolved", value: c.resolved_at, kind: "datetime" },
        { label: "Resolution", value: c.resolution, kind: "badge" },
        { label: "Created", value: c.created_at, kind: "datetime" },
      ],
    },
  ];
}
