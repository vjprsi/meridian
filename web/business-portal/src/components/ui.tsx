"use client";

import Link from "next/link";
import {
  cloneElement, isValidElement, useEffect, useId, useRef,
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactElement, type ReactNode, type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { ApiError } from "@/api/client";
import { GENERIC_ERROR, PROBLEM_HINTS, PROBLEM_TITLES, SERVER_ERROR, STATUS_HINTS } from "@/messages/en";

const INTL = "en-GB";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

export const focusRing = "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-focus";

const buttonStyles = {
  primary: "bg-accent text-accent-fg hover:bg-accent-strong",
  secondary: "bg-surface border border-border-strong text-text hover:bg-surface-2",
  danger: "bg-bad text-bad-fg hover:opacity-90",
  ghost: "text-text hover:bg-surface-2",
} as const;

export type Variant = keyof typeof buttonStyles;

export function Button({ variant = "primary", className, type = "button", size = "md", ...p }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md" }) {
  return (
    <button
      type={type}
      {...p}
      className={cx("inline-flex items-center justify-center gap-2 rounded-[var(--radius)] font-semibold transition",
        size === "sm" ? "min-h-8 px-3 py-1 text-xs" : "min-h-10 px-4 py-2 text-sm",
        "disabled:cursor-not-allowed disabled:opacity-60", focusRing, buttonStyles[variant], className)}
    />
  );
}

export function LinkButton({ href, variant = "primary", className, children, size = "md" }:
  { href: string; variant?: Variant; className?: string; children: ReactNode; size?: "sm" | "md" }) {
  return (
    <Link href={href}
      className={cx("inline-flex items-center justify-center gap-2 rounded-[var(--radius)] font-semibold",
        size === "sm" ? "min-h-8 px-3 py-1 text-xs" : "min-h-10 px-4 py-2 text-sm",
        focusRing, buttonStyles[variant], className)}>
      {children}
    </Link>
  );
}

export function Card({ title, actions, children, className, flush }:
  { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; flush?: boolean }) {
  const id = useId();
  return (
    <section aria-labelledby={title ? id : undefined}
      className={cx("rounded-[var(--radius)] border border-border bg-surface", className)}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
          {title && <h2 id={id} className="text-base font-semibold">{title}</h2>}
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
        </header>
      )}
      <div className={flush ? "" : "p-4"}>{children}</div>
    </section>
  );
}

export function PageHeader({ title, subtitle, actions, back }:
  { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; back?: { href: string; label: string } }) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        {back && (
          <Link href={back.href} className={cx("mb-1 inline-block text-sm text-accent underline-offset-4 hover:underline", focusRing)}>
            <span aria-hidden="true">← </span>{back.label}
          </Link>
        )}
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

const tones = {
  good: "bg-good-soft text-good",
  warn: "bg-warn-soft text-warn",
  bad: "bg-bad-soft text-bad",
  info: "bg-accent-soft text-accent",
  muted: "bg-surface-2 text-muted",
} as const;

export function toneOf(value: string): keyof typeof tones {
  const v = value.toUpperCase();
  return /ACTIVE|APPROVED|POSTED|SETTLED|COMPLETED|PAID|VERIFIED|ACCEPTED|READY|DELIVERED|RELEASED|SUCCEEDED|COLLECTED|BOOKED|CONFIRMED|MATCH$/.test(v) ? "good"
    : /PENDING|SUBMITTED|REVIEW|PARTIAL|QUEUED|PROCESSING|INITIATED|SCREENING|AWAITING|IN_PROGRESS|DRAFT|DUE|PAUSED|SCHEDULED|RETRYING|GENERATING|STEP_UP/.test(v) ? "warn"
    : /FROZEN|BLOCKED|REJECTED|FAILED|CLOSED|SUSPENDED|LOCKED|EXPIRED|CANCELLED|LOST|STOLEN|OVERDUE|REVOKED|RETURNED|DISABLED|DENIED|REMOVED|NO_MATCH|ABANDONED|DECLINED/.test(v) ? "bad"
    : "muted";
}

/** Status chip: always text (colour is never the only cue). */
export function Badge({ value, tone }: { value?: string | null; tone?: keyof typeof tones }) {
  const v = value ?? "—";
  return (
    <span className={cx("inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-semibold", tones[tone ?? toneOf(v)])}>
      {v.replaceAll("_", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}
    </span>
  );
}

/** Labelled form control: the label, hint and error are programmatically tied to the single child control. */
export function Field({ label, hint, error, children, optional }:
  { label: ReactNode; hint?: ReactNode; error?: ReactNode; optional?: boolean; children: ReactElement }) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const control = isValidElement(children)
    ? cloneElement(children as ReactElement<Record<string, unknown>>, {
        id, "aria-describedby": [hintId, errId].filter(Boolean).join(" ") || undefined,
        "aria-invalid": error ? true : undefined,
      })
    : children;
  return (
    <div className="flex flex-col gap-1.5 text-sm">
      <label htmlFor={id} className="font-medium">
        {label}{optional && <span className="font-normal text-muted"> (optional)</span>}
      </label>
      {control}
      {hint && <p id={hintId} className="text-xs text-muted">{hint}</p>}
      {error && <p id={errId} className="text-xs font-medium text-bad">{error}</p>}
    </div>
  );
}

const inputCls = cx("min-h-10 w-full rounded-[var(--radius)] border border-border-strong bg-surface px-3 py-2 text-sm text-text",
  "placeholder:text-muted aria-invalid:border-bad", focusRing);

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(inputCls, p.className)} />;
}

export function Textarea(p: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx(inputCls, "min-h-24", p.className)} />;
}

export function Select({ options, placeholder, ...p }:
  SelectHTMLAttributes<HTMLSelectElement> & { options: { value: string; label: string }[]; placeholder?: string }) {
  return (
    <select {...p} className={cx(inputCls, p.className)}>
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

export function Checkbox({ label, ...p }: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode }) {
  return (
    <label className="inline-flex items-center gap-2 text-sm">
      <input type="checkbox" {...p} className={cx("h-4 w-4 accent-[var(--accent)]", focusRing)} />
      {label}
    </label>
  );
}

/** Formats a decimal-string amount with its currency (display only; never used for arithmetic). */
export function Money({ amount, currency, className }:
  { amount?: string | number | null; currency?: string | null; className?: string }) {
  if (amount === undefined || amount === null || amount === "") return <span className="text-muted">—</span>;
  const n = Number(amount);
  let text: string;
  try {
    text = currency
      ? new Intl.NumberFormat(INTL, { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n)
      : n.toLocaleString(INTL);
  } catch {
    text = `${currency ?? ""} ${amount}`;
  }
  return <span className={cx("num whitespace-nowrap", className)}>{text}</span>;
}

export function DateTime({ value, dateOnly }: { value?: string | null; dateOnly?: boolean }) {
  if (!value) return <span className="text-muted">—</span>;
  const d = new Date(dateOnly && value.length === 10 ? `${value}T00:00:00` : value);
  if (Number.isNaN(d.getTime())) return <span>{value}</span>;
  const text = dateOnly ? d.toLocaleDateString(INTL, { dateStyle: "medium" })
    : d.toLocaleString(INTL, { dateStyle: "medium", timeStyle: "short" });
  return <time dateTime={value} className="num whitespace-nowrap">{text}</time>;
}

/** Masks an account number / IBAN to its last four characters. */
export function masked(value?: string | null) {
  if (!value) return "—";
  return `•••• ${value.slice(-4)}`;
}

export function shortId(id?: string | null) {
  return id ? id.slice(-8) : "—";
}

/** Headline and hint for a problem: special business-channel codes first, then the HTTP status. */
export function describeProblem(error: unknown): { title: string; hint?: string; detail?: string; code?: string; requestId?: string;
  errors?: { field?: string; message?: string }[] } {
  const p = error instanceof ApiError ? error.problem
    : { status: 0, detail: error instanceof Error ? error.message : String(error) } as ApiError["problem"];
  const status = p.status ?? 0;
  const code = p.code;
  const title = (code && PROBLEM_TITLES[code]) ?? (p.title && p.detail ? p.title : undefined)
    ?? STATUS_HINTS[status] ?? (status >= 500 ? SERVER_ERROR : GENERIC_ERROR);
  return { title, hint: code ? PROBLEM_HINTS[code] : undefined, detail: p.detail ?? p.title, code,
    requestId: p.request_id, errors: p.errors };
}

/**
 * Renders a Meridian problem+json error: a clear headline for the business codes (not_entitled, role_not_allowed,
 * self_approval_forbidden, ...), the service's detail, field errors, and the code and request id for support.
 */
export function ErrorBanner({ error, className }: { error: unknown; className?: string }) {
  if (!error) return null;
  const d = describeProblem(error);
  return (
    <div role="alert" data-code={d.code} className={cx("rounded-[var(--radius)] border border-bad/40 bg-bad-soft px-4 py-3 text-sm text-bad", className)}>
      <p className="font-semibold">{d.title}</p>
      {d.detail && d.detail !== d.title && <p className="mt-1">{d.detail}</p>}
      {d.hint && <p className="mt-1 text-xs">{d.hint}</p>}
      {d.errors && d.errors.length > 0 && (
        <ul className="mt-2 list-disc pl-5 text-xs">
          {d.errors.map((e, i) => <li key={`${e.field}-${i}`}>{e.field ? `${e.field}: ` : ""}{e.message}</li>)}
        </ul>
      )}
      {(d.code || d.requestId) && (
        <p className="mt-2 text-xs opacity-90">
          {d.code && <>Code <span className="font-mono">{d.code}</span></>}
          {d.requestId && <> · Reference <span className="font-mono">{d.requestId}</span></>}
        </p>
      )}
    </div>
  );
}

export function Loading({ label, rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div role="status" aria-live="polite" className="space-y-2 py-2">
      <span className="sr-only">{label ?? "Loading…"}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} aria-hidden="true" className="h-9 animate-pulse rounded-[var(--radius)] bg-surface-2 motion-reduce:animate-none" />
      ))}
    </div>
  );
}

export function Empty({ title, children, action }: { title: ReactNode; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="rounded-[var(--radius)] border border-dashed border-border-strong px-4 py-8 text-center">
      <p className="font-medium">{title}</p>
      {children && <p className="mt-1 text-sm text-muted">{children}</p>}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  );
}

/** Loading / error / empty handling for one query in one place. */
export function Async<T>({ q, empty, isEmpty, children, rows }:
  { q: { data?: T; error: unknown; isPending: boolean }; empty?: ReactNode; isEmpty?: (d: T) => boolean;
    children: (d: T) => ReactNode; rows?: number }) {
  if (q.isPending && q.data === undefined && !q.error) return <Loading rows={rows} />;
  if (q.error) return <ErrorBanner error={q.error} />;
  if (q.data === undefined) return null;
  const blank = isEmpty ? isEmpty(q.data) : Array.isArray(q.data) && q.data.length === 0;
  if (blank && empty) return <>{empty}</>;
  return <>{children(q.data)}</>;
}

/** Accessible modal built on the native dialog element (focus is trapped and Escape closes it). */
export function Dialog({ open, onClose, title, children, footer, wide }:
  { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} aria-labelledby={id} onClose={onClose} onCancel={onClose}
      className={cx("m-auto rounded-[var(--radius)] border border-border bg-surface p-0 text-text shadow-xl backdrop:bg-black/50",
        wide ? "w-[min(48rem,calc(100vw-2rem))]" : "w-[min(32rem,calc(100vw-2rem))]")}>
      {open && (
        <div className="p-5">
          <h2 id={id} className="text-lg font-semibold">{title}</h2>
          <div className="mt-3 text-sm">{children}</div>
          {footer && <div className="mt-5 flex flex-wrap justify-end gap-2">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}

/** Definition list for "label: value" summaries (confirmation screens, detail panels). */
export function Summary({ items, cols = 2 }: { items: { label: ReactNode; value: ReactNode }[]; cols?: 2 | 3 | 4 }) {
  return (
    <dl className={cx("grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2",
      cols === 3 && "lg:grid-cols-3", cols === 4 && "lg:grid-cols-4")}>
      {items.map((it, i) => (
        <div key={i} className="min-w-0">
          <dt className="text-muted">{it.label}</dt>
          <dd className="mt-0.5 break-words font-medium">{it.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Notice({ tone = "info", children }: { tone?: "info" | "warn" | "good"; children: ReactNode }) {
  return (
    <div role="status" className={cx("rounded-[var(--radius)] px-4 py-3 text-sm", tones[tone])}>{children}</div>
  );
}

export interface Column<T> {
  header: ReactNode;
  cell: (row: T) => ReactNode;
  className?: string;
  align?: "right";
}

/** Plain data table; rows scroll horizontally on narrow screens. */
export function Table<T>({ rows, columns, rowKey, caption, empty }:
  { rows: T[]; columns: Column<T>[]; rowKey: (row: T) => string; caption?: string; empty?: ReactNode }) {
  if (rows.length === 0 && empty) return <>{empty}</>;
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
            {columns.map((c, i) => (
              <th key={i} scope="col" className={cx("px-3 py-2 font-semibold", c.align === "right" && "text-right", c.className)}>{c.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)} className="border-b border-border last:border-0 hover:bg-surface-2/60">
              {columns.map((c, i) => (
                <td key={i} className={cx("px-3 py-2 align-top", c.align === "right" && "text-right", c.className)}>{c.cell(r)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs<K extends string>({ value, onChange, tabs }:
  { value: K; onChange: (k: K) => void; tabs: { key: K; label: ReactNode }[] }) {
  return (
    <div role="tablist" className="mb-4 flex flex-wrap gap-1 border-b border-border">
      {tabs.map((t) => (
        <button key={t.key} type="button" role="tab" aria-selected={value === t.key} onClick={() => onChange(t.key)}
          className={cx("-mb-px border-b-2 px-3 py-2 text-sm", focusRing,
            value === t.key ? "border-accent font-semibold text-accent" : "border-transparent text-muted hover:text-text")}>
          {t.label}
        </button>
      ))}
    </div>
  );
}
