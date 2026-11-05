import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** product.yaml enums (kept in sync with spec/domains/product.yaml). */
export const PRODUCT_FAMILY = [
  "CURRENT_ACCOUNT", "SAVINGS", "TERM_DEPOSIT", "CALL_DEPOSIT", "NOTICE_DEPOSIT", "WALLET",
  "PREPAID_CARD", "DEBIT_CARD", "CREDIT_CARD", "CORPORATE_CARD", "INSTALMENT_LOAN", "CREDIT_LINE",
  "BNPL", "OVERDRAFT", "MORTGAGE", "NANO_LOAN", "MERCHANT_FINANCING", "ESCROW", "VIRTUAL_ACCOUNT",
];
export const PRODUCT_SEGMENT = [
  "RETAIL", "SME", "CORPORATE", "PRIVATE", "YOUTH", "STUDENT", "SENIOR", "STAFF", "GOVERNMENT", "NON_PROFIT",
];
export const PRODUCT_STATUS = ["DRAFT", "ACTIVE", "SUSPENDED", "RETIRED"];
export const PRODUCT_VERSION_STATUS = [
  "DRAFT", "PENDING_APPROVAL", "APPROVED", "PUBLISHED", "SUPERSEDED", "REJECTED", "RETIRED",
];
export const MIGRATION_POLICY = ["PIN", "MIGRATE_ON_RENEWAL", "FORCE_MIGRATE"];
export const PRODUCT_CHANNEL = ["MOBILE", "WEB", "BRANCH", "AGENT", "USSD", "API", "CALL_CENTER", "PARTNER"];

/**
 * The full writable field set of product-catalog-service `product` (every column except the
 * server-derived ones: status is driven by lifecycle actions, current_version_id is set on
 * publish, and the json/json[] columns — available_channels, available_countries, metadata —
 * are structured and configured on the version/availability surfaces, not this create form).
 */
export const productFormSections: FormSection[] = [
  {
    title: "Identity & classification",
    description: "What the product is, which family it belongs to and who it is sold to.",
    fields: [
      { key: "code", label: "Product code", type: "text", required: true, placeholder: "e.g. SAV-GOLD-USD" },
      { key: "name", label: "Marketing name", type: "text", required: true, span: 2 },
      { key: "family", label: "Product family", type: "select", options: PRODUCT_FAMILY, required: true },
      { key: "segment", label: "Target segment", type: "select", options: PRODUCT_SEGMENT, required: true },
      { key: "currency", label: "Product currency", type: "currency", required: true },
      { key: "legal_entity_id", label: "Issuing legal entity", type: "text", required: true, hint: "Bank entity that issues the product" },
      { key: "template_id", label: "Template", type: "text", hint: "Template the product was created from" },
      { key: "owner_user_id", label: "Product manager", type: "text", hint: "Owning product manager" },
    ],
  },
  {
    title: "Marketing copy",
    fields: [
      { key: "short_description", label: "Short description", type: "text", span: 3, hint: "One-line description for channels" },
      { key: "long_description", label: "Long description", type: "textarea", hint: "Full marketing description" },
    ],
  },
  {
    title: "Sales & availability",
    description: "When the product goes on and off sale.",
    fields: [
      { key: "launch_date", label: "Launch date", type: "date" },
      { key: "retire_date", label: "Retire date", type: "date", hint: "Date it stops being sold" },
      { key: "is_sellable", label: "Sellable", type: "bool", hint: "New sales allowed" },
    ],
  },
];

/**
 * The writable metadata of a `product_version`. version_no, status and all maker-checker /
 * checksum columns are server-derived (ro); the resolved `definition` snapshot is built from
 * the parameter/fee/rate sub-resources, so a draft is created with this metadata and populated
 * afterwards. product_id is pre-filled from the selected product.
 */
export const productVersionFormSections: FormSection[] = [
  {
    title: "Version",
    description: "Create a new draft version; its parameters, rates and fees are configured after it is drafted.",
    fields: [
      { key: "product_id", label: "Product", type: "text", required: true, hint: "Product this version belongs to" },
      { key: "migration_policy", label: "Migration policy", type: "select", options: MIGRATION_POLICY, required: true,
        hint: "PIN keeps existing accounts; MIGRATE_ON_RENEWAL / FORCE_MIGRATE move them" },
      { key: "effective_from", label: "Effective from", type: "datetime", hint: "When new sales start using this version" },
      { key: "effective_to", label: "Effective to", type: "datetime", hint: "When it stops applying to new sales" },
      { key: "change_summary", label: "Change summary", type: "textarea", hint: "What changed compared to the previous version" },
    ],
  },
];

type P = Record<string, unknown>;

/** The full catalogue record of a product, grouped for a detail page. */
export function productDetailSections(p: P): DetailSection[] {
  return [
    {
      title: "Identity & classification",
      rows: [
        { label: "Code", value: p.code, kind: "mono" },
        { label: "Name", value: p.name },
        { label: "Family", value: p.family, kind: "badge" },
        { label: "Segment", value: p.segment, kind: "badge" },
        { label: "Currency", value: p.currency },
        { label: "Status", value: p.status, kind: "badge" },
        { label: "Sellable", value: p.is_sellable, kind: "bool" },
        { label: "Legal entity", value: p.legal_entity_id, kind: "mono" },
        { label: "Template", value: p.template_id, kind: "mono" },
        { label: "Owner", value: p.owner_user_id, kind: "mono" },
      ],
    },
    {
      title: "Marketing copy",
      hideWhenEmpty: true,
      rows: [
        { label: "Short description", value: p.short_description },
        { label: "Long description", value: p.long_description },
      ],
    },
    {
      title: "Availability",
      rows: [
        { label: "Channels", value: p.available_channels },
        { label: "Countries", value: p.available_countries },
        { label: "Launch date", value: p.launch_date, kind: "date" },
        { label: "Retire date", value: p.retire_date, kind: "date" },
      ],
    },
    {
      title: "Versioning & lifecycle",
      rows: [
        { label: "Current version", value: p.current_version_id, kind: "mono" },
        { label: "Metadata", value: p.metadata ? JSON.stringify(p.metadata) : undefined, kind: "mono" },
        { label: "Created", value: p.created_at, kind: "datetime" },
      ],
    },
  ];
}

/** The full definition of a single product version, grouped for a detail view. */
export function productVersionDetailSections(v: P): DetailSection[] {
  return [
    {
      title: "Version",
      rows: [
        { label: "Product", value: v.product_id, kind: "mono" },
        { label: "Version", value: v.version_no },
        { label: "Status", value: v.status, kind: "badge" },
        { label: "Migration policy", value: v.migration_policy, kind: "badge" },
        { label: "Effective from", value: v.effective_from, kind: "datetime" },
        { label: "Effective to", value: v.effective_to, kind: "datetime" },
        { label: "Change summary", value: v.change_summary },
        { label: "Checksum", value: v.checksum, kind: "mono" },
      ],
    },
    {
      title: "Approval (maker-checker)",
      rows: [
        { label: "Submitted by", value: v.submitted_by },
        { label: "Submitted at", value: v.submitted_at, kind: "datetime" },
        { label: "Approved by", value: v.approved_by },
        { label: "Approved at", value: v.approved_at, kind: "datetime" },
        { label: "Rejected by", value: v.rejected_by },
        { label: "Rejected at", value: v.rejected_at, kind: "datetime" },
        { label: "Rejection reason", value: v.rejection_reason },
        { label: "Published at", value: v.published_at, kind: "datetime" },
        { label: "Approval request", value: v.approval_request_id, kind: "mono" },
      ],
    },
  ];
}
