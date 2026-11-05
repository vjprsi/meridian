"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Card, PageHeader } from "@/components/ui";
import type { Page } from "@/lib/types";

/** Counts one page (max 200) of a list endpoint; shows "offline" when the owning service is not running. */
function useCount(path: string) {
  return useQuery({
    queryKey: ["count", path],
    queryFn: async () => {
      const page = await call<Page<unknown>>("GET", `${path}${path.includes("?") ? "&" : "?"}limit=200`);
      return `${page.data.length}${page.has_more ? "+" : ""}`;
    },
    retry: false,
  });
}

function Stat({ label, path, href }: { label: string; path: string; href: string }) {
  const q = useCount(path);
  return (
    <Link href={href} className="rounded-[var(--radius)] border border-border bg-surface p-5 hover:border-accent">
      <p className="text-xs font-medium uppercase tracking-wide text-muted">{label}</p>
      <p className="mt-2 text-2xl font-semibold num">
        {q.isLoading ? "…" : q.isError ? <span className="text-sm font-normal text-muted">offline</span> : q.data}
      </p>
    </Link>
  );
}

const GROUPS: { title: string; tiles: { label: string; path: string; href: string }[] }[] = [
  {
    title: "Customers & accounts",
    tiles: [
      { label: "Customers", path: "/v1/customers", href: "/customers" },
      { label: "Deposit accounts", path: "/v1/deposit-accounts", href: "/accounts" },
      { label: "Term deposits", path: "/v1/term-deposits", href: "/accounts/term-deposits" },
      { label: "Wallets", path: "/v1/wallets", href: "/wallets" },
    ],
  },
  {
    title: "Lending & cards",
    tiles: [
      { label: "Loan applications", path: "/v1/loan-applications", href: "/lending/applications" },
      { label: "Loans", path: "/v1/loans", href: "/lending/loans" },
      { label: "Cards", path: "/v1/cards", href: "/cards" },
      { label: "Agents", path: "/v1/agents", href: "/wallets/agents" },
    ],
  },
  {
    title: "Operations & risk",
    tiles: [
      { label: "Payments", path: "/v1/payments", href: "/payments" },
      { label: "Products", path: "/v1/products", href: "/products" },
      { label: "Open cases", path: "/v1/risk/cases", href: "/risk/cases" },
      { label: "Pending approvals", path: "/v1/approval-requests?status=PENDING", href: "/approvals" },
    ],
  },
];

export default function Dashboard() {
  return (
    <>
      <PageHeader title="Dashboard" subtitle="Operational overview for this tenant" />
      <div className="space-y-6">
        {GROUPS.map((g) => (
          <div key={g.title}>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted">{g.title}</p>
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              {g.tiles.map((t) => <Stat key={t.href} {...t} />)}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-8 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card title="Quick actions">
          <ul className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
            <li><Link className="text-accent hover:underline" href="/customers?new=1">Onboard a customer</Link></li>
            <li><Link className="text-accent hover:underline" href="/accounts?new=1">Open an account</Link></li>
            <li><Link className="text-accent hover:underline" href="/accounts/term-deposits?new=1">Place a term deposit</Link></li>
            <li><Link className="text-accent hover:underline" href="/lending/applications?new=1">New loan application</Link></li>
            <li><Link className="text-accent hover:underline" href="/cards?new=1">Issue a card</Link></li>
            <li><Link className="text-accent hover:underline" href="/payments/new">Make a payment</Link></li>
            <li><Link className="text-accent hover:underline" href="/ledger/post">Post a journal entry</Link></li>
            <li><Link className="text-accent hover:underline" href="/approvals">Review approvals</Link></li>
          </ul>
        </Card>
        <Card title="How this console works">
          <p className="text-sm text-muted">
            Every screen calls the same public APIs available to integrators, through the edge gateway. Writes carry
            an Idempotency-Key, so retries never double-post; errors show the stable error code and request id. Tiles
            read live counts per service and show “offline” when a service is not running.
          </p>
        </Card>
      </div>
    </>
  );
}
