"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { useRun } from "./step-up";
import { Button, Checkbox, Dialog, ErrorBanner, Field, Input, Select, Textarea, type Variant } from "./ui";

export interface FieldDef {
  name: string;
  label: string;
  type?: "text" | "textarea" | "number" | "date" | "datetime-local" | "email" | "url" | "select" | "checkbox";
  required?: boolean;
  options?: { value: string; label: string }[];
  defaultValue?: string;
  hint?: string;
  placeholder?: string;
}

export type Values = Record<string, string>;

/** Empty strings become undefined so optional fields are left out of request bodies. */
export function opt(v: string | undefined): string | undefined {
  return v === undefined || v.trim() === "" ? undefined : v.trim();
}

/** datetime-local value to an ISO instant (or undefined). */
export function isoInstant(v: string | undefined): string | undefined {
  const s = opt(v);
  return s ? new Date(s).toISOString() : undefined;
}

export function FormFields({ fields, values, set }:
  { fields: FieldDef[]; values: Values; set: (name: string, v: string) => void }) {
  return (
    <div className="flex flex-col gap-3">
      {fields.map((f) => {
        const common = { name: f.name, required: f.required, placeholder: f.placeholder, value: values[f.name] ?? "",
          onChange: (e: { target: { value: string } }) => set(f.name, e.target.value) };
        if (f.type === "checkbox") {
          return <Checkbox key={f.name} label={f.label} checked={values[f.name] === "true"}
            onChange={(e) => set(f.name, String(e.target.checked))} />;
        }
        const control = f.type === "textarea" ? <Textarea {...common} />
          : f.type === "select" ? <Select {...common} options={f.options ?? []} placeholder={f.required ? undefined : "—"} />
          : <Input {...common} type={f.type ?? "text"} step={f.type === "number" ? "any" : undefined} />;
        return <Field key={f.name} label={f.label} hint={f.hint} optional={!f.required}>{control}</Field>;
      })}
    </div>
  );
}

function initial(fields: FieldDef[]): Values {
  return Object.fromEntries(fields.map((f) => [f.name, f.defaultValue ?? (f.type === "select" && f.required ? f.options?.[0]?.value ?? "" : "")]));
}

/**
 * A button that opens a confirmation dialog (optionally with a small form) and performs one write. Each opening is a
 * new confirmation with its own Idempotency-Key; a step-up (401 step_up_required) retries with the same key.
 */
export function ActionButton<T = unknown>({ label, title, variant = "secondary", size = "sm", description, fields = [],
  confirmLabel, confirmVariant, run, onDone, disabled, wide }:
  { label: ReactNode; title?: ReactNode; variant?: Variant; size?: "sm" | "md"; description?: ReactNode; fields?: FieldDef[];
    confirmLabel?: string; confirmVariant?: Variant; run: (values: Values, key: string) => Promise<T>;
    onDone?: (result: T) => void; disabled?: boolean; wide?: boolean }) {
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Values>({});
  const r = useRun();

  function start() {
    setValues(initial(fields));
    r.newKey();
    setOpen(true);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const res = await r.run((key) => run(values, key));
    if (res.ok) {
      setOpen(false);
      onDone?.(res.data);
    }
  }

  return (
    <>
      <Button variant={variant} size={size} onClick={start} disabled={disabled}>{label}</Button>
      <Dialog open={open} onClose={() => setOpen(false)} title={title ?? label} wide={wide}>
        <form onSubmit={submit} className="flex flex-col gap-3">
          {description && <div className="text-muted">{description}</div>}
          {fields.length > 0 && <FormFields fields={fields} values={values} set={(n, v) => setValues((s) => ({ ...s, [n]: v }))} />}
          <ErrorBanner error={r.error} />
          <div className="mt-2 flex flex-wrap justify-end gap-2">
            <Button variant="secondary" onClick={() => setOpen(false)} disabled={r.pending}>Close</Button>
            <Button type="submit" variant={confirmVariant ?? (variant === "danger" ? "danger" : "primary")} disabled={r.pending}>
              {r.pending ? "Working…" : confirmLabel ?? "Confirm"}
            </Button>
          </div>
        </form>
      </Dialog>
    </>
  );
}

/** Shows a one-time secret (API client secret, API key, signing secret) with a copy button. */
export function SecretOnce({ label, value, onClose }: { label: string; value?: string | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <Dialog open={!!value} onClose={onClose} title={label}
      footer={<Button onClick={onClose}>I have stored it</Button>}>
      <p className="text-muted">This value is shown only once. Store it securely now.</p>
      <pre className="mt-3 overflow-x-auto rounded-[var(--radius)] bg-surface-2 p-3 font-mono text-xs" data-testid="secret">{value}</pre>
      <Button className="mt-2" size="sm" variant="secondary" onClick={async () => {
        try { await navigator.clipboard.writeText(value ?? ""); setCopied(true); } catch { /* clipboard blocked */ }
      }}>{copied ? "Copied" : "Copy"}</Button>
    </Dialog>
  );
}
