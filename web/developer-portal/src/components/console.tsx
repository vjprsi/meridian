"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { rememberReturnTo, SESSION_KEY, signOut } from "@/lib/auth";
import { useSession } from "./providers";
import { Logo } from "./site";
import { Button, ErrorBanner, Field, Input, cx } from "./ui";

/* ---------------------------------------------------------------------------------------------------------------
 * Authenticated console: shell with sign-in guard, keyset paging, modals, one-time secret reveal, confirmed actions.
 * ------------------------------------------------------------------------------------------------------------- */

const NAV = [
  { href: "/console", label: "Overview" },
  { href: "/console/apps", label: "Apps & API keys" },
  { href: "/console/webhooks", label: "Webhooks" },
  { href: "/console/deliveries", label: "Delivery log" },
  { href: "/console/try", label: "Try it (sandbox)" },
];

const DOCS = [
  { href: "/docs", label: "Guides" },
  { href: "/reference", label: "API reference" },
  { href: "/events", label: "Event catalogue" },
];

export function ConsoleShell({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const router = useRouter();
  const path = usePathname();

  useEffect(() => {
    const t = setTimeout(() => {
      if (!sessionStorage.getItem(SESSION_KEY)) {
        rememberReturnTo(window.location.pathname + window.location.search);
        router.replace("/login");
      }
    }, 0);
    return () => clearTimeout(t);
  }, [router]);

  const active = NAV.filter((i) => (i.href === "/console" ? path === "/console" : path === i.href || path.startsWith(i.href + "/")))
    .sort((a, b) => b.href.length - a.href.length)[0]?.href;

  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 overflow-y-auto border-r border-border bg-surface px-3 py-5 md:block">
        <div className="mb-6 px-2"><Logo sub="Console" /></div>
        <nav className="flex flex-col gap-5" aria-label="Console">
          <div>
            <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">Build</p>
            {NAV.map((i) => (
              <Link key={i.href} href={i.href}
                className={cx("block rounded-md px-2 py-1.5 text-sm",
                  i.href === active ? "bg-accent-soft font-medium text-accent" : "text-text hover:bg-surface-2")}>
                {i.label}
              </Link>
            ))}
          </div>
          <div>
            <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">Docs</p>
            {DOCS.map((i) => (
              <Link key={i.href} href={i.href} className="block rounded-md px-2 py-1.5 text-sm text-text hover:bg-surface-2">{i.label}</Link>
            ))}
          </div>
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-4 border-b border-border bg-surface px-6 py-3 text-sm">
          <div className="md:hidden"><Logo sub="Console" /></div>
          <nav className="flex gap-3 overflow-x-auto md:hidden">
            {NAV.map((i) => <Link key={i.href} href={i.href} className={cx("whitespace-nowrap", i.href === active ? "text-accent" : "text-muted")}>{i.label}</Link>)}
          </nav>
          <span className="flex-1" />
          {session && (
            <>
              <span className="hidden text-muted sm:inline">tenant <span className="font-mono text-xs">{session.tenantId.slice(0, 8)}…</span></span>
              <span className="font-medium">{session.user}</span>
              <span className="rounded bg-surface-2 px-2 py-0.5 text-xs text-muted">{session.mode === "oidc" ? "SSO" : "dev"}</span>
              <button onClick={() => signOut(session)} className="text-muted hover:text-text">Sign out</button>
            </>
          )}
        </header>
        <main className="mx-auto w-full max-w-7xl flex-1 px-6 py-8">{children}</main>
      </div>
    </div>
  );
}

export interface PageOf<T> { data: T[]; has_more: boolean; next_cursor?: string | null }

/**
 * Keyset-paged list query. The filters are part of the query key and reset paging to the first page when they change;
 * previous pages are reached through a local stack of cursors (keyset paging has no random access).
 */
export function usePaged<T>(key: QueryKey, fetchPage: (cursor: string | undefined) => Promise<PageOf<T>>,
  filters: unknown = {}, enabled = true) {
  const fk = JSON.stringify(filters);
  const [state, setState] = useState<{ fk: string; cursors: (string | undefined)[] }>({ fk, cursors: [undefined] });
  const cursors = state.fk === fk ? state.cursors : [undefined];
  const cursor = cursors[cursors.length - 1];
  const q = useQuery({
    queryKey: [...key, fk, cursor],
    queryFn: () => fetchPage(cursor),
    placeholderData: keepPreviousData,
    enabled,
  });
  return {
    ...q,
    rows: q.data?.data ?? [],
    page: cursors.length,
    hasNext: !!q.data?.has_more && !!q.data?.next_cursor,
    hasPrev: cursors.length > 1,
    next: () => { if (q.data?.next_cursor) setState({ fk, cursors: [...cursors, q.data.next_cursor] }); },
    prev: () => setState({ fk, cursors: cursors.length > 1 ? cursors.slice(0, -1) : [undefined] }),
  };
}

export function Pager({ p }: { p: { page: number; hasNext: boolean; hasPrev: boolean; next: () => void; prev: () => void; isFetching?: boolean } }) {
  if (!p.hasPrev && !p.hasNext) return null;
  return (
    <div className="mt-4 flex items-center justify-end gap-2 text-sm">
      <span className="text-muted">Page {p.page}{p.isFetching ? " · loading…" : ""}</span>
      <Button variant="secondary" disabled={!p.hasPrev} onClick={p.prev}>← Previous</Button>
      <Button variant="secondary" disabled={!p.hasNext} onClick={p.next}>Next →</Button>
    </div>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" role="dialog" aria-modal="true" aria-label={title}
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={cx("max-h-[90vh] w-full overflow-y-auto rounded-[var(--radius)] border border-border bg-surface shadow-xl", wide ? "max-w-3xl" : "max-w-lg")}>
        <header className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 className="text-sm font-semibold">{title}</h2>
          <button onClick={onClose} className="text-muted hover:text-text" aria-label="Close">✕</button>
        </header>
        <div className="p-5">{children}</div>
      </div>
    </div>
  );
}

/** Shows a secret exactly once: copy button, warning, and an explicit acknowledgement to close. */
export function SecretReveal({ title, label, secret, note, onDone }:
  { title: string; label: string; secret: string; note?: ReactNode; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <Modal title={title} onClose={onDone}>
      <div className="mb-4 rounded-[var(--radius)] border border-warn/30 bg-warn-soft px-4 py-3 text-sm text-warn">
        This is the only time the {label.toLowerCase()} is shown. It is stored hashed / encrypted and cannot be retrieved later.
      </div>
      <Field label={label}>
        <div className="flex gap-2">
          <Input readOnly value={secret} className="flex-1 font-mono text-xs" onFocus={(e) => e.currentTarget.select()} />
          <Button variant="secondary" onClick={async () => {
            try { await navigator.clipboard.writeText(secret); setCopied(true); } catch { /* clipboard blocked */ }
          }}>{copied ? "Copied" : "Copy"}</Button>
        </div>
      </Field>
      {note && <div className="mt-3 text-xs text-muted">{note}</div>}
      <div className="mt-5 flex justify-end"><Button onClick={onDone}>I&apos;ve stored it safely</Button></div>
    </Modal>
  );
}

/** Button that runs a mutation (optionally after a confirm / reason prompt) and invalidates queries on success. */
export function Action<T>({ label, run, invalidate = [], variant = "secondary", confirm, reason, onDone, disabled }: {
  label: string;
  run: (reason: string) => Promise<T>;
  invalidate?: QueryKey[];
  variant?: "primary" | "secondary" | "danger" | "ghost";
  confirm?: string;
  reason?: string;
  onDone?: (r: T) => void;
  disabled?: boolean;
}) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [why, setWhy] = useState("");
  const m = useMutation({
    mutationFn: () => run(why),
    onSuccess: (r) => {
      for (const k of invalidate) qc.invalidateQueries({ queryKey: k });
      setOpen(false);
      setWhy("");
      onDone?.(r);
    },
  });
  const needsDialog = !!confirm || !!reason;
  return (
    <>
      <Button variant={variant} disabled={disabled || m.isPending} onClick={() => (needsDialog ? setOpen(true) : m.mutate())}>
        {m.isPending && !open ? "…" : label}
      </Button>
      {!open && m.error && <span className="sr-only" role="alert">{String(m.error)}</span>}
      {open && (
        <Modal title={label} onClose={() => setOpen(false)}>
          <ErrorBanner error={m.error} />
          {confirm && <p className="mb-4 text-sm">{confirm}</p>}
          {reason && (
            <Field label={reason}>
              <Input value={why} onChange={(e) => setWhy(e.target.value)} required maxLength={255} autoFocus />
            </Field>
          )}
          <div className="mt-5 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button variant={variant === "danger" ? "danger" : "primary"} disabled={m.isPending || (!!reason && !why.trim())}
              onClick={() => m.mutate()}>{m.isPending ? "Working…" : label}</Button>
          </div>
        </Modal>
      )}
      {!needsDialog && m.error ? <ErrorInline error={m.error} /> : null}
    </>
  );
}

function ErrorInline({ error }: { error: unknown }) {
  return <span className="self-center text-xs text-bad">{error instanceof Error ? error.message : String(error)}</span>;
}

/** Key / value grid for resource details. */
export function Details({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2 lg:grid-cols-3">
      {items.map(([k, v]) => (
        <div key={k} className="min-w-0">
          <dt className="text-xs text-muted">{k}</dt>
          <dd className="mt-0.5 break-words">{v ?? <span className="text-muted">—</span>}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: readonly { id: T; label: string }[]; value: T; onChange: (t: T) => void }) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto border-b border-border" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}
          className={cx("-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm",
            t.id === value ? "border-accent font-medium text-accent" : "border-transparent text-muted hover:text-text")}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function Textarea(p: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...p} className={cx("rounded-[var(--radius)] border border-border bg-surface px-3 py-2 font-mono text-xs outline-none focus:border-accent", p.className)} />;
}

export function idem() {
  return { "Idempotency-Key": crypto.randomUUID() };
}
