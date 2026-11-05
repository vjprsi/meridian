"use client";

import { useState } from "react";
import { Button, Card, ErrorBanner, Field, Input, Select } from "@/components/ui";

/**
 * Declarative, grouped entity form used across the console so every create/edit surface
 * exposes the full enterprise field set (not a token subset). Drive it from a per-entity
 * list of {@link FormSection}s; the engine renders the inputs, tracks state, and builds a
 * cleaned request body (empty strings dropped, booleans/numbers coerced, upper-cased codes).
 */
export type FieldType =
  | "text" | "textarea" | "number" | "date" | "datetime" | "bool"
  | "select" | "country" | "currency" | "amount" | "email" | "phone"
  | "json" | "list";  // json → parsed object; list → comma-separated → string[]

export interface FieldSpec {
  key: string;
  label: string;
  type?: FieldType;              // default "text"
  required?: boolean;
  options?: string[];            // for "select"
  hint?: string;
  placeholder?: string;
  /** Conditional visibility, e.g. show only for business customer types. */
  when?: (v: Record<string, string>) => boolean;
  /** Grid columns the field spans (1–3). Textareas default to 3. */
  span?: 1 | 2 | 3;
}

export interface FormSection {
  title: string;
  description?: string;
  fields: FieldSpec[];
}

export type FormValues = Record<string, string>;

function Control({ f, value, onChange }: { f: FieldSpec; value: string; onChange: (v: string) => void }) {
  const t = f.type ?? "text";
  if (t === "bool") {
    return (
      <label className="flex items-center gap-2 py-2 text-sm">
        <input type="checkbox" checked={value === "true"} onChange={(e) => onChange(e.target.checked ? "true" : "false")} />
        <span className="text-muted">{f.hint ?? "Yes"}</span>
      </label>
    );
  }
  if (t === "select") {
    const opts = f.required ? (f.options ?? []) : ["", ...(f.options ?? [])];
    return <Select options={opts} value={value} required={f.required} onChange={(e) => onChange(e.target.value)} />;
  }
  if (t === "textarea" || t === "json") {
    return (
      <textarea
        className="min-h-20 rounded-[var(--radius)] border border-border bg-surface px-3 py-2 font-mono text-xs outline-none focus:border-accent"
        value={value} placeholder={f.placeholder ?? (t === "json" ? '{ }' : undefined)} required={f.required}
        onChange={(e) => onChange(e.target.value)} />
    );
  }
  if (t === "country" || t === "currency") {
    const len = t === "country" ? 2 : 3;
    return (
      <Input value={value} required={f.required} maxLength={len}
        placeholder={f.placeholder ?? (t === "country" ? "ISO 3166 (e.g. KE)" : "ISO 4217 (e.g. USD)")}
        onChange={(e) => onChange(e.target.value.toUpperCase())} />
    );
  }
  const htmlType = t === "amount" || t === "number" ? "number"
    : t === "date" ? "date" : t === "datetime" ? "datetime-local"
    : t === "email" ? "email" : t === "phone" ? "tel" : "text";
  return (
    <Input type={htmlType} value={value} required={f.required} placeholder={f.placeholder}
      step={t === "amount" ? "any" : undefined}
      onChange={(e) => onChange(e.target.value)} />
  );
}

/** Builds the cleaned request body from the raw string state. */
export function cleanBody(sections: FormSection[], v: FormValues): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const s of sections) {
    for (const f of s.fields) {
      if (f.when && !f.when(v)) continue;
      const raw = v[f.key];
      if (raw === undefined || raw === "") continue;
      const t = f.type ?? "text";
      if (t === "bool") { if (raw === "true") body[f.key] = true; else if (raw === "false") body[f.key] = false; }
      else if (t === "number") body[f.key] = Number(raw);
      else if (t === "amount") body[f.key] = raw;          // money stays a decimal string
      else if (t === "list") { const arr = raw.split(",").map((s) => s.trim()).filter(Boolean); if (arr.length) body[f.key] = arr; }
      else if (t === "json") { try { body[f.key] = JSON.parse(raw); } catch { body[f.key] = raw; } }  // invalid JSON → let the server reject
      else body[f.key] = raw;
    }
  }
  return body;
}

export function EntityForm({
  sections, initial = {}, submitLabel = "Save", pending, error, onSubmit, footer,
}: {
  sections: FormSection[];
  initial?: FormValues;
  submitLabel?: string;
  pending?: boolean;
  error?: unknown;
  onSubmit: (body: Record<string, unknown>, raw: FormValues) => void;
  footer?: React.ReactNode;
}) {
  const [v, setV] = useState<FormValues>(initial);
  const set = (k: string, val: string) => setV((p) => ({ ...p, [k]: val }));

  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(cleanBody(sections, v), v); }} className="space-y-6">
      <ErrorBanner error={error} />
      {sections.map((s) => {
        const visible = s.fields.filter((f) => !f.when || f.when(v));
        if (visible.length === 0) return null;
        return (
          <Card key={s.title} title={s.title}>
            {s.description && <p className="mb-4 -mt-1 text-xs text-muted">{s.description}</p>}
            <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
              {visible.map((f) => (
                <div key={f.key} className={f.type === "textarea" || f.type === "json" ? "md:col-span-3" : f.span === 2 ? "md:col-span-2" : f.span === 3 ? "md:col-span-3" : ""}>
                  <Field label={f.required ? `${f.label} *` : f.label} hint={f.type === "bool" ? undefined : f.hint}>
                    <Control f={f} value={v[f.key] ?? ""} onChange={(val) => set(f.key, val)} />
                  </Field>
                </div>
              ))}
            </div>
          </Card>
        );
      })}
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={pending}>{pending ? "Saving…" : submitLabel}</Button>
        {footer}
      </div>
    </form>
  );
}
