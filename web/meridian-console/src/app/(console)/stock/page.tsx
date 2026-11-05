"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-FLOAT-1 (G-02): operator e-money stock / float management (stock operations).
function Stock() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("stock-operations", "/v1/stock-operations", filters);

  return (
    <>
      <PageHeader title="Stock & float" subtitle="Operator e-money stock creation, allocation and reimbursement" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "operation_type", label: "Type" },
          { name: "reference", label: "Reference" },
          { name: "currency", label: "Currency", placeholder: "KES" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/stock/${r.id}`)}
            empty="No stock operations."
            columns={[
              { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
              { key: "operation_type", header: "Type" },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "created_at", header: "Created", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Stock /></Suspense>;
}
