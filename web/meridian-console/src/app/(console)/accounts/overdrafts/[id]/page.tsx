"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Money, Mono, PageHeader } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { overdraftDetailSections } from "@/lib/entities/account-extras";

type OD = Record<string, unknown>;

interface Inputs { amount: string; reason: string }

/** Lifecycle actions available per status (accounts.yaml overdraft-facilities apis block). */
const ACTIONS: Record<string, { action: string; label: string; body: (i: Inputs) => Record<string, unknown>; danger?: boolean }[]> = {
  PENDING_APPROVAL: [
    { action: "approve", label: "Approve", body: (i) => ({ comment: i.reason || undefined }) },
  ],
  ACTIVE: [
    { action: "change-limit", label: "Change limit", body: (i) => ({ limit_amount: i.amount || undefined, reason: i.reason || undefined }) },
    { action: "suspend", label: "Suspend", body: (i) => ({ reason: i.reason || undefined }), danger: true },
    { action: "cancel", label: "Cancel", body: (i) => ({ reason: i.reason || undefined }), danger: true },
  ],
  SUSPENDED: [
    { action: "cancel", label: "Cancel", body: (i) => ({ reason: i.reason || undefined }), danger: true },
  ],
};

export default function OverdraftDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");

  const facility = useQuery({ queryKey: ["overdraft-facility", id], queryFn: () => call<OD>("GET", `/v1/overdraft-facilities/${id}`) });
  const act = useMutation({
    mutationFn: ({ action, body }: { action: string; body: Record<string, unknown> }) =>
      call("POST", `/v1/overdraft-facilities/${id}/${action}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["overdraft-facility", id] }),
  });

  const o = facility.data;
  if (facility.isLoading) return <Loading />;
  const status = String(o?.status ?? "");
  const ccy = o?.currency as string | undefined;
  const inputs: Inputs = { amount, reason };

  return (
    <>
      <PageHeader title="Overdraft facility"
        subtitle={o && <><Mono>{String(o.deposit_account_id ?? o.id)}</Mono> · {String(o.overdraft_type)} · <Money amount={o.limit_amount as string} currency={ccy} /> · <Badge value={status} /></>} />
      <ErrorBanner error={facility.error ?? act.error} />

      <Card title="Lifecycle" className="mb-6">
        <div className="mb-4 grid grid-cols-1 gap-3 md:grid-cols-2">
          <Field label="New limit"><Input type="number" step="any" placeholder="Limit (for change-limit)" value={amount} onChange={(e) => setAmount(e.target.value)} /></Field>
          <Field label="Reason / comment"><Input placeholder="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          {(ACTIONS[status] ?? []).map((x) => (
            <Button key={x.action} variant={x.danger ? "danger" : "primary"} disabled={act.isPending}
              onClick={() => act.mutate({ action: x.action, body: x.body(inputs) })}>{x.label}</Button>
          ))}
        </div>
      </Card>

      {o && <DetailSections sections={overdraftDetailSections(o)} />}
    </>
  );
}
