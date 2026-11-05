"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-PRICE-1 (G-05, D-ED-11): commission earnings — one leg per hierarchy beneficiary (Merchant -> Aggregator -> SP).
function Commission() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("commission-earnings", "/v1/commission-earnings", filters);

  return (
    <>
      <PageHeader title="Commission earnings" subtitle="Per-beneficiary commission accruals, payouts and clawback" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "beneficiary_type", label: "Beneficiary" },
          { name: "transaction_id", label: "Txn id" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/commission/${r.id}`)}
            empty="No commission earnings."
            columns={[
              { key: "beneficiary_type", header: "Beneficiary" },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "plan_id", header: "Plan", render: (r) => <Mono>{str(r.plan_id)}</Mono> },
              { key: "created_at", header: "Earned", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Commission /></Suspense>;
}
