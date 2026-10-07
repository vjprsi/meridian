# Wallets and Platform Coverage Benchmark

Research date: 2026-10-07. Sources are public documentation only (links in section 6). This document is research input. It does not change any spec.

Scope:

- **Part A**: field-level reference for mobile money and wallet APIs. Covers GSMA Mobile Money API 1.2, Safaricom M-Pesa Daraja, MTN MoMo, Airtel Money, Mojaloop FSPIOP, Paystack, Flutterwave and Comviva mobiquity.
- **Part B**: a platform capability benchmark of 12 vendors, compared with Meridian (`spec/domains/*.yaml`, `generated/services.json`, 67 services).
- **Part C**: a gap matrix between Part A and `spec/domains/wallets.yaml` (8 services, 52 tables).

Legend:

- Required column: **M** = mandatory, **O** = optional, **C** = conditional, **NA** = set by the provider (read-only).
- Meridian column: **Y** = covered, **P** = partial, **N** = missing.

---

## Summary

| Measure | Count |
|---|---|
| Part A field and enum rows catalogued (8 topic tables) | ~210 |
| Part B capabilities benchmarked | 72 |
| Part B: Meridian covers (Y) | 43 |
| Part B: Meridian partial (P) | 6 |
| Part B: Meridian missing entirely (N) | 23 |
| Part C wallet gaps | 58 (Must 14, Should 35, Could 9) |

Overall, Meridian's wallet domain is strong on the core. It already covers:

- KYC-tier limits, fee rules and ledger legs
- maker-checker reversals
- the agent hierarchy, float, tills and cash-in-transit
- a multi-level commission engine
- billers, airtime and settlement
- EMVCo QR codes
- USSD and SMS menus
- G2P batches
- Mojaloop-style two-phase interop

The main wallet gaps are the **API-partner and ecosystem patterns** that GSMA, Daraja and MTN standardise. These are:

- payee-initiated collection (STK push / request-to-pay)
- debit mandates (pre-approvals)
- authorisation codes (cardless or pre-authorised cash-out)
- sending to unregistered users (vouchers)
- B2C, B2B and C2B (paybill) transaction semantics with partner validation and confirmation callbacks
- per-request callback URLs and external references
- persisted quotations
- international remittance metadata and KYC snapshots (travel rule)
- a harmonised error catalogue
- e-money safeguarding (trust account) reconciliation

At the platform level, the gaps are US-market and corporate/wealth product lines. These are:

- remote deposit capture (RDC)
- Metro 2 credit reporting
- IRA and HSA accounts
- positive pay and stop-payment for ACH
- lockbox
- trade finance
- safe deposit boxes
- wealth and brokerage
- insurance distribution
- card-linked offers
- early wage access
- direct-deposit switching
- CTR (Currency Transaction Report) / SAR (Suspicious Activity Report) e-filing formats
- the regulatory notice and disclosure content library

### Top 20 missing capabilities (ranked)

| # | Capability | Area | Priority | Target |
|---|---|---|---|---|
| 1 | Payee-initiated collection / request-to-pay for wallets (STK push, MTN requesttopay, GSMA merchantpay payee-initiated) with customer PIN approval on the handset | Wallets | Must | wallet-service: new `wallet_payment_request` |
| 2 | Wallet debit mandates / pre-approvals (GSMA debitmandates, MTN preapproval, M-Pesa Ratiba) | Wallets | Must | wallet-service: new `wallet_debit_mandate` |
| 3 | C2B paybill / till with partner **validation and confirmation URLs** (Daraja C2B) and account-reference routing | Wallets | Must | merchant-service: `merchant_api_config` and C2B callbacks |
| 4 | B2C single payout API (CommandID Salary/Business/Promotion) and B2B (BusinessPayBill / BuyGoods) transaction types | Wallets | Must | wallet-service: `WalletTxnType` and `/b2c`, `/b2b` actions |
| 5 | Per-request async callback URL, requesting organisation and external reference on every wallet transaction (GSMA requestingOrganisation, X-Callback-Url, ResultURL) | Wallets | Must | `wallet_transaction` columns |
| 6 | Harmonised wallet error catalogue (GSMA errorCategory/errorCode, Mojaloop 4-digit codes, MTN reasons, Daraja ResultCodes) | Wallets | Must | new `wallet_error_code` reference table |
| 7 | International remittance detail on wallet transactions (origin/receiving country, delivery method, purpose, relationship, sender/recipient KYC snapshot, travel rule) | Wallets | Must | new `wallet_remittance_detail` linked to fx-service corridors |
| 8 | E-money safeguarding: trust account vs e-money liability daily reconciliation and regulator return | Wallets / Risk | Must | wallet-service or reconciliation-service: `emoney_safeguarding_position` |
| 9 | Authorisation codes / cardless cash-out / pre-authorised merchant codes (GSMA authorisationcodes) | Wallets | Should | wallet-service: `wallet_authorisation_code` |
| 10 | Send to unregistered recipient (voucher / OTC), claim by agent, expiry and refund | Wallets | Should | wallet-service: `wallet_voucher` |
| 11 | Remote deposit capture (mobile check deposit) | Deposits (US) | Should (US) | accounts or payments: `check_deposit` and image service |
| 12 | Credit bureau reporting (Metro 2 furnishing, monthly) | Lending (US) | Should (US) | reporting: Metro 2 generator |
| 13 | Trade finance (letters of credit, guarantees, collections, supply-chain finance) | Corporate | Should (corporate) | new trade-finance domain |
| 14 | Corporate treasury management: positive pay (cheque/ACH), lockbox, ACH debit blocks/filters, cash pooling (notional) | Corporate | Should | payments, accounts, business channel |
| 15 | Savings groups / savings clubs (chama, ROSCA) and wallet micro-savings sub-wallets with interest | Wallets | Should | wallet-service: `wallet_savings_group` and wallet types |
| 16 | Wallet overdraft / auto-repay credit line (Fuliza-style) with sweep of inbound credits | Wallets / Lending | Should | lending `credit_line` linked to wallet |
| 17 | Wallet-linked companion / virtual card (wallet as funding source for a card program) | Wallets / Cards | Should | cards `card_program` funding = wallet ledger |
| 18 | IRA / HSA / tax-advantaged accounts and tax forms beyond 1099-INT (1099-R, 5498, 5498-SA, 1099-SA) | Deposits (US) | Could (US) | accounts and statement-service |
| 19 | CTR / SAR e-filing formats (FinCEN BSA XML, goAML schema validation), CTR aggregation | Risk | Should | case-management and regulatory-reporting |
| 20 | Earned / early wage access and direct-deposit switching (payroll connectors) | Deposits / Lending | Could | payments and lending |

---

## Part A: Mobile money and wallet API reference

### A.1 Endpoint and resource catalogue by provider

| Provider | Resources / operations (public) | Source |
|---|---|---|
| GSMA MM API 1.2 | `/transactions` (with type in the URL: billpay, deposit, disbursement, transfer, merchantpay, inttransfer, adjustment, reversal, withdrawal); `/transactions/{ref}/reversals`; `/batchtransactions` (with `/rejections`, `/completions`, PATCH approval for maker/checker); `/accounts/{id}` (create, read, PATCH update incl. identity-level KYC); `/accounts/{id}/status`, `/balance`, `/accountname`, `/statemententries`, `/transactions`; `/accounts/{id}/bills`, `/bills/{billRef}/payments`, `/billcompanies`; `/accounts/{id}/debitmandates`; `/accounts/{id}/links`; `/accounts/{id}/authorisationcodes`; `/quotations`; `/requeststates/{serverCorrelationId}`; `/responses/{clientCorrelationId}`; `/heartbeat` | GSMA spec PDF [1], developer portal [2] |
| M-Pesa Daraja | Authorization (OAuth); M-Pesa Express (STK Push) and STK Query; C2B Register URL (validation/confirmation); B2C (incl. B2C Account Top-up); B2B (BusinessPayBill, BusinessBuyGoods); B2Pochi; Transaction Status; Account Balance; Reversal; Dynamic QR; M-Pesa Ratiba (standing orders); Bill Manager (invoicing); Tax Remittance (to KRA); IMSI / SIM-swap checks | [3] |
| MTN MoMo | **Collection**: requesttopay, requesttopay status, requesttowithdraw, preapproval, invoice (v2), payment (v2), account balance, accountholder active check, basicuserinfo. **Disbursement**: transfer, deposit, refund, balance. **Remittance**: transfer, cashtransfer (v2). All async with `X-Reference-Id` and `X-Callback-Url` | [4] |
| Airtel Money | Collection (USSD push payment), payment enquiry, refund; Disbursement (B2C/B2B, PIN-encrypted); Remittance; KYC user enquiry; Account balance; Cash-in / cash-out for partners | [5] |
| Mojaloop FSPIOP 1.1 | `/participants`, `/parties`, `/quotes`, `/transfers`, `/transactionRequests`, `/authorizations`, `/bulkQuotes`, `/bulkTransfers`, `/transactions`, plus PUT callbacks and `/error` callbacks | [6] |
| Paystack | Transfer Recipients, Transfers (single, bulk, finalize with OTP, verify), Balance and ledger, Dedicated Virtual Accounts, Subaccounts / Splits, Charge (incl. mobile_money) | [7] |
| Flutterwave | Transfers (bank, mobile money, wallet), Beneficiaries, Bills (categories, validate, pay), Charges (mobile money per country), Virtual accounts, Payout subaccounts (wallets), Subaccounts / split | [8] |
| Comviva mobiquity Money / Pay | Stored-value wallet; P2P (domestic and international); bill and merchant pay (QR, NFC, companion cards); B2B; recharge; mobile ticketing; bulk (salary, G2P); closed-loop NFC; companion and virtual cards; pricing and loyalty engine; agent and merchant NxM hierarchy and grades; cash-in/out via agents and via a linked bank; savings clubs, micro-savings, micro-loans (Yabx), micro-insurance, BNPL; Service Creation Manager; USSD Menu Manager; External Interface Gateway; Reporting Suite; transaction simulation and auto-reverse | [9][10][11] |

### A.2 Wallet account and KYC tiers

| Field | Type | Req | Enums / format | Purpose | Providers |
|---|---|---|---|---|---|
| accountIdentifiers[] (key, value) | array | M | key in {msisdn, walletid, accountid, identityalias, consumerno, accountcategory, accountrank, bankaccountno, iban, swiftbic, sortcode, organisationid, username, linkref, serviceprovider, storeid, bankname, bankaccounttitle, emailaddress, mandatereference} | Identify an account. One or more identifiers can be combined | GSMA |
| identity[] | array (1..20) | M | identityType=individual; accountRelationship=accountholder | Holders of the account | GSMA |
| identity.kycVerificationStatus | string | O | verified, unverified, rejected | KYC state of the identity | GSMA |
| identity.kycVerificationEntity | string | O | free text (e.g. an agent) | Who verified the KYC | GSMA |
| identity.kycLevel | integer | O | provider tiers (0..n) | KYC tier that drives limits | GSMA, mobiquity (grade), Airtel `grade` |
| identityKyc.* | object | M | birthCountry, dateOfBirth, contactPhone, emailAddress, employerName, gender (m/f/u), idDocument[], nationality, postalAddress, occupation, subjectName | KYC attributes | GSMA, Mojaloop personalInfo |
| idDocument.idType | string | M | passport, nationalregistration, otherid, drivinglicence, socialsecurity, alienregistration, nationalidcard, employer, taxid, seniorcitizenscard, marriagecertificate, birthcertificate, healthcard, votersid, villageelderletter, pancard, officialletter | Document type | GSMA |
| accountType | string | O | provider-defined | Account / wallet product | GSMA |
| accountStatus | string | NA/M | available, unavailable, unregistered | Can transactions be posted? `unregistered` means sending to it creates a voucher | GSMA |
| accountSubStatus | string | O | provider-defined | Detailed status | GSMA |
| currentBalance / availableBalance / reservedBalance / unClearedBalance | string amount | O | | Balance views | GSMA, MTN balance, Daraja AccountBalance (Working/Utility/Charges accounts) |
| registeringEntity | string | O | | The agent or partner that registered the account | GSMA |
| fees[] / commissionEarned[] | array | O | feeType, feeAmount, feeCurrency / commissionType, commissionAmount, commissionCurrency | Registration fees and agent onboarding commission | GSMA |
| lei | string | O | ISO 17442 | LEI of an organisation account holder | GSMA status API |
| wallet_type (Airtel `payee.wallet_type`) | string | O | NORMAL, SALARY (Airtel); mobiquity: multiple wallets per user (normal, savings, bonus/loyalty, remittance) | Choose the destination wallet | Airtel, mobiquity, GSMA accountcategory/accountrank |
| is_barred / is_pin_set / registration.status | bool / string | NA | | KYC enquiry result | Airtel KYC user enquiry |
| basicuserinfo (given_name, family_name, birthdate, locale, gender, status) | object | NA | | Name and KYC look-up before paying | MTN |
| accountholder active | bool | NA | | Is the MSISDN an active MoMo account? | MTN |
| KYC tier limits | config | n/a | e.g. three-tier KYC (Nigeria CBN), tiered wallets (Kenya, Ghana) | Max balance, per-txn and daily/monthly caps per tier | mobiquity, regulators |

### A.3 Transaction object (common core)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| transactionReference | string | NA/M | | Provider reference | GSMA; Daraja `TransID`/`MpesaReceiptNumber`; MTN `financialTransactionId`; Airtel `airtel_money_id` |
| requestingOrganisationTransactionReference | string | O | | Client's own reference | GSMA; MTN `externalId`; Airtel `transaction.id`; Paystack `reference`; Flutterwave `reference` |
| clientCorrelationId / X-Reference-Id / OriginatorConversationID | uuid / string | M | | Idempotency and correlation of async requests | GSMA header, MTN header, Daraja |
| originalTransactionReference | string | C | | For reversals and refunds | GSMA, MTN `referenceIdToRefund`, Daraja `TransactionID` |
| debitParty[] / creditParty[] | array | C (at least one) | Account Identifiers | Parties | GSMA; Mojaloop `payer`/`payee.partyIdInfo` (partyIdType MSISDN, EMAIL, PERSONAL_ID, BUSINESS, DEVICE, ACCOUNT_ID, IBAN, ALIAS) |
| type | string | M | billpay, deposit, disbursement, transfer, merchantpay, inttransfer, adjustment, reversal, withdrawal | Harmonised type | GSMA |
| subType | string | O | provider-defined | Sub-classification | GSMA, Mojaloop `subScenario` |
| transactionType (Mojaloop) | object | M | scenario: DEPOSIT, WITHDRAWAL, TRANSFER, PAYMENT, REFUND; initiator: PAYER, PAYEE; initiatorType: CONSUMER, AGENT, BUSINESS, DEVICE; refundInfo; balanceOfPayments | Interop semantics | Mojaloop |
| CommandID (Daraja) | string | M | SalaryPayment, BusinessPayment, PromotionPayment, BusinessPayBill, BusinessBuyGoods, TransactionReversal, TransactionStatusQuery, AccountBalance, CustomerPayBillOnline, CustomerBuyGoodsOnline | Operation type | Daraja |
| amount / currency | string / ISO 4217 | M | | Value | all |
| amountType | string | O | SEND, RECEIVE | Whether the amount is fixed at the payer's or the payee's end | Mojaloop |
| fees[] | array | O | feeType / feeAmount / feeCurrency | Fee breakdown | GSMA, Mojaloop payeeFspFee |
| descriptionText / payerMessage / payeeNote / Remarks / Occasion / TransactionDesc | string | O | Daraja TransactionDesc max 13, AccountReference max 12 | Narrative shown on SMS and statements | GSMA, MTN, Daraja |
| AccountReference / BillRefNumber / InvoiceNumber | string | C | | Paybill account number | Daraja C2B/STK |
| geoCode | string | O | lat,long | Initiation location | GSMA, Mojaloop |
| oneTimeCode | string | O | | OTP or pre-authorisation code | GSMA |
| senderKyc / recipientKyc | object | O | KYC Information | Travel rule and AML | GSMA |
| requestingOrganisation | object | O | identifierType swiftbic, lei, organisationid | Originating institution | GSMA |
| servicingIdentity | string | O | | Till, POS or assistant ID | GSMA |
| transactionReceipt | string | NA | | Receipt number shown to the parties | GSMA, Daraja MpesaReceiptNumber |
| internationalTransferInformation | object | C | see A.7 | Remittance | GSMA |
| creationDate / modificationDate / requestDate | date-time | NA/O | | Timestamps | GSMA |
| customData[] / metadata[] | kv array (max 20) | O | | Extensibility | GSMA, Paystack metadata, Flutterwave meta |
| callback URL | url | C | X-Callback-Url, CallBackURL, ResultURL, QueueTimeOutURL, callback_url | Async result delivery per request | MTN, Daraja, Flutterwave, GSMA (registered) |
| SecurityCredential / pin | string (RSA-encrypted) | M | | Initiator credential (B2C, reversal) or wallet PIN (Airtel disbursement) | Daraja, Airtel |
| expiration | date-time | M | | Quote and transfer expiry | Mojaloop |
| ilpPacket / condition / fulfilment | string | M | | Interledger conditional transfer | Mojaloop |

### A.4 Transaction types catalogue (cross-provider)

| Use case | GSMA type | Daraja | MTN | Airtel | Mojaloop scenario | Meridian `WalletTxnType` |
|---|---|---|---|---|---|---|
| P2P domestic | transfer | (internal) | transfer (disb.) | n/a | TRANSFER / CONSUMER | P2P **Y** |
| P2P to another provider | transfer | | | | TRANSFER | INTEROP_OUT/IN **Y** |
| Cash-in at agent | deposit | | deposit | cash-in API | DEPOSIT / AGENT | CASH_IN **Y** |
| Cash-out at agent / ATM | withdrawal | Dynamic QR TrxCode WA | requesttowithdraw | cash-out API | WITHDRAWAL (payee-initiated with OTP via /authorizations) | CASH_OUT **Y** (agent only; no ATM/cardless) |
| Bill pay | billpay | C2B Paybill / STK CustomerPayBillOnline / Bill Manager | payment (v2), invoice | collection | PAYMENT / BUSINESS | BILL_PAYMENT **Y** |
| Merchant pay (payer-initiated) | merchantpay | Buy Goods (till), Dynamic QR BG | | | PAYMENT | MERCHANT_PAYMENT **Y** |
| Merchant pay (payee-initiated, push to handset) | merchantpay (payee initiated) | STK Push | requesttopay | USSD push collection | transactionRequests (initiator PAYEE) | **N** (no payee-initiated wallet request) |
| B2C payout (single) | disbursement | B2C SalaryPayment/BusinessPayment/PromotionPayment | disbursement transfer | disbursement B2C | TRANSFER / BUSINESS | **P** (only G2P/BULK_DISBURSEMENT batch) |
| C2B | billpay / merchantpay | C2B register + validation/confirmation | collection | collection | PAYMENT | **P** (MERCHANT/BILL only, no partner validation) |
| B2B | transfer | B2B BusinessPayBill / BusinessBuyGoods | | disbursement type B2B | TRANSFER / BUSINESS | **N** |
| Salary / bulk | disbursement via batchtransactions | B2C bulk | | | bulkQuotes/bulkTransfers | BULK_DISBURSEMENT, G2P **Y** |
| International remittance | inttransfer | | remittance transfer / cashtransfer | remittance | TRANSFER cross-currency | **N** (fx-service corridors exist, no wallet txn type) |
| Airtime / bundles | billpay (subType) | (Safaricom airtime) | | | PAYMENT | AIRTIME **Y** |
| Reversal | reversal | TransactionReversal | refund | refund | REFUND | REVERSAL, REFUND **Y** |
| Adjustment | adjustment | | | | | ADJUSTMENT **Y** |
| Standing order | debit mandate | M-Pesa Ratiba | preapproval | | | **N** |
| Wallet to/from bank | transfer with linkref | B2C Account Top-up | | | | WALLET_TO_BANK / BANK_TO_WALLET **Y** |
| Nano-loan | (subType) | (Fuliza/M-Shwari products) | | | | LOAN_DISBURSEMENT / LOAN_REPAYMENT **Y** |
| Tax remittance | billpay | Tax Remittance | | | | **P** (biller category TAX) |
| Savings deposit / withdrawal | transfer (subType) | (M-Shwari) | | | | **N** |
| Insurance premium | billpay | | | | | **P** (biller category INSURANCE) |

### A.5 Agent hierarchy and float

| Field | Type | Req | Enums | Purpose | Providers | Meridian |
|---|---|---|---|---|---|---|
| hierarchy level | enum | M | super-agent / master agent, agent / dealer, sub-agent / retailer; mobiquity "NxM hierarchy" (configurable depth, distributor, wholesaler, retailer) | Distribution tree | mobiquity, M-Pesa | `agent.agent_level` (3 fixed) **P** |
| grade / category | string | O | e.g. Gold, Silver, Bronze; channel-user grade | Pricing, commission and limits by grade | mobiquity ("grade management"), Airtel `grade` | **N** |
| agent till / store number | string | M | | Customer-facing agent ID used in cash-out (`identityalias`, `storeid`) | GSMA, Daraja (agent number + store number) | `agent_till.till_number`, `agent_outlet.outlet_code` **Y** |
| float (e-value) wallet | account | M | | Agent e-money stock | all | `agent.float_wallet_id`, `agent_float` **Y** |
| commission wallet | account | M | | Earned commissions, separate from float | M-Pesa, mobiquity | `agent.commission_wallet_id` **Y** |
| float purchase / sale / rebalance | txn | M | float buy from bank, super-agent float sale, e-float to cash conversion | Liquidity | M-Pesa, mobiquity | `agent_float_request` (TOP_UP, REBALANCE, WITHDRAW, TRANSFER) **Y** |
| low-float alert / auto-rebalance | config | O | | Liquidity management | mobiquity | **Y** |
| cash-in-transit | request | O | | Physical cash logistics | operators | `cash_management_request` **Y** |
| geo / outlet location, opening hours | geo | O | | Agent locator, remote-vs-onsite detection | mobiquity X.10 | **P** (outlet geo present; no remote-vs-onsite rule) |
| agent-assisted services | enum | M | cash-in, cash-out, registration, KYC upgrade, bill pay, OTC send, PIN reset | Scope of an agent licence | GSMA agent services, mobiquity | `AgentService` **P** (no OTC send, no KYC_UPGRADE) |
| agent KYB / contract | docs | M | | Compliance | regulators | **Y** |
| agent devices / POS | device | O | | Device binding | mobiquity | **Y** |

### A.6 Commissions, pricing and fees

| Field | Type | Req | Enums | Purpose | Providers | Meridian |
|---|---|---|---|---|---|---|
| service charge rule keys | rule | M | txn type, channel, payer/payee user type and grade, KYC tier, amount slab, location, day/time window, gender/profile | Pricing engine dimensions | mobiquity pricing engine [11] | `wallet_fee_rule` **P** (no grade, geo, time window, payer/payee pair, segment) |
| fee basis | enum | M | flat, percent, slab/tiered, flat+percent, min/max | Fee calculation | all | **Y** |
| fee bearer | enum | O | payer, payee, shared | Who pays the fee | mobiquity, Paystack (bearer), Flutterwave | **P** (biller `fee_bearer` only; not on wallet fee rules) |
| tax on fee | rule | O | excise duty (KE), E-Levy (GH, threshold-based), VAT | Statutory levies | operators | `tax_rate` **P** (no cumulative-threshold exemption) |
| promotions / free-txn counters | rule | O | first N free per period, promo codes, campaign zero-rating | Growth | mobiquity loyalty/pricing | **N** |
| commission rule keys | rule | M | txn type, channel, tier, agent level, territory, biller, amount slab | Distributor payout | mobiquity, M-Pesa | `commission_rule` **Y** |
| commission split | rule | M | multi-level share (super-agent, agent, sub-agent, platform) | Hierarchy sharing | mobiquity | `beneficiary`, `beneficiary_share` **Y** |
| milestone / target incentives | rule | O | count or amount targets per period, bonus | Incentive programmes | mobiquity X.10 "Milestone-Based Incentives" | **N** |
| onboarding (registration) commission | rule | O | per wallet registered, per KYC upgrade | Acquisition | GSMA `commissionEarned` on accounts | **N** (commission keyed only on txn types) |
| commission withholding tax | rule | O | | Tax compliance | operators | `tax_withholding_rate` **Y** |
| clawback | rule | O | | Reversed txns | operators | `is_clawback` **Y** |
| simulation | api | O | | Test pricing before launch | mobiquity "transaction simulation" | `/commission-plans/{id}/simulate`, `/wallet-transactions/quote` **Y** |

### A.7 Quotations, fees and remittance

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| quotationReference | string | NA/M | | Quote ID | GSMA |
| quotationStatus | string | O | pending, rejected, completed | Quote lifecycle | GSMA |
| requestAmount / requestCurrency | string / ISO | M | | Requested amount | GSMA |
| type / subType | string | O | Transaction Types | Context | GSMA |
| chosenDeliveryMethod / availableDeliveryMethod | string | O | directtoaccount, agent, personaldelivery | Payout method | GSMA; Meridian `RemittancePayoutMethod` (BANK_ACCOUNT, MOBILE_WALLET, CASH_PICKUP, CARD, HOME_DELIVERY) |
| originCountry / receivingCountry / sendingServiceProviderCountry | ISO 3166 | O | | Corridor | GSMA |
| quotes[].quoteId / sendingAmount / receivingAmount / sendingCurrency / receivingCurrency / fxRate (10 dp) / fees[] / quoteExpiryTime / receivingServiceProvider / deliveryMethod | mixed | NA | | Multi-provider quotes | GSMA |
| senderBlockingReason / recipientBlockingReason | string | NA | | AML block outcome | GSMA |
| relationshipSender / remittancePurpose | string | O | | Remittance compliance | GSMA, MTN cashtransfer |
| transferAmount / payeeReceiveAmount / payeeFspFee / payeeFspCommission / expiration / condition | mixed | M/O | | Interop quote | Mojaloop |
| amountType | string | M | SEND, RECEIVE | Fee-inclusive vs. exclusive | Mojaloop |
| originalAmount / originalCurrency / payeeFirstName... | mixed | O | | Remittance cashtransfer | MTN v2 |
| sender, sender_country, mobile_number (meta) | object | C | | Remittance sender data | Flutterwave transfers |

### A.8 Merchant, QR and collections

| Field | Type | Req | Enums | Purpose | Providers | Meridian |
|---|---|---|---|---|---|---|
| BusinessShortCode / till | string | M | Paybill (account reference required) vs. Buy Goods (till) | Merchant addressing | Daraja | `merchant_code` **P** (no paybill/till distinction) |
| ResponseType | enum | M | Completed, Cancelled | Default action if the merchant validation URL is unreachable | Daraja C2B | **N** |
| ConfirmationURL / ValidationURL | url | M/O | | Partner validates the account reference before posting and gets a confirmation after | Daraja C2B | **N** |
| C2B validation result codes | string | M | 0 accept; C2B00011 invalid MSISDN; C2B00012 invalid account; C2B00013 invalid amount; C2B00014 invalid KYC; C2B00015 invalid shortcode; C2B00016 other | Partner rejection reasons | Daraja | **N** |
| C2B payload: TransactionType, TransID, TransTime, TransAmount, BusinessShortCode, BillRefNumber, InvoiceNumber, OrgAccountBalance, ThirdPartyTransID, MSISDN, FirstName, MiddleName, LastName | mixed | NA | | Payment notification to the merchant | Daraja | **P** (webhooks generic) |
| STK push: BusinessShortCode, Password, Timestamp, TransactionType, Amount, PartyA, PartyB, PhoneNumber, CallBackURL, AccountReference, TransactionDesc | mixed | M | | Payee-initiated request to handset | Daraja | **N** |
| STK result codes | int | NA | 0 success; 1 insufficient funds; 1032 cancelled by user; 1037 no response / timeout; 2001 wrong PIN | Outcome | Daraja | **N** |
| requesttopay: amount, currency, externalId, payer{partyIdType MSISDN/EMAIL/PARTY_CODE, partyId}, payerMessage, payeeNote | mixed | M | | Payee-initiated | MTN | **N** |
| Dynamic QR: MerchantName, RefNo, Amount, TrxCode (BG, WA, PB, SM, SB), CPI, Size | mixed | M | | QR generation by transaction type | Daraja | `merchant_qr` EMVCo **Y** (merchant only; no P2P / withdraw QR) |
| EMVCo merchant-presented QR (static/dynamic) | payload | M | | Interoperable QR | EMVCo, mobiquity | **Y** |
| consumer-presented QR / NFC tap / companion card | token | O | | In-store acceptance | mobiquity | **N** |
| subaccount / split (percentage or flat, bearer) | object | O | | Marketplace settlement split | Paystack, Flutterwave | **N** |
| merchant refund | txn | O | | Refund to payer | MTN refund, Paystack refunds | **Y** |
| payment link | object | O | | Remote collection | Paystack, Flutterwave | **Y** |
| settlement (frequency, MDR) | config | M | | Merchant payout | all | **Y** |

### A.9 Bills

| Field | Type | Req | Enums | Purpose | Providers | Meridian |
|---|---|---|---|---|---|---|
| billReference | string | O | | Bill ID | GSMA | `bill_inquiry.inquiry_reference` **Y** |
| billStatus | string | O | paid, unpaid, partialpaid | Presentment status | GSMA | **N** |
| amountDue / minimumAmountDue / dueDate / billDescription | mixed | O | | Presentment | GSMA | **P** (no minimum due, no description) |
| list bills per account | api | O | | Show all outstanding bills | GSMA `/accounts/{id}/bills` | **N** (single inquiry only) |
| paymentType | string | O | fullpayment, partialpayment | Payment kind | GSMA | **P** (biller flag only) |
| supplementaryBillReferenceDetails[] | kv | C | | Multi-part references | GSMA | **N** |
| serviceProviderComment / serviceProviderNotification | string | NA | | Text from the biller for the customer SMS | GSMA | **N** |
| billcompanies: serviceProvider, serviceProviderType, serviceProviderSubType, companyName | mixed | M | | Biller catalogue | GSMA; Flutterwave bill-categories | `biller` **Y** |
| validate customer | api | M | | Validate the account before payment | Flutterwave, GSMA | `/billers/{id}/validate-reference` **Y** |
| token / receipt (prepaid electricity) | string | NA | | Token delivery | operators | `bill_payment.token` **Y** |

### A.10 Debit mandates, links and authorisation codes

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| mandateReference | string | NA/M | | Mandate ID | GSMA |
| payee[] | Account Identifiers | O | | Who may pull | GSMA, MTN preapproval `payer` |
| mandateStatus | string | O | active, inactive | | GSMA |
| startDate / endDate | date | M/O | | Validity | GSMA, Daraja Ratiba |
| amountLimit / currency | string | O | | Max per pull (no limit means any amount) | GSMA |
| frequencyType | string | O | weekly, fortnight, monthspecificdate, twomonths, threemonths, fourmonths, sixmonths, yearly, lastdaymonth, lastdaymonthworking, lastmonday ... lastsunday, specificdaymonthly | Schedule | GSMA |
| numberOfPayments | number | O | | Instalment count | GSMA |
| Ratiba Frequency | int | M | 1 one-off, 2 daily, 3 weekly, 4 monthly, 5 bi-monthly, 6 quarterly, 7 half-yearly, 8 yearly | Standing order | Daraja |
| validityTime (preapproval) | int seconds | O | | Pre-approval lifetime | MTN |
| link.linkReference | string | NA/M | | Link ID | GSMA |
| link.sourceAccountIdentifiers | array | M | | Linked account | GSMA |
| link.mode | string | M | push, pull, both | Direction allowed | GSMA |
| link.status | string | M | active, inactive | | GSMA |
| authorisationCode | string | NA/M | | Code given to the payee or ATM | GSMA |
| codeState | string | NA/M | active, expired, cancelled | | GSMA |
| amount / currency / amountType | mixed | O | exact, maximum | Redeemable amount | GSMA |
| codeLifetime | int seconds | O | >= 1 | Expiry | GSMA |
| holdFundsIndicator | bool | O | | Reserve funds when the code is created | GSMA |
| redemptionAccountIdentifiers / redemptionChannels / redemptionTransactionTypes | array | O | channel types e.g. ATM, Merchant, Agent | Where and how the code is redeemable | GSMA |

### A.11 Transaction status, request state and errors

| Field | Type | Enums / values | Providers |
|---|---|---|---|
| requestState.status | string | pending, completed, failed | GSMA async |
| requestState.notificationMethod | string | callback, polling | GSMA |
| requestState.serverCorrelationId / objectReference / pendingReason / expiryTime / pollLimit | mixed | | GSMA |
| transactionStatus | string | provider-defined (e.g. pending, completed, failed, reversed) | GSMA |
| errorCategory | string | businessRule, validation, authorisation, identification, internal, serviceUnavailable, idempotency (GSMA API Fundamentals) | GSMA |
| errorCode / errorDescription / errorParameters[] / errorDateTime | mixed | e.g. insufficientFunds, accountNotFound, transactionLimitExceeded, duplicateRequest | GSMA |
| MTN status / reason | string | PENDING, SUCCESSFUL, FAILED; reasons PAYEE_NOT_FOUND, PAYER_NOT_FOUND, NOT_ALLOWED, NOT_ALLOWED_TARGET_ENVIRONMENT, INVALID_CALLBACK_URL_HOST, INVALID_CURRENCY, SERVICE_UNAVAILABLE, INTERNAL_PROCESSING_ERROR, NOT_ENOUGH_FUNDS, PAYER_LIMIT_REACHED, PAYEE_NOT_ALLOWED_TO_RECEIVE, PAYMENT_NOT_APPROVED, RESOURCE_NOT_FOUND, APPROVAL_REJECTED, EXPIRED, TRANSACTION_CANCELED, RESOURCE_ALREADY_EXIST | MTN |
| Airtel transaction.status | string | TS (success), TF (failed), TA (ambiguous), TIP (in progress); response codes `DP008000010xx` family | Airtel |
| Daraja ResultCode | int/string | 0 success; plus API-specific codes (see A.8); ResultType, ResultDesc, OriginatorConversationID, ConversationID, TransactionID | Daraja |
| Mojaloop transferState | string | RECEIVED, RESERVED, COMMITTED, ABORTED | Mojaloop |
| Mojaloop bulkTransferState | string | RECEIVED, PENDING, ACCEPTED, PROCESSING, COMPLETED, REJECTED | Mojaloop |
| Mojaloop errorCode | 4-digit | 1xxx communication, 2xxx server, 3xxx client (31xx validation, 32xx id not found, 33xx expired), 4xxx payer (4001 insufficient liquidity, 41xx rejection, 42xx limit), 5xxx payee (51xx rejection, 52xx limit) | Mojaloop |
| Paystack transfer status | string | pending, success, failed, reversed, otp, abandoned, blocked, rejected, received | Paystack |
| Flutterwave transfer status | string | NEW, PENDING, SUCCESSFUL, FAILED | Flutterwave |
| Meridian `WalletTxnStatus` | enum | INITIATED, PENDING_CONFIRMATION, PENDING_APPROVAL, PROCESSING, COMPLETED, FAILED, REVERSED, EXPIRED, CANCELLED (+ `failure_code` free text) | Meridian, **P**: no ambiguous/unknown state, no error catalogue |

### A.12 Notifications

| Item | Detail | Providers | Meridian |
|---|---|---|---|
| Customer SMS on every financial txn (payer and payee) | Text uses descriptionText, receipt, balance and the biller's serviceProviderNotification | GSMA, M-Pesa, mobiquity | notification-service and wallet events **Y** (template mapping per txn type should be verified) |
| Async callback to an API client | per-request URL (X-Callback-Url, CallBackURL, ResultURL, QueueTimeOutURL) or a registered endpoint | MTN, Daraja, GSMA | webhook-service (registered endpoints) **P**: no per-request URL |
| Partner notification of C2B | Confirmation URL | Daraja | **N** |
| Low-float alert to agent | | mobiquity | **Y** |
| USSD push (STK / USSD prompt for PIN) | Network-initiated session | Daraja STK, Airtel, MTN | **N** (USSD is pull-only) |
| Delivery receipts | SMS DLR | all | `sms_message` **Y** |
| Event webhooks (signed) | | Paystack, Flutterwave | webhook-service **Y** |

---

## Part B: Platform capability benchmark

Vendor columns:

- **GAL** = Galileo (SoFi)
- **MAM** = Mambu
- **TM** = Thought Machine Vault
- **TEM** = Temenos
- **FIN** = Finastra
- **FSV** = Fiserv
- **FIS** = FIS
- **OFC** = Oracle FLEXCUBE / Oracle Banking
- **10X** = 10x SuperCore
- **MOB** = Comviva mobiquity
- **UNIT** = Unit
- **SYN** = Synctera

Marks:

- **x** = publicly listed capability.
- **~** = available through a partner or a product family adjacent to core, or not verifiable from public catalogues.

Vendor marks come from public API catalogues and product pages [12]-[24]. Synctera and Unit marks were checked against their published OpenAPI tags and paths.

Meridian column: Y / P / N, with evidence (service, table).

| # | Capability | GAL | MAM | TM | TEM | FIN | FSV | FIS | OFC | 10X | MOB | UNIT | SYN | Meridian | Evidence / note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | Customer/CIF, KYC/KYB | x | x | ~ | x | x | x | x | x | ~ | x | x | x | Y | party: customer, kyc-service |
| 2 | Onboarding applications | x | ~ | | x | x | x | x | x | | x | x | x | Y | onboarding-service |
| 3 | DDA / current accounts | x | x | x | x | x | x | x | x | x | | x | x | Y | deposit_account |
| 4 | Savings, notice, money market | x | x | x | x | x | x | x | x | x | x | x | x | Y | DepositAccountType |
| 5 | Term deposits / CDs | | x | x | x | x | x | x | x | x | | | | Y | term_deposit |
| 6 | Savings goals / pots | x | ~ | x | x | | ~ | ~ | | x | x | | x | Y | savings_pot |
| 7 | Round-ups | x | | x | | | ~ | | | ~ | | | | Y | SavingsPotRuleType ROUND_UP |
| 8 | Sweeps (target/ZBA/concentration) | x(Promontory) | | x | x | x | x | x | x | | | ~ | | Y | sweep_rule |
| 9 | Notional cash pooling / interest optimisation | | | | x | x | ~ | x | x | | | | | N | ZBA only, no notional pooling |
| 10 | Virtual accounts | x | | x | x | x | x | x | x | | | ~ | x | Y | virtual_account |
| 11 | Overdraft | x | x | x | x | x | x | x | x | x | | | | Y | overdraft_facility |
| 12 | Joint / minor / custodial accounts | x | x | x | x | x | x | x | x | x | | x | x | Y | AccountHolderRole |
| 13 | IRA / retirement accounts | | | | ~ | x | x | x | | | | | | N | none |
| 14 | HSA | | | | | | x | x | | | | | | N | none |
| 15 | Escheatment / unclaimed property | | | | x | x | x | x | x | | | | | P | DormancyEventType ESCHEATED; no state reporting (NAUPA file) or due-diligence letters |
| 16 | Statements (PDF, camt.053, MT940) | x | x | ~ | x | x | x | x | x | ~ | x | x | x | Y | statement-service |
| 17 | Regulatory notices and disclosures content (Reg DD/E/Z, TISA, change-in-terms, adverse action) | x | | | x | x | x | x | x | | | x | x | P | product_disclosure, document_template; no notice catalogue or adverse-action letters (Synctera "Adverse Actions") |
| 18 | Tax forms (1099-INT, withholding certs) | x | | | x | x | x | x | x | | | x | | Y | tax_certificate FORM_1099_INT |
| 19 | Other tax forms (1099-R, 5498, 1098 mortgage interest, 1099-C, 1042-S) | | | | ~ | x | x | x | ~ | | | | | N | |
| 20 | Cheque book and issued cheques, stop payment | | | | x | x | x | x | x | | | x | | Y | cheque_book, cheque (STOPPED) |
| 21 | Remote deposit capture (mobile cheque deposit) | x(Ingo) | | | ~ | x | x | x | ~ | | | x | x | N | no check deposit, image or hold policy |
| 22 | Cheque clearing (image exchange, X9.37) | | | | x | x | x | x | x | | | ~ | ~ | N | |
| 23 | Cheque printing / print-and-mail payouts | | | | | | x | x | | | | x | | N | |
| 24 | Cash deposits at retail (barcode / Green Dot / PayNearMe) | x | | | | | ~ | ~ | | | | x | x | N | in-store cash load missing (agent network is the wallet analogue) |
| 25 | ACH / domestic batch rails | x | x(MPG) | x | x | x | x | x | x | ~ | | x | x | Y | routing-service rails |
| 26 | Instant payments (RTP, FedNow, FPS, SEPA Inst, PIX, UPI) | x | x | x | x | x | x | x | x | ~ | | x | x | Y | RailType INSTANT |
| 27 | Wires / SWIFT / cross-border | x | | x | x | x | x | x | x | | x | x | x | Y | CROSS_BORDER, nostro_account |
| 28 | Zelle / P2P networks | x | | | | | x | x | | | x | | | P | P2P generic; no Zelle connector |
| 29 | Direct debit mandates (pull) | x | x | x | x | x | x | x | x | x | x | x | x | Y | payment_mandate |
| 30 | Positive pay (cheque and ACH debit filters/blocks) | | | | ~ | x | x | x | | | | x | | N | |
| 31 | Lockbox / receivables | | | | ~ | x | x | x | ~ | | | ~ | | N | |
| 32 | Request-to-pay | | | | x | x | x | x | x | | x | | | Y | request_to_pay |
| 33 | Bill payment (biller directory) | x | | | x | x | x(CheckFree) | x | x | | x | | | Y | biller-service |
| 34 | Remittance corridors | | | | x | x | ~ | x | x | | x | | | Y | fx-service remittance_corridor |
| 35 | FX quotes / multi-currency | | x | x | x | x | x | x | x | x | x | | | Y | fx_quote, multi-currency ledger |
| 36 | Card issuing (debit/prepaid/credit) | x | ~ | x | x | x | x | x | x | x | x | x | x | Y | cards domain |
| 37 | Card authorisation / JIT funding | x | ~ | x | x | | x | x | | ~ | | x | x | Y | card_jit_funding_request |
| 38 | Tokenisation / digital wallets | x | | ~ | x | | x | x | | | x | x | x | Y | card-tokenization-service |
| 39 | Card disputes / chargebacks | x | | ~ | x | x | x | x | x | | | x | x | Y | card-dispute-service |
| 40 | Non-card (ACH / Reg E) disputes and error resolution | x | | | x | | x | x | | | | ~ | x | P | servicing complaint; no Reg E claim workflow with provisional credit for ACH/P2P |
| 41 | Spend controls | x | | x | | | x | x | | | | x | x | Y | card_control |
| 42 | Secured card / credit builder | x | | ~ | | | ~ | | | | | ~ | x | N | no secured-card programme with collateral deposit |
| 43 | Consumer loans / lines | x | x | x | x | x | x | x | x | x | x | x | x | Y | loan-service |
| 44 | Mortgages | | x | x | x | x | x | x | x | x | | | | Y | loan types and collateral |
| 45 | BNPL (pay-in-4, merchant-financed) | x | x | x | x | | ~ | | | x | x | | | Y | bnpl_plan |
| 46 | Syndicated / commercial lending | | ~ | | x | x(Loan IQ) | x | x | x | | | | | N | no syndication |
| 47 | Credit bureau pulls | x | x | | x | x | x | x | x | | x | | x | Y | bureau_report |
| 48 | Credit bureau reporting (Metro 2 / local CRB submission) | x | ~ | | x | x | x | x | x | | ~ | x | x | N | no furnishing file |
| 49 | Collections | x | x | | x | x | x | x | x | | | ~ | | Y | collections-service |
| 50 | Earned wage access / early direct deposit | x | | | | | ~ | ~ | | | ~ | | | N | |
| 51 | Direct-deposit switching (payroll connector) | x | | | | | ~ | ~ | | | | ~ | | N | |
| 52 | Rewards / cashback / points | x | | | | | x | x | | | x | x | x | Y | rewards-service |
| 53 | Card-linked offers (merchant-funded) | ~ | | | | | x | x | | | | | | N | campaign offers are bank-funded only |
| 54 | PFM / insights | | | | x | x | x | x | | | | | | Y | insights-service |
| 55 | Fraud scoring | x | | | x | x | x | x | x | | x | x | x | Y | fraud-service |
| 56 | AML monitoring and case management | ~ | | | x | x | x | x | x | | x | x | x | Y | transaction-monitoring, case-management |
| 57 | SAR/STR/CTR records | | | | x | x | x | x | x | | | ~ | ~ | P | sar_report (SAR, STR, CTR, LCTR, EFTR, TFR); no FinCEN BSA XML / goAML schema generator, no CTR multi-txn aggregation rule |
| 58 | Sanctions screening | | | | x | x | x | x | x | | x | x | x | Y | sanctions-service |
| 59 | Travel rule (FATF R.16 originator/beneficiary data) | | | | x | x | x | x | x | | ~ | | | P | payments ISO 20022 fields; wallets/remittance lack structured KYC snapshot |
| 60 | Open banking (PSD2 ASPSP) | | ~ | | x | x | x | x | x | | | | ~ | Y | openbanking-service |
| 61 | Trade finance (LC, guarantees, collections, SCF) | | | | x | x | ~ | x | x | | | | | N | |
| 62 | Treasury dealing / ALM / liquidity | | | | x | x | x | x | x | | | | | Y | treasury domain |
| 63 | Wealth / brokerage / custody | | | | x | x | x | x | x | | | | | N | treasury securities only (bank book) |
| 64 | Insurance distribution / bancassurance / micro-insurance | | | | x | ~ | ~ | ~ | | | x | | | N | biller category INSURANCE only |
| 65 | Safe deposit boxes / lockers | | | | x | x | x | x | x | | | | | N | |
| 66 | Islamic banking | | x | x | x | x | | | x | | | | | N | no Murabaha, Ijara or profit-sharing products |
| 67 | Mobile money wallets / agents / merchants | | | | ~ | | | | | | x | ~ | | Y | wallets domain |
| 68 | USSD / SMS channels | | | | | | | | | | x | | | Y | channel-service |
| 69 | Group lending / savings groups (MFI centres, VSLA, chama) | | x | | x | | | | | | x | | | N | Mambu groups/centres; mobiquity savings clubs |
| 70 | Business banking portal with entitlements / approvals | | | | x | x | x | x | x | | | ~ | | Y | business-channel-service |
| 71 | Account analysis / commercial fee billing (AFP codes) | | | | x | x | x | x | x | | | | | N | |
| 72 | Event streaming / webhooks | x | x | x | x | x | x | x | x | x | x | x | x | Y | webhook-service, CDC |

Additional capabilities seen publicly that Meridian partly models elsewhere:

- **DACA** (deposit account control agreement; Unit `/accounts/{id}/enter-daca`): no equivalent. Account blocks with `LEGAL` scope could host it.
- **Authorized users on accounts** (Unit): covered by AccountHolderRole SIGNATORY / POA.

### Capabilities Meridian lacks entirely (Part B: 23 N rows; 18 core items below)

1. Notional cash pooling
2. IRA
3. HSA
4. Other US tax forms (1099-R, 5498, 1098, 1099-C, 1042-S)
5. Remote deposit capture
6. Cheque image clearing (X9.37)
7. Cheque printing / print-and-mail
8. Retail cash deposit networks
9. Positive pay
10. Lockbox
11. Secured card / credit builder
12. Syndicated lending
13. Metro 2 / CRB furnishing
14. Earned wage access
15. Direct-deposit switching
16. Card-linked offers
17. Trade finance
18. Wealth / brokerage

Also missing but marked P/N elsewhere in the table: insurance distribution (N), safe deposit boxes (N), Islamic banking (N), savings groups (N), commercial account analysis (N).

Those five plus the 18 above make up the table's 23 N rows. The five are niche (Islamic, safe deposit, account analysis) or wallet-specific (savings groups and insurance, which also appear in Part C).

---

## Part C: Wallet gap matrix vs. `spec/domains/wallets.yaml`

Priority:

- **Must** = needed for parity with GSMA/Daraja/MTN partner integrations or for e-money regulation.
- **Should** = common in mobiquity-class platforms.
- **Could** = differentiator or regional.

| # | Field / capability | Priority | Target table / service | Notes and sources |
|---|---|---|---|---|
| 1 | Payee-initiated request-to-pay to a wallet (push PIN prompt, approve/decline, expiry, callback) | Must | wallet-service: new `wallet_payment_request` (requester_wallet_id/merchant_id, payer_msisdn, amount, currency, external_id, payer_message, payee_note, callback_url, status SENT/APPROVED/DECLINED/EXPIRED/FAILED, expires_at, wallet_transaction_id); USSD push via channel-service | Daraja STK [3], MTN requesttopay [4], GSMA merchantpay payee-initiated [1], Mojaloop transactionRequests [6]. payments has `request_to_pay` but not wallet-native |
| 2 | USSD/STK push session (network-initiated) | Must | channel-service: `ussd_session.direction` (PULL/PUSH), push request API to the MNO gateway | Needed for #1 on feature phones |
| 3 | Debit mandates / pre-approvals on wallets | Must | wallet-service: new `wallet_debit_mandate` (mandate_reference, payer_wallet_id, payee identifiers, status ACTIVE/INACTIVE/CANCELLED, start_date, end_date, amount_limit, currency, frequency_type enum per GSMA 4.7, number_of_payments, payments_taken, requesting_org) and a `/collect` action | GSMA debitmandates [1], MTN preapproval [4], M-Pesa Ratiba [3] |
| 4 | Standing orders / scheduled wallet payments | Should | wallet-service: `wallet_standing_order` or reuse accounts `standing_order` pattern | M-Pesa Ratiba [3] |
| 5 | `WalletTxnType` additions: B2C, B2B, C2B, INTL_REMITTANCE_OUT, INTL_REMITTANCE_IN, SAVINGS_DEPOSIT, SAVINGS_WITHDRAWAL, MERCHANT_REFUND, VOUCHER_ISSUE, VOUCHER_REDEEM, CASH_OUT_CARDLESS (ATM), SALARY | Must | wallets.yaml enums; ledger mapping; commission/fee rules | GSMA types [1], Daraja CommandIDs [3] |
| 6 | `sub_type` free classifier on transactions and fee/commission rules | Should | `wallet_transaction.sub_type`, `wallet_fee_rule.sub_type`, `commission_rule.sub_type` | GSMA subType, Mojaloop subScenario |
| 7 | B2C single payout API with command type (SALARY, BUSINESS, PROMOTION), remarks, occasion, recipient-registered flag, result callback | Must | wallet-service `POST /wallet-transactions/b2c` | Daraja B2C [3], MTN disbursement [4], Airtel disbursement [5] |
| 8 | B2B transfers (org wallet to org paybill/till with account reference) | Must | wallet-service `POST /wallet-transactions/b2b` | Daraja B2B [3] |
| 9 | C2B partner integration: merchant/paybill API config with validation URL, confirmation URL, default response type (COMPLETED/CANCELLED), validation timeout, HMAC secret; validation result codes | Must | merchant-service: new `merchant_api_config`; `merchant.collection_type` (PAYBILL/TILL) | Daraja C2B [3] |
| 10 | Per-request async callback URL and correlation: `callback_url`, `client_correlation_id`, `requesting_organisation_id` and `_type` (SWIFTBIC/LEI/ORG_ID), `external_reference` (unique per API client) | Must | `wallet_transaction` columns; webhook-service one-off delivery | GSMA requestingOrganisation and clientCorrelationId [1], MTN X-Callback-Url [4] |
| 11 | Request-state resource for async calls (pending, completed, failed; poll limit; expiry) | Should | wallet-service `GET /request-states/{id}` or a generic platform resource | GSMA requeststates [1] |
| 12 | Look-up of a transaction by external reference / provider reference (status query) | Must | `GET /wallet-transactions?external_reference=` index | Daraja Transaction Status [3], MTN GET requesttopay [4], Airtel enquiry [5] |
| 13 | Harmonised error catalogue: `wallet_error_code` (code, category per GSMA errorCategory, http_status, retryable, customer_message per language, mapping to MTN reason, Daraja ResultCode, Mojaloop code) and `failure_code` FK | Must | wallet-service reference table | [1][3][4][6] |
| 14 | Ambiguous / unknown outcome status (timeouts with downstream systems) and a resolution job | Should | `WalletTxnStatus` add UNKNOWN / PENDING_PROVIDER; reconciliation task | Airtel TA [5], Daraja QueueTimeOutURL |
| 15 | International remittance detail: origin_country, receiving_country, sending_provider_country, delivery_method, relationship_sender, remittance_purpose, quote_id, fx_rate, receive_amount/currency, sender/recipient blocking reason, partner reference | Must | new `wallet_remittance_detail` (1:1 with wallet_transaction) linked to fx-service `remittance_corridor`, `fx_quote` | GSMA internationalTransferInformation [1], MTN cashtransfer [4], mobiquity remittance [9] |
| 16 | Sender/recipient KYC snapshot on transactions (name, DOB, ID type/number, nationality, address); travel rule | Must | `wallet_transaction_party_kyc` (role SENDER/RECIPIENT, kyc json) with PII flags | GSMA senderKyc/recipientKyc [1]; FATF R.16 |
| 17 | Persisted quotation with multiple quotes, status, expiry, delivery methods; transaction must reference a valid unexpired quote | Should | wallet-service: new `wallet_quote` and `wallet_quote_option` (today `/quote` is stateless) | GSMA quotations [1], Mojaloop quotes [6] |
| 18 | Fee breakdown array (fee_type: SERVICE, EXCISE, LEVY, INTEROP, WITHDRAWAL; amount; bearer) | Should | `wallet_transaction.fees json[]` (legs carry amounts but not the type) | GSMA fees object |
| 19 | Fee bearer on wallet fee rules (PAYER, PAYEE, SHARED) and "receive amount" mode (SEND/RECEIVE) | Should | `wallet_fee_rule.fee_bearer`; `/quote amount_type` | Mojaloop amountType, Paystack bearer |
| 20 | Fee rule dimensions: payer and payee wallet type pair, agent grade, territory, day-of-week and time window, customer segment, promo code | Should | `wallet_fee_rule` columns or pricing-rules-service decision table | mobiquity pricing engine [11] |
| 21 | Free-transaction counters / promotional zero-rating (first N free per month) | Should | `wallet_fee_waiver_rule` and usage counter in limits-service | mobiquity loyalty/pricing [11] |
| 22 | Threshold-based levies (e.g. levy exempt below a daily cumulative amount), tax per jurisdiction | Should | link `wallet_fee_rule.tax_rule_id` to config-service `tax_rate_rule` with cumulative threshold | Ghana E-Levy, Kenya excise |
| 23 | Authorisation codes (cardless ATM, pre-authorised agent/merchant redemption): code_hash, state ACTIVE/REDEEMED/EXPIRED/CANCELLED, amount, amount_type EXACT/MAXIMUM, lifetime, hold_id, redemption channels and txn types, redeemed_by | Should | wallet-service: new `wallet_authorisation_code` | GSMA authorisationcodes [1], Mojaloop /authorizations [6] |
| 24 | Vouchers for unregistered recipients (send to a non-wallet MSISDN; SMS code; agent redemption with ID; expiry auto-refund) | Should | wallet-service: new `wallet_voucher`; GSMA accountStatus `unregistered` from lookup | GSMA account status [1], mobiquity OTC |
| 25 | `/lookup` returning a harmonised status (AVAILABLE/UNAVAILABLE/UNREGISTERED) and a masked holder name | Should | wallet-service lookup response | GSMA status and accountname [1], MTN basicuserinfo [4], Airtel KYC enquiry [5] |
| 26 | Balance views: uncleared balance | Could | balance response | GSMA balances |
| 27 | Multiple wallets per customer by purpose (SAVINGS, BONUS/PROMO, SALARY, REMITTANCE) with category/rank addressing | Should | `WalletType` additions, `wallet.wallet_category`, `wallet.rank` | GSMA accountcategory/accountrank, Airtel wallet_type SALARY, mobiquity |
| 28 | Micro-savings sub-wallet with interest accrual and lock rules | Should | wallet product using product-catalog interest config; savings txn types | mobiquity micro-savings [9] |
| 29 | Savings groups / clubs (chama, ROSCA, VSLA): group wallet, members, contribution schedule, rotation payout, multi-approver withdrawals | Should | wallet-service: new `wallet_group`, `wallet_group_member`, `wallet_group_contribution`, `wallet_group_approval` | mobiquity savings clubs / circles [9] |
| 30 | Wallet overdraft / auto-repay credit line (Fuliza style): auto-draw on insufficient balance, auto-repay sweep from inbound credits | Should | lending `credit_line` link `wallet_id`; wallet-service hook on posting | mobiquity digital lending / BNPL [9] |
| 31 | Micro-insurance: policy link, premium collection via mandate, claim payout as B2C | Could | new `wallet_insurance_policy` or partner integration via biller | mobiquity micro-insurance [9] |
| 32 | Companion / virtual card funded by the wallet ledger (prepaid program, JIT from wallet) | Should | cards `card_program.funding_model=PREPAID`, `card.wallet_id` | mobiquity companion cards [9][10] |
| 33 | Consumer-presented QR / NFC closed-loop tap payments | Could | merchant-service `merchant_terminal` acceptance of CPQR token | mobiquity NFC [10] |
| 34 | P2P / withdraw QR (non-merchant QR types: send money, agent withdraw, paybill) | Should | `merchant_qr` to generalise as `wallet_qr` with `trx_code` | Daraja Dynamic QR TrxCode [3] |
| 35 | Merchant split settlement / sub-merchants (marketplace) | Should | merchant-service: new `merchant_split_rule` (sub-merchant, share, bearer) | Paystack subaccounts/splits [7], Flutterwave split [8] |
| 36 | Paybill vs. till distinction and account-reference regex for C2B | Must | `merchant.collection_type`, `merchant.account_reference_regex` | Daraja C2B [3] |
| 37 | Bill presentment list (all bills per account) with billStatus PAID/UNPAID/PARTIALPAID, minimum amount due, description | Should | biller-service: new `bill` table or extend `bill_inquiry` (bill_status, minimum_amount_due, bill_description) | GSMA bills [1] |
| 38 | Supplementary bill references (multi-field references) and `payment_type` FULL/PARTIAL | Should | `bill_payment.supplementary_references json`, `bill_payment.payment_type` | GSMA bill payments [1] |
| 39 | Biller notification text passed to the customer SMS | Could | `bill_payment.provider_notification` | GSMA serviceProviderNotification |
| 40 | Account links with mode PUSH/PULL/BOTH and institution type (BANK, MFI, SACCO, INSURER) | Should | `wallet_bank_link.link_mode`, `institution_type` | GSMA links [1] |
| 41 | Agent grades / categories and configurable hierarchy depth (NxM) | Should | `agent.agent_grade`; AgentLevel to a configurable hierarchy (distributor, dealer, retailer) | mobiquity NxM hierarchy and grades [9] |
| 42 | Merchant hierarchy (head office, branch, till) with group settlement | Could | `merchant.parent_merchant_id` | mobiquity merchant hierarchy [9] |
| 43 | Milestone / target incentives (bonus for N txns or acquisitions in a period) | Should | commission-service: new `incentive_scheme`, `incentive_progress` | mobiquity X.10 [9] |
| 44 | Onboarding / KYC-upgrade commissions (non-transaction events) | Should | commission-service: event types ACCOUNT_REGISTRATION, KYC_UPGRADE on `commission_rule` | GSMA commissionEarned [1] |
| 45 | Remote-vs-onsite detection (agent txn far from the outlet geo) for commission and fraud | Could | agent-network-service rule; fraud-service feature | mobiquity X.10 [9] |
| 46 | Agent services: KYC_UPGRADE, OTC_SEND, PIN_RESET, VOUCHER_REDEEM | Should | `AgentService` enum | GSMA agent services spec |
| 47 | Wrong-recipient reversal with payee-funds hold and payee consent / timeout | Should | wallet-service `wallet_reversal_request` (hold on payee wallet, consent flag, auto-approve after N hours) | M-Pesa reversal practice [3] |
| 48 | Confirmation methods: BIOMETRIC, PUSH_APPROVAL, USSD_PUSH | Should | `PinConfirmationMethod` enum | MTN/Daraja push approval |
| 49 | SIM-swap / IMSI change check before high-risk txns | Should | fraud-service signal from MNO API | Daraja IMSI/SIM swap [3] |
| 50 | Wallet amount liens / court-order partial freeze (amount-scoped block) | Should | `wallet_block` (scope DEBIT/CREDIT/BOTH/AMOUNT) reusing ledger holds | Parity with accounts `account_block` |
| 51 | KYC tier downgrade on document expiry / periodic review failure | Should | `/wallets/{id}/downgrade-tier` triggered by kyc-service | Regulators (tiered KYC) |
| 52 | E-money safeguarding: daily trust/escrow bank balance vs. sum of wallet liabilities, breach alerting, regulator return | Must | `emoney_safeguarding_position` (business_date, currency, trust_bank_balance, emoney_liability, difference, status) in wallet-service; reconciliation-service source | E-money regulation (e.g. CBK, BoG, EU EMD2) |
| 53 | Dormant e-money transfer to an unclaimed-assets authority (escheat) | Should | wallet dormancy and an ESCHEATED status/event | Parity with accounts escheat |
| 54 | Receipt number distinct from reference | Could | `wallet_transaction.receipt_number` | GSMA transactionReceipt, Daraja MpesaReceiptNumber |
| 55 | `servicing_identity` (till, POS or assistant ID) on transactions | Could | `wallet_transaction.servicing_identity` | GSMA servicingIdentity |
| 56 | Interop party types: EMAIL, IBAN, PERSONAL_ID, BUSINESS, DEVICE (+ sub-id) | Should | `InteropPartyType` enum, `interop_quote.payee_party_sub_id` | Mojaloop PartyIdType [6] |
| 57 | Interop transaction semantics: scenario, sub_scenario, initiator, initiator_type, amount_type, payee_fsp_fee and commission, ilp_packet; bulkQuotes / bulkTransfers; transactionRequests; authorizations; settlement windows | Should | interop-service: `interop_quote`, `interop_transfer` columns; new `interop_bulk_transfer`, `interop_settlement_window` | Mojaloop [6] |
| 58 | Deceased / nominee handling on wallets (beneficiary payout of the balance) | Could | `wallet.nominee` or party relationship | regional practice |

Counts: **Must 14, Should 35, Could 9 (58 total).** The Must rows are #1, 2, 3, 5, 7, 8, 9, 10, 12, 13, 15, 16, 36 and 52.

### Already covered (no action): wallet-domain strengths vs. benchmarks

These are on par with or ahead of the benchmarks:

- KYC-tier limits (`wallet_tier_limit`)
- ledger legs per transaction
- maker-checker reversals
- PIN lockout and PIN reset via agent or OTP
- favourites
- wallet-bank link verification
- agent territories, tills, cash counts and devices
- cash-in-transit
- daily agent KPIs
- versioned commission plans with clawback and payout runs
- biller inquiry, validation, tokens and settlement
- EMVCo QR decode and pay
- payment links
- merchant settlement and refunds
- USSD menu versioning and simulator
- SMS commands
- G2P programmes with multi-level approvals and name matching
- Mojaloop-style reserve/commit with net debit cap

---

## 6. Sources

1. GSMA Mobile Money API Specification 1.2.0 (Master): https://www.gsma.com/mobilefordevelopment/wp-content/uploads/2021/10/Mobile-Money-API-Specification-1.2.0-Master.pdf. Use-case documents (Merchant Payments, P2P, International Transfers, Agent CICO and Account Services, Recurring Payments, Account Linking) are in the same folder, e.g. https://www.gsma.com/mobilefordevelopment/wp-content/uploads/2021/10/Mobile-Money-API-Specification-1.2.0-Recurring-Payments.pdf
2. GSMA Mobile Money API developer portal: https://developer.mobilemoneyapi.io/ ; release blog: https://www.gsma.com/mobilefordevelopment/blog/mobile-money-api-specification-1-2-release-blog/
3. Safaricom Daraja: https://developer.safaricom.co.ke/ (APIs: M-Pesa Express, C2B, B2C, B2B, Transaction Status, Account Balance, Reversal, Dynamic QR, M-Pesa Ratiba, Bill Manager, Tax Remittance)
4. MTN MoMo developer: https://momodeveloper.mtn.com/api-documentation (Collection, Disbursement, Remittance)
5. Airtel Africa developer: https://developers.airtel.africa/documentation
6. Mojaloop FSPIOP API v1.1: https://docs.mojaloop.io/api/fspiop/v1.1/api-definition.html
7. Paystack: https://paystack.com/docs/api/transfer-recipient/ , https://paystack.com/docs/api/transfer/ , https://paystack.com/docs/api/subaccount/ , https://paystack.com/docs/api/dedicated-virtual-account/
8. Flutterwave: https://developer.flutterwave.com/docs/transfers , https://developer.flutterwave.com/docs/bill-payments , https://developer.flutterwave.com/reference
9. Comviva mobiquity Pay X.10: https://www.comviva.com/products-solutions/fintech/mobiquity-pay-x-10/
10. Comviva mobiquity Money brochure: https://www.comviva.com/wp-content/uploads/2020/03/Comviva_mobiquity-Money.pdf
11. Comviva mobiquity Banking Suite flyer: https://www.comviva.com/wp-content/uploads/2022/09/mobiquity®-Banking-Suite-Flyer_26082022.pdf
12. Galileo Pro API reference (now SoFi Technology Platform): https://docs.galileo-ft.com/pro/reference , redirects to https://docs.tech.sofi.com/pro/reference
13. Unit API: https://www.unit.co/docs/api/ ; OpenAPI: https://github.com/unit-finance/openapi-unit-sdk
14. Synctera OpenAPI (tags verified): https://docs.synctera.com/openapi.json , https://docs.synctera.com/openapi-v1.json
15. Mambu API: https://docs.mambu.com/api/
16. Thought Machine Vault Core: https://www.thoughtmachine.net/vault-core , https://docs.thoughtmachine.net/
17. Temenos developer portal: https://developer.temenos.com/ ; products: https://www.temenos.com/products/
18. Finastra: https://www.finastra.com/solutions ; FusionFabric.cloud: https://developer.fusionfabric.cloud/
19. Fiserv developer studio: https://developer.fiserv.com/
20. FIS Code Connect: https://codeconnect.fisglobal.com/
21. Oracle FLEXCUBE / Oracle Banking documentation: https://docs.oracle.com/en/industries/financial-services/
22. 10x Banking SuperCore: https://www.10xbanking.com/platform
23. Metro 2 (CDIA): https://www.cdiaonline.org/resources/furnishers-of-data-overview/
24. FinCEN BSA E-Filing (CTR/SAR XML): https://bsaefiling.fincen.treas.gov/ ; IRS Form 1099-INT: https://www.irs.gov/forms-pubs/about-form-1099-int

Method notes:

- The GSMA 1.2 field tables were transcribed from the Master PDF. Daraja, MTN, Airtel, Paystack and Flutterwave field names come from their public API reference pages; several portals are JavaScript applications that could not be machine-fetched during this research, so their fields reflect the published references as commonly documented and should be re-verified against the live sandbox before implementation. Error code families for Airtel are indicative.
- The Meridian evidence comes from `spec/domains/*.yaml` and `generated/services.json` (67 services) as of this date.
- `spec/domains/ledger.yaml` failed YAML parsing at line 58 col 46 during this research ("mapping values are not allowed here"). The ledger inventory was read by text extraction instead. Flag this for the spec owners.
