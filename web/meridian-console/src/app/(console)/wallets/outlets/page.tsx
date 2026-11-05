"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, str, usePaged } from "@/components/ops";

// MovilRed Admin: agent outlet network.
function Outlets() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("agent-outlets", "/v1/agent-outlets", filters);

  return (
    <>
      <PageHeader title="Agent outlets" subtitle="Cash-in / cash-out agent outlet network" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "agent_id", label: "Agent id" },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/outlets/${r.id}`)}
            empty="No outlets."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "location", header: "Location" },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Outlets /></Suspense>;
}
