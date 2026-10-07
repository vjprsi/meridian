"use client";

import { useQuery } from "@tanstack/react-query";

/* ---------------------------------------------------------------------------------------------------------------
 * Loading and walking the contracts that scripts/sync-specs.mjs copies into public/specs.
 * Only the subset of OpenAPI 3.1 / AsyncAPI 3 that the Meridian generator emits is modelled.
 * ------------------------------------------------------------------------------------------------------------- */

export type Json = Record<string, unknown>;

export interface Schema {
  $ref?: string;
  type?: string | string[];
  format?: string;
  description?: string;
  enum?: unknown[];
  const?: unknown;
  items?: Schema;
  properties?: Record<string, Schema>;
  required?: string[];
  additionalProperties?: boolean | Schema;
  allOf?: Schema[];
  oneOf?: Schema[];
  anyOf?: Schema[];
  readOnly?: boolean;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
  pattern?: string;
  default?: unknown;
  example?: unknown;
}

export interface Parameter {
  $ref?: string;
  name: string;
  in: "query" | "path" | "header" | "cookie";
  required?: boolean;
  description?: string;
  schema?: Schema;
}

export interface Response {
  $ref?: string;
  description?: string;
  content?: Record<string, { schema?: Schema }>;
  headers?: Record<string, { description?: string; schema?: Schema }>;
}

export interface Operation {
  operationId: string;
  summary?: string;
  description?: string;
  tags?: string[];
  parameters?: Parameter[];
  requestBody?: { required?: boolean; content?: Record<string, { schema?: Schema }> };
  responses?: Record<string, Response>;
  security?: Record<string, string[]>[];
  deprecated?: boolean;
  "x-required-scope"?: string;
  "x-service"?: string;
  "x-requirements"?: string[];
  "x-table"?: string;
}

export interface OpenApiDoc {
  openapi: string;
  info: { title: string; version: string; description?: string };
  servers?: { url: string; description?: string }[];
  tags?: { name: string; description?: string }[];
  paths: Record<string, Partial<Record<Method, Operation>> & { parameters?: Parameter[] }>;
  components?: {
    schemas?: Record<string, Schema>;
    parameters?: Record<string, Parameter>;
    responses?: Record<string, Response>;
  };
}

export type Method = "get" | "post" | "put" | "patch" | "delete";
export const METHODS: Method[] = ["get", "post", "put", "patch", "delete"];

export interface FlatOperation extends Operation {
  method: Method;
  path: string;
  tag: string;
}

export interface Catalog {
  generated_at: string;
  domains: {
    id: string; title: string; description: string; version: string; operations: number; tags: number;
    scopes: string[]; services: { name: string; description: string; phase: number }[];
  }[];
  scopes: { scope: string; domain: string; description: string }[];
  events: { channels: number; messages: number };
}

export interface EventMessage {
  key: string;
  name: string;
  title?: string;
  summary?: string;
  headers?: Schema;
  payload?: Schema;
}

export interface EventsDoc {
  info: { title: string; version: string; description?: string };
  defaultContentType?: string;
  channels: { id: string; address: string; description?: string; messages: EventMessage[] }[];
  schemas: Record<string, Schema>;
}

/** One search-index row: k = "op" | "ev"; d = domain/channel; id = operationId or event type; m/p = method/path. */
export interface SearchEntry { k: "op" | "ev"; d: string; id: string; m: string; p: string; s: string; t: string; sc: string; sv: string }

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: ${res.status} (run npm run sync:specs)`);
  return res.json() as Promise<T>;
}

const forever = { staleTime: Infinity, gcTime: Infinity, retry: 1 } as const;

export function useCatalog() {
  return useQuery({ queryKey: ["spec", "catalog"], queryFn: () => getJson<Catalog>("/specs/catalog.json"), ...forever });
}

export function useDomainSpec(domain: string | undefined) {
  return useQuery({
    queryKey: ["spec", "openapi", domain],
    queryFn: () => getJson<OpenApiDoc>(`/specs/openapi/${domain}.json`),
    enabled: !!domain,
    ...forever,
  });
}

export function useEvents() {
  return useQuery({ queryKey: ["spec", "events"], queryFn: () => getJson<EventsDoc>("/specs/asyncapi/events.json"), ...forever });
}

export function useSearchIndex(enabled = true) {
  return useQuery({
    queryKey: ["spec", "search"], queryFn: () => getJson<SearchEntry[]>("/specs/search-index.json"), enabled, ...forever,
  });
}

/** Resolves a local JSON pointer ("#/components/schemas/X") against a document. */
export function deref<T>(doc: Json, ref: string): T | undefined {
  if (!ref.startsWith("#/")) return undefined;
  let cur: unknown = doc;
  for (const part of ref.slice(2).split("/")) {
    if (cur === null || typeof cur !== "object") return undefined;
    cur = (cur as Json)[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  }
  return cur as T;
}

export function refName(ref: string): string {
  return ref.split("/").pop() ?? ref;
}

/** Follows $ref chains for parameters / responses (schemas are resolved lazily by the tree). */
export function resolve<T extends { $ref?: string }>(doc: Json, v: T): T {
  let cur = v;
  for (let i = 0; i < 10 && cur?.$ref; i++) cur = deref<T>(doc, cur.$ref) ?? cur;
  return cur;
}

/** Flattens paths into operations grouped by first tag, preserving the contract's tag order. */
export function operationsByTag(doc: OpenApiDoc): { tag: string; description?: string; ops: FlatOperation[] }[] {
  const groups = new Map<string, FlatOperation[]>();
  for (const t of doc.tags ?? []) groups.set(t.name, []);
  for (const [path, item] of Object.entries(doc.paths)) {
    for (const method of METHODS) {
      const op = item[method];
      if (!op) continue;
      const tag = op.tags?.[0] ?? "Other";
      if (!groups.has(tag)) groups.set(tag, []);
      groups.get(tag)!.push({ ...op, parameters: [...(item.parameters ?? []), ...(op.parameters ?? [])], method, path, tag });
    }
  }
  const desc = new Map((doc.tags ?? []).map((t) => [t.name, t.description]));
  return [...groups.entries()].filter(([, ops]) => ops.length > 0)
    .map(([tag, ops]) => ({ tag, description: desc.get(tag), ops }));
}

export function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

export function typeLabel(s: Schema): string {
  if (s.$ref) return refName(s.$ref);
  const t = Array.isArray(s.type) ? s.type.filter((x) => x !== "null").join(" | ") + (s.type.includes("null") ? " | null" : "") : s.type;
  if (t === "array" || (Array.isArray(s.type) && s.type.includes("array"))) {
    const inner = s.items && (s.items.$ref || s.items.type) ? typeLabel(s.items) : "any";
    return `${inner}[]${Array.isArray(s.type) && s.type.includes("null") ? " | null" : ""}`;
  }
  if (s.allOf) return s.allOf.map(typeLabel).join(" & ");
  if (s.oneOf) return s.oneOf.map(typeLabel).join(" | ");
  if (s.anyOf) return s.anyOf.map(typeLabel).join(" | ");
  return (t ?? (s.properties ? "object" : "any")) + (s.format ? ` (${s.format})` : "");
}

/** Builds an example value from a schema (used for curl bodies and the try-it console). */
export function exampleOf(doc: Json, s: Schema | undefined, depth = 0, seen: string[] = []): unknown {
  if (!s || depth > 6) return undefined;
  if (s.$ref) {
    if (seen.includes(s.$ref)) return {};
    return exampleOf(doc, deref<Schema>(doc, s.$ref), depth + 1, [...seen, s.$ref]);
  }
  if (s.example !== undefined) return s.example;
  if (s.const !== undefined) return s.const;
  if (s.default !== undefined) return s.default;
  if (s.enum?.length) return s.enum[0];
  if (s.allOf) return Object.assign({}, ...s.allOf.map((x) => exampleOf(doc, x, depth + 1, seen) ?? {}));
  if (s.oneOf?.length) return exampleOf(doc, s.oneOf[0], depth + 1, seen);
  const type = Array.isArray(s.type) ? s.type.find((t) => t !== "null") : s.type;
  switch (type) {
    case "string":
      switch (s.format) {
        case "uuid": return "0199a000-0000-7000-8000-000000000001";
        case "date-time": return "2026-01-01T00:00:00Z";
        case "date": return "2026-01-01";
        case "email": return "dev@example.com";
        case "uri": return "https://example.com";
        case "decimal": return "100.00";
        default: return s.maxLength && s.maxLength <= 3 ? "USD".slice(0, s.maxLength) : "string";
      }
    case "integer": return s.minimum ?? 0;
    case "number": return s.minimum ?? 0;
    case "boolean": return false;
    case "array": return s.items && (s.items.$ref || s.items.type) ? [exampleOf(doc, s.items, depth + 1, seen)] : [];
    default: {
      if (!s.properties) return {};
      const o: Json = {};
      // Request examples include required fields plus the first few optional ones; read-only fields are omitted.
      const req = new Set(s.required ?? []);
      const keys = Object.keys(s.properties).filter((k) => !s.properties![k].readOnly && !k.startsWith("custom_field_"));
      const pick = req.size ? keys.filter((k) => req.has(k)) : keys.slice(0, 4);
      for (const k of pick) o[k] = byName(k, s.properties[k]) ?? exampleOf(doc, s.properties[k], depth + 1, seen);
      return o;
    }
  }
}

/** Realistic sample values for common plain-string field names (enums and formats take precedence). */
function byName(name: string, s: Schema): unknown {
  const type = Array.isArray(s.type) ? s.type.find((t) => t !== "null") : s.type;
  if (type !== "string" || s.enum || s.format || s.$ref) return undefined;
  if (/(^|_)(url|uri)$/.test(name)) return "https://example.com/webhooks/meridian";
  if (/currency$/.test(name)) return "USD";
  if (/(^|_)amount$/.test(name)) return "100.00";
  if (/country$/.test(name)) return "US";
  if (/(^|_)email$/.test(name)) return "dev@example.com";
  if (/(^|_)name$/.test(name)) return "Example";
  return undefined;
}

/** Example payload including every non-custom field (event payloads, responses). */
export function fullExample(doc: Json, s: Schema | undefined, depth = 0, seen: string[] = []): unknown {
  if (!s || depth > 4) return undefined;
  if (s.$ref) {
    if (seen.includes(s.$ref)) return {};
    return fullExample(doc, deref<Schema>(doc, s.$ref), depth + 1, [...seen, s.$ref]);
  }
  if (s.properties) {
    const o: Json = {};
    for (const [k, v] of Object.entries(s.properties)) {
      if (!k.startsWith("custom_field_")) o[k] = byName(k, v) ?? fullExample(doc, v, depth + 1, seen);
    }
    return o;
  }
  if (s.items && (Array.isArray(s.type) ? s.type.includes("array") : s.type === "array")) {
    return [fullExample(doc, s.items, depth + 1, seen)];
  }
  return exampleOf(doc, s, depth, seen);
}
