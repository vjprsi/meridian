# Accounts, Products, Balances, Credit and Transactions: Field Catalogue and Gap Analysis

Research date: 2026-10-07. This is a research input for the Meridian spec (`spec/domains/accounts.yaml`, `product.yaml`,
`lending.yaml`, `ledger.yaml`). It does not change any code or spec.

**Goal.** Build a field-level catalogue of how leading cores, BaaS platforms and open-data standards model accounts,
products, balances, credit and transactions. Then list every field, enum, table and API that Meridian is missing, as a
prioritised gap matrix (section 7).

**Legend**

- **Req**: our recommendation for Meridian. M = mandatory column (`nn`), O = optional, RO = server-computed or read-only.
- **Providers**: who has the concept.
  - MB Mambu; GA Galileo (now SoFi Tech Pro); TM Thought Machine Vault; TE Temenos Transact; UN Unit; SY Synctera; TP Treasury Prime; ST Stripe Treasury; MT Modern Treasury; CV Comviva mobiquity.
  - FDX Financial Data Exchange; OB UK Open Banking; BG Berlin Group NextGenPSD2; ISO ISO 20022 camt.05x.
  - REG US/UK regulation.
- **Verified / (u)**: "Verified" means taken from the provider's published OpenAPI or doc page. Rows from memory are marked **(u)** (unverified). Check (u) rows against the source before you code against them.

## 0. Sources

| Provider | URL(s) read | Quality |
|---|---|---|
| Mambu v2 | https://docs.mambu.com/swagger_files/v2/depositproducts.json, deposits.json, deposits_transactions.json, loanproducts.json, loans.json, installments.json, loans_transactions.json, creditarrangements.json, glaccounts.json, indexratesources.json (api.mambu.com now redirects to docs.mambu.com) | Verified from OpenAPI |
| Galileo / SoFi Tech Pro | https://docs.tech.sofi.com/pro/reference/post_getbalance, https://docs.tech.sofi.com/pro/reference/post_getalltranshistory, https://docs.galileo-ft.com/pro/docs/about-transactions, https://docs.galileo-ft.com/pro/reference/api-reference-account-statuses | Balance and transaction history verified; status codes and credit model partly (u) |
| Unit | https://www.unit.co/docs/api/resources/ (deposit accounts, credit accounts, limits, end-of-day, transactions, repayments) | Partly verified (pages truncated) |
| Synctera | https://docs.synctera.com/reference/getaccount, https://docs.synctera.com/v1/docs/create-accounts-guide, https://docs.synctera.com/docs/transactions-guide | Account and transactions verified |
| Treasury Prime | https://docs.treasuryprime.com/reference/get_account.md, https://docs.treasuryprime.com/reference/get_transaction.md, .../account-product.md, .../manual-hold.md | Account and transaction verified |
| Stripe Treasury | https://docs.stripe.com/api/treasury/financial_accounts/object, https://docs.stripe.com/api/treasury/financial_account_features, https://docs.stripe.com/api/treasury/transactions/object, https://docs.stripe.com/api/treasury/transaction_entries/object | Verified |
| Modern Treasury | https://docs.moderntreasury.com/platform/reference/ledger-account-object, .../ledger-transaction-object, .../ledger-entry-object, .../internal-account-object, .../balance-report-object | Partly verified (site rate-limited); rest (u) |
| Thought Machine Vault | https://docs.thoughtmachine.net (now login-gated); names partly confirmed via https://anypoint.mulesoft.com/exchange/org.mule.examples/fins-tm-accounts-sys-api/ | (u) - docs not public |
| Temenos Transact | https://developer.temenos.com/ (service landing pages only; no public AA field detail) | (u) |
| FDX | https://apis.io/schemas/fdx/fdx-depositaccountdescriptor/, https://financialdataexchange.org (spec is members-only) | Deposit descriptor verified; rest (u) |
| UK Open Banking v3.1/v4.0 | https://openbankinguk.github.io/read-write-api-site3/v4.0/resources-and-data-models/aisp/Accounts.html, .../Balances.html, .../Transactions.html, .../Statements.html, .../Products.html, .../StandingOrders.html, .../DirectDebits.html, .../Beneficiaries.html, .../Party.html | Accounts, balances and transactions verified |
| Berlin Group | https://www.berlin-group.org/nextgenpsd2-downloads (Implementation Guidelines v1.3.12) | (u) |
| ISO 20022 | https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets, https://www.iso20022.org/catalogue-messages/additional-content-messages/bank-transaction-codes | (u) |
| Regulation | Reg CC https://www.ecfr.gov/current/title-12/part-229 (2025-07-01 thresholds: https://www.federalreserve.gov/newsevents/pressreleases/files/bcreg20240513a1.pdf); Reg D https://www.federalreserve.gov/newsevents/pressreleases/bcreg20200424a.htm; Reg E https://www.consumerfinance.gov/rules-policy/regulations/1005/; Reg DD https://www.consumerfinance.gov/rules-policy/regulations/1030/; Reg Z / CARD Act https://www.consumerfinance.gov/rules-policy/regulations/1026/; FCA PS19/16 https://www.fca.org.uk/publication/policy/ps19-16.pdf; PSRs 2017 https://www.legislation.gov.uk/uksi/2017/752/contents | Thresholds verified; rest (u) |

**Corrections to common assumptions found during research**

- Reg CC amounts changed on 2025-07-01. The first-day amount is $275 (was $225). The large-deposit, new-account and repeated-overdraft thresholds are $6,725 (was $5,525). The cash-withdrawal amount is $550.
- Mambu enum spellings:
  - Interest rate source is `FIXED_INTEREST_RATE` / `INDEX_INTEREST_RATE`.
  - Loan interest type is `SIMPLE_INTEREST` / `CAPITALIZED_INTEREST` / `COMPOUNDING_INTEREST`.
  - Loan product type also includes `INTEREST_ONLY_EQUAL_INSTALLMENTS`, `DYNAMIC_MORTGAGE`, `REVOLVING_CREDIT_CARD` and `FLEXIBLE_REPAYMENT_MORTGAGE`.
- UK Open Banking v4.0 replaces `AccountType`/`AccountSubType` with `AccountCategory` plus `AccountTypeCode`, which uses ISO ExternalCashAccountType1Code. Balance types became ISO codes (CLAV, CLBD, ...).
- Galileo docs now redirect to docs.tech.sofi.com.

---

## 1. Account types, subtypes, ownership and roles

### 1.1 Account type and subtype taxonomy (union across providers)

| Account type / subtype | Category | Purpose and key behaviour | Providers | Meridian today |
|---|---|---|---|---|
| CHECKING / CURRENT | DEPOSIT | Transactional account; overdraft, cards, cheques | MB CURRENT_ACCOUNT, FDX CHECKING, OB CurrentAccount / CACC, SY CHECKING, TP checking, UN depositProduct, ISO CACC | CURRENT |
| SAVINGS | DEPOSIT | Interest-bearing; may limit withdrawals (Reg D contractual, notice) | MB REGULAR_SAVINGS, FDX SAVINGS, OB Savings / SVGS, SY SAVING | SAVINGS |
| MONEY_MARKET | DEPOSIT | Tiered savings with cheque or debit access | FDX MONEYMARKET, ISO MOMA | MONEY_MARKET |
| CALL / NOTICE | DEPOSIT | Withdrawal on demand / after notice | TE, ISO NTDP | CALL, NOTICE (no notice-request table) |
| CD / TERM / FIXED_DEPOSIT | DEPOSIT | Fixed term and rate; renewal, grace window, early-withdrawal penalty | MB FIXED_DEPOSIT, FDX CD, TM time deposit, TE DEPOSITS, ISO FTDP | term_deposit table |
| SAVINGS_PLAN / RECURRING_DEPOSIT | DEPOSIT | Scheduled contributions to a target by maturity | MB SAVINGS_PLAN | Missing (pots are close but have no maturity) |
| INVESTOR_ACCOUNT | DEPOSIT | Funds loans (P2P / participations) | MB INVESTOR_ACCOUNT | Missing (Could) |
| IRA / ROTH / SEP / SIMPLE / 401K / 403B / 529 / COVERDELL | RETIREMENT / EDUCATION (tax-advantaged) | Contribution limits per tax year, custodian, beneficiaries, distributions, 5498 / 1099-R | FDX investment types | Missing |
| HSA | TAX-ADVANTAGED DEPOSIT | Contribution limits, qualified-expense debits, 5498-SA / 1099-SA | FDX | Missing |
| ISA / LISA / JISA (UK) | TAX-ADVANTAGED DEPOSIT | Annual subscription allowance, flexible-ISA rules | OB Savings + product data | Missing |
| ESCROW | DEPOSIT | Held for a third party (mortgage escrow, trade escrow) | FDX ESCROW, MB | ESCROW (no escrow analysis) |
| TRUST / FBO / CLIENT_MONEY | DEPOSIT (ownership variant) | Titled to a trust or "for benefit of"; pass-through insurance | FDX TRUST, FOR_BENEFIT_OF*, OB BCA ClientAccount | VirtualAccountPurpose CLIENT_MONEY only |
| CUSTODIAL / MINOR (UTMA/UGMA) | ownership variant | Custodian controls until the age of majority | FDX UTMA/UGMA, OB CustodianForMinor | MINOR type + GUARDIAN role (no custodian role or majority date) |
| JOINT | ownership variant | JTWROS / tenants in common / either-or signing | FDX PRIMARY_JOINT, OB PartyType Joint | holders + mandate_rule (no joint tenancy type) |
| BUSINESS_OPERATING / COMMERCIAL | DEPOSIT | Business current account; mandates, analysis fees | FDX COMMERCIALDEPOSIT, OB BusinessCurrentAccount, BG ORGA | via segment SME (no account_category) |
| PAYROLL / SALARY | DEPOSIT | Salary-credit account | ISO SLRY | PAYROLL |
| E-MONEY / WALLET / PREPAID | E-MONEY | Stored value; KYC-tier limits | OB EMoney / PrePaidCard, SY PREPAID, CV wallet types, FDX DIGITAL_WALLET | wallets domain |
| VIRTUAL / SUB-ACCOUNT | DEPOSIT (sub-ledger) | Routes funds to a master; parent/child balances | ST financial_addresses, MT ledger sub-accounts, FDX parentAccountId, ISO VACC | virtual_account, savings_pot |
| POOLED / OMNIBUS | DEPOSIT (internal) | Pooled client funds; balances held off-ledger by the platform | ST, MT, SY GENERAL_LEDGER | Missing as a type (SETTLEMENT is close) |
| NOSTRO / VOSTRO | INTERBANK | Correspondent accounts; reconciled to camt.053 / MT940 | MT internal_account, TE | NOSTRO, VOSTRO |
| GL / INTERNAL / SUSPENSE | INTERNAL | Bank's own books | MT internal_account general_ledger, SY GENERAL_LEDGER | SUSPENSE, SETTLEMENT + ledger_account |
| CREDIT_CARD / CHARGE_CARD / SECURED_CARD | LOC | Revolving / pay-in-full / deposit-secured | FDX CREDITCARD / CHARGE, OB CreditCard / ChargeCard, SY CHARGE_SECURED, CHARGE_UNSECURED, CREDIT_CARD, REVOLVING_CREDIT_SECURED / UNSECURED, MB REVOLVING_CREDIT_CARD, GA Secured Credit | CreditLineType CREDIT_CARD (no charge or secured variant) |
| LINE_OF_CREDIT / HELOC / COMMERCIAL_LOC | LOC | Revolving draws | FDX LINEOFCREDIT, HOMELINEOFCREDIT, COMMERCIALLINEOFCREDIT, SY LINE_OF_CREDIT, MB REVOLVING_CREDIT | REVOLVING, WORKING_CAPITAL |
| OVERDRAFT | LOC | Overdraft on a current account (ISO ODFT) | OB product Overdraft | overdraft_facility |
| INSTALMENT / PERSONAL / AUTO / STUDENT / MORTGAGE / HOME_EQUITY / SMB / MILITARY | LOAN | Amortising loans | FDX LoanAccountType, MB FIXED_TERM / DYNAMIC_TERM / INTEREST_FREE / TRANCHED / DYNAMIC_MORTGAGE / FLEXIBLE_REPAYMENT_MORTGAGE | LoanType (no STUDENT, PERSONAL, HOME_EQUITY) |

### 1.2 Account header fields (deposit account)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| account_category | enum | M | PERSONAL, BUSINESS | Personal vs business; drives regulation and fees | OB v4 AccountCategory, BG usage PRIV/ORGA, FDX lineOfBusiness |
| account_type | enum | M | see 1.1 | Product family at account level | all |
| iso_cash_account_type | code(4) | O | CACC, SVGS, MOMA, LOAN, CARD, ODFT, SLRY, TRAS, VACC, CASH, CHAR, CISH, COMM, CPAC, LLSV, MGLD, NFCA, NREX, ONDP, OTHR, SACC, TAXE, TRAN | Open-banking and camt export | OB v4 AccountTypeCode, BG cashAccountType, ISO |
| ownership_type | enum | M | INDIVIDUAL, JOINT_WROS, JOINT_TENANTS_IN_COMMON, JOINT_EITHER_OR, TRUST, CUSTODIAL_UTMA, CUSTODIAL_UGMA, ESTATE, BUSINESS, FBO, POD | Legal title; survivorship; insurance category | FDX relationships, OB PartyType Sole/Joint/Delegate |
| product_name / display_name | string | RO | | Shown to TPPs | FDX productName, BG product, OB Description |
| nickname | string | O | | Customer label | all |
| masked_account_number | string | RO | | Safe display | UN maskedAccountNumber, SY account_number_masked, FDX accountNumberDisplay |
| secondary_identification | string(34) | O | | Building-society roll number | OB SecondaryIdentification |
| parent_account_id | uuid | O | | Sub-account hierarchy | FDX parentAccountId, MT parent ledger account |
| status | enum | M | PENDING_APPROVAL, APPROVED, PENDING_FUNDING, ACTIVE, ACTIVE_IN_ARREARS / OVERDRAWN, DORMANT, INACTIVE, LOCKED, FROZEN, RESTRICTED, MATURED, CLOSING / PENDING_CLOSE, CLOSED, CLOSED_WRITTEN_OFF / CHARGED_OFF, ESCHEATED, REJECTED, WITHDRAWN | Lifecycle | MB accountState, SY status (13 values incl. CHARGED_OFF, DELINQUENT, FAILED_KYC), UN Open/Frozen/Closed, TP open/closed/close_pending, FDX OPEN/CLOSED/PENDINGOPEN/PENDINGCLOSE/DELINQUENT/NEGATIVECURRENTBALANCE/RESTRICTED, OB Enabled/Disabled/Deleted/ProForma/Pending, GA N/C/Z/W/B/D/I (u) |
| status_update_at | ts | RO | | When the status changed | OB StatusUpdateDateTime |
| freeze_reason | enum | O | FRAUD, COMPLIANCE, DISPUTE, CUSTOMER_REQUEST, ACCOUNT_CLOSURE, LEGAL, KYC | Typed reason | UN freezeReason (+ freezeReasonText) |
| close_reason | enum | O | CUSTOMER_REQUEST, FRAUD, COMPLIANCE, BANK_DECISION, DECEASED, DORMANCY, LOW_BALANCE, CHARGED_OFF, OTHER | Typed reason | UN closeReason, UN accountLowBalanceClosureTransaction |
| fraud_reason | enum | O | ACH_ACTIVITY, CARD_ACTIVITY, CHECK_ACTIVITY, APPLICATION_HISTORY, ACCOUNT_ACTIVITY, CLIENT_IDENTIFIED, IDENTITY_THEFT, LINKED_TO_FRAUDULENT_CUSTOMER | Fraud closure (u) | UN |
| opened_at / approved_at / activated_at / closed_at | ts | RO | | Lifecycle dates | MB approvedDate, activationDate, closedDate; FDX accountOpenDate, accountClosedDate |
| maturity_date | date | O | | Term products and savings plans | MB, OB MaturityDate, FDX |
| locked_at | ts | RO | | Lock date | MB lockedDate |
| last_customer_activity_at | ts | RO | | Dormancy clock | FDX lastActivityDate |
| max_balance | amount | O | | Balance ceiling (e-money, ISA, product cap) | MB maxDepositBalance, SY balance_ceiling |
| balance_floor | amount | O | | JIT top-up floor from a linked account | SY balance_floor |
| max_withdrawal_amount | amount | O | | Per-withdrawal cap | MB maxWithdrawalAmount |
| allowed_rails / features | json | O | ACH, CARD, WIRE, CHECK, CASH, P2P, RTP, FEDNOW, SEPA... with status ACTIVE/PENDING/RESTRICTED | Per-account capability switches | SY is_ach_enabled..., ST features / active_features / pending_features / restricted_features, ST platform_restrictions inbound_flows/outbound_flows, FDX transferIn/transferOut, PaymentNetwork |
| switch_status | enum | O | NOT_SWITCHED, SWITCH_COMPLETED | UK CASS | OB SwitchStatus |
| daca_status | enum | O | NOT_STARTED, ENTERED, ACTIVATED, TERMINATED | Deposit-account control agreement (lender control) (u) | UN dacaStatus |
| deposit_insurance_scheme / insured | enum / bool | O | FDIC, FDIC_PASS_THROUGH, FSCS, DGS, NONE | Insurance status | ST features.deposit_insurance, OB StatementAmount FSCSInsurance |
| reg_e_overdraft_opt_in / opt_in_at | bool / ts | O | | Reg E 1005.17 ATM and one-time debit overdraft consent | REG, MB courtesyPaySettings |
| courtesy_pay_limit | amount | O | | Discretionary overdraft limit | MB allowCourtesyPay / courtesyPayLimit |
| technical_overdraft_allowed | bool | O | | Unarranged overdraft allowed | MB allowTechnicalOverdraft |
| overdraft_expiry_date | date | O | | Arranged overdraft expiry | MB overdraftExpiryDate (we have it on overdraft_facility) |
| interest_withholding_status | enum | O | NOT_SUBJECT, BACKUP_WITHHOLDING, NRA, EXEMPT | US backup withholding and NRA tax | REG (u) |
| apy / aer | rate | RO | | Current disclosed yield | FDX annualPercentageYield, OB AER |
| interest_ytd / interest_prior_year | amount | RO | | Tax reporting | FDX interestYtd, interestPriorYear (we have interest_paid_ytd) |
| micr_number | string | O | | Cheque MICR line | FDX micrNumber |
| ownership_history | json | RO | | Ownership transfers | MB ownershipHistory |
| linked_settlement_account_ids | uuid[] | RO | | Loans repaid from this account | MB linkedSettlementAccountKeys |
| credit_arrangement_id | uuid | O | | Umbrella credit limit | MB creditArrangementKey |

### 1.3 Ownership and roles with rights

| Role | Rights (typical) | Providers | Meridian AccountHolderRole |
|---|---|---|---|
| PRIMARY / SOLE_OWNER | Full: view, transact, close, add holders, receive tax forms | FDX PRIMARY, SOLE_OWNER; OB Principal | PRIMARY |
| JOINT / SECONDARY_OWNER | Full or per mandate; survivorship under JTWROS | FDX PRIMARY_JOINT, SECONDARY_JOINT, *_JOINT_TENANTS; OB SecondaryOwner | JOINT |
| AUTHORISED_USER | Transact (card), no ownership; may have a sub-limit | FDX AUTHORIZED_USER; BG ownerNames.role authorisedUser | AUTHORISED_USER |
| SIGNATORY / AUTHORISED_SIGNATORY | Sign per mandate; no ownership | OB SeniorManagingOfficial; TE | SIGNATORY |
| POWER_OF_ATTORNEY / DELEGATE | Act for the owner within POA scope and dates | FDX POWER_OF_ATTORNEY; OB PowerOfAttorney, PartyType Delegate | POWER_OF_ATTORNEY |
| BENEFICIARY (POD / TOD) | No rights while the owner lives; paid on death by share % (primary / contingent) | FDX BENEFICIARY; OB Beneficiary, SuccessorOnDeath | BENEFICIARY (no share %, no primary/contingent) |
| CUSTODIAN | Controls a minor's UTMA / UGMA account until the age of majority | FDX CUSTODIAN, UNIFORM_TRANSFER_TO_MINOR; OB CustodianForMinor | Missing (GUARDIAN is close) |
| GUARDIAN / LEGAL_GUARDIAN | Court-appointed control | FDX GUARDIAN; OB LegalGuardian | GUARDIAN |
| MINOR | Owner without full capacity | FDX UTMA | MINOR |
| TRUSTEE / SETTLOR / PROTECTOR / GRANTOR | Trust roles | FDX TRUSTEE; OB Granter, Settlor, Protector | TRUSTEE only |
| CO_SIGNER / GUARANTOR / CO_BORROWER | Liable on credit; not an owner | FDX CO_SIGNER, PRIMARY_BORROWER, SECONDARY_BORROWER; MB guarantors | Loan applicant only (not on the booked loan) |
| ADMINISTRATOR / EXECUTOR / LEGAL_REPRESENTATIVE | Deceased estate or corporate representative | OB Administrator; BG legalRepresentative | Missing |
| FOR_BENEFIT_OF | FBO sub-owner | FDX FOR_BENEFIT_OF_* | Missing |
| REGISTERED_SHAREHOLDER / BENEFICIAL_OWNER (business) | KYB | OB RegisteredShareholderName, BeneficialOwnership | party domain |

Rights fields we recommend on account_holder: can_view, can_transact (both exist), can_close, can_manage_holders, can_order_cheques,
can_receive_statements, is_tax_reporting_owner, ownership_percentage, beneficiary_share_pct, beneficiary_rank (PRIMARY/CONTINGENT),
relationship_to_owner, majority_date.

---

## 2. Product configuration

### 2.1 Product header and versioning

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| product_type | enum | M | MB deposit: CURRENT_ACCOUNT, REGULAR_SAVINGS, FIXED_DEPOSIT, SAVINGS_PLAN, INVESTOR_ACCOUNT. MB loan: FIXED_TERM_LOAN, DYNAMIC_TERM_LOAN, INTEREST_FREE_LOAN, TRANCHED_LOAN, REVOLVING_CREDIT, INTEREST_ONLY_EQUAL_INSTALLMENTS, DYNAMIC_MORTGAGE, REVOLVING_CREDIT_CARD, FLEXIBLE_REPAYMENT_MORTGAGE | Engine behaviour | MB type, OB ProductType, TE product line |
| category | enum | O | PERSONAL_DEPOSIT, BUSINESS_DEPOSIT, DAILY_BANKING_ACCOUNTS, BUSINESS_BANKING_ACCOUNTS, STORED_VALUE_ACCOUNTS, SHARIAH_*; PERSONAL_LENDING, PURCHASE_FINANCING, RETAIL_MORTGAGES, SME_LENDING, COMMERCIAL | Regulatory and reporting category | MB category |
| segment (OB) | enum | O | PCA: Basic, BenefitAndReward, CreditInterest, Cashback, General, Graduate, Overdraft, Packaged, Premium, Reward, Student, YoungAdult, Youth. BCA: ClientAccount, Standard, NonCommercialCharities..., Religious, SectorSpecific, Startup, Switcher | UK product-data segment | OB Products |
| state | enum | M | ACTIVE, INACTIVE | Sellable | MB state |
| available_for | enum[] | O | INDIVIDUALS, PURE_GROUPS, SOLIDARITY_GROUPS | Holder types | MB availabilitySettings |
| branch availability | json | O | | Per-branch availability | MB branchSettings |
| allowed_currencies | ccy[] | O | | Multi-currency products | MB currencySettings.currencies (incl. non-fiat such as GOLD), BG currency XXX |
| id_generator_type / id_pattern | enum / string | O | INCREMENTAL_NUMBER, RANDOM_PATTERN | Account number format per product | MB newAccountSettings |
| account_initial_state | enum | O | PARTIAL_APPLICATION, PENDING_APPROVAL, APPROVED, ACTIVE | Auto-approve or not | MB newAccountSettings.accountInitialState |
| four_eyes_for_approval | bool | O | | Maker-checker on account approval | MB fourEyesPrinciple.activeForLoanApproval |
| parameter level | enum | M | TEMPLATE, INSTANCE, GLOBAL | Where a parameter value lives | TM smart-contract parameters |
| parameter update_permission | enum | M | FIXED, OPS_EDITABLE, USER_EDITABLE | Who may change it after opening | TM |
| parameter shape | enum | M | NUMBER, STRING, ACCOUNT_ID, DENOMINATION, DATE, OPTIONAL, UNION | Typed validation | TM shapes (we have ProductParameterType) |
| derived parameters | computed | RO | | Read-only values computed by the product (e.g. next maturity) | TM derived_instance_param_vals |
| product_version_id pinned on account | uuid | M | | Version pinning; contract upgrade | TM product_version_id, TE arrangement, Meridian (has it) |
| accounting_method | enum | O | NONE, CASH, ACCRUAL | Cash vs accrual accounting | MB accountingSettings |
| interest_accrual_posting | enum | O | NONE, AGGREGATED_AMOUNT, BREAKDOWN_PER_ACCOUNT; DAILY, END_OF_MONTH | GL posting granularity and timing | MB interestAccrualCalculation, interestAccruedAccountingMethod |
| accounting role map | {financialResource → GL} | M | 47 roles (PORTFOLIO_CONTROL, INTEREST_RECEIVABLE, SUSPENDED_INTEREST_INCOME, OVERDRAFT_PORTFOLIO_CONTROL, UNAPPLIED_FUNDS, ...) | Product-to-GL mapping by role | MB accountingRules, TE ACCOUNTING property |
| suspended_income_enabled | bool | O | | Non-accrual GL | MB |
| change_notice_days | int | O | | Days of customer notice before a rate or fee change applies to existing accounts | REG (UK BCOBS 2 months, US Reg DD 30 days) |

### 2.2 Interest

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| interest_rate_source | enum | M | FIXED_INTEREST_RATE, INDEX_INTEREST_RATE | Fixed or index-linked | MB |
| interest_rate_terms | enum | M | FIXED, TIERED, TIERED_PERIOD, TIERED_BAND | Tier method | MB; OB TierBandMethod Banded/Tiered/Whole |
| tiers[] (ending_balance, ending_day, rate) | list | O | | Balance or duration tiers | MB interestRateTiers, OB TierBand, TM tiered interest |
| rate default/min/max | decimal interval | O | | Allowed range for account-level negotiation | MB interestRate DecimalInterval |
| index_source / spread / floor / ceiling | ref / rate | O | | Index + margin with collar | MB indexSourceKey, interestSpread, interestRateFloorValue, CeilingValue |
| review_count / review_unit | int / enum | O | DAYS, WEEKS, MONTHS | Reprice cadence | MB interestRateReviewCount/Unit |
| interest_charge_frequency | enum | O | ANNUALIZED, EVERY_MONTH, EVERY_FOUR_WEEKS, EVERY_WEEK, EVERY_DAY, EVERY_X_DAYS | Quoting period of the rate | MB |
| days_in_year | enum | M | ACTUAL_365_FIXED, ACTUAL_364, ACTUAL_360, ACTUAL_ACTUAL_ISDA, E30_360, E30_42_365, BUS_252 | Day count | MB (we lack ACT_364, BUS_252, E30_42_365) |
| interest_calculation_balance | enum | M | MINIMUM, AVERAGE, END_OF_DAY, MINIMUM_TO_END_OF_DAY, FRENCH_INTEREST_ACCRUAL | Balance basis | MB, OB CalculationMethod |
| calculation_frequency / application_frequency | enum | M | Daily, Weekly, Monthly, Quarterly, HalfYearly, Yearly, PerStatementDate, AT_MATURITY | Accrual and posting | OB CalculationFrequency/ApplicationFrequency |
| interest_payment_point / payment_dates | enum / MonthAndDay[] | O | FIRST_DAY_OF_MONTH, EVERY_WEEK, EVERY_OTHER_WEEK, EVERY_MONTH, EVERY_3_MONTHS, ON_FIXED_DATES, DAILY, ANNUALLY, BI_ANNUALLY, ON_ACCOUNT_MATURITY | When interest is paid | MB InterestPaymentSettings |
| destination | enum | M | SELF_CREDIT, PAY_AWAY | Capitalise or pay to another account | OB Destination, MB interestPaidIntoAccount |
| compounding | enum | O | COMPOUND, SIMPLE | Compound or simple | OB CalculationMethod, MB interestType |
| min balance for interest / max balance for interest | amount | O | | Floor and cap on the interest-bearing balance | MB maximumBalance, ST (u) |
| interest_window | date range | O | | Earn interest only between dates | MB interestGainsProvidedStartDate/EndDate |
| allow_negative_interest_rate | bool | O | | Negative rates (EUR / CHF / JPY) | MB allowNegativeInterestRate, negativeInterestAccrued |
| accrue_after_maturity | bool | O | | Keep accruing past maturity | MB accrueInterestAfterMaturity |
| collect_interest_when_locked | bool | O | | Interest on locked accounts | MB |
| bonus / promo rate | rate + conditions | O | | Conditional bonus (no withdrawals in month, deposit ≥ X) | TM bonus interest (u), Meridian promotional_rate |
| AER / APY / EAR / representative APR | rate | RO | | Disclosure rates | OB AER, EAR, RepresentativeAPR; FDX annualPercentageYield; REG Reg DD |
| bank_interest_rate_type | enum | O | GROSS, NET | Rate before or after tax | OB BankInterestRateType |
| fixed_variable | enum | M | FIXED, VARIABLE | Disclosure | OB FixedVariableInterestRateType, FDX interestRateType |

### 2.3 Fees

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| trigger | enum | M | MB deposit: MANUAL, MONTHLY_FEE, ARBITRARY. MB loan: MANUAL, MANUAL_PLANNED, DISBURSEMENT, CAPITALIZED_DISBURSEMENT, UPFRONT_DISBURSEMENT, LATE_REPAYMENT, PAYMENT_DUE, PAYMENT_DUE_APPLIED_ON_DUE_DATES, ARBITRARY, IOF, EARLY_REPAYMENT_CHARGE, FEE_INCLUDED_IN_PMT, CORE_EXTENSION_PENALTY | What charges it | MB |
| fee type catalogue (UK) | code | M | ServiceCAccountFee, ServiceCAccountFeeMonthly, AccountClosing, ATMAbroad, CardReplacement, ChequeStopped, ChequeBook, CounterCash, DirectDebitUnpaid, StandingOrderUnpaid, ForeignCurrencyTransfer, StatementCopy, PaymentCHAPS, PaymentSEPA, ... | Standard fee taxonomy | OB OtherFeesCharges FeeType (u) |
| overdraft fee types (UK) | code | M | ArrangedOverdraft, AnnualReview, EmergencyBorrowing, BorrowingItem, OverdraftRenewal, OverdraftSetup, Surcharge, TempOverdraft, UnauthorisedBorrowing, UnauthorisedPaidTrans, UnauthorisedUnpaidTrans | Overdraft fees | OB OverdraftFeeChargeDetail |
| card statement fee types | code | O | Annual, BalanceTransfer, CashAdvance, CashTransaction, ForeignCashTransaction, ForeignTransaction, Gambling, LatePayment, MoneyTransfer, Monthly, Overlimit, PostalOrder, PrizeEntry, StatementCopy | Card fees | OB StatementFee |
| US fee types | code | M | MONTHLY_MAINTENANCE, OVERDRAFT_ITEM (paid), NSF / RETURNED_ITEM (unpaid), CONTINUOUS_OVERDRAFT, STOP_PAYMENT, WIRE_IN, WIRE_OUT, ATM_OUT_OF_NETWORK, FOREIGN_TRANSACTION, EXCESS_WITHDRAWAL, PAPER_STATEMENT, DEPOSITED_ITEM_RETURNED, LEGAL_PROCESS (garnishment), RESEARCH, EARLY_CLOSURE, INACTIVITY | US deposit fees | GA FE otype (u), UN feeTransaction, REG Reg DD |
| amount_calculation_method | enum | M | FLAT, MAMBU_FUNCTION, LOAN_AMOUNT_PERCENTAGE, REPAYMENT_PRINCIPAL_AMOUNT_PERCENTAGE, LOAN_AMOUNT_PERCENTAGE_NUMBER_OF_INSTALLMENTS, FLAT_NUMBER_OF_INSTALLMENTS, FEE_RATE_ON_OUTSTANDING_PRINCIPAL, IOF_* | Computation | MB |
| apply_date_method | enum | O | MONTHLY_FROM_ACTIVATION, FIRST_OF_EVERY_MONTH | Periodic fee anchor | MB |
| fee_application | enum | O | REQUIRED, OPTIONAL | Optional fees | MB |
| schedule_allocation_method | enum | O | ON_INSTALLMENT, NO_ALLOCATION | Due with an instalment | MB |
| interest_bearing / include_in_penalty | bool | O | | Fee accrues interest | MB |
| amortization profile | enum | O | NONE, SUM_OF_YEARS_DIGITS, STRAIGHT_LINE, EFFECTIVE_INTEREST_RATE (+ frequency, intervalType, periodUnit, behaviour on reschedule) | IFRS 9 deferral of origination fees | MB PeriodIntervalSettings |
| taxable_calculation_method | enum | O | DEFAULT, NON_TAXABLE, CUSTOM_TAX | VAT on fee | MB |
| fee cap | {occurrence, amount, period, min/max type} | O | | Per-period caps, min/max | OB FeeChargeCap (FeeCapOccurrence, CappingPeriod, MinMaxType) |
| fee_applicable_range | amount range | O | | Fee applies only within a range (de minimis) | OB FeeApplicableRange |
| negotiable_indicator | bool | O | | Negotiable fee | OB |
| max occurrences per day | int | O | | e.g. at most 3 overdraft fees per day | REG / industry |
| de minimis threshold | amount | O | | No overdraft fee if overdrawn by less than X | REG / industry |
| fee_free_length / period | int / enum | O | Day, Half Year, Month, Quarter, Week, Year | Fee-free intro period | OB FeeFreeLength |

### 2.4 Overdraft (product level)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| allow_overdraft / max_overdraft_limit | bool / amount | M | | Arranged overdraft | MB overdraftSettings |
| allow_technical_overdraft | bool | M | | Unarranged | MB |
| overdraft interest settings | as 2.2 | M | | Separate tiers and day count | MB overdraftInterestSettings, OB OverdraftTierBand |
| overdraft_type | enum | O | COMMITTED, ON_DEMAND, OTHER | Committed vs on-demand | OB OverdraftType |
| authorised_indicator | bool | M | | Arranged vs unarranged band | OB |
| buffer_amount / bank_guaranteed | amount / bool | O | | Interest-free buffer | OB BufferAmount |
| ear / representative_apr | rate | RO | | FCA single-rate disclosure | OB, REG PS19/16 |
| overdraft fee charges | list | O | see 2.3 | Overdraft fees | OB |
| courtesy pay | bool + limit | O | | Discretionary overdraft (US) | MB courtesyPaySettings |
| cure / grace period | hours / days | O | | No fee if covered by end of day / within 24h | industry |
| linked protection account | uuid | O | | Overdraft protection sweep from savings / LOC | GA Overdraft Protection (u), MB availableProtection |

### 2.5 Limits, minimums, maturity, dormancy, regulation, availability and holds

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| opening_balance default/min/max | amount interval | M | | Initial funding requirement | MB internalControls.openingBalance, TP min_deposit (u) |
| recommended_deposit_amount | amount | O | | Savings-plan guidance | MB |
| max_withdrawal_amount | amount | O | | Per-withdrawal cap | MB |
| maturity_period default/min/max + unit | int interval + enum | M (TD) | DAYS, WEEKS, MONTHS | Term range | MB maturitySettings |
| rail limits ACH / card / check deposit / wire / check payment: daily and monthly, hard and soft | amount | O | | Velocity limits | UN limits (dailyDebit, dailyDebitSoft, monthlyCredit...) |
| spending limits | {day, week, month, lifetime, transaction}{amount, count} | O | | Spend control | SY spending_limits, spend_control_ids |
| dormancy_period_days | int | M | | Days to DORMANT | MB dormancyPeriodDays, TE DORMANCY |
| escheat period / jurisdiction | int / state | M (US) | | Unclaimed property | REG state law |
| Reg D convenient-transfer limit | int + enforced flag + excess fee | O | | Contractual 6/month (federal limit suspended 2020) | REG Reg D |
| Reg CC availability schedule | per deposit type | M (US) | NEXT_DAY, SECOND_DAY, FIFTH_DAY (non-proprietary ATM), CASE_BY_CASE | Funds availability | REG 229.10/.12 |
| Reg CC first-day amount | amount | M (US) | | $275 since 2025-07-01 | REG |
| exception hold reasons | enum | M (US) | NEW_ACCOUNT, LARGE_DEPOSIT (> $6,725), REDEPOSITED_CHECK, REPEATED_OVERDRAFT, REASONABLE_CAUSE_TO_DOUBT, EMERGENCY_CONDITIONS | Exception holds plus notice | REG 229.13 |
| UK credit availability | rule | M (UK) | D+1 electronic, D+2 paper, same-day consumer cash | PSRs 2017 | REG |
| Reg E overdraft opt-in | bool | M (US) | | 1005.17 | REG |
| statement cycle | frequency + day | M | | Statement cadence | OB StatementFrequencyAndFormat, MB |
| eligibility | rules | O | | Age, residency, KYC, score | OB Eligibility, MB availableFor, Meridian eligibility_rule |
| credit_arrangement_requirement | enum | O | OPTIONAL, REQUIRED, NOT_REQUIRED | Account must sit under an umbrella limit | MB |
| allow_offset | bool | O | | Offset savings against a mortgage | MB offsetSettings |
| withholding_tax_enabled | bool | O | | WHT on interest | MB taxSettings |

---

## 3. Balances

### 3.1 Balance type catalogue

| Balance | Definition | Type | ISO code | Providers | Meridian today |
|---|---|---|---|---|---|
| Ledger / current / booked | All posted entries | amount | ITBD (interim), CLBD (closing) | GA ledger_balance, MB totalBalance, TP current_balance, FDX currentBalance, SY ACCOUNT_BALANCE, MT posted_balance, TM committed phase, ST balance.cash (u) | account_balance.posted_balance |
| Available | Ledger − debit holds − blocked + arranged overdraft (product-dependent) | amount | ITAV, CLAV | all | available_balance |
| Pending debits (holds) | Card auth and payment holds | amount | sub-type PDNG | MB holdBalance, UN hold, MT pending, TM PENDING_OUTGOING, ST outbound_pending | pending_debits |
| Pending credits / forward available | Incoming not yet available | amount | FWAV | MB forwardAvailableBalance, TM PENDING_INCOMING, ST inbound_pending, BG forwardAvailable | pending_credits |
| Blocked / held (legal, lien) | Amount blocks | amount | sub-type BLCK / BLKD | MB blockedBalance, BlockFund | Through ledger holds (not reported separately) |
| Locked | Term-locked funds | amount | | MB lockedBalance | Missing |
| Reserved | Reserved for initiated outgoing payments | amount | | UN reserved | Missing (merged in pending) |
| Uncleared / float | Deposited cheques not yet available | amount | Avlbty (days → amount) | ISO Avlbty, OB ClosingCleared / InterimCleared | Missing |
| Cleared | Ledger − uncleared | amount | OB *Cleared | OB | Missing |
| Expected | Balance including future-dated items | amount | XPCD | OB Expected, BG expected | Missing |
| Opening booked / available | Start of day | amount | OPBD, OPAV | OB, ISO, FDX openingDayBalance | balance_snapshot.opening_balance (booked only) |
| Closing booked / available | End of day / statement | amount | CLBD, CLAV | OB, ISO, UN accountEndOfDay | balance_snapshot.closing_balance / available_balance |
| Previously closed booked | Last statement closing | amount | PRCD | ISO, OB | Missing |
| Information | Informational | amount | INFO | ISO, OB | Missing |
| Overdraft used (arranged) | Negative part within the arranged limit | amount | sub-type DLOD | MB overdraftAmount | overdraft_facility.utilised_amount |
| Technical / unarranged overdraft used | Beyond the limit | amount | | MB technicalOverdraftAmount | Missing |
| Overdraft available | Limit − used | amount | CreditLine Type Available | MB availableOverdraft, totalAvailableOverdraft; OB CreditLine | Missing |
| Courtesy / protection available | Discretionary or protection headroom | amount | | MB availableCourtesy, availableProtection | Missing |
| Credit line included flag | Whether the limit is included in available | bool | CdtLine Incl | OB, BG creditLimitIncluded, ISO | Missing |
| Credit line type | Kind of credit line | enum | | OB Available, Credit, Emergency, Pre-Agreed, Temporary | Missing |
| Interest accrued (credit / negative / overdraft / technical overdraft) | Accrued, not posted | amount | | MB accruedAmounts, SY ACCRUED_INTEREST_MTD | accrued_credit_interest / accrued_debit_interest (no negative or technical) |
| Fees due / overdraft interest due | Charged, unpaid | amount | | MB feesDue, overdraftInterestDue, technicalOverdraftInterestDue | Missing |
| Payments received MTD | Payments this month | amount | | SY PAYMENTS_RECEIVED_MTD | Missing |
| Intraday / point-in-time | Balance at timestamp | amount | ITBD/ITAV + DtTm | OB DateTime, BG lastChangeDateTime, MT balances as_of effective_at | balance-at action |
| Value-dated balance | By value date (interest base) | amount | | TM value_time, MT effective_at | Missing (booking-date based) |
| Credit limit / available credit | Revolving | amount | | GA available_balance (credit), FDX creditLine/availableCredit, UN creditLimit, SY credit_limit, MB CreditArrangement.availableCreditAmount | credit_line.credit_limit / available_amount |
| Statement balance / minimum due / past due | Card cycle | amount | | FDX statementBalance, minimumPaymentAmount, pastDueAmount; OB StatementAmount MinimumPaymentDue, ArrearsClosingBalance | credit_line_statement (no past due) |
| Loan components (principal, interest, interest-from-arrears, fees, penalty: due / paid / balance) | Loan buckets | amount | | MB Balances (15 fields), FDX principalBalance, escrowBalance, payOffAmount | loan.outstanding_* (no due/paid split, no escrow) |
| Redraw / credit balance / unapplied funds | Loan overpayment buckets | amount | | MB redrawBalance, creditBalance, unappliedFundsBalance | loan.credit_balance |
| Running balance after transaction | Per entry | amount | | GA rolling_balance, UN balance, TP balance, FDX runningBalance, OB Balance, BG balanceAfterTransaction, MT resulting_ledger_account_balances | ledger_entry.balance_after (posted only) |
| Total value across accounts | Sum in base currency | amount | | OB v4 Data.TotalValue | Missing |

### 3.2 Balance object fields (API shape)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| type | code | M | CLAV, CLBD, FWAV, INFO, ITAV, ITBD, OPAV, OPBD, PRCD, XPCD (+ v3.1 word forms) | Balance type | ISO BalanceType12Code, OB, BG |
| sub_type | code | O | ADJT, BCUR, BLCK, BLKD, CUST, DLOD, FUND, LRLD, PDNG, SCUR, SLOD, TRSR, XCHG | Sub-type | ISO ExternalBalanceSubType1Code (u) |
| amount + credit_debit_indicator | amount + enum | M | CREDIT, DEBIT | Signed by indicator | OB, ISO |
| local_amount | amount | O | | Local-currency equivalent | OB v4 |
| datetime / reference_date | ts / date | M | | As-of | OB, BG |
| last_committed_transaction | string | O | | Last entry included | BG |
| credit_lines[] {included, type, amount, datetime} | list | O | | Limits | OB, ISO, BG |
| availability[] {days or date, amount} | list | O | | Float schedule | ISO Avlbty |
| phase | enum | O | COMMITTED, PENDING_INCOMING, PENDING_OUTGOING | Posting phase | TM |
| address / asset / denomination | string | O | | Balance dimensions (e.g. INTEREST_ACCRUED address) | TM balances |
| lock_version | int | RO | | Optimistic concurrency | MT (we have account_version) |
| currency_exponent | int | RO | | Minor-unit scale | MT |

---

## 4. Credit products

### 4.1 Credit cards and revolving lines

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| line_type | enum | M | REVOLVING, CREDIT_CARD, CHARGE_CARD, SECURED_CARD, HELOC, COMMERCIAL_LOC, OVERDRAFT, BNPL_LIMIT | Facility kind | SY CHARGE_SECURED / CHARGE_UNSECURED / REVOLVING_CREDIT_SECURED / UNSECURED / LINE_OF_CREDIT; FDX CHARGE, CREDITCARD, LINEOFCREDIT, HOMELINEOFCREDIT, COMMERCIALLINEOFCREDIT; MB REVOLVING_CREDIT_CARD |
| credit_limit | amount | M | | Approved limit | all |
| cash_advance_limit / cash_available | amount | O | | Cash sub-limit | FDX cashAdvanceLimit (u) |
| temporary limit + expiry | amount / ts | O | | Seasonal increase | OB CreditLine Temporary, Meridian (has it) |
| secured_deposit_account_id / security_amount | uuid / amount | O | | Secured card collateral; graduation | GA Secured Credit (u), SY *_SECURED |
| credit_arrangement (umbrella) | ref | O | APPROVED_AMOUNT, OUTSTANDING_AMOUNT | Shared limit across several accounts | MB CreditArrangement (amount, availableCreditAmount, consumedCreditAmount, exposureLimitType, state, expireDate) |
| APR by balance segment | rate | M | PURCHASE, CASH_ADVANCE, BALANCE_TRANSFER, PENALTY, PROMO_PURCHASE, PROMO_BALANCE_TRANSFER, INSTALMENT_PLAN | Per-segment interest | TM annual_percentage_rate by txn type (u), FDX purchasesApr / advancesApr / balanceTransferApr, OB StatementRate AnnualPurchase / AnnualCash / AnnualBalanceTransfer, GA APR types (u) |
| variable APR index + margin | ref + rate | O | | Prime + margin (Reg Z variable-rate disclosure) | TM base_interest_rates (u), MB index |
| promo expiry dates | date | O | | Purchase / BT promo end | OB StatementDateTime PurchasePromoEnd, BalanceTransferPromoEnd; TM interest_free_expiry (u) |
| deferred interest flag | bool | O | | "No interest if paid in full" plans | industry |
| penalty APR trigger / cure | rule | O | | Penalty APR after 60 days late; reverts after 6 on-time payments (Reg Z 1026.55) | REG |
| grace_period_days | int | M | | ≥21 days from statement to due (1026.5(b)(2)(ii)) | SY grace_period, GA (u) |
| grace loss / regain rule | enum | O | PAY_STATEMENT_BALANCE, PAY_IN_FULL_TWO_CYCLES | Trailing interest | industry |
| billing cycle (frequency, start, statement day) | object | M | | Cycle | SY billing_period {frequency, start_date}, MB billingCycles.startDays |
| minimum payment formula | enum + params | M | PERCENT_OF_BALANCE, PERCENT_PLUS_INTEREST_AND_FEES, FIXED, GREATER_OF; MB FLAT, OUTSTANDING_PRINCIPAL_PERCENTAGE, PRINCIPAL_PERCENTAGE_LAST_DISB, TOTAL_BALANCE_PERCENTAGE, TOTAL_BALANCE_FLAT, TOTAL_PRINCIPAL_PERCENTAGE | Minimum due | MB principalPaymentSettings (+ floor / ceiling, includeFeesInFloorAmount, includeInterestInFloorAmount); SY minimum_payment; TM minimum_payment_percentage / minimum_payment_fixed_amount / minimum_percentage_due per type (u) |
| minimum includes past due and overlimit | bool | O | | Add arrears and overlimit to the minimum | industry |
| payment allocation | enum | M | HIGHEST_APR_FIRST (above minimum, 1026.53), OLDEST_FIRST, PRO_RATA | CARD Act allocation | REG |
| payment_due_date / days | int | M | | Due date | FDX nextPaymentDate, Meridian payment_due_days |
| autopay mode, amount, day | enum / amount / int | O | NONE, MINIMUM, STATEMENT, FULL, FIXED | Autopay | SY auto_payment_period, Meridian autopay_mode (no amount or day) |
| late fee (first / subsequent, cap) | amount | M | | Safe-harbour tiers | TM late_repayment_fee (u), OB StatementFee LatePayment |
| returned payment fee | amount | O | | NSF on a payment | industry |
| overlimit allowed / opt-in / fee | bool / bool / amount | O | | Reg Z 1026.56 opt-in | TM overlimit, overlimit_opt_in, overlimit_fee (u) |
| annual / cash advance / foreign transaction / balance transfer fee | amount / rate | O | | Card fees | TM annual_fee, cash_advance_fee, transaction_type_fees (u); OB StatementFee |
| accrue interest on unpaid interest | bool | O | | Compounding on cards | TM (u) |
| delinquency: days past due, cycles delinquent, bucket | int / int / enum | RO | | Delinquency | SY days_past_due, GA (u), FDX pastDueAmount |
| charge-off at DPD / charge_off_date / amount | int / date / amount | RO | | 180-day charge-off (FFIEC policy) | GA charge-off (u), SY CHARGED_OFF |
| recovery after charge-off | amount | RO | | Recovery | Meridian loan_recovery (loans only) |
| rewards program / balance / earned / redeemed / expired | object | O | CASHBACK, MILES, POINTS | Rewards | FDX rewards (u), OB StatementValue |
| authorised users + sub-limits | list | O | | Extra cardholders | FDX AUTHORIZED_USER |
| credit underwriting record | object | O | created_from APPLICATION / PROACTIVE_REVIEW; decision CREDIT_LIMIT_APPROVED, CREDIT_LIMIT_DECREASED, APPLICATION_REJECTED, CREDIT_LINE_CLOSED, NO_CHANGES, WITHDRAWN_BY_APPLICANT; ≤4 adverse-action reasons | Adverse-action notices (ECOA / Reg B) | ST Issuing credit_underwriting_record |
| credit balance (overpayment) | amount | RO | | Refund or hold a credit balance | MB revolvingProductCreditBalance (maxCreditBalance, creditBalanceAutoAllocation), refund settings (maxDaysForRefund, LEGACY / FORWARD_ONLY / RETROACTIVE) |

### 4.2 Credit statement (card / LOC)

| Field | Type | Req | Purpose | Providers |
|---|---|---|---|---|
| previous_balance / new_balance | amount | M | Opening and closing | OB PreviousClosingBalance, ClosingBalance; FDX lastStmtBalance |
| purchases / cash_advances / balance_transfers / payments / credits / fees / interest / adjustments | amount | M | Activity summary (Reg Z 1026.7) | OB TotalPurchases, TotalCashAdvances, TotalCharges, TotalCredits, TotalAdjustments |
| interest, APR and balance subject to interest, by segment | amount / rate | M | Segment disclosure | OB StatementInterest Purchase / Cash / BalanceTransfer; StatementRate |
| minimum_payment_due / payment_due_date | amount / date | M | | OB MinimumPaymentDue, PaymentDue |
| past_due_amount / overlimit_amount | amount | M | | OB ArrearsClosingBalance |
| credit_limit / available_credit / cash_available | amount | M | | OB CreditLimit, AvailableBalance |
| fees_ytd / interest_ytd | amount | M | Required totals (1026.7(b)(6)) | REG |
| minimum payment warning (months and total cost at minimum; 3-year payment) | object | M (US) | CARD Act disclosure | REG 1026.7(b)(12) |
| late payment warning (fee and penalty APR) | object | M (US) | | REG |
| days_in_cycle | int | O | | REG |
| promo end dates | date | O | | OB StatementDateTime |
| rewards summary | object | O | | OB StatementValue, StatementBenefit |
| statement type | enum | O | REGULAR_PERIODIC, INTERIM, ANNUAL, ACCOUNT_OPENING, ACCOUNT_CLOSURE | OB Type |

### 4.3 Loans (instalment, mortgage, tranched, dynamic)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| interest_calculation_method | enum | M | FLAT, DECLINING_BALANCE, DECLINING_BALANCE_DISCOUNTED, EQUAL_INSTALLMENTS | Interest engine | MB |
| amortisation method | enum | M | MB STANDARD_PAYMENTS, BALLOON_PAYMENTS, OPTIMIZED_PAYMENTS, PAYMENT_PLAN; TM declining_principal, flat_interest, rule_of_78, interest_only, minimum_repayment_with_balloon_payment, no_repayment; TE CONSTANT, LINEAR, ACTUAL, INTEREST.ONLY | Schedule shape | MB amortizationMethod, TM (u), TE PAYMENT.TYPE (u) |
| interest_type | enum | O | SIMPLE_INTEREST, CAPITALIZED_INTEREST, COMPOUNDING_INTEREST (+ compoundingFrequency DAILY, SEMI_ANNUALLY) | Compounding | MB |
| interest_application_method | enum | O | AFTER_DISBURSEMENT, REPAYMENT_DUE_DATE, FIXED_DAYS_OF_MONTH | When interest is applied | MB |
| interest_balance_calculation | enum | O | ONLY_PRINCIPAL, PRINCIPAL_AND_INTEREST, PRINCIPAL_AND_FEE, PRINCIPAL_INTEREST_AND_FEE | Accrual base | MB |
| schedule_interest_days_count | enum | O | REPAYMENT_PERIODICITY, ACTUAL_DAYS_COUNT | Period-day counting | MB |
| interest_accrual_since | enum | O | DISBURSEMENT, DUE_DATE | Start of accrual | MB |
| schedule_due_dates_method / fixed_days_of_month | enum / int[] | M | INTERVAL, FIXED_DAYS_OF_MONTH | Due-date anchor | MB |
| repayment_period_unit / count | enum / int | M | DAYS, WEEKS, MONTHS, YEARS | Frequency | MB, FDX paymentFrequency |
| num_installments range / first_due_offset range | int interval | O | | Product constraints | MB |
| repayment_rescheduling_method | enum | M | NONE, NEXT_WORKING_DAY, PREVIOUS_WORKING_DAY, EXTEND_SCHEDULE | Holiday handling | MB (Meridian BusinessDayConvention) |
| short_month_handling | enum | O | LAST_DAY_IN_MONTH, FIRST_DAY_OF_NEXT_MONTH | 29–31 due days | MB |
| rounding (currency, elements, remainder) | enums | O | NO_ROUNDING, ROUND_TO_NEAREST_WHOLE_UNIT, ROUND_UP_TO_NEAREST_WHOLE_UNIT; ROUND_ALL, PAYMENT_DUE; ROUND_REMAINDER_INTO_LAST_REPAYMENT, ROUND_PRINCIPAL_AND_INTEREST_REMAINDER_INTO_LAST_REPAYMENT | Instalment rounding | MB roundingSettings |
| amortization_period / periodic_payment | int / amount | O | | Balloon PMT basis | MB amortizationPeriod, periodicPayment |
| grace_period + grace_type | int / enum | O | NONE, PAY_INTEREST_ONLY, INTEREST_FORGIVENESS | Leading grace instalments | MB gracePeriodSettings |
| payment holidays | flag per instalment + term option | O | EXTEND_LOAN_TERM, KEEP_THE_SAME_LOAN_TERM | Holidays | MB isPaymentHoliday, paymentHolidaysLoanTermOption; TE PAYMENT.HOLIDAY |
| schedule edit options | enum[] | O | ADJUST_PAYMENT_DATES, ADJUST_PRINCIPAL_PAYMENT_SCHEDULE, ADJUST_INTEREST_PAYMENT_SCHEDULE, ADJUST_FEE_PAYMENT_SCHEDULE, ADJUST_PENALTY_PAYMENT_SCHEDULE, ADJUST_NUMBER_OF_INSTALLMENTS, ADJUST_PAYMENT_HOLIDAYS | Which manual edits are allowed | MB |
| payment_method (allocation) | enum | M | HORIZONTAL, VERTICAL | Instalment-first vs component-first | MB (Meridian allocate_oldest_first) |
| repayment_allocation_order | enum[] | M | PRINCIPAL, INTEREST, FEE, PENALTY | Waterfall | MB (Meridian has it) |
| late_payments_recalculation_method | enum | O | OVERDUE_INSTALLMENTS_INCREASE, LAST_INSTALLMENT_INCREASE, NO_RECALCULATION | Effect of late payments | MB |
| prepayment acceptance / future payments | enum | M | ACCEPT_PREPAYMENTS, NO_PREPAYMENTS; NO_FUTURE_PAYMENTS, ACCEPT_FUTURE_PAYMENTS, ACCEPT_OVERPAYMENTS | Prepayment policy | MB |
| prepayment_recalculation_method | enum | M | NO_RECALCULATION, RESCHEDULE_REMAINING_REPAYMENTS, RECALCULATE_SCHEDULE_KEEP_SAME_NUMBER_OF_TERMS, ..._KEEP_SAME_PRINCIPAL_AMOUNT, ..._KEEP_SAME_TOTAL_REPAYMENT_AMOUNT, REDUCE_AMOUNT_PER_INSTALLMENT, REDUCE_NUMBER_OF_INSTALLMENTS | Effect of prepayment | MB (Meridian REDUCE_TERM / REDUCE_INSTALMENT) |
| ERC free allowance % / amount / reset date | rate / amount / date | O | | UK mortgage overpayment allowance | MB ercFreeAllowance, ercAllowanceNextResetDate |
| prepayment penalty schedule | tiers by year | O | | Early repayment charge | MB EARLY_REPAYMENT_CHARGE, TE PREPAYMENT.PENALTY, TM overpayment_fee_rate (u) |
| penalty calculation method / grace / rate | enum / int / rate | M | NONE, OVERDUE_BALANCE, OVERDUE_BALANCE_AND_INTEREST, OVERDUE_BALANCE_INTEREST_AND_FEE, OUTSTANDING_PRINCIPAL | Default interest base | MB penaltySettings |
| arrears tolerance (period, method, floor amount, % of principal, monthly day) | mixed | M | ARREARS_TOLERANCE_PERIOD, MONTHLY_ARREARS_TOLERANCE_DAY | Materiality before DPD counts (EBA) | MB arrearsSettings |
| arrears date method | enum | O | ACCOUNT_FIRST_WENT_TO_ARREARS, LAST_LATE_REPAYMENT, ACCOUNT_FIRST_BREACHED_MATERIALITY_THRESHOLD | DPD start | MB |
| non-working days in arrears | enum | O | INCLUDED, EXCLUDED | DPD counting | MB |
| lock / non-accrual (lock period days, capping %) | int / rate | O | SOFT_CAP, HARD_CAP; OUTSTANDING_PRINCIPAL_PERCENTAGE, ORIGINAL_PRINCIPAL_PERCENTAGE | Stop charges after N days; charge caps | MB lockSettings, lockedOperations APPLY_INTEREST / APPLY_FEES / APPLY_PENALTIES; TE NAB state |
| tranches (number, amount, expected date) | list | O | | Staged disbursement | MB tranches, maxNumberOfTranches |
| guarantors (type, amount, pledged deposit) | list | O | CLIENT, GROUP | Personal guarantees | MB guarantors, securitySettings.requiredGuaranties |
| collateral assets (amount, original currency) | list | O | | Security | MB assets |
| investor funding (share %, commission) | list | O | | Participations / P2P | MB fundingSources |
| redraw (allow, balance) | bool / amount | O | | Australian redraw | MB redrawSettings |
| offset with deposit | bool | O | | Offset mortgage | MB offsetSettings |
| linked settlement account + settlement method | uuid / enum | O | FULL_DUE_AMOUNTS, PARTIAL_DUE_AMOUNTS, NO_AUTOMATED_TRANSFERS | Auto-collection | MB accountLinkSettings |
| escrow balance / escrow payment / escrow paid YTD | amount | O | | Mortgage taxes and insurance | FDX escrowBalance, nextPaymentEscrow, escrowPaidYtd (u) |
| next payment split (principal / interest / escrow) | amount | RO | | Borrower view | FDX nextPaymentPrincipal / Interest / Escrow (u) |
| payoff amount | amount | RO | | Payoff | FDX payOffAmount, TE PAYOFF |
| original principal / originating date / total number of payments | mixed | RO | | Borrower view | FDX |
| interest paid YTD / principal paid YTD | amount | RO | | Form 1098 | FDX interestPaidYearToDate |
| APR / effective interest rate | rate | RO | | TILA APR, IFRS 9 EIR | MB effectiveInterestRate |
| account state + sub-state | enum | M | PARTIAL_APPLICATION, PENDING_APPROVAL, APPROVED, ACTIVE, ACTIVE_IN_ARREARS, CLOSED, CLOSED_WRITTEN_OFF, CLOSED_REJECTED / PARTIALLY_DISBURSED, LOCKED, LOCKED_CAPPING, REFINANCED, RESCHEDULED, WITHDRAWN, REPAID, REJECTED, WRITTEN_OFF, TERMINATED, SUSPENDED | Lifecycle | MB |
| balances (principal / interest / interestFromArrears / fees / penalty: due, paid, balance) | amount | RO | | Component balances | MB Balances; TE CUR/DUE/GRC/DEL/NAB/ACC/SUSP balance states (u) |
| instalment states | enum | M | PENDING, LATE, PAID, PARTIALLY_PAID, GRACE | | MB (Meridian has SCHEDULED, DUE, PARTIALLY_PAID, PAID, OVERDUE, WAIVED, RESCHEDULED) |
| instalment components (expected / paid / due / reduced, tax per component) | amount | RO | | Tax on interest and fees per instalment | MB Installment |
| fee amortisation (IFRS 9 EIR) | see 2.3 | O | | Deferral of origination fees | MB |
| restructure / refinance / reschedule links | ref | RO | | Original and new account links | MB originalAccountKey, rescheduledAccountKey |
| delinquency ageing states | enum | O | GRC, DEL, NAB, user-defined ageing states | Configurable ageing | TE OVERDUE property (u) |

---

## 5. Transactions

### 5.1 Posting and transaction fields

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| transaction_id (stable) | string | M | | Same id from pending to posted | SY id stable through lifecycle, FDX transactionId |
| reference_transaction_id / prior_id | string | O | | Links posted to pending, reversal to original | FDX referenceTransactionId, GA prior_id, MB originalTransactionKey / adjustmentTransactionKey, MT reverses_ledger_transaction_id |
| status | enum | M | PENDING, POSTED, VOID / DECLINED / EXPIRED, BOOKED, INFO, FUTURE, MEMO, AUTHORIZATION, ARCHIVED | Lifecycle | ST open/posted/void, SY PENDING/POSTED/DECLINED/EXPIRED, FDX PENDING/MEMO/POSTED/AUTHORIZATION, ISO BOOK/PDNG/INFO/FUTR, MT pending/posted/archived, OB Booked/Pending |
| mutability | enum | O | IMMUTABLE, MUTABLE | Whether the record can still change | OB TransactionMutability |
| booking_date / value_date / transaction_date / auth_ts / post_ts | date / ts | M | | Booking, value and event times | ISO BookgDt/ValDt, OB, BG transactionDate, GA auth_ts/post_ts, ST effective_at |
| amount + credit_debit_indicator | amount + enum | M | CREDIT, DEBIT, MEMO | Signed amount | all |
| original amount / currency / exchange rate / markup | amount / ccy / rate | O | | FX detail | ISO AmtDtls InstdAmt/TxAmt/CntrValAmt, OB CurrencyExchange, BG currencyExchange + markupFee, GA billing_amt/local_amt/settle_amt, MB originalAmount/originalCurrency |
| charges (amount, bearer, type) | list | O | BORN, CRED, DEBT, SHAR, SLEV | Bank charges on the item | ISO Chrgs, OB ChargeAmount |
| balance_impact per sub-balance | amounts | RO | cash, inbound_pending, outbound_pending | How each balance moved | ST balance_impact |
| affected amounts per bucket | amounts | RO | funds, interest, fees, overdraft, technical overdraft... | Bucket split | MB affectedAmounts |
| running balance (booked / available) | amount | RO | | Balance after | GA rolling_balance, UN balance, TP balance, FDX runningBalance, OB Balance, BG balanceAfterTransaction, MT resulting balances, MB accountBalances |
| bank transaction code (ISO) | Domain / Family / SubFamily | M | PMNT / RCDT / ESCT etc. (see 5.3) | Standard classification | ISO BkTxCd, OB BankTransactionCode, BG bankTransactionCode |
| proprietary transaction code + issuer | string | M | | Internal code (GA act_type + otype, e.g. "PMMR") | GA trans_code, OB ProprietaryBankTransactionCode, MT vendor_code + vendor_code_type, TM transaction_code |
| transaction type (business) | enum | M | see 5.2 | Business type | UN types, MB DepositTransaction.type (48), LoanTransaction.type (78), FDX transactionType, TP type, ST flow_type / entry type |
| rail / type_source / network | enum | O | ACH, WIRE, CARD, CHECK, BOOK, RTP, FEDNOW, SEPA, FPS, BACS... | Channel | TP type_source, SY type, ST network, MT type |
| sub-type | string | O | pos_purchase, atm_withdrawal, interest_payout, fee | Finer operation | SY subtype, GA otype |
| category / sub_category | string | O | interest, fees, PFM categories | Categorisation | TP category, FDX category / subCategory |
| description / memo / summary / formatted merchant description | string | M | | Narrative | GA details, formatted_merchant_desc; UN summary; FDX memo; OB TransactionInformation |
| remittance information (unstructured / structured with creditor reference) | text / object | O | | Payer reference | ISO RmtInf, BG remittanceInformation*, OB Reference |
| end_to_end_id / instruction_id / UETR / mandate_id / cheque_number / clearing ref / account servicer ref / entry reference | string | O | | References | ISO Refs, BG entryReference, mandateId, checkId; Meridian (has most) |
| counterparty (name, account, agent BIC, ultimate debtor / creditor) | party | O | | Counterparty | ISO RltdPties / RltdAgts, OB CreditorAccount / DebtorAccount / UltimateCreditor, BG |
| merchant (name, id, MCC, city, country, terminal id, coordinates) | object | O | | Card merchant | GA merchant_id, mcc_code, network_code; OB MerchantDetails; BG cardAcceptorId, cardAcceptorAddress, terminalId; MB cardAcceptor; UN merchant (u) |
| card data (card id / last4, scheme, auth type, auth id, card present, ecommerce, digital wallet) | object | O | ConsumerDevice, Contactless, None, PIN | Card instrument | OB CardInstrument, GA card_id / auth_id, UN (u) |
| disputable | bool | O | | Can be disputed | GA disputable |
| purpose / category purpose code | code | O | | ISO purpose | OB v4 PaymentPurposeCode, BG purposeCode |
| batch indicator / batch count | bool / int | O | | Batch booking | BG batchIndicator, ISO Btch |
| reversal indicator | bool | M | | Reversal | ISO RvslInd |
| return information (ISO reason code) | object | O | AC01, AC04, AM04, MD06, MS03 ... | Returns | ISO RtrInf |
| interest record on entry (type, rate, period) | object | O | INDY, OVRN | Interest detail | ISO Intrst |
| input channel | enum | O | | Origination channel | ISO TechInptChanl, MB transactionChannelKey |
| terms at time of transaction | object | RO | | Rates in force at the time | MB terms |
| tags / user data / external data / fiAttributes / supplementary data | json | O | | Extensions | UN tags, SY user_data / external_data, TP userdata, FDX fiAttributes, OB SupplementaryData |
| fingerprint / trace_id / related transfers | string | O | | De-duplication and linking | TP |
| regulatory receipt URL | string | O | | Reg E receipt | ST hosted_regulatory_receipt_url |
| line items / image ids | list | O | | Cheque images, splits | FDX lineItem, imageIds |
| failure / decline code | enum | O | account_closed, account_frozen, insufficient_funds, international_transaction | Why an inbound credit or debit failed | ST ReceivedCredit / ReceivedDebit failure_code, GA deny_code |
| reversal window / restriction | ts / enum | O | already_reversed, deadline_passed, network_restricted, source_flow_restricted | Return deadline | ST reversal_details |

### 5.2 Transaction type catalogue (union)

| Family | Values seen |
|---|---|
| Deposit core (MB, 48) | DEPOSIT, WITHDRAWAL, TRANSFER, ADJUSTMENT, FEE_APPLIED, FEE_ADJUSTED, FEES_DUE_REDUCED, INTEREST_APPLIED, INTEREST_APPLIED_ADJUSTMENT, NET_DIFF_INTEREST, WITHHOLDING_TAX, INTEREST_RATE_CHANGED, OVERDRAFT_INTEREST_RATE_CHANGED, OVERDRAFT_LIMIT_CHANGED, OVERDRAFT_LIMIT_EXPIRED, BRANCH_CHANGED, ACCOUNT_HOLDER_CHANGED, SEIZED_AMOUNT, COURTESY_PAY_LIMIT_CHANGED, LOAN_FUNDED, LOAN_REPAID, LOAN_FRACTION_BOUGHT / SOLD, PROFIT_APPLIED (Islamic), WRITE_OFF, IMPORT, CARD_TRANSACTION_REVERSAL, REVERSAL_*, *_ADJUSTMENT |
| Loan core (MB, 78) | DISBURSEMENT, REPAYMENT, ADVANCE_REPAYMENT, PAYMENT_MADE, FEE_APPLIED / CHARGED / CAPITALISED / REFUND, PENALTY_APPLIED, INTEREST_ACCRUED, INTEREST_APPLIED, DEFERRED_INTEREST_APPLIED / PAID, INTEREST_LOCKED / UNLOCKED, FEE_LOCKED, PENALTY_LOCKED, REDRAW_TRANSFER / REPAYMENT, WITHDRAWAL_REDRAW, TERMS_CHANGED, DUE_DATE_CHANGED, ACCOUNT_TERMINATED / SUSPENDED / UNSUSPENDED, REFUND, REDUCE_BALANCE, PRINCIPAL_OVERPAYMENT, CREDIT_BALANCE_DEPOSIT / WITHDRAWAL, PAYMENT_HOLIDAYS_INTEREST_CAPITALISED, WITHDRAWAL_UNAPPLIED_FUNDS, WRITE_OFF, *_ADJUSTMENT |
| BaaS (UN) | originatedAch, receivedAch, returnedAch, returnedReceivedAch, dishonoredAch, book, purchase, atm, fee, interest, sponsoredInterest, adjustment, cardReversal, release, dispute, chargeback, wire, cashDeposit, checkDeposit, returnedCheckDeposit, checkPayment, paymentAdvance, repaidPaymentAdvance, paymentCanceled, reward, negativeBalanceCoverage, customerRepayment, bankRepayment, accountLowBalanceClosure (mostly (u)) |
| TP | charge, deposit, hold, hold_release, interest, payment, reversal, withdrawal |
| ST entry types | credit_reversal(_posting), debit_reversal, inbound_transfer(_return), issuing_authorization_hold / release, outbound_payment(_cancellation / _failure / _posting / _return), outbound_transfer(_cancellation / _failure / _posting / _return), received_credit, received_debit, other |
| FDX deposit | ADJUSTMENT, ATMDEPOSIT, ATMWITHDRAWAL, BILLPAYMENT, CHECK, DEPOSIT, DIRECTDEPOSIT, DIVIDEND, FEE, INTEREST, POSCREDIT, POSDEBIT, TRANSFER, WITHDRAWAL |
| FDX loan / LOC | PAYMENT, FEE, ADJUSTMENT, INTEREST, LUMP_SUM_PAYMENT, SKIP_PAYMENT, DOUBLE_UP_PAYMENT, PAYOFF; PURCHASE, CASHADVANCE, BALANCETRANSFER, CHECK, WITHDRAWAL, REWARD (u) |
| GA act_type | PM payment, AD adjustment, FE fee, authorisations and settlements by network (VS / VI / MS / MP / DA...) (u) |
| TM posting instructions | outbound_authorisation, inbound_authorisation, authorisation_adjustment, settlement, release, inbound_hard_settlement, outbound_hard_settlement, transfer, custom_instruction |
| TE activities | `<LINE>-<ACTION>-<PROPERTY>`, e.g. LENDING-DISBURSE-COMMITMENT, LENDING-MAKEDUE-SCHEDULE, LENDING-AGE-OVERDUE, ACCOUNTS-CAPITALISE-SCHEDULE, DEPOSITS-ROLLOVER-ARRANGEMENT (u) |
| MT rails | ach, au_becs, bacs, book, chats, check, cross_border, dk_nets, eft, gb_fps, hu_ics, interac, masav, mx_ccen, neft, nics, nz_becs, pl_elixir, provexchange, ro_sent, rtp, se_bankgirot, sen, sepa, sg_giro, sic, signet, sknbi, wire, zengin, other |

### 5.3 ISO 20022 Bank Transaction Codes (top level, (u))

| Level | Codes |
|---|---|
| Domain | PMNT, CAMT, DERV, LDAS, FORX, SECU, ACMT, CMDT, TRAD, PMET, XTND |
| PMNT families | RCDT, ICDT, RDDT, IDDT, CCRD, MCRD, CNTR, LBOX, DRFT, RCHQ, ICHQ, IRCT, RRCT, MCOP, MDOP, NTAV |
| ACMT families | MCOP, MDOP, OPCL, ACOP |
| LDAS families | FTDP, FTLN, NTDP, NTLN, MGLN, CSLN, SYDN |
| CAMT families | ACCB, CAPL |
| Common sub-families | ESCT, SALA, STDO, BOOK, DMCT, XBCT, FICT, ESDD, BBDD, PMDD, UPDD, RRTN, CHRG, INTR, FEES, ADJT, COMM, TAXE, CWDL, CDPT, POSD, POSC, SMRT, ACCC, ACCO, SWEP, TOPG, ZABA, INST, RPCR, OTHR, NTAV |

### 5.4 Standing orders, direct debits, beneficiaries, scheduled payments (open-data view)

| Field | Type | Enums | Providers | Meridian |
|---|---|---|---|---|
| StandingOrder frequency pattern | string | OB: EvryDay, EvryWorkgDay, IntrvlDay:NN, IntrvlWkDay, WkInMnthDay, IntrvlMnthDay (incl. last day of month), QtrDay ENGLISH / SCOTTISH / RECEIVED. BG: Daily, Weekly, EveryTwoWeeks, Monthly, EveryTwoMonths, Quarterly, SemiAnnual, Annual, MonthlyVariable + executionRule following / preceding + dayOfExecution | OB, BG | ScheduleFrequency (no working-day, week-in-month or month-end patterns) |
| first / next / last / final payment date and amount | ts / amount | | OB | single amount, next date |
| number_of_payments | int | | OB | max_executions |
| status | enum | Active, Inactive | OB | StandingOrderStatus |
| DirectDebit mandate id / name / status / previous payment date and amount | mixed | Active, Inactive | OB | payment_mandate (payments domain); no previous payment view |
| Beneficiary type | enum | Trusted, Ordinary | OB | payee.trusted |
| ScheduledPayment scheduled_type | enum | Arrival, Execution | OB | Missing (standing_order ONCE only; no arrival vs execution) |

### 5.5 Ledger-platform patterns worth copying

| Pattern | Detail | Provider | Meridian today |
|---|---|---|---|
| Pending journal entries | Two-phase transactions: pending → posted / archived; pending balance = pending + posted | MT LedgerTransaction status | Holds only (debit side); no pending credits API |
| Balance conditions on posting | Reject the posting unless available_balance_amount satisfies gte / lte / eq; archived with `balance_lock_failure` | MT | allow_negative / min_balance only |
| Ledger account categories | Many-to-many roll-ups with category balances | MT LedgerAccountCategory | parent_id + gl_account_id only |
| Balance monitors | Alert when available, pending or posted crosses a threshold | MT LedgerAccountBalanceMonitor | Missing |
| Ledger account statements | Starting and ending balances for any effective_at window | MT LedgerAccountStatement | balance_snapshot (daily only) |
| Settlements | Zero out an account against a contra account up to effective_at | MT LedgerAccountSettlement | Missing |
| Balance addresses | Several named balances per account (DEFAULT, ACCRUED_INTEREST, PRINCIPAL_DUE...) | TM | One balance per ledger_account (sub-accounts instead) |
| Restrictions and flags | Typed restriction sets (prevent debits / credits / closure / param update) at account, customer or device level; contract-visible flags (ACCOUNT_DORMANT, REPAYMENT_HOLIDAY) | TM | account_block + ledger freeze (no customer-level or flag model) |
| Feature flags per account | Feature status ACTIVE / PENDING / RESTRICTED with reason codes and resolutions | ST FinancialAccountFeatures | Missing |
| Balance reports from banks | intraday / previous_day / real_time with ledger and available balance types (BAI2 / camt) | MT BalanceReport | recon_file opening / closing only |

---

## 6. Provider notes not covered above

| Provider | Note |
|---|---|
| Thought Machine Vault | The docs portal (docs.thoughtmachine.net) now sits behind a login (vault-portal / auth.thoughtmachine.net). TM rows come from knowledge, plus names confirmed on a public MuleSoft Exchange template: https://anypoint.mulesoft.com/exchange/org.mule.examples/fins-tm-accounts-sys-api/. TM's Python contracts implement products, unlike our declarative product versions. Our WASM `product_script` hooks match TM's hooks: activation, pre_posting, post_posting, scheduled_event, conversion, derived_parameter, pre/post_parameter_change, deactivation. We lack `derived_parameter`, `pre_parameter_change` and `conversion` (version upgrade) hooks. |
| Temenos Transact | developer.temenos.com has no public field-level AA detail; Temenos rows are (u). The AA model is: product line → group → product (inheritance) → arrangement; properties by property class; balances named `<STATE><PROPERTY>` (CURACCOUNT, DUEACCOUNT, GRCACCOUNT, DELACCOUNT, NABACCOUNT). That generalises our fixed columns, `outstanding_*` and `overdue_amount`. |
| Comviva mobiquity Pay | Public brochures could not be loaded (PDFs over 10 MB); rows are from search snippets (u). It has multiple wallets per user: main SVA, commission, bonus / loyalty, remittance, savings, escrow / settlement. It has grades per user category (KYC levels), plus TCP transfer-control profiles per grade and wallet: min / max per transaction, daily / weekly / monthly count and value, max balance. Our wallets domain has `WalletType` (CUSTOMER, MERCHANT, AGENT, SUPER_AGENT, FLOAT, COMMISSION, ESCROW, G2P, TREASURY) and `wallet_tier_limit`. Missing are wallet sub-purses per user (BONUS, LOYALTY, SAVINGS, REMITTANCE) and "barred as sender / receiver" statuses. These belong in the wallets domain. |
| Stripe Treasury v2 | Financial-account `type` (storage, payments, other), `status` (pending, open, closed), close `forwarding_settings` (where residual funds go), `funds_usage_type` (consumer, business). |

---

## 7. Gap matrix (Meridian spec vs catalogue)

Columns:
- **Gap**: the missing field, enum, table or API.
- **Kind**: F field, E enum value(s), T new table, A API action, V event.
- **Pri**: M Must (needed for correctness, regulation or core parity), S Should (parity with mainstream cores / BaaS), C Could (niche or market-specific).
- **Target**: the table or resource it belongs in.

Rows are grouped by target.

### 7.1 accounts.yaml: deposit_account

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 1 | account_category (PERSONAL, BUSINESS) | F+E | M | deposit_account | OB v4 AccountCategory, BG usage; drives fees and regulation |
| 2 | ownership_type (INDIVIDUAL, JOINT_WROS, JOINT_TENANTS_IN_COMMON, JOINT_EITHER_OR, TRUST, CUSTODIAL_UTMA, CUSTODIAL_UGMA, ESTATE, BUSINESS, FBO, POD) | F+E | M | deposit_account | FDX relationships; survivorship and insurance category |
| 3 | iso_cash_account_type (ExternalCashAccountType1Code) | F | S | deposit_account | OB v4 AccountTypeCode, BG cashAccountType, camt.053 |
| 4 | DepositAccountType += IRA, ROTH_IRA, HSA, ISA, CERTIFICATE_OF_DEPOSIT, SAVINGS_PLAN, TRUST, CUSTODIAL, BUSINESS_OPERATING, OMNIBUS, FBO, EMONEY, INVESTOR | E | S | DepositAccountType | FDX, MB, OB; IRA / HSA / ISA need contribution tracking (rows 60–62) |
| 5 | DepositAccountStatus += APPROVED, OVERDRAWN (ACTIVE_IN_ARREARS), LOCKED, MATURED, CHARGED_OFF, ESCHEATED, WITHDRAWN | E | M | DepositAccountStatus | MB accountState, SY CHARGED_OFF, DELINQUENT; negative-balance charge-off is common |
| 6 | status_updated_at | F | S | deposit_account | OB StatusUpdateDateTime |
| 7 | freeze_reason enum + close_reason enum (typed, not free text) | F+E | S | deposit_account | UN freezeReason / closeReason |
| 8 | approved_at, activated_at (separate from opened_at), approved_by | F | S | deposit_account | MB approvedDate / activationDate |
| 9 | masked_account_number (ro) | F | S | deposit_account | UN, SY, FDX accountNumberDisplay |
| 10 | secondary_identification (roll number) | F | C | deposit_account | OB |
| 11 | parent_account_id (sub-accounts other than pots / virtual) | F | S | deposit_account | FDX parentAccountId |
| 12 | max_balance (balance ceiling) | F | M | deposit_account / product | MB maxDepositBalance, SY balance_ceiling, TM maximum_balance; e-money and ISA caps |
| 13 | balance_floor + floor_funding_account_id (JIT top-up) | F | C | deposit_account | SY balance_floor |
| 14 | max_withdrawal_amount | F | S | deposit_account / product | MB |
| 15 | technical_overdraft_allowed + unarranged_overdraft_used (ro) | F | M | deposit_account | MB allowTechnicalOverdraft, technicalOverdraftAmount; FCA unarranged pricing |
| 16 | reg_e_overdraft_opt_in, opt_in_at, opt_in_channel | F | M | deposit_account | Reg E 1005.17; needed to charge ATM / one-time debit overdraft fees (US) |
| 17 | courtesy_pay_enabled + courtesy_pay_limit | F | S | deposit_account | MB courtesyPaySettings |
| 18 | overdraft_protection_account_id (linked savings / LOC sweep) | F | S | deposit_account | GA Overdraft Protection, MB availableProtection |
| 19 | accrued_negative_interest, accrued_unarranged_od_interest (ro) | F | S | deposit_account | MB accruedAmounts |
| 20 | fees_due / interest_due (charged, unpaid) (ro) | F | S | deposit_account | MB feesDue, overdraftInterestDue |
| 21 | interest_paid_prior_year (ro) | F | S | deposit_account | FDX interestPriorYear (1099-INT corrections) |
| 22 | apy_current / aer_current (ro) | F | S | deposit_account | FDX annualPercentageYield, Reg DD |
| 23 | last_interest_applied_date, last_rate_review_date, last_appraisal_date (ro) | F | S | deposit_account | MB |
| 24 | locked_amount / lock (term-locked funds) | F | C | deposit_account | MB lockedBalance |
| 25 | interest_withholding_status (NOT_SUBJECT, BACKUP_WITHHOLDING, NRA, EXEMPT) + tax_residency_country | F+E | M | deposit_account | US backup withholding / FATCA / CRS on interest |
| 26 | deposit_insurance_scheme + insured flag / insured_amount | F | S | deposit_account | ST features.deposit_insurance, FSCS / FDIC disclosures |
| 27 | features / rail capabilities [{feature, status ACTIVE/PENDING/RESTRICTED, reason}] | T | S | new account_feature | ST FinancialAccountFeatures, SY is_*_enabled, FDX transferIn/Out |
| 28 | platform_restrictions inbound_flows / outbound_flows | F | S | deposit_account | ST; partner-imposed debit / credit freeze distinct from bank blocks |
| 29 | switch_status (UK CASS) + switch reference | F | C | deposit_account | OB SwitchStatus |
| 30 | daca_status (deposit account control agreement) | F | C | deposit_account | UN dacaStatus |
| 31 | credit_arrangement_id (umbrella limit) | F | C | deposit_account | MB creditArrangementKey |
| 32 | escheat_jurisdiction (state of last known address) + escheat_due_date (ro) | F | M | deposit_account | US unclaimed property (escheat) |
| 33 | allowed_currencies / multi-currency pockets | F | C | deposit_account | MB currencySettings, BG XXX multicurrency |
| 34 | ownership_history (transfer of ownership) | A | C | deposit-accounts | MB TransferOwnershipAction: POST /{id}/transfer-ownership |
| 35 | Reg D convenient-transfer counter (cycle count, limit, enforced) | F | S | deposit_account | Reg D; contractual 6-per-month savings limit + excess fee |
| 36 | micr_number | F | C | deposit_account | FDX |
| 37 | instance parameter values (generic overrides beyond rates) with history | T | S | new account_parameter_value | TM instance_param_vals, update_permission; today only account_rate_override + product_terms json |
| 38 | derived parameter values (ro, computed) | A | C | deposit-accounts | TM derived_instance_param_vals: GET /{id}/derived-parameters |
| 39 | GET /{id}/balances returning ISO-typed list (CLAV, CLBD, ITAV, ITBD, OPAV, OPBD, XPCD, FWAV, PRCD, INFO) with credit_debit_indicator, credit_lines[] and datetime | A | M | deposit-accounts | OB / BG / camt; today only ledger, available and pending |
| 40 | GET /{id}/transactions should include pending items (holds) with status PENDING / BOOKED and a pending → posted link | A | M | deposit-accounts | FDX, OB, BG, ST, SY; today ledger entries only |
| 41 | POST /{id}/reopen (closed or withdrawn account) | A | S | deposit-accounts | MB ReopenDepositAction |
| 42 | POST /{id}/lock and /unlock (distinct from freeze) | A | C | deposit-accounts | MB LOCK / UNLOCK |
| 43 | POST /{id}/write-off (charge off negative balance) + negative-balance ageing | A+T | M | deposit-accounts | MB CLOSE_WRITE_OFF, SY CHARGED_OFF; overdrawn accounts with no facility |
| 44 | POST /{id}/apply-withholding-tax-change | A | C | deposit-accounts | MB ChangeWithholdingTaxAction |
| 45 | Event accounts.deposit_account.overdrawn / overdraft_cured | V | S | events | MB lastSetToArrearsDate |

### 7.2 accounts.yaml: holders, roles, beneficiaries

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 46 | AccountHolderRole += CUSTODIAN, SECONDARY_OWNER, CO_SIGNER, FOR_BENEFIT_OF, SETTLOR, GRANTOR, PROTECTOR, ADMINISTRATOR / EXECUTOR, LEGAL_REPRESENTATIVE, SUCCESSOR_ON_DEATH, DELEGATE | E | M | AccountHolderRole | FDX relationships, OB AccountRole, BG ownerNames.role; CUSTODIAN and EXECUTOR are needed for minors and deceased estates |
| 47 | beneficiary_share_pct + beneficiary_rank (PRIMARY, CONTINGENT) + relationship_to_owner | F | M | account_holder | POD / TOD designations |
| 48 | ownership_percentage | F | S | account_holder | Tenants in common; insurance calculation |
| 49 | majority_date (minor becomes adult) + auto role conversion | F | S | account_holder | FDX UTMA; valid_to exists but there is no conversion action |
| 50 | can_close, can_manage_holders, can_order_cheques, can_receive_statements, is_tax_reporting_owner | F | S | account_holder | Rights matrix |
| 51 | specimen signature / signature document | F | C | account_holder | Branch cheque verification |
| 52 | account_holder_history (append-only) | T | S | new account_holder_history | Audit of role changes; MB ACCOUNT_HOLDER_CHANGED transaction |
| 53 | Deceased handling: estate workflow (date of death, notified_at, executor, release of funds) | T | S | new account_deceased_case | OB SuccessorOnDeath, Administrator; AccountBlockType DECEASED exists but no case |

### 7.3 accounts.yaml: term deposits, notice, savings plans, tax-advantaged

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 54 | grace_period_days (renewal window after maturity) + grace_ends_on (ro) | F | M | term_deposit | TM grace_period; US CDs (7–10 day grace) |
| 55 | rollover_rate_policy (CURRENT_PRODUCT_RATE, SAME_RATE, BEST_OF) + rollover_product_id + rollover_term_days | F | M | term_deposit | Renewal behaviour |
| 56 | maturity_notice_days + maturity_notice_sent_at | F | S | term_deposit | Reg DD 1030.5 renewal notice; event term_deposit.maturing exists |
| 57 | early_withdrawal_penalty_method (DAYS_OF_INTEREST, RATE_REDUCTION, PERCENT_OF_PRINCIPAL, FLAT) + penalty_days | F+E | M | term_deposit / product | Today only premature_penalty_rate |
| 58 | cool_off_period_days (cancel without penalty) | F | S | term_deposit | TM cool_off_period |
| 59 | term_unit (DAYS, WEEKS, MONTHS) + term_value | F | S | term_deposit | MB maturityPeriodUnit; today only term_days |
| 60 | allow_top_up flag + max_top_ups, fee_free_withdrawal_percentage | F | S | term_deposit / product | TM maximum_withdrawal_percentage, fee_free_withdrawal_percentage |
| 61 | apy (ro) | F | S | term_deposit | Reg DD |
| 62 | CD variants: bump_up_allowed, step_up schedule, callable, no_penalty, brokered | F | C | term_deposit | US market |
| 63 | notice_withdrawal_request (notice account: amount, notice_given_on, available_on, status) | T+A | M | new notice_withdrawal | NOTICE type exists but there is no way to give notice |
| 64 | savings_plan (recurring deposit: instalment amount, frequency, target, maturity, missed instalments) | T | S | new savings_plan or term_deposit variant | MB SAVINGS_PLAN; common in India / Africa (RD) |
| 65 | tax_advantaged_contribution (tax_year, contribution_type, amount, limit, source) for IRA / HSA / ISA | T | S | new contribution | IRS 5498 / 5498-SA, UK ISA subscription allowance |
| 66 | retirement_distribution (type, withholding, code) | T | C | new distribution | 1099-R |
| 67 | TaxCertificateType += FORM_1098, FORM_1099_R, FORM_5498, FORM_5498_SA, FORM_1042_S | E | S | TaxCertificateType | US tax reporting |

### 7.4 accounts.yaml: overdraft, blocks, holds, availability, dormancy, escheat

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 68 | overdraft_facility: buffer_amount, ear, representative_apr, review_frequency, last_reviewed_on | F | M | overdraft_facility | OB OverdraftTierBand, FCA PS19/16 |
| 69 | overdraft_facility: tiered rates (tier band set) | F | S | overdraft_facility / interest_rate_config | OB OverdraftTierBand |
| 70 | OverdraftType += COMMITTED vs ON_DEMAND attribute; EMERGENCY credit line | E | C | OverdraftType | OB OverdraftType, CreditLine Emergency |
| 71 | funds_availability_policy (deposit_item_type, availability_days, first_day_amount, cash_withdrawal_amount, jurisdiction, effective_from) | T | M | new (accounts or product) | Reg CC 229.10 / .12, PSRs 2017 D+1 |
| 72 | deposit_hold / exception hold (reason NEW_ACCOUNT, LARGE_DEPOSIT, REDEPOSITED_CHECK, REPEATED_OVERDRAFT, REASONABLE_CAUSE_TO_DOUBT, EMERGENCY_CONDITIONS; available_on; notice_id) | T | M | new deposit_hold (links to ledger hold) | Reg CC 229.13; notice obligation |
| 73 | availability schedule per hold (staged release: amount by date) | F | M | ledger hold | ISO Avlbty; first $275 next day, rest later |
| 74 | account_block: seize action + seized_amount (garnishment payout) | F+A | M | account_block | MB BlockFund seizedAmount, SEIZED_AMOUNT txn; POST /account-blocks/{id}/seize |
| 75 | AccountBlockType += LEVY, BANKRUPTCY, SANCTIONS, PROBATE | E | S | AccountBlockType | Legal process variety |
| 76 | garnishment protected amount (exempt federal benefits, 2-month lookback, 31 CFR 212) | F | M (US) | account_block | Treasury rule: protected amount must stay available |
| 77 | dormancy parameters typed: inactive_days, dormant_days, escheat_days, warning_days, what counts as activity | F | M | product parameters | MB dormancyPeriodDays, TE DORMANCY; today free param keys only |
| 78 | unclaimed_property (escheat) record: jurisdiction, report_year, due_diligence_letter_sent_at, amount, remitted_at, holder_report_ref, reclaimed | T | M | new unclaimed_property_item | DormancyEventType ESCHEATED exists but there is no record or report |
| 79 | unclaimed_property_report (state, year, file, status) | T | S | new | NAUPA file to states; UK Dormant Assets scheme (Reclaim Fund) |
| 80 | inactivity / dormancy fee rule with jurisdiction guard (some states prohibit) | F | S | fee_definition | FeeTrigger DORMANCY exists |

### 7.5 accounts.yaml: standing orders, sweeps, statements, closure, virtual

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 81 | ScheduleFrequency += FORTNIGHTLY alias, EVERY_WORKING_DAY, EVERY_N_DAYS, WEEK_IN_MONTH (e.g. 2nd Tuesday), LAST_DAY_OF_MONTH, LAST_WORKING_DAY_OF_MONTH, QUARTER_DAY | E | S | ScheduleFrequency | OB frequency patterns, BG MonthlyVariable |
| 82 | standing_order first_payment_amount, final_payment_amount, last_payment_at, last_payment_amount | F | S | standing_order | OB StandingOrder |
| 83 | scheduled_type (EXECUTION, ARRIVAL) for future-dated payments | F | C | standing_order | OB ScheduledPayment |
| 84 | GET /deposit-accounts/{id}/direct-debits (active mandates with previous payment) | A | S | deposit-accounts → payments | OB DirectDebits; payment_mandate exists in payments |
| 85 | statement: statement_type (REGULAR_PERIODIC, INTERIM, ANNUAL, ACCOUNT_OPENING, ACCOUNT_CLOSURE) | F+E | S | statement | OB Statement Type |
| 86 | statement: apy_earned, days_in_period, average_balance_in_credit, average_balance_in_debit | F | M (US) | statement | Reg DD 1030.6 (APY earned), OB StatementAmount |
| 87 | statement: overdraft_fees_period / overdraft_fees_ytd, returned_item_fees_period / ytd | F | M (US) | statement | Reg DD 1030.11(a) aggregate fee disclosure |
| 88 | statement: interest rates applied (list), fees list, benefits, previous_statement_closing (PRCD) | F | S | statement | OB StatementRate, StatementFee, StatementBenefit |
| 89 | statement: copy / duplicate indicator, electronic sequence number, legal sequence number | F | S | statement | camt.053 CpyDplctInd, ElctrncSeqNb, LglSeqNb |
| 90 | statement_line: original amount / currency / fx rate, merchant name / MCC, charges, entry status | F | S | statement_line | camt Ntry, OB |
| 91 | StatementFormat += BAI2, MT942 (intraday), CAMT_052, QFX | E | S | StatementFormat | MT BalanceReport intraday; camt.052 |
| 92 | intraday report generation (camt.052 / MT942) for business accounts | A | S | statements | ISO camt.052 |
| 93 | account_closure: forwarding_settings (residual funds destination, cheque by post), closure blockers list | F | S | account_closure | ST v2 close forwarding_settings |
| 94 | virtual_account: routing_details[] (multi-scheme: ABA, sort code, IBAN, CLABE) + account_number_type | F | S | virtual_account | MT AccountDetail / RoutingDetail, ST financial_addresses |
| 95 | virtual_account: supported_networks (ach, wire, fps, sepa...) | F | S | virtual_account | ST supported_networks |
| 96 | savings_pot: interest-bearing pots (separate rate) | F | C | savings_pot | Monzo / Starling style |
| 97 | sweep_rule: overdraft-protection sweep type (cover shortfall from savings) | E | S | SweepType | GA Overdraft Protection; add COVER_SHORTFALL |

### 7.6 product.yaml

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 98 | ProductFamily += MONEY_MARKET, SAVINGS_PLAN, IRA, HSA, ISA, CHARGE_CARD, SECURED_CARD, HELOC, STUDENT_LOAN, AUTO_LOAN, PERSONAL_LOAN, REVOLVING_CREDIT, INVESTOR_ACCOUNT | E | S | ProductFamily | MB product types, FDX types |
| 99 | product.category (reporting category incl. SHARIAH variants) | F+E | S | product | MB category |
| 100 | product.is_shariah_compliant + profit-sharing model (MUDARABA, MURABAHA...) | F | C | product | MB PROFIT_APPLIED, MUDARIB_SHARE |
| 101 | product.allowed_currencies (multi-currency product) | F | C | product | MB currencySettings |
| 102 | Standard parameter registry (product_parameter_definition: key, family, type, shape, level TEMPLATE / INSTANCE / GLOBAL, update_permission FIXED / OPS_EDITABLE / USER_EDITABLE, default, min, max, unit) | T | M | new product_parameter_definition | TM parameter levels and permissions; replaces ad-hoc param_key strings so dormancy, overdraft, limits and maturity are typed and validated |
| 103 | product_parameter.level + update_permission (replace is_overridable bool) | F+E | M | product_parameter | TM |
| 104 | deposit settings typed: opening_balance_min / max / default, max_balance, recommended_deposit, max_withdrawal_amount, maturity_period min / max / default + unit | F | M | product parameters (via #102) | MB internalControls, maturitySettings |
| 105 | account_initial_state / auto_approve, requires_funding_within_days | F | S | product_version | MB accountInitialState |
| 106 | change_notice_days (rate / fee change notice to existing accounts) + notice generation on publish | F+A | M | product_version | UK BCOBS 2-month notice, Reg DD 30-day notice; event exists, no notice policy |
| 107 | product_version: conversion / upgrade hook (map old instance params to new) | E | S | ScriptHook += CONVERSION, DERIVED_PARAMETER, PRE_PARAMETER_CHANGE, POST_PARAMETER_CHANGE, ACTIVATION, DEACTIVATION | TM hooks |
| 108 | DayCountBasis += ACT_364, BUS_252, E30_42_365, ACT_365_25 | E | S | DayCountBasis | MB daysInYear (BUS_252 for Brazil) |
| 109 | BalanceBasis += MINIMUM_TO_END_OF_DAY, FRENCH_INTEREST_ACCRUAL | E | C | BalanceBasis | MB |
| 110 | interest_rate_config.allow_negative_rate | F | M | interest_rate_config | MB allowNegativeInterestRate; EUR / CHF / JPY |
| 111 | interest_rate_config.destination (SELF_CREDIT, PAY_AWAY) + payout_account rule | F+E | M | interest_rate_config | OB Destination, MB interestPaidIntoAccount |
| 112 | interest_rate_config.payment_point + fixed payment dates (MonthAndDay[]) | F | S | interest_rate_config | MB interestPaymentPoint ON_FIXED_DATES |
| 113 | interest_rate_config.max_balance_for_interest (cap) | F | S | interest_rate_config | MB maximumBalance |
| 114 | interest_rate_config.accrue_after_maturity, collect_when_locked | F | S | interest_rate_config | MB |
| 115 | interest_rate_config.rate_charge_frequency (ANNUALIZED, EVERY_MONTH, EVERY_FOUR_WEEKS, EVERY_WEEK, EVERY_DAY, EVERY_X_DAYS) | F+E | C | interest_rate_config | MB interestChargeFrequency |
| 116 | interest_rate_config.rate_min / rate_max (negotiation range for account overrides) | F | S | interest_rate_config | MB DecimalInterval; enforce in account_rate_override |
| 117 | interest_rate_config.fixed_variable (FIXED, VARIABLE) + gross_net (GROSS, NET) disclosure | F+E | S | interest_rate_config | OB FixedVariableInterestRateType, BankInterestRateType |
| 118 | interest_rate_config.tier_method: WHOLE vs TIERED vs BANDED clarified; TIERED_PERIOD (days since opening) | E | S | RateStructureType | OB TierBandMethod Whole, MB TIERED_PERIOD (STEPPED is close) |
| 119 | interest_rate_config.interest_window (earn only between dates) | F | C | interest_rate_config | MB interestGainsProvidedStartDate / EndDate |
| 120 | bonus interest conditions (no withdrawals in period, min monthly deposit, min transactions) | T | S | new interest_bonus_condition | TM bonus_interest, UK regular savers |
| 121 | disclosure rates computed and stored: AER / APY / EAR / representative APR per version | F | M | product_version / interest_rate_config | OB AER, EAR, RepresentativeAPR; Reg DD APY; FCA overdraft APR |
| 122 | FeeTrigger += NSF_RETURNED_ITEM, OVERDRAFT_ITEM, CONTINUOUS_OVERDRAFT, STOP_PAYMENT, WIRE_IN, WIRE_OUT, ACH_RETURN, DEPOSITED_ITEM_RETURNED, EXCESS_WITHDRAWAL, PAPER_STATEMENT, ACCOUNT_RESEARCH, LEGAL_PROCESS, COUNTER_TRANSACTION, CASH_ADVANCE, BALANCE_TRANSFER, FOREIGN_TRANSACTION, OVERLIMIT, RETURNED_PAYMENT, ANNUAL_CARD, EARLY_REPAYMENT_CHARGE, DISBURSEMENT, PAYMENT_DUE, ARRANGED_OVERDRAFT_SETUP, OVERDRAFT_RENEWAL, EMERGENCY_BORROWING, DIRECT_DEBIT_UNPAID, STANDING_ORDER_UNPAID, CHAPS, SEPA, ATM_ABROAD | E | M | FeeTrigger | OB fee codes, MB triggers, Reg DD / CARD Act fee types |
| 123 | fee_definition.max_occurrences_per_period (e.g. 3 OD fees per day) | F | M | fee_definition | Industry / CFPB practice |
| 124 | fee_definition.min_trigger_amount (de minimis: no OD fee if overdrawn < X) + applicable_range | F | M | fee_definition | OB FeeApplicableRange |
| 125 | fee_definition.apply_date_method (MONTHLY_FROM_ACTIVATION, FIRST_OF_MONTH, STATEMENT_DATE) | F+E | S | fee_definition | MB applyDateMethod |
| 126 | fee_definition.application (REQUIRED, OPTIONAL) + negotiable | F | C | fee_definition | MB feeApplication, OB NegotiableIndicator |
| 127 | fee_definition.amortisation_profile (NONE, STRAIGHT_LINE, EFFECTIVE_INTEREST_RATE, SUM_OF_YEARS_DIGITS) + period settings + behaviour on reschedule | F+E | M | fee_definition | MB PeriodIntervalSettings; IFRS 9 EIR deferral of origination fees |
| 128 | fee_definition.capitalise (add to principal), deduct_from_disbursement, schedule_allocation (ON_INSTALLMENT, NO_ALLOCATION), interest_bearing | F | S | fee_definition | MB CAPITALIZED_DISBURSEMENT, UPFRONT_DISBURSEMENT, scheduleAllocationMethod |
| 129 | fee_definition.fee_category (SERVICING, CARDS, CHEQUES, CASH, FOREIGN, ONLINE, OVERDRAFT, PENALTY) | F+E | S | fee_definition | OB FeeCategory |
| 130 | fee_definition.calculation_method += LOAN_AMOUNT_PERCENTAGE, REPAYMENT_PRINCIPAL_PERCENTAGE, FEE_RATE_ON_OUTSTANDING_PRINCIPAL, PER_INSTALMENT, SCRIPT | E | S | FeeCalculationMethod | MB amountCalculationMethod |
| 131 | fee_free_length + period (intro fee-free months) | F | S | fee_definition / product_version | OB FeeFreeLength |
| 132 | overdraft product config typed: allow_arranged, max_arranged_limit, allow_unarranged, buffer_amount, authorised_indicator per band, overdraft_type (COMMITTED, ON_DEMAND), representative_apr, cure_period_hours, courtesy_pay, protection sources | T | M | new product_overdraft_config (or registry keys) | MB overdraftSettings, OB Overdraft, FCA PS19/16 (single rate, no fixed fees) |
| 133 | product_limit_template.soft_limit_value / alert_threshold | F | S | product_limit_template | UN dailyDebitSoft, monthlyDebitSoft |
| 134 | product_limit_template.metric += BALANCE_MAX (max balance per KYC tier), TRANSFER_COUNT | E | S | ProductLimitMetric | Comviva TCP, UN limits |
| 135 | Reg D convenient-transfer limit as product limit (count per statement cycle, enforced flag, excess fee link) | F | S | product_limit_template | Reg D |
| 136 | EligibilityRuleType += AVAILABLE_FOR (INDIVIDUAL, GROUP, BUSINESS), BRANCH, EMPLOYER, MEMBERSHIP | E | C | EligibilityRuleType | MB availableFor |
| 137 | DisclosureType += TRUTH_IN_SAVINGS, SCHUMER_BOX, CARDHOLDER_AGREEMENT, FSCS_INFORMATION_SHEET, OVERDRAFT_OPT_IN_NOTICE, FUNDS_AVAILABILITY_POLICY, PRIVACY_GLBA, KEY_FACTS_ESIS | E | M | DisclosureType | Reg DD, Reg Z, Reg E, Reg CC, FSCS |
| 138 | product accounting settings: accounting_method (CASH, ACCRUAL), accrual_posting (DAILY, MONTH_END), accrual granularity (AGGREGATED, PER_ACCOUNT), suspended_income_enabled | F | S | product_version | MB accountingSettings |
| 139 | accounting role map: role enum (PORTFOLIO_CONTROL, INTEREST_RECEIVABLE, INTEREST_INCOME, SUSPENDED_INTEREST_INCOME, FEE_INCOME, PENALTY_INCOME, WRITE_OFF_EXPENSE, INTEREST_EXPENSE, INTEREST_PAYABLE, OVERDRAFT_PORTFOLIO_CONTROL, NEGATIVE_INTEREST_*, TAXES_PAYABLE, UNAPPLIED_FUNDS, CREDIT_BALANCE_PAYABLE, DEFERRED_FEE_INCOME...) on gl_mapping_rule | F+E | S | gl_mapping_rule (ledger.yaml) | MB financialResource (47 roles) |
| 140 | credit_arrangement_requirement (OPTIONAL, REQUIRED, NOT_REQUIRED) | F+E | C | product_version | MB |
| 141 | product availability for holder type (INDIVIDUALS, GROUPS) and branch set | F | C | product_availability | MB availabilitySettings |
| 142 | account number id_pattern per product | F | C | product | MB idPattern |
| 143 | open-banking product data export (OB PCA / BCA Products API mapping) | A | C | products (GET /{id}/ob-product-data) | OB Products API |

### 7.7 ledger.yaml

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 144 | transaction_code registry table (code, description, ISO BTC domain / family / sub-family, direction, statement narrative template, customer_initiated (dormancy), reg_d_convenient, fee / interest flag, reversal_code) | T | M | new transaction_code | ISO BkTxCd, OB ProprietaryBankTransactionCode, GA act_type + otype, TM transaction_code, MB transaction types |
| 145 | journal_entry.transaction_code + bank_transaction_code (domain / family / sub-family), copied to ledger_entry | F | M | journal_entry / ledger_entry | OB, BG, camt; statement_line has transaction_code but the ledger does not |
| 146 | journal_entry.original_amount, original_currency, exchange_rate, fx_markup (customer FX, not base) | F | M | journal_entry | ISO AmtDtls, OB CurrencyExchange, BG markupFee, GA billing / local / settle amounts |
| 147 | journal_entry.charges_amount + charge_bearer | F | S | journal_entry | ISO Chrgs, OB ChargeAmount |
| 148 | journal_entry.merchant (name, id, mcc, city, country, terminal_id) | F | M | journal_entry external_refs | OB MerchantDetails, BG card fields, GA mcc_code / merchant_id; statements and OB need it |
| 149 | journal_entry.card_ref (card_id, last4, auth_id, scheme, auth_type) | F | S | journal_entry external_refs | OB CardInstrument, GA auth_id / card_id |
| 150 | journal_entry.remittance_unstructured / structured creditor reference | F | M | journal_entry external_refs | ISO RmtInf, BG remittanceInformation*; description is 500 chars with no structure |
| 151 | journal_entry.ultimate_debtor / ultimate_creditor, counterparty_agent_bic | F | C | journal_entry external_refs | ISO RltdPties / RltdAgts, OB v4 |
| 152 | journal_entry.mandate_id, cheque_number, return_reason_code | F | S | journal_entry external_refs | ISO Refs MndtId / ChqNb, RtrInf |
| 153 | journal_entry.transaction_at (event time distinct from effective_at / booked_at) + input_channel | F | S | journal_entry | BG transactionDate, ISO TechInptChanl, MB transactionChannelKey |
| 154 | journal_entry.category / sub_category (PFM enrichment) | F | C | new transaction_enrichment | FDX category, TP category |
| 155 | pending journal entries (status PENDING → POSTED / ARCHIVED) incl. pending credits | F+A | S | journal_entry | MT pending ledger transactions, TM PENDING_INCOMING; today only debit holds |
| 156 | posting balance conditions (available_balance gte / lte / eq) with BALANCE_LOCK_FAILURE | F | S | journal-entries create | MT ledger entry balance conditions |
| 157 | ledger_entry.available_after (running available balance) | F | S | ledger_entry | GA rolling_balance is available-based |
| 158 | account_balance += blocked_amount, reserved_amount, uncleared_amount, unarranged_overdraft_used, overdraft_available, forward_available | F | M | account_balance | MB blockedBalance / holdBalance / forwardAvailableBalance / technicalOverdraftAmount; UN reserved; OB Cleared |
| 159 | HoldType += DEPOSIT_AVAILABILITY (Reg CC float), GARNISHMENT, INCOMING_CREDIT, SEIZURE | E | M | HoldType | Reg CC, MB CRDT holds |
| 160 | hold.direction (DEBIT, CREDIT) — credit holds feed forward-available | F | S | hold | MB creditDebitIndicator DBIT / CRDT |
| 161 | hold.available_on / release_schedule[] (staged release) | F | M | hold | ISO Avlbty; Reg CC partial next-day release |
| 162 | hold.original_amount / currency / merchant (card holds) + advice flag | F | C | hold | MB AuthorizationHold originalAmount, cardAcceptor, advice |
| 163 | balance_snapshot += opening_available, closing_cleared, value-date closing balance, interest-bearing balance | F | S | balance_snapshot | OB OPAV, Cleared; TM value_time |
| 164 | value-date balance query (balance as of value date, not booking time) | A | S | ledger-accounts GET /{id}/balance-at-value-date | Back-valued interest; TM value_time |
| 165 | ledger account categories (many-to-many roll-up with balances) | T | S | new ledger_account_category | MT LedgerAccountCategory |
| 166 | balance monitors (threshold alerts on available / pending / posted) | T+V | S | new ledger_balance_monitor | MT LedgerAccountBalanceMonitor |
| 167 | ledger account statement for an arbitrary effective_at window (starting / ending pending / posted / available) | A | S | ledger-accounts GET /{id}/statement?from&to | MT LedgerAccountStatement |
| 168 | ledger account settlement (zero out against contra up to effective_at) | T+A | C | new ledger_account_settlement | MT LedgerAccountSettlement |
| 169 | ledger_account.currency_exponent (ro) | F | C | ledger_account | MT |
| 170 | restriction sets (typed: PREVENT_DEBITS, PREVENT_CREDITS, PREVENT_CLOSURE, PREVENT_PARAM_UPDATE) at account, customer and payment-device level | T | S | new restriction (ledger or accounts) | TM RestrictionSet; today account_block + ledger freeze only, no customer-level restriction |
| 171 | flags (definition + per-account / customer flag, visible to product scripts: ACCOUNT_DORMANT, REPAYMENT_HOLIDAY, DECEASED) | T | S | new flag / flag_definition | TM Flags |
| 172 | gl_mapping_rule.accounting_role (see #139) | F | S | gl_mapping_rule | MB financialResource |
| 173 | recon_file / bank balance report: balance_report_type (INTRADAY, PREVIOUS_DAY, REAL_TIME) + balances[] typed (OPENING_LEDGER, CLOSING_LEDGER, CURRENT_LEDGER, OPENING_AVAILABLE, OPENING_AVAILABLE_NEXT_BUSINESS_DAY, CLOSING_AVAILABLE, CURRENT_AVAILABLE, PREVIOUSLY_CLOSED_BOOK) | T | S | new bank_balance_report | MT BalanceReport, camt.052 / BAI2 |
| 174 | ExpectedPayment (expected inbound / outbound with amount and date bounds for recon) | T | C | new expected_payment (recon) | MT ExpectedPayment |

### 7.8 lending.yaml: loans

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 175 | loan.interest_calculation_method (FLAT, DECLINING_BALANCE, DECLINING_BALANCE_DISCOUNTED, EQUAL_INSTALMENTS) | F+E | M | loan / product | MB; flat-rate loans are common in emerging markets (nano, BNPL) |
| 176 | ScheduleType += RULE_OF_78, FLAT_INTEREST, OPTIMIZED, PAYMENT_PLAN, MINIMUM_REPAYMENT_WITH_BALLOON | E | S | ScheduleType | TM amortisation methods, MB amortizationMethod |
| 177 | loan.interest_type (SIMPLE, CAPITALISED, COMPOUNDING) + compounding_frequency | F+E | S | loan | MB interestType |
| 178 | loan.interest_balance_basis (PRINCIPAL_ONLY, PRINCIPAL_AND_INTEREST, PRINCIPAL_AND_FEE, PRINCIPAL_INTEREST_AND_FEE) | F+E | S | loan | MB interestBalanceCalculationMethod |
| 179 | loan.interest_application_method + interest_accrual_since (DISBURSEMENT, DUE_DATE) + schedule_interest_days (REPAYMENT_PERIODICITY, ACTUAL_DAYS) | F+E | S | loan | MB |
| 180 | loan.grace_installments + grace_type (NONE, PAY_INTEREST_ONLY, INTEREST_FORGIVENESS) | F+E | M | loan / product | MB gracePeriodSettings; today grace_period_days is only the overdue tolerance |
| 181 | loan.repayment_day_of_month / fixed_days_of_month + due_dates_method (INTERVAL, FIXED_DAYS_OF_MONTH) | F+E | M | loan | MB scheduleDueDatesMethod; customers choose their due day |
| 182 | loan.repayment_period_unit + count (e.g. every 2 weeks, every 45 days) | F | S | loan | MB; RepaymentFrequency enum is fixed |
| 183 | loan.short_month_handling (LAST_DAY_IN_MONTH, FIRST_DAY_OF_NEXT_MONTH) | F+E | C | loan | MB |
| 184 | rounding settings (currency rounding, remainder into last instalment) | F+E | S | product / loan | MB roundingSettings |
| 185 | loan.apr (TILA / CCD) + effective_interest_rate (IFRS 9 EIR) (ro) | F | M | loan | MB effectiveInterestRate; simulate returns APR but the loan does not store it |
| 186 | loan.total_cost_of_credit / finance_charge (ro) | F | S | loan | TILA, EU CCD |
| 187 | loan.rate_floor, rate_cap, next_rate_reset_date, rate_review_frequency, rate_index_id (FK not string) | F | M | loan | MB interestRateFloorValue / CeilingValue / review; today rate_index is free text |
| 188 | loan_party table (CO_BORROWER, GUARANTOR, CO_SIGNER, AUTHORISED_THIRD_PARTY; liability JOINT_AND_SEVERAL / LIMITED; guaranteed_amount; pledged_deposit_account_id; released_at) | T | M | new loan_party | MB guarantors, FDX PRIMARY_BORROWER / SECONDARY_BORROWER / CO_SIGNER; origination has loan_applicant but the booked loan has only customer_id |
| 189 | arrears tolerance: tolerance_floor_amount, tolerance_pct_of_principal, tolerance_method (PERIOD, MONTHLY_DAY), arrears_date_method, non_working_days (INCLUDED, EXCLUDED) | F+E | M | product / loan | MB arrearsSettings; EBA DoD materiality threshold for DPD |
| 190 | penalty_calculation_method (OVERDUE_PRINCIPAL, OVERDUE_BALANCE_AND_INTEREST, OVERDUE_BALANCE_INTEREST_AND_FEE, OUTSTANDING_PRINCIPAL) + penalty_grace_days | F+E | S | loan / product | MB penaltySettings |
| 191 | non_accrual status: is_non_accrual, non_accrual_since, lock_period_days, locked_operations (APPLY_INTEREST, APPLY_FEES, APPLY_PENALTIES) | F | M | loan | MB lockSettings, TE NAB; loan_accrual.is_suspended exists but there is no loan-level state or trigger |
| 192 | charge capping (cap %, base ORIGINAL / OUTSTANDING principal, SOFT / HARD) | F+E | C | product / loan | MB lockSettings capping (consumer protection caps, e.g. UK HCSTC 100% cap) |
| 193 | LoanStatus += APPROVED, PARTIALLY_DISBURSED, LOCKED, SUSPENDED, REFINANCED, RESCHEDULED (or a loan.sub_state) | E | S | LoanStatus | MB accountSubState |
| 194 | loan component balances due / paid split (principal_due, interest_due, fees_due, penalty_due, *_paid totals) | F | S | loan | MB Balances; today outstanding_* and overdue_amount only |
| 195 | interest_from_arrears (default interest) accrued and balance, separate from penalties | F | C | loan / loan_accrual | MB interestFromArrears* |
| 196 | deferred interest / payment-holiday interest balance + capitalisation event | F | S | loan | MB paymentHolidaysAccruedInterest, DEFERRED_INTEREST_*, PAYMENT_HOLIDAYS_INTEREST_CAPITALISED |
| 197 | schedule_installment: tax per component (interest_tax, fee_tax, penalty_tax), reduced amounts, is_payment_holiday, GRACE status | F+E | S | schedule_installment | MB Installment; InstallmentStatus += GRACE, PAYMENT_HOLIDAY |
| 198 | prepayment: prepayment_acceptance, future_payments_acceptance (ACCEPT_FUTURE_PAYMENTS, ACCEPT_OVERPAYMENTS), recalculation enum expanded (keep same number of terms / principal / total) | F+E | S | product / loan | MB prepaymentSettings |
| 199 | ERC free allowance (pct, amount, next reset date) + ERC tiers by year | F | M (mortgage) | product / loan_prepayment_quote | MB ercFreeAllowance; UK mortgages 10% overpayment |
| 200 | prepayment lock-in period | F | C | product | industry |
| 201 | late_payments_recalculation_method | F+E | C | product / loan | MB |
| 202 | allocation_rule.payment_method (HORIZONTAL, VERTICAL) naming + custom allocation per repayment | F | C | allocation_rule / loan_repayment | MB allowCustomRepaymentAllocation |
| 203 | unapplied funds bucket separate from credit_balance + POST /{id}/withdraw-unapplied | F+A | S | loan | MB unappliedFundsBalance, WITHDRAWAL_UNAPPLIED_FUNDS |
| 204 | redraw facility (allow_redraw, redraw_balance, POST /{id}/redraw) | F+A | C | loan | MB redrawSettings (AU / NZ mortgages) |
| 205 | offset account link (deposit balance reduces interest base) | F | C | loan | MB offsetSettings |
| 206 | settlement / linked account method (FULL_DUE_AMOUNTS, PARTIAL_DUE_AMOUNTS, NO_AUTOMATED_TRANSFERS) | F+E | S | loan | MB settlementMethod; today auto_debit_enabled bool |
| 207 | tranche plan (expected tranche schedule before disbursement, max_tranches on product) | T | S | new loan_tranche_plan | MB tranches; loan_disbursement records only actual disbursements |
| 208 | escrow sub-account (escrow_balance, escrow_payment, escrow analysis, shortage / surplus) | T | S | new loan_escrow | FDX escrowBalance; US mortgages (RESPA) |
| 209 | loan.payoff_amount (ro, daily) + per-diem interest | F | S | loan | FDX payOffAmount; quote exists but there is no live field |
| 210 | interest_paid_ytd / principal_paid_ytd / interest_paid_prior_year (ro) | F | S | loan | FDX; Form 1098 |
| 211 | investor funding / participations (funding sources, share %, commission) | T | C | new loan_funding_source | MB fundingSources, INVESTOR_ACCOUNT |
| 212 | refinance / reschedule links: original_loan_id, rescheduled_to_loan_id | F | S | loan | MB originalAccountKey, rescheduledAccountKey; restructure creates a schedule but there is no loan link for refinance |
| 213 | schedule edit API (adjust due dates / amounts / holidays on an approved schedule with maker-checker) | A | S | loans POST /{id}/edit-schedule | MB repaymentScheduleEditOptions |
| 214 | POST /loans/{id}/suspend and /unsuspend; /terminate | A | C | loans | MB SUSPEND, ACCOUNT_TERMINATED |
| 215 | LoanFeeType += PAYMENT_DUE, EARLY_REPAYMENT_CHARGE, CAPITALISED, RETURNED_PAYMENT | E | S | LoanFeeType | MB triggers |
| 216 | LoanType += PERSONAL, STUDENT, HOME_EQUITY, AUTO (VEHICLE exists), MILITARY, INTEREST_FREE | E | C | LoanType | FDX LoanAccountType, MB INTEREST_FREE_LOAN |

### 7.9 lending.yaml: credit lines and cards

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 217 | credit_line_balance_segment table (segment_type PURCHASE, CASH_ADVANCE, BALANCE_TRANSFER, PROMO_PURCHASE, PROMO_BALANCE_TRANSFER, INSTALMENT_PLAN, FEES, INTEREST; apr; promo_expires_on; post_promo_apr; balance; accrued_interest; deferred_interest; is_deferred_interest) | T | M | new credit_line_balance_segment | FDX purchasesApr / advancesApr, OB StatementInterest / StatementRate, TM APR by txn type; Reg Z per-segment disclosure |
| 218 | credit_line.balance_transfer_rate + penalty_apr + penalty_trigger_days + penalty_cure_payments | F | M | credit_line | Reg Z 1026.55 |
| 219 | credit_line.rate_index_id + margin + floor (variable APR) | F | M | credit_line | Prime + margin cards |
| 220 | credit_line.cash_advance_limit (sub-limit) + cash_available (ro) | F | M | credit_line | FDX cashAdvanceLimit |
| 221 | credit_line.minimum_payment_method enum (PERCENT_OF_BALANCE, PERCENT_PLUS_INTEREST_AND_FEES, GREATER_OF_PCT_OR_FLOOR, FIXED) + include_past_due + include_overlimit + ceiling | F+E | M | credit_line | MB principalPaymentSettings, TM minimum_percentage_due per type |
| 222 | payment allocation rule for revolving (above minimum to highest APR first) | F | M | credit_line / allocation_rule | Reg Z 1026.53; today "interest and fees first, then oldest statement" |
| 223 | credit_line.grace_period_days (distinct from payment_due_days) + grace_lost flag (ro) + trailing interest | F | S | credit_line | Reg Z 1026.5(b)(2)(ii); double-cycle billing ban 1026.54 |
| 224 | credit_line.overlimit_opt_in + overlimit_fee + overlimit_amount (ro) | F | M (US) | credit_line | Reg Z 1026.56 |
| 225 | credit_line.late_fee schedule (first / subsequent / cap ≤ minimum due) + returned_payment_fee | F | M | credit_line / fee_definition | Reg Z 1026.52(b) |
| 226 | credit_line.annual_fee, foreign_transaction_fee_rate, cash_advance_fee, balance_transfer_fee | F | S | fee_definition (card triggers #122) | OB StatementFee, TM fees |
| 227 | credit_line.autopay_amount + autopay_day | F | S | credit_line | SY auto_payment_period |
| 228 | credit_line.statement_balance, minimum_due, past_due_amount, next_payment_due_date (ro, current cycle) | F | M | credit_line | FDX LocAccount; today only on the statement table |
| 229 | credit_line.cycles_delinquent (ro) | F | S | credit_line | Card delinquency is counted in cycles |
| 230 | credit_line charge-off: charged_off_at, charge_off_amount + write-off and recovery for credit lines | T+A | M | loan_write_off / loan_recovery extended to credit_line_id | SY CHARGED_OFF; today loan only |
| 231 | CreditLineType += CHARGE_CARD, SECURED_CARD, HELOC, COMMERCIAL_CARD | E | S | CreditLineType | SY, FDX |
| 232 | credit_line.secured_deposit_account_id + security_amount + graduation_review_date | F | M | credit_line | GA Secured Credit, SY *_SECURED |
| 233 | credit_line_user (authorised users / additional cardholders with sub-limit) | T | S | new credit_line_user | FDX AUTHORIZED_USER |
| 234 | credit_line_statement += previous_balance, balance_transfers, adjustments, credits, past_due_amount, overlimit_amount, credit_limit, available_credit, days_in_cycle, interest_by_segment json, apr_by_segment json, fees_ytd, interest_ytd, minimum_payment_warning json, late_payment_warning json, promo_end_dates | F | M | credit_line_statement | Reg Z 1026.7, OB Statement amounts |
| 235 | CreditStatementStatus += WAIVED / CLOSED_PAID_LATE | E | C | CreditStatementStatus | |
| 236 | credit_arrangement (umbrella limit across loans / lines / overdrafts: amount, available, consumed, exposure_limit_type APPROVED_AMOUNT / OUTSTANDING_AMOUNT, expiry, state) + add / remove account actions | T+A | S | new credit_arrangement | MB CreditArrangement; SME facilities |
| 237 | credit decision / adverse action record for line changes (limit decrease, closure) with principal reasons | T | S | credit_line_limit_change + adverse_action | ST credit_underwriting_record; ECOA / Reg B |
| 238 | rewards (program, balance, earned, redeemed, expired) | T | C | new rewards domain | FDX rewards, OB StatementValue |
| 239 | credit balance refund policy (max credit balance, auto-allocation, refund days) | F | C | credit_line | MB revolvingProductCreditBalance |

### 7.10 Cross-cutting

| # | Gap | Kind | Pri | Target | Notes / source |
|---|---|---|---|---|---|
| 240 | Open-data mapping layer: FDX (DepositAccount, LoanAccount, LocAccount, Transactions, Statements, AccountContact, PaymentNetworks) and OB / BG response builders driven from the fields above | A | S | openbanking.yaml | FDX, OB, BG; ob_consent exists but data shapes depend on the gaps above |
| 241 | Payment networks per account (bankId, identifier, type US_ACH / US_FEDWIRE / US_RTP / US_FEDNOW, transferIn / Out) | A | S | deposit-accounts GET /{id}/payment-networks | FDX PaymentNetwork |
| 242 | Wallet sub-purses (BONUS, LOYALTY, SAVINGS, REMITTANCE) and barred-sender / barred-receiver statuses | E | C | wallets.yaml | Comviva multiple wallets, grades, TCP (u) |
| 243 | Interest projection for credit lines and loans (per-diem, payoff by date) | A | S | lending | MB schedule preview |
| 244 | Event catalogue additions: accounts.funds_availability.hold_placed / released, accounts.unclaimed_property.reported, lending.credit_line.statement_issued, lending.credit_line.charged_off, lending.loan.non_accrual_entered | V | S | events | Supports rows 72, 78, 230, 191 |

---

## 8. Summary

| Item | Count |
|---|---|
| Catalogue rows (sections 1–5, excluding long enum lists) | about 330 field / concept rows |
| Distinct provider fields reviewed (agents' raw catalogues incl. nested objects) | about 1,500 (Mambu about 600, Galileo / Unit / Synctera / TP about 260, Stripe / MT / TM / TE / Comviva about 330, FDX / OB / BG / ISO / regulation about 330) |
| Gap rows | 244 |
| Must | 69 |
| Should | 125 |
| Could | 50 |
| New tables proposed | 33 (e.g. account_feature, account_parameter_value, notice_withdrawal, savings_plan, contribution, funds_availability_policy, deposit_hold, unclaimed_property_item / report, product_parameter_definition, product_overdraft_config, interest_bonus_condition, transaction_code, ledger_account_category, ledger_balance_monitor, restriction, flag, bank_balance_report, loan_party, loan_tranche_plan, loan_escrow, credit_line_balance_segment, credit_line_user, credit_arrangement) |
| New / changed API actions | about 30 |

**Top Must items (do first)**

1. Typed balances API with ISO balance types and pending transactions in the account feed (#39, #40, #158).
2. Transaction code registry plus ISO BTC, merchant, FX and remittance on ledger postings (#144–#150).
3. Funds availability policy, Reg CC exception holds and staged hold release (#71–#73, #159, #161).
4. Product parameter registry with TM-style levels and permissions, plus a typed overdraft config (#102–#104, #132).
5. Fee trigger catalogue, per-day fee caps, de minimis threshold and IFRS 9 fee amortisation (#122–#124, #127).
6. Ownership type, extra roles, beneficiary shares, and escheat / unclaimed property (#2, #46–#47, #32, #78).
7. Credit-card balance segments with per-segment APR, CARD Act allocation, minimum-payment formula, overlimit opt-in, statement disclosures, and charge-off for credit lines (#217–#225, #228, #230, #234).
8. Loan parties (co-borrowers and guarantors), grace types, arrears materiality, non-accrual state, APR / EIR, rate collars and due-day selection (#175, #180, #181, #185, #187–#189, #191).

**Caveats.**
- Thought Machine and Temenos docs are not public. Their rows are from knowledge and marked (u).
- The Berlin Group, ISO 20022 and most FDX rows are (u). Check them against the official OpenAPI or XSD before implementing.
- Mambu rows were parsed from the official OpenAPI files and are reliable.
- The Reg CC dollar thresholds were updated on 2025-07-01. Make them configurable per `funds_availability_policy.effective_from`; do not hard-code them.
