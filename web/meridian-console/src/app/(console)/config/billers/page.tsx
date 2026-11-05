"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, str, usePaged } from "@/components/ops";

// MovilRed Admin: Biller Management.
function Billers() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("billers", "/v1/billers", filters);

  return (
    <>
      <PageHeader title="Billers" subtitle="Bill-payment and recharge billers" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "category", label: "Category" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/config/billers/${r.id}`)}
            empty="No billers."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "category", header: "Category" },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Billers /></Suspense>;
}
