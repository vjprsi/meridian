"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { GUIDES } from "@/lib/guides";
import { buildIndex, hrefFor, searchIndex } from "@/lib/search";
import { useCatalog, useSearchIndex } from "@/lib/spec";
import { MethodBadge } from "./code";
import { useSession } from "./providers";
import { cx } from "./ui";


export function Logo({ sub = "Developers" }: { sub?: string }) {
  return (
    <Link href="/" className="flex items-center gap-2">
      <span className="grid h-7 w-7 place-items-center rounded-md bg-accent text-xs font-bold text-accent-fg">M</span>
      <span className="text-sm font-semibold">Meridian <span className="font-normal text-muted">{sub}</span></span>
    </Link>
  );
}

/** Header search: loads the index on first focus, shows the top hits, Enter opens the full results page. */
export function SearchBox({ className, autoFocus }: { className?: string; autoFocus?: boolean }) {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [wanted, setWanted] = useState(false);
  const [sel, setSel] = useState(0);
  const ref = useRef<HTMLInputElement>(null);
  const idx = useSearchIndex(wanted);
  const index = useMemo(() => (idx.data ? buildIndex(idx.data) : []), [idx.data]);
  const hits = useMemo(() => searchIndex(index, q, 8), [index, q]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (e.key === "/" && !["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName)) {
        e.preventDefault();
        ref.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div className={cx("relative", className)}>
      <input ref={ref} type="search" value={q} autoFocus={autoFocus} aria-label="Search the API reference and events"
        placeholder="Search operations, paths, events…  ( / )"
        onFocus={() => { setWanted(true); setOpen(true); }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => { setQ(e.target.value); setSel(0); setOpen(true); }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, hits.length - 1)); }
          if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
          if (e.key === "Escape") { setOpen(false); ref.current?.blur(); }
          if (e.key === "Enter") {
            e.preventDefault();
            setOpen(false);
            if (hits[sel] && !e.shiftKey) router.push(hrefFor(hits[sel]));
            else if (q.trim()) router.push(`/search?q=${encodeURIComponent(q.trim())}`);
          }
        }}
        className="w-full rounded-[var(--radius)] border border-border bg-surface-2 px-3 py-1.5 text-sm outline-none focus:border-accent focus:bg-surface" />
      {open && q.trim() && (
        <div className="absolute right-0 left-0 z-50 mt-1 max-h-[70vh] overflow-auto rounded-[var(--radius)] border border-border bg-surface shadow-lg">
          {idx.isLoading && <p className="px-3 py-2 text-xs text-muted">Loading index…</p>}
          {!idx.isLoading && hits.length === 0 && <p className="px-3 py-2 text-xs text-muted">No matches.</p>}
          {hits.map((h, i) => (
            <Link key={`${h.k}:${h.d}:${h.id}:${h.m}`} href={hrefFor(h)} onMouseDown={(e) => e.preventDefault()}
              onClick={() => setOpen(false)}
              className={cx("flex items-center gap-2 px-3 py-1.5 text-sm", i === sel ? "bg-accent-soft" : "hover:bg-surface-2")}>
              <MethodBadge method={h.m} />
              <span className="min-w-0 flex-1 truncate">
                <span className="font-mono text-xs">{h.k === "ev" ? h.id : h.p}</span>
                <span className="ml-2 text-xs text-muted">{h.s}</span>
              </span>
              <span className="shrink-0 text-[10px] uppercase text-muted">{h.d}</span>
            </Link>
          ))}
          {hits.length > 0 && (
            <Link href={`/search?q=${encodeURIComponent(q.trim())}`} onMouseDown={(e) => e.preventDefault()}
              className="block border-t border-border px-3 py-1.5 text-xs text-accent">All results for “{q.trim()}” →</Link>
          )}
        </div>
      )}
    </div>
  );
}

const TOP_NAV = [
  { href: "/docs", label: "Guides" },
  { href: "/reference", label: "API reference" },
  { href: "/events", label: "Events" },
];

export function SiteHeader() {
  const path = usePathname();
  const { session } = useSession();
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-surface/95 backdrop-blur">
      <div className="mx-auto flex max-w-[90rem] items-center gap-6 px-4 py-2.5 md:px-6">
        <Logo />
        <nav className="hidden items-center gap-1 md:flex" aria-label="Docs">
          {TOP_NAV.map((n) => (
            <Link key={n.href} href={n.href}
              className={cx("rounded-md px-2.5 py-1.5 text-sm",
                path.startsWith(n.href) ? "bg-accent-soft font-medium text-accent" : "text-muted hover:text-text")}>
              {n.label}
            </Link>
          ))}
        </nav>
        <SearchBox className="ml-auto w-full max-w-sm" />
        <Link href={session ? "/console" : "/login"}
          className="shrink-0 rounded-[var(--radius)] bg-accent px-3 py-1.5 text-sm font-medium text-accent-fg hover:opacity-90">
          {session ? "Console" : "Sign in"}
        </Link>
      </div>
    </header>
  );
}

/** Left navigation for guides, reference domains and the events catalogue. */
export function DocsSidebar() {
  const path = usePathname();
  const catalog = useCatalog();
  const item = (href: string, label: ReactNode, active: boolean) => (
    <Link key={href} href={href}
      className={cx("block rounded-md px-2 py-1 text-sm",
        active ? "bg-accent-soft font-medium text-accent" : "text-text hover:bg-surface-2")}>{label}</Link>
  );
  return (
    <aside className="sticky top-[53px] hidden h-[calc(100vh-53px)] w-60 shrink-0 overflow-y-auto border-r border-border px-3 py-5 lg:block">
      <nav className="flex flex-col gap-5" aria-label="Documentation">
        <div>
          <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">Guides</p>
          {GUIDES.map((g) => item(`/docs/guides/${g.slug}`, g.title, path === `/docs/guides/${g.slug}`))}
        </div>
        <div>
          <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">API reference</p>
          {item("/reference", "All domains", path === "/reference")}
          {(catalog.data?.domains ?? []).map((d) => item(`/reference/${d.id}`, d.title, path === `/reference/${d.id}`))}
        </div>
        <div>
          <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">Events</p>
          {item("/events", "Event catalogue", path === "/events")}
        </div>
      </nav>
    </aside>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t border-border bg-surface">
      <div className="mx-auto flex max-w-[90rem] flex-wrap items-center justify-between gap-3 px-6 py-5 text-xs text-muted">
        <span>Meridian Core developer portal · contracts generated from <span className="font-mono">spec/domains</span></span>
        <span className="flex gap-4">
          <a className="hover:text-text" href="/specs/asyncapi/meridian.asyncapi.yaml">AsyncAPI</a>
          <Link className="hover:text-text" href="/reference">OpenAPI</Link>
          <Link className="hover:text-text" href="/docs/guides/versioning">Changelog</Link>
        </span>
      </div>
    </footer>
  );
}
