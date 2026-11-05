import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** party.yaml KYC enums (kept in sync with the kyc-service section of spec/domains/party.yaml). */
export const KYC_PROFILE_STATUS = ["NOT_STARTED", "IN_PROGRESS", "VERIFIED", "REFER", "REJECTED", "EXPIRED", "REVIEW_DUE"];
export const KYC_CHECK_TYPE = ["DOCUMENT_OCR", "DOCUMENT_AUTHENTICITY", "LIVENESS", "FACE_MATCH", "DATABASE_VERIFICATION",
  "ADDRESS_VERIFICATION", "PHONE_VERIFICATION", "EMAIL_VERIFICATION", "REGISTRY_LOOKUP", "UBO_DISCOVERY",
  "TAX_ID_VERIFICATION", "BANK_ACCOUNT_VERIFICATION"];
export const KYC_CHECK_STATUS = ["PENDING", "IN_PROGRESS", "PASSED", "FAILED", "INCONCLUSIVE", "ERROR", "EXPIRED"];
export const KYC_DOCUMENT_TYPE = ["NATIONAL_ID", "PASSPORT", "DRIVING_LICENCE", "RESIDENCE_PERMIT", "UTILITY_BILL",
  "BANK_STATEMENT", "PROOF_OF_INCOME", "CERTIFICATE_OF_INCORPORATION", "ARTICLES_OF_ASSOCIATION", "SHAREHOLDER_REGISTER",
  "BOARD_RESOLUTION", "TAX_CERTIFICATE", "SELFIE", "OTHER"];
export const KYC_DOCUMENT_STATUS = ["UPLOADED", "PROCESSING", "ACCEPTED", "REJECTED", "EXPIRED"];
export const KYC_SCREENING_TYPE = ["SANCTIONS", "PEP", "ADVERSE_MEDIA", "WATCHLIST", "INTERNAL_BLACKLIST"];
export const KYC_SCREENING_STATUS = ["CLEAR", "POTENTIAL_MATCH", "CONFIRMED_MATCH", "FALSE_POSITIVE", "ERROR"];
/** kyc_screening.trigger is a free string(40); these are the documented values. */
export const KYC_SCREENING_TRIGGER = ["ONBOARDING", "DATA_CHANGE", "LIST_UPDATE", "PERIODIC", "MANUAL"];
export const KYC_RISK_LEVEL = ["LOW", "MEDIUM", "HIGH", "PROHIBITED"];
export const KYC_REVIEW_STATUS = ["SCHEDULED", "DUE", "IN_PROGRESS", "COMPLETED", "OVERDUE", "CANCELLED"];
export const KYC_REVIEW_TRIGGER = ["PERIODIC", "DOCUMENT_EXPIRY", "RISK_CHANGE", "SCREENING_HIT", "CUSTOMER_CHANGE",
  "REGULATOR_REQUEST", "MANUAL"];
export const KYC_PROVIDER_CAPABILITY = ["DOCUMENT", "LIVENESS", "FACE_MATCH", "DATABASE", "ADDRESS", "REGISTRY",
  "SCREENING", "PHONE", "EMAIL"];
export const ONBOARDING_CHANNEL = ["MOBILE_APP", "WEB", "USSD", "AGENT", "BRANCH", "API"];

/* ---------------------------------------------------------------------------------------------------------------
 * Create / initiate field sets. Each covers the full writable column set of the matching kyc-service table
 * (ro / server-derived columns such as status, scores, payloads and timestamps are excluded; nn-without-default
 * columns are required). Grouped so operators see every field rather than a token subset.
 * ------------------------------------------------------------------------------------------------------------- */

/** Open a KYC profile for a customer (kyc_profile — status, tier, risk and verification flags are server-derived). */
export const kycProfileFormSections: FormSection[] = [
  {
    title: "KYC profile",
    description: "Opens the KYC state record for a customer. Tier, risk and status are derived by the evaluate step.",
    fields: [
      { key: "customer_id", label: "Customer", type: "text", required: true, span: 2, hint: "Customer this profile belongs to" },
      { key: "last_reviewed_by", label: "Last reviewed by", type: "text", hint: "Reviewer user id" },
      { key: "notes", label: "Analyst notes", type: "textarea" },
    ],
  },
];

/** Initiate a verification check against a provider (kyc_check — scores, payloads and results are provider-filled). */
export const kycCheckFormSections: FormSection[] = [
  {
    title: "Check request",
    description: "Runs one verification call against a provider. The score, result summary and reason codes are returned asynchronously.",
    fields: [
      { key: "kyc_profile_id", label: "KYC profile", type: "text", required: true, span: 2 },
      { key: "check_type", label: "Check type", type: "select", options: KYC_CHECK_TYPE, required: true },
      { key: "kyc_provider_id", label: "Provider", type: "text", required: true, hint: "Configured KYC provider id" },
      { key: "kyc_document_id", label: "Document", type: "text", hint: "Document to check (OCR / authenticity / face match)" },
      { key: "onboarding_application_id", label: "Onboarding application", type: "text", hint: "Application that requested the check" },
    ],
  },
];

/** Register a captured KYC/KYB document (kyc_document — status defaults to UPLOADED; rejection reason is set on reject). */
export const kycDocumentFormSections: FormSection[] = [
  {
    title: "Document",
    description: "Links a captured file to the KYC profile for OCR and manual review.",
    fields: [
      { key: "kyc_profile_id", label: "KYC profile", type: "text", required: true },
      { key: "customer_id", label: "Customer", type: "text", required: true },
      { key: "document_type", label: "Document type", type: "select", options: KYC_DOCUMENT_TYPE, required: true },
      { key: "document_id", label: "Stored file (front)", type: "text", required: true, hint: "Document-store file id" },
      { key: "back_document_id", label: "Stored file (back)", type: "text", hint: "Reverse side, where applicable" },
      { key: "captured_channel", label: "Capture channel", type: "select", options: ONBOARDING_CHANNEL },
    ],
  },
  {
    title: "Document details",
    description: "From OCR / MRZ or manual entry.",
    fields: [
      { key: "document_number", label: "Document number", type: "text" },
      { key: "issuing_country", label: "Issuing country", type: "country" },
      { key: "issue_date", label: "Issue date", type: "date" },
      { key: "expiry_date", label: "Expiry date", type: "date" },
      { key: "extracted_data", label: "Extracted data", type: "json", hint: "OCR / MRZ extracted fields as JSON" },
    ],
  },
];

/** Run a screening against a customer (kyc_screening — outcome, matches and list versions are engine-filled). */
export const kycScreeningFormSections: FormSection[] = [
  {
    title: "Screening",
    description: "Screens the customer against sanctions, PEP, adverse-media and watchlists. The outcome and candidate matches are returned by the engine.",
    fields: [
      { key: "kyc_profile_id", label: "KYC profile", type: "text", required: true },
      { key: "customer_id", label: "Customer", type: "text", required: true },
      { key: "screening_type", label: "Screening type", type: "select", options: KYC_SCREENING_TYPE, required: true },
      { key: "screened_name", label: "Screened name", type: "text", required: true, span: 2, hint: "Name as submitted to the lists" },
      { key: "trigger", label: "Trigger", type: "select", options: KYC_SCREENING_TRIGGER, required: true },
      { key: "kyc_provider_id", label: "Provider", type: "text", hint: "Leave blank to use the internal engine" },
      { key: "ongoing_monitoring", label: "Ongoing monitoring", type: "bool", hint: "Enrol in continuous monitoring" },
    ],
  },
];

/** Record a risk assessment (kyc_risk_assessment — append-only; a manual rating with its factors). */
export const kycRiskAssessmentFormSections: FormSection[] = [
  {
    title: "Risk assessment",
    description: "Appends a risk rating to the profile. Append-only; a new assessment supersedes the previous one.",
    fields: [
      { key: "kyc_profile_id", label: "KYC profile", type: "text", required: true },
      { key: "customer_id", label: "Customer", type: "text", required: true },
      { key: "model_version", label: "Model version", type: "text", required: true, hint: "Risk model version, e.g. v3.2 or MANUAL" },
      { key: "score", label: "Score", type: "number", required: true, hint: "0–1000" },
      { key: "risk_level", label: "Risk level", type: "select", options: KYC_RISK_LEVEL, required: true },
      { key: "trigger", label: "Trigger", type: "select", options: KYC_REVIEW_TRIGGER, required: true },
      { key: "assessed_by", label: "Assessed by", type: "text", hint: "Analyst user id (manual assessments)" },
    ],
  },
  {
    title: "Factors & override",
    fields: [
      { key: "factors", label: "Factors", type: "json", required: true, hint: "Factor contributions and inputs as JSON" },
      { key: "override_level", label: "Override level", type: "select", options: KYC_RISK_LEVEL, hint: "Manual override of the model level" },
      { key: "override_reason", label: "Override reason", type: "text", span: 2 },
    ],
  },
];

/** Open a periodic or event-driven review (kyc_review — outcome and completion are recorded via the Complete action). */
export const kycReviewFormSections: FormSection[] = [
  {
    title: "Review",
    description: "Schedules a KYC refresh for a customer. Assign it to an analyst and record the outcome when complete.",
    fields: [
      { key: "kyc_profile_id", label: "KYC profile", type: "text", required: true },
      { key: "customer_id", label: "Customer", type: "text", required: true },
      { key: "trigger", label: "Trigger", type: "select", options: KYC_REVIEW_TRIGGER, required: true },
      { key: "due_date", label: "Due date", type: "date", required: true },
      { key: "status", label: "Status", type: "select", options: KYC_REVIEW_STATUS, hint: "Defaults to SCHEDULED" },
      { key: "assigned_to", label: "Assigned analyst", type: "text", hint: "Analyst user id" },
      { key: "customer_outreach_at", label: "Customer outreach", type: "datetime", hint: "When the customer was asked to update information" },
    ],
  },
];

/** Configure an external KYC/KYB provider (kyc_provider). Admin configuration — not wired into the workbench tabs. */
export const kycProviderFormSections: FormSection[] = [
  {
    title: "Provider",
    fields: [
      { key: "code", label: "Provider code", type: "text", required: true },
      { key: "name", label: "Provider name", type: "text", required: true, span: 2 },
      { key: "capabilities", label: "Capabilities", type: "list", required: true, span: 3,
        hint: `Comma-separated: ${KYC_PROVIDER_CAPABILITY.join(", ")}` },
      { key: "countries", label: "Countries", type: "list", span: 3, hint: "ISO 3166-1 alpha-2, comma-separated (blank = all)" },
      { key: "priority", label: "Routing priority", type: "number", hint: "Lower runs first (default 100)" },
      { key: "is_active", label: "Active", type: "bool" },
      { key: "is_fallback", label: "Fallback only", type: "bool", hint: "Used only when the primary provider fails" },
    ],
  },
  {
    title: "Connectivity",
    fields: [
      { key: "base_url", label: "API base URL", type: "text", span: 2 },
      { key: "timeout_ms", label: "Timeout (ms)", type: "number", hint: "Default 10000" },
      { key: "credentials_ref", label: "Credentials ref", type: "text", required: true, span: 2, hint: "Vault path of the API credentials (a reference, never the secret)" },
      { key: "webhook_secret_ref", label: "Webhook secret ref", type: "text", span: 2, hint: "Vault path of the webhook signing secret" },
    ],
  },
];

type K = Record<string, unknown>;

/** The full KYC profile, grouped into sections, for the profile detail page. */
export function kycProfileDetailSections(p: K): DetailSection[] {
  return [
    {
      title: "Verification & risk",
      rows: [
        { label: "Status", value: p.status, kind: "badge" },
        { label: "Achieved tier", value: p.achieved_tier, kind: "badge" },
        { label: "Risk level", value: p.risk_level, kind: "badge" },
        { label: "Risk score", value: p.risk_score },
        { label: "Identity verified", value: p.identity_verified, kind: "bool" },
        { label: "Address verified", value: p.address_verified, kind: "bool" },
        { label: "Screening clear", value: p.screening_clear, kind: "bool" },
        { label: "EDD required", value: p.edd_required, kind: "bool" },
        { label: "EDD completed", value: p.edd_completed_at, kind: "datetime" },
      ],
    },
    {
      title: "Customer & review",
      rows: [
        { label: "Customer", value: p.customer_id, kind: "mono" },
        { label: "Verified at", value: p.verified_at, kind: "datetime" },
        { label: "Documents expire", value: p.expires_at, kind: "datetime" },
        { label: "Next review", value: p.next_review_date, kind: "date" },
        { label: "Last reviewed by", value: p.last_reviewed_by, kind: "mono" },
        { label: "Notes", value: p.notes },
        { label: "Created", value: p.created_at, kind: "datetime" },
        { label: "Updated", value: p.updated_at, kind: "datetime" },
      ],
    },
  ];
}

/** Configured KYC/KYB provider detail (kyc_provider). */
export function kycProviderDetailSections(p: K): DetailSection[] {
  return [
    {
      title: "Provider",
      rows: [
        { label: "Code", value: p.code, kind: "mono" },
        { label: "Name", value: p.name },
        { label: "Capabilities", value: p.capabilities },
        { label: "Countries", value: p.countries },
        { label: "Priority", value: p.priority },
        { label: "Active", value: p.is_active, kind: "bool" },
        { label: "Fallback only", value: p.is_fallback, kind: "bool" },
      ],
    },
    {
      title: "Connectivity",
      rows: [
        { label: "Base URL", value: p.base_url },
        { label: "Timeout (ms)", value: p.timeout_ms },
        { label: "Credentials ref", value: p.credentials_ref, kind: "mono" },
        { label: "Webhook secret ref", value: p.webhook_secret_ref, kind: "mono" },
      ],
    },
  ];
}
