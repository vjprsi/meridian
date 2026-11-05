"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, DateTime, ErrorBanner, Field, Input, Money, Select, cx } from "./ui";
import type { Page } from "@/lib/types";

/* ---------------------------------------------------------------------------------------------------------------
 * Shared operator building blocks: keyset paging, filter bars, confirmed actions, detail lists and timelines.
 * Every screen in the console composes these so lists, actions and errors behave the same everywhere.
 * ------------------------------------------------------------------------------------------------------------- */

export type Row = Record<string, unknown>;
type QV = string | number | boolean | null | undefined;

/** Builds a query string from the non-empty values ("?a=1&b=x", or "" when nothing is set). */
export function qs(params: Record<string, QV>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : "";
}

/** Normalises a list payload: a bare array, a Page ({data}) or an object holding an array under `key`. */
export function rows(v: unknown, key?: string): Row[] {
  if (Array.isArray(v)) return v as Row[];
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    if (key && Array.isArray(o[key])) return o[key] as Row[];
    if (Array.isArray(o.data)) return o.data as Row[];
  }
  return [];
}

export function str(v: unknown): string {
  return v === undefined || v === null || v === "" ? "—" : String(v);
}

/**
 * Keyset-paged list query. `key` is the React Query root key (invalidate it after a write); the filter object is part
 * of the key and resets paging to the first page when it changes. Pages are walked with the server's next_cursor and
 * a local stack of previous cursors (keyset paging has no random access).
 */
export function usePaged<T = Row>(key: string, path: string, filters: Record<string, QV> = {},
  opts: { limit?: number; enabled?: boolean } = {}) {
  const limit = opts.limit ?? 25;
  const fk = JSON.stringify(filters);
  const [state, setState] = useState<{ fk: string; cursors: (string | undefined)[] }>({ fk, cursors: [undefined] });
  const cursors = state.fk === fk ? state.cursors : [undefined];
  const cursor = cursors[cursors.length - 1];
  const q = useQuery({
    queryKey: [key, "list", path, filters, cursor],
    queryFn: () => call<Page<T>>("GET", `${path}${qs({ ...filters, limit, cursor })}`),
    placeholderData: keepPreviousData,
    enabled: opts.enabled ?? true,
  });
  const next = () => {
    const c = q.data?.next_cursor;
    if (c) setState({ fk, cursors: [...cursors, c] });
  };
  const prev = () => setState({ fk, cursors: cursors.length > 1 ? cursors.slice(0, -1) : [undefined] });
  return {
    ...q,
    rows: (q.data?.data ?? []) as unknown as Row[],
    page: cursors.length,
    hasNext: !!q.data?.has_more && !!q.data?.next_cursor,
    hasPrev: cursors.length > 1,
    next,
    prev,
  };
}

/** Previous / next controls for a usePaged list. */
export function Pager({ p }: { p: { page: number; hasNext: boolean; hasPrev: boolean; next: () => void; prev: () => void;
  isFetching?: boolean; rows: Row[] } }) {
  if (!p.hasPrev && !p.hasNext) return null;
  return (
    <div className="mt-4 flex items-center justify-between border-t border-border pt-3 text-sm">
      <span className="text-muted">Page {p.page}{p.isFetching ? " · loading…" : ""}</span>
      <div className="flex gap-2">
        <Button variant="secondary" disabled={!p.hasPrev || p.isFetching} onClick={p.prev}>Previous</Button>
        <Button variant="secondary" disabled={!p.hasNext || p.isFetching} onClick={p.next}>Next</Button>
      </div>
    </div>
  );
}

export interface FilterSpec {
  name: string;
  label: string;
  /** Enumerated values: rendered as a select with an "Any" option. */
  options?: readonly string[];
  type?: "text" | "date" | "number";
  placeholder?: string;
}

/** Filter bar: edits a draft and applies it on submit (or immediately for selects). */
export function Filters({ specs, value, onChange }:
  { specs: FilterSpec[]; value: Record<string, string>; onChange: (v: Record<string, string>) => void }) {
  const [draft, setDraft] = useState(value);
  const set = (name: string, v: string, apply: boolean) => {
    const d = { ...draft, [name]: v };
    setDraft(d);
    if (apply) onChange(d);
  };
  return (
    <form className="mb-4 flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); onChange(draft); }}>
      {specs.map((s) => (
        <Field key={s.name} label={s.label}>
          {s.options ? (
            <Select options={["", ...s.options]} value={draft[s.name] ?? ""}
              onChange={(e) => set(s.name, e.target.value, true)} className="min-w-36" />
          ) : (
            <Input type={s.type ?? "text"} placeholder={s.placeholder} value={draft[s.name] ?? ""}
              onChange={(e) => set(s.name, e.target.value, false)} className="min-w-40" />
          )}
        </Field>
      ))}
      <div className="flex gap-2">
        <Button type="submit" variant="secondary">Apply</Button>
        {Object.values(draft).some(Boolean) && (
          <Button type="button" variant="ghost" onClick={() => { setDraft({}); onChange({}); }}>Clear</Button>
        )}
      </div>
    </form>
  );
}

export interface ActionField {
  name: string;
  label: string;
  required?: boolean;
  type?: "text" | "textarea" | "number" | "date" | "datetime-local";
  options?: readonly string[];
  placeholder?: string;
  hint?: string;
  initial?: string;
}

/**
 * A state-changing operator action: button -> confirmation dialog (with optional inputs, e.g. a reason) -> request.
 * Problem+json errors stay inside the dialog so the operator can correct the input and retry; on success the listed
 * query keys are invalidated.
 */
export function Action({ label, title, description, method = "POST", path, fields = [], body, variant = "primary",
  confirmLabel, invalidate = [], onDone, disabled, size }: {
  label: string;
  title?: string;
  description?: ReactNode;
  method?: "POST" | "PUT" | "PATCH" | "DELETE";
  path: string;
  fields?: ActionField[];
  /** Static body fields merged with the dialog's inputs (or a function building the body from the inputs). */
  body?: Record<string, unknown> | ((v: Record<string, string>) => unknown);
  variant?: "primary" | "secondary" | "danger" | "ghost";
  confirmLabel?: string;
  invalidate?: QueryKey[];
  onDone?: (result: unknown) => void;
  disabled?: boolean;
  size?: "sm";
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant={variant} disabled={disabled} onClick={() => setOpen(true)}
        className={size === "sm" ? "px-2.5 py-1 text-xs" : undefined}>{label}</Button>
      {open && (
        <ActionDialog title={title ?? label} description={description} method={method} path={path} fields={fields}
          body={body} danger={variant === "danger"} confirmLabel={confirmLabel ?? label} invalidate={invalidate}
          onClose={() => setOpen(false)} onDone={(r) => { setOpen(false); onDone?.(r); }} />
      )}
    </>
  );
}

function ActionDialog({ title, description, method, path, fields, body, danger, confirmLabel, invalidate, onClose, onDone }: {
  title: string; description?: ReactNode; method: string; path: string; fields: ActionField[];
  body?: Record<string, unknown> | ((v: Record<string, string>) => unknown); danger: boolean; confirmLabel: string;
  invalidate: QueryKey[]; onClose: () => void; onDone: (r: unknown) => void;
}) {
  const qc = useQueryClient();
  const ref = useRef<HTMLDialogElement>(null);
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(fields.map((f) => [f.name, f.initial ?? (f.options && f.required ? f.options[0] : "")])));
  const m = useMutation({
    mutationFn: () => {
      const filled = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ""));
      const payload = typeof body === "function" ? body(values) : { ...(body ?? {}), ...filled };
      return call(method, path, method === "DELETE" && !fields.length && !body ? undefined : payload);
    },
    onSuccess: (r) => {
      invalidate.forEach((k) => qc.invalidateQueries({ queryKey: k }));
      onDone(r);
    },
  });
  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
  }, []);
  return (
    <dialog ref={ref} onClose={onClose} onCancel={onClose}
      className="m-auto w-full max-w-lg rounded-[var(--radius)] border border-border bg-surface p-0 text-text shadow-xl backdrop:bg-black/40">
      <form method="dialog" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <header className="border-b border-border px-5 py-3">
          <h2 className="text-sm font-semibold">{title}</h2>
        </header>
        <div className="space-y-4 px-5 py-4 text-sm">
          {description && <div className="text-muted">{description}</div>}
          <ErrorBanner error={m.error} />
          {fields.map((f) => (
            <Field key={f.name} label={f.label + (f.required ? "" : " (optional)")} hint={f.hint}>
              {f.options ? (
                <Select options={f.required ? [...f.options] : ["", ...f.options]} value={values[f.name]}
                  onChange={(e) => setValues({ ...values, [f.name]: e.target.value })} />
              ) : f.type === "textarea" ? (
                <textarea required={f.required} placeholder={f.placeholder} value={values[f.name]} rows={3}
                  onChange={(e) => setValues({ ...values, [f.name]: e.target.value })}
                  className="rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent" />
              ) : (
                <Input required={f.required} type={f.type ?? "text"} placeholder={f.placeholder} value={values[f.name]}
                  step={f.type === "number" ? "any" : undefined}
                  onChange={(e) => setValues({ ...values, [f.name]: e.target.value })} />
              )}
            </Field>
          ))}
        </div>
        <footer className="flex justify-end gap-2 border-t border-border px-5 py-3">
          <Button type="button" variant="secondary" onClick={() => ref.current?.close()}>Cancel</Button>
          <Button type="submit" variant={danger ? "danger" : "primary"} disabled={m.isPending}>
            {m.isPending ? "Working…" : confirmLabel}
          </Button>
        </footer>
      </form>
    </dialog>
  );
}

/** Key/value description list. Values: ReactNode as-is; ISO timestamps are formatted; null shows a dash. */
export function Details({ items, cols = 2 }: { items: [string, ReactNode | unknown][]; cols?: 1 | 2 }) {
  return (
    <dl className={cx("grid gap-x-4 gap-y-2 text-sm", cols === 2 ? "grid-cols-[max-content_1fr] md:grid-cols-[max-content_1fr_max-content_1fr]" : "grid-cols-[max-content_1fr]")}>
      {items.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted">{k}</dt>
          <dd className="min-w-0 break-words">{renderValue(v)}</dd>
        </div>
      ))}
    </dl>
  );
}

function renderValue(v: unknown): ReactNode {
  if (v === undefined || v === null || v === "") return <span className="text-muted">—</span>;
  if (typeof v === "boolean") return v ? "yes" : "no";
  if (typeof v === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(v)) return <DateTime value={v} />;
  if (typeof v === "string" || typeof v === "number") return String(v);
  if (Array.isArray(v)) {
    return v.every((x) => typeof x !== "object" || x === null) ? v.join(", ") || "—"
      : <span className="font-mono text-xs">{JSON.stringify(v)}</span>;
  }
  if (typeof v === "object" && !Array.isArray(v) && !isElement(v)) return <span className="font-mono text-xs">{JSON.stringify(v)}</span>;
  return v as ReactNode;
}

function isElement(v: object): boolean {
  return "$$typeof" in v;
}

/** Pretty JSON for free-form payloads (rule hits, evidence, raw rail messages). */
export function Json({ value }: { value: unknown }) {
  if (value === undefined || value === null) return <p className="text-sm text-muted">—</p>;
  return <pre className="max-h-80 overflow-auto rounded bg-surface-2 p-3 text-xs">{JSON.stringify(value, null, 2)}</pre>;
}

/** Vertical status timeline (status history, events, notes). */
export function Timeline({ items }: { items: { at?: unknown; title: ReactNode; detail?: ReactNode; status?: string }[] }) {
  if (items.length === 0) return <p className="py-4 text-center text-sm text-muted">No history yet.</p>;
  return (
    <ol className="relative ml-2 border-l border-border">
      {items.map((it, i) => (
        <li key={i} className="mb-4 ml-4 last:mb-0">
          <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-surface bg-accent" />
          <div className="flex flex-wrap items-center gap-2 text-sm">
            {it.status ? <Badge value={it.status} /> : null}
            <span className="font-medium">{it.title}</span>
            {typeof it.at === "string" && <span className="text-xs text-muted"><DateTime value={it.at} /></span>}
          </div>
          {it.detail && <div className="mt-1 text-xs text-muted">{it.detail}</div>}
        </li>
      ))}
    </ol>
  );
}

/** Small KPI tile. */
export function Stat({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: "good" | "warn" | "bad" }) {
  return (
    <div className="rounded-[var(--radius)] border border-border bg-surface p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
      <p className={cx("mt-1.5 font-display text-2xl font-semibold num", tone === "good" && "text-good", tone === "warn" && "text-warn",
        tone === "bad" && "text-bad")}>{value}</p>
      {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </div>
  );
}

/** Simple tab strip; content is rendered by the caller for the active tab. */
export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: readonly { id: T; label: string }[]; value: T; onChange: (t: T) => void }) {
  return (
    <div role="tablist" className="mb-4 flex flex-wrap gap-1 border-b border-border">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}
          className={cx("-mb-px border-b-2 px-3 py-2 text-sm", t.id === value
            ? "border-accent font-medium text-accent" : "border-transparent text-muted hover:text-text")}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

/** Money cell for a row: amount + currency keys (defaults "amount" / "currency"). */
export function amountCell(amountKey = "amount", currencyKey = "currency") {
  return function AmountCell(r: Row) {
    return <Money amount={r[amountKey] as string | null | undefined} currency={r[currencyKey] as string | null | undefined} />;
  };
}

export function dateCell(key: string) {
  return function DateCell(r: Row) {
    return <DateTime value={r[key] as string | null | undefined} />;
  };
}

export function statusCell(key = "status") {
  return function StatusCell(r: Row) {
    return <Badge value={r[key] as string | null | undefined} />;
  };
}
