"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useApprovalQueue, useMe, usePermissions, type Permissions } from "@/api/business";
import { signOut } from "@/lib/auth";
import { useTheme, type ThemeChoice } from "@/lib/theme";
import { useSession } from "./providers";
import { Badge, Button, ErrorBanner, cx, focusRing } from "./ui";

interface NavItem { href: string; label: string; show?: (p: Permissions) => boolean }

const NAV: { group?: string; items: NavItem[] }[] = [
  { items: [
    { href: "/", label: "Dashboard" },
    { href: "/accounts", label: "Accounts" },
    { href: "/payments", label: "Payments" },
    { href: "/approvals", label: "Approval queue", show: (p) => p.canApprove },
    { href: "/bulk", label: "Bulk & payroll", show: (p) => p.canInitiate },
    { href: "/payees", label: "Beneficiaries" },
  ] },
  { group: "Cash management", items: [
    { href: "/mandates", label: "Direct debits" },
    { href: "/fx", label: "FX & international" },
    { href: "/collections", label: "Collections" },
    { href: "/cards", label: "Expense cards" },
    { href: "/credit", label: "Loans & credit lines" },
    { href: "/statements", label: "Statements & export" },
  ] },
  { group: "Administration", items: [
    { href: "/admin/users", label: "Users", show: (p) => p.isAdmin },
    { href: "/admin/rules", label: "Approval rules", show: (p) => p.isAdmin },
    { href: "/integrations", label: "Integrations", show: (p) => p.isAdmin },
    { href: "/onboarding", label: "Business verification", show: (p) => p.isAdmin },
    { href: "/activity", label: "Security log" },
  ] },
];

function isActive(path: string, href: string) {
  return href === "/" ? path === "/" : path === href || path.startsWith(href + "/");
}

function ThemeSelect() {
  const { theme, setTheme } = useTheme();
  return (
    <label className="flex items-center gap-2 text-xs text-muted">
      <span>Theme</span>
      <select value={theme} onChange={(e) => setTheme(e.target.value as ThemeChoice)}
        className={cx("rounded border border-border-strong bg-surface px-2 py-1 text-xs text-text", focusRing)}>
        <option value="system">System</option>
        <option value="light">Light</option>
        <option value="dark">Dark</option>
      </select>
    </label>
  );
}

function Nav({ onNavigate }: { onNavigate?: () => void }) {
  const path = usePathname();
  const perms = usePermissions();
  const queue = useApprovalQueue(perms.loaded && perms.canApprove);
  const queued = perms.canApprove ? queue.data?.length ?? 0 : 0;
  return (
    <nav aria-label="Main">
      {NAV.map((g, gi) => {
        const items = g.items.filter((i) => !i.show || (perms.loaded && i.show(perms)));
        if (items.length === 0) return null;
        return (
          <div key={gi} className="mb-4">
            {g.group && <p className="px-3 pb-1 text-xs font-semibold uppercase tracking-wide text-muted">{g.group}</p>}
            <ul className="flex flex-col gap-0.5">
              {items.map((i) => {
                const active = isActive(path, i.href);
                return (
                  <li key={i.href}>
                    <Link href={i.href} onClick={onNavigate} aria-current={active ? "page" : undefined}
                      className={cx("flex min-h-9 items-center justify-between gap-2 rounded-[var(--radius)] px-3 text-sm", focusRing,
                        active ? "bg-accent-soft font-semibold text-accent" : "text-text hover:bg-surface-2")}>
                      <span>{i.label}</span>
                      {i.href === "/approvals" && queued > 0 && (
                        <span className="rounded-full bg-accent px-2 text-xs font-semibold text-accent-fg" aria-label={`${queued} waiting`}>{queued}</span>
                      )}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
    </nav>
  );
}

/** Signed-in frame: skip link, header with the business and user, role-aware side navigation, and the session guard. */
export function Shell({ children }: { children: ReactNode }) {
  const { session, ready } = useSession();
  const router = useRouter();
  const me = useMe();
  const [menu, setMenu] = useState(false);

  useEffect(() => {
    if (ready && !session) router.replace("/login");
  }, [ready, session, router]);

  if (!ready || !session) {
    return <p role="status" className="p-8 text-sm text-muted">Loading…</p>;
  }
  if (!session.businessCustomerId || !session.businessUserId) {
    return (
      <main id="main" className="mx-auto max-w-lg p-6">
        <h1 className="text-xl font-semibold">No business user</h1>
        <p className="mt-2 text-sm text-muted">
          Your sign-in does not carry a business (business_customer_id / business_user_id). Ask the bank or an administrator
          of your business to set up your portal access.
        </p>
        <Button className="mt-4" onClick={() => signOut(session)}>Sign out</Button>
      </main>
    );
  }

  const user = me.data?.user;
  const business = me.data?.business;
  const businessName = business?.display_name ?? business?.legal_name ?? "Business";

  return (
    <div className="flex min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded focus:bg-surface focus:px-4 focus:py-2 focus:text-accent">
        Skip to content
      </a>
      <header className="sticky top-0 z-30 border-b border-border bg-surface/95 backdrop-blur">
        <div className="flex items-center justify-between gap-3 px-4 py-2.5">
          <div className="flex items-center gap-3">
            <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => setMenu((m) => !m)} aria-expanded={menu}>Menu</Button>
            <Link href="/" className={cx("flex items-center gap-2 rounded", focusRing)}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/icon.svg" alt="" width={28} height={28} />
              <span className="font-semibold">Meridian Business</span>
            </Link>
            <span className="hidden text-sm text-muted sm:inline" data-testid="business-name">{me.data ? businessName : ""}</span>
          </div>
          <div className="flex items-center gap-3">
            {session.mode === "dev" && (
              <span className="hidden rounded bg-warn-soft px-2 py-0.5 text-xs font-medium text-warn sm:inline">Dev mode</span>
            )}
            <span className="hidden text-sm md:inline" data-testid="current-user">
              {user?.display_name ?? session.user} {user?.role && <Badge value={user.role} tone="info" />}
            </span>
            <ThemeSelect />
            <Button variant="secondary" size="sm" onClick={() => signOut(session)}>Sign out</Button>
          </div>
        </div>
      </header>

      <div className="flex w-full flex-1">
        <aside className={cx("w-60 shrink-0 border-r border-border bg-surface px-2 py-4",
          menu ? "fixed inset-y-14 left-0 z-20 overflow-y-auto" : "hidden lg:block")}>
          <Nav onNavigate={() => setMenu(false)} />
        </aside>
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 px-4 pb-12 pt-6 outline-none lg:px-8">
          {me.error ? (
            <div className="max-w-xl">
              <h1 className="mb-3 text-xl font-semibold">Your portal access could not be loaded</h1>
              <ErrorBanner error={me.error} />
            </div>
          ) : children}
        </main>
      </div>
    </div>
  );
}
