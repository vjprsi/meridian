"use client";

import { Suspense, useState } from "react";
import { Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, str, usePaged } from "@/components/ops";

// MovilRed Admin: Bank Management (partner / settlement banks).
function Banks() {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("reference-banks", "/v1/reference/banks", filters);

  return (
    <>
      <PageHeader title="Banks" subtitle="Partner and settlement bank registry" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "country", label: "Country", placeholder: "KE" },
          { name: "name", label: "Name" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows}
            empty="No banks."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "bic", header: "BIC / SWIFT", render: (r) => <Mono>{str(r.bic)}</Mono> },
              { key: "country", header: "Country" },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Banks /></Suspense>;
}
