"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, str, usePaged } from "@/components/ops";

// WE-HIER-1 (G-06): Domain -> Category -> Grade. Categories.
function Categories() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("domain-categories", "/v1/domain-categories", filters);

  return (
    <>
      <PageHeader title="Domain categories" subtitle="Middle level of the Domain → Category → Grade hierarchy"
        actions={
          <Action label="New category" path="/v1/domain-categories" invalidate={[["domain-categories"]]}
            title="Create domain category" description="Created as a draft; submit it for maker-checker approval."
            fields={[
              { name: "distribution_domain_id", label: "Domain id", required: true, placeholder: "parent domain uuid" },
              { name: "code", label: "Code", required: true },
              { name: "name", label: "Name", required: true },
            ]}
            onDone={(r) => { const id = (r as { id?: string })?.id; if (id) router.push(`/config/categories/${id}`); }} />
        } />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "distribution_domain_id", label: "Domain id" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/config/categories/${r.id}`)}
            empty="No categories."
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
  return <Suspense><Categories /></Suspense>;
}
