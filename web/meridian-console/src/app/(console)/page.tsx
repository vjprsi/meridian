"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Card, PageHeader } from "@/components/ui";
import type { Page } from "@/lib/types";

/** Counts one page (max 200) of a list endpoint; returns null when the owning service is not running. */
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
        {q.isLoading ? "…" : q.isError ? <span className="text-sm font-normal text-muted">service offline</span> : q.data}
      </p>
    </Link>
  );
}

export default function Dashboard() {
  return (
    <>
      <PageHeader title="Dashboard" subtitle="Operational overview for this tenant" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Customers" path="/v1/customers" href="/customers" />
        <Stat label="Ledgers" path="/v1/ledgers" href="/ledger" />
        <Stat label="Products" path="/v1/products" href="/products" />
        <Stat label="Pending approvals" path="/v1/approval-requests?status=PENDING" href="/approvals" />
      </div>
      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card title="Quick actions">
          <ul className="space-y-2 text-sm">
            <li><Link className="text-accent hover:underline" href="/customers?new=1">Create a customer</Link></li>
            <li><Link className="text-accent hover:underline" href="/ledger/post">Post a journal entry</Link></li>
            <li><Link className="text-accent hover:underline" href="/approvals">Review approvals</Link></li>
            <li><Link className="text-accent hover:underline" href="/products">Design a product</Link></li>
          </ul>
        </Card>
        <Card title="How this console works">
          <p className="text-sm text-muted">
            Every screen calls the same public APIs available to integrators, through the edge gateway. Writes carry
            an Idempotency-Key, so retries never double-post; errors show the stable error code and request id.
          </p>
        </Card>
      </div>
    </>
  );
}
