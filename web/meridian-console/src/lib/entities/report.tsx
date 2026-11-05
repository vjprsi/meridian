import type { FormSection } from "@/components/entity-form";
import type { DetailSection } from "@/components/detail";

/** reporting.yaml enums (regulatory-reporting-service, kept in sync with spec/domains/reporting.yaml). */
export const REPORT_FREQUENCY = ["DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL", "AD_HOC", "EVENT_DRIVEN"];
export const REPORT_FORMAT = ["XBRL", "XML", "CSV", "XLSX", "JSON", "PDF", "FIXED_WIDTH"];
export const REPORT_TRIGGER = ["SCHEDULE", "MANUAL", "API", "RESUBMISSION"];
export const FILING_DAY_CONVENTION = ["FOLLOWING", "MODIFIED_FOLLOWING", "PRECEDING", "NONE"];
export const REPORT_DEFINITION_STATUS = ["DRAFT", "ACTIVE", "DEPRECATED"];
/** report_definition.report_family (free string with a documented value set). */
export const REPORT_FAMILY = ["BALANCE_SHEET", "INCOME_STATEMENT", "LIQUIDITY", "CAPITAL", "LARGE_EXPOSURE",
  "AML_CTR", "AML_SAR", "COMPLAINTS", "PAYMENTS_STATS", "MOBILE_MONEY", "CRS", "FATCA", "DEPOSIT_INSURANCE", "OTHER"];

/**
 * Full writable field set for producing a report run (report_run writable columns + the request-only
 * `execute` flag). Server-derived fields (version used, status, counts, revision, lineage, timings) are omitted.
 */
export const reportRunFormSections: FormSection[] = [
  {
    title: "Report & entity",
    description: "Which report definition to produce, and for which legal entity. The version in force at the as-of date is selected automatically.",
    fields: [
      { key: "report_definition_id", label: "Report definition", type: "text", required: true, span: 2, hint: "Active report definition id" },
      { key: "legal_entity_id", label: "Legal entity", type: "text", required: true, hint: "Reporting legal entity id" },
    ],
  },
  {
    title: "Reporting period",
    description: "The as-of (reporting) date is mandatory; the period defaults to the frequency window ending on that date.",
    fields: [
      { key: "as_of_date", label: "As-of date", type: "date", required: true },
      { key: "period_start", label: "Period start", type: "date", hint: "Default: start of the frequency period" },
      { key: "period_end", label: "Period end", type: "date", hint: "Default: as-of date" },
    ],
  },
  {
    title: "Execution",
    fields: [
      { key: "trigger", label: "Trigger", type: "select", options: REPORT_TRIGGER, hint: "Default MANUAL" },
      { key: "execute", label: "Execute immediately", type: "bool", hint: "Run now — uncheck to leave QUEUED for a later /execute" },
      { key: "parameters", label: "Run parameters", type: "json", hint: "JSON; available as {param.x} and param_x in expressions" },
    ],
  },
];

/**
 * Full writable field set for a report definition (report_definition writable columns). Status, active version,
 * status reason and the regulatory-pack provenance fields are server-managed and omitted.
 */
export const reportDefinitionFormSections: FormSection[] = [
  {
    title: "Identity",
    description: "The report and the country pack / regulator it belongs to.",
    fields: [
      { key: "code", label: "Report code", type: "text", required: true, hint: "e.g. NG-CBN-MBR300" },
      { key: "name", label: "Report name", type: "text", required: true, span: 2 },
      { key: "country_pack", label: "Country pack", type: "text", required: true, hint: "e.g. NG, KE, BR, GB" },
      { key: "jurisdiction", label: "Jurisdiction", type: "country", required: true },
      { key: "regulator", label: "Regulator / authority", type: "text", required: true },
      { key: "report_family", label: "Report family", type: "select", options: REPORT_FAMILY, required: true },
    ],
  },
  {
    title: "Submission",
    fields: [
      { key: "frequency", label: "Frequency", type: "select", options: REPORT_FREQUENCY, required: true },
      { key: "format", label: "Submission format", type: "select", options: REPORT_FORMAT, required: true, hint: "XBRL, XML, CSV and JSON are rendered by the service" },
    ],
  },
  {
    title: "Filing calendar",
    description: "How the deadline after the period end is computed and rolled for weekends and holidays.",
    fields: [
      { key: "deadline_rule", label: "Deadline rule", type: "text", hint: "PERIOD_END+<n>D (calendar) or PERIOD_END+<n>BD (business days)" },
      { key: "filing_offset_days", label: "Filing offset (days)", type: "number", hint: "Jurisdiction extension in calendar days (default 0)" },
      { key: "business_day_convention", label: "Business-day convention", type: "select", options: FILING_DAY_CONVENTION, hint: "Default FOLLOWING" },
      { key: "holidays", label: "Regulator holidays", type: "list", hint: "ISO dates, comma-separated (besides Saturdays and Sundays)" },
    ],
  },
  {
    title: "Governance",
    fields: [
      { key: "owner", label: "Accountable owner", type: "text", hint: "Finance / compliance" },
      { key: "description", label: "Description", type: "textarea", hint: "Purpose and scope" },
    ],
  },
];

type R = Record<string, unknown>;

/** The full report-run record, grouped into sections, for the run detail page. */
export function reportRunDetailSections(r: R): DetailSection[] {
  return [
    {
      title: "Run",
      rows: [
        { label: "Report definition", value: r.report_definition_id, kind: "mono" },
        { label: "Version used", value: r.report_definition_version_id, kind: "mono" },
        { label: "Legal entity", value: r.legal_entity_id, kind: "mono" },
        { label: "Status", value: r.status, kind: "badge" },
        { label: "Trigger", value: r.trigger, kind: "badge" },
        { label: "Revision", value: r.revision },
      ],
    },
    {
      title: "Reporting period",
      rows: [
        { label: "As-of date", value: r.as_of_date, kind: "date" },
        { label: "Period start", value: r.period_start, kind: "date" },
        { label: "Period end", value: r.period_end, kind: "date" },
      ],
    },
    {
      title: "Results",
      rows: [
        { label: "Rows / facts", value: r.row_count },
        { label: "Errors", value: r.error_count },
        { label: "Warnings", value: r.warning_count },
        { label: "Data snapshot", value: r.data_snapshot_id, kind: "mono" },
      ],
    },
    {
      title: "Lineage",
      hideWhenEmpty: true,
      rows: [
        { label: "Supersedes run", value: r.supersedes_run_id, kind: "mono" },
        { label: "Superseded by", value: r.superseded_by_run_id, kind: "mono" },
        { label: "Schedule", value: r.report_schedule_id, kind: "mono" },
      ],
    },
    {
      title: "Lifecycle",
      rows: [
        { label: "Started", value: r.started_at, kind: "datetime" },
        { label: "Finished", value: r.finished_at, kind: "datetime" },
        { label: "Error", value: r.error },
        { label: "Created by", value: r.created_by },
        { label: "Created", value: r.created_at, kind: "datetime" },
      ],
    },
  ];
}

/** The full report-definition record, grouped into sections, for the definition detail view. */
export function reportDefinitionDetailSections(d: R): DetailSection[] {
  return [
    {
      title: "Identity",
      rows: [
        { label: "Code", value: d.code, kind: "mono" },
        { label: "Name", value: d.name },
        { label: "Country pack", value: d.country_pack, kind: "badge" },
        { label: "Jurisdiction", value: d.jurisdiction },
        { label: "Regulator", value: d.regulator },
        { label: "Family", value: d.report_family, kind: "badge" },
      ],
    },
    {
      title: "Submission & status",
      rows: [
        { label: "Frequency", value: d.frequency, kind: "badge" },
        { label: "Format", value: d.format, kind: "badge" },
        { label: "Status", value: d.status, kind: "badge" },
        { label: "Active version", value: d.active_version },
        { label: "Status reason", value: d.status_reason },
      ],
    },
    {
      title: "Filing calendar",
      rows: [
        { label: "Deadline rule", value: d.deadline_rule, kind: "mono" },
        { label: "Filing offset (days)", value: d.filing_offset_days },
        { label: "Business-day convention", value: d.business_day_convention },
        { label: "Holidays", value: d.holidays },
      ],
    },
    {
      title: "Governance",
      rows: [
        { label: "Owner", value: d.owner },
        { label: "Description", value: d.description },
      ],
    },
    {
      title: "Regulatory pack",
      hideWhenEmpty: true,
      rows: [
        { label: "Pack code", value: d.pack_code, kind: "mono" },
        { label: "Pack version", value: d.pack_version, kind: "mono" },
        { label: "Pack checksum", value: d.pack_checksum, kind: "mono" },
      ],
    },
  ];
}
