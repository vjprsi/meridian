"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Select, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { termDepositFormSections, TERM_DEPOSIT_STATUS } from "@/lib/entities/account-extras";
import type { Page } from "@/lib/types";

type TermDeposit = Record<string, unknown>;

function TermDeposits() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [open, setOpen] = useState(params.get("new") === "1");
  const [status, setStatus] = useState("");
  const [currency, setCurrency] = useState("");

  const q = new URLSearchParams({ limit: "50" });
  if (status) q.set("status", status);
  if (currency) q.set("currency", currency);
  const list = useQuery({
    queryKey: ["term-deposits", status, currency],
    queryFn: () => call<Page<TermDeposit>>("GET", `/v1/term-deposits?${q.toString()}`),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<TermDeposit>("POST", "/v1/term-deposits", body),
    onSuccess: (t) => { qc.invalidateQueries({ queryKey: ["term-deposits"] }); router.push(`/accounts/term-deposits/${t.id}`); },
  });

  return (
    <>
      <PageHeader title="Term deposits" subtitle="Fixed-term deposits: placement, maturity, rollover and premature withdrawal"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "Place term deposit"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={termDepositFormSections} submitLabel="Place term deposit"
            initial={{ day_count: "ACT_365", interest_payout_mode: "AT_MATURITY", rollover_instruction: "PAY_OUT_ALL" }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <div className="mb-4 flex flex-wrap gap-2">
          <Select options={["", ...TERM_DEPOSIT_STATUS]} value={status} onChange={(e) => setStatus(e.target.value)} />
          <Select options={["", "USD", "EUR", "GBP", "KES", "NGN", "ZAR"]} value={currency} onChange={(e) => setCurrency(e.target.value)} />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={(list.data?.data ?? []) as Record<string, unknown>[]} onRow={(r) => router.push(`/accounts/term-deposits/${r.id}`)}
            empty="No term deposits."
            columns={[
              { key: "deposit_number", header: "Deposit number", render: (r) => <Mono>{String(r.deposit_number ?? "—")}</Mono> },
              { key: "customer_id", header: "Customer", render: (r) => <Mono>{String(r.customer_id ?? "—").slice(0, 8)}</Mono> },
              { key: "principal_amount", header: "Principal", align: "right", render: (r) => <Money amount={r.principal_amount as string} currency={r.currency as string} /> },
              { key: "currency", header: "Ccy" },
              { key: "maturity_date", header: "Maturity" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><TermDeposits /></Suspense>;
}
