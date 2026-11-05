import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** party.yaml onboarding_db enums (kept in sync with spec/domains/party.yaml). */
export const ONBOARDING_CHANNEL = ["MOBILE_APP", "WEB", "USSD", "AGENT", "BRANCH", "API"];
export const CUSTOMER_TYPE = ["INDIVIDUAL", "SOLE_TRADER", "COMPANY", "PARTNERSHIP", "TRUST", "NON_PROFIT", "GOVERNMENT", "GROUP"];
export const KYC_TIER = ["TIER_0", "TIER_1", "TIER_2", "TIER_3"];

/**
 * Fields whose values the operator enters as a comma-separated list but the API expects as a
 * JSON array, and fields the operator pastes as JSON text that must be parsed into an object.
 * The page runs {@link transformOnboardingBody} over the cleaned body before POSTing.
 */
export const JOURNEY_ARRAY_FIELDS = ["channels", "product_ids"];
export const JOURNEY_JSON_FIELDS = ["definition", "decision_rules"];
export const APPLICATION_ARRAY_FIELDS = ["product_ids"];
export const APPLICATION_JSON_FIELDS = ["collected_data", "geo_location", "utm"];

/**
 * Turns the engine's cleaned string body into the shape the onboarding API expects:
 * comma-separated inputs become arrays, and JSON textareas are parsed into objects
 * (left as the raw string if invalid, so the service's own validation surfaces the error).
 */
export function transformOnboardingBody(
  body: Record<string, unknown>,
  arrayFields: string[],
  jsonFields: string[],
): Record<string, unknown> {
  const out = { ...body };
  for (const k of arrayFields) {
    const v = out[k];
    if (typeof v === "string") out[k] = v.split(",").map((s) => s.trim()).filter(Boolean);
  }
  for (const k of jsonFields) {
    const v = out[k];
    if (typeof v === "string" && v.trim() !== "") {
      try { out[k] = JSON.parse(v); } catch { /* leave as raw string; the service validates */ }
    }
  }
  return out;
}

/** The full no-code journey definition field set — every writable column on onboarding_journey. */
export const onboardingJourneyFormSections: FormSection[] = [
  {
    title: "Journey",
    description: "Identifies the versioned journey and who it is for.",
    fields: [
      { key: "code", label: "Journey code", type: "text", required: true, placeholder: "RETAIL_WALLET_KE", hint: "Unique per tenant + version" },
      { key: "name", label: "Journey name", type: "text", required: true, span: 2 },
      { key: "customer_type", label: "Customer type", type: "select", options: CUSTOMER_TYPE, required: true, hint: "Individual or business journey" },
      { key: "country", label: "Country", type: "country", hint: "Country the journey applies to" },
      { key: "target_tier", label: "Target KYC tier", type: "select", options: KYC_TIER, required: true, hint: "Tier granted on approval" },
      { key: "expiry_days", label: "Expiry (days)", type: "number", required: true, hint: "Days before an idle application expires" },
    ],
  },
  {
    title: "Channels & products",
    description: "Where the journey can run and what it can open at the end.",
    fields: [
      { key: "channels", label: "Allowed channels", type: "text", required: true, span: 2, placeholder: "MOBILE_APP, WEB, AGENT", hint: "Comma-separated OnboardingChannel values" },
      { key: "product_ids", label: "Product ids", type: "text", span: 2, placeholder: "uuid, uuid", hint: "Comma-separated products openable at the end" },
      { key: "legal_entity_id", label: "Legal entity", type: "text", hint: "Bank entity holding relationships opened through this journey" },
    ],
  },
  {
    title: "Definition & decision rules",
    description: "The no-code flow definition and automated decisioning, as JSON.",
    fields: [
      { key: "definition", label: "Definition (JSON)", type: "textarea", required: true,
        placeholder: '{"steps": [{"code": "phone", "type": "PHONE_VERIFICATION"}]}',
        hint: "Steps, branching conditions and screens (server-driven UI)" },
      { key: "decision_rules", label: "Decision rules (JSON)", type: "textarea",
        placeholder: '{"auto_approve": [], "refer": [], "reject": []}',
        hint: "Auto-approve / refer / reject rules" },
    ],
  },
];

/** The full operator-initiated application field set — every writable column on onboarding_application. */
export const onboardingApplicationFormSections: FormSection[] = [
  {
    title: "Application",
    description: "The journey this run follows and the relationship it opens.",
    fields: [
      { key: "onboarding_journey_id", label: "Journey", type: "text", required: true, span: 2, hint: "Published onboarding_journey id" },
      { key: "channel", label: "Channel", type: "select", options: ONBOARDING_CHANNEL, required: true },
      { key: "customer_id", label: "Customer", type: "text", hint: "Existing customer matched to this application" },
      { key: "legal_entity_id", label: "Legal entity", type: "text", hint: "Entity that will hold the relationship" },
      { key: "product_ids", label: "Product ids", type: "text", span: 2, placeholder: "uuid, uuid", hint: "Comma-separated products requested" },
    ],
  },
  {
    title: "Assisted by",
    description: "Set for agent-, branch- and staff-assisted applications.",
    fields: [
      { key: "agent_id", label: "Agent", type: "text", hint: "Agent assisting (agent channel)" },
      { key: "branch_id", label: "Branch", type: "text", hint: "Branch (branch channel)" },
      { key: "staff_user_id", label: "Staff user", type: "text", hint: "Staff member assisting" },
    ],
  },
  {
    title: "Applicant contact",
    fields: [
      { key: "phone", label: "Phone", type: "phone", hint: "Phone used to start the application" },
      { key: "email", label: "Email", type: "email", hint: "Email used to start the application" },
    ],
  },
  {
    title: "Capture context",
    description: "Device, origin and pre-collected data (JSON where noted).",
    fields: [
      { key: "device_fingerprint", label: "Device fingerprint", type: "text" },
      { key: "ip_address", label: "IP address", type: "text", placeholder: "203.0.113.4" },
      { key: "expires_at", label: "Expires at", type: "datetime", hint: "Expiry of an idle application" },
      { key: "geo_location", label: "Geo location (JSON)", type: "textarea", placeholder: '{"lat": -1.29, "lon": 36.82}', hint: "Coarse location at start" },
      { key: "utm", label: "Marketing attribution (JSON)", type: "textarea", placeholder: '{"source": "...", "campaign": "..."}' },
      { key: "collected_data", label: "Collected data (JSON)", type: "textarea", hint: "Data captured so far" },
    ],
  },
];

type J = Record<string, unknown>;

const asText = (v: unknown) => (v === undefined || v === null ? undefined : typeof v === "string" ? v : JSON.stringify(v));
const asList = (v: unknown) => (Array.isArray(v) ? v.join(", ") : v);

/** Full read-only journey record, grouped into sections. */
export function onboardingJourneyDetailSections(j: J): DetailSection[] {
  return [
    {
      title: "Journey",
      rows: [
        { label: "Code", value: j.code, kind: "mono" },
        { label: "Version", value: j.version },
        { label: "Name", value: j.name },
        { label: "Status", value: j.status, kind: "badge" },
        { label: "Customer type", value: j.customer_type, kind: "badge" },
        { label: "Country", value: j.country },
        { label: "Target KYC tier", value: j.target_tier, kind: "badge" },
        { label: "Expiry (days)", value: j.expiry_days },
      ],
    },
    {
      title: "Channels & products",
      rows: [
        { label: "Allowed channels", value: asList(j.channels) },
        { label: "Product ids", value: asList(j.product_ids) },
        { label: "Legal entity", value: j.legal_entity_id, kind: "mono" },
      ],
    },
    {
      title: "Definition & decision rules",
      rows: [
        { label: "Definition", value: asText(j.definition), kind: "mono" },
        { label: "Decision rules", value: asText(j.decision_rules), kind: "mono" },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Published at", value: j.published_at, kind: "datetime" },
        { label: "Published by", value: j.published_by },
        { label: "Retired at", value: j.retired_at, kind: "datetime" },
        { label: "Retirement reason", value: j.retirement_reason },
        { label: "Approval request", value: j.approval_request_id, kind: "mono" },
        { label: "Created", value: j.created_at, kind: "datetime" },
      ],
    },
  ];
}

/** Full read-only application record, grouped into sections. */
export function onboardingApplicationDetailSections(a: J): DetailSection[] {
  return [
    {
      title: "Application",
      rows: [
        { label: "Application number", value: a.application_number, kind: "mono" },
        { label: "Status", value: a.status, kind: "badge" },
        { label: "Channel", value: a.channel, kind: "badge" },
        { label: "Journey", value: a.onboarding_journey_id, kind: "mono" },
        { label: "Customer", value: a.customer_id, kind: "mono" },
        { label: "Legal entity", value: a.legal_entity_id, kind: "mono" },
        { label: "Current step", value: a.current_step },
        { label: "Progress", value: a.progress_percent, kind: "pct" },
      ],
    },
    {
      title: "Assisted by",
      hideWhenEmpty: true,
      rows: [
        { label: "Agent", value: a.agent_id, kind: "mono" },
        { label: "Branch", value: a.branch_id, kind: "mono" },
        { label: "Staff user", value: a.staff_user_id, kind: "mono" },
      ],
    },
    {
      title: "Applicant contact",
      rows: [
        { label: "Phone", value: a.phone },
        { label: "Email", value: a.email },
      ],
    },
    {
      title: "Products",
      rows: [
        { label: "Requested", value: asList(a.product_ids) },
        { label: "Opened", value: asText(a.created_product_refs), kind: "mono" },
      ],
    },
    {
      title: "Risk & decision",
      rows: [
        { label: "Risk score", value: a.risk_score },
        { label: "Risk level", value: a.risk_level, kind: "badge" },
        { label: "Decision", value: a.decision, kind: "badge" },
        { label: "Decision reasons", value: asText(a.decision_reasons), kind: "mono" },
        { label: "Risk factors", value: asText(a.risk_factors), kind: "mono" },
      ],
    },
    {
      title: "Device & attribution",
      hideWhenEmpty: true,
      rows: [
        { label: "Device fingerprint", value: a.device_fingerprint, kind: "mono" },
        { label: "IP address", value: a.ip_address, kind: "mono" },
        { label: "Geo location", value: asText(a.geo_location), kind: "mono" },
        { label: "Marketing attribution", value: asText(a.utm), kind: "mono" },
      ],
    },
    {
      title: "Review & lifecycle",
      rows: [
        { label: "Submitted by", value: a.submitted_by },
        { label: "Submitted at", value: a.submitted_at, kind: "datetime" },
        { label: "Reviewer", value: a.reviewer_id, kind: "mono" },
        { label: "Reviewed by", value: a.reviewed_by },
        { label: "Review comment", value: a.review_comment },
        { label: "Reviewed at", value: a.reviewed_at, kind: "datetime" },
        { label: "Rejection reason", value: a.rejection_reason },
        { label: "Expires at", value: a.expires_at, kind: "datetime" },
        { label: "Completed at", value: a.completed_at, kind: "datetime" },
        { label: "Created", value: a.created_at, kind: "datetime" },
      ],
    },
  ];
}
