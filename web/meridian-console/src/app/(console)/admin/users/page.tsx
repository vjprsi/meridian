"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// MovilRed Admin: System User Management (admin/operator users + roles).
function Users() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("admin-users", "/v1/users", filters);

  return (
    <>
      <PageHeader title="System users" subtitle="Admin / operator users and their roles" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status" },
          { name: "username", label: "Username" },
          { name: "email", label: "Email" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/admin/users/${r.id}`)}
            empty="No users."
            columns={[
              { key: "username", header: "Username", render: (r) => <Mono>{str(r.username)}</Mono> },
              { key: "email", header: "Email" },
              { key: "roles", header: "Roles", render: (r) => <span>{Array.isArray(r.roles) ? (r.roles as string[]).join(", ") : str(r.roles)}</span> },
              { key: "last_login_at", header: "Last login", render: dateCell("last_login_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Users /></Suspense>;
}
