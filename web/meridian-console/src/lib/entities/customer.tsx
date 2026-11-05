import type { FormSection, FormValues } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** party.yaml enums (kept in sync with spec/domains/party.yaml). */
export const CUSTOMER_TYPE = ["INDIVIDUAL", "SOLE_TRADER", "COMPANY", "PARTNERSHIP", "TRUST", "NON_PROFIT", "GOVERNMENT", "GROUP"];
export const GENDER = ["MALE", "FEMALE", "OTHER", "UNDISCLOSED"];
export const MARITAL = ["SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "SEPARATED", "CIVIL_PARTNERSHIP", "UNDISCLOSED"];
export const EMPLOYMENT = ["EMPLOYED", "SELF_EMPLOYED", "UNEMPLOYED", "STUDENT", "RETIRED", "HOMEMAKER", "OTHER"];
export const ONBOARDING_CHANNEL = ["MOBILE_APP", "WEB", "USSD", "AGENT", "BRANCH", "API"];
export const TITLE = ["Mr", "Mrs", "Ms", "Miss", "Dr", "Prof"];

const PERSON_TYPES = ["INDIVIDUAL", "SOLE_TRADER"];
const ORG_TYPES = ["SOLE_TRADER", "COMPANY", "PARTNERSHIP", "TRUST", "NON_PROFIT", "GOVERNMENT", "GROUP"];
const COMPANY_TYPES = ["COMPANY", "PARTNERSHIP", "TRUST", "NON_PROFIT", "GOVERNMENT", "GROUP"];
const isPerson = (v: FormValues) => PERSON_TYPES.includes(v.customer_type);
const isOrg = (v: FormValues) => ORG_TYPES.includes(v.customer_type);
const isCompany = (v: FormValues) => COMPANY_TYPES.includes(v.customer_type);

/** The full customer onboarding field set — every writable column on party.customer. */
export const customerFormSections: FormSection[] = [
  {
    title: "Classification",
    description: "Who this party is and which part of the bank owns the relationship.",
    fields: [
      { key: "customer_type", label: "Customer type", type: "select", options: CUSTOMER_TYPE, required: true },
      { key: "legal_entity_id", label: "Legal entity", type: "text", required: true, hint: "Bank entity holding the relationship" },
      { key: "home_branch_id", label: "Home branch", type: "text" },
      { key: "relationship_manager_id", label: "Relationship manager", type: "text" },
      { key: "onboarding_channel", label: "Onboarding channel", type: "select", options: ONBOARDING_CHANNEL },
      { key: "referral_code", label: "Referral / campaign code", type: "text" },
      { key: "external_ref", label: "External reference", type: "text", hint: "Core migration / partner id" },
      { key: "is_staff", label: "Bank staff", type: "bool", hint: "Employee of the bank (staff pricing & monitoring)" },
    ],
  },
  {
    title: "Name",
    fields: [
      { key: "title", label: "Title", type: "select", options: TITLE, when: isPerson },
      { key: "first_name", label: "First name", type: "text", required: true, when: isPerson },
      { key: "middle_name", label: "Middle name(s)", type: "text", when: isPerson },
      { key: "last_name", label: "Last name", type: "text", required: true, when: isPerson },
      { key: "name_local_script", label: "Name (local script)", type: "text", when: isPerson, hint: "e.g. Arabic, Cyrillic" },
      { key: "display_name", label: "Display name", type: "text", span: 2, hint: "Defaults to first + last / legal name when blank" },
    ],
  },
  {
    title: "Personal details",
    fields: [
      { key: "date_of_birth", label: "Date of birth", type: "date", when: isPerson },
      { key: "place_of_birth", label: "Place of birth", type: "text", when: isPerson },
      { key: "country_of_birth", label: "Country of birth", type: "country", when: isPerson },
      { key: "gender", label: "Gender", type: "select", options: GENDER, when: isPerson },
      { key: "marital_status", label: "Marital status", type: "select", options: MARITAL, when: isPerson },
    ],
  },
  {
    title: "Nationality & residence",
    fields: [
      { key: "nationality", label: "Nationality", type: "country", when: isPerson },
      { key: "second_nationality", label: "Second nationality", type: "country", when: isPerson },
      { key: "residence_country", label: "Country of residence", type: "country" },
      { key: "preferred_language", label: "Preferred language", type: "text", placeholder: "BCP 47, e.g. en", hint: "Communications language" },
    ],
  },
  {
    title: "Tax & regulatory",
    description: "CRS / FATCA classification.",
    fields: [
      { key: "tax_residence_country", label: "Tax residence", type: "country", hint: "Primary tax residence (CRS/FATCA)" },
      { key: "us_person", label: "US person", type: "bool", hint: "FATCA US person indicator" },
    ],
  },
  {
    title: "Employment & income",
    fields: [
      { key: "occupation", label: "Occupation", type: "text", when: isPerson },
      { key: "employment_status", label: "Employment status", type: "select", options: EMPLOYMENT, when: isPerson },
      { key: "employer_name", label: "Employer", type: "text", when: isPerson },
      { key: "annual_income", label: "Declared annual income", type: "amount", when: isPerson },
      { key: "income_currency", label: "Income currency", type: "currency", when: isPerson },
      { key: "source_of_funds", label: "Source of funds", type: "text", span: 2 },
      { key: "source_of_wealth", label: "Source of wealth", type: "text", span: 2, hint: "High-risk & private customers" },
    ],
  },
  {
    title: "Organisation",
    description: "Registered-entity details (businesses, sole traders and other organisations).",
    fields: [
      { key: "legal_name", label: "Registered legal name", type: "text", required: true, span: 2, when: isCompany },
      { key: "legal_name", label: "Registered legal name", type: "text", span: 2, when: (v) => v.customer_type === "SOLE_TRADER" },
      { key: "trading_name", label: "Trading / DBA name", type: "text", span: 2, when: isOrg },
      { key: "registration_number", label: "Registration number", type: "text", when: isOrg },
      { key: "registration_country", label: "Country of incorporation", type: "country", when: isOrg },
      { key: "incorporation_date", label: "Incorporation date", type: "date", when: isOrg },
      { key: "legal_form", label: "Legal form", type: "text", placeholder: "LLC, PLC, GmbH…", when: isOrg },
      { key: "industry_code", label: "Industry code", type: "text", placeholder: "ISIC / NACE / NAICS", when: isOrg },
      { key: "lei", label: "LEI", type: "text", when: isOrg },
      { key: "website", label: "Website", type: "text", when: isOrg },
      { key: "number_of_employees", label: "Number of employees", type: "number", when: isOrg },
      { key: "annual_turnover", label: "Declared annual turnover", type: "amount", when: isOrg },
    ],
  },
];

type C = Record<string, unknown>;

/** The full 360 profile, grouped into sections, for the detail page. */
export function customerDetailSections(c: C): DetailSection[] {
  return [
    {
      title: "Identity",
      rows: [
        { label: "Customer number", value: c.customer_number, kind: "mono" },
        { label: "Type", value: c.customer_type, kind: "badge" },
        { label: "Status", value: c.status, kind: "badge" },
        { label: "KYC tier", value: c.kyc_tier, kind: "badge" },
        { label: "Pending tier", value: c.pending_tier, kind: "badge" },
        { label: "Risk level", value: c.risk_level, kind: "badge" },
        { label: "PEP", value: c.is_pep, kind: "bool" },
        { label: "Bank staff", value: c.is_staff, kind: "bool" },
        { label: "Minor", value: c.is_minor, kind: "bool" },
        { label: "External ref", value: c.external_ref, kind: "mono" },
      ],
    },
    {
      title: "Name",
      hideWhenEmpty: true,
      rows: [
        { label: "Title", value: c.title },
        { label: "First name", value: c.first_name },
        { label: "Middle name", value: c.middle_name },
        { label: "Last name", value: c.last_name },
        { label: "Local script", value: c.name_local_script },
        { label: "Display name", value: c.display_name },
      ],
    },
    {
      title: "Personal",
      hideWhenEmpty: true,
      rows: [
        { label: "Date of birth", value: c.date_of_birth, kind: "date" },
        { label: "Place of birth", value: c.place_of_birth },
        { label: "Country of birth", value: c.country_of_birth },
        { label: "Gender", value: c.gender },
        { label: "Marital status", value: c.marital_status },
      ],
    },
    {
      title: "Nationality & residence",
      rows: [
        { label: "Nationality", value: c.nationality },
        { label: "Second nationality", value: c.second_nationality },
        { label: "Residence country", value: c.residence_country },
        { label: "Preferred language", value: c.preferred_language },
      ],
    },
    {
      title: "Tax & regulatory",
      rows: [
        { label: "Tax residence", value: c.tax_residence_country },
        { label: "US person (FATCA)", value: c.us_person, kind: "bool" },
      ],
    },
    {
      title: "Employment & income",
      hideWhenEmpty: true,
      rows: [
        { label: "Occupation", value: c.occupation },
        { label: "Employment status", value: c.employment_status },
        { label: "Employer", value: c.employer_name },
        { label: "Annual income", value: c.annual_income, kind: "money", currency: c.income_currency as string },
        { label: "Source of funds", value: c.source_of_funds },
        { label: "Source of wealth", value: c.source_of_wealth },
      ],
    },
    {
      title: "Organisation",
      hideWhenEmpty: true,
      rows: [
        { label: "Legal name", value: c.legal_name },
        { label: "Trading name", value: c.trading_name },
        { label: "Registration number", value: c.registration_number, kind: "mono" },
        { label: "Country of incorporation", value: c.registration_country },
        { label: "Incorporation date", value: c.incorporation_date, kind: "date" },
        { label: "Legal form", value: c.legal_form },
        { label: "Industry code", value: c.industry_code },
        { label: "LEI", value: c.lei, kind: "mono" },
        { label: "Website", value: c.website },
        { label: "Employees", value: c.number_of_employees },
        { label: "Annual turnover", value: c.annual_turnover, kind: "money" },
      ],
    },
    {
      title: "Relationship & lifecycle",
      rows: [
        { label: "Legal entity", value: c.legal_entity_id, kind: "mono" },
        { label: "Home branch", value: c.home_branch_id, kind: "mono" },
        { label: "Relationship manager", value: c.relationship_manager_id, kind: "mono" },
        { label: "Onboarding channel", value: c.onboarding_channel },
        { label: "Referral code", value: c.referral_code },
        { label: "Onboarded at", value: c.onboarded_at, kind: "datetime" },
        { label: "Dormant since", value: c.dormant_since, kind: "date" },
        { label: "Status reason", value: c.status_reason },
        { label: "Status changed at", value: c.status_changed_at, kind: "datetime" },
        { label: "Created", value: c.created_at, kind: "datetime" },
      ],
    },
  ];
}
