"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Money, Mono, PageHeader, Select, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { termDepositDetailSections, ROLLOVER_INSTRUCTION } from "@/lib/entities/account-extras";

type TD = Record<string, unknown>;

interface Inputs { amount: string; reason: string; reference: string; rollover: string }

/** Lifecycle actions available per status (accounts.yaml term-deposits apis block). */
const ACTIONS: Record<string, { action: string; label: string; body?: (i: Inputs) => Record<string, unknown>; danger?: boolean }[]> = {
  PENDING_FUNDING: [
    { action: "fund", label: "Fund" },
  ],
  ACTIVE: [
    { action: "top-up", label: "Top up", body: (i) => ({ amount: i.amount || undefined }) },
    { action: "withdraw", label: "Withdraw", body: (i) => ({ amount: i.amount || undefined, reason: i.reason || undefined }) },
    { action: "break", label: "Break early", body: (i) => ({ reason: i.reason || undefined }), danger: true },
    { action: "mark-lien", label: "Mark lien", body: (i) => ({ reference: i.reference || undefined, amount: i.amount || undefined }) },
    { action: "release-lien", label: "Release lien", body: (i) => ({ reference: i.reference || undefined }) },
  ],
};

function rows(v: unknown): Record<string, unknown>[] {
  if (Array.isArray(v)) return v as Record<string, unknown>[];
  if (v && typeof v === "object" && Array.isArray((v as { data?: unknown }).data)) return (v as { data: Record<string, unknown>[] }).data;
  return [];
}

export default function TermDepositDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [reference, setReference] = useState("");
  const [rollover, setRollover] = useState("");

  const deposit = useQuery({ queryKey: ["term-deposit", id], queryFn: () => call<TD>("GET", `/v1/term-deposits/${id}`) });
  const events = useQuery({
    queryKey: ["term-deposit-events", id],
    queryFn: () => call<unknown>("GET", `/v1/term-deposits/${id}/events`),
    retry: false,
  });
  const act = useMutation({
    mutationFn: ({ action, body }: { action: string; body?: Record<string, unknown> }) =>
      call("POST", `/v1/term-deposits/${id}/${action}`, body ?? {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["term-deposit", id] });
      qc.invalidateQueries({ queryKey: ["term-deposit-events", id] });
    },
  });
  const changeRollover = useMutation({
    mutationFn: () => call("POST", `/v1/term-deposits/${id}/change-rollover`, { rollover_instruction: rollover }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["term-deposit", id] }),
  });

  const t = deposit.data;
  if (deposit.isLoading) return <Loading />;
  const status = String(t?.status ?? "");
  const ccy = t?.currency as string | undefined;
  const inputs: Inputs = { amount, reason, reference, rollover };

  return (
    <>
      <PageHeader title={String(t?.deposit_number || "Term deposit")}
        subtitle={t && <><Mono>{String(t.deposit_number ?? t.id)}</Mono> · <Money amount={t.principal_amount as string} currency={ccy} /> · <Badge value={status} /></>} />
      <ErrorBanner error={deposit.error ?? (act.error ?? changeRollover.error)} />

      <Card title="Lifecycle" className="mb-6">
        <div className="mb-4 grid grid-cols-1 gap-3 md:grid-cols-3">
          <Field label="Amount"><Input type="number" step="any" placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Reason"><Input placeholder="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          <Field label="Lien reference"><Input placeholder="Collateral reference" value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          {(ACTIONS[status] ?? []).map((x) => (
            <Button key={x.action} variant={x.danger ? "danger" : "primary"} disabled={act.isPending}
              onClick={() => act.mutate({ action: x.action, body: x.body ? x.body(inputs) : undefined })}>{x.label}</Button>
          ))}
        </div>
        {status === "ACTIVE" && (
          <div className="mt-4 flex flex-wrap items-end gap-3 border-t border-border pt-4">
            <Field label="Rollover instruction">
              <Select options={["", ...ROLLOVER_INSTRUCTION]} value={rollover} onChange={(e) => setRollover(e.target.value)} className="min-w-64" />
            </Field>
            <Button variant="secondary" disabled={changeRollover.isPending || !rollover} onClick={() => changeRollover.mutate()}>Change rollover</Button>
          </div>
        )}
      </Card>

      {t && <DetailSections sections={termDepositDetailSections(t)} />}

      <Card title="Lifecycle events" className="mt-6">
        {events.isLoading ? <Loading /> : (
          <Table rows={rows(events.data)} empty="No events."
            columns={[
              { key: "event_type", header: "Event", render: (r) => <Badge value={String(r.event_type)} /> },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Money amount={r.amount as string} currency={ccy} /> },
              { key: "interest_amount", header: "Interest", align: "right", render: (r) => <Money amount={r.interest_amount as string} currency={ccy} /> },
              { key: "penalty_amount", header: "Penalty", align: "right", render: (r) => <Money amount={r.penalty_amount as string} currency={ccy} /> },
              { key: "tax_amount", header: "Tax", align: "right", render: (r) => <Money amount={r.tax_amount as string} currency={ccy} /> },
              { key: "note", header: "Note", render: (r) => String(r.note ?? "—") },
            ]} />
        )}
      </Card>
    </>
  );
}
