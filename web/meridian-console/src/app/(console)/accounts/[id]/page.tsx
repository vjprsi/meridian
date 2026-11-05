"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { accountDetailSections } from "@/lib/entities/account";

type A = Record<string, unknown>;

/** Reason/comment-based lifecycle transitions available per status. */
const ACTIONS: Record<string, { action: string; label: string; field?: "reason" | "comment"; danger?: boolean }[]> = {
  PENDING_APPROVAL: [{ action: "approve", label: "Approve", field: "comment" }],
  PENDING_FUNDING: [{ action: "activate", label: "Activate" }],
  APPROVED: [{ action: "activate", label: "Activate" }],
  ACTIVE: [{ action: "freeze", label: "Freeze", field: "reason", danger: true }],
  FROZEN: [{ action: "unfreeze", label: "Unfreeze", field: "reason" }],
  DORMANT: [{ action: "reactivate", label: "Reactivate", field: "reason" }],
};

function rows(v: unknown): Record<string, unknown>[] {
  if (Array.isArray(v)) return v as Record<string, unknown>[];
  if (v && typeof v === "object" && Array.isArray((v as { data?: unknown }).data)) return (v as { data: Record<string, unknown>[] }).data;
  return [];
}

type TabKey = "transactions" | "holders" | "mandates" | "blocks" | "status" | "capitalisations" | "fees";
const TABS: { key: TabKey; label: string; path: (id: string) => string }[] = [
  { key: "transactions", label: "Transactions", path: (id) => `/v1/deposit-accounts/${id}/transactions` },
  { key: "holders", label: "Holders", path: (id) => `/v1/deposit-accounts/${id}/holders` },
  { key: "mandates", label: "Mandates", path: (id) => `/v1/deposit-accounts/${id}/mandates` },
  { key: "blocks", label: "Blocks", path: (id) => `/v1/deposit-accounts/${id}/blocks` },
  { key: "status", label: "Status history", path: (id) => `/v1/deposit-accounts/${id}/status-history` },
  { key: "capitalisations", label: "Interest", path: (id) => `/v1/deposit-accounts/${id}/capitalisations` },
  { key: "fees", label: "Fees", path: (id) => `/v1/deposit-accounts/${id}/fees` },
];

const COLUMNS: Record<TabKey, { key: string; header: string; render?: (r: Record<string, unknown>) => React.ReactNode; align?: "right" }[]> = {
  transactions: [
    { key: "value_date", header: "Value date" }, { key: "narrative", header: "Narrative", render: (r) => String(r.narrative ?? r.description ?? "—") },
    { key: "direction", header: "Dir" }, { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} currency={r.currency as string} /> },
  ],
  holders: [
    { key: "customer_id", header: "Customer", render: (r) => <Mono>{String(r.customer_id ?? "—")}</Mono> }, { key: "role", header: "Role" },
    { key: "can_transact", header: "Can transact", render: (r) => r.can_transact ? "✓" : "" },
    { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
  ],
  mandates: [
    { key: "name", header: "Mandate" }, { key: "operation_type", header: "Operation" }, { key: "rule", header: "Rule" },
    { key: "min_amount", header: "From", align: "right", render: (r) => <Money amount={r.min_amount as string} /> },
  ],
  blocks: [
    { key: "block_type", header: "Type" }, { key: "scope", header: "Scope" },
    { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> }, { key: "reason", header: "Reason" },
  ],
  status: [
    { key: "from_status", header: "From" }, { key: "to_status", header: "To" }, { key: "reason", header: "Reason" }, { key: "changed_at", header: "When" },
  ],
  capitalisations: [
    { key: "capitalisation_date", header: "Date" }, { key: "kind", header: "Kind" },
    { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} /> },
    { key: "tax_withheld", header: "Tax", align: "right", render: (r) => <Money amount={r.tax_withheld as string} /> },
  ],
  fees: [
    { key: "fee_code", header: "Fee" }, { key: "charged_at", header: "When" },
    { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} /> }, { key: "reason", header: "Reason" },
  ],
};

export default function AccountDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [tab, setTab] = useState<TabKey>("transactions");

  const account = useQuery({ queryKey: ["account", id], queryFn: () => call<A>("GET", `/v1/deposit-accounts/${id}`) });
  const balance = useQuery({ queryKey: ["account-balance", id], queryFn: () => call<A>("GET", `/v1/deposit-accounts/${id}/balance`), retry: false });
  const tabQuery = useQuery({
    queryKey: ["account-tab", id, tab],
    queryFn: () => call<unknown>("GET", TABS.find((t) => t.key === tab)!.path(id)),
    retry: false,
  });
  const act = useMutation({
    mutationFn: ({ action, field }: { action: string; field?: string }) =>
      call("POST", `/v1/deposit-accounts/${id}/${action}`, field ? { [field]: reason || undefined } : {}),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["account", id] }); qc.invalidateQueries({ queryKey: ["account-balance", id] }); },
  });

  const a = account.data;
  if (account.isLoading) return <Loading />;
  const status = String(a?.status ?? "");
  const ccy = a?.currency as string | undefined;
  const bal = balance.data;

  return (
    <>
      <PageHeader title={String(a?.nickname || a?.account_number || "Account")}
        subtitle={a && <><Mono>{String(a.account_number ?? a.id)}</Mono> · {String(a.account_type)} · {String(a.currency)} · <Badge value={status} /></>} />
      <ErrorBanner error={account.error ?? act.error} />

      {bal && (
        <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
          <Card title="Ledger balance"><div className="text-lg font-semibold"><Money amount={bal.ledger_balance as string ?? bal.balance as string} currency={ccy} /></div></Card>
          <Card title="Available"><div className="text-lg font-semibold"><Money amount={bal.available_balance as string ?? bal.available as string} currency={ccy} /></div></Card>
          <Card title="Pending"><div className="text-lg font-semibold"><Money amount={bal.pending as string ?? bal.pending_balance as string} currency={ccy} /></div></Card>
        </div>
      )}

      <Card title="Lifecycle" className="mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Reason / comment"><Input placeholder="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} className="min-w-64" /></Field>
          {(ACTIONS[status] ?? []).map((x) => (
            <Button key={x.action} variant={x.danger ? "danger" : "primary"} disabled={act.isPending}
              onClick={() => act.mutate({ action: x.action, field: x.field })}>{x.label}</Button>
          ))}
          <Button variant="secondary" disabled={act.isPending} onClick={() => act.mutate({ action: "capitalise-interest" })}>Capitalise interest</Button>
        </div>
      </Card>

      {a && <DetailSections sections={accountDetailSections(a)} />}

      <Card className="mt-6">
        <div className="mb-4 flex flex-wrap gap-1 border-b border-border">
          {TABS.map((t) => (
            <button key={t.key} onClick={() => setTab(t.key)}
              className={"rounded-t-md px-3 py-2 text-sm " + (tab === t.key ? "border-b-2 border-accent font-medium" : "text-muted hover:text-accent")}>
              {t.label}
            </button>
          ))}
        </div>
        {tabQuery.isLoading ? <Loading /> : (
          <Table rows={rows(tabQuery.data)} columns={COLUMNS[tab]} empty={`No ${tab}.`} />
        )}
      </Card>
    </>
  );
}
