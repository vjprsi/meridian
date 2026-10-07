"use client";

import Link from "next/link";
import { useState } from "react";
import {
  deref, exampleOf, fullExample, refName, resolve, typeLabel,
  type FlatOperation, type Json, type OpenApiDoc, type Parameter, type Response, type Schema,
} from "@/lib/spec";
import { CodeBlock, MethodBadge } from "./code";
import { cx } from "./ui";

/* ---------------------------------------------------------------------------------------------------------------
 * Lightweight OpenAPI viewer: schema trees, parameter tables, responses and example curl for one operation.
 * ------------------------------------------------------------------------------------------------------------- */

const SANDBOX_FALLBACK = "https://sandbox.api.meridian.example";

function isObjectLike(doc: Json, s: Schema): boolean {
  const r = s.$ref ? deref<Schema>(doc, s.$ref) : s;
  if (!r) return false;
  if (r.properties || r.allOf || r.oneOf || r.anyOf) return true;
  const items = r.items;
  return !!items && (Array.isArray(r.type) ? r.type.includes("array") : r.type === "array") && isObjectLike(doc, items);
}

/** Expands $ref / allOf / array items into the object whose properties should be listed. */
function bodyOf(doc: Json, s: Schema, seen: string[]): { schema: Schema; seen: string[] } | null {
  let cur = s;
  let chain = seen;
  for (let i = 0; i < 6; i++) {
    if (cur.$ref) {
      if (chain.includes(cur.$ref)) return null;
      chain = [...chain, cur.$ref];
      cur = deref<Schema>(doc, cur.$ref) ?? {};
      continue;
    }
    if (cur.items && !cur.properties) { cur = cur.items; continue; }
    if (cur.allOf) {
      const merged: Schema = { properties: {}, required: [] };
      for (const part of cur.allOf) {
        const b = bodyOf(doc, part, chain);
        if (b) {
          Object.assign(merged.properties!, b.schema.properties ?? {});
          merged.required!.push(...(b.schema.required ?? []));
        }
      }
      return { schema: merged, seen: chain };
    }
    return { schema: cur, seen: chain };
  }
  return null;
}

function Constraints({ s }: { s: Schema }) {
  const bits: string[] = [];
  if (s.maxLength !== undefined) bits.push(`≤ ${s.maxLength} chars`);
  if (s.minimum !== undefined) bits.push(`min ${s.minimum}`);
  if (s.maximum !== undefined) bits.push(`max ${s.maximum}`);
  if (s.pattern) bits.push(`pattern ${s.pattern}`);
  if (s.default !== undefined) bits.push(`default ${JSON.stringify(s.default)}`);
  if (!bits.length) return null;
  return <span className="text-[11px] text-muted">{bits.join(" · ")}</span>;
}

function SchemaRow({ doc, name, schema, required, depth, seen }:
  { doc: Json; name: string; schema: Schema; required: boolean; depth: number; seen: string[] }) {
  const expandable = isObjectLike(doc, schema);
  const [open, setOpen] = useState(depth < 1 && expandable && !name.startsWith("custom_field_"));
  const target = schema.$ref ? deref<Schema>(doc, schema.$ref) : schema;
  const enumVals = schema.enum ?? target?.enum;
  const description = schema.description ?? target?.description;
  return (
    <li className="border-t border-border first:border-t-0">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-1.5">
        {expandable ? (
          <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}
            className="w-4 text-left font-mono text-xs text-muted hover:text-text">{open ? "−" : "+"}</button>
        ) : <span className="w-4" />}
        <span className="font-mono text-[12.5px] font-medium">{name}</span>
        <span className="font-mono text-[11.5px] text-accent">{typeLabel(schema)}</span>
        {required && <span className="text-[10.5px] font-semibold uppercase text-bad">required</span>}
        {(schema.readOnly || target?.readOnly) && <span className="text-[10.5px] uppercase text-muted">read-only</span>}
        <Constraints s={schema} />
      </div>
      {(description || enumVals) && (
        <div className="pb-1.5 pl-6 text-xs text-muted">
          {description && <p>{description}</p>}
          {enumVals && (
            <p className="mt-0.5 flex flex-wrap gap-1">
              {enumVals.map((v) => <code key={String(v)} className="rounded bg-surface-2 px-1 font-mono text-[11px] text-text">{String(v)}</code>)}
            </p>
          )}
        </div>
      )}
      {open && expandable && (
        <div className="mb-2 ml-6 border-l border-border pl-3">
          <SchemaTree doc={doc} schema={schema} depth={depth + 1} seen={seen} />
        </div>
      )}
    </li>
  );
}

/** Property tree of a schema; nested objects expand on demand, recursive $refs stop at the repeat. */
export function SchemaTree({ doc, schema, depth = 0, seen = [] }: { doc: Json; schema: Schema; depth?: number; seen?: string[] }) {
  const b = bodyOf(doc, schema, seen);
  if (!b) return <p className="text-xs text-muted">↺ recursive reference</p>;
  const s = b.schema;
  if (!s.properties) {
    return <p className="py-1 font-mono text-xs text-muted">{typeLabel(schema)}{s.additionalProperties ? " (free-form object)" : ""}</p>;
  }
  const req = new Set(s.required ?? []);
  const entries = Object.entries(s.properties);
  const regular = entries.filter(([k]) => !k.startsWith("custom_field_"));
  const custom = entries.filter(([k]) => k.startsWith("custom_field_"));
  return (
    <ul className="text-sm">
      {regular.map(([k, v]) => (
        <SchemaRow key={k} doc={doc} name={k} schema={v} required={req.has(k)} depth={depth} seen={b.seen} />
      ))}
      {custom.length > 0 && (
        <li className="border-t border-border py-1.5 pl-6 text-xs text-muted">
          <span className="font-mono">custom_field_1 … custom_field_{custom.length}</span> — tenant-defined custom fields (string, ≤ 500 chars)
        </li>
      )}
    </ul>
  );
}

function ParamTable({ doc, params }: { doc: Json; params: Parameter[] }) {
  const resolved = params.map((p) => resolve(doc, p));
  const custom = resolved.filter((p) => /^custom_field_\d$/.test(p.name));
  const shown = resolved.filter((p) => !/^custom_field_\d$/.test(p.name));
  return (
    <table className="w-full text-sm">
      <tbody>
        {shown.map((p) => (
          <tr key={`${p.in}:${p.name}`} className="border-t border-border first:border-t-0 align-top">
            <td className="w-56 py-1.5 pr-3">
              <span className="font-mono text-[12.5px] font-medium">{p.name}</span>
              <span className="ml-2 text-[10.5px] uppercase text-muted">{p.in}</span>
              {p.required && <span className="ml-2 text-[10.5px] font-semibold uppercase text-bad">required</span>}
            </td>
            <td className="py-1.5 font-mono text-[11.5px] text-accent">{p.schema ? typeLabel(p.schema) : "string"}</td>
            <td className="py-1.5 text-xs text-muted">
              {p.description}
              {p.schema?.enum && (
                <span className="mt-0.5 flex flex-wrap gap-1">
                  {p.schema.enum.map((v) => <code key={String(v)} className="rounded bg-surface-2 px-1 font-mono text-[11px] text-text">{String(v)}</code>)}
                </span>
              )}
              {p.schema && <Constraints s={p.schema} />}
            </td>
          </tr>
        ))}
        {custom.length > 0 && (
          <tr className="border-t border-border">
            <td colSpan={3} className="py-1.5 text-xs text-muted">
              <span className="font-mono">custom_field_1 … custom_field_{custom.length}</span> (query) — exact-match filters on tenant-defined custom fields
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

export function opNotes(doc: Json, op: FlatOperation) {
  const params = (op.parameters ?? []).map((p) => resolve(doc, p));
  const idempotent = params.some((p) => p.in === "header" && p.name === "Idempotency-Key");
  const ifMatch = params.some((p) => p.in === "header" && p.name === "If-Match");
  const paged = params.some((p) => p.in === "query" && p.name === "cursor");
  const okSchema = Object.entries(op.responses ?? {}).filter(([c]) => c.startsWith("2"))
    .map(([, r]) => resolve(doc, r).content?.["application/json"]?.schema).find(Boolean);
  const okTarget = okSchema?.$ref ? deref<Schema>(doc, okSchema.$ref) : okSchema;
  const etag = op.method === "get" && !!okTarget?.properties?.row_version;
  return { idempotent, ifMatch, paged, etag };
}

/** Example curl for an operation against the sandbox server. */
export function curlFor(doc: OpenApiDoc, op: FlatOperation): string {
  const json = doc as unknown as Json;
  const server = doc.servers?.find((s) => /sandbox/i.test(s.description ?? s.url))?.url ?? SANDBOX_FALLBACK;
  const params = (op.parameters ?? []).map((p) => resolve(json, p));
  const query = params.filter((p) => p.in === "query" && p.required).map((p) => `${p.name}=${encodeURIComponent(String(exampleOf(json, p.schema) ?? ""))}`);
  const path = op.path.replace(/\{([^}]+)\}/g, (_, n) => `\${${n.toUpperCase()}}`);
  const lines = [`curl -X ${op.method.toUpperCase()} "${server}${path}${query.length ? `?${query.join("&")}` : ""}"`,
    `  -H "Authorization: Bearer $TOKEN"`];
  const n = opNotes(json, op);
  if (n.idempotent) lines.push(`  -H "Idempotency-Key: $(uuidgen)"`);
  if (n.ifMatch) lines.push(`  -H "If-Match: $ROW_VERSION"`);
  for (const h of params.filter((p) => p.in === "header" && p.required && !["Idempotency-Key", "If-Match"].includes(p.name))) {
    lines.push(`  -H "${h.name}: <${h.name}>"`);
  }
  const body = op.requestBody?.content?.["application/json"]?.schema;
  if (body) {
    lines.push(`  -H "Content-Type: application/json"`);
    lines.push(`  -d '${JSON.stringify(exampleOf(json, body), null, 2).replace(/'/g, "'\\''")}'`);
  }
  return lines.join(" \\\n");
}

/** Link into the sandbox try-it console, prefilled with this operation. */
export function tryItHref(doc: OpenApiDoc, op: FlatOperation, domain: string): string {
  const body = op.requestBody?.content?.["application/json"]?.schema;
  const p = new URLSearchParams({ method: op.method.toUpperCase(), path: op.path, domain, op: op.operationId });
  if (body) p.set("body", JSON.stringify(exampleOf(doc as unknown as Json, body), null, 2));
  return `/console/try?${p.toString()}`;
}

export function OperationView({ doc, op, domain }: { doc: OpenApiDoc; op: FlatOperation; domain: string }) {
  const json = doc as unknown as Json;
  const n = opNotes(json, op);
  const [showErrors, setShowErrors] = useState(false);
  const body = op.requestBody?.content?.["application/json"]?.schema;
  const responses = Object.entries(op.responses ?? {}).map(([code, r]) => ({ code, r: resolve<Response>(json, r) }));
  const ok = responses.filter((x) => x.code.startsWith("2"));
  const errors = responses.filter((x) => !x.code.startsWith("2"));
  const params = op.parameters ?? [];

  return (
    <article id={op.operationId} className="scroll-mt-24 rounded-[var(--radius)] border border-border bg-surface">
      <header className="flex flex-wrap items-center gap-3 border-b border-border px-5 py-3">
        <MethodBadge method={op.method} />
        <code className="font-mono text-sm font-medium break-all">{op.path}</code>
        <span className="text-sm text-muted">{op.summary}</span>
        <a href={`#${op.operationId}`} className="ml-auto font-mono text-[11px] text-muted hover:text-accent">{op.operationId}</a>
      </header>
      <div className="grid gap-6 p-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <div className="min-w-0">
          {op.description && <p className="mb-3 text-sm">{op.description}</p>}
          <div className="mb-4 flex flex-wrap gap-2 text-xs">
            {op["x-required-scope"] && (
              <span className="rounded-full bg-accent-soft px-2 py-0.5 text-accent">scope <span className="font-mono">{op["x-required-scope"]}</span></span>
            )}
            {op["x-service"] && <span className="rounded-full bg-surface-2 px-2 py-0.5 text-muted">served by {op["x-service"]}</span>}
            {n.idempotent && <span className="rounded-full bg-warn-soft px-2 py-0.5 text-warn">Idempotency-Key required</span>}
            {n.ifMatch && <span className="rounded-full bg-warn-soft px-2 py-0.5 text-warn">If-Match required</span>}
            {n.paged && <span className="rounded-full bg-surface-2 px-2 py-0.5 text-muted">keyset paginated</span>}
            {op.deprecated && <span className="rounded-full bg-bad-soft px-2 py-0.5 text-bad">deprecated</span>}
          </div>
          {(n.idempotent || n.ifMatch || n.etag || n.paged) && (
            <ul className="mb-4 list-disc space-y-1 pl-5 text-xs text-muted">
              {n.idempotent && <li>Send a unique <code className="font-mono">Idempotency-Key</code> (≤ 128 chars). Retrying with the same key and body within 24 h returns the original response with <code className="font-mono">Idempotent-Replayed: true</code>; the same key with a different body fails with <code className="font-mono">422 idempotency_key_reused</code>. <Link className="text-accent underline" href="/docs/guides/idempotency">Idempotency guide</Link></li>}
              {n.ifMatch && <li>Send the resource&apos;s current <code className="font-mono">row_version</code> (also returned as <code className="font-mono">ETag</code>) in <code className="font-mono">If-Match</code>. A stale value fails with <code className="font-mono">409 version_conflict</code>; reload and retry.</li>}
              {n.etag && <li>The response carries <code className="font-mono">ETag</code> = <code className="font-mono">row_version</code>; use it as <code className="font-mono">If-Match</code> on updates.</li>}
              {n.paged && <li>Returns one page <code className="font-mono">{"{ data, has_more, next_cursor }"}</code>; pass <code className="font-mono">next_cursor</code> as <code className="font-mono">cursor</code> for the next page. <Link className="text-accent underline" href="/docs/guides/pagination">Pagination guide</Link></li>}
            </ul>
          )}

          {params.length > 0 && (
            <section className="mb-5">
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Parameters</h4>
              <ParamTable doc={json} params={params} />
            </section>
          )}
          {body && (
            <section className="mb-5">
              <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">
                Request body <span className="font-mono normal-case text-accent">{typeLabel(body)}</span>
                {op.requestBody?.required && <span className="ml-2 text-bad">required</span>}
              </h4>
              <SchemaTree doc={json} schema={body} />
            </section>
          )}
          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Responses</h4>
            {ok.map(({ code, r }) => {
              const s = r.content?.["application/json"]?.schema;
              return (
                <div key={code} className="mb-3">
                  <p className="text-sm"><span className="font-mono font-semibold text-good">{code}</span> {r.description}
                    {s && <span className="ml-2 font-mono text-[11.5px] text-accent">{typeLabel(s)}</span>}</p>
                  {s && <div className="mt-1 rounded border border-border px-3"><SchemaTree doc={json} schema={s} /></div>}
                </div>
              );
            })}
            {errors.length > 0 && (
              <div>
                <button type="button" className="text-xs text-muted hover:text-text" onClick={() => setShowErrors(!showErrors)}>
                  {showErrors ? "−" : "+"} {errors.length} error responses ({errors.map((e) => e.code).join(", ")}) — application/problem+json
                </button>
                {showErrors && (
                  <ul className="mt-1 space-y-0.5 pl-4 text-xs">
                    {errors.map(({ code, r }) => (
                      <li key={code}><span className="font-mono font-semibold text-bad">{code}</span> <span className="text-muted">{r.description}</span></li>
                    ))}
                    <li className="text-muted">Body: <Link href="/docs/guides/errors" className="text-accent underline">Problem (RFC 9457)</Link></li>
                  </ul>
                )}
              </div>
            )}
          </section>
        </div>
        <div className="min-w-0">
          <CodeBlock code={curlFor(doc, op)} title="Example request (sandbox)" className="mt-0" />
          {ok[0]?.r.content?.["application/json"]?.schema && (
            <CodeBlock title={`Example response ${ok[0].code}`}
              code={JSON.stringify(fullExample(json, ok[0].r.content["application/json"].schema), null, 2)} />
          )}
          <Link href={tryItHref(doc, op, domain)}
            className={cx("mt-2 inline-flex rounded-[var(--radius)] border border-border bg-surface px-3 py-1.5 text-xs font-medium hover:bg-surface-2")}>
            Try it in the sandbox console →
          </Link>
          <p className="mt-2 text-[11px] text-muted">
            Contract: <a className="underline" href={`/specs/openapi/${domain}.openapi.yaml`}>{domain}.openapi.yaml</a>
            {op["x-requirements"]?.length ? <> · requirements {op["x-requirements"].join(", ")}</> : null}
            {op.requestBody?.content?.["application/json"]?.schema?.$ref && <> · body {refName(op.requestBody.content["application/json"].schema.$ref)}</>}
          </p>
        </div>
      </div>
    </article>
  );
}
