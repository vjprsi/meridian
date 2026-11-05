"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Select, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { standingOrderFormSections, STANDING_ORDER_STATUS } from "@/lib/entities/account-extras";
import type { Page } from "@/lib/types";

type StandingOrder = Record<string, unknown>;

function StandingOrders() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [open, setOpen] = useState(params.get("new") === "1");
  const [status, setStatus] = useState("");

  const q = new URLSearchParams({ limit: "50" });
  if (status) q.set("status", status);
  const list = useQuery({
    queryKey: ["standing-orders", status],
    queryFn: () => call<Page<StandingOrder>>("GET", `/v1/standing-orders?${q.toString()}`),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<StandingOrder>("POST", "/v1/standing-orders", body),
    onSuccess: (s) => { qc.invalidateQueries({ queryKey: ["standing-orders"] }); router.push(`/accounts/standing-orders/${s.id}`); },
  });

  return (
    <>
      <PageHeader title="Standing orders" subtitle="Recurring and future-dated payment instructions from deposit accounts"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New standing order"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={standingOrderFormSections} submitLabel="New standing order"
            initial={{ frequency: "MONTHLY", holiday_rule: "NEXT" }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <div className="mb-4 flex flex-wrap gap-2">
          <Select options={["", ...STANDING_ORDER_STATUS]} value={status} onChange={(e) => setStatus(e.target.value)} />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/accounts/standing-orders/${r.id}`)}
            empty="No standing orders."
            columns={[
              { key: "deposit_account_id", header: "Debit account", render: (r) => <Mono>{String(r.deposit_account_id ?? "—").slice(0, 8)}</Mono> },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} currency={r.currency as string} /> },
              { key: "currency", header: "Ccy" },
              { key: "frequency", header: "Frequency" },
              { key: "next_execution_date", header: "Next run" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><StandingOrders /></Suspense>;
}
