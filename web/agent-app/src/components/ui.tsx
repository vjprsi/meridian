"use client";

import Link from "next/link";
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import type { Problem } from "@/lib/outbox/types";
import { formatMoney } from "@/lib/money";
import { localeOf, useLang } from "@/lib/i18n";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

const btn = {
  primary: "bg-accent text-accent-fg hover:opacity-90",
  secondary: "bg-surface border-2 border-border text-text hover:bg-surface-2",
  danger: "bg-bad text-white hover:opacity-90",
  ghost: "text-accent underline-offset-4 hover:underline",
};

/** Buttons are at least 48 px tall (touch target guidance) with a visible focus ring. */
export function Button({ variant = "primary", className, block, ...p }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: keyof typeof btn; block?: boolean }) {
  return (
    <button
      {...p}
      className={cx("inline-flex min-h-12 items-center justify-center gap-2 rounded-[var(--radius)] px-5 text-base font-semibold transition",
        "disabled:cursor-not-allowed disabled:opacity-50", block && "w-full", btn[variant], className)}
    />
  );
}

export function LinkButton({ href, variant = "primary", className, children }:
  { href: string; variant?: keyof typeof btn; className?: string; children: ReactNode }) {
  return (
    <Link href={href} className={cx("inline-flex min-h-12 items-center justify-center gap-2 rounded-[var(--radius)] px-5 text-base font-semibold",
      btn[variant], className)}>{children}</Link>
  );
}

export function Card({ title, actions, children, className }:
  { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx("rounded-[var(--radius)] border border-border bg-surface", className)}>
      {(title || actions) && (
        <header className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
          <h2 className="text-base font-semibold">{title}</h2>
          <div className="flex gap-2">{actions}</div>
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function PageTitle({ children, sub }: { children: ReactNode; sub?: ReactNode }) {
  return (
    <div className="mb-4">
      <h1 className="text-2xl font-bold tracking-tight">{children}</h1>
      {sub && <p className="mt-1 text-sm text-muted">{sub}</p>}
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

export function Badge({ value, tone }: { value?: string | null; tone?: keyof typeof tones }) {
  const v = (value ?? "—").toUpperCase();
  const t = tone ?? (/ACTIVE|APPROVED|COMPLETED|SYNCED|APPLIED|OPEN|BALANCED|PAID|AVAILABLE/.test(v) ? "good"
    : /PENDING|REQUESTED|PROCESSING|INITIATED|SUBMITTED|REVIEW|STARTED|PROGRESS|VALIDATED/.test(v) ? "warn"
    : /REJECTED|FAILED|CANCELLED|EXPIRED|REVERSED|LOST|BLOCKED|SHORT|OVER|SUSPENDED|CLOSED|CONFLICT/.test(v) ? "bad" : "muted");
  return <span className={cx("inline-flex rounded-full px-2.5 py-0.5 text-sm font-semibold", tones[t])}>{v.replaceAll("_", " ")}</span>;
}

export function Field({ label, hint, error, children }: { label: string; hint?: string; error?: string | null; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-sm font-semibold">{label}</span>
      {children}
      {hint && <span className="text-sm text-muted">{hint}</span>}
      {error && <span role="alert" className="text-sm font-semibold text-bad">{error}</span>}
    </label>
  );
}

const inputCls = "min-h-12 w-full rounded-[var(--radius)] border-2 border-border bg-surface px-3 text-lg outline-none focus:border-accent";

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(inputCls, p.className)} />;
}

/** Numeric secret entry (PIN / OTP): masked, no autocomplete, never echoed back. */
export function PinInput(p: InputHTMLAttributes<HTMLInputElement>) {
  return <Input type="password" inputMode="numeric" autoComplete="off" pattern="\d*" maxLength={8} {...p}
    className={cx("tracking-[0.5em] text-center text-2xl", p.className)} />;
}

export function Select({ options, ...p }: SelectHTMLAttributes<HTMLSelectElement> & { options: (string | { value: string; label: string })[] }) {
  return (
    <select {...p} className={cx(inputCls, p.className)}>
      {options.map((o) => typeof o === "string"
        ? <option key={o} value={o}>{o.replaceAll("_", " ")}</option>
        : <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

export function Money({ amount, currency, className }: { amount?: string | number | null; currency?: string | null; className?: string }) {
  const lang = useLang();
  const neg = Number(amount) < 0;
  return <span className={cx("num", neg && "text-bad", className)}>{formatMoney(amount, currency, localeOf(lang))}</span>;
}

export function DateTime({ value }: { value?: string | null }) {
  const lang = useLang();
  if (!value) return <span className="text-muted">—</span>;
  return <time dateTime={value} className="num">{new Date(value).toLocaleString(localeOf(lang))}</time>;
}

export function ProblemBox({ problem, title }: { problem?: Problem | null; title?: string }) {
  if (!problem) return null;
  return (
    <div role="alert" className="rounded-[var(--radius)] border-2 border-bad bg-bad-soft p-3 text-bad">
      <p className="font-semibold">{title ?? problem.title ?? "Request failed"}{problem.status ? ` (${problem.status})` : ""}</p>
      {problem.detail && <p className="mt-1">{problem.detail}</p>}
      {problem.code && <p className="mt-1 font-mono text-sm">{problem.code}</p>}
      {problem.errors?.map((e) => <p key={e.field + e.code} className="text-sm">{e.field}: {e.message}</p>)}
      {problem.request_id && <p className="mt-1 text-xs">request {problem.request_id}</p>}
    </div>
  );
}

export function Notice({ tone = "info", children }: { tone?: "info" | "good" | "warn" | "bad"; children: ReactNode }) {
  const c = { info: "border-accent bg-accent-soft text-accent", good: "border-good bg-good-soft text-good",
    warn: "border-warn bg-warn-soft text-warn", bad: "border-bad bg-bad-soft text-bad" }[tone];
  return <div role="status" className={cx("rounded-[var(--radius)] border-2 p-3 font-medium", c)}>{children}</div>;
}

export function Row({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-border py-2 last:border-0">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-semibold">{children}</dd>
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return <p role="status" className="py-6 text-center text-muted">{label ?? "…"}</p>;
}

export function Tile({ href, label, icon, badge }: { href: string; label: string; icon: ReactNode; badge?: ReactNode }) {
  return (
    <Link href={href} className="relative flex min-h-24 flex-col items-center justify-center gap-2 rounded-[var(--radius)] border border-border bg-surface p-3 text-center font-semibold hover:bg-surface-2">
      <span aria-hidden className="text-accent">{icon}</span>
      <span>{label}</span>
      {badge}
    </Link>
  );
}

/** Tiny inline icons (no icon library: keeps the bundle small). */
export const Icon = {
  in: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 3v14m0 0-5-5m5 5 5-5M4 21h16" /></svg>,
  out: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 17V3m0 0L7 8m5-5 5 5M4 21h16" /></svg>,
  bill: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M6 2h12v20l-3-2-3 2-3-2-3 2zM9 7h6M9 11h6M9 15h4" /></svg>,
  phone: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="6" y="2" width="12" height="20" rx="2" /><path d="M11 18h2" /></svg>,
  user: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="8" r="4" /><path d="M4 21c1-4 4-6 8-6s7 2 8 6M19 4v4M17 6h4" /></svg>,
  float: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 7h18v10H3zM7 12h.01M17 12h.01" /><circle cx="12" cy="12" r="2" /></svg>,
  till: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 10h18v10H3zM6 10V4h12v6M8 14h8" /></svg>,
  coin: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="9" /><path d="M12 7v10M9 9.5c0-1 1.3-1.5 3-1.5s3 .7 3 2-1.3 1.7-3 2-3 .8-3 2 1.3 2 3 2 3-.5 3-1.5" /></svg>,
  list: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" /></svg>,
  sync: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M20 11a8 8 0 0 0-14.9-3M4 13a8 8 0 0 0 14.9 3M4 4v4h4M20 20v-4h-4" /></svg>,
  gear: <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" /></svg>,
  home: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M3 11 12 3l9 8v10H3z" /></svg>,
  more: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 6h16M4 12h16M4 18h16" /></svg>,
};
