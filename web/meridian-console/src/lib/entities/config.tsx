import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/* ---------------------------------------------------------------------------------------------------------------
 * Configuration & system-admin entity specs: the FULL writable field set for the reference-data bank directory
 * (platform.yaml → bank_directory), the biller catalogue (wallets.yaml → biller) and staff/system users
 * (platform.yaml → app_user). Server-derived / read-only / secret columns are shown on the detail view but kept
 * out of the create forms. Kept in sync with the owning services' table + apis blocks.
 * ------------------------------------------------------------------------------------------------------------- */

/* --- enums (platform.yaml / wallets.yaml) ------------------------------------------------------------------- */
export const BILLER_CATEGORY = ["ELECTRICITY", "WATER", "GAS", "TELECOM", "AIRTIME", "DATA", "TV", "INTERNET",
  "EDUCATION", "GOVERNMENT", "TAX", "INSURANCE", "LOAN", "OTHER"];
export const BILLER_INTEGRATION = ["API", "ISO8583", "FILE", "AGGREGATOR"];
export const BILLER_CHECKSUM = ["NONE", "LUHN", "MOD97", "MOD11"];
export const SETTLEMENT_FREQUENCY = ["REALTIME", "DAILY", "WEEKLY", "MONTHLY", "ON_DEMAND"];
export const BILLER_STATUS = ["ACTIVE", "SUSPENDED", "RETIRED"];
export const FEE_BEARER = ["CUSTOMER", "BILLER", "SHARED"];
export const STAFF_USER_TYPE = ["STAFF", "PLATFORM_OPERATOR", "AGENT_STAFF", "AUDITOR", "SERVICE_ACCOUNT"];
export const STAFF_USER_STATUS = ["INVITED", "ACTIVE", "LOCKED", "SUSPENDED", "DEACTIVATED"];

/* =============================================================================================================
 * BANK DIRECTORY — platform.yaml (reference-data-service), table bank_directory, path /v1/reference/banks
 * ============================================================================================================= */
export const bankFormSections: FormSection[] = [
  {
    title: "Identity",
    description: "A bank (or branch) in the external directory used for payment routing and account validation.",
    fields: [
      { key: "bank_name", label: "Bank name", type: "text", required: true, span: 2 },
      { key: "branch_name", label: "Branch name", type: "text", span: 2 },
      { key: "bic", label: "BIC / SWIFT", type: "text", placeholder: "8 or 11 chars", hint: "ISO 9362 BIC" },
      { key: "country", label: "Country", type: "country", required: true },
    ],
  },
  {
    title: "Clearing & rails",
    fields: [
      { key: "clearing_system", label: "Clearing system", type: "text", placeholder: "USABA, GBDSC, INFSC…" },
      { key: "national_clearing_code", label: "National clearing code", type: "text", hint: "Routing / sort / IFSC code" },
      { key: "rail_participation", label: "Rail participation", type: "list", span: 2, hint: "Comma-separated rails, e.g. SEPA_INST, FEDNOW, PIX" },
    ],
  },
  {
    title: "Location",
    fields: [
      { key: "city", label: "City", type: "text" },
      { key: "address", label: "Address", type: "textarea" },
    ],
  },
  {
    title: "Source & status",
    fields: [
      { key: "source", label: "Directory source", type: "text", placeholder: "SWIFTRef…" },
      { key: "source_updated_at", label: "Source last refreshed", type: "datetime" },
      { key: "is_active", label: "Active", type: "bool", hint: "Active in the directory" },
    ],
  },
];

type C = Record<string, unknown>;

export function bankDetailSections(b: C): DetailSection[] {
  return [
    {
      title: "Identity",
      rows: [
        { label: "Bank name", value: b.bank_name },
        { label: "Branch name", value: b.branch_name },
        { label: "BIC / SWIFT", value: b.bic, kind: "mono" },
        { label: "Country", value: b.country },
        { label: "Active", value: b.is_active, kind: "bool" },
      ],
    },
    {
      title: "Clearing & rails",
      rows: [
        { label: "Clearing system", value: b.clearing_system },
        { label: "National clearing code", value: b.national_clearing_code, kind: "mono" },
        { label: "Rail participation", value: Array.isArray(b.rail_participation) ? (b.rail_participation as string[]).join(", ") : b.rail_participation },
      ],
    },
    {
      title: "Location",
      rows: [
        { label: "City", value: b.city },
        { label: "Address", value: b.address },
      ],
    },
    {
      title: "Source",
      rows: [
        { label: "Directory source", value: b.source },
        { label: "Source last refreshed", value: b.source_updated_at, kind: "datetime" },
      ],
    },
  ];
}

/* =============================================================================================================
 * BILLER — wallets.yaml (biller-service), table biller, path /v1/billers
 * ============================================================================================================= */
export const billerFormSections: FormSection[] = [
  {
    title: "Identity",
    description: "A company or agency customers can pay bills to.",
    fields: [
      { key: "biller_code", label: "Biller code", type: "text", required: true },
      { key: "name", label: "Display name", type: "text", required: true, span: 2 },
      { key: "category", label: "Category", type: "select", options: BILLER_CATEGORY, required: true },
      { key: "country", label: "Country", type: "country", required: true },
      { key: "currency", label: "Currency", type: "currency", required: true },
      { key: "integration_type", label: "Integration", type: "select", options: BILLER_INTEGRATION, required: true, hint: "How payments reach the biller" },
      { key: "aggregator_name", label: "Aggregator", type: "text", hint: "When integration is AGGREGATOR" },
    ],
  },
  {
    title: "Capabilities",
    fields: [
      { key: "supports_inquiry", label: "Supports bill inquiry", type: "bool", hint: "Can look up a bill before payment" },
      { key: "supports_partial_payment", label: "Supports partial payment", type: "bool", hint: "Accepts less than the amount due" },
      { key: "supports_overpayment", label: "Supports overpayment", type: "bool", hint: "Accepts more than the amount due" },
    ],
  },
  {
    title: "Amount limits",
    fields: [
      { key: "min_amount", label: "Minimum payment", type: "amount" },
      { key: "max_amount", label: "Maximum payment", type: "amount" },
    ],
  },
  {
    title: "Account reference",
    description: "What the customer types and how it is validated.",
    fields: [
      { key: "account_reference_label", label: "Reference label", type: "text", placeholder: "Meter number, Policy no…" },
      { key: "account_reference_regex", label: "Reference pattern", type: "text", hint: "Full-match validation regex" },
      { key: "account_reference_checksum", label: "Check-digit algorithm", type: "select", options: BILLER_CHECKSUM, required: true, hint: "Default NONE" },
    ],
  },
  {
    title: "Settlement & fees",
    fields: [
      { key: "inquiry_validity_minutes", label: "Inquiry validity (minutes)", type: "number", hint: "How long a bill inquiry can be paid against (default 30)" },
      { key: "settlement_wallet_id", label: "Settlement wallet", type: "text", hint: "Biller collection wallet id" },
      { key: "settlement_frequency", label: "Settlement frequency", type: "select", options: SETTLEMENT_FREQUENCY, required: true, hint: "Default DAILY" },
      { key: "fee_bearer", label: "Fee bearer", type: "select", options: FEE_BEARER, required: true, hint: "Default CUSTOMER" },
    ],
  },
  {
    title: "Presentation",
    fields: [
      { key: "logo_document_id", label: "Logo document", type: "text", hint: "Document id of the logo" },
      { key: "display_order", label: "Display order", type: "number", hint: "Order in customer menus" },
    ],
  },
];

export function billerDetailSections(b: C): DetailSection[] {
  const ccy = b.currency as string;
  return [
    {
      title: "Identity",
      rows: [
        { label: "Biller code", value: b.biller_code, kind: "mono" },
        { label: "Name", value: b.name },
        { label: "Category", value: b.category, kind: "badge" },
        { label: "Status", value: b.status, kind: "badge" },
        { label: "Country", value: b.country },
        { label: "Currency", value: b.currency },
        { label: "Integration", value: b.integration_type, kind: "badge" },
        { label: "Aggregator", value: b.aggregator_name },
        { label: "Status reason", value: b.status_reason },
      ],
    },
    {
      title: "Capabilities",
      rows: [
        { label: "Supports inquiry", value: b.supports_inquiry, kind: "bool" },
        { label: "Supports partial payment", value: b.supports_partial_payment, kind: "bool" },
        { label: "Supports overpayment", value: b.supports_overpayment, kind: "bool" },
      ],
    },
    {
      title: "Amount limits",
      rows: [
        { label: "Minimum payment", value: b.min_amount, kind: "money", currency: ccy },
        { label: "Maximum payment", value: b.max_amount, kind: "money", currency: ccy },
      ],
    },
    {
      title: "Account reference",
      rows: [
        { label: "Reference label", value: b.account_reference_label },
        { label: "Reference pattern", value: b.account_reference_regex, kind: "mono" },
        { label: "Check-digit algorithm", value: b.account_reference_checksum },
      ],
    },
    {
      title: "Settlement & fees",
      rows: [
        { label: "Inquiry validity (minutes)", value: b.inquiry_validity_minutes },
        { label: "Settlement wallet", value: b.settlement_wallet_id, kind: "mono" },
        { label: "Settlement frequency", value: b.settlement_frequency },
        { label: "Fee bearer", value: b.fee_bearer },
      ],
    },
    {
      title: "Presentation",
      rows: [
        { label: "Logo document", value: b.logo_document_id, kind: "mono" },
        { label: "Display order", value: b.display_order },
      ],
    },
  ];
}

/* =============================================================================================================
 * APP USER — platform.yaml (identity-service), table app_user, path /v1/users
 * ============================================================================================================= */
export const appUserFormSections: FormSection[] = [
  {
    title: "Identity",
    description: "A staff, operator, auditor or service-account user of the console and back-office APIs.",
    fields: [
      { key: "username", label: "Username", type: "text", required: true },
      { key: "email", label: "Work email", type: "email", required: true, span: 2 },
      { key: "first_name", label: "First name", type: "text", required: true },
      { key: "last_name", label: "Last name", type: "text", required: true },
      { key: "display_name", label: "Display name", type: "text", hint: "Defaults to first + last when blank" },
      { key: "phone", label: "Work phone", type: "phone" },
      { key: "user_type", label: "User type", type: "select", options: STAFF_USER_TYPE, required: true, hint: "Default STAFF" },
    ],
  },
  {
    title: "Access & roles",
    description: "Roles assigned on creation; status is normally driven by lifecycle actions (invite / lock / deactivate).",
    fields: [
      { key: "roles", label: "Roles", type: "list", span: 2, hint: "Comma-separated role codes or ids" },
      { key: "status", label: "Status", type: "select", options: STAFF_USER_STATUS, hint: "Default INVITED" },
    ],
  },
  {
    title: "Federation",
    description: "For users that sign in through an external identity provider.",
    fields: [
      { key: "sso_provider_id", label: "SSO provider", type: "text", hint: "SSO provider id when federated" },
      { key: "external_subject", label: "External subject", type: "text", hint: "Subject id at the identity provider" },
    ],
  },
  {
    title: "Organisation",
    fields: [
      { key: "legal_entity_id", label: "Home legal entity", type: "text" },
      { key: "branch_id", label: "Home branch", type: "text" },
      { key: "department", label: "Department", type: "text" },
      { key: "job_title", label: "Job title", type: "text" },
      { key: "manager_id", label: "Line manager", type: "text", hint: "User id of the manager" },
    ],
  },
  {
    title: "Preferences",
    fields: [
      { key: "locale", label: "Locale", type: "text", placeholder: "en", hint: "Preferred locale (default en)" },
      { key: "timezone", label: "Timezone", type: "text", placeholder: "UTC", hint: "IANA timezone (default UTC)" },
    ],
  },
];

export function appUserDetailSections(u: C): DetailSection[] {
  return [
    {
      title: "Identity",
      rows: [
        { label: "Username", value: u.username, kind: "mono" },
        { label: "Email", value: u.email },
        { label: "First name", value: u.first_name },
        { label: "Last name", value: u.last_name },
        { label: "Display name", value: u.display_name },
        { label: "Phone", value: u.phone },
        { label: "User type", value: u.user_type, kind: "badge" },
        { label: "Status", value: u.status, kind: "badge" },
      ],
    },
    {
      title: "Access & roles",
      rows: [
        { label: "Roles", value: Array.isArray(u.roles) ? (u.roles as string[]).join(", ") : u.roles },
        { label: "MFA enrolled", value: u.mfa_enrolled, kind: "bool" },
      ],
    },
    {
      title: "Federation",
      hideWhenEmpty: true,
      rows: [
        { label: "SSO provider", value: u.sso_provider_id, kind: "mono" },
        { label: "External subject", value: u.external_subject, kind: "mono" },
      ],
    },
    {
      title: "Organisation",
      rows: [
        { label: "Legal entity", value: u.legal_entity_id, kind: "mono" },
        { label: "Branch", value: u.branch_id, kind: "mono" },
        { label: "Department", value: u.department },
        { label: "Job title", value: u.job_title },
        { label: "Line manager", value: u.manager_id, kind: "mono" },
      ],
    },
    {
      title: "Preferences",
      rows: [
        { label: "Locale", value: u.locale },
        { label: "Timezone", value: u.timezone },
      ],
    },
    {
      title: "Security & lifecycle",
      rows: [
        { label: "Password changed", value: u.password_changed_at, kind: "datetime" },
        { label: "Last login", value: u.last_login_at, kind: "datetime" },
        { label: "Failed logins", value: u.failed_login_count },
        { label: "Locked until", value: u.locked_until, kind: "datetime" },
        { label: "Invited at", value: u.invited_at, kind: "datetime" },
        { label: "Deactivated at", value: u.deactivated_at, kind: "datetime" },
      ],
    },
  ];
}
