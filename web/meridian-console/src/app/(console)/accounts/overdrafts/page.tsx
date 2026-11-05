"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Select, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { overdraftFormSections, OVERDRAFT_TYPE, OVERDRAFT_FACILITY_STATUS } from "@/lib/entities/account-extras";
import type { Page } from "@/lib/types";

type Overdraft = Record<string, unknown>;

function Overdrafts() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [open, setOpen] = useState(params.get("new") === "1");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");

  const q = new URLSearchParams({ limit: "50" });
  if (type) q.set("overdraft_type", type);
  if (status) q.set("status", status);
  const list = useQuery({
    queryKey: ["overdraft-facilities", type, status],
    queryFn: () => call<Page<Overdraft>>("GET", `/v1/overdraft-facilities?${q.toString()}`),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Overdraft>("POST", "/v1/overdraft-facilities", body),
    onSuccess: (o) => { qc.invalidateQueries({ queryKey: ["overdraft-facilities"] }); router.push(`/accounts/overdrafts/${o.id}`); },
  });

  return (
    <>
      <PageHeader title="Overdraft facilities" subtitle="Arranged, temporary and salary-advance overdrafts on deposit accounts"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New overdraft facility"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={overdraftFormSections} submitLabel="New overdraft facility"
            initial={{ overdraft_type: "ARRANGED" }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <div className="mb-4 flex flex-wrap gap-2">
          <Select options={["", ...OVERDRAFT_TYPE]} value={type} onChange={(e) => setType(e.target.value)} />
          <Select options={["", ...OVERDRAFT_FACILITY_STATUS]} value={status} onChange={(e) => setStatus(e.target.value)} />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/accounts/overdrafts/${r.id}`)}
            empty="No overdraft facilities."
            columns={[
              { key: "deposit_account_id", header: "Account", render: (r) => <Mono>{String(r.deposit_account_id ?? "—").slice(0, 8)}</Mono> },
              { key: "overdraft_type", header: "Type" },
              { key: "limit_amount", header: "Limit", align: "right", render: (r) => <Money amount={r.limit_amount as string} currency={r.currency as string} /> },
              { key: "utilised_amount", header: "Utilised", align: "right", render: (r) => <Money amount={r.utilised_amount as string} currency={r.currency as string} /> },
              { key: "currency", header: "Ccy" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Overdrafts /></Suspense>;
}
