"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-OPS-1 (G-09, D-ED-13): maker-checker wallet transaction corrections (reversals).
const STATUSES = ["REQUESTED", "PENDING_APPROVAL", "APPROVED", "EXECUTED", "REJECTED", "FAILED"] as const;

function Corrections() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("wallet-corrections", "/v1/risk/wallet-corrections", filters);

  return (
    <>
      <PageHeader title="Transaction corrections" subtitle="Maker-checker reversal / correction of wallet transactions" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "reason", label: "Reason" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/wallet-corrections/${r.id}`)}
            empty="No corrections."
            columns={[
              { key: "reason", header: "Reason" },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "requested_by", header: "Maker", render: (r) => <Mono>{str(r.requested_by)}</Mono> },
              { key: "reversal_transaction_id", header: "Reversal txn", render: (r) => <Mono>{str(r.reversal_transaction_id)}</Mono> },
              { key: "created_at", header: "Requested", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Corrections /></Suspense>;
}
