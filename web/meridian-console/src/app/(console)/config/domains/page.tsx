"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, str, usePaged } from "@/components/ops";

// WE-HIER-1 (G-06): configurable Domain -> Category -> Grade dimension. Domains.
function Domains() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("distribution-domains", "/v1/distribution-domains", filters);

  return (
    <>
      <PageHeader title="Distribution domains" subtitle="Top level of the Domain → Category → Grade hierarchy" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/config/domains/${r.id}`)}
            empty="No domains."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Domains /></Suspense>;
}
