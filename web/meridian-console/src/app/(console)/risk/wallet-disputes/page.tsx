"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-OPS-1 (G-09, D-ED-13): wallet transaction dispute tracker.
const STATUSES = ["RAISED", "UNDER_INVESTIGATION", "RESOLVED", "REJECTED", "WITHDRAWN"] as const;
const REASONS = ["WRONG_RECIPIENT", "DUPLICATE", "FRAUDULENT", "ERRONEOUS_AMOUNT", "OTHER"] as const;

function Disputes() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("wallet-disputes", "/v1/risk/wallet-disputes", filters);

  return (
    <>
      <PageHeader title="Wallet disputes" subtitle="Customer transaction disputes (GSMA reversal-policy aligned)" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "reason", label: "Reason", options: REASONS },
          { name: "reference", label: "Reference" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/wallet-disputes/${r.id}`)}
            empty="No disputes."
            columns={[
              { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
              { key: "reason", header: "Reason" },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "repeat_claimant", header: "Repeat", render: (r) => r.repeat_claimant ? <Badge value="REPEAT" /> : <span className="text-muted">—</span> },
              { key: "due_at", header: "SLA due", render: dateCell("due_at") },
              { key: "created_at", header: "Raised", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Disputes /></Suspense>;
}
