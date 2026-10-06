"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { signOut } from "@/lib/auth";
import { useSession } from "./providers";
import { cx } from "./ui";

interface NavItem { href: string; label: string; live?: boolean }

/** Navigation by business area; items marked live have working screens, the rest arrive with their services. */
const NAV: { group: string; items: NavItem[] }[] = [
  { group: "Overview", items: [{ href: "/", label: "Dashboard", live: true }] },
  { group: "Customers", items: [
    { href: "/customers", label: "Customers", live: true },
    { href: "/onboarding", label: "Onboarding queue" },
    { href: "/kyc", label: "KYC reviews" },
  ] },
  { group: "Money", items: [
    { href: "/ledger", label: "Ledger explorer", live: true },
    { href: "/ledger/post", label: "Post journal entry", live: true },
    { href: "/payments", label: "Payments" },
    { href: "/cards", label: "Cards" },
    { href: "/loans", label: "Loans" },
  ] },
  { group: "Products", items: [{ href: "/products", label: "Product Studio", live: true }] },
  { group: "Control", items: [
    { href: "/approvals", label: "Approvals inbox", live: true },
    { href: "/audit", label: "Audit log", live: true },
    { href: "/risk", label: "Fraud & AML" },
  ] },
];

export function Shell({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const router = useRouter();
  const path = usePathname();

  useEffect(() => {
    const t = setTimeout(() => {
      if (!sessionStorage.getItem("meridian.session")) router.replace("/login");
    }, 0);
    return () => clearTimeout(t);
  }, [router]);

  // The most specific nav entry matching the current path is the active one.
  const activeHref = NAV.flatMap((g) => g.items).filter((i) => i.live)
    .filter((i) => (i.href === "/" ? path === "/" : path === i.href || path.startsWith(i.href + "/")))
    .sort((a, b) => b.href.length - a.href.length)[0]?.href;

  return (
    <div className="flex min-h-screen">
      <aside className="hidden w-60 shrink-0 border-r border-border bg-surface px-3 py-5 md:block">
        <Link href="/" className="mb-6 flex items-center gap-2 px-2">
          <span className="grid h-7 w-7 place-items-center rounded-md bg-accent text-xs font-bold text-accent-fg">M</span>
          <span className="text-sm font-semibold">Meridian Console</span>
        </Link>
        <nav className="flex flex-col gap-5" aria-label="Main">
          {NAV.map((g) => (
            <div key={g.group}>
              <p className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-muted">{g.group}</p>
              {g.items.map((i) => {
                const active = i.href === activeHref;
                return i.live ? (
                  <Link key={i.href} href={i.href}
                    className={cx("block rounded-md px-2 py-1.5 text-sm",
                      active ? "bg-accent-soft font-medium text-accent" : "text-text hover:bg-surface-2")}>
                    {i.label}
                  </Link>
                ) : (
                  <span key={i.href} className="flex items-center justify-between px-2 py-1.5 text-sm text-muted"
                    title="Arrives with its service">
                    {i.label}<span className="text-[10px]">soon</span>
                  </span>
                );
              })}
            </div>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-end gap-4 border-b border-border bg-surface px-6 py-3 text-sm">
          {session && (
            <>
              <span className="text-muted">tenant <span className="font-mono text-xs">{session.tenantId.slice(0, 8)}…</span></span>
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
