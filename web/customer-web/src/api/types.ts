import type { components as Me } from "./gen/customer-channel";
import type { components as On } from "./gen/onboarding-public";

type S = Me["schemas"];

/** Model types of the customer channel contract (/v1/me/*); they are the owning services' models. */
export type Customer = S["Customer"];
export type CustomerContact = S["CustomerContact"];
export type CustomerAddress = S["CustomerAddress"];
export type CustomerDevice = S["CustomerDevice"];
export type ContactVerification = S["ContactVerification"];
export type KycProfile = S["KycProfile"];
export type KycDocument = S["KycDocument"];
export type DepositAccount = S["DepositAccount"];
export type AccountHolder = S["AccountHolder"];
export type AccountBalance = S["AccountBalance"];
export type LedgerEntry = S["LedgerEntry"];
export type StandingOrder = S["StandingOrder"];
export type Statement = S["Statement"];
export type Payment = S["Payment"];
export type Payee = S["Payee"];
export type Card = S["Card"];
export type CardControl = S["CardControl"];
export type CardSpendLimit = S["CardSpendLimit"];
export type Loan = S["Loan"];
export type ScheduleInstallment = S["ScheduleInstallment"];
export type LoanRepayment = S["LoanRepayment"];
export type Wallet = S["Wallet"];
export type WalletTransaction = S["WalletTransaction"];
export type Budget = S["Budget"];
export type BudgetPeriod = S["BudgetPeriod"];
export type SpendCategory = S["SpendCategory"];
export type FinancialInsight = S["FinancialInsight"];
export type PointsAccount = S["PointsAccount"];
export type PointsLedgerEntry = S["PointsLedgerEntry"];
export type CashbackTransaction = S["CashbackTransaction"];
export type SecureMessageThread = S["SecureMessageThread"];
export type SecureMessage = S["SecureMessage"];
export type Notification = S["Notification"];
export type NotificationPreference = S["NotificationPreference"];
export type CustomerSession = S["CustomerSession"];

/** GET /v1/me/security (`object` in the contract; shape built by customer-channel-service SecurityOverview). */
export interface SecurityOverview {
  authentication: { acr?: string | null; level: number; amr: string[]; auth_time?: string | null; session_id?: string | null };
  /** False when the factors could not be read (no identity provider client configured): then factors is empty. */
  factors_available: boolean;
  factors: { id: string; type: "otp" | "webauthn" | "webauthn-passwordless" | string; label?: string | null; created_at?: string | null }[];
  /** null when unknown (factors not available). */
  has_second_factor: boolean | null;
  step_up: { acr: string; max_age_seconds: number;
    operations: { operation: string; acr: string; max_age_seconds: number; amr: string[]; satisfied: boolean }[] };
  /** Keycloak application-initiated actions (kc_action) for enrolment and removal ({id} = factor id). */
  enrolment: { authenticator_app: string; security_key: string; passkey: string; remove: string };
}

/** Request bodies of the channel: the server fills in customer, debtor details, channel and type. */
export type MyPayment = S["MyPaymentsCreateRequest"];
export type MyPayee = S["MyPayeesCreateRequest"];
export type MyStandingOrder = S["MyStandingOrdersCreateRequest"];

/** Sign-up (no customer yet): models of the public applicant surface of onboarding-service. */
export type OnboardingApplication = On["schemas"]["OnboardingApplication"];
export type OnboardingStep = On["schemas"]["OnboardingStep"];
export type OnboardingOtp = On["schemas"]["OnboardingOtp"];
export type OnboardingApplicantToken = On["schemas"]["OnboardingApplicantToken"];

/**
 * A typed field of a journey step as next-step returns it (the contract types `fields` as untyped items; shape from
 * onboarding-service StepField.toJson: null members are omitted).
 */
export interface StepFieldDef {
  name: string;
  type: "STRING" | "TEXT" | "INTEGER" | "DECIMAL" | "BOOLEAN" | "DATE" | "EMAIL" | "PHONE" | "COUNTRY" | "ENUM" | "DOCUMENT";
  label_key: string;
  required: boolean;
  pattern?: string;
  enum?: string[];
  min_length?: number;
  max_length?: number;
  min?: number | string;
  max?: number | string;
}

/** Response of the public documents action: the registered document and its pre-signed upload. */
export interface ApplicantUpload {
  document_id: string;
  step_code?: string;
  method?: string;
  url?: string;
  headers?: Record<string, string>;
  expires_at?: string;
}

/**
 * Consumer WALLET feature (mobile-money BFF surface, base /v1/me). These endpoints are new in this release and are
 * not in the generated client until `npm run gen:api` is re-run; the shapes below are the app's best inference from
 * the endpoint names and the wallet models already in the contract, and are intentionally tolerant (most fields
 * optional) so a minor server naming difference does not break rendering. Money stays a decimal string throughout.
 */

/** How a counterparty wallet is addressed for lookup / P2P. */
export type WalletHandleType = "MSISDN" | "ALIAS" | "WALLET_NUMBER";

/** POST /v1/me/wallets/lookup → the resolved payee wallet (name shown to the payer for confirmation). */
export interface WalletLookupResult {
  wallet_id?: string;
  display_name?: string;
  masked_name?: string;
  msisdn?: string | null;
  alias?: string | null;
  wallet_number?: string | null;
  currency?: string | null;
  status?: string | null;
}

/** POST /v1/me/wallets/{id}/p2p/quote → fees and the resolved payee before confirmation. */
export interface WalletQuote {
  quote_id?: string;
  amount: string;
  currency?: string;
  fee?: string | null;
  tax?: string | null;
  total_debit?: string | null;
  payee_name?: string | null;
  payee_wallet_id?: string | null;
  payee_msisdn?: string | null;
  expires_at?: string | null;
  warnings?: { code: string; message: string }[];
}

/** Result of a completed wallet money movement (P2P, merchant, QR, bank transfer) — a WalletTransaction. */
export type WalletTxnResult = Partial<WalletTransaction> & { id: string; reference?: string; status?: string; amount?: string; currency?: string };

/** GET/POST /v1/me/money-requests — a request to collect or send money between wallets. */
export interface MoneyRequest {
  id: string;
  direction?: "INCOMING" | "OUTGOING" | string;
  status?: "PENDING" | "PAID" | "DECLINED" | "CANCELLED" | "EXPIRED" | string;
  amount: string;
  currency?: string;
  note?: string | null;
  wallet_id?: string | null;
  requester_wallet_id?: string | null;
  requester_name?: string | null;
  requester_msisdn?: string | null;
  counterparty_name?: string | null;
  counterparty_msisdn?: string | null;
  created_at?: string;
  expires_at?: string | null;
}

/** GET /v1/me/billers (+ /{id}) — a payable biller or airtime operator. */
export interface Biller {
  id: string;
  name: string;
  short_name?: string | null;
  category?: string | null;
  /** AIRTIME, UTILITY, TV, WATER, INTERNET, … — used to separate airtime operators from bill billers. */
  biller_type?: string | null;
  logo_url?: string | null;
  currency?: string | null;
  /** Label for the account/meter/number the customer enters (e.g. "Meter number"). */
  reference_label?: string | null;
  reference_pattern?: string | null;
  min_amount?: string | null;
  max_amount?: string | null;
  /** True when the amount is fixed/looked up by inquiry rather than entered. */
  amount_fixed?: boolean | null;
  supports_inquiry?: boolean | null;
  status?: string | null;
  products?: BillerProduct[];
}

/** A denomination / bundle under a biller (e.g. an airtime bundle or a data plan). */
export interface BillerProduct {
  id: string;
  name: string;
  amount?: string | null;
  currency?: string | null;
  description?: string | null;
}

/** POST /v1/me/billers/{id}/validate-reference → whether the entered account/meter is valid and who it belongs to. */
export interface ReferenceValidation {
  valid?: boolean;
  customer_name?: string | null;
  reference?: string | null;
  message?: string | null;
  errors?: { code: string; message: string }[];
}

/** GET/POST /v1/me/airtime-topups — an airtime or bundle purchase. */
export interface AirtimeTopup {
  id: string;
  status?: string;
  reference?: string | null;
  wallet_id?: string | null;
  biller_id?: string | null;
  biller_name?: string | null;
  biller_product_id?: string | null;
  msisdn?: string | null;
  amount?: string;
  currency?: string | null;
  created_at?: string;
}

/** POST /v1/me/bill-payments/inquire → the bill looked up for a biller + reference. */
export interface BillInquiry {
  bill_inquiry_id?: string;
  biller_id?: string;
  reference?: string;
  customer_name?: string | null;
  amount?: string | null;
  currency?: string | null;
  due_date?: string | null;
  min_amount?: string | null;
  max_amount?: string | null;
  amount_editable?: boolean | null;
  message?: string | null;
}

/** GET/POST /v1/me/bill-payments — a completed bill payment. */
export interface BillPayment {
  id: string;
  status?: string;
  reference?: string | null;
  wallet_id?: string | null;
  biller_id?: string | null;
  biller_name?: string | null;
  bill_reference?: string | null;
  amount?: string;
  currency?: string | null;
  created_at?: string;
}

/** POST /v1/me/qr/decode → what a scanned/pasted QR payload resolves to. */
export interface QrDecoded {
  /** MERCHANT, P2P, BILL, … */
  type?: string;
  payload?: string;
  merchant_name?: string | null;
  merchant_till?: string | null;
  payee_name?: string | null;
  payee_wallet_id?: string | null;
  payee_msisdn?: string | null;
  wallet_number?: string | null;
  biller_id?: string | null;
  reference?: string | null;
  amount?: string | null;
  currency?: string | null;
  /** True when the payer may set/override the amount. */
  amount_editable?: boolean | null;
}

/** POST /v1/me/qr/generate → a receive-money QR for one of the customer's wallets. */
export interface QrCode {
  payload: string;
  /** Data URL of a rendered QR image when the server provides one. */
  image_url?: string | null;
  amount?: string | null;
  currency?: string | null;
  reference?: string | null;
  expires_at?: string | null;
}

/** Response of GET /v1/me/insights/spending (untyped in the contract; shape from insights-service). */
export interface SpendingByCategory {
  customer_id: string;
  from: string;
  to: string;
  currency?: string;
  categories: {
    currency: string;
    spend_category_id: string | null;
    category_code: string | null;
    category_name: string;
    is_income: boolean;
    spend: string;
    income: string;
    transaction_count: number;
    share_of_spend: string;
  }[];
}

/** Response of POST /v1/me/payments/validate (untyped in the contract; shape from payment-service). */
export interface PaymentValidation {
  valid?: boolean;
  errors?: { code: string; message: string }[];
  warnings?: { code: string; message: string }[];
  fee?: { amount?: string; currency?: string };
  total_debit?: string;
  currency?: string;
  execution_date?: string;
  payee_verification?: { result?: string; matched_name?: string };
}
