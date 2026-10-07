"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { BusinessRole, BusinessUser } from "@/api/types";
import { ActionButton, opt } from "@/components/actions";
import { UserActions, UserCreated, roleOptions } from "@/components/admin-users";
import { AdminOnly } from "@/components/admin-only";
import { Async, Badge, Card, DateTime, Empty, Field, Money, PageHeader, Select, Table } from "@/components/ui";

export default function UsersPage() {
  const perms = usePermissions();
  const [status, setStatus] = useState("");
  const [created, setCreated] = useState<BusinessUser | null>(null);
  const users = useQuery({
    queryKey: ["users", status], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/users", { params: { query: {
      limit: 200, status: (status || undefined) as BusinessUser["status"] | undefined } } })).data,
  });

  return (
    <AdminOnly>
      <PageHeader title="Users" subtitle="Portal users of the business, their roles, daily limits and account entitlements"
        actions={(
          <ActionButton<BusinessUser> label="Add user" variant="primary" size="md" confirmLabel="Add user"
            fields={[
              { name: "display_name", label: "Name", required: true },
              { name: "email", label: "Email", type: "email", required: true },
              { name: "role", label: "Role", type: "select", required: true, options: roleOptions, defaultValue: "MAKER" },
              { name: "daily_limit", label: "Daily limit", type: "number" },
              { name: "limit_currency", label: "Limit currency", placeholder: "KES" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/users", {
              params: { header: idem(key) },
              body: { display_name: v.display_name, email: v.email, role: v.role as BusinessRole, daily_limit: opt(v.daily_limit),
                limit_currency: opt(v.limit_currency)?.toUpperCase() } }))}
            onDone={setCreated} />
        )} />
      <Card flush>
        <div className="border-b border-border p-4">
          <Field label="Status">
            <Select value={status} onChange={(e) => setStatus(e.target.value)} placeholder="Active and suspended"
              options={["ACTIVE", "SUSPENDED", "REMOVED"].map((s) => ({ value: s, label: s.toLowerCase() }))} />
          </Field>
        </div>
        <Async q={users} empty={<div className="p-4"><Empty title="No users" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(u) => u.id} caption="Users" columns={[
              { header: "Name", cell: (u) => <Link className="prose-link" href={`/admin/users/${u.id}`}>{u.display_name}</Link> },
              { header: "Email", cell: (u) => u.email ?? "—" },
              { header: "Role", cell: (u) => <Badge value={u.role} tone="info" /> },
              { header: "Daily limit", align: "right", cell: (u) => u.daily_limit ? <Money amount={u.daily_limit} currency={u.limit_currency} /> : "No limit" },
              { header: "Last sign-in", cell: (u) => <DateTime value={u.last_login_at} /> },
              { header: "Status", cell: (u) => <Badge value={u.status} /> },
              { header: "", align: "right", cell: (u) => <UserActions u={u} self={u.id === perms.userId} /> },
            ]} />
          )}
        </Async>
      </Card>
      <UserCreated user={created} onClose={() => setCreated(null)} />
    </AdminOnly>
  );
}
