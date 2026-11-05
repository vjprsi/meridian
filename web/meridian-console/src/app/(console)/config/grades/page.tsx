"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, str, usePaged } from "@/components/ops";

// WE-HIER-1 (G-06): Domain -> Category -> Grade. Grades.
function Grades() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("grades", "/v1/grades", filters);

  return (
    <>
      <PageHeader title="Grades" subtitle="Leaf level of the Domain → Category → Grade hierarchy (keys limits + pricing)"
        actions={
          <Action label="New grade" path="/v1/grades" invalidate={[["grades"]]}
            title="Create grade" description="Created as a draft; submit it for maker-checker approval."
            fields={[
              { name: "domain_category_id", label: "Category id", required: true, placeholder: "parent category uuid" },
              { name: "code", label: "Code", required: true },
              { name: "name", label: "Name", required: true },
            ]}
            onDone={(r) => { const id = (r as { id?: string })?.id; if (id) router.push(`/config/grades/${id}`); }} />
        } />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "domain_category_id", label: "Category id" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/config/grades/${r.id}`)}
            empty="No grades."
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
  return <Suspense><Grades /></Suspense>;
}
