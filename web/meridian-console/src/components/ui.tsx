"use client";

import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import { ApiError } from "@/api/client";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

export function Button({ variant = "primary", className, ...p }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" | "ghost" }) {
  const styles = {
    primary: "bg-accent text-accent-fg hover:bg-accent-hover shadow-sm",
    secondary: "bg-surface border border-border text-text hover:bg-surface-2",
    danger: "bg-bad text-white hover:opacity-90 shadow-sm",
    ghost: "text-muted hover:text-text hover:bg-surface-2",
  }[variant];
  return (
    <button
      {...p}
      className={cx("inline-flex items-center gap-2 rounded-[var(--radius)] px-3.5 py-2 text-sm font-medium transition",
        "disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-accent", styles, className)}
    />
  );
}

export function Card({ title, actions, children, className }:
  { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx("rounded-[var(--radius)] border border-border bg-surface", className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-4 border-b border-border px-5 py-3">
          <h2 className="text-sm font-semibold">{title}</h2>
          <div className="flex gap-2">{actions}</div>
        </header>
      )}
      <div className="p-5">{children}</div>
    </section>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="font-display text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1.5 text-sm text-muted">{subtitle}</p>}
      </div>
      <div className="flex gap-2">{actions}</div>
    </div>
  );
}

const tones: Record<string, string> = {
  good: "bg-good-soft text-good",
  warn: "bg-warn-soft text-warn",
  bad: "bg-bad-soft text-bad",
  info: "bg-accent-soft text-accent",
  muted: "bg-surface-2 text-muted",
};

/** Status chip; tone inferred from common Meridian status values unless given. */
export function Badge({ value, tone }: { value?: string | null; tone?: keyof typeof tones }) {
  const v = (value ?? "—").toUpperCase();
  const t = tone ?? (/ACTIVE|APPROVED|POSTED|SETTLED|SUCCEEDED|PUBLISHED|DONE|PASSED|CAPTURED/.test(v) ? "good"
    : /PENDING|DRAFT|SUBMITTED|REVIEW|PARTIAL|QUEUED|RUNNING|INVITED/.test(v) ? "warn"
    : /FROZEN|BLOCKED|REJECTED|FAILED|CLOSED|SUSPENDED|LOCKED|RELEASED|EXPIRED|CANCELLED/.test(v) ? "bad" : "muted");
  return <span className={cx("inline-flex rounded-full px-2 py-0.5 text-xs font-medium", tones[t])}>{v.replaceAll("_", " ")}</span>;
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5 text-sm">
      <span className="font-medium">{label}</span>
      {children}
      {hint && <span className="text-xs text-muted">{hint}</span>}
    </label>
  );
}

const inputCls = "rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent";

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(inputCls, p.className)} />;
}

export function Select({ options, ...p }: SelectHTMLAttributes<HTMLSelectElement> & { options: string[] }) {
  return (
    <select {...p} className={cx(inputCls, p.className)}>
      {options.map((o) => <option key={o} value={o}>{o.replaceAll("_", " ")}</option>)}
    </select>
  );
}

export interface Column<T> {
  key: string;
  header: string;
  render?: (row: T) => ReactNode;
  align?: "left" | "right";
}

export function Table<T extends Record<string, unknown>>({ rows, columns, onRow, empty }:
  { rows: T[]; columns: Column<T>[]; onRow?: (r: T) => void; empty?: string }) {
  if (rows.length === 0) {
    return <p className="py-8 text-center text-sm text-muted">{empty ?? "Nothing here yet."}</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs uppercase tracking-wide text-muted">
            {columns.map((c) => (
              <th key={c.key} className={cx("px-3 py-2 font-medium", c.align === "right" && "text-right")}>{c.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={String(r.id ?? i)} onClick={onRow ? () => onRow(r) : undefined}
              className={cx("border-b border-border last:border-0", onRow && "cursor-pointer hover:bg-surface-2")}>
              {columns.map((c) => (
                <td key={c.key} className={cx("px-3 py-2.5", c.align === "right" && "text-right num")}>
                  {c.render ? c.render(r) : String(r[c.key] ?? "—")}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Formats a decimal-string amount with its currency (never parses money to float for arithmetic). */
export function Money({ amount, currency }: { amount?: string | number | null; currency?: string | null }) {
  if (amount === undefined || amount === null) return <span className="text-muted">—</span>;
  const n = Number(amount);
  const text = currency
    ? new Intl.NumberFormat(undefined, { style: "currency", currency, maximumFractionDigits: 8,
        minimumFractionDigits: 2 }).format(n)
    : n.toLocaleString();
  return <span className={cx("num", n < 0 && "text-bad")}>{text}</span>;
}

export function DateTime({ value }: { value?: string | null }) {
  if (!value) return <span className="text-muted">—</span>;
  return <time dateTime={value} className="num">{new Date(value).toLocaleString()}</time>;
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className="font-mono text-xs text-muted">{children}</span>;
}

/** Renders a Meridian problem+json error: message, stable code and request id for support. */
export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) return null;
  const p = error instanceof ApiError ? error.problem : { detail: String(error) };
  return (
    <div role="alert" className="mb-4 rounded-[var(--radius)] border border-bad/30 bg-bad-soft px-4 py-3 text-sm text-bad">
      <p className="font-medium">{p.detail ?? p.title ?? "Something went wrong"}</p>
      <p className="mt-1 text-xs opacity-80">
        {p.code && <>code <span className="font-mono">{p.code}</span></>}
        {p.request_id && <> · request <span className="font-mono">{p.request_id}</span></>}
      </p>
      {p.errors && p.errors.length > 0 && (
        <ul className="mt-2 list-disc pl-5 text-xs">
          {p.errors.map((e) => <li key={e.field}><span className="font-mono">{e.field}</span>: {e.message}</li>)}
        </ul>
      )}
    </div>
  );
}

export function Loading() {
  return <p className="py-8 text-center text-sm text-muted">Loading…</p>;
}
