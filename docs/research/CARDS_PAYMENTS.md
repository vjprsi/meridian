# Cards and Payments: Field-Level Research Catalogue and Gap Matrix

Status: research input (not a spec). Compiled 2026-10-07 from public API docs and scheme or standards material.
Scope: card issuing (program/product config, cards and cardholders, controls, authorisation, clearing, tokens, 3DS, disputes) and payments (payment orders per rail, counterparties, mandates, returns, recurring, lifecycle, fees/FX).
Compared against: `spec/domains/cards.yaml` and `spec/domains/payments.yaml`. Part C lists the gaps.

Conventions
- **Req**: M = mandatory in the source API or standard, O = optional, C = conditional.
- **Providers** column: MQ = Marqeta, GL = Galileo (docs now hosted at docs.tech.sofi.com), SI = Stripe Issuing, LI = Lithic, AD = Adyen Issuing, MT = Modern Treasury, ST = Stripe Treasury, UN = Unit, ISO = ISO 8583 / ISO 20022, EMV = EMVCo, V = Visa, MC = Mastercard, NACHA, EPC = European Payments Council.
- Field names are the providers' own names where one provider dominates. Otherwise they are normalised snake_case.
- "Ours" in Part C means the current Meridian YAML spec.
- Some values were not checked line-by-line against the source. These are marked *(verify)* and should be confirmed in the licensed scheme manuals before they become a spec. Visa Core Rules, Mastercard Chargeback Guide, NACHA Operating Rules and EPC rulebooks are partly licensed, so some exact limits or timelines can't be fully checked from public sources.

## Sources (primary URLs)

| # | Source | URL |
|---|---|---|
| S1 | Marqeta Card Products (config.poi, transaction_controls, card_life_cycle, fulfillment, jit_funding, digital_wallet_tokenization) | https://www.marqeta.com/docs/core-api/card-products |
| S2 | Marqeta Gateway JIT Funding messages | https://www.marqeta.com/docs/core-api/gateway-jit-funding-messages |
| S3 | Marqeta Event types / transaction model | https://www.marqeta.com/docs/core-api/event-types |
| S4 | Marqeta Velocity controls | https://www.marqeta.com/docs/core-api/velocity-controls |
| S5 | Marqeta Authorization controls / MCC groups / Merchant groups | https://www.marqeta.com/docs/core-api/authorization-controls , https://www.marqeta.com/docs/core-api/mcc-groups |
| S6 | Marqeta Digital wallets management | https://www.marqeta.com/docs/core-api/digital-wallets-management |
| S7 | Marqeta Cards / Users / Disputes / 3DS | https://www.marqeta.com/docs/core-api/cards , https://www.marqeta.com/docs/core-api/users , https://www.marqeta.com/docs/core-api/disputes , https://www.marqeta.com/docs/core-api/three-d-secure |
| S8 | Galileo Auth API Webhook 3.0 | https://docs.tech.sofi.com/pro/reference/webhook_authorization_post_3 (formerly docs.galileo-ft.com) |
| S9 | Galileo Authorization Controller API | https://docs.galileo-ft.com/pro/docs/authorization-controller-api |
| S10 | Stripe Issuing Card object | https://docs.stripe.com/api/issuing/cards/object |
| S11 | Stripe Issuing Cardholder object | https://docs.stripe.com/api/issuing/cardholders/object |
| S12 | Stripe Issuing Authorization object | https://docs.stripe.com/api/issuing/authorizations/object |
| S13 | Stripe Issuing Transaction / Token / Dispute objects | https://docs.stripe.com/api/issuing/transactions/object , https://docs.stripe.com/api/issuing/tokens/object , https://docs.stripe.com/api/issuing/disputes/object |
| S14 | Stripe Issuing spending controls | https://docs.stripe.com/issuing/controls/spending-controls |
| S15 | Lithic cards, auth rules, ASA | https://docs.lithic.com/docs/cards , https://docs.lithic.com/docs/authorization-rules , https://docs.lithic.com/docs/auth-stream-access-asa (Java SDK model docs at javadoc.io/com.lithic.api) |
| S16 | Adyen Issuing: payment instruments, transaction rules, relayed authorisation | https://docs.adyen.com/issuing/ , https://docs.adyen.com/issuing/transaction-rules , https://docs.adyen.com/api-explorer/balanceplatform/latest/overview |
| S17 | ISO 8583 overview | https://en.wikipedia.org/wiki/ISO_8583 |
| S18 | EMV 3-D Secure Protocol & Core Functions Spec | https://www.emvco.com/emv-technologies/3-d-secure/ |
| S19 | Visa Dispute Management Guidelines for Merchants (reason codes, VCR) | https://usa.visa.com/dam/VCOM/global/support-legal/documents/merchants-dispute-management-guidelines.pdf |
| S20 | Mastercard Chargeback Guide | https://www.mastercard.us/content/dam/public/mastercardcom/na/global-site/documents/chargeback-guide.pdf |
| S21 | Modern Treasury Payment Order / Counterparty / Expected Payment / Return / Reversal / Virtual Account | https://docs.moderntreasury.com/payments/reference/payment-order-object , https://docs.moderntreasury.com/payments/reference/counterparty-object , https://docs.moderntreasury.com/reconciliation/reference/expected-payment-object , https://docs.moderntreasury.com/payments/reference/return-object , https://docs.moderntreasury.com/payments/reference/reversal-object , https://docs.moderntreasury.com/payments/reference/virtual-account-object |
| S22 | Stripe Treasury OutboundPayment / OutboundTransfer / InboundTransfer / ReceivedCredit / ReceivedDebit | https://docs.stripe.com/api/treasury/outbound_payments/object , https://docs.stripe.com/api/treasury/outbound_transfers/object , https://docs.stripe.com/api/treasury/inbound_transfers/object , https://docs.stripe.com/api/treasury/received_credits/object , https://docs.stripe.com/api/treasury/received_debits/object |
| S23 | Unit API (ACH, wire, book, check deposit, check payment, recurring payments) | https://www.unit.co/docs/api/ |
| S24 | ISO 20022 message catalogue + External Code Sets | https://www.iso20022.org/iso-20022-message-definitions , https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets |
| S25 | EPC SEPA schemes (SCT, SCT Inst, SDD Core/B2B, SRTP, VOP) | https://www.europeanpaymentscouncil.eu/what-we-do/epc-payment-schemes , https://www.europeanpaymentscouncil.eu/what-we-do/other-schemes/verification-payee |
| S26 | Pay.UK Faster Payments / Confirmation of Payee | https://www.wearepay.uk/what-we-do/payment-systems/faster-payment-system/ , https://www.wearepay.uk/what-we-do/overlay-services/confirmation-of-payee/ |
| S27 | FedNow Service | https://www.frbservices.org/financial-services/fednow |
| S28 | The Clearing House RTP | https://www.theclearinghouse.org/payment-systems/rtp |
| S29 | Nacha ACH rules / ACH Developer Guide | https://www.nacha.org/rules , https://achdevguide.nacha.org/ach-file-details |
| S30 | Fedwire Funds Service (ISO 20022 since 2025-07-14) | https://www.frbservices.org/financial-services/wires |
| S31 | Swift gpi / UETR / CBPR+ | https://www.swift.com/our-solutions/swift-gpi , https://www.swift.com/standards/iso-20022 |
| S32 | Visa Direct / Mastercard Move (Send) | https://developer.visa.com/capabilities/visa_direct , https://developer.mastercard.com/mastercard-send-funds-transfer/documentation/ |
| S33 | EMVCo QR Code Specification for Payment Systems (Merchant-Presented Mode) | https://www.emvco.com/emv-technologies/qr-codes/ |
| S34 | EU Instant Payments Regulation (EU) 2024/886 | https://eur-lex.europa.eu/eli/reg/2024/886/oj |

---

# PART A: CARD ISSUING CATALOGUE

## A1. Card program / card product configuration

| Field | Type | Req | Enums / format | Purpose | Providers |
|---|---|---|---|---|---|
| program_code / token | string(36) | M | | Program identifier | MQ, GL (prog_id), LI (card_program_token), AD (paymentInstrumentGroup) |
| product_name | string | M | | Display name | MQ, GL |
| bin_prefix | string(6-9) | M | 6-9 digits; sandbox 111111 | Issuer BIN/account range | MQ fulfillment.bin_prefix |
| bin_issue_country | string(3) | O | ISO 3166 alpha-3 | Country of BIN issuance (cross-border detection) | MQ |
| pan_length | int | O | default 16 | PAN length | MQ |
| network / brand | enum | M | VISA, MASTERCARD, DISCOVER, AMEX, PULSE, STAR, MAESTRO, INTERLINK | Scheme | all |
| brand_variant / product_id | string | O | e.g. Visa product ID (A=Traditional, F=Classic, I=Infinite...), MC product code (MCS, MDS, MPL...) | Drives interchange category, wallet eligibility | AD (brandVariant), GL (product_id) |
| funding_type | enum | M | DEBIT, PREPAID, CREDIT, CHARGE, DEFERRED_DEBIT | Account model | all |
| regulated_interchange (Durbin flag) | bool | C (US) | | Regulation II exempt vs regulated issuer → interchange cap | V, MC, GL |
| reloadability | enum | O | NON-RELOADABLE, RELOADABLE, SINGLE_USE_VIRTUAL | Prepaid lifecycle | MQ card_life_cycle |
| activate_upon_issue | bool | O | | Auto-activate on create | MQ |
| expiration_offset {unit,value,min_offset} | object | O | unit: YEARS, MONTHS, DAYS, HOURS, MINUTES, SECONDS | Card validity, incl. short-lived virtual cards | MQ |
| update_expiration_upon_activation | bool | O | | Measure validity from activation | MQ |
| card_service_code | int(3) | O | 100-999 (e.g. 101, 201, 221, 226) | Mag/chip service code (international, PIN, cash restrictions) | MQ |
| poi.atm | bool | O | | ATM allowed | MQ |
| poi.ecommerce | bool | O | | CNP allowed | MQ |
| poi.other.allow | bool | O | | POS allowed | MQ |
| poi.other.card_presence_required | bool | O | | Require card present | MQ |
| poi.other.cardholder_presence_required | bool | O | | Require cardholder present | MQ |
| poi.other.use_random_pin | bool | O | | Random PIN generation | MQ |
| transaction_controls.accepted_countries_token | string | O | accept_us_only, decline_ofac_countries, custom | Country allow/deny list | MQ |
| av_accepted_countries_token | string | O | | Account-verification geography | MQ |
| address_verification {av_messages, auth_messages: validate, decline_on_address_number_mismatch, decline_on_postal_code_mismatch} | object | O | | AVS policy | MQ |
| allow_chip_fallback | bool | O | | Permit mag fallback on chip failure | MQ |
| always_require_icc | bool | O | | Chip mandatory | MQ |
| always_require_pin | bool | O | | PIN mandatory | MQ |
| require_card_not_present_card_security_code | bool | O | | CVV2 mandatory for CNP | MQ |
| allow_quasi_cash | bool | O | | Money orders, travellers cheques, crypto, gambling chips | MQ |
| quasi_cash_exempt_mids / _merchant_group_token | string | O | | Exemptions | MQ |
| allow_network_load / _card_activation | bool | O | | Visa ReadyLink / MC rePower cash loads | MQ |
| allow_gpa_auth | bool | O | | Auth against prepaid GPA | MQ |
| allow_mcc_group_authorization_controls | bool | O | | Enables MCC-group features (holds) | MQ |
| auth_control_default_behavior | enum | O | ALLOW, DENY | Default when no control matches | MQ |
| enable_partial_auth_approval | bool | O | | Partial approvals | MQ, LI, GL (partial_supported) |
| ignore_card_suspended_state | bool | O | | Treat suspended as active (special programs) | MQ |
| notification_language | enum | O | ces, deu, eng, fra, ell, ita, nld, pol, por, ron, spa, swe | Cardholder notifications | MQ |
| afd_hold_increase_override / afd_minimum_hold_amount / afd_preauth_expiration_hours | int/string/int | O | | Automated fuel dispenser holds | MQ |
| sca.sca_contactless_transaction_limit | decimal | O | e.g. EUR 50 | PSD2 contactless single limit | MQ |
| sca.sca_contactless_cumulative_amount_limit | decimal | O | e.g. EUR 150 | PSD2 contactless cumulative | MQ |
| sca.sca_contactless_transactions_count_limit | int | O | e.g. 5 | PSD2 consecutive count | MQ |
| sca.sca_lvp_transaction_limit / _cumulative_amount_limit / _count_limit / _currency | decimal/int/ccy | O | EUR 30 / EUR 100 / 5 | PSD2 low-value remote exemption counters | MQ |
| sca.sca_tra_exemption_amount_limit | decimal | O | EUR 100/250/500 by fraud-rate band | TRA exemption | MQ |
| sca.enable_issuer_tra_exemption | bool | O | | Issuer TRA | MQ |
| sca.enable_cavv_authentication_amount_validation / cavv_authentication_amount_incremental_percentage | bool/string | O | | Validate auth amount vs 3DS-authenticated amount | MQ |
| selective_auth.sa_mode | int | O | 0 inactive, 1 active, 2 log-only | Merchant-name matching for restricted cards | MQ |
| selective_auth.dmd_location_sensitivity | int | O | 0-4 | Location strictness | MQ |
| clearing_and_settlement.overdraft_destination | enum | O | GPA, MERCHANT_CAMPAIGN_ACCOUNT, GLOBAL_OVERDRAFT_ACCOUNT | Where force-post overdrafts land | MQ |
| jit_funding.program_funding_source {enabled, funding_source_token, refunds_destination} | object | O | refunds: PROGRAM_FUNDING_SOURCE, GPA, WATERFALL | Program-funded JIT | MQ |
| jit_funding.programgateway_funding_source {enabled, always_fund, funding_source_token, refunds_destination} | object | O | refunds: GATEWAY, GPA, WATERFALL | Gateway JIT (client decides) | MQ |
| jit_funding.paymentcard_funding_source | object | O | | Card-funded JIT | MQ |
| gateway timeout / stand-in policy | int / enum | O | APPROVE, DECLINE, USE_BALANCE | Behaviour on gateway timeout | MQ, GL (stip), LI |
| digital_wallet_tokenization.card_art_id | string | O | Visa profileID (GUID) / MC ProductConfigID | Wallet card art | MQ |
| provisioning_controls.in_app_provisioning {enabled, address_verification} | object | O | | Push provisioning | MQ |
| provisioning_controls.manual_entry {enabled, address_verification} | object | O | | Manual PAN entry in wallet | MQ |
| provisioning_controls.wallet_provider_card_on_file | object | O | | Wallet-provider-stored card | MQ |
| provisioning_controls.web_push_provisioning | object | O | | Web push (Visa/MC) | MQ |
| dwt_use_card_status_during_auth | bool | O | | Decline token auth if card suspended | MQ |
| fulfillment.payment_instrument | enum | O | PHYSICAL_MSR, PHYSICAL_ICC, PHYSICAL_CONTACTLESS, PHYSICAL_COMBO, VIRTUAL_PAN | Form factor/technology | MQ |
| fulfillment.fulfillment_provider | enum | O | ABCORP, ARROWEYE, CPI, GD, IDEMIA*, NITECREST*, OBERTHUR, PERFECTPLASTIC, TAG ... | Bureau | MQ |
| fulfillment.card_personalization.perso_type | enum | O | EMBOSS, LASER, FLAT | Personalisation method | MQ |
| card_personalization.text.name_line_1/2/3 | string(21-26) | O | | Emboss lines (name, company) | MQ, SI (second_line) |
| card_personalization.images {card, carrier, signature, carrier_return_window} | object | O | | Artwork | MQ, SI (personalization_design) |
| card_personalization.carrier {template_id, logo_file, logo_thumbnail_file, message_text, message_line} | object | O | | Carrier letter | MQ, AD (configuration.carrier, insert, envelope) |
| fulfillment.shipping {method, return_address, recipient_address, care_of_line} | object | O | method: LOCAL_MAIL, GROUND, TWO_DAY, OVERNIGHT, INTERNATIONAL, LOCAL_MAIL_PACKAGE, INTERNATIONAL_PRIORITY | Default shipping | MQ |
| fulfillment.bulk_ship | bool | O | | Bulk issuance to one address | MQ, SI (shipping.type bulk) |
| fulfillment.package_id | string | O | | Bureau package reference | MQ |
| fulfillment.uppercase_name_lines | bool | O | | Uppercase emboss | MQ |
| fulfillment.enable_offline_pin | bool | O | | EMV offline PIN | MQ |
| fulfillment.all_zero_card_security_code | bool | O | | Test-only CVV 000 | MQ |
| fulfillment.allow_card_creation | bool | O | | Kill-switch for new cards | MQ |
| pin_mailer / configuration.pinMailer | string | O | | PIN mailer template | AD |
| configuration.activation / activationUrl | string | O | | Activation letter/URL | AD |
| configuration.language / logoImageId / cardImageId | string | O | | Localisation & art | AD |
| special.merchant_on_boarding | bool | O | | Merchant onboarding cards | MQ |

## A2. Card and cardholder

### A2.1 Card

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| token / id | string | M | | Card id | all |
| user_token / cardholder | string | M | | Owner | MQ, SI, LI (account_token) |
| acting_user_token | string | O | | Employee using a business card | MQ |
| business_token | string | O | | Business owner | MQ |
| card_product_token | string | M | | Product | MQ, LI (product_id) |
| type | enum | M | SI: virtual, physical; LI: VIRTUAL, PHYSICAL, MERCHANT_LOCKED, SINGLE_USE | Card type incl. single-use / merchant-locked | SI, LI |
| state / status | enum | M | MQ: UNACTIVATED, ACTIVE, SUSPENDED, TERMINATED, LIMITED; SI: active, inactive, canceled; LI: OPEN, PAUSED, CLOSED, PENDING_FULFILLMENT, PENDING_ACTIVATION; AD: active, inactive, suspended, closed, requested, lost, stolen | Lifecycle | all |
| state_reason / reason_code / substatus | enum | O | MQ reason_code 00-32 (e.g. 00 object activated, 01 requested by you, 03 lost, 04 stolen, 05 fraud...) *(verify)*; LI substatus: LOST, COMPROMISED, DAMAGED, END_USER_REQUEST, ISSUER_REQUEST, NOT_ACTIVE, SUSPICIOUS_ACTIVITY, INTERNAL_REVIEW, EXPIRED, UNDELIVERABLE, OTHER; AD statusReason: accountClosure, damaged, endOfLife, expired, lost, other, stolen, suspectedFraud, transactionRule | Machine-readable reason | MQ, LI, AD |
| cancellation_reason | enum | O | design_rejected, fulfillment_error, lost, stolen | Why cancelled | SI |
| replacement_reason | enum | O | damaged, expired, fulfillment_error, lost, stolen | Why replaced | SI |
| replaced_by / replacement_for | string | O | | Replacement chain both directions | SI, LI, AD |
| reissue_pan_from_card_token | string | O | | Reissue with same PAN | MQ |
| pan / cvv / exp | string | C | reveal only, expand-gated | Sensitive data | SI (number, cvc), MQ (show_pan, show_cvv_number) |
| last_four / last4 | string(4) | M | | Masked | all |
| expiration / exp_month / exp_year | string/int | M | MMYY | Expiry | all |
| expiration_time | ts | O | | Exact expiry for short-lived virtual cards | MQ |
| pin_is_set / pin_status | bool / enum | O | LI: OK, BLOCKED, NOT_SET; SI pin.status: active, blocked | PIN state | MQ, LI, SI |
| spend_limit / spend_limit_duration | int / enum | O | TRANSACTION, MONTHLY, ANNUALLY, FOREVER | Simple per-card limit | LI |
| memo / nickname | string | O | | Label | LI, MQ |
| second_line | string | O | | Second emboss line | SI |
| personalization_design | string | O | | Artwork per card | SI |
| digital_card_art_token | string | O | | Wallet art per card | LI |
| fulfillment {shipping, card_personalization} | object | O | | Per-card fulfilment override | MQ |
| fulfillment_status | enum | O | ISSUED, ORDERED, REORDERED, REJECTED, SHIPPED, DELIVERED, DIGITALLY_PRESENTED | Fulfilment state separate from card state | MQ |
| shipping.status | enum | O | pending, submitted, shipped, delivered, returned, failure, canceled | Shipment state | SI |
| shipping.service | enum | O | standard, express, priority | Speed | SI |
| shipping.carrier / tracking_number / tracking_url / eta | string/ts | O | carrier: dhl, fedex, royal_mail, usps | Tracking | SI, MQ |
| shipping.address_validation {mode, result, normalized_address} | object | O | mode: disabled, normalization_only, validation_and_normalization; result: indeterminate, likely_deliverable, likely_undeliverable | Address QA | SI |
| shipping.require_signature / customs.eori_number / phone_number | bool/string | O | | Courier & customs | SI |
| wallets.apple_pay / google_pay {eligible, ineligible_reason} | object | O | missing_agreement, missing_cardholder_contact, unsupported_region | Wallet eligibility | SI |
| wallets.primary_account_identifier | string | O | | Apple Pay PAI | SI |
| latest_fraud_warning {type, started_at} | object | O | card_testing_exposure, fraud_dispute_filed, third_party_reported, user_indicated_fraud | Compromise signal | SI |
| lifecycle_controls.cancel_after.payment_count | object | O | | Auto-cancel after N uses | SI |
| cardholder_currency | ccy | O | | Billing currency per card | LI |
| network_program_token | string | O | | Scheme program link | LI |
| auth_rule_tokens | string[] | O | | Rules bound to card | LI |
| hostname | string | O | | Embed/iframe display host | LI |
| new_pan_from_card_token / translate_pin_from_card_token | string | O | | Carry PIN to replacement | MQ |
| balanceAccountId / paymentInstrumentGroupId | string | M | | Funding account & group | AD |
| card.authentication {email, password, phone} | object | O | | 3DS OTP channels | AD |
| card.threeDSecure | string | O | | 3DS configuration | AD |
| card.deliveryContact | object | O | | Delivery recipient | AD |
| metadata | map | O | | Client tags | all |

### A2.2 Cardholder

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| type | enum | M | individual, company | Cardholder type | SI |
| name | string | M | | Legal name | SI, MQ |
| individual.first_name / last_name / dob | string/date | C | | KYC (required for some regions) | SI, MQ |
| individual.card_issuing.user_terms_acceptance {date, ip, user_agent} | object | C | | Evidence of card terms acceptance | SI |
| individual.verification.document {front, back} | file | C | | ID document | SI |
| company.tax_id | string | C | | Business tax id | SI |
| billing.address | address | M | | AVS & 3DS billing | SI, MQ |
| email | email | C | | 3DS OTP, wallet eligibility | SI, MQ |
| phone_number | E.164 | C | | 3DS OTP, wallet step-up | SI, MQ |
| preferred_locales | enum[] | O | de, en, es, fr, it | 3DS challenge language | SI |
| status | enum | M | active, inactive, blocked | Cardholder-level kill switch | SI; MQ users: UNVERIFIED, LIMITED, ACTIVE, SUSPENDED, CLOSED |
| requirements {disabled_reason, past_due[]} | object | O | disabled_reason: listed, rejected.listed, requirements.past_due, under_review | Outstanding KYC | SI |
| spending_controls | object | O | same as card | Cardholder-wide limits | SI |
| parent_token / uses_parent_account | string/bool | O | | Hierarchy: user→parent user/business sharing GPA | MQ |
| account_holder_group_token | string | O | | KYC/config group | MQ |
| identifications[] {type, value, expiration_date} | array | O | SSN, TIN, SIN, NIN, PASSPORT_NUMBER, DRIVERS_LICENSE, BUSINESS_NUMBER, BUSINESS_TAX_ID, TAXPAYER_REFERENCE | KYC identifiers | MQ |
| redaction.status | enum | O | redacted, deletion_requested | GDPR erasure state | SI |

## A3. Card controls and velocity

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| allowed_categories / blocked_categories | enum[] | O | ~300 named MCC categories (e.g. ac_refrigeration_repair ... wrecking_and_salvage_yards) | MCC allow/deny by name | SI |
| allowed_merchant_countries / blocked_merchant_countries | country[] | O | ISO alpha-2 | Country allow/deny | SI |
| spending_limits[] {amount, categories[], interval} | array | O | interval: per_authorization, daily, weekly, monthly, yearly, all_time | Amount limits, optionally per category | SI |
| spending_limits_currency | ccy | O | | Currency of limits | SI |
| velocity_control.token / name | string | M | | Control id | MQ |
| velocity_control.association {user_token, card_product_token} | object | O | | Scope (program, product, user) | MQ |
| velocity_control.merchant_scope {mcc, mid, mcc_group} | object | O | | Merchant scope | MQ |
| velocity_control.usage_limit | int | O | | Max count in window | MQ |
| velocity_control.amount_limit | decimal | O | | Max amount in window | MQ |
| velocity_control.velocity_window | enum | M | DAY, WEEK, MONTH, LIFETIME, TRANSACTION | Window | MQ |
| velocity_control.include_purchases / include_withdrawals / include_transfers / include_cashback / include_credits | bool | O | | Which transaction types count | MQ |
| velocity_control.approvals_only | bool | O | | Count only approvals | MQ |
| velocity_control.currency_code / active | ccy / bool | M | | | MQ |
| auth_control {name, association, merchant_scope, start_time, end_time, active} | object | O | | Block/allow by MCC/MID with time window | MQ |
| auth_control_exemption_mids | object | O | | Exempt merchants from a control | MQ |
| mcc_group {token, name, mccs[], config.authorization_controls.hold_increase {type: AMOUNT/PERCENT, value}, hold_expiration_days} | object | O | | Named MCC sets + hold behaviour (hotels, car rental) | MQ |
| merchant_group {token, name, mids[]} | object | O | | Named MID sets | MQ |
| auth_rule.type | enum | M | CONDITIONAL_BLOCK, VELOCITY_LIMIT, MERCHANT_LOCK, CONDITIONAL_ACTION | Rule kind | LI |
| auth_rule.conditions[] {attribute, operation, value} | array | O | attribute: MCC, COUNTRY, CURRENCY, MERCHANT_ID, DESCRIPTOR, LIABILITY_SHIFT, PAN_ENTRY_MODE, TRANSACTION_AMOUNT, RISK_SCORE, CARD_TRANSACTION_COUNT_15M/1H/24H, CARD_STATE, PIN_ENTERED, PIN_STATUS, WALLET_TYPE, ADDRESS_MATCH *(verify full list)*; operation: IS_ONE_OF, IS_NOT_ONE_OF, MATCHES, DOES_NOT_MATCH, IS_GREATER_THAN, IS_LESS_THAN | Expression rules | LI |
| auth_rule.velocity {scope CARD/ACCOUNT, period (window seconds or FIXED day/week/month/year), filters {include_mccs, exclude_mccs, include_countries, exclude_countries}, limit_amount, limit_count} | object | O | | Velocity rule | LI |
| auth_rule.mode / state | enum | O | ACTIVE, INACTIVE; versions with draft + shadow/backtest | Shadow-mode testing, backtests | LI |
| transactionRule.type | enum | M | allowList, blockList, maxUsage, velocity | Rule kind | AD |
| transactionRule.interval.type | enum | M | daily, weekly, monthly, lifetime, perTransaction, rolling, sliding | Window incl. rolling/sliding | AD |
| transactionRule.outcomeType | enum | O | hardBlock, scoreBased, enforceSCA | Outcome incl. step-up | AD |
| transactionRule.score | int | C | | Score added (scoreBased) | AD |
| transactionRule.ruleRestrictions.* | object | O | activeNetworkTokens, brandVariants, counterpartyBank, countries, dayOfWeek, differentCurrencies, entryModes, internationalTransaction, matchingTransactions, mccs, merchantNames, merchants, processingTypes (atmWithdraw, balanceInquiry, ecommerce, moto, pos, recurring, token, unknown), riskScores, sameAmountRestriction, sameCounterpartyRestriction, timeOfDay, tokenRequestors, totalAmount, walletProviderAccountScore, walletProviderDeviceScore | Rich restrictions | AD |
| transactionRule.entityKey {entityType, entityReference} | object | M | paymentInstrument, paymentInstrumentGroup, balanceAccount, accountHolder, balancePlatform | Scope hierarchy | AD |
| transactionRule.startDate / endDate / status / reference | ts/enum | O | active, inactive | Validity | AD |
| Galileo card controls / account features | object | O | feature types e.g. ATM block, international block, ecommerce block *(verify)* | Toggle features per account | GL |
| rules_denied / rules_warned | array | O | | Rules evaluated in auth (returned to client) | GL |

## A4. Authorisation request / response fields

### A4.1 ISO 8583 data elements commonly used (issuer side)

| DE | Name | Format | Purpose / notes |
|---|---|---|---|
| MTI | Message type indicator | n4 | 0100/0110 auth, 0120/0130 auth advice, 0200/0210 financial (single-message, PIN debit/ATM), 0220/0230 financial advice, 0400/0410 reversal, 0420/0430 reversal advice, 0800/0810 network mgmt (echo, sign-on, key exchange) |
| 2 | Primary account number | n..19 | PAN or token (DPAN) |
| 3 | Processing code | n6 | pos1-2 txn type: 00 purchase, 01 cash/withdrawal, 09 purchase with cashback, 10 account funding (AFT), 11 quasi-cash, 20 refund/credit, 26 OCT, 28 payment, 30 balance inquiry, 40 transfer; pos3-4 from-account, pos5-6 to-account (00 default, 10 savings, 20 checking, 30 credit) |
| 4 | Amount, transaction | n12 | Merchant currency, minor units |
| 5 | Amount, settlement | n12 | Settlement currency |
| 6 | Amount, cardholder billing | n12 | Billing currency |
| 7 | Transmission date & time | n10 MMDDhhmmss | GMT |
| 9 | Conversion rate, settlement | n8 | |
| 10 | Conversion rate, cardholder billing | n8 | |
| 11 | STAN | n6 | System trace audit number |
| 12 / 13 | Local transaction time / date | n6 / n4 | Terminal local |
| 14 | Expiration date | n4 YYMM | |
| 15 | Settlement date | n4 MMDD | |
| 18 | Merchant type (MCC) | n4 | ISO 18245 |
| 19 | Acquiring institution country code | n3 | |
| 22 | POS entry mode | n3 | pos1-2 PAN entry: 00 unknown, 01 manual, 02 mag (partial), 05 chip, 07 contactless chip, 10 credential on file, 79/80 fallback, 81 e-commerce (MC), 90 full mag stripe, 91 contactless mag; pos3 PIN capability: 0 unknown, 1 can accept PIN, 2 cannot |
| 23 | Card sequence number (PSN) | n3 | EMV |
| 25 | POS condition code | n2 | 00 normal, 01 cardholder not present, 08 MOTO, 59 e-commerce (Visa) |
| 26 | POS PIN capture code | n2 | |
| 28 | Amount, transaction fee | x+n8 | ATM surcharge / acquirer fee |
| 32 | Acquiring institution ID | n..11 | |
| 33 | Forwarding institution ID | n..11 | |
| 35 | Track 2 data | z..37 | (never stored) |
| 37 | Retrieval reference number | an12 | |
| 38 | Authorisation ID response | an6 | Approval code |
| 39 | Response code | an2 | 00 approved, 01 refer, 03 invalid merchant, 04 pick up, 05 do not honor, 10 partial approval, 12 invalid transaction, 13 invalid amount, 14 invalid card number, 15 no such issuer, 19 re-enter, 30 format error, 41 lost (pick up), 43 stolen (pick up), 51 insufficient funds, 54 expired card, 55 incorrect PIN, 57 not permitted to cardholder, 58 not permitted to terminal, 59 suspected fraud, 61 exceeds amount limit, 62 restricted card, 63 security violation, 65 exceeds frequency limit (MC: also SCA soft decline), 1A additional customer authentication required (Visa SCA soft decline), 70 PIN data required (Visa), 75 PIN tries exceeded, 78 blocked first use, 82 negative CVV/dCVV/iCVV result, 85 no reason to decline (account verification / AVS), 86 cannot verify PIN, 91 issuer unavailable, 93 violation of law, 94 duplicate transmission, 96 system malfunction, N7 CVV2 mismatch (Visa), R0/R1/R3 stop-payment orders (Visa) |
| 41 | Card acceptor terminal ID | ans8 | |
| 42 | Card acceptor ID code | ans15 | MID |
| 43 | Card acceptor name/location | ans40 | Name(25) City(13) Country(2) |
| 44 | Additional response data | ans..25 | AVS/CVV result (Visa) |
| 48 | Additional data (private) | ans..999 | MC sub-elements: SE 22 multi-purpose, SE 42 e-commerce security level (UCAF), SE 43 AAV/UCAF, SE 61 POS data extended (partial approval), SE 77 payment transaction type indicator (C07 P2P etc), SE 84 merchant advice code |
| 49 / 50 / 51 | Currency codes: transaction / settlement / billing | n3 | ISO 4217 numeric |
| 52 | PIN data | b64 | Encrypted PIN block |
| 53 | Security related control info | n16 | Key index, PIN block format |
| 54 | Additional amounts | an..120 | Cashback, available/ledger balance (balance inquiry response), healthcare amounts |
| 55 | ICC system related data | b..255 | EMV TLVs: 9F26 ARQC, 9F27 CID, 9F10 IAD, 9F37 unpredictable number, 9F36 ATC, 95 TVR, 9A date, 9C txn type, 5F2A currency, 82 AIP, 9F1A terminal country, 9F33 terminal capabilities, 9F34 CVM results; response: 91 ARPC, 71/72 issuer scripts |
| 60 | Additional POS data (Visa private) | | Terminal type, entry capability, chip condition, e-commerce indicator (ECI) |
| 61 | POS data (MC) | ans..26 | Attended, terminal location, cardholder present, card present, card capture, txn status, security, CAT level, card data input capability, auth life cycle |
| 62 | Custom payment service fields (Visa) | | 62.1 authorization characteristics indicator, 62.2 transaction identifier (TID), 62.3 validation code |
| 63 | Network data (MC Banknet ref + date) / Visa private | | Network transaction id |
| 90 | Original data elements | n42 | Original MTI, STAN, transmission time, acquirer/forwarder IDs (reversals) |
| 95 | Replacement amounts | an42 | Partial reversal actual amount |
| 102 / 103 | Account identification 1 / 2 | ans..28 | From/to accounts |
| 104 | Transaction description / transaction-specific data | ans..255 | Visa: Business Application Identifier (BAI) for OCT/AFT, sender/recipient data |
| 108 | MoneySend reference data (MC) | | Sender / recipient name, address, account |
| 120-127 | Private / national use | | Visa 126: 126.8 XID/TID, 126.9 CAVV, 126.10 CVV2, 126.13 POS environment (R recurring, I instalment, C credential-on-file), 126.19 dynamic currency conversion, 126.20 3DS indicator |
| 128 | Message authentication code | b64 | |

### A4.2 Authorisation webhook / object fields (normalised)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| auth_type / message type | enum | M | GL: Auth, Advice, Reversal; LI status: AUTHORIZATION, CREDIT_AUTHORIZATION, FINANCIAL_AUTHORIZATION, FINANCIAL_CREDIT_AUTHORIZATION, BALANCE_INQUIRY *(verify)* | Message class | GL, LI |
| transaction_type | enum | M | GL: Preauth, Auth, ATM, Cash Advance, Balance Inquiry, Merchant Credit, Adjustment, Payment, Tokenization | Business type | GL |
| MQ transaction.type | enum | M | authorization, authorization.incremental, authorization.advice, authorization.reversal, authorization.clearing, authorization.cashback, authorization.atm.withdrawal, account.verification, balanceinquiry, refund, refund.authorization, refund.authorization.clearing, refund.authorization.reversal, pindebit, pindebit.atm.withdrawal, pindebit.cashback, pindebit.refund, pindebit.balanceinquiry, originalcredit.authorization, originalcredit.authpluscapture, transaction.network.cashload.*, gpa.credit, gpa.debit, fee.charge, dispute.credit, dispute.debit ... | Event type | MQ |
| MQ transaction.state | enum | M | PENDING, CLEARED, COMPLETION, DECLINED, ERROR | State | MQ |
| JIT method | enum | M | pgfs.authorization, pgfs.authorization.incremental, pgfs.authorization.account_verification, pgfs.authorization.capture, pgfs.authorization.reversal, pgfs.authorization.standin, pgfs.auth_plus_capture(.standin/.reversal), pgfs.balanceinquiry, pgfs.force_capture, pgfs.original.credit.*, pgfs.refund(.authorization/.reversal/.standin), pgfs.product.inquiry, pgfs.adjustment.credit/debit, pgfs.authorization.capture.chargeback(.reversal), pgfs.pindebit.chargeback(.reversal) | JIT message type | MQ (S2) |
| JIT decline_reason | enum | O | AMOUNT_LIMIT_EXCEEDED, BLOCKED_BY_CARDHOLDER, BLOCKED_BY_ISSUER, BLOCKED_MERCHANT_BY_CARDHOLDER, CARD_NOT_ACTIVE, CARDHOLDER_NOT_ACTIVE, CLOSED_ACCOUNT, CRYPTOGRAPHIC_FAILURE, DUPLICATE_TRANSACTION, EXPIRED_CARD, INSUFFICIENT_FUNDS, INVALID_AMOUNT, INVALID_CARD, INVALID_MERCHANT, NO_CHECKING_ACCOUNT, NO_CREDIT_ACCOUNT, NO_SAVINGS_ACCOUNT, REVOCATION_AUTHORIZATION_ORDER, REVOCATION_ALL_AUTHORIZATION_ORDER, SOFT_DECLINE_AUTHENTICATION_REQUIRED, SOFT_DECLINE_PIN_REQUIRED, STOP_PAYMENT, SUSPECTED_FRAUD, TRANSACTION_COUNT_LIMIT_EXCEEDED, TRANSACTION_NOT_PERMITTED | Client decline reason → ISO code | MQ (S2) |
| JIT anticipated_amount_supported | bool | O | | AFD anticipated amount | MQ |
| JIT balances | object | O | | Return balance to network | MQ, GL (available_balance) |
| JIT original_jit_funding_token / incremental_authorization_jit_funding_tokens | string / string[] | C | | Link incrementals/reversals | MQ |
| JIT tags / memo | string | O | | Client annotations | MQ |
| request_history[] {amount, approved, created, reason, reason_message, merchant_amount} | array | M | reason: account_disabled, card_active, card_canceled, card_expired, card_inactive, cardholder_blocked, cardholder_inactive, cardholder_verification_required, insecure_authorization_method, insufficient_funds, network_fallback, not_allowed, pin_blocked, spending_controls, suspected_fraud, verification_failed, webhook_approved, webhook_declined, webhook_error, webhook_timeout | Decision audit per request incl. incrementals | SI |
| pending_request {amount, merchant_amount, is_amount_controllable, network_risk_score, amount_details} | object | C | | Live decision request | SI |
| amount / merchant_amount / currency / merchant_currency | int/ccy | M | | Billing vs merchant amounts | SI, LI |
| amount_details {atm_fee, cashback_amount} | object | O | | Fee/cashback breakdown | SI |
| amounts {trans_amount, partial_amount, cashback_amount, fee_amount, available_funds, amt_til_limit, local_currency(_amount), settle_currency(_amount), billing_currency(_amount), upcharge_amount, exchange_rate, settle_exchange_rate} | object | M | | Full amount set | GL |
| authorization_method | enum | M | chip, contactless, keyed_in, online, swipe | Entry method (simplified) | SI |
| entry_type | enum | M | EMV Chip, EMV Contactless, EMV Fallback, Magnetic Stripe, Contactless, Card Not Present, Manual, Other | Entry method | GL |
| pos.entry_mode {card, cardholder, pan, pin_entered} | object | M | card: PRESENT, NOT_PRESENT, PREAUTHORIZED, UNKNOWN; cardholder: PRESENT, NOT_PRESENT, MAIL_ORDER, TELEPHONE_ORDER, REOCCURRING, INSTALLMENT, DEFERRED_BILLING, ELECTRONIC_ORDER, AGENT, UNKNOWN; pan: AUTO_ENTRY, BAR_CODE, CONTACTLESS, CREDENTIAL_ON_FILE, ECOMMERCE, ICC, KEY_ENTERED, MAG_STRIPE, OCR, SECURE_CARDLESS, UNKNOWN | Decomposed entry mode | LI |
| pos.terminal {attended, card_retention_capable, on_premise, operator, partial_approval_capable, pin_capability, type} | object | O | operator: ADMINISTRATIVE, CARDHOLDER, CARD_ACCEPTOR, UNKNOWN; pin_capability: CAPABLE, INOPERATIVE, NOT_CAPABLE, UNSPECIFIED; type: ADMINISTRATIVE, ATM, AUTHORIZATION, COUPON_MACHINE, DIAL_TERMINAL, ECOMMERCE, ECR, FUEL_MACHINE, HOME_TERMINAL, MICR, OFF_PREMISE, PAYMENT, PDA, PHONE, POINT, POS_TERMINAL, PUBLIC_UTILITY, SELF_SERVICE, TELEVISION, TELLER, TRAVELERS_CHECK_MACHINE, VENDING, VOICE, UNKNOWN | Terminal capabilities | LI |
| pin_entry_capability | enum | O | Capable, Incapable, Inoperative, Other | | GL |
| card_presence | enum | O | present, not_present | | SI |
| transaction.recurring / cardholder_present / transaction_initiator_code / is_advance_payment | mixed | O | initiator: CIT/MIT codes | Stored-credential / MIT framework | GL |
| merchant_data {category, category_code, city, country, name, network_id, postal_code, state, terminal_id, url} | object | M | | Merchant details | SI |
| merchant {acquirer_id, terminal_id, merchant_id, merchant_description, merchant_country, merchant_postal_code, merchant_state, terminal_network} | object | M | | | GL |
| card_acceptor {mid, mcc, name, street_address, city, state, zip, country_code, poi, partial_approval_capable} | object | M | | | MQ |
| network / subnetwork | enum | M | GL: Visa, Mastercard, Discover, Star, Pulse, Allpoint; MQ: VISA, MASTERCARD, DISCOVER, PULSE; subnetwork: VISANET, VISANETDEBIT, VISAINTERLINK, VISAPLUS, MAESTRO, MASTERCARDDEBIT, MASTERCARDCREDIT, CIRRUS ... | Network & sub-network (single vs dual message) | GL, MQ |
| network_data {acquiring_institution_id, system_trace_audit_number, transaction_id} | object | O | | Network identifiers | SI |
| network_risk_score | int | O | Visa VAA / MC Decision Intelligence 0-99 / 0-999 | Scheme risk score | SI, LI, GL (risk_score, risk_code) |
| verification_data {address_line1_check, address_postal_code_check, cvc_check, expiry_check, postal_code, authentication_exemption {claimed_by: acquirer/issuer, type: low_value_transaction, transaction_risk_analysis, unknown}, three_d_secure {result: attempt_acknowledged, authenticated, failed, required}} | object | M | checks: match, mismatch, not_provided | Verification outcomes | SI |
| validation_results {cvv1, cvv2, cvv3, pin, arqc, offline_pin, avs_result, aav} | object | M | | Full crypto validation | GL |
| avs_data {zip, address} | object | O | | AVS input | GL, LI (avs) |
| ecommerce {is_ecommerce, raw_eci, merchant_asserts_authenticated, merchant_asserts_authentication_attempted, cavv_result_code, aav_indicator} | object | O | | 3DS / UCAF result in auth | GL |
| cardholder_authentication {three_ds_version, acquirer_exemption, authentication_result, decision_made_by, liability_shift, three_ds_authentication_token, verification_attempted, verification_result} | object | O | liability_shift: 3DS_AUTHENTICATED, ACQUIRER_EXEMPTION, NONE, TOKEN_AUTHENTICATED | | LI |
| emv {is_emv, brand, terminal_verification_results} | object | O | | Chip data | GL |
| stip {detail, stip_decision, additional_detail, is_stip_denial} | object | O | | Network stand-in advice detail | GL |
| token_type / tar_info / digital_wallet_token | object | O | | Token used in auth | GL, MQ, SI (token, wallet: apple_pay, google_pay, samsung_pay) |
| bai | string(2) | C | AA, BB, BI, BP, CB, CI, CO, CP, FD, GD, GP, LO, MD, MI, OG, PD, PP, TU, WT | Visa business application id (OCT/AFT) | GL |
| payment_info {sender_name, fund_source} | object | C | | OCT sender data | GL |
| merchant_advice_code | string(2) | O | 01 new account info, 02 try later, 03 do not try again, 21 cancel payment (recurring) | MC retry guidance | GL |
| fleet / fuel / fleet_info | object | O | | Fleet card data (driver, vehicle, odometer, fuel type/qty/unit price) | SI, GL |
| iias_info | object | O | | Healthcare/FSA amounts (Visa) | GL |
| incremental_auth / original_id | bool / int | C | | Link incrementals | GL |
| partial_supported | bool | O | | Terminal partial approval | GL |
| response_code / response_code_objects | string / object | M | | Decision + explanation list | GL |
| response: approved_amount / balance / avs_result / result | mixed | M | LI result: APPROVED + decline codes (e.g. ACCOUNT_INACTIVE, AVS_INVALID, CARD_CLOSED, CARD_PAUSED, INSUFFICIENT_FUNDS, UNAUTHORIZED_MERCHANT, VELOCITY_EXCEEDED ...) *(verify)* | Client response | LI |
| response: transfer_prn / transfer_amount | string | O | | Initiate funding transfer in-line | GL |
| fraud_challenges[] {channel: sms, status, undeliverable_reason} | array | O | | Out-of-band fraud challenge to cardholder | SI |
| verified_by_fraud_challenge | bool | O | | | SI |
| balance_transactions / transactions / hold | array | O | | Ledger links | SI |
| digital_commerce_data / digital_payment_data / fraud_scoring_data (MC) | object | O | | MC enrichment | GL |
| mastercard_transaction_id_data (TLID) | object | O | | MC transaction-link id across lifecycle | GL |

## A5. Clearing and settlement fields

| Field | Type | Req | Enums / format | Purpose | Providers |
|---|---|---|---|---|---|
| Visa transaction code (TC) | n2 | M | TC05 sale, TC06 credit/refund, TC07 cash, TC15/16/17 chargeback of 05/06/07, TC25/26/27 reversals, TC10 fee collection, TC20 funds disbursement, TC33 (VSS/report), TC40 fraud advice, TC52 retrieval request, TC56 currency rates | BASE II record type | V |
| MC IPM MTI + function code (DE24) | n4+n3 | M | 1240/200 first presentment, 1240/205 second presentment full, 1240/282 second presentment partial, 1442/450 first chargeback full, 1442/453 partial, 1442/451 arbitration chargeback, 1644/603 retrieval request, 1644/696 file header, 1740/700 fee collection, 1740/780-783 fee collection return/resubmission/arbitration | IPM message type | MC |
| ARN | an23 | M | Mod-10 check digit, format: 1 + acquirer BIN(6) + julian date(4) + seq(11) + check(1) | Acquirer reference number | V, MC, SI |
| network transaction id (Visa TID n15 / MC trace id) | string | M | | Link to auth | all |
| authorization code | an6 | O | | Match key | all |
| central processing date | date | M | | Scheme processing date | V, MC |
| purchase date | date | M | | Transaction date | all |
| source amount / currency | amount | M | | Merchant amount | all |
| destination (billing) amount / currency | amount | M | | Cardholder amount | all |
| settlement amount / currency | amount | M | | Scheme settlement | all |
| conversion rate / currency conversion assessment | rate / amount | O | Visa ISA 1%, MC cross-border assessment | Scheme FX fee | V, MC |
| interchange fee amount + sign | amount | M | credit = issuer income | Interchange | V, MC, MQ |
| interchange rate designator (IRD, MC) / fee program indicator (FPI, Visa) | string(2-3) | M | | Interchange category | V, MC |
| reimbursement attribute (Visa) | an1 | O | | Interchange qualifier | V |
| multiple clearing sequence number / count | n2/n2 | C | | Split shipments (one auth, many presentments) | V, MC |
| reversal / cancel indicator | flag | C | | Clearing reversal | V, MC |
| POS entry mode, terminal capability, cardholder ID method | codes | O | | Interchange & liability | V, MC |
| ECI / UCAF collection indicator | an1-2 | O | | E-commerce security level | V, MC |
| merchant name, city, state, postal code, country, MCC | strings | M | | Descriptor | all |
| merchant verification value (Visa MVV) | an10 | O | | Merchant program identification | V |
| purchase identifier / Level 2/3 data (tax amount, customer code, invoice, line items) | object | O | | Commercial card interchange | V, MC, SI (purchase_details) |
| purchase_details.flight / lodging / fuel / receipt / reference | object | O | flight {departure_at, passenger_name, refundable, segments[], travel_agency}; lodging {check_in_at, nights}; fuel {type, unit, unit_cost_decimal, quantity_decimal} | Enhanced data | SI |
| documentation indicator / member message text | an | O | | Chargeback cycle support | V, MC |
| chargeback reference number / reason code (in chargeback records) | an | C | | Dispute linkage | V, MC |
| settlement service / SRE / cycle | id | M | VSS settlement reporting entity, MC GCMS settlement service | Settlement position grouping | V, MC |
| VSS reports (VSS-110 settlement summary, VSS-120 interchange value, VSS-130/140 reimbursement fees, VSS-900 reconciliation) | report | M | | Daily settlement recon | V |
| MC settlement: T140 / T464 / T461 reports *(verify)* | report | M | | Daily settlement recon | MC |
| transaction.type (issuer view) | enum | M | SI: capture, refund; MQ: authorization.clearing, refund.authorization.clearing, pindebit.authorization.clearing | Posted transaction | SI, MQ |
| force_capture / force post | flag | C | | Clearing without auth | MQ (pgfs.force_capture) |

## A6. Network tokens (Apple Pay, Google Pay, Samsung Pay, card-on-file)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| token_reference_id / TUR (MC tokenUniqueReference) / Visa tokenReferenceID | string(64) | M | | Token id at TSP | V, MC, MQ, SI (network_data) |
| pan_reference_id | string | O | | PAN id at TSP (no PAN) | V, MC |
| payment_account_reference (PAR) | an29 | M | EMVCo PAR | Link all tokens + PAN for one account (for fraud, loyalty) | EMV, MQ, SI |
| token_requestor_id | n11 | M | | Wallet/merchant TRID | V, MC, SI, MQ |
| token_requestor_name | string | O | APPLE_PAY, GOOGLE_PAY, SAMSUNG_PAY, MERCHANT, CLICK_TO_PAY, MICROSOFT, FITBIT, GARMIN ... | Wallet | MQ, SI (wallet_provider) |
| token_type | enum | M | SECURE_ELEMENT, HCE (cloud), CARD_ON_FILE, ECOMMERCE, QR, PSEUDO_ACCOUNT | Token type | V, MC, MQ |
| token_assurance_level / method | n2 / code | O | | ID&V level achieved | V, MC |
| token_expiry | YYMM | M | | Token expiry | all |
| token_last4 / dpan | string | O | | Device PAN last 4 | SI (last4), MQ |
| device {type, name, id, language, ip_address, location lat/long, phone_number last digits, secure_element_id} | object | O | type: MOBILE_PHONE, WATCH, TABLET, MOBILE_PHONE_OR_TABLET, VEHICLE, APPLIANCE, LAPTOP, GAMING_DEVICE, UNKNOWN | Device data for decisioning | MQ, SI (device_fingerprint, ip_address, location, name, phone_number, type: other, phone, watch) |
| wallet_provider_profile {account.score, device.score, risk_assessment.score, reason_codes[], recommendation: APPROVE, REQUIRE_ADDITIONAL_AUTHENTICATION, DECLINE} | object | O | reason codes per wallet (e.g. Apple 0A-0G) *(verify)* | Wallet risk inputs | MQ, SI (wallet_provider.card_number_source: app, manual, on_file, other; suggested_decision; suggested_decision_version; cardholder_name_match) |
| address_verification_result / name verification | enum | O | | Billing/name match during provisioning | MQ, SI |
| provisioning decision | enum | M | MQ fulfillment_status: DECISION_RED, DECISION_YELLOW, DECISION_GREEN, PROVISIONED, REJECTED | Green/yellow/red path | MQ |
| token state | enum | M | MQ: REQUESTED, REQUEST_DECLINED, ACTIVE, SUSPENDED, TERMINATED; SI: active, deleted, requested, suspended | Lifecycle | MQ, SI |
| state_reason / reason_code | enum | O | | Why state changed | MQ |
| activation methods offered (step-up list) | array | C | type: TEXT_TO_CARDHOLDER_NUMBER, EMAIL_TO_CARDHOLDER_ADDRESS, CARDHOLDER_TO_CALL_AUTOMATED_NUMBER, CARDHOLDER_TO_CALL_MANNED_NUMBER, CARDHOLDER_TO_USE_MOBILE_APP, CARDHOLDER_TO_VISIT_WEBSITE, ISSUER_TO_CALL_CARDHOLDER_NUMBER; value (masked) | Yellow-path ID&V options sent to TSP | MC MDES, V VTS, MQ |
| activation code (OTP) notification | message | C | | Issuer sends OTP via its channel (Activation Code Notification) | MC, V |
| token lifecycle message types | enum | M | Authorize Service / Tokenization Authorization Request (TAR), Activation Code Notification, Tokenization Complete Notification, Tokenization Event Notification, Delete/Suspend/Unsuspend, Update Card Metadata | TSP↔issuer messages | MC MDES, V VTS |
| card metadata (card art, foreground colour, issuer name, contact phone/email/website, terms URL, privacy URL, app launch URL) | object | O | | Wallet presentation | V, MC, MQ (card_art_id) |
| push provisioning (Apple) {certificates[], nonce, nonce_signature} → {activation_data, encrypted_pass_data, ephemeral_public_key} | object | C | | Apple in-app provisioning | MQ, SI |
| push provisioning (Google) {device_id, wallet_account_id, user_address} → {opaque_payment_card} | object | C | | Google push provisioning | MQ, SI |
| token cryptogram (TAVV / DSRP UCAF) validation result | enum | C | | Validate token cryptogram in auth | V, MC |
| token domain restriction controls | object | O | | Lock token to merchant/channel | V, MC |
| network token for COF: merchant name, token requestor id | string | O | | Merchant tokens visible to cardholder | V, MC |
| update PAN (lifecycle) | event | O | | Renewal/replacement re-points tokens | V, MC, MQ |

## A7. 3-D Secure (EMV 3DS 2.2 / 2.3.1)

| Field | Type | Req | Enums | Purpose |
|---|---|---|---|---|
| messageVersion | string | M | 2.1.0, 2.2.0, 2.3.1 | Protocol version |
| threeDSServerTransID / acsTransID / dsTransID / sdkTransID | UUID | M | | Transaction ids |
| dsReferenceNumber / acsReferenceNumber / acsOperatorID | string | M/O | | EMVCo-assigned component ids |
| deviceChannel | n2 | M | 01 APP, 02 BRW, 03 3RI | Channel |
| messageCategory | n2 | M | 01 PA (payment), 02 NPA (non-payment), 80+ DS-specific | Payment vs non-payment auth |
| threeDSRequestorAuthenticationInd | n2 | C | 01 payment, 02 recurring, 03 instalment, 04 add card, 05 maintain card, 06 cardholder verification (EMV token ID&V), 07 billing agreement (2.3) | Why authenticating |
| threeDSRequestorChallengeInd | n2 | O | 01 no preference, 02 no challenge requested, 03 challenge requested (requestor preference), 04 challenge mandated, 05 no challenge (TRA performed), 06 no challenge (data share only), 07 no challenge (SCA already performed), 08 no challenge (trust list exemption), 09 challenge requested (trust list prompt) | Merchant preference / exemption |
| threeRIInd | n2 | C | 01 recurring, 02 instalment, 03 add card, 04 maintain card info, 05 account verification, 06 split/delayed shipment, 07 top-up, 08 mail order, 09 telephone order, 10 trust list status check, 11 other payment | 3RI reason |
| transType | n2 | O | 01 goods/service purchase, 03 check acceptance, 10 account funding, 11 quasi-cash, 28 prepaid activation and load | Transaction type |
| purchaseAmount / purchaseCurrency / purchaseExponent / purchaseDate | n / n3 / n1 / ts | C | | Amount authenticated |
| recurringExpiry / recurringFrequency / purchaseInstalData | date / n / n | C | | Recurring/instalment |
| acquirerBIN / acquirerMerchantID / merchantName / mcc / merchantCountryCode | strings | M | | Merchant identity |
| cardholderAccount: acctNumber, cardExpiryDate, acctType (01 NA, 02 credit, 03 debit) | | M | | Card |
| acctInfo {chAccAgeInd, chAccDate, chAccChangeInd, chAccPwChangeInd, nbPurchaseAccount, provisionAttemptsDay, txnActivityDay, txnActivityYear, paymentAccAge, shipAddressUsage, shipNameIndicator, suspiciousAccActivity} | object | O | | Merchant-side account risk data |
| merchantRiskIndicator {shipIndicator, deliveryTimeframe, deliveryEmailAddress, reorderItemsInd, preOrderPurchaseInd, preOrderDate, giftCardAmount, giftCardCount, giftCardCurr} | object | O | shipIndicator 01-07 | Order risk |
| cardholderName, email, homePhone/mobilePhone/workPhone, billAddr*, shipAddr*, addrMatch | | C | | Cardholder data for risk |
| browserInfo {browserAcceptHeader, browserIP, browserJavaEnabled, browserJavascriptEnabled, browserLanguage, browserColorDepth, browserScreenHeight, browserScreenWidth, browserTZ, browserUserAgent} | object | C (BRW) | | Device risk (browser) |
| deviceInfo / sdkAppID / sdkEphemPubKey / sdkReferenceNumber / sdkMaxTimeout / deviceRenderOptions | object | C (APP) | | Device risk (app SDK) |
| threeDSRequestorDecReqInd / threeDSRequestorDecMaxTime | flag / n5 | O | | Decoupled authentication request |
| whiteListStatus / whiteListStatusSource (trustListStatus in 2.3) | an1 | O | Y, N, E, P, R, U | Trusted beneficiary exemption |
| transStatus | an1 | M | Y authenticated, N not authenticated/denied, U could not be performed, A attempted, C challenge required, D decoupled challenge, R rejected, I informational only | Outcome |
| transStatusReason | n2 | C | 01 card authentication failed, 02 unknown device, 03 unsupported device, 04 exceeds authentication frequency limit, 05 expired card, 06 invalid card number, 07 invalid transaction, 08 no card record, 09 security failure, 10 stolen card, 11 suspected fraud, 12 transaction not permitted to cardholder, 13 cardholder not enrolled, 14 transaction timed out at ACS, 15 low confidence, 16 medium confidence, 17 high confidence, 18 very high confidence, 19 exceeds ACS maximum challenges, 20 non-payment not supported, 21 3RI not supported, 22 ACS technical issue, 23 decoupled required but not requested, 24 decoupled max expiry exceeded, 25 decoupled insufficient time, 26 authentication attempted but not performed by cardholder | Machine reason |
| authenticationValue | an28 (base64) | C | Visa CAVV / MC AAV (SPA2) | Cryptographic proof for auth |
| eci | n2 | C | Visa 05 authenticated, 06 attempted, 07 none; MC 02 authenticated, 01 attempted, 00 none, 06/07 exemptions (MC) | Liability indicator |
| authenticationType | n2 | C | 01 static, 02 dynamic, 03 OOB, 04 decoupled | Method used |
| acsChallengeMandated | an1 | C | Y, N | Regulatory challenge requirement (PSD2) |
| acsRenderingType {acsInterface, acsUiTemplate} | object | C | 01 native, 02 HTML; template 01 text, 02 single select, 03 multi select, 04 OOB, 05 HTML other | Challenge UI |
| acsURL / acsSignedContent | string | C | | Challenge endpoint |
| challengeCancel | n2 | C | 01 cardholder cancelled, 03 transaction timed out, 04 timed out at ACS other, 05 timed out first CReq, 06 transaction error, 07 unknown, 08 SDK timed out | Abandon reason |
| interactionCounter | n2 | C | | Challenge attempts |
| cardholderInfo | ans128 | O | | Text shown to cardholder (e.g. contact bank) |
| RReq / RRes resultsStatus | n2 | M | 01 RReq received, 02 challenge not completed, 03 RReq not received | Results message |
| messageExtension[] | array | O | | Scheme-specific (e.g. Visa Data Only, MC Identity Check Insights) |
| exemption claimed (in auth) | enum | C | LOW_VALUE, TRA, TRUSTED_BENEFICIARY, SECURE_CORPORATE, RECURRING/MIT (out of scope), ONE_LEG_OUT, ANONYMOUS_PREPAID | PSD2 SCA exemptions |
| CAVV verification result (in auth) | an1 | C | Visa CAVV results codes 0-9, A-D *(verify)* | Validate authentication value at auth |
| SCA counters per card | ints | C (EEA) | | RTS Art. 11/16: max 5 consecutive or EUR 100 cumulative since last SCA (remote LVP), contactless EUR 150 / 5 txns |

## A8. Disputes

### A8.1 Dispute object fields

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| transaction / original_transaction_token | string | M | | Disputed transaction | SI, MQ |
| amount / currency | int / ccy | M | | Disputed amount | all |
| status | enum | M | SI: unsubmitted, submitted, won, lost, expired; MQ case states *(verify)* | Status | SI, MQ |
| evidence.reason | enum | M | canceled, duplicate, fraudulent, merchandise_not_as_described, no_valid_authorization, not_received, other, service_not_as_described | Normalised dispute reason | SI |
| evidence.<reason>.explanation | string | M | | Cardholder narrative | SI |
| evidence.<reason>.additional_documentation | file | O | | Attachment | SI |
| evidence.canceled {canceled_at, cancellation_policy_provided, cancellation_reason, expected_at, product_description, product_type: merchandise/service, return_status: merchant_rejected/successful, returned_at} | object | C | | Cancellation details | SI |
| evidence.duplicate {card_statement, cash_receipt, check_image, original_transaction} | object | C | | Duplicate / paid by other means | SI |
| evidence.merchandise_not_as_described {received_at, return_description, return_status, returned_at} | object | C | | | SI |
| evidence.not_received {expected_at, product_description, product_type} | object | C | | | SI |
| evidence.service_not_as_described {canceled_at, cancellation_reason, received_at} | object | C | | | SI |
| evidence.no_valid_authorization / fraudulent / other | object | C | | | SI |
| loss_reason | enum | O | cardholder_authentication_issuer_liability, excess_disputes_in_timeframe, has_not_met_the_minimum_dispute_amount_requirements, invalid_duplicate_dispute, invalid_incorrect_amount_dispute, invalid_no_authorization, invalid_use_of_disputes, merchandise_delivered_or_shipped, merchandise_or_service_as_described, not_cancelled, other, refund_issued, submitted_beyond_allowable_time_limit, transaction_approved_after_prior_fraud_dispute, transaction_authorized, transaction_electronically_read, transaction_qualifies_for_visa_easy_payment_service, transaction_unattended | Why lost (incl. scheme auto-rejects) | SI |
| balance_transactions | array | O | | Postings (provisional credit, final) | SI |
| reason_code / network reason | string | M | Visa 10.x-13.x, MC 48xx | Scheme reason | MQ, GL, all |
| network case id / chargeback reference | string | O | VROL case id, Mastercom claim id | Scheme reference | MQ |
| stage dates: dispute_date, response_due, pre_arb_due, arbitration_due | date | M | | Deadlines per stage | MQ |
| Reg E fields: notice_date, investigation_due (10 business days), provisional_credit_date, final_due (45/90 days), customer_letters_sent | dates | C (US) | | Regulation E (12 CFR 1005.11) | US |
| Reg Z fields: billing_error_notice_date (within 60 days of statement), acknowledgement_due (30 days), resolution_due (2 billing cycles, max 90 days) | dates | C (US credit) | | Regulation Z (12 CFR 1026.13) | US |
| fraud report (Visa TC40 / MC SAFE / FRAUD) {fraud_type, report_date} | object | M for fraud | Visa fraud type: 0 lost, 1 stolen, 2 card not received, 3 fraudulent application, 4 counterfeit, 5 misc, 6 fraudulent use of account number (CNP), 9 account takeover *(verify)*; MC SAFE type 00-06, 51 *(verify)* | Mandatory fraud reporting to scheme | V, MC |

### A8.2 Dispute stages

| Scheme | Flow | Stages |
|---|---|---|
| Visa (Visa Claims Resolution, VROL) | Allocation (fraud 10.x, authorisation 11.x) | Dispute → (acquirer) Pre-Arbitration → Arbitration (Visa decides) |
| Visa | Collaboration (processing errors 12.x, consumer disputes 13.x) | Dispute → Dispute Response (representment) → Pre-Arbitration → Pre-Arbitration Response → Arbitration |
| Visa | Pre-dispute | Retrieval Request (TC52, limited), Order Insight, Rapid Dispute Resolution (RDR), Compelling Evidence 3.0 |
| Mastercard (Mastercom) | All | Retrieval Request (optional) → First Chargeback → Second Presentment → Pre-Arbitration → Arbitration Case Filing; also Pre-Compliance → Compliance Case; Collaboration / Ethoca alerts |
| Timelines (typical) | | Issuer dispute 120 days from processing date (or expected delivery date, max 540 days); acquirer response 30 days; pre-arbitration 30 days; arbitration filing 10 days *(verify per scheme manual)* |

### A8.3 Visa dispute reason codes (VCR)

| Code | Category | Description |
|---|---|---|
| 10.1 | Fraud | EMV liability shift counterfeit fraud |
| 10.2 | Fraud | EMV liability shift non-counterfeit fraud |
| 10.3 | Fraud | Other fraud, card-present environment |
| 10.4 | Fraud | Other fraud, card-absent environment |
| 10.5 | Fraud | Visa Fraud Monitoring Program |
| 11.1 | Authorization | Card recovery bulletin |
| 11.2 | Authorization | Declined authorization |
| 11.3 | Authorization | No authorization |
| 12.1 | Processing error | Late presentment |
| 12.2 | Processing error | Incorrect transaction code |
| 12.3 | Processing error | Incorrect currency |
| 12.4 | Processing error | Incorrect account number |
| 12.5 | Processing error | Incorrect amount |
| 12.6.1 | Processing error | Duplicate processing |
| 12.6.2 | Processing error | Paid by other means |
| 12.7 | Processing error | Invalid data |
| 13.1 | Consumer dispute | Merchandise / services not received |
| 13.2 | Consumer dispute | Cancelled recurring transaction |
| 13.3 | Consumer dispute | Not as described or defective merchandise/services |
| 13.4 | Consumer dispute | Counterfeit merchandise |
| 13.5 | Consumer dispute | Misrepresentation |
| 13.6 | Consumer dispute | Credit not processed |
| 13.7 | Consumer dispute | Cancelled merchandise/services |
| 13.8 | Consumer dispute | Original credit transaction not accepted |
| 13.9 | Consumer dispute | Non-receipt of cash or load transaction value |

### A8.4 Mastercard chargeback reason codes (current, post-simplification)

| Code | Category | Description |
|---|---|---|
| 4808 | Authorization | Authorization-related chargeback (replaces 4807/4812 for most cases) |
| 4834 | Point of interaction error | Duplicate / paid by other means, late presentment, incorrect amount/currency, ATM dispute |
| 4837 | Fraud | No cardholder authorization |
| 4840 | Fraud | Fraudulent processing of transactions |
| 4841 | Cardholder dispute | Cancelled recurring / digital goods |
| 4849 | Fraud | Questionable merchant activity |
| 4853 | Cardholder dispute | Not as described / defective, counterfeit, credit not processed, goods/services not provided (consolidated) |
| 4855 | Cardholder dispute | Goods or services not provided (legacy / specific) |
| 4859 | Cardholder dispute | Addendum, no-show, ATM dispute |
| 4860 | Cardholder dispute | Credit not processed |
| 4863 | Fraud | Cardholder does not recognise – potential fraud |
| 4870 | Fraud | Chip liability shift |
| 4871 | Fraud | Chip / PIN liability shift |
| 4999 | Domestic | Domestic chargeback dispute (Europe region) |
| 4831 / 4842 / 4846 | Legacy | Transaction amount differs / late presentment / currency (now under 4834) |
| 7xxx (Maestro / debit), 17 / 30 etc. | Other networks | Use network-specific tables (Pulse, Star, Discover, Amex) |

### A8.5 Evidence types (union)

Cardholder statement / affidavit; cardholder letter (Reg E); police report; receipt; invoice; proof of cancellation (date, method, cancellation number); cancellation policy; proof of return (tracking number, return date); delivery proof / non-delivery confirmation; correspondence with merchant (merchant contact attempt required for most 13.x and 4853); card statement (paid by other means); check image / cash receipt; original transaction ID (duplicate); screenshots; merchant response (representment documents); compelling evidence 3.0 data (IP, device id, shipping address used in two prior undisputed transactions); 3DS/authentication proof; authorization log; scheme documents (VROL questionnaires, Mastercom forms).

---

# PART B: PAYMENTS CATALOGUE

## B1. Payment order: common fields (provider + ISO 20022 union)

| Field | Type | Req | Enums | Purpose | Providers / ISO path |
|---|---|---|---|---|---|
| type (rail) | enum | M | MT: ach, book, check, rtp, wire, card, stablecoin (+ au_becs, bacs, eft, interac, sepa, signet, sen, provxchange, zengin, cross_border, nics, nz_becs, masav, chats, neft, sic, dk_nets, se_bankgirot, hu_ics, gb_fps ...); ST: ach, us_domestic_wire, stripe; UN: achPayment, wirePayment, bookPayment, pushToCardPayment, checkPayment | Rail | MT, ST, UN |
| subtype | enum | C | ACH SEC: CCD, CIE, CTX, IAT, PPD, TEL, WEB; SEPA: sepa_credit_transfer, sepa_instant_credit_transfer *(verify)* | Rail sub-format | MT |
| direction | enum | M | credit, debit | Push vs pull | MT, UN |
| amount / currency | int / ccy | M | | | all; ISO IntrBkSttlmAmt, InstdAmt |
| priority | enum | O | normal, high (MT); ISO InstrPrty HIGH/NORM | Same-day ACH / urgent | MT |
| effective_date | date | O | | Desired settlement/effective date | MT; ISO ReqdExctnDt |
| process_after | ts | O | | Hold until | MT |
| expires_at | ts | O | | Auto-cancel if not sent | MT |
| status | enum | M | see B10 | Lifecycle | all |
| originating_account_id | uuid | M | | Debtor internal account | MT; ISO DbtrAcct |
| receiving_account_id / receiving_account_type / receiving_account (inline) | uuid / enum / obj | M | external_account, internal_account | Creditor account | MT |
| counterparty_id | uuid | O | | Counterparty | MT |
| originating_party_name / originating_party_address | string / address | O | | Override originator shown on the payment | MT |
| ultimate_originating_party_name / _identifier | string | O | | ISO UltmtDbtr (payments on behalf of) | MT; ISO UltmtDbtr |
| ultimate_receiving_party_name / _identifier | string | O | | ISO UltmtCdtr | MT; ISO UltmtCdtr |
| description | string | O | | Internal description | MT, UN |
| statement_descriptor | string | O | ACH company entry description 10, wire 140, RTP 140 | Text shown to receiver | MT, ST |
| remittance_information | string | O | 140 (ISO Ustrd), ACH addenda 80 | Free-text remittance | MT; ISO RmtInf/Ustrd |
| structured remittance (creditor reference ISO 11649 RF, invoices) | object | O | | Reconciliation | ISO RmtInf/Strd |
| purpose | string | C | ISO ExternalPurpose1Code (e.g. SALA, SUPP, TAXS, GDDS); CBPR+/some countries require | Purpose | MT; ISO Purp |
| charge_bearer | enum | C | shared, sender, receiver (MT); ISO ChrgBr DEBT, CRED, SHAR, SLEV; MT103 71A OUR/BEN/SHA | Who pays fees | MT, ISO |
| foreign_exchange_indicator | enum | C | fixed_to_variable, variable_to_fixed | Which side fixed for FX wires | MT |
| foreign_exchange_contract | string | O | | Pre-booked FX deal id | MT |
| foreign_exchange_rate {base_amount, base_currency, exponent, rate_string, target_amount, target_currency, value} | object | RO | | Applied rate | MT |
| fallback_type | enum | O | ach | Fallback rail if RTP fails | MT |
| nsf_protected | bool | O | | Reject if debtor balance insufficient (no overdraft) | MT |
| send_remittance_advice | bool | O | | Email counterparty | MT |
| line_items[] {amount, description, accounting_category_id, metadata} | array | O | | Itemisation | MT |
| accounting {account_id, class_id} | object | O | | ERP mapping | MT |
| reference_numbers[] {reference_number, reference_number_type} | array | RO | ach_original_trace_number, ach_trace_number, bankprov_payment_activity_date, bankprov_payment_id, bnk_dev_prenotification_identifier, bnk_dev_transfer_identifier, chase_*, fedwire_imad, fedwire_omad, swift_uetr, swift_mir, rtp_instruction_identification, sepa_end_to_end_id, ... | Rail/bank references | MT |
| reconciliation_status | enum | RO | unreconciled, tentatively_reconciled, reconciled | Recon status | MT |
| current_return / current_hold | object | RO | | Latest return / compliance hold | MT |
| transaction_monitoring_enabled / decision_id | bool / string | O | | Compliance screening | MT |
| ledger_transaction / ledger_transaction_id | object | O | | Embedded ledger posting | MT |
| idempotency_key | header | M | | Replay safety | MT, ST, UN |
| expected_arrival_date / expected_completion_date | date | RO | | ETA shown to customer | ST, UN |
| status_transitions {posted_at, failed_at, canceled_at, returned_at} | object | RO | | Timestamps per state | ST |
| end_user_details {present, ip_address} | object | M | | Regulatory: end-user initiated & IP (Stripe Treasury) | ST |
| hosted_regulatory_receipt_url | string | RO | | Reg E / money-transmission receipt | ST |
| tracking_details {ach.trace_id, us_domestic_wire.imad/omad/chips} | object | RO | | Rail tracking | ST |
| tags | map | O | | Client tags | UN |
| end_to_end_id / instruction_id / uetr / tx_id / msg_id | string | M | ISO EndToEndId(35), InstrId(35), UETR (UUIDv4), TxId(35), MsgId(35) | ISO identification | ISO |
| settlement method / clearing system | enum | M | ISO SttlmMtd: INDA, INGA, COVE, CLRG; ClrSys: e.g. TGT, FDW, EBA, STET | Settlement routing | ISO |
| service_level / local_instrument / category_purpose | codes | C | SvcLvl: SEPA, URGP, NURG, SDVA, G001-G004 (gpi); LclInstrm: INST, CORE, B2B, CCD, PPD, ...; CtgyPurp: SALA, SUPP, TREA, CASH, INTC, ... | Payment type information | ISO PmtTpInf |
| intermediary_agent1/2/3 (+account) | BIC/clearing | C | | Correspondent chain | ISO IntrmyAgt1-3; MT103 :56A: |
| instruction_for_creditor_agent / next_agent | code+text | O | CHQB, HOLD, PHOB, TELB; PHOA, TELA | Special handling | ISO InstrForCdtrAgt; MT103 :23E: / :72: |
| regulatory_reporting | object | C | | Central-bank reporting codes | ISO RgltryRptg; MT103 :77B: |
| debtor/creditor identification (LEI, BIC, org id, private id: DOB+place of birth, national id) | object | C | | Travel-rule / FATF R16 | ISO Dbtr/Id, Cdtr/Id |
| debtor/creditor postal address (structured: StrtNm, BldgNb, PstCd, TwnNm, CtrySubDvsn, Ctry) | address | C | Structured or hybrid address mandatory in CBPR+ from Nov 2026 *(verify)* | Address | ISO PstlAdr |
| charges information[] {amount, agent} | array | RO | | Deducted charges per agent | ISO ChrgsInf (pacs.008), MT103 :71F:/:71G: |
| exchange rate / instructed amount | rate / amount | C | | FX | ISO XchgRate, InstdAmt; MT103 :36:, :33B: |

## B2. Rail-specific payment fields

### B2.1 ACH (Nacha)

| Field | Type | Req | Enums | Purpose |
|---|---|---|---|---|
| sec_code | an3 | M | PPD, CCD, CTX, WEB, TEL, IAT, POP, BOC, ARC, RCK, CIE, XCK, ACK, ATX, MTE, POS, SHR, TRC, TRX, DNE, ENR, COR | Standard Entry Class |
| transaction_code | n2 | M | 22 checking credit, 23 checking prenote credit, 24 zero-dollar credit w/ remittance, 27 checking debit, 28 checking prenote debit, 32 savings credit, 33 savings prenote credit, 37 savings debit, 38 savings prenote debit, 42/47 GL credit/debit, 52/55 loan credit/debit, 21/26/31/36 return/NOC entries | Account type + direction |
| receiving_dfi_identification + check_digit | n8 + n1 | M | ABA routing | RDFI |
| dfi_account_number | an17 | M | | Receiver account |
| individual_id_number / identification number | an15 | O | | Receiver id at originator |
| individual_name / receiving company name | an22 | M | | Receiver name |
| discretionary_data | an2 | O | | |
| addenda_record_indicator | n1 | M | 0/1 | Addenda present |
| trace_number | n15 | M | ODFI routing(8) + seq(7) | Entry trace |
| company_name | an16 | M | | Originator name shown |
| company_identification | an10 | M | usually 1 + EIN | Originator id |
| company_entry_description | an10 | M | e.g. PAYROLL, PURCHASE, REVERSAL (mandatory literal for reversals), RETURN FEE, RETRY PYMT, NONSETTLED, HCCLAIMPMT | Statement text |
| company_descriptive_date | an6 | O | | Displayed date |
| company_discretionary_data | an20 | O | | |
| effective_entry_date | YYMMDD | M | | Requested settlement date (same-day if today and window open) |
| settlement_date (julian) | n3 | RO | | Set by operator |
| service_class_code | n3 | M | 200 mixed, 220 credits, 225 debits | Batch type |
| originator_status_code | an1 | M | | |
| same_day | bool | O | | Same Day ACH (limit USD 1,000,000 per entry) |
| addenda type 05 payment_related_information | an80 | O | 1 addenda for PPD/CCD/WEB, up to 9,999 for CTX (ANSI X12 820) | Remittance |
| addenda type 98 (NOC) / 99 (return) / 10-18 (IAT) | records | C | | Change notices, returns, IAT party info |
| IAT fields: foreign_exchange_indicator (FV/VF/FF), iso_destination_country, originator/receiver names+addresses, ODFI/RDFI country, gateway operator OFAC indicator | various | C | | Cross-border ACH |
| WEB: payment type code (R recurring / S single) | an1 | C | | WEB entries |
| TEL/WEB authorisation evidence (recording, timestamp, IP) | object | C | | Proof of authorisation (Nacha requirement) |
| prenote | bool | O | | Zero-dollar account validation (wait 3 banking days) |
| counterparty_verification_method | enum | O | Plaid, micro-deposits, prenote, none | Account ownership validation (Nacha WEB debit fraud screening rule) | UN, MT |
| clearing_days_override | int | O | | Hold days for ACH debits (fund availability) | UN |
| fraud monitoring: risk-based ACH monitoring (Nacha 2026 rules) | flag | M (2026) | | Phase 1 effective 2026-03-20, Phase 2 2026-06-22 *(verify)* |

### B2.2 Wires: Fedwire (ISO 20022 since 2025-07-14) and legacy FAIM tags

| Legacy tag | ISO 20022 equivalent | Content |
|---|---|---|
| {1500} | AppHdr / BizMsgIdr | Sender supplied info (format version, user request correlation, test/production) |
| {1510} | LclInstrm / message type | Type/subtype (10 00 basic funds transfer, 10 01 request for reversal, 10 02 reversal of transfer, 16 xx bank transfer, 31/32 drawdown request/response) |
| {1520} | MsgId (IMAD) | Input message accountability data: YYYYMMDD + 8 char source + 6 seq |
| {2000} | IntrBkSttlmAmt | Amount (USD) |
| {3100} | InstgAgt | Sender DI routing number |
| {3320} | InstrId | Sender reference |
| {3400} | InstdAgt | Receiver DI routing number |
| {3600} | business function | CTR customer transfer, CTP customer transfer plus, BTR bank transfer, DRC customer drawdown, DRW bank drawdown, CKS check same day settlement, DEP deposit to sender, FFR/FFS fed funds |
| {3700}/{3710}/{3720} | ChrgBr / InstdAmt / XchgRate | Charges, instructed amount, exchange rate |
| {4000} / {4100} | IntrmyAgt1 / CdtrAgt | Intermediary FI, beneficiary FI |
| {4200} | Cdtr + CdtrAcct | Beneficiary |
| {4320} | EndToEndId | Reference for beneficiary |
| {5000} / {5100} / {5200} | Dbtr / DbtrAgt / InstgAgt (orig) | Originator, originator FI, instructing FI |
| {6000} | RmtInf/Ustrd | Originator to beneficiary info (4x35) |
| {6100}-{6500} | InstrForNxtAgt / InstrForCdtrAgt | FI-to-FI info |
| {8200}-{8750} | RmtInf/Strd | Unstructured addenda / related remittance / structured remittance |
| OMAD | output accountability | Fed output reference (YYYYMMDD + source + seq + time) |

### B2.3 SWIFT MT103 / CBPR+ pacs.008 and gpi

| MT field | ISO field | Content |
|---|---|---|
| Block 3 {121:} | UETR | Unique end-to-end transaction reference (UUIDv4), mandatory on all cross-border |
| Block 3 {111:} | SvcLvl G001 / G004 | gpi service type identifier |
| :20: | InstrId | Sender's reference |
| :23B: | (bank operation code) | CRED, CRTS, SPAY, SPRI, SSTD |
| :23E: | InstrForCdtrAgt / NxtAgt | CHQB, HOLD, INTC, PHOB, PHOI, PHON, REPA, SDVA, TELB, TELE, TELI |
| :26T: | Purp | Transaction type code |
| :32A: | IntrBkSttlmDt + Ccy + IntrBkSttlmAmt | Value date, currency, settled amount |
| :33B: | InstdAmt | Instructed currency/amount |
| :36: | XchgRate | Exchange rate |
| :50A/F/K: | Dbtr (+Id, PstlAdr) | Ordering customer (F = structured with DOB, national id) |
| :51A: | (sending institution) | |
| :52A/D: | DbtrAgt | Ordering institution |
| :53A/B/D: | InstgRmbrsmntAgt | Sender's correspondent |
| :54A: | InstdRmbrsmntAgt | Receiver's correspondent |
| :55A: | ThrdRmbrsmntAgt | Third reimbursement institution |
| :56A/C/D: | IntrmyAgt1 | Intermediary |
| :57A/B/C/D: | CdtrAgt | Account-with institution |
| :59/59A/59F: | Cdtr + CdtrAcct | Beneficiary |
| :70: | RmtInf | Remittance (4x35) |
| :71A: | ChrgBr | OUR / SHA / BEN |
| :71F: / :71G: | ChrgsInf | Sender's charges / receiver's charges |
| :72: | InstrForNxtAgt | Sender to receiver info |
| :77B: | RgltryRptg | Regulatory reporting |
| gpi tracker status | TrckrStsAndTx (trck.001) / pacs.002 | ACSP + reason G000 (sent to next agent), G001 (credited, no further tracking), G002 (credit may not be same day), G003 (pending documents), G004 (pending funds); ACCC (credited to beneficiary); RJCT; also confirmed amount, charges, FX per hop |
| Coexistence | | MT for CBPR+ payments retired 2025-11-22; MX (pacs.008/009/004/002, camt.056/029) mandatory |

### B2.4 SEPA SCT / SCT Inst / SDD

| Field | Type | Req | Enums | Purpose |
|---|---|---|---|---|
| debtor IBAN / creditor IBAN | IBAN | M | | Accounts |
| debtor/creditor BIC | BIC | O (IBAN-only rule) | | Agents |
| debtor / creditor name | 70 | M | | Names (VoP name check on creditor) |
| debtor address | address | C | Mandatory when either PSP is outside EEA | Address |
| end_to_end_id | 35 | M | | Customer reference ("NOTPROVIDED" allowed) |
| remittance (Ustrd 140 or Strd with RF creditor reference) | | O | | |
| purpose / category purpose | codes | O | | |
| ultimate debtor / ultimate creditor | name + id | O | | |
| requested execution date | date | M | | D+1 for SCT |
| SCT Inst: acceptance datetime (AccptncDtTm) | ts | M | | Timestamp for the 10-second execution clock |
| SCT Inst: time-out | | M | 10 s target; reason AB05/AB06 on timeout | Instant rule |
| SCT Inst: max amount | | | no scheme maximum after 2025-10-09 (IPR); PSUs may set own limits *(verify)* | Limits |
| VoP request/response | | M (euro area from 2025-10-09) | MTCH, CMTC (close match + suggested name), NMTC, NOAP | Payee name check before SCT/SCT Inst |
| SDD: mandate id (UMR, 35) | | M | | Mandate |
| SDD: date of signature | date | M | | |
| SDD: creditor identifier (CI) | 35 | M | format CC + check digits + business code + national id | Creditor id |
| SDD: sequence type | | M | FRST, RCUR, FNAL, OOFF | Collection sequence (FRST optional since 2016) |
| SDD: amendment indicator + original mandate id / creditor scheme id / debtor account / debtor agent (SMNDA) | | C | | Mandate changes |
| SDD: electronic signature / e-mandate | | O | | |
| SDD: pre-notification | | M | 14 calendar days unless agreed | Notify debtor |
| SDD Core refund | | | 8 weeks no-questions (authorised), 13 months (unauthorised) | Debtor rights |
| SDD B2B | | | No refund right; debtor bank verifies mandate | |
| R-transactions | | | Reject, Return, Refund, Reversal, Revocation, Request for cancellation; SCT recall (camt.056) within 10 business days (fraud 13 months) | Exceptions |

### B2.5 UK Faster Payments / Bacs

| Field | Req | Notes |
|---|---|---|
| sort code (6) + account number (8) | M | Creditor account |
| beneficiary name (18 in legacy FPS; longer in ISO) | M | CoP name check before payment |
| reference (18) | M | Shown to payee |
| payment type | M | SIP single immediate, FDP forward-dated, SOP standing order, RPP return payment |
| scheme limit | | Scheme maximum £1m per transaction; participant limits lower *(verify)* |
| secondary reference / roll number | C | Building-society accounts |
| CoP request: name, account type (personal/business), secondary ref | M | |
| CoP response codes | | Match; ANNM (no match), MBAM (close match), BANM (business account name match, personal requested), PANM (personal match, business requested), BAMM / PAMM (close match with wrong account type), AC01 (account does not exist), IVCR (invalid secondary reference), ACNS (account not supported), OPTO (opted out), CASS (switched via CASS), SCNS (sort code not supported) |
| APP fraud reimbursement (PSR, since 2024-10-07) | M (UK) | Claim tracking, max £85,000 reimbursement, 50/50 sending/receiving PSP split, 5-business-day decision *(verify)* |
| Bacs: SUN (service user number), transaction code (01 first DD, 17 regular DD, 18 re-presented, 19 final, 99 credit, 0N/0C/0S AUDDIS), processing date D+3 | M | Bacs Direct Debit / credit |
| Bacs reports: ARUDD (unpaid DD, reason codes 0-B), ADDACS (mandate amendments 0-E, B, C, D, E, R), AUDDIS returns, AWACS (wrong account), ARUCS (unapplied credits), DDIC (indemnity claims) | | Exceptions |

### B2.6 US instant: FedNow and RTP

| Field | Req | Notes |
|---|---|---|
| message set | M | ISO 20022 pacs.008 (credit), pacs.002 (status), pacs.004 (return), pacs.028 (status request), camt.056/029 (return request/response), pain.013/014 (request for payment / response), camt.026/028 (request for information / additional info), admi.002/004/007 (system), camt.052/054 (activity / notification) |
| creditor/debtor routing (ABA) + account | M | |
| debtor & creditor names, debtor address (RTP: required) | M | |
| end_to_end_id, instruction_id, UETR | M | |
| network limit | | RTP USD 10,000,000 (since Feb 2025); FedNow default USD 500,000, configurable up to USD 1,000,000+ *(verify current FedNow max)* |
| participant-configurable limits | O | Per-participant caps, fraud controls (negative list) in FedNow |
| timeouts | M | RTP 20 s receiver response; FedNow 20 s (with 5 s extension options) *(verify)* |
| return request (camt.056) reasons | | DUPL, FRAD, TECH, CUST, AM09, AC03, UPAY |
| request for payment fields (pain.013): requested execution date, expiry date (XpryDt), amount modification allowed, early payment discount, payment condition (partial payment allowed, guaranteed), creditor reference, invoice (RfP with invoice presentment URL) | C | RfP |
| RfP response status (pain.014) | | ACTC, RJCT (with reasons e.g. AM09, NARR, CUST...), ACWP; payment follows as pacs.008 linked by RfP id |

### B2.7 Card push / pull (Visa Direct OCT/AFT, Mastercard Move/Send)

| Field | Type | Req | Enums | Purpose |
|---|---|---|---|---|
| transaction type | enum | M | OCT (push, credit to card), AFT (pull, debit from card) | Direction |
| recipient PAN / token | string | M | | Destination card |
| business_application_identifier (Visa BAI) | an2 | M | AA account-to-account, BB business-to-business, BI bank-initiated, BP non-card bill pay, CB consumer bill pay, CI cash in, CO cash out, CP card bill payment, FD funds disbursement, GD government disbursement, GP gambling payout, LO loyalty, MD merchant disbursement, MI merchant-initiated, OG online gambling, PD payroll/pension, PG payment to government, PP person-to-person, TU top-up, WT wallet transfer | Use case |
| MC payment transaction type indicator (PTTI, DE48 SE77) | an3 | M | C07 P2P, C52 account-to-account, C55 business disbursement, C56 government disbursement, C57 gambling payout, C65 general B2B *(verify full list)* | Use case |
| sender name / address / city / state / country / account number / reference | strings | M (cross-border, >USD 3,000 travel rule) | | Sender data |
| source_of_funds_code | an2 | M | 01 Visa credit, 02 Visa debit, 03 Visa prepaid, 04 cash, 05 debit/deposit account (non-card), 06 credit account (non-card) *(verify)* | Funding source |
| recipient name | string | C | | Recipient data (required for some corridors) |
| purpose_of_payment | string | C | | Cross-border |
| fast funds indicator / push funds block indicator / online gambling block indicator | flags | RO | from Funds Transfer Attributes Inquiry (FTAI) / PAAI | Card eligibility (fast funds = funds within 30 min) |
| card network / issuer country / card type (debit/credit/prepaid) | enum | RO | | Eligibility & pricing |
| transaction_identifier / approval code / RRN / STAN | ids | RO | | Tracking |
| merchant category code (6012 / 4829 / 6538 / 6540) | n4 | M | | Classification |
| fee / interchange (OCT issuer earns or pays) | amount | RO | | Pricing |

### B2.8 Book, check and check deposit (Unit, MT, ST)

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| book: amount, description, from/to account, transactionSummaryOverride | | M | status: Pending, Sent, Rejected | Internal transfer | UN, MT (book) |
| check payment: amount, memo, counterparty {name, address}, sendDate, check_number, onUsAuxiliary, onUs, mailing service, signature image, return address | | M | status: New, Pending, PendingCancellation, Canceled, InProduction, InDelivery, Delivered, Returned, Processed, PendingFirstLevelReview, PendingReview, MarkedForReturn *(verify)* | Outbound printed check | UN, MT (check) |
| check stop payment {amount, check_number, expiration} | | O | status Active, Disabled | Stop | UN |
| positive pay (issued-check file + exception decisions) | | O | pay / return | Check fraud control | banks |
| check deposit (RDC): amount, front_image, back_image, check_number, counterparty {name, routingNumber, accountNumber}, settlementDate | | M | status: AwaitingImages, AwaitingFrontImage, AwaitingBackImage, Pending, PendingReview, AwaitingCustomerConfirmation, Rejected, Clearing, Sent, Canceled, Returned | Mobile deposit | UN |
| check deposit reject reasons | enum | C | e.g. PoorImageQuality, IncompleteEndorsement, DuplicateCheck, InvalidAmount, AmountMismatch, StaleDated, PostDated, SuspectedFraud, NotEligible *(verify)* | | UN |
| fund availability (Reg CC hold schedule) | | M (US) | next-day items, $225 first-day availability, exception holds *(verify current amounts)* | Holds | US |

## B3. Counterparties / beneficiaries

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| name | string | M | | Counterparty display name | MT, UN |
| email | email | O | | Notifications / remittance advice | MT |
| send_remittance_advice | bool | O | | | MT |
| legal_entity_id / party_type | uuid / enum | O | business, individual | KYC link | MT |
| taxpayer_identifier | string | O | | Tax reporting | MT |
| accounts[] | array | M | | Many accounts per counterparty | MT |
| accounts[].account_type | enum | C | checking, savings, cash, general_ledger, loan, non_resident, other, overdraft | ACH txn code derivation | MT |
| accounts[].party_name / party_type / party_address / party_identifier | | M | | Account-holder data | MT |
| accounts[].account_details[] {account_number, account_number_type} | array | M | account_number_type: au_number, base_address, clabe, ethereum_address, hk_number, iban, id_number, nz_number, other, pan, polygon_address, sg_number, solana_address, wallet_address | Multiple identifiers | MT |
| accounts[].routing_details[] {routing_number, routing_number_type, payment_type, bank_name, bank_address} | array | M | routing_number_type: aba, au_bsb, br_codigo, ca_cpa, chips, cnaps, dk_interbank_clearing_code, gb_sort_code, hk_interbank_clearing_code, hu_interbank_clearing_code, id_sknbi_code, in_ifsc, jp_zengin_code, my_branch_code, mx_bank_identifier, nz_national_clearing_code, pl_national_clearing_code, se_bankgiro_clearing_code, swift, za_national_clearing_code | Routing per rail | MT |
| accounts[].contact_details[] {contact_identifier, contact_identifier_type} | array | O | email, phone_number, website | | MT |
| accounts[].verification_status | enum | RO | unverified, pending_verification, verified (+ needs_approval, denied) | Ownership verified | MT |
| verification method | enum | O | micro_deposits (two amounts), prenote, plaid/aggregator, name_match (CoP/VoP) | Verification | MT, UN, ST |
| micro-deposit verification {amounts[], attempts, expires_at} | object | C | | | MT, ST |
| ledger_account_id | uuid | O | | Counterparty sub-ledger | MT |
| metadata | map | O | | | all |
| beneficiary_type | enum | M | INDIVIDUAL, BUSINESS | | ours |
| cooling-off / new payee limit, trusted flag | | O | | APP fraud controls | ours, UK banks |
| first-payment warnings / confirmation of payee result on save | | O | | | UK, EU |

## B4. Mandates and direct debits

| Field | Type | Req | Enums | Purpose | Source |
|---|---|---|---|---|---|
| unique mandate reference | an35 | M | | UMR | EPC SDD, Bacs DDI ref (18) |
| creditor identifier | an35 | M | SEPA CI / Bacs SUN / ACH company id | | EPC, Bacs, NACHA |
| creditor name + address | | M | | Shown on mandate | EPC |
| debtor name + address | | M | | | EPC |
| debtor account (IBAN) + BIC (optional) | | M | | | EPC |
| type of payment | enum | M | recurrent, one-off | | EPC |
| date of signing + place of signing | date / string | M | | | EPC |
| signature (paper / e-mandate) {signer, method, ip, timestamp, document} | object | M | | Evidence | EPC, NACHA |
| mandate scheme / local instrument | enum | M | CORE, B2B; ACH PPD/CCD/WEB/TEL authorization types | | EPC, NACHA |
| amendment fields: original mandate id, original creditor scheme id, original creditor name, original debtor account, original debtor agent (SMNDA) | | C | | Mandate changes without re-signing | EPC pain.008 AmdmntInfDtls |
| last sequence type / next sequence type | enum | RO | FRST, RCUR, FNAL, OOFF | | EPC |
| dormancy expiry | date | RO | 36 months after last collection | Mandate expires | EPC |
| max amount / frequency / count | | O | | Debtor-side limits | EPC, PSD2 Art. 5(3)(d) debtor rights |
| debtor-side controls: limit by amount/periodicity, block all DD, allow-list or block-list creditors | | M (EU debtor PSP) | | PSD2 / SEPA Reg Art. 5(3)(d) | EU |
| B2B mandate confirmation by debtor bank | | M (B2B) | | Debtor PSP must check mandate | EPC |
| Bacs DDI: AUDDIS code (0N new, 0C cancel, 0S conversion) | | M (Bacs) | | Lodgement | Bacs |
| Bacs ADDACS reason codes | | | 0 refer to payer, 1 instruction cancelled, 2 payer deceased, 3 account transferred, B account closed, C account transferred to new bank, D advance notice disputed, E instruction amended, R instruction re-instated | Debtor-bank amendments | Bacs |
| ACH authorisation: written / oral recorded (TEL) / electronic (WEB) with revocation method | | M | | Nacha authorisation rules | NACHA |
| PIX Automático / UPI AutoPay specifics (frequency, max amount, first charge date, retry policy) | | C | | Local schemes (already in MandateScheme) | BCB, NPCI |
| pre-notification | ts | M | 14 days default SEPA, Bacs 10 working days | Advance notice | EPC, Bacs |

## B5. Returns, rejections and change notices: reason codes

### B5.1 Nacha ACH return codes (key)

| Code | Meaning | Return window |
|---|---|---|
| R01 | Insufficient funds | 2 banking days |
| R02 | Account closed | 2 |
| R03 | No account / unable to locate | 2 |
| R04 | Invalid account number structure | 2 |
| R05 | Unauthorized debit to consumer account using corporate SEC code | 60 calendar days |
| R06 | Returned per ODFI's request | as agreed |
| R07 | Authorization revoked by customer | 60 |
| R08 | Payment stopped | 2 |
| R09 | Uncollected funds | 2 |
| R10 | Customer advises originator unknown / not authorized | 60 |
| R11 | Customer advises entry not in accordance with terms of authorization | 60 |
| R12 | Account sold to another DFI | 2 |
| R13 | Invalid ACH routing number | operator |
| R14 | Representative payee deceased | 2 |
| R15 | Beneficiary or account holder deceased | 2 |
| R16 | Account frozen / returned per OFAC | 2 |
| R17 | File record edit criteria / questionable entry (incl. suspected fraud with "QUESTIONABLE") | 2 |
| R20 | Non-transaction account | 2 |
| R21 | Invalid company identification | 2 |
| R22 | Invalid individual ID number | 2 |
| R23 | Credit entry refused by receiver | |
| R24 | Duplicate entry | 2 |
| R29 | Corporate customer advises not authorized | 2 |
| R31 | Permissible return entry (CCD/CTX, ODFI agrees) | |
| R33 | Return of XCK entry | 60 |
| R37 | Source document presented for payment (ARC/BOC/POP) | 60 |
| R38 | Stop payment on source document | 60 |
| R39 | Improper source document | 2 |
| R51 | Item related to RCK entry ineligible or improper | 60 |
| R52 | Stop payment on item related to RCK | 60 |
| R53 | Item and RCK entry presented for payment | 60 |
| R61-R69 | Dishonored returns (misrouted, untimely, duplicate, field errors, erroneous/reversing debit) | 5 banking days |
| R70-R77 | Contested dishonored returns / permissible return not accepted | 2 |
| R80-R85 | IAT-specific (coding error, non-participant country, invalid foreign RDFI, foreign RDFI unable to settle, not processed by gateway, incorrectly coded outbound IAT) | |
| Unauthorised return rate thresholds | | Unauthorised 0.5%, administrative 3%, overall 15% |
| Reinitiation | | Max 2 re-presentments after R01/R09 within 180 days, "RETRY PYMT" description |

### B5.2 Nacha Notifications of Change (NOC, addenda 98) and refused NOC

| Code | Meaning |
|---|---|
| C01 | Incorrect DFI account number |
| C02 | Incorrect routing number |
| C03 | Incorrect routing number and account number |
| C05 | Incorrect transaction code |
| C06 | Incorrect account number and transaction code |
| C07 | Incorrect routing number, account number and transaction code |
| C08 | Incorrect receiving DFI identification (IAT) |
| C09 | Incorrect individual identification number |
| C13 | Addenda format error |
| C14 | Incorrect SEC code for outbound international payment |
| C61-C69 | Refused NOC (misrouted, incorrect trace, incorrect company id, etc.) |
| Rule | Originator must apply the change within 6 banking days or before the next entry, whichever is later |

### B5.3 ISO 20022 status, return, reject and cancellation codes (key)

| Code | Set | Meaning |
|---|---|---|
| ACTC | TxSts | Accepted technical validation |
| ACCP | TxSts | Accepted customer profile |
| ACSP | TxSts | Accepted settlement in process |
| ACSC | TxSts | Accepted settlement completed (debtor side) |
| ACCC | TxSts | Accepted settlement completed (creditor credited) |
| ACWC | TxSts | Accepted with change |
| ACWP | TxSts | Accepted without posting |
| ACFC | TxSts | Accepted funds checked |
| PDNG | TxSts | Pending |
| RCVD | TxSts | Received |
| PART | TxSts | Partially accepted (group) |
| RJCT | TxSts | Rejected |
| BLCK | TxSts | Blocked (funds blocked, e.g. sanctions) |
| AC01 | Return/reject | Incorrect account number |
| AC03 | | Invalid creditor account number |
| AC04 | | Closed account |
| AC06 | | Blocked account |
| AC13 | | Invalid debtor account type |
| AG01 | | Transaction forbidden |
| AG02 | | Invalid bank operation code |
| AM02 | | Not allowed amount |
| AM04 | | Insufficient funds |
| AM05 | | Duplication |
| AM09 | | Wrong amount |
| AM23 | | Amount exceeds settlement limit |
| BE04 | | Missing creditor address |
| BE05 | | Unrecognised initiating party |
| CNOR | | Creditor bank not registered |
| DNOR | | Debtor bank not registered |
| FF01 | | Invalid file format |
| MD01 | | No mandate |
| MD02 | | Missing mandatory mandate information |
| MD06 | | Refund request by end customer |
| MD07 | | End customer deceased |
| MS02 | | Not specified reason (customer) |
| MS03 | | Not specified reason (agent) |
| RC01 | | BIC incorrect |
| RR01-RR04 | | Regulatory: missing debtor account/id, missing debtor name/address, missing creditor name/address, regulatory reason |
| SL01 | | Specific service offered by debtor agent (DD blocking) |
| AB05 / AB06 | | Timeout at creditor agent / instructed agent (instant) |
| AB08 | | Creditor agent offline |
| ERIN | | Extended remittance info option not supported |
| FOCR | | Following cancellation request (return after camt.056) |
| FRAD | Cancellation (camt.056) | Fraudulent origin |
| DUPL | | Duplicate payment |
| TECH | | Technical problem |
| CUST | | Requested by customer |
| AGNT | | Incorrect agent |
| CURR | | Incorrect currency |
| UPAY | | Undue payment |
| ACCR / RJCR / PDCR | camt.029 status | Accepted cancellation request / rejected / pending |
| ARDT / NOAS / NOOR / LEGL / AC04 / AM04 / CUST | camt.029 reject reasons | Already returned / no answer from customer / no original transaction received / legal decision / closed account / insufficient funds / customer decision |
| VoP: MTCH / CMTC / NMTC / NOAP | EPC VOP | Match / close match / no match / check not possible |

### B5.4 Provider-level failure / return enums

| Provider | Field | Values |
|---|---|---|
| MT Return | code | ACH R01-R85, check 901-907 *(verify)*, currencycloud, cross-border bank codes, SEPA/BACS reason codes |
| MT Return | type | ach, ach_noc, au_becs, bacs, book, check, cross_border, eft, interac, manual, paper_item, sepa, wire |
| MT Return | status | completed, failed, pending, processing, returned, sent |
| MT Return | role | originating, receiving |
| MT Return | returnable_type | incoming_payment_detail, paper_item, payment_order, return, reversal |
| MT Return | date_of_death, additional_information, failure_reason | |
| MT Reversal | reason | duplicate, incorrect_amount, incorrect_receiving_account, date_earlier_than_intended, date_later_than_intended |
| MT Reversal | status | completed, failed, pending, processing, returned, sent |
| ST OutboundPayment.returned_details.code | | account_closed, account_frozen, bank_account_restricted, bank_ownership_changed, declined, incorrect_account_holder_name, invalid_account_number, invalid_currency, no_account, other |
| ST ReceivedCredit.failure_code | | account_closed, account_frozen, international_transaction, other |
| ST ReceivedDebit.failure_code | | account_closed, account_frozen, insufficient_funds, international_transaction, other |
| ST Received* network | | ach, card, rtp, stripe, us_domestic_wire |
| ST reversal_details | | deadline, restricted_reason (already_reversed, deadline_passed, network_restricted, other, source_flow_restricted) |
| UN ACH reject/return reasons | reason | e.g. InsufficientFunds, AccountClosed, NoAccount, InvalidAccountNumberStructure, UnauthorizedDebitToConsumerAccount, ... (mapped to R codes) |
| UN received ACH | status | Pending, Advanced, Completed, Returned; wasAdvanced flag |

## B6. Recurring and scheduled payments

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| schedule.start_time / start_date | date | M | | First execution | UN, banks |
| schedule.end_time / end_date | date | O | | Last execution | UN |
| schedule.interval | enum | M | UN: Weekly, Monthly; banks: DAILY, WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL | Frequency | UN |
| schedule.day_of_month / day_of_week | int / enum | C | 1-28, -1..-5 (from month end) | Anchor day | UN |
| schedule.total_number_of_payments | int | O | | Count-terminated schedules | UN |
| schedule.next_scheduled_action | date | RO | | Next run | UN |
| business_day_convention | enum | O | FOLLOWING, PRECEDING, MODIFIED_FOLLOWING | Holiday handling | ISO/banks |
| amount_type | enum | M | FIXED, VARIABLE (sweep to target balance), FULL_BALANCE, PERCENTAGE | Standing order vs sweep | banks |
| status | enum | M | UN: Active, Completed, Disabled | Lifecycle | UN |
| number_of_payments (executed) | int | RO | | Counter | UN |
| on-failure policy (retry count, retry delay, skip, suspend after N) | object | O | | Failed run handling | banks |
| payment template (counterparty, rail, reference) | object | M | | What to send | UN recurringCreditAchPayment / recurringCreditBookPayment / recurringDebitAchPayment |
| FPS SOP / UK standing order | | | Standing orders over FPS | | UK |
| first / last payment amount override | amount | O | | Pro-rata first payments | banks |
| ISO 20022 standing order (camt.069/070/071, camt.024 modify) | | O | | Standing order maintenance | ISO |

## B7. Fees and FX on payments

| Field | Type | Req | Enums | Purpose | Providers |
|---|---|---|---|---|---|
| fee lines[] {type, amount, currency, bearer, agent, tax} | array | O | type: RAIL, CORRESPONDENT, RECEIVING_BANK, FX_MARGIN, SERVICE, EXPEDITE, RETURN, INVESTIGATION | Breakdown | ISO ChrgsInf, MT103 71F/71G |
| charge_bearer | enum | M | DEBT, CRED, SHAR, SLEV | | ISO |
| fx rate {rate, base/target, exponent, value} | object | RO | | Applied rate | MT foreign_exchange_rate |
| fx indicator | enum | C | fixed_to_variable, variable_to_fixed | Which side fixed | MT |
| fx contract / deal id | string | O | | Pre-booked rate | MT, ours (fx_quote_id) |
| rate type | enum | O | AGRD (agreed), SALE, SPOT | ISO RateTp | ISO XchgRateInf |
| total cost disclosure (EU CBPR 2021/1230: currency conversion charges as % markup over ECB rate) | | M (EU card & credit transfers) | | Pre-transaction and post-transaction disclosure | EU Reg 2019/518 |
| remittance transfer disclosure (US Reg E Subpart B 1005.30-36): pre-payment disclosure, receipt with exchange rate, fees, taxes, amount received, date available, cancellation right 30 min, error resolution 180 days | | M (US consumer remittances) | | | CFPB |
| return fee / NOC fee | amount | O | | Charged on returns | MT, UN |
| expedite fee (same-day ACH, wire) | amount | O | | | banks |
| fx gain/loss posting on return (re-conversion) | amount | RO | | Returned cross-currency payments | banks |

## B8. ISO 20022 key messages and fields

| Message | Purpose | Key fields |
|---|---|---|
| pain.001 | Customer credit transfer initiation | GrpHdr {MsgId, CreDtTm, NbOfTxs, CtrlSum, InitgPty}; PmtInf {PmtInfId, PmtMtd TRF/CHK/TRA, BtchBookg, ReqdExctnDt, Dbtr, DbtrAcct, DbtrAgt, UltmtDbtr, ChrgBr, PmtTpInf}; CdtTrfTxInf {PmtId {InstrId, EndToEndId, UETR}, Amt {InstdAmt / EqvtAmt}, XchgRateInf, ChrgBr, CdtrAgt, Cdtr, CdtrAcct, UltmtCdtr, InstrForCdtrAgt, Purp, RgltryRptg, Tax, RltdRmtInf, RmtInf} |
| pain.002 | Customer payment status report | OrgnlGrpInfAndSts {GrpSts}, OrgnlPmtInfAndSts, TxInfAndSts {OrgnlEndToEndId, TxSts, StsRsnInf {Rsn/Cd, AddtlInf}} |
| pain.007 | Customer payment reversal | Reversal of a DD by creditor |
| pain.008 | Customer direct debit initiation | MndtRltdInf {MndtId, DtOfSgntr, AmdmntInd, AmdmntInfDtls, ElctrncSgntr}, CdtrSchmeId, SeqTp, ReqdColltnDt |
| pain.009-012 | Mandate initiation / amendment / cancellation / acceptance | Mandate lifecycle |
| pain.013 / pain.014 | Creditor payment activation request / status report | Request-to-pay |
| pacs.008 | FI-to-FI customer credit transfer | GrpHdr {MsgId, CreDtTm, NbOfTxs, SttlmInf {SttlmMtd, ClrSys}}; CdtTrfTxInf {PmtId {InstrId, EndToEndId, TxId, UETR, ClrSysRef}, PmtTpInf {InstrPrty, SvcLvl, LclInstrm, CtgyPurp}, IntrBkSttlmAmt, IntrBkSttlmDt, SttlmPrty, AccptncDtTm (instant), InstdAmt, XchgRate, ChrgBr, ChrgsInf[], PrvsInstgAgt1-3, InstgAgt, InstdAgt, IntrmyAgt1-3 (+Acct), UltmtDbtr, InitgPty, Dbtr, DbtrAcct, DbtrAgt, CdtrAgt, Cdtr, CdtrAcct, UltmtCdtr, InstrForCdtrAgt, InstrForNxtAgt, Purp, RgltryRptg, RltdRmtInf, RmtInf} |
| pacs.009 (+COV) | FI credit transfer / cover | Bank-to-bank, cover with underlying customer credit transfer |
| pacs.002 | FI-to-FI payment status report | TxInfAndSts {OrgnlInstrId, OrgnlEndToEndId, OrgnlTxId, OrgnlUETR, TxSts, StsRsnInf, AccptncDtTm, ClrSysRef, OrgnlTxRef} |
| pacs.003 | FI-to-FI customer direct debit | Collection |
| pacs.004 | Payment return | RtrId, OrgnlGrpInf, OrgnlInstrId, OrgnlEndToEndId, OrgnlTxId, OrgnlUETR, OrgnlIntrBkSttlmAmt, RtrdIntrBkSttlmAmt, RtrdInstdAmt, ChrgBr, ChrgsInf, RtrRsnInf {Orgtr, Rsn/Cd, AddtlInf}, OrgnlTxRef |
| pacs.007 | FI-to-FI payment reversal | DD reversal |
| pacs.028 | FI-to-FI payment status request | Instant status inquiry |
| camt.056 | FI-to-FI payment cancellation request | Assgnmt {Id, Assgnr, Assgne, CreDtTm}, Case {Id, Cretr}, Undrlyg/TxInf {CxlId, OrgnlInstrId, OrgnlEndToEndId, OrgnlTxId, OrgnlUETR, OrgnlIntrBkSttlmAmt, CxlRsnInf {Orgtr, Rsn/Cd, AddtlInf}} |
| camt.029 | Resolution of investigation | Sts {Conf: ACCR/RJCR/PDCR/CNCL...}, CxlDtls/TxInfAndSts {CxlStsId, OrgnlEndToEndId, TxCxlSts, CxlStsRsnInf} |
| camt.027 / camt.087 / camt.110 / camt.111 | Claim non-receipt / request to modify payment / investigation request / response | Exceptions & investigations |
| camt.052 / camt.053 / camt.054 | Intraday report / statement / debit-credit notification | Account reporting and reconciliation (Ntry {Amt, CdtDbtInd, Sts, BookgDt, ValDt, AcctSvcrRef, BkTxCd, NtryDtls/TxDtls/Refs {EndToEndId, UETR, InstrId}}) |
| camt.026 / camt.028 | Unable to apply / additional payment information | RfI |

## B9. Other payment objects

### B9.1 Expected payments and reconciliation (Modern Treasury)

| Field | Type | Req | Enums | Purpose |
|---|---|---|---|---|
| amount_upper_bound / amount_lower_bound | int | M | | Amount tolerance |
| direction / type / currency | enum | M | credit/debit; rail types | |
| internal_account_id | uuid | M | | Receiving account |
| date_upper_bound / date_lower_bound | date | O | | Date tolerance |
| counterparty_id | uuid | O | | |
| description / statement_descriptor / remittance_information | string | O | | Match hints |
| reconciliation_groups / reconciliation_filters / reconciliation_rule_variables | object | O | | Custom match rules |
| reconciliation_method | enum | RO | automatic, manual | |
| status | enum | RO | archived, partially_reconciled, reconciled, unreconciled | |
| transaction_id / transaction_line_item_id / ledger_transaction_id | uuid | RO | | Match result |
| metadata | map | O | | |

### B9.2 Virtual accounts

| Field | Type | Req | Purpose |
|---|---|---|---|
| name / description | string | M | |
| internal_account_id | uuid | M | Physical account it routes into |
| counterparty_id | uuid | O | Payer it is dedicated to (auto-attribution) |
| account_details[] / routing_details[] | array | RO | Unique account number(s) per virtual account |
| debit_ledger_account_id / credit_ledger_account_id / ledger_account_id | uuid | O | Sub-ledger postings |
| metadata | map | O | |
| incoming_payment_detail.virtual_account_id | uuid | RO | Attribution of inbound payments |

### B9.3 Incoming payment detail / received credits and debits

| Field | Type | Enums | Providers |
|---|---|---|---|
| type | enum | ach, book, check, eft, interac, rtp, sepa, signet, wire | MT |
| direction / status | enum | credit/debit; completed, pending, returned | MT |
| originating_account_number / originating_routing_number(_type) | string | | MT |
| as_of_date / vendor_id / virtual_account_id / data (raw) | | | MT |
| initiating_payment_method_details {type: balance, financial_account, issuing_card, stripe, us_bank_account; billing_details; us_bank_account {bank_name, last4, routing_number}} | object | | ST |
| linked_flows {credit_reversal, issuing_authorization, issuing_transaction, source_flow, source_flow_type / debit_reversal, inbound_transfer, payout} | object | | ST |
| reversal_details {deadline, restricted_reason} | object | | ST |
| inbound ACH debit decision (accept / return with R-code), debit blocks & filters (ACH positive pay by company id, max amount) | | | banks, UN |

### B9.4 Request to Pay (SEPA SRTP, RTP/FedNow RfP, UPI collect, PIX cobrança)

| Field | Req | Notes |
|---|---|---|
| rtp_id / end_to_end_id | M | Links RfP to the resulting credit transfer |
| payee (creditor) name, account, agent, creditor reference | M | |
| payer (debtor) identifier: alias, IBAN, or account + routing | M | |
| amount (or zero / open), currency, amount modification allowed, partial payment allowed | M/O | |
| requested execution date, expiry date-time | M | |
| payment methods accepted (SCT, SCT Inst, card) | O | SRTP |
| remittance / invoice presentment (document URL) | O | |
| early payment discount / penalty | O | |
| status | M | Sent, Received, Accepted (ACCP), Rejected (RJCT with reason), Expired, Cancelled, Paid; SRTP R-messages: cancellation request, request for status update |
| rejection reasons | | AC04, AM09, CUST, NARR, MD07, ... (pain.014 StsRsnInf) |

### B9.5 EMVCo merchant-presented QR (tags)

| Tag | Name | Notes |
|---|---|---|
| 00 | Payload format indicator | "01" |
| 01 | Point of initiation method | 11 static, 12 dynamic |
| 02-03 / 04-05 / 06-25 | Visa / Mastercard / other network PANs | Card-scheme merchant ids |
| 26-51 | Merchant account information templates | Sub-tag 00 globally unique id (GUID / reverse domain, e.g. br.gov.bcb.pix), 01+ scheme data (PIX key, UPI VPA, PayNow proxy) |
| 52 | Merchant category code | |
| 53 | Transaction currency | ISO 4217 numeric |
| 54 | Transaction amount | Dynamic QR |
| 55 | Tip or convenience indicator | 01 prompt for tip, 02 fixed fee, 03 percentage fee |
| 56 / 57 | Convenience fee fixed / percentage | |
| 58 | Country code | |
| 59 | Merchant name (25) | |
| 60 | Merchant city (15) | |
| 61 | Postal code | |
| 62 | Additional data field template | 01 bill number, 02 mobile number, 03 store label, 04 loyalty number, 05 reference label, 06 customer label, 07 terminal label, 08 purpose of transaction, 09 additional consumer data request (A address, M mobile, E email), 10 merchant tax id, 11 merchant channel |
| 63 | CRC (CRC-16/CCITT-FALSE) | Last field |
| 64 | Merchant information, language template | Alternate-language name/city |
| 65-79 / 80-99 | RFU for EMVCo / unreserved templates | |

### B9.6 Payee verification (CoP / VoP / name enquiry)

| Field | Req | Enums | Source |
|---|---|---|---|
| requested name, account identifier, account type (personal/business) | M | | UK CoP, EU VoP |
| secondary reference (roll number) | C | | UK CoP |
| result code | M | UK: MATCH, ANNM, MBAM, BANM, PANM, BAMM, PAMM, AC01, IVCR, ACNS, OPTO, CASS, SCNS; EU VoP: MTCH, CMTC, NMTC, NOAP | S25, S26 |
| suggested / actual name (close match) | C | | both |
| responder PSP, request id, timestamp | M | | both |
| customer proceed decision after warning (liability evidence) | M | PROCEEDED, CANCELLED, EDITED | EU IPR Art. 5c, UK PSR |
| bulk VoP (opt-out for bulk business payments) | O | | EU IPR |

## B10. Payment status lifecycle (cross-provider mapping)

| Meridian (ours) | ISO 20022 | MT payment order | Stripe Treasury outbound | Unit ACH | Notes |
|---|---|---|---|---|---|
| INITIATED | RCVD | (created) | | | |
| SCREENING / ON_HOLD | BLCK / PDNG | held | | PendingReview | Compliance/fraud hold |
| PENDING_APPROVAL | | needs_approval | | | |
| APPROVED | ACTC | approved | | Pending | |
| (missing) SCHEDULED | PDNG | approved + process_after/effective_date | | | Future-dated |
| SUBMITTED | ACSP | processing / sent | processing | Clearing / Sent | MT distinguishes processing (at bank) vs sent (on rail) |
| ACCEPTED | ACCP / ACSP | sent | | Sent | |
| SETTLED | ACSC / ACCC | completed | posted | Sent (+settlementDate) | |
| REJECTED | RJCT | failed / denied | failed | Rejected | MT "denied" = approval denied |
| RETURNED | (pacs.004) | returned | returned | Returned | |
| (missing) REVERSED | (pacs.007 / Nacha reversal) | reversed | | | |
| FAILED | RJCT | failed | failed | | |
| CANCELLED | CNCL (camt.029) | cancelled | canceled | Canceled | |
| IN_REPAIR | PDNG | | | | |
| (missing) EXPIRED | | (expires_at) | | | |
| (missing) PARTIALLY_SETTLED / PARTIALLY_RETURNED | PART | | | | Partial returns |

---

# PART C: GAP MATRIX AGAINST THE MERIDIAN SPEC

How to read this
- **Kind**: F = column/field, E = enum value(s), T = new table, A = API/action, R = rule/behaviour.
- **Priority**:
  - Must = needed for scheme/regulatory compliance or core issuer/processor parity.
  - Should = expected by fintech clients (Marqeta/Stripe/Lithic parity).
  - Could = differentiator or market-specific.
- **Target** is the existing table, or a proposed new one marked `(new)`.
- Items the spec already covers are not listed. For example, PAN vault, JIT funding log, stand-in replay, partial approval flag, FX markup, ARN/RRN matching, provisional credit, camt.056/029, CoP/VoP base table, EMVCo QR base fields and bulk pain.001 are all covered.

## C1. Cards gaps

### C1.1 Program / product configuration

| # | Kind | Field / enum / table / API | Priority | Target | Notes (source) |
|---|---|---|---|---|---|
| 1 | F | reloadability enum (NON_RELOADABLE, RELOADABLE, SINGLE_USE_VIRTUAL) | Should | card_product | Prepaid/gift lifecycle (S1) |
| 2 | F | activate_upon_issue bool | Should | card_product | Physical cards that skip activation (S1) |
| 3 | F | expiration_offset {unit (YEARS..MINUTES), value} + update_expiration_upon_activation | Should | card_product | Spec only has expiry_years. Short-lived virtual cards need finer units (S1) |
| 4 | F | card_service_code n3 | Must | card_product | Needed in bureau perso file and chip profile (S1) |
| 5 | F | payment_instrument enum (PHYSICAL_MSR, PHYSICAL_ICC, PHYSICAL_CONTACTLESS, PHYSICAL_COMBO, VIRTUAL_PAN) | Should | card_product | Spec has form_factors only (S1) |
| 6 | F | perso_type enum (EMBOSS, LASER, FLAT) | Should | card_product | (S1) |
| 7 | F | name_line_2 / name_line_3 rules, uppercase_name_lines | Must | card_product / card | Business cards need a company line (S1, S10 second_line) |
| 8 | F | carrier template {template_id, logo, message_text}, card/carrier images, package_id | Should | card_product | Bureau personalisation (S1, S16) |
| 9 | F | bulk_ship bool + bulk shipping address | Could | card_product / card_order | Corporate bulk issuance (S1, S10) |
| 10 | F | enable_offline_pin bool | Should | card_product | EMV chip profile (S1) |
| 11 | F | brand_variant / scheme product id (e.g. Visa product ID, MC product code MDS/MCS) | Must | card_program | Drives interchange, wallet eligibility, BIN table (S16) |
| 12 | F | regulated_interchange (Durbin) bool | Must (US) | card_program | Interchange cap & clearing validation (Reg II) |
| 13 | F | always_require_pin / always_require_icc / allow_chip_fallback | Must | card_program | Authorisation policy (S1) |
| 14 | F | require_cvv_for_cnp bool | Must | card_program | CNP policy (S1) |
| 15 | F | allow_quasi_cash + quasi_cash_exempt_merchant_group | Should | card_program | (S1) |
| 16 | F | auth_control_default_behavior enum (ALLOW, DENY) | Should | card_program | Default-deny programs, e.g. expense cards (S1) |
| 17 | F | accepted_countries list / OFAC-country decline token | Must | card_program | Sanctioned-country decline. default_controls exists but there is no first-class OFAC token (S1) |
| 18 | F | avs_policy {validate, decline_on_postal_mismatch, decline_on_address_mismatch} | Should | card_program | (S1) |
| 19 | F | sca_limits {contactless_txn_limit, contactless_cumulative_limit, contactless_count_limit, lvp_txn_limit, lvp_cumulative_limit, lvp_count_limit, tra_exemption_limit, issuer_tra_enabled, cavv_amount_validation, cavv_amount_tolerance_pct} | Must (EEA/UK) | card_program | PSD2 RTS Art. 11/16/18 (S1) |
| 20 | F | network_load_allowed (Visa ReadyLink / MC rePower) | Could | card_program | Prepaid cash loads (S1) |
| 21 | F | jit refunds_destination enum (GATEWAY, ACCOUNT, WATERFALL) + always_fund | Should | card_program | Where refunds land under JIT (S1) |
| 22 | F | jit_on_timeout enum (APPROVE_STAND_IN, DECLINE, USE_BALANCE) | Should | card_program | stand_in_enabled is bool only (S1, S2) |
| 23 | F | overdraft_destination enum for force posts | Should | card_program | (S1) |
| 24 | F | mcc_hold_rules (hold increase %/amount, hold_expiration_days by MCC group; AFD min hold, AFD preauth expiration hours) | Must | card_program / card_mcc_group (new) | Hotels, car rental and fuel need different hold validity and amounts. auth_hold_days is global (S1, S5) |
| 25 | F | wallet provisioning controls {in_app, manual_entry, web_push, wallet_cof} each {enabled, address_verification} | Must | card_program | Per-path tokenisation policy (S1) |
| 26 | F | wallet card_art_id (Visa profileID / MC ProductConfigID) + card metadata (issuer name, contact phone/email/web, T&C URL, privacy URL, app URL) | Must | card_product | Required by VTS/MDES onboarding (S1, A6) |
| 27 | F | dwt_use_card_status_during_auth bool | Should | card_program | (S1) |
| 28 | F | notification_language / preferred locale | Could | card_program | 3DS/wallet messaging (S1) |
| 29 | F | selective_auth (merchant-name matching mode, sensitivity) | Could | card_program | Restricted/benefit cards (S1) |
| 30 | T | card_bin_range (new): {bin, range_start, range_end, scheme product id, funding type, country, regulated flag, tokenization_enabled, card_program_id} | Should | card_bin_range (new) | Programs with several account ranges. Today the range is a single column on card_program |
| 31 | E | CardFundingModel + CHARGE, DEFERRED_DEBIT | Could | enum | Charge cards / deferred debit (S17) |
| 32 | E | CardScheme + MAESTRO, INTERLINK, PULSE, STAR, NYCE, ACCEL | Should | enum | US debit networks (Durbin dual routing) & MC Maestro (S8) |
| 33 | F | debit_network_routing {secondary_network, routing_rule} | Must (US debit) | card_program | Reg II requires 2 unaffiliated networks, including for CNP since 2023 |

### C1.2 Card and cardholder

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 34 | T | card_cardholder (new) or extend party: {billing_address, email, phone, preferred_locale, status (ACTIVE, INACTIVE, BLOCKED), card_terms_acceptance {date, ip, user_agent}, requirements past_due[]} | Must | card_cardholder (new) | AVS, 3DS OTP and wallet eligibility need a verified billing address, email and phone. Terms acceptance is evidence (S11) |
| 35 | F | acting_user_id (employee) on business cards | Should | card | Business account owned by the company, card used by an employee (S7) |
| 36 | F | card_type enum: STANDARD, SINGLE_USE, MERCHANT_LOCKED, CATEGORY_LOCKED | Should | card | Lithic SINGLE_USE / MERCHANT_LOCKED (S15) |
| 37 | F | locked_merchant_id / auto-lock-on-first-use | Should | card | MERCHANT_LOCKED semantics (S15) |
| 38 | F | lifecycle_controls.cancel_after_payment_count | Should | card | Single-use virtual cards (S10) |
| 39 | F | status_reason_code enum (LOST, STOLEN, COMPROMISED, DAMAGED, END_USER_REQUEST, ISSUER_REQUEST, SUSPICIOUS_ACTIVITY, INTERNAL_REVIEW, EXPIRED, UNDELIVERABLE, FRAUD, ACCOUNT_CLOSED, OTHER) | Must | card, card_status_history | Free-text status_reason only. Needs a machine code for reporting and token sync (S15, S16) |
| 40 | E | CardStatus + PENDING_FULFILLMENT, SUSPENDED (issuer, distinct from cardholder FROZEN) | Should | enum | Issuer-initiated vs cardholder freeze (S15, S7) |
| 41 | F | replaced_by_card_id | Should | card | Only the replaces_card_id direction exists (S10) |
| 42 | F | pin_status enum (NOT_SET, OK, BLOCKED) | Should | card | Derive from pin_set + pin_try_count (S15) |
| 43 | F | spend_limit + spend_limit_duration shortcut on card | Could | card | Simple-API parity. card_spend_limit covers it (S15) |
| 44 | F | name_line_2 (company / second line) | Must | card | Business cards (S10) |
| 45 | F | personalization_design_id per card | Could | card | Card-level art override (S10) |
| 46 | F | digital_card_art_id per card | Could | card | (S15) |
| 47 | F | expiration_time ts (exact) | Should | card | Minute-level virtual card expiry (S7) |
| 48 | F | wallet_eligibility {apple_pay, google_pay: eligible, ineligible_reason} | Should | card (derived) | (S10) |
| 49 | F | fraud_warning {type, started_at} | Could | card | Card-testing exposure (S10) |
| 50 | F | fulfillment_status separate from status (ISSUED, ORDERED, REORDERED, REJECTED, SHIPPED, DELIVERED, DIGITALLY_PRESENTED) | Should | card | Partially covered by card_order (S7) |
| 51 | F | shipping.address_validation {mode, result, normalized_address} | Should | card_order | Avoid undeliverable cards (S10) |
| 52 | F | shipping.require_signature, customs.eori_number, eta, tracking_url | Should | card_order | (S10) |
| 53 | E | CardShippingMethod + PRIORITY, INTERNATIONAL, OVERNIGHT, TWO_DAY | Could | enum | (S1) |
| 54 | E | CardReplacementReason + COMPROMISED, FULFILLMENT_ERROR, PRODUCT_CHANGE | Should | enum | (S10) |
| 55 | A | POST /cards/{id}/pin/reveal (PIN view via vault, audit logged) | Should | card-vault / cards | CardPinEventType has VIEW but no API |
| 56 | A | POST /cards/{id}/ephemeral-key or display-token for PCI-safe iframe/SDK reveal | Must | card-vault | Reveal to cardholder apps without PAN passing through the client backend (S10 ephemeral keys, S15 hostname/embed) |
| 57 | A | GET /cards/{id}/transactions (unified posted + pending view) | Should | card_authorization + clearing_record | Stripe/Marqeta expose a unified transaction model (S3, S13) |
| 58 | A | POST /cards/{id}/convert-to-physical already exists as order-physical. Add POST /cards/{id}/reissue-pin-mailer | Could | cards | (S16 pinMailer) |
| 59 | T | card_account_updater (new): VAU/ABU submissions {card_id, old_pan_token, new_pan_token or new_expiry, reason (EXPIRY_UPDATE, PAN_UPDATE, CLOSED, CONTACT_CARDHOLDER), submitted_at, scheme_ack} | Must | card_account_updater (new) | Visa Account Updater / MC ABU keep card-on-file merchants working after renewal or replacement |

### C1.3 Controls and velocity

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 60 | T | card_mcc_group (new) {code, name, mccs[], hold_increase_type, hold_increase_value, hold_expiration_days} | Must | card_mcc_group (new) | Named reusable MCC sets (S5) |
| 61 | T | card_merchant_group (new) {name, merchant_ids[], descriptor_patterns[]} | Should | card_merchant_group (new) | (S5) |
| 62 | E | CardControlType + MCC_GROUP_ALLOW, MCC_GROUP_DENY, MERCHANT_GROUP_ALLOW/DENY, ENTRY_MODE_ALLOW/DENY, WALLET_ALLOW/DENY, TOKEN_REQUESTOR_ALLOW/DENY, QUASI_CASH_DENY, CASHBACK_DENY, RECURRING_DENY, INTERNATIONAL_DENY, CARD_PRESENT_REQUIRED, CARDHOLDER_PRESENT_REQUIRED, PIN_REQUIRED, THREEDS_REQUIRED, DESCRIPTOR_MATCH, COUNT_MAX | Must | enum + card_control | Parity with Adyen ruleRestrictions / Lithic attributes (S15, S16) |
| 63 | F | card_spend_limit include flags: include_purchases, include_withdrawals (cash/ATM), include_transfers (OCT/AFT), include_cashback, include_credits (net of refunds), approvals_only | Must | card_spend_limit | Which transaction types consume the limit (S4) |
| 64 | F | card_spend_limit merchant_scope {mccs[], mcc_group_id, merchant_ids[]} | Must | card_spend_limit | Per-category limits, e.g. 500/month on travel (S4, S14 categories) |
| 65 | E | CardLimitPeriod + ROLLING_HOURS (window_seconds), SLIDING, CALENDAR vs trailing semantics | Should | enum + card_spend_limit (window_seconds) | Adyen rolling/sliding, Lithic custom windows (S15, S16) |
| 66 | F | card_spend_limit / control scope: customer (cardholder) and business account level | Should | card_spend_limit, card_control | Cardholder-wide and company-wide budgets (S11, S16 entityKey) |
| 67 | F | card_authorization_rule mode enum (ACTIVE, SHADOW) + shadow results log | Should | card_authorization_rule | Safe rollout and backtests (S15) |
| 68 | A | POST /card-authorization-rules/{id}/backtest {from, to} → hit rate, declined amount | Should | card_authorization_rule | (S15) |
| 69 | E | CardAuthRuleAction + SCORE (add risk points), ENFORCE_SCA | Should | enum | Adyen outcomeType scoreBased / enforceSCA (S16) |
| 70 | F | card_control exemption_merchant_ids[] | Could | card_control | (S5 auth_control_exemption_mids) |
| 71 | F | card_control token_requestor / wallet dimension | Should | card_control | Block wallet X or COF tokens (S16) |
| 72 | F | rule condition attributes: network_risk_score, liability_shift, wallet_type, token_requestor_id, avs_result, cvv_result, card_age_days, merchant_descriptor, txn counts 15m/1h/24h | Should | card_authorization_rule (CEL vocabulary) | Spec CEL vars are limited to 11 fields (S15) |

### C1.4 Authorisation

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 73 | E | CardAuthMessageType + ORIGINAL_CREDIT (OCT), ACCOUNT_FUNDING (AFT), CASH_ADVANCE, ATM_WITHDRAWAL, FORCE_POST, PIN_CHANGE (ATM), TOKEN_ACTIVATION | Must | enum | OCT/AFT are different JIT methods with different risk (S2, S3, S8) |
| 74 | F | network enum (VISA, MASTERCARD, ...) + subnetwork (VISANET, INTERLINK, PLUS, MAESTRO, CIRRUS, PULSE, STAR ...) | Must | card_authorization | Single- vs dual-message handling and fees (S3, S8) |
| 75 | F | transmission_datetime (DE7), local_txn_datetime (DE12/13), settlement_date (DE15) | Must | card_authorization | Reconciliation and reversal matching (S17) |
| 76 | F | card_sequence_number (DE23) | Should | card_authorization | EMV / PSN (S17) |
| 77 | F | pos_condition_code (DE25) + pos_environment (recurring, instalment, COF) | Must | card_authorization | MIT/CIT classification (S17, Visa 126.13) |
| 78 | F | stored_credential {initiator: CIT/MIT, type: UNSCHEDULED, RECURRING, INSTALLMENT, RESUBMISSION, DELAYED_CHARGE, REAUTH, NO_SHOW, INCREMENTAL, ESTIMATED; original_network_transaction_id} | Must | card_authorization | Visa/MC stored-credential framework, also needed for SCA out-of-scope (S8 transaction_initiator_code) |
| 79 | F | terminal attributes {attended, terminal_type, pin_capability, card_retention_capable, operator, on_premise} | Should | card_authorization | (S15 pos.terminal) |
| 80 | F | entry decomposition {card_presence, cardholder_presence enum (PRESENT, NOT_PRESENT, MAIL_ORDER, TELEPHONE_ORDER, RECURRING, INSTALLMENT, ELECTRONIC_ORDER)} | Should | card_authorization | bools only today (S15) |
| 81 | E | CardPosEntryMode + CONTACTLESS_MAGSTRIPE, BARCODE_QR, OCR, SECURE_CARDLESS, CHIP_FALLBACK_MAGSTRIPE (split FALLBACK) | Could | enum | (S15, S17) |
| 82 | F | merchant_postal_code, merchant_state, merchant_street, merchant_url, merchant_descriptor_raw (DE43) | Should | card_authorization | Disputes and enrichment (S12 merchant_data) |
| 83 | F | acquirer_country (DE19), forwarding_institution_id (DE33) | Should | card_authorization | Cross-border detection by acquirer (S17) |
| 84 | F | cashback_amount, surcharge/atm_fee (DE28), additional_amounts (DE54) | Must | card_authorization | ATM surcharge & cashback posting (S12 amount_details, S8 amounts) |
| 85 | F | original_data_elements (DE90) + replacement_amount (DE95) | Must | card_authorization | Exact reversal matching (S17) |
| 86 | F | emv_data {arqc_result, atc, tvr, cvr, iad, arpc, issuer_scripts} | Must | card_authorization | Chip cryptogram verification is required to approve chip transactions safely (S8 validation_results.arqc) |
| 87 | F | cvv_type verified (CVV1 mag, iCVV chip, dCVV contactless, CVV2), dcvv_result | Should | card_authorization | (S8 cvv1/cvv2/cvv3) |
| 88 | F | token fields in auth: token_requestor_id, token_type, wallet (APPLE_PAY, GOOGLE_PAY, SAMSUNG_PAY), par, tavv_result | Must | card_authorization | Only card_token_id exists (S12 wallet/token, S8 tar_info) |
| 89 | F | cavv_result (CAVV/AAV verification code), sca_exemption_claimed {type, claimed_by}, liability_shift | Must | card_authorization | Chargeback liability depends on this (S12, S15) |
| 90 | F | avs_input {postal_code, address_line} + avs_result enum (MATCH, ZIP_ONLY, ADDRESS_ONLY, NO_MATCH, NOT_PROVIDED, UNAVAILABLE) | Should | card_authorization | avs_result is a free 1-char field (S12, S8) |
| 91 | F | network_risk_score + network_risk_code | Should | card_authorization | Visa VAA / MC Decision Intelligence input to rules (S12, S8) |
| 92 | F | business_application_id (BAI) + sender data {name, account, address, fund_source} for OCT | Must | card_authorization | Incoming push-to-card credits (S8 bai, payment_info) |
| 93 | F | merchant_advice_code (MC MAC 01/02/03/21) and Visa decline category in response | Should | card_authorization | Retry guidance to merchants (S8) |
| 94 | E | CardDeclineReason + SCA_REQUIRED (soft decline 1A/65), PIN_REQUIRED (soft), CARDHOLDER_INACTIVE, ACCOUNT_CLOSED, DUPLICATE_TRANSACTION, INVALID_MERCHANT, INVALID_AMOUNT, CRYPTOGRAM_FAILED, TOKEN_INACTIVE, CVV_BLOCKED, AVS_MISMATCH, STOP_PAYMENT_ORDER (Visa R0/R1/R3), COUNT_LIMIT_EXCEEDED, QUASI_CASH_BLOCKED, OFAC_COUNTRY | Must | enum | (S2 decline_reason list, S12 request_history.reason) |
| 95 | F | response balance (available/ledger) for balance inquiry (DE54) | Must | card_authorization | BALANCE_INQUIRY is in the message types but the response has no balance (S2 balances, S8) |
| 96 | F | estimated vs final authorisation indicator + auth validity (preauth 30 days MC, 7 days Visa default) | Must | card_authorization | expires_at should follow the scheme rule per auth type and MCC, not a flat auth_hold_days |
| 97 | F | anticipated_amount (AFD) + fuel/fleet data {driver, vehicle, odometer, fuel type/qty/unit price} | Could | card_authorization | Fleet programs (S12 fleet/fuel, S8 fleet_info) |
| 98 | F | network_stand_in advice {stip_reason, stip_decision, is_stip_denial} | Must | card_authorization | Scheme STIP advices (0120) must be posted. Spec stand-in covers only internal stand-in (S8 stip) |
| 99 | F | decision_trace[] (request_history: every request/incremental with reason codes and rule ids) | Should | card_authorization | (S12 request_history, S8 response_code_objects) |
| 100 | F | fraud_challenge {channel SMS/push, status, verified} + verified_by_fraud_challenge | Could | card_authorization | Out-of-band confirmation of a declined auth, then retry (S12) |
| 101 | F | iias / healthcare amounts | Could | card_authorization | HSA/FSA cards (S8) |
| 102 | F | jit request: method (normalised), tags, memo, original_jit_request_id, incremental ids, address_verification block | Should | card_jit_funding_request | (S2) |
| 103 | F | jit response decline_reason enum (S2 list) | Must | card_jit_funding_request | The client JIT response has no structured decline reason today |
| 104 | A | POST /card-authorizations/simulate-oct, simulate-balance-inquiry, simulate-atm, simulate-advice (network STIP), simulate-token-auth | Should | card-authorizations | Sandbox parity (Lithic/Marqeta simulate) |
| 105 | R | Multi-clearing (one auth, many partial presentments with sequence number/count) and over/under-clearing tolerance per MCC (restaurants +20%, etc.) | Must | card_authorization / clearing_record | Spec has cleared_amount but no tolerance rules per MCC |

### C1.5 Clearing and settlement

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 106 | E | ClearingFileFormat + VISA_BASEII (TC records), MC_IPM (ISO 8583 1987 variable, 1014-blocked), VSS_REPORT, MC_SETTLEMENT_REPORT | Must | enum | Only CSV/JSON today, so real scheme files can't be ingested |
| 107 | F | scheme_record_type (Visa TC / MC MTI+function code) | Must | clearing_record | (A5) |
| 108 | F | multiple_clearing_sequence_number / count | Must | clearing_record | Split shipments (A5) |
| 109 | F | central_processing_date, purchase_date | Should | clearing_record | transaction_date only (A5) |
| 110 | F | pos_entry_mode, eci, cardholder_id_method, terminal_capability in clearing | Should | clearing_record | Interchange qualification checks (A5) |
| 111 | F | interchange_fee_program_indicator (Visa FPI) / IRD (MC), reimbursement attribute | Must | clearing_record | interchange_rate_code exists but is too generic. Add scheme-specific fields |
| 112 | F | scheme_fx_fee (Visa ISA / MC cross-border assessment) separate from scheme_fee | Should | clearing_record | Cost analytics (A5) |
| 113 | F | level2/3 purchase data {tax_amount, customer_code, invoice, line_items[]}, travel/lodging/fuel data | Could | clearing_record | Commercial cards (S13 purchase_details) |
| 114 | F | merchant_verification_value, merchant_postal_code, merchant_state | Could | clearing_record | (A5) |
| 115 | F | reversal_indicator / cancel flag | Must | clearing_record | Clearing reversals (TC25/26) vs REVERSAL record_type ambiguity |
| 116 | F | chargeback_reference_number, reason_code, documentation_indicator, member_message_text | Must | clearing_record | For CHARGEBACK/REPRESENTMENT records (A5) |
| 117 | F | settlement_service / SRE / settlement cycle | Should | card_settlement_record | Visa VSS / MC GCMS multiple cycles per day |
| 118 | T | interchange_rate_table (new) {scheme, region, product, MCC set, entry mode, regulated flag, rate_bps, fixed_fee, cap, valid_from} | Should | interchange_rate (new) | Expected-vs-actual interchange validation |
| 119 | T | scheme_fee_schedule (new) | Could | scheme_fee (new) | Cost modelling |
| 120 | T | card_transaction (new, read model): {type (PURCHASE, REFUND, CASH, OCT, AFT, FEE, CHARGEBACK_CREDIT, ...), state (PENDING, POSTED, DECLINED, REVERSED), auth_id, clearing_ids[], amounts, merchant, posted_at} | Should | card_transaction (new) | Client-friendly unified ledger of card activity (S3, S13) |
| 121 | A | POST /card-clearing/files/{id}/acknowledge (outgoing files), GET /card-clearing/settlements/{id}/report | Could | clearing | |
| 122 | R | Force-post handling: presentment with no auth (offline chip, below floor limit) posts and may overdraw; overdraft destination | Must | clearing_record | FORCE_POST match status exists. Add the overdraw behaviour and destination (S1) |

### C1.6 Tokens

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 123 | F | payment_account_reference (PAR) | Must | card + card_token | Links PAN and all tokens (A6) |
| 124 | F | token_type enum (SECURE_ELEMENT, HCE, CARD_ON_FILE, ECOMMERCE, QR) | Must | card_token | device_type exists but token type is missing (A6) |
| 125 | F | pan_reference_id | Should | card_token | (A6) |
| 126 | F | token_assurance_level / id&v method | Should | card_token | (A6) |
| 127 | F | wallet risk detail {reason_codes[], recommendation (APPROVE, REQUIRE_ADDITIONAL_AUTHENTICATION, DECLINE), card_number_source (APP, MANUAL, ON_FILE), name_match, address_verification_result} | Must | card_token | The spec has only two scores (S6, S13) |
| 128 | F | device detail {language, ip_address, location lat/long, phone_number_last4, secure_element_id} | Should | card_token | (S13) |
| 129 | F | activation methods offered [{type, masked_value}] returned to TSP on YELLOW | Must | card_token | MDES/VTS ID&V method list (A6) |
| 130 | E | step_up_method + CALL_CENTER_INBOUND, ISSUER_CALL_OUTBOUND, APP_TO_APP, WEBSITE | Should | card_token | (A6) |
| 131 | F | app_to_app ID&V config (iOS app URL scheme / Android package + action) | Should | card_program | Issuer-app authentication of yellow-path provisioning |
| 132 | A | POST /card-tokens/push-provision split: /apple {certificates[], nonce, nonce_signature} → {activation_data, encrypted_pass_data, ephemeral_public_key}; /google {device_id, wallet_account_id} → {opaque_payment_card} | Must | card_token | The generic device_info json can't carry the wallet-specific cryptographic exchange (S6, S10) |
| 133 | A | POST /card-tokens/network/activation-code (issuer delivers OTP via TSP Activation Code Notification) | Should | card_token | (A6) |
| 134 | A | POST /card-tokens/{id}/update-metadata (push card art/metadata change) | Could | card_token | |
| 135 | E | CardTokenEventType + METADATA_UPDATED, DEVICE_CHANGED, TOKEN_EXPIRY_UPDATED, CRYPTOGRAM_FAILURE | Could | enum | |
| 136 | F | token domain controls (merchant lock / channel) and cardholder-visible COF token management (merchant name, last used) | Could | card_token | Subscription management UX |
| 137 | E | CardTokenRequestor + CLICK_TO_PAY exists; add MICROSOFT, FITBIT/GARMIN (wearables), PAYPAL | Could | enum | |
| 138 | R | Token lifecycle on renewal: send PAN update (new expiry/PAN) to TSP | Must | card-tokenization-service | Event PAN_UPDATED exists, but no outbound call/API for issuer-initiated update |

### C1.7 3DS

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 139 | F | message_category (PA, NPA) | Must | threeds_authentication | (A7) |
| 140 | F | authentication_indicator (01-07) / three_ri_indicator | Must | threeds_authentication | (A7) |
| 141 | F | requestor_challenge_indicator (01-09) incl. exemption requests | Must | threeds_authentication | Merchant exemption request (A7) |
| 142 | F | trans_status (Y, N, U, A, C, D, R, I) raw + trans_status_reason (01-26) | Must | threeds_authentication | The spec's ThreeDsResult enum loses this information. The DS requires the codes (A7) |
| 143 | F | authentication_type (static, dynamic, OOB, decoupled) | Should | threeds_authentication | (A7) |
| 144 | F | acquirer_bin, acquirer_merchant_id | Must | threeds_authentication | Required in AReq / risk (A7) |
| 145 | F | transaction_type (01, 03, 10, 11, 28) | Should | threeds_authentication | (A7) |
| 146 | F | recurring_expiry, recurring_frequency, instalment count | Should | threeds_authentication | (A7) |
| 147 | F | browser_info {ip, accept header, user agent, language, tz, screen} and sdk device_info / sdk_app_id | Must | threeds_authentication | Main inputs to risk scoring (A7) |
| 148 | F | cardholder data matched {bill/ship address, email, phone, addr_match} + acct_info + merchant_risk_indicator | Should | threeds_authentication | Risk signals (A7) |
| 149 | F | whitelist / trust_list status and source | Should | threeds_authentication | Trusted-beneficiary exemption (A7) |
| 150 | F | decoupled request indicator + max time | Should | threeds_authentication | DECOUPLED exists as type but timing fields are missing (A7) |
| 151 | F | challenge_cancel code, interaction_counter, acs_rendering_type | Should | threeds_authentication | (A7) |
| 152 | F | cardholder_info text | Could | threeds_authentication | (A7) |
| 153 | F | message_version-specific message_extension[] | Could | threeds_authentication | Scheme extensions |
| 154 | F | rreq_status {sent_at, results_status} | Must | threeds_authentication | Results request delivery tracking (A7) |
| 155 | F | sca exemption counters per card (lvp_count, lvp_cumulative_amount, contactless_count, contactless_cumulative_amount, last_sca_at) | Must (EEA/UK) | card_sca_counter (new) or card_velocity_counter | PSD2 RTS Art. 11/16. Not present (A7) |
| 156 | E | ThreeDsChallengeType + OOB_BANK_APP (generic OOB), KBA, PASSKEY/SPC | Could | enum | |
| 157 | E | ThreeDsResult + INFORMATIONAL (I), DECOUPLED_PENDING (D) | Should | enum | (A7) |
| 158 | R | Validate CAVV at authorisation and bind auth amount to authenticated amount (tolerance %) | Must | card-authorization-service | (S1 cavv_authentication_amount_validation) |

### C1.8 Disputes

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 159 | T | card_dispute_reason_code (new reference): {scheme, code, category, description, flow (ALLOCATION/COLLABORATION), issuer_time_limit_days, acquirer_response_days, required_evidence[], requires_merchant_contact, requires_card_blocked, min_amount} | Must | card_dispute_reason_code (new) | Deadlines and evidence depend on the reason code. Seed with A8.3/A8.4 |
| 160 | E | CardDisputeStage + DISPUTE_RESPONSE (Visa collaboration), PRE_ARBITRATION_RESPONSE, PRE_COMPLIANCE, COMPLIANCE, SECOND_PRESENTMENT (MC naming) | Should | enum | (A8.2) |
| 161 | F | dispute_flow enum (ALLOCATION, COLLABORATION) for Visa | Should | card_dispute | (S19) |
| 162 | F | network_case_ids {vrol_case_id, mastercom_claim_id, chargeback_reference_number} | Should | card_dispute | Only one scheme_case_id today |
| 163 | F | stage amounts: pre_arbitration_amount, arbitration_amount, filing_fee, arbitration_fee | Should | card_dispute | Arbitration costs (S19, S20) |
| 164 | F | loss_reason enum (S13 list) | Should | card_dispute | Analytics and auto-reject handling |
| 165 | F | normalised dispute reason (canceled, duplicate, fraudulent, merchandise_not_as_described, no_valid_authorization, not_received, service_not_as_described, credit_not_processed, incorrect_amount, atm_cash_not_dispensed, other) | Should | card_dispute | CardDisputeCategory is close. Add INCORRECT_AMOUNT, PAID_BY_OTHER_MEANS, COUNTERFEIT_MERCHANDISE, MISREPRESENTATION, OCT_NOT_ACCEPTED |
| 166 | F | structured evidence: canceled_at, cancellation_reason, cancellation_policy_provided, expected_at, product_type (MERCHANDISE/SERVICE), product_description, return_status, returned_at, received_at, original_transaction_id, merchant_contacted_at, merchant_contact_method | Must | card_dispute (or evidence json) | Scheme questionnaires (VROL/Mastercom) need structured answers (S13) |
| 167 | E | CardDisputeEvidenceType + INVOICE, CANCELLATION_PROOF, RETURN_PROOF, CARD_STATEMENT, CHECK_IMAGE, CASH_RECEIPT, MERCHANT_RESPONSE, AUTHENTICATION_PROOF, COMPELLING_EVIDENCE | Should | enum | (A8.5) |
| 168 | F | Reg E fields: notice_received_at, notice_channel, investigation_due_at (10 BD / 20 BD new account), extended_due_at (45/90 days), provisional_credit_due_at, customer_notices_sent[] | Must (US) | card_dispute | regulatory_deadline is a single date (12 CFR 1005.11) |
| 169 | F | Reg Z fields for credit cards (billing error notice, acknowledgement due 30 days, resolution 2 cycles/90 days, interest/fee suspension) | Must (US credit) | card_dispute | 12 CFR 1026.13 |
| 170 | T | card_fraud_report (new): {card_dispute_id/authorization_id/clearing_record_id, scheme, fraud_type code, report_date, submitted_at, scheme_ack} | Must | card_fraud_report (new) | Visa TC40 (FRAUD reporting) and MC SAFE/FRAUD reporting are mandatory for fraud chargebacks |
| 171 | T | card_dispute_message (new): inbound/outbound scheme messages per stage {direction, stage, message_type, amount, reason_code, payload_ref, due_at} | Should | card_dispute_message (new) | Spec only keeps events. Representments and pre-arb need payload storage |
| 172 | A | POST /card-disputes/{id}/submit-pre-arbitration, /respond-pre-arbitration, /file-arbitration (with fees) | Should | card-disputes | escalate exists but has no stage-specific payload |
| 173 | A | POST /card-disputes/{id}/submit-evidence (bundle evidence + questionnaire) | Should | card-disputes | |
| 174 | A | Pre-dispute: Visa Order Insight / RDR, Ethoca/Verifi alerts intake (POST /card-disputes/pre-dispute-alerts) | Could | card_dispute_alert (new) | Deflect disputes before chargeback |
| 175 | R | Fraud dispute auto-requires card block/reissue (scheme rule: fraud chargebacks on an unblocked card can be auto-rejected) | Must | card-dispute-service | (S13 loss_reason transaction_approved_after_prior_fraud_dispute) |
| 176 | R | Compelling Evidence 3.0 matching (prior undisputed transactions sharing IP/device/address) when merchant representment arrives | Could | card-dispute-service | Visa CE 3.0 |

### C1.9 Fees

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 177 | E | CardFeeType + ATM_BALANCE_INQUIRY, ATM_DECLINE, PIN_RESET, CARD_LOAD, OCT_RECEIVE, DISPUTE_FEE, PAPER_STATEMENT, FOREIGN_ATM | Should | enum | Prepaid fee schedules |
| 178 | F | fee_rule reference (pricing rule applied) | Should | card_fee | Traceability to product pricing |

## C2. Payments gaps

### C2.1 Payment (core order)

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 201 | F | debtor_address (structured PostalAddress24) | Must | payment | Only creditor_address exists. Debtor address is required for cross-border (FATF R16 / EU TFR 2023/1113, CBPR+) and when a SEPA PSP is non-EEA (B1, B2.4) |
| 202 | F | ultimate_debtor {name, id} and ultimate_creditor {name, id} | Must | payment | ISO UltmtDbtr/UltmtCdtr. Payments on behalf of, payroll bureaus, marketplaces (S21, B1) |
| 203 | F | debtor_identification / creditor_identification {lei, bic, org_id, private_id (dob, birth_place, national_id), scheme} | Should | payment | Travel-rule data and CBPR+ (B1) |
| 204 | F | initiating_party {name, id} | Should | payment | pain.001 InitgPty (B8) |
| 205 | F | service_level code (SEPA, URGP, NURG, SDVA, G001..) | Must | payment | ISO PmtTpInf/SvcLvl (B1) |
| 206 | F | local_instrument code (INST, CCD, PPD, CTX, WEB, TEL, IAT, CORE, B2B, ...) | Must | payment | Rail sub-type. ACH SEC code lives here (S21 subtype) |
| 207 | F | instruction_priority (HIGH/NORM) and settlement_priority | Could | payment | Spec PaymentPriority is close |
| 208 | F | settlement_method (INDA, INGA, COVE, CLRG) + clearing_system code | Should | payment | Cover vs serial wires (B8) |
| 209 | F | intermediary_agent_1/2/3 {bic, clearing_id, name, account} | Must | payment | Correspondent routing for USD/EUR wires (MT103 :56A:, B2.3) |
| 210 | F | creditor_agent_name / creditor_agent_address | Should | payment | Non-BIC banks in cross-border (B2.3) |
| 211 | F | instruction_for_creditor_agent / instruction_for_next_agent {code, text} | Should | payment | :23E: / :72: (B2.3) |
| 212 | F | regulatory_reporting json | Should | payment | :77B: / RgltryRptg; needed for some corridors (B1) |
| 213 | F | statement_descriptor (originator-visible text per rail limits) | Must | payment | ACH company entry description, FPS reference (S21) |
| 214 | F | interbank_settlement_date | Should | payment | value_date exists. Add IntrBkSttlmDt separately for instant/batch |
| 215 | F | acceptance_datetime (SCT Inst AccptncDtTm) | Must | payment | 10-second clock evidence under IPR (B2.4) |
| 216 | F | charges[] {amount, currency, agent_bic, type} (deducted by agents) | Should | payment_charge (new) | pacs.008 ChrgsInf / MT103 71F/71G (B7) |
| 217 | F | fx_indicator enum (FIXED_TO_VARIABLE, VARIABLE_TO_FIXED) + exchange_rate_type (AGRD, SPOT, SALE) | Should | payment | (S21, B7) |
| 218 | F | expected_settlement_date / expected_arrival_at | Should | payment | Customer ETA (S22, S23) |
| 219 | F | process_after ts and expires_at ts | Should | payment | Hold-until and auto-cancel windows (S21) |
| 220 | F | nsf_protected bool (no overdraft for this payment) | Could | payment | (S21) |
| 221 | F | reconciliation_status enum (UNRECONCILED, TENTATIVELY_RECONCILED, RECONCILED) | Should | payment | (S21) |
| 222 | F | reference_numbers[] {type, value} (ach_trace, fedwire_imad/omad, swift_mir, rtp_instruction_id, sepa_tx_id, chips_ssn) | Must | payment_reference (new) or payment | A single clearing_system_ref can't hold multiple rail ids (S21) |
| 223 | F | end_user_details {present bool, ip_address} | Should | payment | risk_context covers IP; add an explicit "end-user present" flag (S22) |
| 224 | F | line_items[] {amount, description, accounting_category, metadata} | Could | payment_line_item (new) | (S21) |
| 225 | F | tax / withholding information (ISO Tax) | Could | payment | Tax payments in some countries (B8) |
| 226 | F | fallback_rail (e.g. RTP→ACH) | Should | payment | Routing has fallback rules, but there is no per-payment client override (S21) |
| 227 | F | hosted / regulatory receipt url and Reg E remittance disclosures {pre-payment disclosure id, receipt id, cancellation_deadline (30 min), funds_available_date} | Must (US consumer cross-border) | payment | Reg E Subpart B (B7) |
| 228 | F | EU cost disclosure {currency_conversion_markup_pct_over_ecb, total_charges} | Must (EU) | payment / fx_quote | Reg (EU) 2019/518 (B7) |
| 229 | E | PaymentStatus + SCHEDULED, PROCESSING (at bank, pre-rail), SENT, REVERSED, EXPIRED, PARTIALLY_RETURNED | Should | enum | Needed to map MT/Stripe/Unit states one-to-one (B10) |
| 230 | E | PaymentType + CHECK, CARD_PUSH (OCT), CARD_PULL (AFT), WIRE, ACH, BOOK, REVERSAL | Should | enum | Rail-oriented types (S21, S23) |
| 231 | E | PaymentAccountIdType + CLABE, BSB, PAN (raw push-to-card via token), CRYPTO_ADDRESS | Could | enum | (S21 account_number_type) |
| 232 | E | ChargeBearer: fine. Add an MT103 OUR/BEN/SHA alias mapping table in rail adapters | Could | rail_adapter config | |
| 233 | E | pacs.002 status code enum (ACTC, ACCP, ACSP, ACSC, ACCC, ACWC, ACWP, PDNG, RCVD, RJCT, BLCK) for /status-report | Must | enum PaymentRailStatusCode (new) | Action accepts free string(4) (B5.3) |
| 234 | A | POST /payments/{id}/reverse (Nacha reversal within 5 banking days: duplicate, wrong amount, wrong receiver, wrong date; SEPA reversal) | Must | payment_reversal (new) | Different from return and recall (S21 reversal) |
| 235 | A | POST /payments/{id}/request-status (pacs.028) | Should | payments | Instant-rail status inquiry after timeout (B8) |
| 236 | A | POST /payments/{id}/investigation (camt.027 claim non-receipt, camt.087 request to modify, camt.110) | Should | payment_investigation (new) | Exceptions & investigations (B8) |
| 237 | A | GET /payments/{id}/tracker extended with gpi hop events (agent, status, reason G000-G004, charges, fx per hop) | Should | payment_tracker_event (new) | (B2.3) |
| 238 | T | payment_tracker_event (new) {payment_id, uetr, agent_bic, status, reason (G000-G004), amount, charges, fx_rate, event_at, source (gpi tracker / pacs.002)} | Should | payment_tracker_event (new) | (S31) |
| 239 | A | POST /payments/{id}/stop-payment (ACH debit stop / check stop) | Should | payment_stop (new) | R08 / check stop (B2.8) |

### C2.2 Rail-specific detail tables

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 240 | T | payment_ach_detail (new): sec_code, transaction_code, company_name, company_id, company_entry_description, company_descriptive_date, company_discretionary_data, effective_entry_date, same_day, trace_number, addenda[], individual_id, iat_fields, authorization_evidence {type, timestamp, ip, recording_ref}, prenote | Must | payment_ach_detail (new) | No ACH fields in the spec, yet NACHA is a BulkFileFormat (B2.1) |
| 241 | T | payment_wire_detail (new): business_function_code (CTR, CTP, BTR, DRW...), imad, omad, sender_reference, fi_to_fi_info, drawdown flag | Must | payment_wire_detail (new) | Fedwire (B2.2) |
| 242 | T | payment_card_push_detail (new): direction (OCT/AFT), card_token / pan_token, card_network, business_application_id, ptti (MC), source_of_funds, sender {name, account, address}, recipient name, purpose, fast_funds, network transaction id, approval code, rrn | Should | payment_card_push_detail (new) | RailType CARD_PUSH exists but has no fields (B2.7) |
| 243 | A | POST /card-eligibility (FTAI/PAAI: push/fast-funds/gambling-block/issuer country) | Should | routing-service | Pre-check before OCT (B2.7) |
| 244 | T | check_payment (new): check_number, memo, payee name/address, mailing service, send_date, delivery_status, tracking, signature image ref, void/stop | Could | check_payment (new) | US outbound checks (B2.8) |
| 245 | T | check_deposit (new): front/back image refs, amount, MICR (routing, account, check number, aux on-us), status (AWAITING_IMAGES ... RETURNED), reject_reason, hold schedule {available_at per portion}, duplicate detection fingerprint, endorsement check | Should (US) | check_deposit (new) | Remote deposit capture (B2.8) |
| 246 | T | positive_pay_issue / exception (new) | Could | check_positive_pay (new) | Business check fraud |
| 247 | F | SCT Inst: max-amount per customer (customer-set limit), timeout reason codes | Should | payment / customer prefs | EU IPR lets payers set limits (B2.4) |
| 248 | F | FPS payment_type (SIP, FDP, SOP, RPP) + secondary reference (roll number) | Should | payment | UK (B2.5) |
| 249 | T | bacs_report_item (new): ARUDD / ADDACS / AUDDIS / AWACS / ARUCS / DDIC records with reason codes | Could (UK) | bacs_report_item (new) | (B2.5) |
| 250 | T | app_fraud_claim (new): UK PSR reimbursement {payment_id, claim_date, amount, vulnerable_customer, gross_negligence_assessment, decision, decision_due (5 BD), reimbursed_amount, receiving_psp_share 50%} | Must (UK) | app_fraud_claim (new) | PSR mandatory reimbursement since 2024-10-07 (B2.5) |

### C2.3 Counterparties / payees

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 251 | T | payee_account (new): multiple accounts per payee {account_id_type, account_number, routing_details[] {routing_number, routing_number_type, payment_type}, account_type, currency, is_default} | Should | payee_account (new) | One account per payee today. MT counterparty supports many (S21) |
| 252 | F | account_type (CHECKING, SAVINGS, LOAN, GENERAL_LEDGER, OTHER) | Must | payee, payment | ACH transaction-code derivation (B2.1) |
| 253 | F | routing_number_type enum (ABA, SWIFT, GB_SORT_CODE, IN_IFSC, AU_BSB, CA_CPA, CHIPS, CNAPS, JP_ZENGIN, MX_CLABE ...) | Should | payee | agent_clearing_id is untyped (S21) |
| 254 | F | party_type + legal_entity_id / taxpayer_identifier | Should | payee | 1099 reporting, KYB of business payees (S21) |
| 255 | F | ownership_verification {method (MICRO_DEPOSIT, PRENOTE, AGGREGATOR, NAME_CHECK, MANUAL), status (UNVERIFIED, PENDING, VERIFIED, FAILED), verified_at} | Must | payee | Nacha WEB debit account-validation rule. verification_result only covers CoP (S21) |
| 256 | T | micro_deposit_verification (new) {payee_id, amounts (hashed), sent_at, attempts, expires_at, status} | Should | micro_deposit_verification (new) | (S21, S22) |
| 257 | F | send_remittance_advice + remittance email | Could | payee | (S21) |
| 258 | F | payee scope: business-level counterparty (shared across users of a business customer) + approval for new payee (maker-checker) | Should | payee | Corporate banking |
| 259 | F | ledger_account_id (counterparty sub-ledger) | Could | payee | (S21) |
| 260 | E | PayeeVerificationResult + UK detailed codes (ANNM, MBAM, BANM, PANM, BAMM, PAMM, AC01, IVCR, ACNS, OPTO, CASS, SCNS) and EU codes (MTCH, CMTC, NMTC, NOAP) as raw_code | Must | payee_verification | Warning text and liability depend on the exact code (B9.6) |
| 261 | F | payee_verification.account_type requested (PERSONAL, BUSINESS) + secondary_reference | Must | payee_verification | UK CoP inputs (B2.5) |
| 262 | F | payee_verification.payer_decision (PROCEEDED, CANCELLED, EDITED) + decided_at | Must | payee_verification | Liability evidence under EU IPR / UK PSR (B9.6) |
| 263 | A | POST /payee-verifications/bulk (VoP for bulk files, opt-out handling) | Should | payee-verifications | EU IPR bulk VoP (B9.6) |
| 264 | F | inbound VoP/CoP responder log (requests we answered as the payee's bank) | Must | payee_verification_response (new) | Responding PSP obligation (EU IPR, UK CoP) |

### C2.4 Mandates and direct debits

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 265 | F | debtor_address, creditor_address | Must | payment_mandate | EPC mandate form fields (B4) |
| 266 | F | place_of_signing | Should | payment_mandate | (B4) |
| 267 | F | signature evidence {document_id, e-signature ref, ip, timestamp, signer} | Must | payment_mandate | signature_channel only (B4) |
| 268 | F | debtor_agent_clearing_id, debtor_account_id_type | Should | payment_mandate | ACH/Bacs (B4) |
| 269 | F | amendment fields: original_mandate_reference, original_creditor_id, original_creditor_name, original_debtor_account, original_debtor_agent, amendment_indicator | Must | payment_mandate (+ mandate_amendment new) | SEPA AmdmntInfDtls. SMNDA after account switch (B4) |
| 270 | F | ultimate_creditor / ultimate_debtor | Should | payment_mandate | (B4) |
| 271 | F | last_sequence_type, next_sequence_type | Should | payment_mandate | Sequence tracking (B4) |
| 272 | F | dormant_expiry_at (36 months after last collection) | Must | payment_mandate | Auto-expire per SDD rulebook (B4) |
| 273 | F | max_collections_count / max_amount_per_period | Should | payment_mandate | Debtor-side limits (PSD2 Art. 5(3)(d)) |
| 274 | F | ach_authorization_type (WRITTEN, ORAL_RECORDED, ELECTRONIC) + revocation_method + recording ref | Must (ACH) | payment_mandate | Nacha authorisation evidence (B2.1) |
| 275 | F | bacs_auddis_code (0N, 0C, 0S) + addacs_reason | Should (UK) | payment_mandate | (B4) |
| 276 | F | pre_notification_days + pre_notification_channel | Should | payment_mandate | 14-day default (B2.4) |
| 277 | T | direct_debit_rule (new): debtor-side controls {account_id, type (BLOCK_ALL, ALLOW_LIST, BLOCK_LIST, MAX_AMOUNT, MAX_FREQUENCY), creditor_id, max_amount, period} | Must (EU) | direct_debit_rule (new) | Debtor PSP obligation under SEPA Reg 260/2012 Art. 5(3)(d) (B4) |
| 278 | E | MandateStatus + DORMANT, AMENDED (history), REVOKED | Should | enum | |
| 279 | F | direct_debit_collection: end_to_end_id, remittance, creditor_reference, settlement_date, refund_deadline (8 weeks / 13 months), r_transaction_type (REJECT, RETURN, REFUND, REVERSAL, REVOCATION, REQUEST_FOR_CANCELLATION) | Must | direct_debit_collection | (B2.4) |
| 280 | E | DirectDebitCollectionStatus + REJECTED (pre-settlement), REFUNDED, REVERSED | Should | enum | (B2.4) |
| 281 | A | POST /payment-mandates/{id}/amend (creates amendment with original values) | Must | payment-mandates | (B4) |
| 282 | A | POST /direct-debit-collections/inbound (pacs.003 / ACH debit received: decision accept/return with R-code, check against direct_debit_rule and mandate) | Must | direct_debit_collection | Spec books inbound credits only. Inbound debits are not modelled (B9.3) |
| 283 | A | POST /direct-debit-collections/{id}/reverse (creditor-initiated reversal, pain.007/pacs.007) | Should | direct-debit-collections | (B8) |
| 284 | A | POST /payment-mandates/inbound (mandate set-up received from creditor, e.g. Bacs AUDDIS, NACH, PIX Automático) | Should | payment-mandates | (B4) |

### C2.5 Returns, rejections, NOC, reversals

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 285 | T | payment_reason_code (new reference): {scheme (ISO, NACHA, BACS, FPS, SEPA, CARD), code, description, category (RETURN, REJECT, CANCEL, NOC, STATUS), return_window_days, unauthorised flag, retryable flag} | Must | payment_reason_code (new) | Seed with B5.1-B5.3. reason_code is free string(8) today |
| 286 | T | payment_change_notification (new): NOC {payment_id, code (C01-C14), corrected_data {routing, account, account_type, individual_id, sec}, received_at, applied_at, refused (C61-C69)} | Must (ACH) | payment_change_notification (new) | Originators must apply NOCs within 6 banking days (B5.2) |
| 287 | A | POST /payment-change-notifications/{id}/apply (update payee/mandate) and /refuse | Must | payment-change-notifications | (B5.2) |
| 288 | T | payment_reversal (new): {payment_id, reason (DUPLICATE, INCORRECT_AMOUNT, INCORRECT_RECEIVER, DATE_EARLIER, DATE_LATER), status, deadline (5 banking days), reversal_payment_id} | Must | payment_reversal (new) | (S21) |
| 289 | F | payment_return: return_window_deadline, date_of_death, addenda_information, original_trace_number, dishonored (bool), contested (bool), role (ORIGINATING/RECEIVING) | Should | payment_return | Dishonor R61-R69 / contested R70-R77 (B5.1) |
| 290 | E | PaymentReturnStatus + DISHONORED, CONTESTED | Could | enum | (B5.1) |
| 291 | F | payment_recall: camt.029 status enum (ACCR, RJCR, PDCR) and reason enum (ARDT, NOAS, NOOR, LEGL, CUST, AC04, AM04); recall deadline | Should | payment_recall | (B5.3) |
| 292 | R | ACH return-rate monitoring (unauthorised 0.5%, administrative 3%, overall 15%) per originator | Must (ODFI) | reporting / payee | Nacha thresholds (B5.1) |
| 293 | R | Reinitiation limits after R01/R09 (max 2 retries within 180 days, "RETRY PYMT") | Must (ACH) | direct_debit_collection | (B5.1) |
| 294 | F | inbound return of FX payments: reconversion amount and fx gain/loss posting | Should | payment_return | (B7) |

### C2.6 Recurring and scheduled payments

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 295 | T | payment_schedule (new) / standing order: {customer_id, debtor_account_id, payee_id or payment template json, rail preference, amount_type (FIXED, VARIABLE, SWEEP_TO_TARGET, FULL_BALANCE, PERCENTAGE), amount, currency, frequency (DAILY, WEEKLY, FORTNIGHTLY, MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL), interval, day_of_month (incl. negative from end), day_of_week, start_date, end_date, max_occurrences, business_day_convention (FOLLOWING, PRECEDING, MODIFIED_FOLLOWING), next_execution_date, executed_count, status (ACTIVE, PAUSED, COMPLETED, CANCELLED, FAILED), failure_policy {retry_count, retry_delay, suspend_after}, last_payment_id, first_amount, final_amount} | Must | payment_schedule (new) | No recurring payment object exists (operations has a STANDING_ORDERS job type only) (B6, S23) |
| 296 | T | payment_schedule_run (new) {schedule_id, scheduled_for, executed_at, payment_id, status, failure_reason} | Must | payment_schedule_run (new) | Audit per occurrence (B6) |
| 297 | A | /v1/payment-schedules CRUD + POST /{id}/pause, /resume, /skip-next, /cancel, GET /{id}/runs, POST /execute-due | Must | payment-schedules | (B6) |
| 298 | E | PaymentChannel SCHEDULER exists. Add PaymentType STANDING_ORDER or a schedule_id link on payment | Must | payment.schedule_id | |
| 299 | T | sweep_rule (new) {source, target, threshold, target_balance, direction, schedule} | Could | sweep_rule (new) | Cash concentration (overlaps treasury) |

### C2.7 Request-to-pay, QR, links

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 300 | F | request_to_pay: requested_execution_date, amount_modification_allowed, creditor_reference, payer agent/account (non-alias), accepted payment methods, rail (SRTP, RTP_RFP, FEDNOW_RFP, UPI_COLLECT, PIX_COBRANCA), external rfp id, rejection_reason_code | Should | request_to_pay | pain.013/014 (B2.6, B9.4) |
| 301 | E | RequestToPayStatus + PARTIALLY_PAID, REJECTED (by payer PSP, distinct from DECLINED) | Could | enum | (B9.4) |
| 302 | A | POST /requests-to-pay/inbound (RfP received for our customer as payer, from RTP/FedNow/SRTP) | Must | request_to_pay | Inbound RfP is not modelled (B2.6) |
| 303 | F | payment_qr_code: point_of_initiation (11/12), merchant_account_templates json (tag 26-51 GUID + data), additional_data {bill_number, mobile, store_label, loyalty, reference_label, customer_label, terminal_label, purpose, consumer_data_request}, tip_indicator (01/02/03), convenience_fee_fixed/percent, postal_code, language_template {lang, name, city} | Should | payment_qr_code | (B9.5) |
| 304 | F | payment_link: payer data collection (name, email, address required flags), success/cancel URLs, reference, locale | Could | payment_link | |

### C2.8 Inbound flows, reconciliation, virtual accounts

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 305 | T | virtual_account (new): {name, customer_id, internal deposit_account_id, counterparty/payee_id, account_number, routing details, iban (virtual IBAN), currency, ledger sub-account, status, expires_at} | Must | virtual_account (new) | Collections attribution and virtual IBANs. Not in accounts or payments (S21) |
| 306 | F | payment.virtual_account_id (inbound attribution) | Must | payment | (S21) |
| 307 | T | expected_payment (new): {amount_lower/upper, date_lower/upper, direction, rail, currency, internal_account, counterparty, remittance/descriptor match hints, reconciliation rule vars, status (UNRECONCILED, PARTIALLY_RECONCILED, RECONCILED, ARCHIVED), matched payment/transaction ids, reconciliation_method} | Should | expected_payment (new) | Receivables matching (S21) |
| 308 | A | POST /expected-payments/{id}/reconcile, /unreconcile, auto-match job | Should | expected-payments | (S21) |
| 309 | F | inbound payment: originating account/routing, originator company id/name (ACH), raw rail data, sec_code | Should | payment (inbound) | (S21 incoming_payment_detail) |
| 310 | A | POST /payments/inbound-debit (received ACH debit / pull: decision, NSF, debit filters) | Must | payment / direct_debit_collection | (S22 ReceivedDebit, S23 received ACH) |
| 311 | T | ach_debit_filter (new) / ACH positive pay: {account_id, company_id allow/block, max_amount, sec_codes, expires_at} | Should | ach_debit_filter (new) | Business account protection (B9.3) |
| 312 | F | received credit failure_code (ACCOUNT_CLOSED, ACCOUNT_FROZEN, INTERNATIONAL_NOT_ALLOWED) and reversal deadline | Should | payment (inbound) | (S22) |
| 313 | F | early availability / advance of incoming ACH (wasAdvanced, advanced_amount) | Could | payment | Unit "Advanced" status (S23) |
| 314 | A | camt.053/camt.054/BAI2 statement ingestion into payment reconciliation | Should | rail_message / reconciliation | (B8) |

### C2.9 Fees and FX

| # | Kind | Field / enum / table / API | Priority | Target | Notes |
|---|---|---|---|---|---|
| 315 | T | payment_fee (new): fee lines {type (RAIL, SERVICE, EXPEDITE, FX_MARGIN, CORRESPONDENT, RETURN, INVESTIGATION, NOC), amount, currency, tax, bearer, waived, journal_entry_id} | Should | payment_fee (new) | fee_amount is a single column today (B7) |
| 316 | F | fx_quote: rate_type (SPOT, FORWARD), value_date, markup_pct_over_reference (ECB) | Should | fx_quote | EU disclosure (B7) |
| 317 | F | fx_deal: forward/NDF support (maturity, settlement type) | Could | fx_deal | Corporate FX (overlaps treasury) |
| 318 | F | rail_cutoff: same-day ACH windows (3 windows) and per-window settlement times | Should | rail_cutoff | One cut-off row per window works, but there is no settlement_time column |
| 319 | F | payment_rail: max_amount per participant tier, customer-type limits (FedNow participant limits), timeout_seconds | Should | payment_rail | (B2.6) |

### C2.10 Events (missing domain events)

| # | Kind | Event | Priority | Notes |
|---|---|---|---|---|
| 320 | Ev | payments.payment.reversed / payment_reversal.completed | Must | with payment_reversal |
| 321 | Ev | payments.payment_change_notification.received / applied | Must | NOC |
| 322 | Ev | payments.payment_schedule.created / executed / failed / completed | Must | standing orders |
| 323 | Ev | payments.direct_debit.received / returned (inbound debits) | Must | |
| 324 | Ev | payments.virtual_account.created / payment_attributed | Should | |
| 325 | Ev | payments.expected_payment.reconciled | Should | |
| 326 | Ev | cards.card_authorization.network_stand_in_advice | Must | STIP advice |
| 327 | Ev | cards.card_fraud_report.submitted | Must | TC40/SAFE |
| 328 | Ev | cards.card_token.activation_code_sent / metadata_updated | Should | |
| 329 | Ev | cards.card_account_updater.submitted / acknowledged | Should | VAU/ABU |
| 330 | Ev | cards.threeds_authentication.rreq_failed | Should | |

---

## Summary counts

| Part | Content | Count |
|---|---|---|
| A1 | Program/product config fields catalogued | ~95 |
| A2 | Card + cardholder fields | ~70 |
| A3 | Controls / velocity fields | ~35 |
| A4 | ISO 8583 DEs (44 rows) + authorisation fields (~60) | ~105 |
| A5 | Clearing / settlement fields | ~28 |
| A6 | Token fields | ~25 |
| A7 | 3DS fields | ~45 |
| A8 | Dispute fields (~22) + Visa reason codes (25) + MC reason codes (16) + stage table | ~65 |
| B1-B2 | Payment order common (~55) + ACH (~28), Fedwire tags (19), MT103/gpi (25), SEPA (22), FPS/Bacs (10), FedNow/RTP (10), card push (12), check/book (7) | ~190 |
| B3-B4 | Counterparty (~20) + mandate (~20) fields | ~40 |
| B5 | Reason codes: Nacha returns (~40), NOC (12), ISO status/return/cancel (~55), provider enums (15) | ~120 |
| B6-B10 | Recurring (14), fees/FX (11), ISO messages (19), expected payments/virtual accounts/inbound/RfP/QR/VoP (~70), lifecycle map (16) | ~130 |
| **Total catalogue table rows (Parts A-B, incl. sources and headers)** | | **~950 (about 900 distinct fields/codes)** |
| **C1 cards gaps** | 178 items: Must 66, Should 83, Could 29 | 178 |
| **C2 payments gaps** | 130 items (#201-330, incl. 11 events): Must 50, Should 63, Could 17 | 130 |
| **New tables proposed** | cards: card_bin_range, card_cardholder, card_account_updater, card_mcc_group, card_merchant_group, interchange_rate, scheme_fee, card_transaction, card_sca_counter, card_dispute_reason_code, card_fraud_report, card_dispute_message, card_dispute_alert; payments: payment_charge, payment_reference, payment_line_item, payment_reversal, payment_investigation, payment_tracker_event, payment_stop, payment_ach_detail, payment_wire_detail, payment_card_push_detail, check_payment, check_deposit, check_positive_pay, bacs_report_item, app_fraud_claim, payee_account, micro_deposit_verification, payee_verification_response, direct_debit_rule, mandate_amendment, payment_reason_code, payment_change_notification, payment_schedule, payment_schedule_run, sweep_rule, virtual_account, expected_payment, ach_debit_filter, payment_fee | 41 |

### Top Must items to schedule first
1. **Payments: recurring payments.** payment_schedule and payment_schedule_run, with their APIs. Nothing models them today.
2. **Payments: ACH.** ACH detail table (SEC, transaction code, trace, company fields, addenda), NOC table, payment_reversal, and a reason-code reference covering Nacha, ISO, Bacs and FPS.
3. **Payments: inbound debits and debtor protection.** Inbound debit handling (ACH and SEPA DD received) with debtor-side direct-debit rules (EU), plus mandate amendment and dormancy expiry.
4. **Payments: virtual accounts.** The virtual_account table and inbound attribution. Collections and vIBAN depend on it.
5. **Payments: debtor-side data and verification.** Debtor address, ultimate parties, service level, local instrument and intermediary agents (CBPR+/TFR). Also the full CoP/VoP result codes and the payer decision evidence (EU IPR, UK PSR), plus the UK APP fraud claim.
6. **Cards: authorisation.** Stored-credential/MIT data, EMV cryptogram results, token/wallet/PAR in auth, CAVV/SCA exemption/liability shift, and network STIP advice. Also OCT/AFT message types, scheme-rule hold validity, and a balance in the balance-inquiry response.
7. **Cards: PSD2 SCA.** Program-level SCA limits and per-card counters, plus 3DS trans_status and transStatusReason.
8. **Cards: controls.** MCC groups with hold rules, and velocity include-flags plus merchant scope.
9. **Cards: tokens.** Wallet-specific push-provisioning payloads, the activation-methods list, PAR and token type.
10. **Cards: clearing and disputes.** Real BASE II/IPM clearing formats with multi-clearing, interchange designators and chargeback fields. A dispute reason-code reference with deadlines, Reg E/Z deadlines, and mandatory fraud reporting (TC40/SAFE).
