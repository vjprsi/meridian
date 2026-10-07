"use client";

import Link from "next/link";
import {
  cloneElement, isValidElement, useEffect, useId, useRef,
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactElement, type ReactNode, type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { ApiError } from "@/api/client";
import { useI18n, useT } from "@/lib/i18n";
import type { MessageKey } from "@/messages/en";

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(" ");
}

const focusRing = "focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-focus";

const buttonStyles = {
  primary: "bg-accent text-accent-fg hover:bg-accent-strong",
  secondary: "bg-surface border border-border-strong text-text hover:bg-surface-2",
  danger: "bg-bad text-bad-fg hover:opacity-90",
  ghost: "text-text hover:bg-surface-2",
} as const;

type Variant = keyof typeof buttonStyles;

/** Buttons are at least 44px tall (WCAG 2.5.8 target size, comfortable on touch screens). */
export function Button({ variant = "primary", className, type = "button", ...p }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type={type}
      {...p}
      className={cx("inline-flex min-h-11 items-center justify-center gap-2 rounded-[var(--radius)] px-4 py-2 text-sm font-semibold transition",
        "disabled:cursor-not-allowed disabled:opacity-60", focusRing, buttonStyles[variant], className)}
    />
  );
}

export function LinkButton({ href, variant = "primary", className, children }:
  { href: string; variant?: Variant; className?: string; children: ReactNode }) {
  return (
    <Link href={href}
      className={cx("inline-flex min-h-11 items-center justify-center gap-2 rounded-[var(--radius)] px-4 py-2 text-sm font-semibold",
        focusRing, buttonStyles[variant], className)}>
      {children}
    </Link>
  );
}

export function Card({ title, actions, children, className, as: Tag = "section" }:
  { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; as?: "section" | "div" }) {
  const id = useId();
  return (
    <Tag aria-labelledby={title ? id : undefined}
      className={cx("rounded-[var(--radius)] border border-border bg-surface", className)}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3 sm:px-5">
          {title && <h2 id={id} className="text-base font-semibold">{title}</h2>}
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
        </header>
      )}
      <div className="p-4 sm:p-5">{children}</div>
    </Tag>
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
  return /ACTIVE|APPROVED|POSTED|SETTLED|COMPLETED|PAID|VERIFIED|ACCEPTED|READY|DELIVERED|TRUSTED|CONFIRMED|PASSED|MATCH$/.test(v) ? "good"
    : /PENDING|SUBMITTED|REVIEW|PARTIAL|QUEUED|PROCESSING|INITIATED|SCREENING|AWAITING|IN_PROGRESS|STARTED|DUE|PAUSED|UPLOADED|SCHEDULED/.test(v) ? "warn"
    : /FROZEN|BLOCKED|REJECTED|FAILED|CLOSED|SUSPENDED|LOCKED|EXPIRED|CANCELLED|LOST|STOLEN|OVERDUE|REVOKED|RETURNED|NO_MATCH/.test(v) ? "bad"
    : "muted";
}

/** Status chip: always text (colour is never the only cue). */
export function Badge({ value, tone }: { value?: string | null; tone?: keyof typeof tones }) {
  const v = value ?? "—";
  return (
    <span className={cx("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold", tones[tone ?? toneOf(v)])}>
      {v.replaceAll("_", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}
    </span>
  );
}

/** Labelled form control: the label, hint and error are programmatically tied to the single child control. */
export function Field({ label, hint, error, children, optional }:
  { label: ReactNode; hint?: ReactNode; error?: ReactNode; optional?: boolean; children: ReactElement }) {
  const id = useId();
  const t = useT();
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
        {label}{optional && <span className="font-normal text-muted"> ({t("common.optional")})</span>}
      </label>
      {control}
      {hint && <p id={hintId} className="text-xs text-muted">{hint}</p>}
      {error && <p id={errId} className="text-xs font-medium text-bad">{error}</p>}
    </div>
  );
}

const inputCls = cx("min-h-11 w-full rounded-[var(--radius)] border border-border-strong bg-surface px-3 py-2 text-base text-text sm:text-sm",
  "placeholder:text-muted aria-invalid:border-bad", focusRing);

export function Input(p: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...p} className={cx(inputCls, p.className)} />;
}

export function Textarea(p: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx(inputCls, "min-h-24", p.className)} />;
}

export function Select({ options, ...p }:
  SelectHTMLAttributes<HTMLSelectElement> & { options: { value: string; label: string }[] }) {
  return (
    <select {...p} className={cx(inputCls, p.className)}>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
}

/** Formats a decimal-string amount with its currency (display only; never used for arithmetic). */
export function Money({ amount, currency, className, signed }:
  { amount?: string | number | null; currency?: string | null; className?: string; signed?: boolean }) {
  const { intl } = useI18n();
  if (amount === undefined || amount === null || amount === "") return <span className="text-muted">—</span>;
  const n = Number(amount);
  let text: string;
  try {
    text = currency
      ? new Intl.NumberFormat(intl, { style: "currency", currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
          signDisplay: signed ? "exceptZero" : "auto" }).format(n)
      : n.toLocaleString(intl);
  } catch {
    text = `${currency ?? ""} ${amount}`;
  }
  return <span className={cx("num", className)}>{text}</span>;
}

export function DateTime({ value, dateOnly }: { value?: string | null; dateOnly?: boolean }) {
  const { intl } = useI18n();
  if (!value) return <span className="text-muted">—</span>;
  const d = new Date(dateOnly && value.length === 10 ? `${value}T00:00:00` : value);
  const text = dateOnly ? d.toLocaleDateString(intl, { dateStyle: "medium" })
    : d.toLocaleString(intl, { dateStyle: "medium", timeStyle: "short" });
  return <time dateTime={value} className="num">{text}</time>;
}

/** Masks an account number / IBAN to its last four characters. */
export function masked(value?: string | null) {
  if (!value) return "—";
  return `•••• ${value.slice(-4)}`;
}

const STATUS_HINT: Record<number, MessageKey> = {
  0: "error.network",
  401: "error.401",
  403: "error.403",
  404: "error.404",
  409: "error.409",
  422: "error.422",
  429: "error.429",
};

/**
 * Renders a Meridian problem+json error: the service's message, field errors, and the stable code and request id
 * a customer can quote to support.
 */
export function ErrorBanner({ error, className }: { error: unknown; className?: string }) {
  const t = useT();
  if (!error) return null;
  const p = error instanceof ApiError ? error.problem
    : { status: 0, detail: error instanceof Error ? error.message : String(error) };
  const status = p.status ?? 0;
  const hint = STATUS_HINT[status] ?? (status >= 500 ? "error.5xx" : undefined);
  return (
    <div role="alert" className={cx("rounded-[var(--radius)] border border-bad/40 bg-bad-soft px-4 py-3 text-sm text-bad", className)}>
      <p className="font-semibold">{p.title && p.detail ? p.title : hint ? t(hint) : t("error.generic")}</p>
      {(p.detail ?? (p.title || null)) && <p className="mt-1">{p.detail ?? p.title}</p>}
      {p.errors && p.errors.length > 0 && (
        <ul className="mt-2 list-disc pl-5 text-xs">
          {p.errors.map((e, i) => <li key={`${e.field}-${i}`}>{e.message}</li>)}
        </ul>
      )}
      {(p.code || p.request_id) && (
        <p className="mt-2 text-xs opacity-90">
          {p.code && <>{t("error.code")} <span className="font-mono">{p.code}</span></>}
          {p.request_id && <> · {t("error.reference")} <span className="font-mono">{p.request_id}</span></>}
        </p>
      )}
    </div>
  );
}

export function Loading({ label, rows = 3 }: { label?: string; rows?: number }) {
  const t = useT();
  return (
    <div role="status" aria-live="polite" className="space-y-2 py-2">
      <span className="sr-only">{label ?? t("common.loading")}</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} aria-hidden="true" className="h-10 animate-pulse rounded-[var(--radius)] bg-surface-2 motion-reduce:animate-none" />
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
export function Dialog({ open, onClose, title, children, footer }:
  { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; footer?: ReactNode }) {
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
      className="m-auto w-[min(32rem,calc(100vw-2rem))] rounded-[var(--radius)] border border-border bg-surface p-0 text-text shadow-xl backdrop:bg-black/50">
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
export function Summary({ items }: { items: { label: ReactNode; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
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
