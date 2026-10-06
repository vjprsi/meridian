import type { components } from "@/api/schema";

/** Payment-service model types and enumerations (from the generated OpenAPI contract). */
export type Payment = components["schemas"]["Payment"];
export type PaymentCreate = components["schemas"]["PaymentCreate"];
export type PaymentStatusHistory = components["schemas"]["PaymentStatusHistory"];
export type PaymentApproval = components["schemas"]["PaymentApproval"];

export const PAYMENT_STATUSES = ["INITIATED", "SCREENING", "PENDING_APPROVAL", "APPROVED", "SUBMITTED", "ACCEPTED",
  "SETTLED", "REJECTED", "RETURNED", "FAILED", "CANCELLED", "IN_REPAIR"] as const satisfies readonly Payment["status"][];

export const PAYMENT_TYPES = ["INTERNAL_TRANSFER", "P2P", "DOMESTIC", "INSTANT", "CROSS_BORDER", "BULK_ITEM", "PAYROLL",
  "DIRECT_DEBIT", "REQUEST_TO_PAY", "BILL_PAYMENT", "QR_PAYMENT", "LINK_PAYMENT", "REFUND", "RETURN"] as const satisfies readonly Payment["payment_type"][];

export const DIRECTIONS = ["OUTBOUND", "INBOUND", "INTERNAL"] as const satisfies readonly Payment["direction"][];

export const CHANNELS = ["API", "MOBILE", "WEB", "BRANCH", "USSD", "AGENT", "BULK_FILE", "OPEN_BANKING", "SCHEDULER",
  "INBOUND_RAIL"] as const satisfies readonly Payment["channel"][];

export const ACCOUNT_ID_TYPES = ["IBAN", "BBAN", "ACCOUNT_NUMBER", "WALLET", "PROXY", "CARD_PAN_TOKEN", "INTERNAL"] as const;

export const CHARGE_BEARERS = ["SHAR", "DEBT", "CRED", "SLEV"] as const;

/** Statuses from which the payment service still accepts a cancel (PaymentLifecycle.CANCELLABLE). */
export const CANCELLABLE = ["INITIATED", "SCREENING", "PENDING_APPROVAL", "APPROVED", "IN_REPAIR"];

/** pacs.004 return reason codes commonly used by operators. */
export const RETURN_REASONS = ["AC01", "AC03", "AC04", "AC06", "AG01", "AM05", "BE04", "MD07", "MS02", "MS03", "RR04",
  "FOCR", "CUST"] as const;

/** camt.056 recall reason codes accepted by the payment service. */
export const RECALL_REASONS = ["DUPL", "FRAD", "TECH", "CUST", "AM09", "AC03"] as const;
