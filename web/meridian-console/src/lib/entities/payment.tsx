import type { FormSection, FormValues } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/**
 * Payment-service capture + detail field sets for the two creatable "order / batch" entities the
 * payments console exposes beyond the single-payment form (payments/new): the Order & Pay (ETM)
 * payment order and the bulk (batch) payment. Kept in sync with spec/domains/payments.yaml,
 * payment-service. Read-only / server-derived columns (order_reference, the order / payment /
 * fulfilment / alert / settlement state machine, heartbeats, holds, counts, lifecycle timestamps,
 * four-eyes submitter) are excluded from the create forms and shown on the detail sections.
 */

/** payments.yaml enums (ETM / Order & Pay — G-08). */
export const ORDER_STATUS = ["CREATED", "AWAITING_PAYMENT", "PAID", "FULFILLING", "COMPLETED", "CANCELLED", "FAILED"];
export const FULFILMENT_STATUS = ["PENDING", "IN_PROGRESS", "FULFILLED", "FAILED", "CANCELLED"];
export const ORDER_SETTLEMENT_ACTION = ["RETRY", "ROLLBACK", "ROLL_FORWARD", "WAIT"];
export const ORDER_ALERT_LEVEL = ["GREEN", "YELLOW", "RED"];
export const PG_HEARTBEAT_STATUS = ["UP", "DEGRADED", "DOWN", "UNKNOWN"];
export const PAYMENT_CHANNEL = ["API", "MOBILE", "WEB", "BRANCH", "USSD", "AGENT", "BULK_FILE", "OPEN_BANKING", "SCHEDULER", "INBOUND_RAIL"];

/** payments.yaml enums (bulk / batch payments). */
export const BULK_FILE_FORMAT = ["PAIN_001", "CSV", "NACHA", "BACS_STD18", "JSON"];
export const BULK_PAYMENT_STATUS = ["UPLOADED", "VALIDATING", "VALIDATED", "PENDING_APPROVAL", "APPROVED", "PROCESSING", "COMPLETED", "PARTIALLY_COMPLETED", "REJECTED", "CANCELLED"];
export const BULK_PAYMENT_ITEM_STATUS = ["PENDING", "VALID", "INVALID", "SUBMITTED", "SETTLED", "FAILED", "CANCELLED"];
export const BULK_ACCOUNT_ID_TYPE = ["IBAN", "BBAN", "ACCOUNT_NUMBER", "WALLET", "PROXY", "CARD_PAN_TOKEN", "INTERNAL"];

type D = Record<string, unknown>;

/* ---------------------------------------------------------------------------------------------------------------
 * Order & Pay (ETM) payment order — writable columns of payment_order (POST /v1/payment-orders).
 * ------------------------------------------------------------------------------------------------------------- */

export const paymentOrderFormSections: FormSection[] = [
  {
    title: "Order & parties",
    description: "What the customer is buying and who is being paid. The order, payment and fulfilment are tracked as segregated lifecycle states.",
    fields: [
      { key: "customer_id", label: "Customer", type: "text", hint: "Customer placing the order" },
      { key: "merchant_id", label: "Merchant / biller", type: "text", hint: "Party being paid" },
      { key: "external_order_id", label: "External order id", type: "text", hint: "Merchant / caller order id", span: 2 },
      { key: "channel", label: "Channel", type: "select", options: PAYMENT_CHANNEL, hint: "Channel that placed the order" },
    ],
  },
  {
    title: "Amount & description",
    fields: [
      { key: "amount", label: "Order amount", type: "amount", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "description", label: "Description", type: "textarea", placeholder: "What the order is for" },
    ],
  },
  {
    title: "Metadata",
    description: "Client-defined key/value metadata carried with the order.",
    fields: [
      { key: "metadata", label: "Metadata (JSON)", type: "json", placeholder: '{ "cart_id": "…" }' },
    ],
  },
];

/* ---------------------------------------------------------------------------------------------------------------
 * Bulk (batch) payment — writable columns of bulk_payment plus its nested item lines (POST /v1/bulk-payments).
 * The file-upload path (/upload, pain.001 / CSV) is a separate flow; this API create carries the lines as JSON.
 * ------------------------------------------------------------------------------------------------------------- */

export const bulkPaymentFormSections: FormSection[] = [
  {
    title: "Batch & account",
    description: "A batch of payments (payroll, supplier run) created through the API with its item lines.",
    fields: [
      { key: "name", label: "Batch name", type: "text", required: true, span: 2 },
      { key: "customer_id", label: "Business customer", type: "text", required: true },
      { key: "debtor_account_id", label: "Debtor account", type: "text", required: true, hint: "Account debited" },
    ],
  },
  {
    title: "Format & execution",
    fields: [
      { key: "file_format", label: "File format", type: "select", options: BULK_FILE_FORMAT, required: true, hint: "Use JSON for an API-created batch" },
      { key: "currency", label: "Batch currency", type: "currency", required: true },
      { key: "category_purpose", label: "Category purpose", type: "text", placeholder: "SALA", hint: "ISO category purpose (SALA for payroll)" },
      { key: "requested_execution_date", label: "Execution date", type: "date" },
      { key: "control_sum", label: "Control sum", type: "amount", hint: "Control sum declared in the file" },
      { key: "document_id", label: "Uploaded document", type: "text", hint: "Document reference when a file was uploaded" },
      { key: "batch_booking", label: "Batch booking", type: "bool", hint: "One debit for the whole batch (off = per item)" },
    ],
  },
  {
    title: "Items",
    description: "Lines of the batch. JSON array of item objects.",
    fields: [
      { key: "items", label: "Items", type: "json", required: true,
        placeholder: '[{"creditor_name":"Jane Doe","creditor_account_id_type":"IBAN","creditor_account_number":"GB00…","amount":"1500.00","currency":"GBP","remittance_unstructured":"October salary"}]',
        hint: "Per line: creditor_name, creditor_account_id_type, creditor_account_number, creditor_agent_bic, creditor_agent_clearing_id, amount, currency, end_to_end_id, remittance_unstructured" },
    ],
  },
];

/** Create endpoints, labels and sensible defaults per creatable payment entity. */
export const paymentOrderCreate = {
  label: "New order",
  submitLabel: "Create order",
  path: "/v1/payment-orders",
  initial: {} as FormValues,
};

export const bulkPaymentCreate = {
  label: "New batch",
  submitLabel: "Create batch",
  path: "/v1/bulk-payments",
  initial: { file_format: "JSON", batch_booking: "true" } as FormValues,
};

/* ---------------------------------------------------------------------------------------------------------------
 * Detail sections (full records grouped for the detail pages).
 * ------------------------------------------------------------------------------------------------------------- */

/** The full payment-order record grouped into labelled sections. */
export function paymentOrderDetailSections(o: D): DetailSection[] {
  const ccy = o.currency as string;
  return [
    {
      title: "Order",
      rows: [
        { label: "Order reference", value: o.order_reference, kind: "mono" },
        { label: "Order status", value: o.order_status, kind: "badge" },
        { label: "Amount", value: o.amount, kind: "money", currency: ccy },
        { label: "Currency", value: o.currency },
        { label: "Description", value: o.description },
        { label: "External order id", value: o.external_order_id, kind: "mono" },
        { label: "Channel", value: o.channel, kind: "badge" },
        { label: "Customer", value: o.customer_id, kind: "mono" },
        { label: "Merchant", value: o.merchant_id, kind: "mono" },
      ],
    },
    {
      title: "Payment leg",
      rows: [
        { label: "Payment id", value: o.payment_id, kind: "mono" },
        { label: "Payment status", value: o.payment_status, kind: "badge" },
        { label: "Paid at", value: o.paid_at, kind: "datetime" },
        { label: "Payment deadline", value: o.payment_deadline_at, kind: "datetime" },
      ],
    },
    {
      title: "Fulfilment",
      rows: [
        { label: "Fulfilment status", value: o.fulfilment_status, kind: "badge" },
        { label: "Fulfilled at", value: o.fulfilled_at, kind: "datetime" },
        { label: "Fulfilment deadline", value: o.fulfilment_deadline_at, kind: "datetime" },
        { label: "Completed at", value: o.completed_at, kind: "datetime" },
      ],
    },
    {
      title: "Settlement & health",
      rows: [
        { label: "Alert level", value: o.alert_level, kind: "badge" },
        { label: "Ambiguous", value: o.ambiguous, kind: "bool" },
        { label: "Settlement action", value: o.settlement_action, kind: "badge" },
        { label: "Settlement attempts", value: o.settlement_attempts },
        { label: "Next settlement at", value: o.next_settlement_at, kind: "datetime" },
        { label: "PG heartbeat", value: o.pg_heartbeat_status, kind: "badge" },
        { label: "PG heartbeat at", value: o.pg_heartbeat_at, kind: "datetime" },
        { label: "Resolution reason", value: o.resolution_reason },
        { label: "Settlement suspense", value: o.settlement_suspense_amount, kind: "money", currency: ccy },
        { label: "Rollback recall", value: o.rollback_recall_id, kind: "mono" },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Initiated by", value: o.initiated_by },
        { label: "Created", value: o.created_at, kind: "datetime" },
        { label: "Updated", value: o.updated_at, kind: "datetime" },
      ],
    },
  ];
}

/** The full bulk-payment record grouped into labelled sections. */
export function bulkPaymentDetailSections(b: D): DetailSection[] {
  const ccy = b.currency as string;
  return [
    {
      title: "Batch",
      rows: [
        { label: "Name", value: b.name },
        { label: "Status", value: b.status, kind: "badge" },
        { label: "File format", value: b.file_format, kind: "badge" },
        { label: "Currency", value: b.currency },
        { label: "Customer", value: b.customer_id, kind: "mono" },
        { label: "Debtor account", value: b.debtor_account_id, kind: "mono" },
        { label: "Category purpose", value: b.category_purpose },
        { label: "Batch booking", value: b.batch_booking, kind: "bool" },
        { label: "Execution date", value: b.requested_execution_date, kind: "date" },
      ],
    },
    {
      title: "Items & amounts",
      rows: [
        { label: "Items", value: b.item_count },
        { label: "Valid", value: b.valid_count },
        { label: "Failed", value: b.failed_count },
        { label: "Settled", value: b.settled_count },
        { label: "Total amount", value: b.total_amount, kind: "money", currency: ccy },
        { label: "Control sum", value: b.control_sum, kind: "money", currency: ccy },
      ],
    },
    {
      title: "Processing & lifecycle",
      rows: [
        { label: "Hold", value: b.hold_id, kind: "mono" },
        { label: "Document", value: b.document_id, kind: "mono" },
        { label: "Submitted by", value: b.submitted_by },
        { label: "Submitted at", value: b.submitted_at, kind: "datetime" },
        { label: "Rejection reason", value: b.rejection_reason },
        { label: "Completed at", value: b.completed_at, kind: "datetime" },
        { label: "Created", value: b.created_at, kind: "datetime" },
      ],
    },
  ];
}
