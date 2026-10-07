import type { components } from "./gen/business-channel";

type S = components["schemas"];

/** Model types of the business channel contract (/v1/business/*); they are the owning services' models. */
export type BusinessUser = S["BusinessUser"];
export type BusinessRole = BusinessUser["role"];
export type BusinessEntitlement = S["BusinessEntitlement"];
export type BusinessApprovalRule = S["BusinessApprovalRule"];
export type BusinessPayment = S["BusinessPayment"];
export type BusinessPaymentApproval = S["BusinessPaymentApproval"];
export type BusinessAction = S["BusinessAction"];
export type DepositAccount = S["DepositAccount"];
export type AccountBalance = S["AccountBalance"];
export type LedgerEntry = S["LedgerEntry"];
export type Statement = S["Statement"];
export type Payee = S["Payee"];
export type FxQuote = S["FxQuote"];
export type FxDeal = S["FxDeal"];
export type PaymentMandate = S["PaymentMandate"];
export type DirectDebitCollection = S["DirectDebitCollection"];
export type VirtualAccount = S["VirtualAccount"];
export type PaymentLink = S["PaymentLink"];
export type Payment = S["Payment"];
export type Card = S["Card"];
export type CardControl = S["CardControl"];
export type CardSpendLimit = S["CardSpendLimit"];
export type CardAuthorization = S["CardAuthorization"];
export type CardProduct = S["CardProduct"];
export type Loan = S["Loan"];
export type ScheduleInstallment = S["ScheduleInstallment"];
export type CreditLine = S["CreditLine"];
export type CreditLineDrawdown = S["CreditLineDrawdown"];
export type ApiClient = S["ApiClient"];
export type ApiKey = S["ApiKey"];
export type WebhookEndpoint = S["WebhookEndpoint"];
export type WebhookSubscription = S["WebhookSubscription"];
export type WebhookDelivery = S["WebhookDelivery"];

export type PaymentKind = BusinessPayment["kind"];
export type PaymentStatus = BusinessPayment["status"];

/** One entitlement as GET /v1/business/me returns it. */
export interface MeEntitlement {
  deposit_account_id: string;
  can_view: boolean;
  can_initiate: boolean;
  can_approve: boolean;
  max_amount?: string | null;
}

/** Response of GET /v1/business/me (untyped in the contract): {user, business, entitlements[]}. */
export interface BusinessMe {
  user: BusinessUser;
  business: { id?: string; display_name?: string | null; legal_name?: string | null; customer_number?: string | null;
    [k: string]: unknown };
  entitlements: MeEntitlement[];
}

/** Response of GET /v1/business/cash-position (untyped in the contract). */
export interface CashPosition {
  accounts: { account_id: string; account_number?: string; nickname?: string | null; currency: string;
    available_balance?: string; posted_balance?: string }[];
  totals: { currency: string; available_balance?: string; posted_balance?: string; accounts?: number }[];
  pending_approval?: { currency: string; amount?: string; count?: number }[];
}

/** One validation error of a bulk upload line (shape from the bulk validation; untyped in the contract). */
export interface BulkValidationError {
  line?: number;
  line_no?: number;
  field?: string;
  code?: string;
  message?: string;
}
