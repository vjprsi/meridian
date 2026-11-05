"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, str, usePaged } from "@/components/ops";

// WE-LIMIT-1 (G-04): two-tier Transfer Control Profiles (cumulative KYC-tier limits).
function Tcp() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("tcp-cumulative", "/v1/risk/limits/tcp/cumulative", filters);

  return (
    <>
      <PageHeader title="Transfer control profiles"
        subtitle="Cumulative KYC-tier transfer limits (two-tier TCP). Instrument/grade TCP under limits." />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: ["DRAFT", "ACTIVE", "INACTIVE"] },
          { name: "regulatory_profile_code", label: "KYC tier" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/tcp/${r.id}`)}
            empty="No transfer control profiles."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "regulatory_profile_code", header: "KYC tier" },
              { key: "direction", header: "Direction" },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Tcp /></Suspense>;
}
