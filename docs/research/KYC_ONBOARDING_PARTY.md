# KYC, Onboarding and Party Data: Field Catalogue and Gap Analysis

Status: research note (2026-10-07). Scope: `spec/domains/party.yaml` (customer-service, onboarding-service, kyc-service)
compared with public API documentation of enterprise BaaS / KYC platforms and with the regulatory minimums.

## 0. Executive summary

**Is the "we only ask 2-3 data points" complaint true?** In part, and the part that is true is worse than it sounds.

1. **The spec (CIF schema) is broader than the complaint suggests.** `customer` already has about 60 columns (names,
   local-script name, DOB, place and country of birth, two nationalities, residence and tax residence, US-person flag,
   occupation, employment, income, source of funds and wealth, KYB basics, PEP, risk). It also has child tables for
   identifiers, addresses with history, contacts with OTP verification, relationships and UBOs, consents, devices and
   credentials.
2. **What runs is narrow.** The starter-pack journeys
   (`services/tenant-service/src/main/resources/starter-packs/generic-2026.10.0.yaml`) collect phone, 6 personal fields,
   5 address fields, an ID number with an ID document image, and 2 consent booleans. That is the CIP minimum and nothing
   more: no tax ID or TIN, no occupation or income, no source of funds, no account purpose or expected activity, no PEP
   self-declaration, no tax self-certification, and no KYC-check consent.
3. **Approval drops most of what is collected.** `ApplicationService.ensureCustomer()` (onboarding-service) sends only
   `first_name`, `last_name`, `date_of_birth`, `nationality`, `display_name`, `legal_name`, `trading_name` and
   `registration_number` to `POST /v1/customers`. The applicant's verified phone and email, address, ID number, consents
   and everything else stay in `onboarding_application.collected_data` (JSON). No `customer_address`,
   `customer_contact`, `customer_identifier` or `consent` rows are ever created. So the CIF really does end up with
   2-3 data points per customer, whatever the journey captured.
4. **Structural gaps against enterprise platforms** (Unit, Stripe, Treasury Prime, Marqeta, Synctera, Alloy, Onfido and
   Persona):
   - no **expected-activity / account-purpose** profile (FATF R.10 "purpose and intended nature"; required by Unit);
   - no **multi-jurisdiction tax self-certification** (CRS/FATCA: TIN per country, reason when no TIN, W-8/W-9);
   - no **PEP self-declaration** with category and position (we only have a screening-derived boolean);
   - no **beneficial-ownership certification** (the FinCEN certifying individual, a "no UBO" attestation, exemption
     reasons);
   - no **control-person / executive / representative** roles;
   - no **industry codes by scheme** (NAICS, MCC, SIC, NACE), nature of business, countries of operation, or
     regulated/listed status;
   - no **requirements / outstanding-items model** (Stripe `currently_due` / `past_due`, Unit `AwaitingDocuments`);
   - no **per-party sub-applications** for officers and UBOs in business onboarding;
   - the document, check and screening enums are thin (no breakdowns or sub-results, no NFC/MRZ/barcode results);
   - no **risk-model configuration** (country lists, industry lists, review frequency per level, EDD triggers).

Counts are in section 8: 381 catalogued rows (356 field-level rows) and 148 gaps (Must 60 / Should 52 / Could 36).

Legend used in all tables:

- **Req.**: M = mandatory in at least one provider or regulation for the stated customer type; C = conditional; O = optional.
- **Providers**: GAL Galileo (now SoFi Technology Platform), UNT Unit, SYN Synctera, TPR Treasury Prime, MQT Marqeta,
  ALY Alloy, PSN Persona, ONF Onfido / Entrust IDV, MAM Mambu, TMV Thought Machine Vault, STR Stripe Connect,
  GSM GSMA Mobile Money API (proxy for Comviva mobiquity, see 1.0), REG regulation (CIP = 31 CFR 1020.220,
  CDD = 31 CFR 1010.230, FATF R.10/R.12/R.24, AMLR = EU Reg. 2024/1624, MLR = UK MLR 2017, CRS = OECD Common Reporting
  Standard, FATCA = IRS W-8/W-9 and IGA Annex I).
- **Ours**: Y = present in `party.yaml`; P = partial (free text where an enum is needed, single value where a list is
  needed, or present in spec but not populated by onboarding); N = absent.
- A dagger (†) marks values taken from provider documentation known before this session but **not re-fetched** in this
  session (the page was unreachable or not rendered). Check these against the live documentation before you rely on
  enum spellings.

## 0.1 Sources

| Provider / reference | URL(s) used | Notes |
|---|---|---|
| Galileo / SoFi | https://docs.tech.sofi.com/pro/reference/post_createaccount (redirect from docs.galileo-ft.com) | Create Account request parameters fetched in full |
| Unit | https://www.unit.co/docs/applications-v2/applications/ ; https://www.unit.co/docs/api/applications/resources/ ; https://rubydoc.info/gems/unit_ruby_sdk/1.4.1/Unit/Application/CreateIndividualApplicationRequest | v2 enums fetched; v1 Occupation, AnnualIncome and similar enums are † |
| Synctera | https://docs.synctera.com/reference/createperson ; https://docs.synctera.com/reference/createbusiness ; https://docs.synctera.com/reference/verifycustomer ; https://docs.synctera.com/docs/external-scores ; https://docs.synctera.com/reference/listcrr | |
| Treasury Prime | https://docs.treasuryprime.com/reference/post_apply_person_application.md ; .../post_apply_business_application.md ; .../post_apply_account_application.md ; .../post_apply_kyc.md | |
| Marqeta | https://www.marqeta.com/docs/core-api/users ; https://www.marqeta.com/docs/core-api/businesses ; https://www.marqeta.com/docs/core-api/kyc-verification | |
| Alloy | https://developer.alloy.com/public/reference/onboard-a-person-1 ; https://help.alloy.com/hc/en-us/articles/4405993438363 ; https://developer.alloy.com/public/reference/manually-reviewing-via-api | the reference page did not render; attribute names are † |
| Persona | https://docs.withpersona.com/docs/inquiries ; https://docs.withpersona.com/docs/model-lifecycle ; https://docs.withpersona.com/reference/create-an-inquiry ; https://help.withpersona.com/reports/concepts/report-types | |
| Onfido / Entrust IDV | https://documentation.identity.entrust.com/api/latest/ (redirect from documentation.onfido.com) | report names, sub-results and workflow statuses fetched; address sub-fields † |
| Mambu | https://docs.mambu.com/swagger_files/v2/clients.json (OpenAPI, parsed) ; https://docs.mambu.com/api/api-v2/clients/schemas/client/ | Client, Address, IdentificationDocument and PortalSettings fetched in full |
| Thought Machine Vault | https://docs.thoughtmachine.net (login-gated) | the Customer resource is minimal; Vault expects the bank's CIF to hold KYC (†, low confidence) |
| Stripe Connect | https://docs.stripe.com/connect/required-verification-information ; requirements endpoint for a US company with card_payments and transfers ; https://docs.stripe.com/api/persons/object ; https://docs.stripe.com/api/accounts/object | |
| Comviva mobiquity | https://www.comviva.com/wp-content/uploads/2022/09/mobiquity®-Pay-Overview.pdf ; GSMA MM API 1.2 spec https://www.gsma.com/mobilefordevelopment/wp-content/uploads/2021/10/Mobile-Money-API-Specification-1.2.0-Agent-Cash-in-Cash-out-and-Customer-Account-Services.pdf | Comviva publishes no field-level KYC API. GSMA MMAPI KYC object fields are † (the PDF returned 403) |
| US CIP | https://www.law.cornell.edu/cfr/text/31/1020.220 | |
| US CDD / beneficial ownership | https://www.law.cornell.edu/cfr/text/31/1010.230 | 25% ownership prong plus a single control-prong individual; Appendix A certification |
| FATF | https://www.fatf-gafi.org/en/publications/Fatfrecommendations/Fatf-recommendations.html (R.10 CDD, R.12 PEPs, R.24/25 BO) | |
| EU AMLR | https://eur-lex.europa.eu/eli/reg/2024/1624/oj (Art. 20 CDD measures, Art. 22 identification data, Art. 51-63 BO; applies from 10 July 2027) | |
| UK MLR 2017 | https://www.legislation.gov.uk/uksi/2017/692/contents (reg. 28 CDD, reg. 30A discrepancy reporting, reg. 33 EDD, reg. 35 PEPs) | |
| OECD CRS self-certification | https://www.oecd.org/tax/automatic-exchange/common-reporting-standard/ (CRS Commentary, s. VI and the self-certification guidance; CRS XML schema controlling-person types) | |
| IRS FATCA | https://www.irs.gov/forms-pubs/about-form-w-9 ; https://www.irs.gov/forms-pubs/about-form-w-8-ben ; https://www.irs.gov/forms-pubs/about-form-w-8-ben-e | |

### 1.0 Comviva mobiquity note

Comviva publishes only marketing-level material. It describes configurable KYC processes per deployment and country
(self-registration on the phone or a KYC form at an agent) and multiple wallet types with different transaction limits
(that is, tiered KYC), and it manages consumers, agents and merchants in a User Management System. There is no public
field catalogue. The closest public harmonised schema for the mobile-money segment is the GSMA Mobile Money API, which
Comviva-class platforms implement. Its `kycInformation` object (†) contains:

- `birthCountry`, `dateOfBirth`, `contactPhone`, `emailAddress`, `employerName`, `gender` (m/f/u), `nationality`,
  `occupation`;
- `postalAddress` {addressLine1-3, city, stateProvince, postalCode, country};
- `subjectName` {title, firstName, middleName, lastName, fullName, nativeName};
- `idDocument[]` {idType, idNumber, issueDate, expiryDate, issuer, issuerPlace, issuerCountry, otherIdDescription}.

`idType` (†) includes passport, nationalregistration, alienregistration, nationalidcard, drivinglicence, employer,
taxid, seniorcitizenscard, marriagecertificate, birthcertificate, healthcard, votersid, villageelderletter,
bankstatement, utilitybill and otherid. The account side also carries `kycVerificationStatus`
(verified/unverified/rejected), `kycVerificationEntity` and `kycLevel` (integer tier). "Village elder letter" and
"employer letter" matter for our Kenya and Africa agent markets.

---

## 1. Individual identity catalogue

### 1.1 Names

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-001 | title / honorific | string(10-20) | O | Mr, Ms, Mrs, Miss, Dr, ... | salutation | MQT `honorific`, GSM `title`, TMV† | Y `title` |
| I-002 | first_name | string, 1-40 (GAL), max 36 ASCII (TPR) | M | | CIP name | all | Y |
| I-003 | middle_name | string ≤40 | O | | name matching | GAL, SYN, TPR, MQT, ALY, MAM, GSM | Y |
| I-004 | last_name | string ≤40 | M | | CIP name | all | Y |
| I-005 | name_suffix | string | O | Jr, Sr, II, III | screening and bureau match | ALY `name_suffix`† | N |
| I-006 | full_name (unsplit) | string | C | | cultures without first/last split | GSM `fullName` | P `display_name` |
| I-007 | preferred / chosen name | string | O | | servicing, inclusion | SYN `chosen_name` | N |
| I-008 | maiden_name | string | O | | screening, bureau | STR `maiden_name` | N |
| I-009 | aliases / previous names [] | string[] with valid_from/to | C | | AMLR "all names and surnames"; screening | STR `full_name_aliases`, REG AMLR Art. 22(1)(a) | N |
| I-010 | name in native script | string | C | | screening in local script | GSM `nativeName`, STR `first_name_kana` / `_kanji` | Y `name_local_script` (one value) |
| I-011 | name as printed on ID | string | O | | doc-vs-declared comparison | ONF document extraction | P (`extracted_data`) |
| I-012 | name per script / transliteration scheme | string + ISO 15924 script | O | Latn, Arab, Cyrl, Hani, Kana, ... | multi-script matching | STR kana/kanji variants | N |
| I-013 | father's or spouse's name | string | C | | required by some registries (e.g. India CKYC) | REG (local) | N |
| I-014 | mother's name / mother's maiden name | string | C | | required by some registries (e.g. Brazil); legacy security | REG (local) | N |

### 1.2 Birth, nationality, residence

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-020 | date_of_birth | date YYYY-MM-DD; Stripe requires age 13-120 | M | | CIP; age gating | all | Y |
| I-021 | place_of_birth (city) | string | C | | AMLR "place of birth"; screening | GAL `placeOfBirth`, REG AMLR | Y |
| I-022 | country_of_birth | ISO 3166-1 | C | | FATCA indicium (US-born); screening | MQT `birth_place`, GSM `birthCountry` | Y |
| I-023 | place_of_birth subdivision | ISO 3166-2, ≤6 chars | O | | precise matching | GAL | N |
| I-024 | nationality (primary) | ISO 3166-1 alpha-2 | M (non-US: UNT) | | CIP non-US alternative; risk | GAL, UNT, MQT, STR, GSM, TMV† | Y |
| I-025 | nationalities (all) [] | ISO 3166-1[] | M (AMLR) | | AMLR "nationalities"; sanctions nexus | REG AMLR Art. 22(1)(a) | P (2 columns) |
| I-026 | stateless / refugee / subsidiary-protection status | enum | C | NONE, STATELESS, REFUGEE, SUBSIDIARY_PROTECTION | AMLR wording; access to basic accounts | REG AMLR | N |
| I-027 | citizenship (distinct from nationality) | ISO 3166-1 | O | | US citizenship test | TPR `citizenship` | P |
| I-028 | country_of_residence | ISO 3166-1 | M | | consent regime (ONF), risk, CRS | ONF `location.country_of_residence`, TMV† | Y `residence_country` |
| I-029 | residency / immigration status | enum | C | CITIZEN, PERMANENT_RESIDENT, TEMPORARY_RESIDENT, NON_RESIDENT, REFUGEE, ASYLUM_SEEKER | non-resident account rules; CIP non-US | UNT (non-US path), REG | N |
| I-030 | visa / permit type, number and expiry | enum + string + date | C | WORK, STUDY, DEPENDANT, ... | expiry-driven review | ONF `right_to_work` report | N |
| I-031 | gender | enum | O | MQT F/M; MAM MALE/FEMALE; GSM m/f/u; STR (enum) | identity matching | MQT, MAM, GSM, STR, ALY† | Y |
| I-032 | marital_status | enum | O | | joint products, inheritance | (bank practice) | Y |
| I-033 | date_of_death and death certificate | date + doc | C | | estate handling | SYN status DECEASED | Y |
| I-034 | minor flag and legal guardian | bool + relationship | C | | minors' accounts | STR `legal_guardian`, TPR role `minor` | Y |
| I-035 | age-verification result | enum | O | clear / consider | age gating | ONF `age_validation` breakdown | N |

### 1.3 Tax identity, FATCA/CRS self-certification

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-040 | SSN / ITIN | 9 digits, digits only | M (US) | | CIP identification number | UNT `ssn`, SYN `ssn`, TPR `tin`, MQT `ssn`, ALY `document_ssn`†, STR `id_number` | P (`TAX_ID` / `SOCIAL_SECURITY`; no ITIN type) |
| I-041 | ssn_last_4 / masked TIN | 4 digits | C | | reduced-collection flows | STR `ssn_last_4`, ALY `document_ssn_last4`† | N |
| I-042 | ssn_source | enum | O | MANUAL, PREFILL | provenance of prefilled data | SYN | N |
| I-043 | tax residencies [] | ISO 3166-1[] | M (CRS) | | CRS reporting | REG CRS | P (one country) |
| I-044 | TIN per tax residence | string per country, format-validated | M (CRS) | | CRS reporting | REG CRS | N |
| I-045 | no-TIN reason | enum | C | A = country does not issue TINs; B = unable to obtain; C = not required by that jurisdiction | CRS self-cert | REG CRS | N |
| I-046 | us_person | bool | M | | FATCA | REG FATCA | Y |
| I-047 | US indicia flags | bool set | C | US_BIRTHPLACE, US_ADDRESS, US_PHONE, US_STANDING_INSTRUCTION, US_POA, US_IN_CARE_OF | FATCA indicia search (IGA Annex I) | REG FATCA | N |
| I-048 | self-cert form type | enum | M | W9, W8BEN, W8BEN_E, W8IMY, W8ECI, CRS_IND, CRS_ENT, CRS_CP | FATCA/CRS documentation | REG | N |
| I-049 | self-cert signed_at, signatory and capacity | ts, string, enum | M | SELF, POA, GUARDIAN, OFFICER | validity of the certification | REG | N |
| I-050 | self-cert valid_until | date | C | | W-8 forms expire after the 3rd calendar year | REG FATCA | N |
| I-051 | treaty country and claim (W-8BEN Part II) | ISO + text | O | | withholding rate | REG FATCA | N |
| I-052 | foreign TIN and "FTIN not legally required" flag | string + bool | C | | W-8BEN line 6 | REG FATCA | N |
| I-053 | change-in-circumstances flag and date | bool + date | C | | re-certification trigger | REG CRS | N |

### 1.4 Government identifiers

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-060 | primary ID type | enum / int | M | GAL `idType` 0-18; MQT SSN, TIN, SIN, NIN, PASSPORT_NUMBER, DRIVERS_LICENSE, BUSINESS_NUMBER, BUSINESS_TAX_ID, TAXPAYER_REFERENCE; ONF ssn, passport, driving_license, identity_card, ... | CIP | GAL, MQT, ONF, TPR `identification_number_type`, MAM `documentType` | Y (`CustomerIdentifierType`) |
| I-061 | ID number | string ≤64 | M | | CIP | all | Y |
| I-062 | secondary / tertiary ID | type + value | O | | stronger verification | GAL `id2` / `id3`, STR `id_number_secondary` | Y (many rows) |
| I-063 | issuing country | ISO 3166 | M | | CIP non-US "country of issuance" | REG CIP, GSM `issuerCountry` | Y |
| I-064 | issuing authority | string | O | | registry checks | MAM `issuingAuthority`, GSM `issuer` | Y |
| I-065 | place of issue | string | O | | some registries | GSM `issuerPlace` | N |
| I-066 | issue date / expiry date | date | C | | expiry-driven refresh | MQT `*_expiration_date`, MAM `validUntil` | Y |
| I-067 | country-specific IDs | per-country types with regex and check digit | C | CURP (MX, 18 chars), CPF (BR), PAN / Aadhaar-masked (IN), BVN / NIN (NG), KRA PIN / ID (KE), NI number (UK), SIN (CA), ... | local CIP | GAL `curp`, MQT SIN/NIN | P (generic `NATIONAL_ID` / `OTHER`, no validation catalogue) |
| I-068 | identification document template | key | O | | which document types are accepted per product | MAM `identificationDocumentTemplateKey` | N |
| I-069 | alien registration number | string | C | | CIP non-US alternative | REG CIP, GSM alienregistration† | N (no enum value) |

### 1.5 PEP, sanctions, adverse media

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-070 | PEP self-declaration | enum | M (STR, GAL) | STR `political_exposure`: existing, none; GAL `politicalAffiliation` 0/1 | customer attestation (independent of screening) | STR, GAL | N (`is_pep` is set by screening only) |
| I-071 | PEP category | enum | C | DOMESTIC, FOREIGN, INTERNATIONAL_ORGANISATION, FAMILY_MEMBER, CLOSE_ASSOCIATE | FATF R.12 / MLR reg. 35 treatment | REG | N |
| I-072 | PEP position, country, start and end date | string, ISO, dates | C | | declassification after leaving office (MLR: at least 12 months) | REG | N |
| I-073 | PEP related person link | relationship | C | | RCA handling | REG | P (SPOUSE exists; no CLOSE_ASSOCIATE) |
| I-074 | senior-management approval of PEP relationship | user + ts | M if PEP | | FATF R.12(b), MLR reg. 35(5)(a) | REG | N |
| I-075 | sanctions screening result | enum | M | CLEAR, POTENTIAL_MATCH, CONFIRMED_MATCH, FALSE_POSITIVE | CIP government list; OFAC | all | Y |
| I-076 | PEP screening result | enum | M | | | ONF `watchlist_peps_only`, PSN PEP report | Y |
| I-077 | adverse media result and categories | enum + category[] | C | FINANCIAL_CRIME, FRAUD, TERRORISM, ORGANISED_CRIME, ... | EDD | ONF `watchlist_enhanced`, PSN Adverse Media | P (no categories) |
| I-078 | match detail (matched name, DOB, countries, list, program, score, URL) | structured | M | | analyst disposition | ONF, PSN | P (`matches` json) |
| I-079 | list sources and versions | json | M | OFAC SDN, UN, EU, HMT/OFSI, local | audit | | Y |
| I-080 | ongoing-monitoring enrolment and provider subscription id | bool + string | M | | perpetual screening | ONF, PSN | P (flag only) |
| I-081 | OFAC failure / watchlist code | enum | | MQT OFACFailure, Watchlist, Sanctions List Non-OFAC | decline reason | MQT | P |
| I-082 | crypto-address watchlist | result | O | | crypto products | PSN | N |
| I-083 | internal blacklist / fraud consortium hit | result | O | | | | Y (`INTERNAL_BLACKLIST`) |

### 1.6 Occupation, employment, income, wealth

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-090 | occupation (free text) | string ≤60 | C | | risk factor | GAL, TPR, GSM | Y |
| I-091 | occupation code | enum | C (UNT non-US) | UNT†: ArchitectOrEngineer, BusinessAnalystAccountantOrFinancialAdvisor, CommunityAndSocialServicesWorker, ConstructionMechanicOrMaintenanceWorker, Doctor, Educator, EntertainmentSportsArtsOrMedia, ExecutiveOrManager, FarmerFishermanForester, FoodServiceWorker, GigWorker, HospitalityOfficeOrAdministrativeSupportWorker, HouseholdManager, JanitorHousekeeperLandscaper, Lawyer, ManufacturingOrProductionWorker, MilitaryOrPublicSafety, NurseHealthcareTechnicianOrHealthcareSupport, PersonalCareOrServiceWorker, PilotDriverOperator, SalesRepresentativeBrokerAgent, ScientistOrTechnologist, Student | risk scoring needs codes, not text; ISCO-08 is the international option | UNT `occupation` / `profession` | N |
| I-092 | employment_status | enum | C | UNT†: Employed, PartTime, SelfEmployed, Student, Unemployed, Retired | | UNT | Y (no PART_TIME) |
| I-093 | employer name | string | C | | | GSM `employerName`, MQT `company`, GAL `incomeSource` | Y |
| I-094 | employer / own-business industry | code | C | | risk (cash-intensive) | UNT `industry` (sole prop) | N |
| I-095 | job title | string | O | | | MQT `title` | N (only on relationships) |
| I-096 | employment start date / years in employment | date / int | O | | lending, stability | (lending) | N |
| I-097 | employer address and phone | address + phone | O | | employment verification | (lending) | N |
| I-098 | annual income amount and currency | amount | C | | affordability, risk | | Y |
| I-099 | monthly income | float 0-9,999,999,999 | O | | | GAL `monthlyIncome` | P (derivable) |
| I-100 | income band | enum | C (UNT non-US) | UNT†: UpTo10k, Between10kAnd25k, Between25kAnd50k, Between50kAnd100k, Between100kAnd250k, Over250k | quick capture without exact amounts | UNT `annualIncome` | N |
| I-101 | source of income | enum | C (UNT non-US) | UNT†: EmploymentOrPayrollIncome, PartTimeOrContractorIncome, InheritancesAndGifts, PersonalInvestments, BusinessOwnershipInterests, GovernmentBenefits | risk | UNT `sourceOfIncome` | N |
| I-102 | income frequency | enum | O | WEEKLY, BIWEEKLY, MONTHLY, ANNUAL, IRREGULAR | expected-credit modelling | (bank practice) | N |
| I-103 | source of funds | enum | M (UNT v2) | UNT: SalaryOrWages, BusinessIncome, InvestmentIncome, RetirementSavings, Inheritance, Gift, SaleOfAssets, LegalSettlement, LoanProceeds | FATF R.10; EDD | UNT `sourceOfFunds` | P (free text) |
| I-104 | source of funds description | text | C | | | UNT `sourceOfFundsDescription` | P |
| I-105 | source of wealth | enum + text | C (PEP / high risk) | EMPLOYMENT, BUSINESS_OWNERSHIP, INHERITANCE, INVESTMENTS, PROPERTY_SALE, ... | FATF R.12; MLR reg. 35 | REG | P (free text) |
| I-106 | source of funds / wealth evidence documents | doc links | C | | EDD | REG | N |
| I-107 | net worth / liquid assets band | enum | O | | wealth, suitability | (wealth) | N |

### 1.7 Expected activity (purpose and intended nature of the relationship)

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-110 | account purpose | enum | M (UNT v2) | UNT individual: PayrollOrDirectDeposit, Cryptocurrency, CashHeavyPersonalIncome, InternationalRemittances | FATF R.10(c), AMLR Art. 20(1)(c) | UNT `accountPurpose`, REG | N |
| I-111 | account purpose detail | text | C | required by UNT for Cryptocurrency, CashHeavyPersonalIncome, InternationalRemittances | | UNT | N |
| I-112 | expected monthly volume band | enum | M (UNT) | UNT individual: Between1KAnd5K, Between5KAnd15K, Between15KAnd30K, Between30KAnd60K, GreaterThan60K | TM baseline | UNT `transactionVolume` | N |
| I-113 | transaction volume description | text | C | UNT requires it when the band is $30,001 or more | | UNT | N |
| I-114 | expected monthly transaction count | int band | O | | TM baseline | (bank practice) | N |
| I-115 | expected cash usage (deposits / withdrawals) | band | C | | cash-intensity risk | (bank practice) | N |
| I-116 | expected international transfers and countries | bool + ISO[] | C | | geography risk | (bank practice) | N |
| I-117 | expected payment rails | enum[] | O | ACH, WIRE, CARD, CHECK, CASH, CRYPTO, MOBILE_MONEY | | (bank practice) | N |
| I-118 | other banking relationships | string[] | O | | | UNT `banks[]` | N |
| I-119 | initial deposit amount and funding source | amount + enum | O | | first-funding risk | GAL `loadAmount`, `loadFromAccountNo`; TPR `deposit_id` | N |

### 1.8 Contact

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-120 | email | string 3-255 | M (most) | | | all | Y |
| I-121 | secondary email | string | O | | | TPR `secondary_email_address` | Y (many rows) |
| I-122 | email verified and verified_at | bool + ts | M | | | MQT `authentication.email_verified` / `email_verified_time` | Y |
| I-123 | mobile phone | E.164 | M | | | all | Y |
| I-124 | phone country code (separate field) | "+1" | O | | | GAL `mobilePhoneCountryCode`, UNT Phone{countryCode, number} | Y (E.164) |
| I-125 | mobile carrier | id | O | | SIM-swap and carrier checks | GAL `mobileCarrierId` | N |
| I-126 | home / work / second mobile | phone | O | | | MAM `homePhone`, `mobilePhone2`, GAL `otherPhone` | Y |
| I-127 | preferred contact method | enum | O | EMAIL, SMS, PHONE, POST, PUSH | servicing | TMV† | N |
| I-128 | phone risk (line type, tenure, SIM swap) | result | O | | fraud | PSN Phone Risk, SYN `phonerisk` | N |
| I-129 | email risk (age, domain, breach) | result | O | | fraud | PSN Email Risk, SYN `emailrisk` | N |
| I-130 | fax, department, extension (business contact) | string | O | | | MQT `primary_contact` | Y (FAX) / N |

### 1.9 Addresses

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-140 | residential address lines | GAL 5 lines (40/40/30/30/30); TPR 2 lines × 35 | M | | CIP address | all | Y (3 lines) |
| I-141 | structured parts | building_number, flat_number, building_name, street, sub_street | O | | UK / EU verification sources | ONF address† | N |
| I-142 | city, state / subdivision, postal code, country | strings; TPR city ≤18, state ≤2 (US) | M | | | all | Y |
| I-143 | subdivision code | ISO 3166-2 | O | | validation | GAL | P (free `state`) |
| I-144 | country code format | alpha-2 (most), UN M49 numeric (GAL) | M | | | | Y |
| I-145 | PO-box prohibition on residential address | validation rule | M | | CIP requires a residential or business street address | GAL, STR, MQT ("PO boxes are not allowed") | N |
| I-146 | mailing address | address | O | | | TPR, Y | Y |
| I-147 | shipping / card-delivery address and "permanent" flag | address + 0/1 | O | | card issuance | GAL `shipTo*`, `shipToAddressPermanent`; SYN `shipping_address` | N (no SHIPPING type) |
| I-148 | registered address (person) | address | C | | | STR `registered_address` | Y |
| I-149 | address history (3+ years) | valid_from / valid_to | C | | bureau match, UK PAF | | Y |
| I-150 | residential status | enum | O | OWNER, MORTGAGED, RENTING, WITH_FAMILY, OTHER | lending | (lending) | N |
| I-151 | geocode | lat / long, 6 dp | O | | | MAM | Y |
| I-152 | proof-of-address document link | doc ref | C | | non-documentary failure fallback | UNT doc `AddressVerification` | N (no FK) |
| I-153 | address verification method and source | enum | O | DOCUMENT, DATABASE, BUREAU, OPEN_BANKING, POSTAL_CODE_LETTER | audit | ONF `proof_of_address`, PSN Address Lookup | P (bool only) |
| I-154 | address in local script | address | O | | | STR `address_kana` / `address_kanji` | N |
| I-155 | standardisation / deliverability result | enum | O | | | (USPS / PAF) | N |
| I-156 | registered-agent-address flag | bool | C (business) | | UNT requires an operating address when true | UNT `isRegisteredAgentAddress` | N |

### 1.10 Other demographics and servicing

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-160 | preferred language | enum / BCP 47 | O | MAM ENGLISH, PORTUGESE, SPANISH, RUSSIAN, FRENCH, GEORGIAN, CHINESE, INDONESIAN, ROMANIAN, BURMESE, GERMAN, PORTUGUESE_BRAZIL, VIETNAMESE, ITALIAN, THAI, NORWEGIAN, PHRASE; GAL `locale` en_US, es_US, en_CA, fr_CA, es_MX, en_MX | communications | MAM, GAL | Y |
| I-161 | number of dependants | int | O | | lending affordability | (lending) | N |
| I-162 | education level | enum | O | | lending and scoring in emerging markets | (lending) | N |
| I-163 | accessibility needs / vulnerability flags | enum[] | C (UK) | HEALTH, LIFE_EVENT, RESILIENCE, CAPABILITY; AUDIO, LARGE_PRINT, BRAILLE | FCA FG21/1, Consumer Duty | TMV `accessibility`† | N |
| I-164 | military status | enum | O | | SCRA / MLA (US lending) | (US) | N |
| I-165 | CFPB demographic data (ethnicity, race, self-identified gender) | enum[] | O | | Section 1071 small-business lending | STR `us_cfpb_data` | N |
| I-166 | profile picture / signature specimen | file ref | O | | branch signature verification | MAM `profilePictureKey`, `profileSignatureKey` | N |
| I-167 | staff flag | bool | O | | | | Y |
| I-168 | referral / campaign code | string | O | | | | Y |
| I-169 | external customer id | string ≤512 | O | | | GAL `externalCustomerId`, ALY `external_entity_id`† | Y |
| I-170 | metadata / tags / notes | map / string | O | MQT metadata (up to 20 keys), notes ≤255; UNT tags | | MQT, UNT, MAM, SYN | Y (no notes on customer) |
| I-171 | assigned branch / centre / officer | ref | O | | | MAM `assignedBranchKey`, `assignedCentreKey`, `assignedUserKey` | Y (no centre) |
| I-172 | client role / customer class | ref | O | | which fields are required per type | MAM `clientRoleKey` | P (customer_type) |
| I-173 | portal (online banking) state | enum | O | ENABLED, DISABLED | | MAM `portalSettings.portalState` | P (credentials) |
| I-174 | loan cycle counts | int | O | | microfinance group lending | MAM `loanCycle`, `groupLoanCycle` | N |

### 1.11 Consents and agreements

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-180 | ToS acceptance date, IP and user agent | ts + ip + string | M | | evidence | STR `tos_acceptance.date` / `ip` / `user_agent` | Y (`consent.evidence`) |
| I-181 | agreement version | string | M | | | | Y |
| I-182 | privacy notice acknowledged | bool | M (US for ONF) | | | ONF `privacy_notices_read` | Y |
| I-183 | consent to KYC / identity checks | bool | M | | GLBA / FCRA permissible purpose; vendor terms | SYN `customer_consent` (required), ONF `ssn_verification` consent | N (no purpose value) |
| I-184 | E-SIGN consent to electronic records | bool + version | M (US) | | E-SIGN Act 15 USC 7001(c) | (US) | N |
| I-185 | CIP notice shown / acknowledged | ts | M (US) | | 31 CFR 1020.220(a)(5) | REG CIP | N |
| I-186 | product agreements (deposit agreement, cardholder agreement, fee schedule, Reg E / Reg DD disclosures) | version + ts | M | | | (US) | P (TERMS only) |
| I-187 | marketing consent per channel | bool | O | | | | Y |
| I-188 | credit bureau pull consent | bool | C | | | | Y |
| I-189 | biometric processing consent | bool | C | | BIPA, GDPR Art. 9 | | Y |
| I-190 | guardian acceptance on behalf of a minor | object | C | | | STR `additional_tos_acceptances` | N |
| I-191 | tax self-certification attestation (penalties of perjury) | ts + signer | M | | W-9 / W-8 / CRS | REG | N |
| I-192 | issuing-programme terms (separate acceptance) | date + ip | C | | card issuing | STR `settings.card_issuing.tos_acceptance` | N |

### 1.12 Device, network and session signals

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| I-200 | IP address | IPv4 / IPv6 | O | | geo consistency | UNT `ip`, ALY `ip_address_v4` / `_v6`†, MQT `ip_address`, ONF `location.ip_address` | Y |
| I-201 | device fingerprints [] | {provider, value} | O | | fraud | UNT `deviceFingerprints`, TPR `iovation_token`, ALY `iovation_blackbox`†, GAL `riskContextId` | P (one string) |
| I-202 | user agent | string | O | | evidence | STR | N |
| I-203 | coarse geolocation | json | O | | | | Y |
| I-204 | IP risk (proxy, VPN, TOR, hosting, IP country vs declared) | flags | O | | fraud | (vendors) | N |
| I-205 | behavioural signals (completion time, paste events, hesitation, autofill) | json | O | | bot and fraud detection | PSN `behaviors` | N |
| I-206 | fraud-vendor session id | string | O | | correlate vendor scores | GAL `riskContextId` | N |
| I-207 | authentication binding (JWT subject) | string | O | | link application to login | UNT `jwtSubject` | P (applicant token) |
| I-208 | UTM / attribution | json | O | | | | Y |
| I-209 | device risk score and attestation | score | O | | | | P (on customer_device only) |

---

## 2. Documents and verification catalogue

Sources: ONF/Entrust API reference (report names, sub-results, workflow statuses, document types and file limits); UNT
v2 application documents; PSN report and verification types; MQT KYC result codes; SYN verification types and results;
MAM IdentificationDocument; GSMA idDocument.

### 2.1 Document data

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| D-001 | document type (identity) | enum | M | ONF passport, driving_licence, national_identity_card, voter_id, work_permit, unknown (residence_permit†); GSM list in 1.0 | | ONF, GSM, MAM | P (no VOTER_ID, WORK_PERMIT, VISA, MILITARY_ID, TAX_ID_CARD, SSN_CARD) |
| D-002 | document type (application requirement) | enum | M | UNT AddressVerification, IdDocument, CertificateOfIncorporation, SocialSecurityCard (EmployerIdentificationNumberConfirmation†, PowerOfAttorney†, ClientRequested†) | | UNT | P |
| D-003 | document subtype / version / class | string | O | e.g. "passport card", "learner licence", "enhanced DL" | | ONF | N |
| D-004 | document number | string | M | | | all | Y |
| D-005 | personal number on the document (second number) | string | O | | national-ID cross-check | ONF extraction | P (`extracted_data`) |
| D-006 | issuing country | ISO alpha-3 (ONF) / alpha-2 | M | | | ONF, MAM, GSM | Y |
| D-007 | issuing subdivision | US state etc. | C (US DL) | | DL data verification | ALY `document_license_state`†, ONF `us_driving_licence` | N |
| D-008 | issuing authority | string | O | | | MAM, GSM | N (on identifier only) |
| D-009 | issue date / expiry date | date | M | | expiry refresh | all | Y |
| D-010 | side | enum | C | front, back | | ONF | Y (front / back ids) |
| D-011 | extra pages / images [] | file refs | O | | multi-page documents | MAM `attachments[]` | N |
| D-012 | file constraints | mime, size, resolution | M | ONF: jpg, png, pdf; 32 KB-10 MB; ≤64 MP | | ONF | P (document-service) |
| D-013 | capture method and SDK | enum + string | O | LIVE_CAPTURE, UPLOAD, SCAN, AGENT_CAPTURE | fraud (uploads are riskier) | ONF / PSN SDKs | N |
| D-014 | MRZ lines | string ×2-3 | C | | | ONF extraction† | P (`extracted_data`) |
| D-015 | NFC chip data and authentication | DG1 / DG2, passive / active / chip authentication result | O | | high-assurance ID | ONF NFC† | N |
| D-016 | barcode (PDF417) data | json | C (US DL) | | | ONF / ALY† | N |
| D-017 | extracted holder data (name, DOB, gender, nationality, place of birth, address) | structured | M | | comparison with declared data | ONF document report `properties` | P (unstructured json) |
| D-018 | field-level comparison with declared data | per-field match | M | MATCH, PARTIAL, NO_MATCH, NOT_CHECKED | | ONF `data_comparison` | N |
| D-019 | translation / certified copy / certifier | file + person | C | | non-face-to-face, UK JMLSG | REG | N |
| D-020 | retention-until date | date | M | | CIP keeps records 5 years after closure | REG CIP | N |
| D-021 | document status | enum | M | UNT Required, Uploaded, Invalid, Approved, Rejected (ReceivedBack†) | | UNT | P (no REQUIRED / INVALID) |
| D-022 | document description / request reason | text | O | | told to the applicant | UNT `description` | N |

### 2.2 Verification checks and results

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| D-030 | report / check name | enum | M | ONF: document, facial_similarity_photo, facial_similarity_motion, facial_similarity_video, known_faces, identity_enhanced, watchlist_standard, watchlist_aml, watchlist_enhanced, watchlist_peps_only, watchlist_sanctions_only, proof_of_address, right_to_work, device_intelligence, us_driving_licence, india_pan, driver_license_data_verification, repeat_attempts | | ONF | P (`KycCheckType` has 12 values) |
| D-031 | verification type | enum | M | SYN: addressrisk, alertlist, decision, documentverification, emailrisk, fraud, kyc, phonerisk, social, synthetic, watchlistplus, watchlistpremier, watchliststandard | | SYN | P |
| D-032 | PSN verification / report types | enum | O | verification/government-id, verification/selfie, verification/database, verification/document, verification/phone-number, verification/email-address†; reports: Address Lookup, Adverse Media, Business Adverse Media, Business Watchlist, Crypto Address Watchlist, Email Risk, Phone Risk, Politically Exposed Person, Watchlist | | PSN | P |
| D-033 | check status (process state) | enum | M | ONF check: in_progress, awaiting_applicant, complete, withdrawn, paused, reopened† | | ONF | P (process state and result are mixed) |
| D-034 | check result | enum | M | ONF clear, consider (report status: clear / consider / reject / withdrawn); SYN ACCEPTED, PROVIDER_FAILURE, PROVISIONAL, REJECTED, REVIEW; MQT success, failure, pending; TPR ok, error, pending | | all | P |
| D-035 | sub-result | enum | C | ONF clear, rejected, suspected, caution | separates fraud from quality failures | ONF | N |
| D-036 | breakdowns / sub-breakdowns | tree | M | ONF document: image_integrity (supported_document, image_quality), visual_authenticity (fonts, security_features, face_detection, ...), data_validation (document_numbers, MRZ, expiry_date, ...), data_consistency (document_type, ...), data_comparison, police_record, compromised_document, age_validation | explainable decision | ONF | N |
| D-037 | liveness type | enum | M | PHOTO, MOTION, VIDEO; PASSIVE, ACTIVE | | ONF facial_similarity_* | N |
| D-038 | face-match score | 0..1 | M | | | ONF, PSN | P (generic `score`) |
| D-039 | duplicate face / known faces | result + matched applicant ids | O | | synthetic ID and multi-accounting | ONF `known_faces`, `repeat_attempts` | N |
| D-040 | presentation / injection / deepfake detection | result | O | | | (vendors) | N |
| D-041 | database verification match levels | per element: FULL, PARTIAL, NONE (name, DOB, address, SSN, phone) | M | | CIP non-documentary | ALY / Socure / IDology†, ONF `identity_enhanced` | N |
| D-042 | SSN verification (eCBSV) | result | O | | synthetic ID | (SSA eCBSV) | N |
| D-043 | synthetic-identity score | score | O | | | SYN `synthetic` | N |
| D-044 | provider reason codes | code[] | M | MQT user: AddressIssue, DateOfBirthIssue, Denied KYC, DocumentIssue, EmailIssue, NameIssue, NoRecordFound, OFACFailure, PhoneIssue, RiskIssue, SSNIssue, warm address alert, Watchlist; MQT business: AddressIssue, BusinessNameIssue, OFACFailure, RegistrationIssue, Sanctions List Non-OFAC, TINIssue, Watchlist | | MQT, SYN `vendor_info` | Y (`reason_codes` raw) |
| D-045 | normalised reason-code catalogue | table | M | | provider-agnostic decisions | (needed for multi-provider) | N |
| D-046 | provider score / confidence | number | O | | | all | Y |
| D-047 | provider reference, raw response, cost | | M | | | | Y |
| D-048 | manual override with external reference | bool + notes + ref ≤32 | O | | third-party KYC reliance | MQT `manual_override`, `reference_id`, `notes` | P (accept / reject actions; no reliance reference) |
| D-049 | reliance on third-party CDD | provider, date, attestation | C | | CIP reliance; MLR reg. 39 | REG | N |
| D-050 | workflow-run status | enum | M | ONF awaiting_input, processing, approved, declined, review, abandoned, error | orchestrated flows | ONF Studio | P |
| D-051 | KYC status rollup | enum | M | SYN ACCEPTED, PENDING, PROVIDER_FAILURE, PROVISIONAL, REJECTED, REVIEW, UNVERIFIED | | SYN | Y (`KycProfileStatus`, no PROVISIONAL) |
| D-052 | consent captured for the check | bool + ip | M | | | SYN `customer_consent`, `customer_ip_address` | N |
| D-053 | proof-of-address recency rule | days | M | typically ≤90 days | | (bank practice) | N |
| D-054 | right-to-work result | enum | O | | UK employers / HR products | ONF | N |

---

## 3. Business / KYB catalogue

### 3.1 Entity identity

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-001 | legal name | string ≤128 (MQT KYC) | M | | CIP | all | Y |
| B-002 | DBA / trade names [] | string[] | O | | screening, descriptor | UNT `dba`, TPR `dba`, MQT `business_name_dba`, SYN `trade_names[]` | P (one value) |
| B-003 | entity type / structure | enum | M | UNT: LLC, Partnership, PubliclyTradedCorporation, PrivatelyHeldCorporation, NotForProfitOrganization, Estate, Trust, ForeignFinancialInstitution, DomesticFinancialInstitution, GovernmentEntityOrAgency, ReligiousOrganization, Charity. TPR: ccorp, corp, estate, foreign_entity, llc, llp, lp, nonprofit, partnership, scorp, soleprop, trust, municipality. MQT: LLC, CORPORATION, SOLE_PROPRIETORSHIP, PARTNERSHIP, COOPERATIVE, OTHER, FINANCIAL_OR_CREDIT_INSTITUTION, FOUNDATION, ASSOCIATION, CHARITY, TRUST. SYN: CORPORATION, LLC, NON_PROFIT, OTHER, PARTNERSHIP, SOLE_PROPRIETORSHIP, S_CORPORATION. STR business_type: company, government_entity, individual, non_profit; company.structure†: llc, multi_member_llc, single_member_llc, private_corporation, public_corporation, private_partnership, limited_liability_partnership, incorporated_non_profit, unincorporated_non_profit, unincorporated_association, sole_proprietorship, government_instrumentality, tax_exempt_government_instrumentality, governmental_unit, registered_charity, free_zone_llc, free_zone_establishment, sole_establishment, public_company, private_company, ... | CDD exclusions, risk | all | P (`CustomerType` has 8 values; `legal_form` is free text) |
| B-004 | legal form code | ISO 20275 ELF code | O | | cross-border normalisation; GLEIF | (LEI ecosystem) | N |
| B-005 | registration number | string | M | | | all | Y |
| B-006 | registrar / registry name | string | C | | which registry to query | STR `tax_id_registrar`, REG AMLR | N |
| B-007 | jurisdiction country | ISO | M | | | | Y |
| B-008 | jurisdiction subdivision (state of incorporation / formation) | 2-3 chars | M (US) | | US registrations are per state | UNT `stateOfIncorporation`, TPR `incorporation_state`, MQT `state_of_incorporation`, SYN `formation_state` | N |
| B-009 | incorporation / formation date | date | M | | young-company risk | SYN `formation_date`, TPR `established_on`, MQT `date_established`, STR `registration_date`† | Y |
| B-010 | is_incorporated (sole prop) | bool | C | | | UNT | P |
| B-011 | registry status | enum | C | ACTIVE, DISSOLVED, IN_LIQUIDATION, STRUCK_OFF, DORMANT | KYB check output | SYN status (DISSOLVED, MERGED, CONVERTED) | N |
| B-012 | EIN / TIN | string | M (US) | | | UNT `ein`, TPR `tin`, MQT `taxpayer_id`, SYN `ein`, STR `tax_id` | Y (identifier) |
| B-013 | VAT number | string | C | | | STR `vat_id` | Y |
| B-014 | LEI | 20 chars | C | | | REG AMLR | Y |
| B-015 | DUNS | 9 digits | O | | | MQT `duns_number` | N (no identifier type) |
| B-016 | GIIN (FATCA FFI) | 19 chars | C | | FFI status | REG FATCA | N |
| B-017 | international id + type | string ≤30 | C | | non-US entities | TPR `identification_number(_type)` | Y |
| B-018 | name / address registered under | string / address | O | | | MQT `name_registered_under`, `address_registered_under` | N |

### 3.2 Activity, industry, size

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-020 | NAICS code and description | 2-6 digits + text | M (TPR / MQT) | | risk; MQT uses it for `general_business_description` | TPR `naics`, `naics_description` | P (one `industry_code`, no scheme) |
| B-021 | MCC | 4 digits | M (STR) | | card acquiring and issuing restrictions | STR `business_profile.mcc` | N |
| B-022 | SIC | 4-5 digits | O | | UK Companies House | REG | N |
| B-023 | NACE / ISIC | code | O | | EU statistics | REG | P |
| B-024 | business industry / vertical (provider taxonomy) | enum | M (UNT) | UNT v2: TechnologyMediaOrTelecom, GroceryStoresOrSupermarkets, ...; v1 `businessVertical`†: AdultEntertainmentDatingOrEscortServices, AgricultureForestryFishingOrHunting, ArtsEntertainmentAndRecreation, BusinessSupportOrBuildingServices, Cannabis, Construction, DirectMarketingOrTelemarketing, EducationalServices, FinancialServicesCryptocurrency, FinancialServicesDebitCollectionOrConsolidation, FinancialServicesMoneyServicesBusinessOrCurrencyExchange, FinancialServicesOther, FinancialServicesPaydayLending, GamingOrGambling, HealthCareAndSocialAssistance, HospitalityAccommodationOrFoodServices, LegalAccountingConsultingOrComputerProgramming, Manufacturing, Mining, Nutraceuticals, PersonalCareServices, PublicAdministration, RealEstate, ReligiousCivicAndSocialOrganizations, RepairAndMaintenance, RetailTrade, TechnologyMediaOrTelecom, TransportationOrWarehousing, Utilities, WholesaleTrade | high-risk vertical gating | UNT | N |
| B-025 | nature of business / product description | text ≤255 | M | | FATF R.10; STR website match | UNT `businessDescription`, TPR `description`, MQT `general_business_description`, STR `product_description` | N |
| B-026 | website(s) | URL[] | M (STR, UNT; null if none) | | | UNT `website`, TPR `urls[]`, MQT, SYN, STR `url` | Y (one) |
| B-027 | website review result | enum | C | | STR website requirements per capability | STR | N |
| B-028 | support phone / email / URL / address | contact | O | | | STR `support_*` | N |
| B-029 | countries of operation | ISO[] | M (UNT) | | geography risk | UNT `countriesOfOperation`, MQT `international_office_locations` | N |
| B-030 | US nexus | enum[] | C | UNT NotAvailable, ... | non-US entities | UNT `usNexus` | N |
| B-031 | annual revenue (amount, currency, fiscal year end) | amount + date | C | | | STR `annual_revenue{amount, currency, fiscal_year_end}` | P (no currency or FYE) |
| B-032 | annual revenue band | enum | C | UNT v1†: UpTo250k, Between250kAnd500k, Between500kAnd1m, Between1mAnd5m, Over5m | | UNT | N |
| B-033 | monthly estimated revenue | amount | C | | | STR `monthly_estimated_revenue` | N |
| B-034 | number of employees / band | int / enum | C | UNT v1†: One, Between2And5, Between5And10, Between10And50, Between50And100, Between100And500, Over500 | | STR `estimated_worker_count`, UNT | Y (int) |
| B-035 | cash-flow predictability | enum | O | UNT v1†: Unpredictable, Predictable | | UNT | N |
| B-036 | business source of funds | enum | M | UNT adds SalesOfGoods, GovernmentContractsOrGrants, DonationsOrFundraising, ImportExportRevenue | | UNT | P (text) |
| B-037 | business account purpose | enum | M | UNT: RetailSalesInPerson, EcommerceSales, CashHeavyIncomeAndOperations, ProfessionalServicesHandlingFunds, CharitableOrNonProfitOrganizationOperations, CommercialCashOperations, ThirdPartyPaymentProcessing | | UNT | N |
| B-038 | business transaction volume band | enum | M | UNT business: Between1KAnd5K, Between5KAnd15K, Between15KAnd30K, Between30KAnd60K, Between60KAnd150K, Between150KAnd1M, GreaterThan1M (description required above $1M); sole prop: LessThan5K … GreaterThan150K | | UNT | N |
| B-039 | business history / in current location since | text / date | O | | | MQT `history`, `in_current_location_since` | N |
| B-040 | high-risk activity flags | enum[] | C | MSB, CRYPTO, GAMBLING, CANNABIS (SYN LICENSED_CANNABIS), ADULT, ATM_OPERATOR, PAYMENT_PROCESSOR, ARMS, PRECIOUS_METALS | prohibited / restricted business policy | SYN `compliance_restrictions` | N |
| B-041 | minority-owned designation | enum | O | | | STR | N |
| B-042 | export licence and purpose code | string | O | | India exports | STR `export_license_id`, `export_purpose_code` | N |

### 3.3 Listing, regulation, licences, tax classification

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-050 | publicly traded | bool | M | | CDD exclusion (31 CFR 1010.230(e)) | TPR `is_publicly_traded`, MQT `is_public` | N |
| B-051 | stock exchange and ticker | string | C | | | UNT `stockExchangeName`, `stockSymbol`; MQT `stock_symbol` | N |
| B-052 | regulated entity and regulator name | bool + string | M | | CDD exclusion; simplified DD | UNT `isRegulated`, `regulatorName`; MQT `is_regulated_entity` | N |
| B-053 | licences (type, number, authority, jurisdiction, issued, expiry) | rows | C | MSB_STATE_LICENCE, FINCEN_MSB_REG, TRADE_LICENCE, ... | | STR `company_license` (UAE), UNT | N |
| B-054 | FATCA / CRS entity classification | enum | M | FATCA: PARTICIPATING_FFI, REGISTERED_DEEMED_COMPLIANT, ACTIVE_NFFE, PASSIVE_NFFE, EXCEPTED_NFFE, ...; CRS: FI, ACTIVE_NFE, PASSIVE_NFE, GOVERNMENT_ENTITY, ... | W-8BEN-E / CRS-E | REG | N |
| B-055 | controlling-person type (CRS) | enum | C | CRS801-CRS813 (e.g. CRS801 legal person by ownership, CRS802 by other means, CRS803 senior managing official, CRS804-807 trust settlor / trustee / protector / beneficiary, ...) | | REG CRS XML | N |

### 3.4 Business addresses and contacts

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-060 | registered address | address | M | | | all | Y |
| B-061 | operating / principal place of business | address | C | UNT: required when the registered address is a registered-agent address or when officers / UBOs are non-US | CIP principal place of business | UNT `operatingAddress`, MQT `office_location` | Y (BUSINESS / TRADING) |
| B-062 | registered-agent flag | bool | C | | | UNT | N |
| B-063 | business phone / email | | M | STR: must be a US phone for US companies | | | Y |
| B-064 | primary contact | {full_name, title, email, phone, mobile, fax, department, extension} | C | | servicing | UNT `contact`, MQT `primary_contact` | N (no CONTACT_PERSON relationship) |

### 3.5 Ownership and control

| ID | Field | Type / format | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-070 | beneficial owners ≥25% (persons) | person[] with % | M | | CDD ownership prong; AMLR; MLR | UNT `beneficialOwners[]`, MQT `beneficial_owner1..4`, STR `owners`, TPR `ownership_percentage` | Y |
| B-071 | ownership percentage | number 0-100, decimals (MQT) | M | | | all | Y |
| B-072 | indirect ownership and chain | % + path | C | | | REG AMLR | Y (no path) |
| B-073 | voting-rights % (separate from equity) | number | C | | UK PSC, AMLR | REG | N |
| B-074 | nature of control | enum[] | C | UK PSC: ownership-of-shares 25-50 / 50-75 / 75-100 %, voting-rights bands, right-to-appoint-and-remove-directors, significant-influence-or-control (also as trust or firm) | | REG MLR / Companies House | P (free text) |
| B-075 | nominee shareholder / director flag | bool | C | | AMLR nominee disclosure | REG AMLR | N |
| B-076 | control person (single individual) | person | M (US) | | CDD control prong | REG CDD, TPR role `control_person` | N (no relationship type) |
| B-077 | officers / executives with title | person + enum | M | UNT titles: CEO, COO, CFO, President, BenefitsAdministrationOfficer, CIO, VP, AVP, Treasurer, Secretary, Controller, Manager, Partner, Member | | UNT `officer`, STR `executives`, `relationship.executive` | P (free `position_title`, no EXECUTIVE type) |
| B-078 | directors | person[] | C | | | STR `directors`, REG AMLR | Y |
| B-079 | representative / account opener | person | M | | the certifying individual | STR `representative`, REG CDD App. A | N |
| B-080 | authoriser | person | C | | | STR `relationship.authorizer` | N |
| B-081 | authorised signatories and mandate | person + rule | C | | | TPR role `signer` | Y |
| B-082 | senior managing official (fallback UBO) | person | C | | when no UBO is identified (AMLR; MLR reg. 28(6)) | REG | N |
| B-083 | trust roles: settlor, trustee, beneficiary, protector, class of beneficiaries | person / text | C | | AMLR Art. 58; MLR | REG | P (no PROTECTOR, no class) |
| B-084 | owners / directors / executives provided | bool ×3 | M (STR) | | completeness attestations | STR `company.owners_provided` etc. | N |
| B-085 | no-beneficial-owner attestation | bool | C | | | UNT `attestedNoBeneficialOwners` | N |
| B-086 | ownership declaration (date, ip, user_agent) | ts + ip + ua | M (STR) | | CDD certification evidence | STR `company.ownership_declaration` | N |
| B-087 | attestation (consent, attester name, title, date) | bool + strings + ts | M (MQT US) | | CDD Appendix A | MQT `attestation_consent`, `attester_name`, `attester_title`, `attestation_date` | N |
| B-088 | ownership exemption reason | enum | C | STR: qualified_entity_exceeds_ownership_threshold, qualifies_as_financial_institution | CDD exclusions | STR | N |
| B-089 | BO-register cross-check / discrepancy report | record | C | | MLR reg. 30A; AMLD5 Art. 30(4); AMLR | REG | N |
| B-090 | parent businesses with % | entity[] | C | | | TPR `parent_businesses` | Y (PARENT_COMPANY) |
| B-091 | per-person roles in the application | enum[] | M | TPR business: signer, control_person, debit_card_holder; TPR account: owner, signer, minor, authorized_user, debit_card_holder | product roles vs KYC roles | TPR | N |
| B-092 | per-person KYC status | enum | M | UNT officer / BO `status` (Approved, ...) | each party verified | UNT | N |
| B-093 | configurable UBO threshold | % | M | 25% default; 10% for high risk (AMLR allows lowering) | | REG | N |

### 3.6 KYB documents

| ID | Document | Type | Req. | Enum values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| B-100 | certificate of incorporation / formation | doc | M | | CIP documentary method | UNT, REG CIP | Y |
| B-101 | articles / memorandum of association, bylaws | doc | C | | | STR `company_memorandum_of_association` | Y (articles) |
| B-102 | register of members / shareholders | doc | C | | | | Y |
| B-103 | register of directors | doc | C | | | | N |
| B-104 | certificate of good standing | doc | C | | | | N |
| B-105 | operating agreement (LLC) / partnership agreement | doc | C | | | | N |
| B-106 | trust deed | doc | C | | | | N |
| B-107 | EIN confirmation letter (IRS CP-575 / 147C) | doc | C | | | UNT† | N |
| B-108 | business / trade licence | doc | C | | | STR `company_license` | N |
| B-109 | proof of registration / registry extract | doc | C | | | STR `proof_of_registration`† | N |
| B-110 | board resolution / mandate | doc | C | | | | Y |
| B-111 | financial statements / tax return | doc | C | | | | N |
| B-112 | proof of business address | doc | C | | | | N |
| B-113 | power of attorney | doc | C | | | UNT† | N |
| B-114 | beneficial ownership certification form | doc | M (US) | | 1010.230 Appendix A | REG | N |
| B-115 | W-9 / W-8BEN-E / CRS entity self-certification | doc | M | | | REG | N |
| B-116 | bank account ownership verification | doc / check | C | | | STR `bank_account_ownership_verification` | P (check type only) |

---

## 4. Customer risk rating catalogue

| ID | Field / capability | Type | Req. | Values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| R-001 | risk level | enum | M | SYN LOW, HIGH, ...; ours LOW, MEDIUM, HIGH, PROHIBITED | | SYN CRR, all banks | Y |
| R-002 | risk score | int | M | ours 0..1000; SYN `risk_score`, `calculated_score` | | SYN | Y |
| R-003 | factor contributions | json | M | | explainability | | Y |
| R-004 | model version | string | M | | model governance | | Y |
| R-005 | manual override, reason, approver | enum + text + user | M | | | | P (no approver) |
| R-006 | valid_from / valid_to | ts | M | | point-in-time rating | SYN CRR | N |
| R-007 | external scores | {score 0-100, high_risk_override, description, metadata} | O | | partner and bank inputs | SYN external scores | N |
| R-008 | risk-factor catalogue | table | M | geography (residence, nationality, birth, operations, counterparties); customer type and legal form; occupation / industry; PEP; adverse media; product; channel (non-face-to-face); delivery; expected volume and cash; SoF / SoW; ownership complexity, nominees, bearer shares; tenure; behaviour; screening hits; prior SAR / STR | FATF RBA; EBA ML/TF Risk Factors GL (EBA/GL/2021/02) | REG | P (factors are ad hoc json) |
| R-009 | country-risk list | table | M | FATF call for action; FATF increased monitoring; EU high-risk third countries (Del. Reg. 2016/1675 as amended); sanctions; CPI-based | | REG | N |
| R-010 | prohibited / restricted industries list | table | M | | | (bank policy) | N |
| R-011 | review frequency per risk level | config | M | typically HIGH 1y, MEDIUM 2-3y, LOW 3-5y | ongoing DD | REG MLR reg. 27(8) | N (only `next_review_date`) |
| R-012 | EDD triggers | config | M | PEP; high-risk third country (MLR reg. 33(1)(b)); complex / unusual transactions; correspondent; adverse media; high score | | REG | N |
| R-013 | EDD record | checklist + evidence | M | SoW / SoF evidence, senior-management approval, enhanced monitoring, site visit, purpose verified | | REG FATF R.12, MLR reg. 33 / 35 | P (flag + text) |
| R-014 | inherent vs residual risk | score ×2 | O | | | | N |
| R-015 | additional levels | enum | O | VERY_HIGH, UNACCEPTABLE | | | N |
| R-016 | rating approval (maker-checker on override / downgrade) | workflow | C | | | | N |
| R-017 | SAR / STR history as factor | ref | C | | | | P |
| R-018 | exit / de-risking decision and reason | record | C | | | | P (review outcome) |
| R-019 | customer due-diligence level | enum | M | SIMPLIFIED, STANDARD, ENHANCED | which requirements apply | REG AMLR (SDD and EDD articles) | P (bool `edd_required`) |
| R-020 | rating trigger | enum | M | | | | Y |

---

## 5. Onboarding application lifecycle catalogue

### 5.1 Provider status models (verbatim)

| Provider | Object | Statuses / outcomes |
|---|---|---|
| UNT | application | AwaitingDocuments (fetched); Pending, PendingReview, Approved, Denied, Canceled (†); officer / BO status Approved |
| TPR | account_application | unsubmitted, submitted, canceled, kyc, processing, manual_review, rejected, approved |
| TPR | kyc evaluation | provider_result.outcome: ok, error, pending |
| PSN | inquiry | created, pending, completed, failed, expired, needs_review, approved, declined (events inquiry.created, .started, .completed, .failed, .expired, .approved, .marked-for-review, .declined) |
| ONF | workflow run | awaiting_input, processing, approved, declined, review, abandoned, error |
| SYN | verification_status | ACCEPTED, PENDING, PROVISIONAL, REJECTED, REVIEW, UNVERIFIED (+ PROVIDER_FAILURE on results) |
| SYN | person.status | ACTIVE, DECEASED, DENIED, DORMANT, ESCHEAT, FROZEN, INACTIVE, PROSPECT, SANCTION |
| SYN | business.status | ACTIVE, CANCELLED, CONVERTED, DISSOLVED, FROZEN, INACTIVE, MERGED, PROSPECT, SANCTION, SUSPENDED |
| MQT | KYC result / user | result: success, failure, pending; user.status: UNVERIFIED, LIMITED, ACTIVE, SUSPENDED, CLOSED, TERMINATED |
| ALY | evaluation outcome | Approved, Denied, Manual Review (Resubmit Document; journey statuses pending_documents / pending_review / completed †) |
| STR | requirements | currently_due, eventually_due, past_due, pending_verification, alternatives, errors, current_deadline, disabled_reason (e.g. requirements.past_due) |
| MAM | client.state | PENDING_APPROVAL, INACTIVE, ACTIVE, EXITED, BLACKLISTED, REJECTED |
| GSM | kycVerificationStatus | verified, unverified, rejected (†) |
| Ours | OnboardingApplicationStatus | STARTED, IN_PROGRESS, SUBMITTED, UNDER_REVIEW, REFERRED, APPROVED, REJECTED, ABANDONED, EXPIRED |

### 5.2 Lifecycle fields and capabilities

| ID | Field / capability | Type | Req. | Values | Purpose | Providers | Ours |
|---|---|---|---|---|---|---|---|
| L-001 | application status | enum | M | see 5.1 | | all | Y |
| L-002 | awaiting-documents / pending-information state | enum value | M | AWAITING_DOCUMENTS / AWAITING_INFORMATION | applicant action needed | UNT, ALY†, ONF awaiting_input, STR currently_due | N |
| L-003 | provisional / conditional approval | enum value + limits | C | PROVISIONAL | open with limits until docs arrive | SYN PROVISIONAL, STR capability limits | P (via tiers) |
| L-004 | cancellation by applicant or operator, with reason | status + text | C | CANCELED | | UNT†, TPR canceled | N |
| L-005 | decision (auto) | enum | M | | | | Y |
| L-006 | decision method | enum | M | AUTOMATIC, MANUAL | audit | UNT `decisionMethod`† | P (inferable) |
| L-007 | decision user / time | ref + ts | M | | | UNT `decisionUserId`† | Y |
| L-008 | decision reasons (codes) | json[] | M | | adverse-action notices (ECOA / Reg B for credit) | UNT `decisionReason`†, ALY `outcome_reasons`† | Y (unstructured) |
| L-009 | decision reason catalogue | table | M | code, customer-facing text, adverse-action flag | | | N |
| L-010 | outstanding requirements list | rows | M | {requirement, party, due_by, status, errors[{code, reason}]} | drives UI and reminders | STR requirements, UNT documents | P (`info_requests` json) |
| L-011 | requirement deadline and consequence | ts + enum | C | | e.g. restrict when past due | STR `current_deadline`, `disabled_reason` | N |
| L-012 | information request message and step codes | | M | | | | Y |
| L-013 | resubmission count / attempts per requirement | int | C | | fraud (repeat attempts) | ONF `repeat_attempts` | P (step attempts) |
| L-014 | manual review queue, assignee, comment | | M | | | | Y |
| L-015 | review SLA / due time and aging | ts | C | | ops | | N |
| L-016 | archive reason | enum | C | | | UNT† | N |
| L-017 | idle expiry and resume link | ts + link | M | | | PSN `expires-at` | Y |
| L-018 | party sub-applications for officers / UBOs, with invites | rows | M | | each party completes own KYC | TPR person_applications, UNT beneficialOwners / officer, STR persons | N |
| L-019 | joint applicants and product roles | rows | C | owner, signer, minor, authorized_user, debit_card_holder | | TPR | N |
| L-020 | separate account (product) application from party application | model | C | | reuse a verified party for a second product | TPR account_application | P (`product_ids`) |
| L-021 | account-number reservation | ref | O | | | TPR | N |
| L-022 | tags on application | map | O | | | UNT `tags` | P (utm) |
| L-023 | idempotency key on create | string | M | | | UNT `idempotencyKey` | P (offline batches only) |
| L-024 | KYC product / waterfall selection per application | ref | C | | different stacks per product / risk | TPR `kyc_product_id`, ONF workflows, PSN templates | P (journey) |
| L-025 | events per state | events | M | | | PSN, UNT webhooks | Y (no awaiting-documents event) |
| L-026 | propagation of collected data to CIF on approval | behaviour | M | | single customer view | all (the application becomes the customer) | N (see section 0) |
| L-027 | conditional field requirements | rule | M | required_if (e.g. UNT `annualIncome` required if nationality ≠ US; descriptions required above thresholds) | | UNT | N |
| L-028 | composite / repeating field types | DSL | M | ADDRESS, MONEY, MULTI_ENUM, COUNTRY_LIST, REPEATING_GROUP (UBOs, tax residencies), TIN (country-validated), SIGNATURE | | | N |
| L-029 | prefill (bureau / carrier) with provenance | behaviour | O | | conversion | SYN `ssn_source` PREFILL | N |
| L-030 | template per country / product with versioning | config | M | | | PSN inquiry-template versions, ONF workflow versions | Y (journey versions) |
| L-031 | typed field schema per template | config | M | | | PSN `fields` | Y (typed step fields) |
| L-032 | applicant redirect / return URL | string | O | | hosted flows | PSN `redirect-uri` | N |
| L-033 | reviewer comment, applicant-visible vs internal | enum | O | | | PSN `reviewer-comment` | P |
| L-034 | link to a pre-existing customer (returning customer) | ref | M | | | PSN `account-id` | Y (`customer_id`) |

---

## 6. Regulatory minimums mapped to our model

| Requirement | Source | Data points | Ours |
|---|---|---|---|
| CIP minimum (individual) | 31 CFR 1020.220(a)(2)(i) | name; DOB; residential or business street address (APO/FPO allowed, no PO box); identification number (US: TIN; non-US: one or more of TIN, passport number and country of issuance, alien ID card number, other government ID number and country of issuance) | spec Y; journey collects them; **only name and DOB reach the CIF** (G-001) |
| CIP minimum (non-individual) | 1020.220(a)(2)(i)(A)(4), (B) | name; principal place of business, local office or other physical location; TIN | Y (spec); not propagated |
| CIP verification and records | 1020.220(a)(2)(ii), (a)(3) | documentary / non-documentary method used; description of document (type, number, place of issuance, issue and expiry dates); methods and results; resolution of discrepancies; retention 5 years after closure | P: no per-element match results (G-087), no retention date (G-092), no discrepancy-resolution record (G-088) |
| CIP government lists and notice | 1020.220(a)(4), (a)(5) | terrorist-list comparison; customer notice before opening | Y (screening); notice acknowledgement N (G-050) |
| CDD beneficial ownership | 31 CFR 1010.230 + App. A | each individual with ≥25% equity; one control person; certification by the person opening the account (name, title, legal entity, signature, date); exclusions (listed, regulated, ...) | P: UBO relationships yes; no control-person role, certification or exclusion reason (G-075, G-077) |
| Purpose and intended nature | FATF R.10(c); 31 CFR 1020.210(a)(2)(v); AMLR Art. 20(1)(c) | purpose of account; expected activity for monitoring | N (G-047) |
| PEPs | FATF R.12; MLR reg. 35; AMLR | PEP determination; senior-management approval; SoW and SoF; enhanced ongoing monitoring; family and close associates | P (G-025, G-026, G-108) |
| AMLR identification data | EU 2024/1624 Art. 22(1) | natural persons: all names and surnames, place and full date of birth, nationalities (or statelessness / refugee / subsidiary-protection status), national ID number, usual residence, TIN; legal entities: legal form and name, registered office and principal place of business, country of creation, names of legal representatives, registration number, TIN, LEI, nominee holders | P (G-023, G-024, G-060) |
| UK MLR CDD and BO | MLR regs. 28, 30A, 33, 35 | identify and verify; BO with control structure; discrepancy reporting to Companies House; EDD for high-risk third countries | P (G-076, G-080, G-105) |
| CRS self-certification | OECD CRS Commentary, s. VI | name, address, jurisdiction(s) of residence, TIN per jurisdiction (or reason), DOB (individuals); entity type; controlling persons with type; signature and date; reasonableness check | N (G-027, G-028, G-067) |
| FATCA | IRS W-9 / W-8BEN / W-8BEN-E; IGA Annex I | US person status; US TIN; foreign TIN; Chapter 3 / 4 status; GIIN; indicia; form validity (W-8: through end of 3rd calendar year) | P: `us_person` only (G-028, G-036) |

---

## 7. Gap matrix

Priority: **Must** = regulatory minimum, or a field that every enterprise platform in the comparison exposes;
**Should** = widely used by the platforms or needed for multi-market or business onboarding; **Could** = niche,
local-market or nice to have. "Target" is the table that would receive the change (new tables are marked *new*).

### 7.1 Implementation and onboarding engine

| ID | Gap (field / enum / table / capability) | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-001 | Propagate all collected data to the CIF on approval: addresses, verified contacts, identifiers (with document link), consents, tax residencies, occupation and income, SoF, activity profile, relationships | Must | onboarding-service `ApplicationService.ensureCustomer` calling customer-service sub-resources | L-026. Today only 8 scalar fields are sent; the root cause of "2-3 data points" |
| G-002 | Starter-pack journeys collect the standard enterprise set (TIN, occupation, employment, income band, SoF, account purpose, expected volume, PEP self-declaration, tax residency and self-cert, KYC consent, E-SIGN, CIP notice; business: structure, state of incorporation, NAICS / MCC, description, countries of operation, UBOs and control person, certification) | Must | `starter-packs/*.yaml` | the spec already supports most customer columns; the packs never ask |
| G-003 | Conditional field rules (`required_if`, `visible_if`) in step field definitions | Must | `onboarding_journey.definition` | L-027 (e.g. Unit: income fields required when nationality ≠ US) |
| G-004 | Composite / repeating field types: ADDRESS, MONEY, MULTI_ENUM, COUNTRY_LIST, REPEATING_GROUP, TIN (country-validated), SIGNATURE | Must | `onboarding_journey.definition`, `StepField` | L-028; needed for UBO lists and tax residencies |
| G-005 | Declarative field-to-CIF mapping (`maps_to: customer.occupation`, `customer_address[RESIDENTIAL].line1`, ...) | Must | `onboarding_journey.definition` | makes G-001 configuration, not code |
| G-006 | `onboarding_requirement` table (code, kind FIELD / DOCUMENT / CHECK / ATTESTATION, party ref, status REQUIRED / SUBMITTED / ACCEPTED / REJECTED / WAIVED, due_at, errors, message, requested_by) replacing `info_requests` json | Must | *new* onboarding-service | L-010, L-011; Stripe requirements, Unit documents |
| G-007 | `OnboardingApplicationStatus` + AWAITING_INFORMATION (awaiting documents / data from the applicant) | Must | enum | L-002; Unit AwaitingDocuments, ONF awaiting_input |
| G-008 | `onboarding_application_party` table (roles[], relationship type, ownership %, title, captured person data, invite link / token, own KYC status, kyc_profile ref) for business, joint and minor applications | Must | *new* onboarding-service | L-018, L-019, B-091, B-092; TPR person_applications, Unit officer / BO |
| G-009 | Decision-reason catalogue (code, category, customer text, adverse-action flag) referenced by `decision_reasons` | Must | *new* onboarding-service (or shared with G-047) | L-009 |
| G-010 | Application CANCELLED status with cancel action and reason | Should | enum + action | L-004 |
| G-011 | PROVISIONALLY_APPROVED (open at reduced tier / limits while requirements are outstanding) | Should | enum | L-003; SYN PROVISIONAL |
| G-012 | `decision_method` AUTOMATIC / MANUAL | Should | `onboarding_application` | L-006 |
| G-013 | Review SLA (`review_due_at`) and queue name | Should | `onboarding_application` | L-015 |
| G-014 | Idempotency key on staff create and public start | Should | `onboarding_application` | L-023 |
| G-015 | Events: `party.onboarding_application.awaiting_information`, `party.onboarding_requirement.*` | Should | events | L-025 |
| G-016 | Session signals: user_agent, `device_fingerprints[]` {provider, value}, fraud_session_id, ip_risk {proxy, vpn, tor, ip_country}, behavioural signals | Should | `onboarding_application` | I-201..206 |
| G-017 | KYC waterfall / check plan per application (which checks, providers, order, fallbacks) | Should | `onboarding_journey.definition` | L-024 |
| G-018 | Per-field data provenance (DECLARED, PREFILL, DOCUMENT_OCR, REGISTRY, BUREAU; source check id; verified_at) | Should | *new* customer-service `customer_attribute_source` | L-029, I-042 |
| G-019 | Tags map on application | Could | `onboarding_application` | L-022 |
| G-020 | Redirect / return URL for hosted flows | Could | public start | L-032 |
| G-021 | Separate product (account) application reusing a verified party | Could | onboarding-service | L-020 |
| G-022 | Account-number reservation at application time | Could | onboarding-service + account-service | L-021 |

### 7.2 Individual identity (customer-service)

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-023 | `customer_name` table: name_type (LEGAL, PREFERRED, PREVIOUS, ALIAS, MAIDEN, BIRTH, NATIVE_SCRIPT, TRANSLITERATED, TRADING), script (ISO 15924), full / parts, valid_from / to, source; screening uses every row | Must | *new* customer-service | I-006..012, B-002; AMLR "all names" |
| G-024 | All nationalities as a list (country, acquired_on, source); replaces `nationality` + `second_nationality` (keep primary) | Must | *new* `customer_nationality` or `nationalities json[]` | I-025 |
| G-025 | PEP self-declaration: `pep_declaration` enum NONE, SELF, FAMILY_MEMBER, CLOSE_ASSOCIATE, plus `pep_declared_at` | Must | `customer` | I-070 (STR `political_exposure`, GAL `politicalAffiliation`) |
| G-026 | `pep_profile`: category (DOMESTIC, FOREIGN, INTERNATIONAL_ORGANISATION), position, organisation, country, start / end, related_customer_id, source (DECLARED / SCREENING), senior-management approval (by / at), declassify_after | Must | *new* kyc-service | I-071..074 |
| G-027 | `customer_tax_residency`: country, tin, tin_type, no_tin_reason (A / B / C), explanation, is_primary, valid_from / to, verified | Must | *new* customer-service | I-043..045 |
| G-028 | `tax_self_certification`: form_type (W9, W8BEN, W8BEN_E, W8IMY, W8ECI, CRS_IND, CRS_ENT, CRS_CP), signed_at, signer_customer_id, capacity, valid_until, document_id, status (VALID, EXPIRED, INVALIDATED, CURED), change_in_circumstances_at, treaty claim | Must | *new* customer-service | I-048..053, B-115 |
| G-029 | Identifier-type catalogue per country (code, country, label, regex, checksum, masking rule, is_tax_id, cip_acceptable, expiry_required) instead of a fixed enum | Must | *new* reference-data `identifier_type` | I-067; CURP, CPF, PAN, BVN, NIN, KRA PIN, SIN, NI number |
| G-030 | Add identifier types until G-029 lands: ITIN, EIN, ALIEN_REGISTRATION, WORK_PERMIT, VISA, MILITARY_ID, DUNS, GIIN | Must | `CustomerIdentifierType` | I-069, B-015, B-016 |
| G-031 | `occupation_code` (ISCO-08 or tenant catalogue) and `employment_industry_code` | Must | `customer` | I-091, I-094; risk scoring cannot use free text |
| G-032 | `source_of_funds_code` enum (SALARY, BUSINESS_INCOME, INVESTMENTS, PENSION, INHERITANCE, GIFT, SALE_OF_ASSETS, LEGAL_SETTLEMENT, LOAN, GOVERNMENT_BENEFITS, GRANTS, DONATIONS, IMPORT_EXPORT, OTHER) + keep text as description; same for `source_of_wealth_code` | Must | `customer` | I-103..105, B-036 |
| G-033 | Residency / immigration status enum + permit type, number and expiry (drives review) | Should | `customer` (+ identifier) | I-029, I-030 |
| G-034 | Stateless / refugee / subsidiary-protection status | Should | `customer` | I-026 (AMLR wording; basic-account access) |
| G-035 | `income_band` and `income_source` enums (alongside amount); `income_frequency` | Should | `customer` | I-100..102 |
| G-036 | US-indicia flags + `indicia_reviewed_at` | Should | `tax_self_certification` or `customer` | I-047 |
| G-037 | Vulnerability / accessibility flags (type, source, consent, review date) | Should | *new* `customer_vulnerability` | I-163 (FCA FG21/1, Consumer Duty) |
| G-038 | Signature specimen and photo references | Should | `customer` (document refs) | I-166 (branch / agent) |
| G-039 | `EmploymentStatus` + PART_TIME, CONTRACTOR, GIG_WORKER | Should | enum | I-092 |
| G-040 | Name suffix | Could | `customer` / `customer_name` | I-005 |
| G-041 | Father / spouse name, mother's name (country packs) | Could | `customer_name` (type) | I-013, I-014 |
| G-042 | Job title, employment start date, employer address | Could | `customer` / *new* `customer_employment` | I-095..097 |
| G-043 | Dependants, education, residential status, military status, CFPB demographics | Could | *new* `customer_demographics` | I-150, I-161, I-162, I-164, I-165 |
| G-044 | Preferred contact method | Could | `customer` | I-127 |
| G-045 | Notes on customer; loan-cycle counters (microfinance) | Could | `customer` | I-170, I-174 |
| G-046 | Net worth / liquid assets band | Could | `customer` | I-107 |

### 7.3 Expected activity, consents, contacts, addresses

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-047 | `customer_activity_profile` (versioned): account_purpose[], purpose_detail, expected_monthly_volume_band / amount, expected_tx_count, expected_cash_in / out, international (bool + countries[]), expected_rails[], initial_funding_source, other_bank_relationships, countries_of_operation (business), declared_at, declared_by | Must | *new* customer-service (input to transaction monitoring) | I-110..119, B-029, B-036..038 |
| G-048 | `AccountPurpose` enum (union of the Unit individual and business lists + SAVINGS, EVERYDAY_SPENDING, SALARY, BILL_PAYMENTS, INVESTMENT, LOAN_REPAYMENT, MERCHANT_COLLECTIONS, PAYROLL_DISBURSEMENT) | Must | enum | I-110, B-037 |
| G-049 | `VolumeBand` enum (tenant-configurable bands per currency) | Must | enum / config | I-112, B-038 |
| G-050 | `ConsentPurpose` + KYC_VERIFICATION, ESIGN, CIP_NOTICE, ACCOUNT_AGREEMENT, CARDHOLDER_AGREEMENT, FEE_SCHEDULE, ELECTRONIC_STATEMENTS, TAX_CERTIFICATION, BENEFICIAL_OWNERSHIP_CERTIFICATION | Must | enum | I-183..191 |
| G-051 | Consent captured on behalf of the customer (`given_by_customer_id`, capacity GUARDIAN / POA / REPRESENTATIVE) | Should | `consent` | I-190 |
| G-052 | `consent.user_agent` and `document_hash` as first-class columns (now inside `evidence` json) | Could | `consent` | I-180 |
| G-053 | PO-box rejection for RESIDENTIAL / REGISTERED addresses (configurable per country; APO/FPO allowed) | Must | `customer_address` validation | I-145 |
| G-054 | `CustomerAddressType` + SHIPPING, TEMPORARY | Should | enum | I-147 |
| G-055 | Address proof link (`kyc_document_id`) and `verification_method` enum | Should | `customer_address` | I-152, I-153 |
| G-056 | Structured address parts (building_number, building_name, sub_premise, street_name) and `subdivision_code` (ISO 3166-2), aligned to ISO 20022 PostalAddress | Should | `customer_address` | I-141, I-143 |
| G-057 | Registered-agent-address flag | Should | `customer_address` | I-156, B-062 |
| G-058 | Address in local script; standardisation result; "permanent shipping" flag | Could | `customer_address` | I-125, I-154, I-155 |
| G-059 | Mobile carrier / line type on phone contacts | Could | `customer_contact` | I-125 |

### 7.4 Business / KYB

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-060 | `business_structure` enum (fine-grained: LLC_SINGLE_MEMBER, LLC_MULTI_MEMBER, C_CORP, S_CORP, PRIVATE_LIMITED, PUBLIC_LIMITED, LLP, LP, GENERAL_PARTNERSHIP, SOLE_PROPRIETORSHIP, COOPERATIVE, FOUNDATION, ASSOCIATION, CHARITY, RELIGIOUS_ORG, TRUST, ESTATE, GOVERNMENT_ENTITY, MUNICIPALITY, FINANCIAL_INSTITUTION, FREE_ZONE_ENTITY, OTHER) + `legal_form_code` (ISO 20275 ELF) | Must | `customer` | B-003, B-004 |
| G-061 | `registration_subdivision` (state of incorporation / formation) | Must | `customer` | B-008 |
| G-062 | `customer_industry` (scheme NAICS / SIC / NACE / ISIC / MCC / TENANT; code; description; is_primary) replacing single `industry_code` | Must | *new* customer-service | B-020..024 |
| G-063 | `business_description` (nature of business, products, customers) | Must | `customer` | B-025 |
| G-064 | `is_publicly_traded`, `stock_exchange_mic`, `ticker` | Must | `customer` | B-050, B-051 |
| G-065 | `is_regulated`, `regulator_name`, `regulator_reference` | Must | `customer` | B-052 |
| G-066 | High-risk activity flags / compliance restrictions (MSB, CRYPTO, GAMBLING, CANNABIS, ADULT, PAYMENT_PROCESSOR, ...) | Must | `customer` (array) | B-040 |
| G-067 | FATCA / CRS entity classification + GIIN | Must | `tax_self_certification` / `customer` | B-054, B-016 |
| G-068 | Registry name, registry status, registry_checked_at (+ registry snapshot from the REGISTRY_LOOKUP check) | Should | `customer` / `kyc_check.output` | B-006, B-011 |
| G-069 | `business_licence` table (type, number, authority, jurisdiction, issued_on, expires_on, document_id, verified) | Should | *new* customer-service | B-053 |
| G-070 | Annual turnover currency, fiscal year end, revenue band, monthly estimated revenue | Should | `customer` | B-031..033 |
| G-071 | Multiple websites + website review outcome | Should | `customer` / kyc check | B-026, B-027 |
| G-072 | CRS controlling-person type on relationships | Should | `customer_relationship` | B-055 |
| G-073 | Employee band enum (in addition to int) | Could | `customer` | B-034 |
| G-074 | US nexus; name / address registered under; history; in current location since; minority-owned; export licence; cash-flow predictability; support contacts | Could | `customer` | B-018, B-028, B-030, B-035, B-039, B-041, B-042 |

### 7.5 Ownership and control

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-075 | `CustomerRelationshipType` + CONTROL_PERSON, EXECUTIVE_OFFICER, REPRESENTATIVE (account opener / certifier), AUTHORISER, SENIOR_MANAGING_OFFICIAL, PROTECTOR, NOMINEE_SHAREHOLDER, NOMINEE_DIRECTOR, GENERAL_PARTNER, LIMITED_PARTNER, MEMBER, COMPANY_SECRETARY, CONTACT_PERSON, AUTHORISED_USER, CARDHOLDER, CLOSE_ASSOCIATE, FAMILY_MEMBER, REGISTERED_AGENT | Must | enum | B-064, B-076..083, I-073 |
| G-076 | `voting_percentage`, `nature_of_control` enum[] (UK PSC natures), `is_nominee` + `nominator_customer_id` (replaces free-text `control_type`) | Must | `customer_relationship` | B-073..075 |
| G-077 | `beneficial_ownership_certification` (customer_id, certified_by_customer_id, certifier_title, certified_at, ip, user_agent, owners_provided, directors_provided, executives_provided, no_ubo_attested, exemption_reason, threshold_pct, document_id, next_recertification_at, status) | Must | *new* customer-service | B-084..088, B-114 |
| G-078 | Configurable UBO threshold (tenant default 25%; lower for high risk) used by ownership-tree | Must | kyc / tenant config | B-093 |
| G-079 | `officer_title` enum next to free `position_title` | Should | `customer_relationship` | B-077 |
| G-080 | BO-register discrepancy record (register, discrepancy, reported_at, reference) | Should | *new* kyc-service | B-089 |
| G-081 | Ownership chain path on indirect holdings (`via_customer_ids[]`) | Could | `customer_relationship` / ownership-tree | B-072 |
| G-082 | Trust: class-of-beneficiaries description | Could | `customer_relationship` | B-083 |

### 7.6 Documents and verification (kyc-service)

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-083 | `KycDocumentType` → reference table (or enum extension): VOTER_ID, WORK_PERMIT, VISA, MILITARY_ID, TAX_ID_CARD, SSN_CARD, BIRTH_CERTIFICATE, MARRIAGE_CERTIFICATE, NAME_CHANGE_DEED, PAYSLIP, TAX_RETURN, EMPLOYMENT_LETTER, SOF_EVIDENCE, SOW_EVIDENCE, LEASE_AGREEMENT, GOVERNMENT_LETTER, COMMUNITY_LETTER (village elder), CERTIFICATE_OF_GOOD_STANDING, REGISTER_OF_DIRECTORS, OPERATING_AGREEMENT, PARTNERSHIP_AGREEMENT, TRUST_DEED, BYLAWS, MEMORANDUM_OF_ASSOCIATION, EIN_LETTER, BUSINESS_LICENCE, REGISTRY_EXTRACT, FINANCIAL_STATEMENTS, POWER_OF_ATTORNEY, BO_CERTIFICATION, TAX_FORM, PROOF_OF_BUSINESS_ADDRESS, VIDEO_SELFIE | Must | enum / *new* `kyc_document_type` | D-001, D-002, B-100..116, GSM idType |
| G-084 | `KycDocumentStatus` + REQUIRED, INVALID | Must | enum | D-021 |
| G-085 | Split `kyc_check.status` (PENDING, IN_PROGRESS, AWAITING_APPLICANT, COMPLETE, WITHDRAWN, ERROR) from `result` (CLEAR, CONSIDER, REJECT, PROVISIONAL) + `sub_result` (CLEAR, CAUTION, SUSPECTED, REJECTED) + `breakdowns` json tree | Must | `kyc_check` | D-033..036 |
| G-086 | Structured extracted holder data and field-level comparison with declared data (name, DOB, address, document number, nationality: MATCH / PARTIAL / NO_MATCH) | Must | `kyc_check.breakdowns` / `kyc_document` | D-017, D-018 |
| G-087 | Database-verification element match levels (name, DOB, address, TIN, phone) kept as CIP evidence | Must | `kyc_check.breakdowns` | D-041; 1020.220(a)(3) |
| G-088 | Normalised reason-code catalogue (code, category, provider mappings, applicant text, fraud flag, default action) | Must | *new* kyc-service `kyc_reason_code` | D-044, D-045 |
| G-089 | Consent link and customer IP for each check (`consent_id`, `customer_ip`) | Must | `kyc_check` | D-052; SYN `customer_consent` required |
| G-090 | `KycCheckType` + DUPLICATE_FACE, AGE_VERIFICATION, NFC_CHIP, EMAIL_RISK, PHONE_RISK, DEVICE_RISK, IP_RISK, SYNTHETIC_IDENTITY, SSN_VERIFICATION, PROOF_OF_ADDRESS_DOCUMENT, WEBSITE_REVIEW, BUSINESS_CLASSIFICATION, REGISTERED_AGENT_CHECK | Should | enum | D-030..043 |
| G-091 | `KycProviderCapability` + FRAUD_SIGNALS, NFC, BUSINESS_CLASSIFICATION, TAX_ID | Should | enum | D-030 |
| G-092 | `kyc_document`: issuing_subdivision, issuing_authority, document_subtype, capture_method, sdk_version, retention_until, request_reason | Should | `kyc_document` | D-003, D-007, D-008, D-013, D-020, D-022 |
| G-093 | MRZ, NFC and barcode validation results | Should | `kyc_check.breakdowns` | D-014..016 |
| G-094 | Liveness type and face-match score as columns (or check subtype) | Should | `kyc_check` | D-037, D-038 |
| G-095 | Third-party CDD reliance record (provider, attestation, date, scope) | Should | `kyc_profile` / *new* `kyc_reliance` | D-048, D-049 |
| G-096 | Proof-of-address recency rule (max age in days per document type) | Should | `kyc_document_type` config | D-053 |
| G-097 | Multi-page attachments for one document | Could | `kyc_document` | D-011 |
| G-098 | Certified copy / translation / certifier details | Could | `kyc_document` | D-019 |
| G-099 | Right-to-work result; crypto-address watchlist | Could | check types | D-054, I-082 |
| G-100 | `KycProfileStatus` + PROVISIONAL | Should | enum | D-051 |

### 7.7 Screening and customer risk

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-101 | `kyc_screening_match` child table: matched_name, entity_type, DOB, countries, list, program, list_entry_id, score, match_strength, pep_class, adverse-media categories, source URLs, per-match disposition | Should | *new* kyc-service | I-077, I-078 (now an opaque `matches` json) |
| G-102 | Monitoring provider subscription id and `last_rescreened_at` | Should | `kyc_screening` | I-080 |
| G-103 | Adverse-media category enum | Should | `kyc_screening_match` | I-077 |
| G-104 | Risk model configuration: `risk_model` (versioned, maker-checker), `risk_factor` (code, input, weight, mapping tables), thresholds per level | Must | *new* kyc-service | R-008 |
| G-105 | Country-risk list (country, list sources, rating, effective dates; FATF black / grey, EU high-risk third countries, sanctions) | Must | *new* reference-data / kyc-service | R-009 |
| G-106 | Prohibited / restricted industry list (scheme + code, PROHIBITED / RESTRICTED / EDD) | Must | *new* kyc-service | R-010, G-066 |
| G-107 | Review-frequency policy per risk level and customer type (drives `next_review_date`) | Must | kyc config | R-011 |
| G-108 | EDD triggers configuration + `edd_case` (trigger, checklist, SoW / SoF evidence, senior-management approval, enhanced-monitoring flag, status) | Must | *new* kyc-service | R-012, R-013, I-074 |
| G-109 | `due_diligence_level` SIMPLIFIED / STANDARD / ENHANCED on `kyc_profile` | Must | `kyc_profile` | R-019 |
| G-110 | `kyc_risk_assessment`: valid_from / valid_to and override approver (maker-checker) | Should | `kyc_risk_assessment` | R-005, R-006, R-016 |
| G-111 | External scores (source, score 0-100, high_risk_override, description) as model inputs | Could | *new* kyc-service | R-007 |
| G-112 | Inherent vs residual risk; VERY_HIGH level | Could | `kyc_risk_assessment`, enum | R-014, R-015 |

### 7.8 API capabilities

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-113 | `POST /v1/customers/{id}/tax-residencies`, `/tax-certifications` (CRUD + `expire` job) | Must | customer-service API | G-027, G-028 |
| G-114 | `POST /v1/customers/{id}/beneficial-ownership-certifications` + `GET` latest | Must | customer-service API | G-077 |
| G-115 | `GET/PUT /v1/customers/{id}/activity-profile` (versioned) | Must | customer-service API | G-047 |
| G-116 | `GET /v1/onboarding/applications/{id}/requirements` + applicant-surface equivalent; `POST .../requirements/{rid}/fulfil` | Must | onboarding-service API | G-006 |
| G-117 | Party sub-application API: add / list parties, send invite, applicant-party token surface | Must | onboarding-service API | G-008 |
| G-118 | `POST /v1/onboarding/applications/{id}/cancel` | Should | onboarding-service API | G-010 |
| G-119 | `GET /v1/kyc/reason-codes`, `GET /v1/kyc/risk-models`, `country-risk`, `industry-risk` admin CRUD | Must | kyc-service API | G-088, G-104..106 |
| G-120 | `POST /v1/kyc/profiles/{id}/edd-cases` and approval action | Must | kyc-service API | G-108 |
| G-121 | `GET /v1/customers/{id}/names`, `/nationalities` (sub-resources) | Must | customer-service API | G-023, G-024 |
| G-122 | PEP profile API (`/v1/kyc/pep-profiles`, approve relationship) | Must | kyc-service API | G-026 |
| G-123 | Identifier validation endpoint (`POST /v1/reference/identifier-types/{code}/validate`) | Should | reference-data API | G-029 |
| G-124 | Webhooks / events for requirement changes and KYC status (customer-facing) | Should | events | G-015 |
| G-125 | Registered-agent address check (`POST /v1/kyc/address-checks`) | Could | kyc-service API | G-057 |

### 7.9 Additional enum gaps

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-126 | `CustomerType` + ESTATE, CLUB_ASSOCIATION, COOPERATIVE, FINANCIAL_INSTITUTION (or keep coarse and rely on G-060) | Should | enum | B-003 |
| G-127 | `CustomerGender` + UNKNOWN vs UNDISCLOSED distinction; ISO 5218 mapping | Could | enum | I-031 |
| G-128 | `CustomerStatus` + DENIED (rejected prospect kept for audit), FROZEN (sanctions) | Should | enum | SYN person.status, MAM REJECTED / BLACKLISTED |
| G-129 | `CustomerContactType` + SECONDARY_EMAIL label semantics; TELEGRAM / SIGNAL optional | Could | enum | I-121 |
| G-130 | `KycScreeningType` + CRYPTO_ADDRESS, BUSINESS_ADVERSE_MEDIA | Could | enum | D-032 |
| G-131 | `OnboardingStepType` + EXPECTED_ACTIVITY, PEP_DECLARATION, FATCA_CRS_SELF_CERT, PARTY_INVITE, BO_CERTIFICATION, EMPLOYMENT_INCOME, NFC_CHIP_READ | Must | enum | G-002 |
| G-132 | `KycReviewTrigger` + TAX_CERT_EXPIRY, PERMIT_EXPIRY, ADVERSE_MEDIA, OWNERSHIP_CHANGE | Should | enum | G-028, G-033 |
| G-133 | `OnboardingChannel` + PARTNER (embedded BaaS / sponsor) | Should | enum | Unit / TPR model |
| G-134 | `KycRiskLevel` + VERY_HIGH (optional) | Could | enum | R-015 |

### 7.10 Mobile-money / tiered-KYC market specifics (Comviva-class)

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-135 | Tier requirement matrix per country (which fields / docs / checks unlock TIER_1..3, plus limits) as configuration, not prose | Must | kyc / tenant config | GSM `kycLevel`; Comviva wallet types by limits |
| G-136 | Agent-assisted capture evidence: agent id, agent attestation, physical-presence flag, original-document-seen flag | Should | `onboarding_application` / `kyc_document` | Comviva agent KYC forms |
| G-137 | SIM / MSISDN registration cross-check (national SIM registry) | Should | check type | KE / NG / TZ practice |
| G-138 | Alternative ID documents for financial inclusion (community / village-elder letter, employer letter) with tier caps | Should | `kyc_document_type` | GSM idType list |
| G-139 | Biometric enrolment reference (fingerprint template id from national ID system) | Could | `customer_identifier` / kyc | GSMA biometrics use case |

### 7.11 Remaining Could items

| ID | Gap | Priority | Target | Notes / refs |
|---|---|---|---|---|
| G-140 | Treaty-benefit claim on W-8BEN | Could | `tax_self_certification` | I-051 |
| G-141 | Masked last-4 value on identifiers (reduced-collection flows) | Could | `customer_identifier` | I-041 |
| G-142 | Identifier place of issue | Could | `customer_identifier` | I-065 |
| G-143 | Prefill provenance on identifiers (`ssn_source`) | Could | `customer_identifier` | I-042 (covered by G-018 if built) |
| G-144 | Client role / per-type field requirements (Mambu-style) | Could | customer-service config | I-172 |
| G-145 | Deepfake / injection-attack detection result | Should | check type | D-040 |
| G-146 | eCBSV SSN verification | Could | check type | D-042 |
| G-147 | Duplicate-face detection across customers | Should | check type | D-039 |
| G-148 | Authentication binding (`jwt_subject`) of application to a login | Could | `onboarding_application` | I-207 |

---

## 8. Counts

| Area | Catalogued rows |
|---|---|
| 1. Individual identity (I-xxx) | 162 |
| 2. Documents and verification (D-xxx) | 47 |
| 3. Business / KYB (B-xxx) | 93 |
| 4. Customer risk rating (R-xxx) | 20 |
| 5. Lifecycle fields and capabilities (L-xxx) | 34 |
| 5.1 Provider status models | 14 |
| 6. Regulatory requirement rows | 11 |
| **Total** | **381** |

Coverage of the 356 field rows in sections 1-5: Y (present) = 105, P (partial) = 65, N (absent) = 186.

| Gap priority | Count |
|---|---|
| Must | 60 |
| Should | 52 |
| Could | 36 |
| **Total gaps** | **148** |

### 8.1 Suggested sequencing

1. **Fix the data path first** (G-001, G-005, G-002): without it, new columns do not help the "2-3 data points" problem.
2. **Regulatory Musts**: tax residency and self-certification, PEP declaration and profile, activity profile, BO
   certification and control person, PO-box rule, CIP evidence (G-087), KYC consent link.
3. **Business onboarding**: party sub-applications, requirements model, AWAITING_INFORMATION, KYB fields (structure,
   state, industry table, description, listed and regulated status).
4. **Risk configuration**: risk model, country and industry lists, review frequency, EDD case.
5. **Verification depth**: check status / result split, breakdowns, reason-code catalogue, document-type table.
