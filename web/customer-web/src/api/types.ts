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
