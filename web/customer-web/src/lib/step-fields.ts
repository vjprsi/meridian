import type { StepFieldDef } from "@/api/types";

/**
 * Client-side validation of a journey step's typed fields. It mirrors onboarding-service StepField.validate so the
 * applicant sees the same rules before submitting (the server stays authoritative and its `input.<field>` errors are
 * shown next to the field too).
 */

/** Values only the bank sets (never sent by the applicant; the server rejects them with server_managed). */
export const SERVER_MANAGED = new Set(["kyc_profile_id", "kyc_document_id", "customer_id"]);

export const E164 = /^\+[1-9][0-9]{6,14}$/;
export const EMAIL = /^[^@\s]{1,64}@[^@\s]+\.[^@\s]{2,}$/;
const COUNTRY = /^[A-Z]{2}$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Error codes of StepField.validate (plus the server-only ones), used as message keys `fieldError.<code>`. */
export const FIELD_ERROR_CODES = ["required", "invalid_type", "invalid_date", "invalid_email", "invalid_phone",
  "invalid_country", "invalid_value", "invalid_document", "too_short", "too_long", "pattern_mismatch", "below_min",
  "above_max", "unknown_field", "server_managed", "document_not_found", "must_accept"] as const;
export type FieldErrorCode = (typeof FIELD_ERROR_CODES)[number];

export interface FieldIssue {
  code: FieldErrorCode;
  vars?: Record<string, string | number>;
}

/** Form state of one field: text for inputs, a boolean for checkboxes (files are kept separately). */
export type RawValue = string | boolean;

/** Fields the applicant fills in (bank-managed ones are filled by the server from the application). */
export function applicantFields(fields: unknown): StepFieldDef[] {
  if (!Array.isArray(fields)) return [];
  return fields
    .map((f): StepFieldDef | null => {
      if (typeof f === "string") return { name: f, type: "STRING", label_key: `onboarding.field.${f}`, required: true };
      if (!f || typeof f !== "object" || typeof (f as StepFieldDef).name !== "string") return null;
      const d = f as Partial<StepFieldDef> & { name: string };
      return { ...d, type: d.type ?? "STRING", label_key: d.label_key ?? `onboarding.field.${d.name}`, required: d.required ?? true };
    })
    .filter((f): f is StepFieldDef => !!f && !SERVER_MANAGED.has(f.name));
}

export function initialValue(f: StepFieldDef, previous: unknown): RawValue {
  if (f.type === "BOOLEAN") return previous === true;
  return previous === undefined || previous === null || typeof previous === "object" ? "" : String(previous);
}

/**
 * The JSON value sent for a field (undefined = omitted, i.e. blank): integers as numbers, decimals as strings (the
 * server accepts both and a string keeps the precision), phone numbers without spaces, country codes upper case.
 */
export function toJson(f: StepFieldDef, raw: RawValue | undefined): unknown {
  if (f.type === "BOOLEAN") return raw === true;
  const s = typeof raw === "string" ? raw.trim() : "";
  if (s === "") return undefined;
  switch (f.type) {
    case "INTEGER": return /^-?\d+$/.test(s) && Number.isSafeInteger(Number(s)) ? Number(s) : s;
    case "PHONE": return s.replace(/[\s\-().]/g, "");
    case "COUNTRY": return s.toUpperCase();
    default: return s;
  }
}

function patternOk(pattern: string, text: string): boolean {
  try {
    return new RegExp(`^(?:${pattern})$`).test(text); // Java Matcher.matches(): the whole value must match
  } catch {
    return true; // a pattern JavaScript cannot compile is left to the server
  }
}

const blank = (v: unknown) => v === undefined || v === null || (typeof v === "string" && v.trim() === "");

/**
 * Validates the value that will be sent (see toJson), like the server: required, then the type, then length and
 * pattern on string values and min / max on numbers. Returns the first problem, or null.
 * `mustAccept`: a required checkbox of a terms or signature step has to be ticked (the server only checks presence).
 */
export function validateField(f: StepFieldDef, v: unknown, mustAccept = false): FieldIssue | null {
  if (blank(v)) return f.required ? { code: "required" } : null;
  const text = typeof v === "string" ? v : null;
  const range = (n: number): FieldIssue | null => {
    if (f.min !== undefined && f.min !== null && n < Number(f.min)) return { code: "below_min", vars: { min: String(f.min) } };
    if (f.max !== undefined && f.max !== null && n > Number(f.max)) return { code: "above_max", vars: { max: String(f.max) } };
    return null;
  };
  switch (f.type) {
    case "STRING": case "TEXT":
      if (text === null) return { code: "invalid_type" };
      break;
    case "INTEGER": {
      if (typeof v !== "number" || !Number.isInteger(v)) return { code: "invalid_type" };
      return range(v);
    }
    case "DECIMAL": {
      const n = typeof v === "number" ? v : text !== null && /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text) ? Number(text) : NaN;
      if (Number.isNaN(n)) return { code: "invalid_type" };
      const r = range(n);
      if (r) return r;
      break;
    }
    case "BOOLEAN":
      if (typeof v !== "boolean") return { code: "invalid_type" };
      return mustAccept && f.required && !v ? { code: "must_accept" } : null;
    case "DATE":
      if (text === null || !DATE.test(text) || Number.isNaN(Date.parse(`${text}T00:00:00Z`))
        || new Date(`${text}T00:00:00Z`).toISOString().slice(0, 10) !== text) return { code: "invalid_date" };
      break;
    case "EMAIL":
      if (text === null || !EMAIL.test(text)) return { code: "invalid_email" };
      break;
    case "PHONE":
      if (text === null || !E164.test(text)) return { code: "invalid_phone" };
      break;
    case "COUNTRY":
      if (text === null || !COUNTRY.test(text)) return { code: "invalid_country" };
      break;
    case "ENUM":
      if (text === null || !(f.enum ?? []).includes(text)) return { code: "invalid_value" };
      break;
    case "DOCUMENT":
      if (text === null || !UUID.test(text)) return { code: "invalid_document" };
      break;
    default:
      return null;
  }
  if (text !== null) {
    if (f.min_length !== undefined && f.min_length !== null && text.length < f.min_length) return { code: "too_short", vars: { min: f.min_length } };
    if (f.max_length !== undefined && f.max_length !== null && text.length > f.max_length) return { code: "too_long", vars: { max: f.max_length } };
    if (f.pattern && !patternOk(f.pattern, text)) return { code: "pattern_mismatch" };
  }
  return null;
}

/** Field errors of a problem (`input.<name>` -> {code, message}), for showing server validation next to the fields. */
export function serverFieldErrors(errors: { field: string; code: string; message: string }[] | undefined, prefix = "input."):
  Record<string, { code: string; message: string }> {
  const out: Record<string, { code: string; message: string }> = {};
  for (const e of errors ?? []) {
    if (e.field?.startsWith(prefix)) {
      const name = e.field.slice(prefix.length);
      if (!out[name]) out[name] = { code: e.code, message: e.message };
    }
  }
  return out;
}

/** Document type for a DOCUMENT field, from its name and the step type (null: the applicant chooses an ID type). */
export function documentTypeFor(fieldName: string, stepType: string): string | null {
  const n = fieldName.toLowerCase();
  if (stepType === "SELFIE_LIVENESS" || n.includes("selfie")) return "SELFIE";
  if (n.includes("video")) return "VIDEO";
  if (n.includes("address") || n.includes("utility")) return "PROOF_OF_ADDRESS";
  if (n.includes("statement")) return "BANK_STATEMENT";
  if (n.includes("passport")) return "PASSPORT";
  if (n.includes("driving") || n.includes("licen")) return "DRIVING_LICENSE";
  if (n.includes("residence") || n.includes("permit")) return "RESIDENCE_PERMIT";
  if (n.includes("national") || n.includes("id_card")) return "ID_CARD";
  return null;
}

export const ID_DOCUMENT_TYPES = ["ID_CARD", "PASSPORT", "DRIVING_LICENSE", "RESIDENCE_PERMIT"] as const;

/** ISO 3166-1 alpha-2 codes (names come from Intl.DisplayNames in the applicant's language). */
export const COUNTRIES = ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS "
  + "BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ "
  + "FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT "
  + "JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP "
  + "MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE "
  + "RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV "
  + "TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(" ");
