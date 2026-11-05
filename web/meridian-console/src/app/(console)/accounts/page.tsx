"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Input, Loading, Mono, PageHeader, Select, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { accountFormSections, ACCOUNT_TYPE } from "@/lib/entities/account";
import type { Page } from "@/lib/types";

type Account = Record<string, unknown>;

function Accounts() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [open, setOpen] = useState(params.get("new") === "1");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");

  const q = new URLSearchParams({ limit: "50" });
  if (type) q.set("account_type", type);
  if (status) q.set("status", status);
  const list = useQuery({
    queryKey: ["accounts", type, status],
    queryFn: () => call<Page<Account>>("GET", `/v1/deposit-accounts?${q.toString()}`),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Account>("POST", "/v1/deposit-accounts", body),
    onSuccess: (a) => { qc.invalidateQueries({ queryKey: ["accounts"] }); router.push(`/accounts/${a.id}`); },
  });

  return (
    <>
      <PageHeader title="Accounts & deposits" subtitle="Current, savings, call, notice, term and internal deposit accounts"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "Open account"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={accountFormSections} submitLabel="Open account"
            initial={{ mandate_rule: "ANY_ONE", statement_frequency: "MONTHLY", statement_cycle_day: "1" }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <div className="mb-4 flex flex-wrap gap-2">
          <Select options={["", ...ACCOUNT_TYPE]} value={type} onChange={(e) => setType(e.target.value)} />
          <Select options={["", "PENDING_APPROVAL", "PENDING_FUNDING", "ACTIVE", "DORMANT", "FROZEN", "CLOSING", "CLOSED"]}
            value={status} onChange={(e) => setStatus(e.target.value)} />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/accounts/${r.id}`)}
            empty="No accounts."
            columns={[
              { key: "account_number", header: "Account number", render: (r) => <Mono>{String(r.account_number ?? "—")}</Mono> },
              { key: "nickname", header: "Nickname" },
              { key: "account_type", header: "Type" },
              { key: "currency", header: "Ccy" },
              { key: "customer_id", header: "Customer", render: (r) => <Mono>{String(r.customer_id ?? "—").slice(0, 8)}</Mono> },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Accounts /></Suspense>;
}
