"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";
import { appUserFormSections } from "@/lib/entities/config";

// MovilRed Admin: System User Management (admin/operator users + roles).
function Users() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("admin-users", "/v1/users", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => {
      // Derive display_name when the operator left it blank.
      if (!body.display_name) {
        body.display_name = [body.first_name, body.last_name].filter(Boolean).join(" ") || undefined;
      }
      return call("POST", "/v1/users", body);
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["admin-users"] }); setOpen(false); },
  });

  return (
    <>
      <PageHeader title="System users" subtitle="Admin / operator users and their roles"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New user"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={appUserFormSections} submitLabel="Create user"
            initial={{ user_type: "STAFF", locale: "en", timezone: "UTC" }}
            pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
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
