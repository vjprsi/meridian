import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** risk.yaml enums for the case-management-service aml_case table. */
export const CASE_TYPES = ["AML", "FRAUD", "SANCTIONS", "KYC", "MULE", "TERRORIST_FINANCING", "BRIBERY_CORRUPTION"];
export const SEVERITIES = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];

/**
 * Full writable field set for opening an investigation case (writable columns of aml_case).
 * Excludes server-derived / read-only columns: case_number, status, opened_at, escalated_to,
 * escalation_reason, closed_at and ai_summary (set through /escalate, /close and /ai-summary).
 */
export const caseFormSections: FormSection[] = [
  {
    title: "Classification & priority",
    description: "What kind of case this is, how urgent it is and its aggregate risk.",
    fields: [
      { key: "case_type", label: "Case type", type: "select", options: CASE_TYPES, required: true },
      { key: "priority", label: "Priority", type: "select", options: SEVERITIES, hint: "Defaults to MEDIUM" },
      { key: "title", label: "Title", type: "text", required: true, span: 2, hint: "Short summary of the case" },
      { key: "risk_score", label: "Risk score", type: "number", hint: "Aggregate risk score (0–1000)" },
    ],
  },
  {
    title: "Subject & links",
    description: "The primary party under investigation and the amount in scope.",
    fields: [
      { key: "customer_id", label: "Primary subject", type: "text", span: 2, hint: "Customer UUID" },
      { key: "total_amount", label: "Total suspicious amount", type: "amount" },
      { key: "currency", label: "Currency", type: "currency" },
      { key: "tags", label: "Tags", type: "list", span: 2, hint: "Comma-separated free tags" },
    ],
  },
  {
    title: "Assignment & SLA",
    description: "Ownership and the regulatory / internal deadline.",
    fields: [
      { key: "assigned_to", label: "Investigator", type: "text", hint: "Assigned analyst" },
      { key: "team", label: "Team / queue", type: "text" },
      { key: "due_at", label: "Due", type: "datetime", hint: "Regulatory / internal deadline" },
    ],
  },
  {
    title: "Narrative",
    fields: [
      { key: "description", label: "Investigation scope", type: "textarea", hint: "What is being investigated and why" },
    ],
  },
];

type C = Record<string, unknown>;

/** The full case profile, grouped into sections, for the detail page. */
export function caseDetailSections(c: C): DetailSection[] {
  return [
    {
      title: "Classification & priority",
      rows: [
        { label: "Case number", value: c.case_number, kind: "mono" },
        { label: "Type", value: c.case_type, kind: "badge" },
        { label: "Status", value: c.status, kind: "badge" },
        { label: "Priority", value: c.priority, kind: "badge" },
        { label: "Risk score", value: c.risk_score },
      ],
    },
    {
      title: "Subject & links",
      rows: [
        { label: "Primary subject", value: c.customer_id, kind: "mono" },
        { label: "Total amount", value: c.total_amount, kind: "money", currency: c.currency as string },
        { label: "Currency", value: c.currency },
        { label: "Tags", value: c.tags },
      ],
    },
    {
      title: "Assignment & SLA",
      rows: [
        { label: "Investigator", value: c.assigned_to },
        { label: "Team", value: c.team },
        { label: "Opened", value: c.opened_at, kind: "datetime" },
        { label: "Due", value: c.due_at, kind: "datetime" },
        { label: "Escalated to", value: c.escalated_to },
        { label: "Escalation reason", value: c.escalation_reason },
        { label: "Closed", value: c.closed_at, kind: "datetime" },
      ],
    },
    {
      title: "Narrative",
      rows: [
        { label: "Description", value: c.description },
        { label: "AI summary", value: c.ai_summary },
      ],
    },
  ];
}
