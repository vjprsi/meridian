import type { components as Cu } from "./gen/customer";
import type { components as On } from "./gen/onboarding";
import type { components as Ky } from "./gen/kyc";
import type { components as De } from "./gen/deposit";
import type { components as St } from "./gen/statement";
import type { components as Pa } from "./gen/payment";
import type { components as Ca } from "./gen/card";
import type { components as Lo } from "./gen/loan";
import type { components as Wa } from "./gen/wallet";
import type { components as In } from "./gen/insights";
import type { components as Re } from "./gen/rewards";
import type { components as Ib } from "./gen/inbox";
import type { components as No } from "./gen/notification";

/** Model types from the owning services' generated contracts. */
export type Customer = Cu["schemas"]["Customer"];
export type CustomerContact = Cu["schemas"]["CustomerContact"];
export type CustomerAddress = Cu["schemas"]["CustomerAddress"];
export type CustomerDevice = Cu["schemas"]["CustomerDevice"];
export type ContactVerification = Cu["schemas"]["ContactVerification"];
export type OnboardingJourney = On["schemas"]["OnboardingJourney"];
export type OnboardingApplication = On["schemas"]["OnboardingApplication"];
export type OnboardingStep = On["schemas"]["OnboardingStep"];
export type KycProfile = Ky["schemas"]["KycProfile"];
export type KycDocument = Ky["schemas"]["KycDocument"];
export type DepositAccount = De["schemas"]["DepositAccount"];
export type AccountBalance = De["schemas"]["AccountBalance"];
export type LedgerEntry = De["schemas"]["LedgerEntry"];
export type StandingOrder = De["schemas"]["StandingOrder"];
export type StandingOrderCreate = De["schemas"]["StandingOrderCreate"];
export type Statement = St["schemas"]["Statement"];
export type Payment = Pa["schemas"]["Payment"];
export type PaymentCreate = Pa["schemas"]["PaymentCreate"];
export type Payee = Pa["schemas"]["Payee"];
export type PayeeCreate = Pa["schemas"]["PayeeCreate"];
export type Card = Ca["schemas"]["Card"];
export type CardControl = Ca["schemas"]["CardControl"];
export type CardSpendLimit = Ca["schemas"]["CardSpendLimit"];
export type Loan = Lo["schemas"]["Loan"];
export type ScheduleInstallment = Lo["schemas"]["ScheduleInstallmentPage"]["data"][number];
export type LoanRepayment = Lo["schemas"]["LoanRepayment"];
export type Wallet = Wa["schemas"]["Wallet"];
export type WalletTransaction = Wa["schemas"]["WalletTransaction"];
export type Budget = In["schemas"]["Budget"];
export type BudgetPeriod = In["schemas"]["BudgetPeriod"];
export type SpendCategory = In["schemas"]["SpendCategory"];
export type FinancialInsight = In["schemas"]["FinancialInsight"];
export type PointsAccount = Re["schemas"]["PointsAccount"];
export type PointsLedgerEntry = Re["schemas"]["PointsLedgerEntry"];
export type CashbackTransaction = Re["schemas"]["CashbackTransaction"];
export type SecureMessageThread = Ib["schemas"]["SecureMessageThread"];
export type SecureMessage = Ib["schemas"]["SecureMessage"];
export type Notification = No["schemas"]["Notification"];
export type NotificationPreference = No["schemas"]["NotificationPreference"];

/** Response of GET /v1/insights/spending/by-category (untyped in the contract; shape from insights-service). */
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

/** Response of POST /v1/payments/validate (untyped in the contract; shape from payment-service). */
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
