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
    { href: "/onboarding", label: "Onboarding queue", live: true },
    { href: "/kyc", label: "KYC checks", live: true },
  ] },
  { group: "Accounts & deposits", items: [
    { href: "/accounts", label: "Accounts", live: true },
    { href: "/accounts/term-deposits", label: "Term deposits", live: true },
    { href: "/accounts/overdrafts", label: "Overdrafts", live: true },
    { href: "/accounts/standing-orders", label: "Standing orders", live: true },
  ] },
  { group: "Ledger", items: [
    { href: "/ledger", label: "Ledger explorer", live: true },
    { href: "/ledger/post", label: "Post journal entry", live: true },
  ] },
  { group: "Payments", items: [
    { href: "/payments", label: "Payments", live: true },
    { href: "/payments/new", label: "New payment", live: true },
    { href: "/payments/orders", label: "Order & Pay (ETM)", live: true },
    { href: "/payments/bulk", label: "Bulk payments", live: true },
  ] },
  { group: "Cards", items: [
    { href: "/cards", label: "Cards", live: true },
    { href: "/cards/programs", label: "Card programs", live: true },
    { href: "/cards/authorisations", label: "Authorisations", live: true },
    { href: "/cards/disputes", label: "Disputes", live: true },
  ] },
  { group: "Lending", items: [
    { href: "/lending/applications", label: "Loan applications", live: true },
    { href: "/lending/loans", label: "Loans", live: true },
    { href: "/lending/collections", label: "Collections", live: true },
  ] },
  { group: "Wallets & agency", items: [
    { href: "/wallets", label: "Wallets", live: true },
    { href: "/wallets/agents", label: "Agents", live: true },
    { href: "/wallets/merchants", label: "Merchants", live: true },
    { href: "/wallets/disbursements", label: "Disbursements", live: true },
    { href: "/wallets/vouchers", label: "Vouchers", live: true },
    { href: "/wallets/cashout", label: "Cash-out auth", live: true },
    { href: "/wallets/commission", label: "Commission", live: true },
    { href: "/wallets/commission-payouts", label: "Commission payouts", live: true },
    { href: "/wallets/joining-commissions", label: "Joining commissions", live: true },
    { href: "/wallets/outlets", label: "Agent outlets", live: true },
    { href: "/stock", label: "Stock & float", live: true },
  ] },
  { group: "Risk & compliance", items: [
    { href: "/risk/fraud", label: "Fraud alerts", live: true },
    { href: "/risk/aml", label: "AML monitoring", live: true },
    { href: "/risk/sanctions", label: "Sanctions hits", live: true },
    { href: "/risk/cases", label: "Cases", live: true },
    { href: "/risk/wallet-disputes", label: "Wallet disputes", live: true },
    { href: "/risk/wallet-corrections", label: "Corrections", live: true },
    { href: "/risk/tcp", label: "Transfer control profiles", live: true },
  ] },
  { group: "Reconciliation", items: [
    { href: "/safeguarding", label: "Safeguarding (trust)", live: true },
  ] },
  { group: "Treasury & reporting", items: [
    { href: "/treasury/deals", label: "Treasury deals", live: true },
    { href: "/treasury/liquidity", label: "Liquidity (LCR/NSFR)", live: true },
    { href: "/reporting/regulatory", label: "Regulatory reports", live: true },
    { href: "/reporting/operational", label: "Operational reports", live: true },
  ] },
  { group: "Products", items: [{ href: "/products", label: "Product Studio", live: true }] },
  { group: "Configuration", items: [
    { href: "/config/domains", label: "Domains", live: true },
    { href: "/config/categories", label: "Categories", live: true },
    { href: "/config/grades", label: "Grades", live: true },
    { href: "/config/billers", label: "Billers", live: true },
    { href: "/config/banks", label: "Banks", live: true },
  ] },
  { group: "Control", items: [
    { href: "/approvals", label: "Approvals inbox", live: true },
    { href: "/audit", label: "Audit log", live: true },
    { href: "/admin/users", label: "System users", live: true },
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
      <aside className="sticky top-0 hidden h-screen w-60 shrink-0 overflow-y-auto border-r border-border bg-surface px-3 py-5 md:block">
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
