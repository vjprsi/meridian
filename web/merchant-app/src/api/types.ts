import type { components } from "./gen/merchant-channel";

type S = components["schemas"];

/** Model types of the merchant channel contract (/v1/merchant/*); several are the owning services' models. */
export type MerchantUser = S["MerchantUser"];
export type MerchantRole = MerchantUser["role"];
export type MerchantUserStore = S["MerchantUserStore"];
export type MerchantStore = S["MerchantStore"];
export type MerchantTerminal = S["MerchantTerminal"];
export type MerchantSale = S["MerchantSale"];
export type SaleStatus = MerchantSale["status"];
export type WalletTransaction = S["WalletTransaction"];
export type MerchantPaymentLink = S["MerchantPaymentLink"];
export type MerchantRefundRequest = S["MerchantRefundRequest"];
export type MerchantSettlement = S["MerchantSettlement"];
export type MerchantChangeRequest = S["MerchantChangeRequest"];
export type MerchantAction = S["MerchantAction"];
export type DepositAccount = S["DepositAccount"];
export type ApiClient = S["ApiClient"];
export type ApiKey = S["ApiKey"];
export type WebhookEndpoint = S["WebhookEndpoint"];
export type WebhookSubscription = S["WebhookSubscription"];
export type WebhookDelivery = S["WebhookDelivery"];

/** Response of GET /v1/merchant/me (untyped in the contract): {user, merchant, stores[]}. */
export interface MerchantMe {
  user: MerchantUser;
  merchant: { id?: string; merchant_code?: string | null; trading_name?: string | null; legal_name?: string | null;
    currency?: string | null; status?: string | null; [k: string]: unknown };
  stores: MerchantStore[];
}

/** Response of GET /v1/merchant/transactions/{id}/refundable (untyped in the contract). */
export interface Refundable {
  transaction_id: string;
  currency: string;
  amount: string;
  refunded: string;
  pending: string;
  refundable: string;
}

/** Response of GET /v1/merchant/fees (untyped in the contract). */
export interface FeeStatement {
  fee_plan?: { code?: string; name?: string; currency?: string; mdr_percentage?: string; fixed_fee?: string;
    min_fee?: string | null; max_fee?: string | null; refund_fee?: string | null; settlement_fee?: string | null } | null;
  from?: string | null;
  to?: string | null;
  currency?: string | null;
  settlements: { settlement_id?: string; id?: string; period_start?: string; period_end?: string; status?: string;
    payment_count?: number; gross_amount?: string; mdr_amount?: string; refund_amount?: string; net_amount?: string }[];
  totals?: { payment_count?: number; gross_amount?: string; mdr_amount?: string; refund_amount?: string;
    net_amount?: string } | null;
}

/** Response of GET /v1/merchant/payout-account (untyped in the contract). */
export interface PayoutAccount {
  settlement_account?: { id?: string; account_number?: string | null; account_number_masked?: string | null;
    currency?: string | null; status?: string | null; nickname?: string | null } | null;
  settlement_frequency?: string | null;
  pending_change?: MerchantChangeRequest | null;
}
