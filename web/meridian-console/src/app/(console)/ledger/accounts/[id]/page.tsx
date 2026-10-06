"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, DateTime, ErrorBanner, Input, Loading, Money, PageHeader, Select, Table } from "@/components/ui";
import type { AccountBalance, LedgerAccount, LedgerEntry, Page } from "@/lib/types";

export default function AccountDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [freezeTo, setFreezeTo] = useState("FROZEN_DEBIT");
  const [reason, setReason] = useState("");

  const account = useQuery({ queryKey: ["account", id], queryFn: () => call<LedgerAccount>("GET", `/v1/ledger-accounts/${id}`) });
  const balance = useQuery({ queryKey: ["balance", id], queryFn: () => call<AccountBalance>("GET", `/v1/ledger-accounts/${id}/balance`) });
  const entries = useQuery({ queryKey: ["entries", id],
    queryFn: () => call<Page<LedgerEntry>>("GET", `/v1/ledger-accounts/${id}/entries?limit=100`) });
  const refresh = () => ["account", "balance", "entries"].forEach((k) => qc.invalidateQueries({ queryKey: [k, id] }));
  const freeze = useMutation({ mutationFn: () => call("POST", `/v1/ledger-accounts/${id}/freeze`, { status: freezeTo, reason }), onSuccess: refresh });
  const unfreeze = useMutation({ mutationFn: () => call("POST", `/v1/ledger-accounts/${id}/unfreeze`, { reason }), onSuccess: refresh });

  const a = account.data;
  const frozen = a?.status?.startsWith("FROZEN");
  return (
    <>
      <PageHeader title={a ? `${a.code} · ${a.name}` : "Account"}
        subtitle={a && <>{a.account_type} · normal {a.normal_balance} · {a.currency} · <Badge value={a.status} /></>} />
      <ErrorBanner error={account.error ?? freeze.error ?? unfreeze.error} />
      <div className="mb-6 grid grid-cols-1 gap-4 md:grid-cols-4">
        {[["Posted balance", balance.data?.posted_balance], ["Available", balance.data?.available_balance],
          ["Held (pending debits)", balance.data?.pending_debits], ["Entries", String(balance.data?.account_version ?? "")]]
          .map(([label, v]) => (
            <div key={label} className="rounded-[var(--radius)] border border-border bg-surface p-4">
              <p className="text-xs uppercase tracking-wide text-muted">{label}</p>
              <p className="mt-1 text-lg font-semibold">
                {label === "Entries" ? <span className="num">{v}</span> : <Money amount={v} currency={a?.currency} />}
              </p>
            </div>
          ))}
      </div>
      <Card title="Restrictions" className="mb-6">
        <div className="flex flex-wrap items-end gap-3">
          {!frozen && <Select options={["FROZEN_DEBIT", "FROZEN_CREDIT", "FROZEN"]} value={freezeTo} onChange={(e) => setFreezeTo(e.target.value)} />}
          <Input placeholder="Reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} className="min-w-64" />
          {frozen
            ? <Button disabled={!reason || unfreeze.isPending} onClick={() => unfreeze.mutate()}>Unfreeze</Button>
            : <Button variant="danger" disabled={!reason || freeze.isPending} onClick={() => freeze.mutate()}>Freeze</Button>}
        </div>
      </Card>
      <Card title="Ledger entries">
        {entries.isLoading ? <Loading /> : (
          <Table rows={(entries.data?.data ?? []) as Record<string, unknown>[]} empty="No postings yet."
            columns={[
              { key: "account_version", header: "#", render: (r) => <span className="num">{String(r.account_version)}</span> },
              { key: "created_at", header: "Booked", render: (r) => <DateTime value={String(r.created_at)} /> },
              { key: "direction", header: "Side", render: (r) => <Badge value={String(r.direction)} tone={r.direction === "DEBIT" ? "info" : "muted"} /> },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={String(r.amount)} currency={String(r.currency)} /> },
              { key: "balance_after", header: "Balance after", align: "right", render: (r) => <Money amount={String(r.balance_after)} currency={String(r.currency)} /> },
              { key: "description", header: "Description" },
            ]} />
        )}
      </Card>
    </>
  );
}
