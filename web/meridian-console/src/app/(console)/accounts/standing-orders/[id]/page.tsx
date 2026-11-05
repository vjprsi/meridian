"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { standingOrderDetailSections } from "@/lib/entities/account-extras";

type SO = Record<string, unknown>;

/** Lifecycle actions available per status (accounts.yaml standing-orders apis block). */
const ACTIONS: Record<string, { action: string; label: string; reason?: boolean; danger?: boolean }[]> = {
  ACTIVE: [
    { action: "pause", label: "Pause", reason: true },
    { action: "skip-next", label: "Skip next" },
    { action: "cancel", label: "Cancel", reason: true, danger: true },
  ],
  PAUSED: [
    { action: "resume", label: "Resume" },
    { action: "cancel", label: "Cancel", reason: true, danger: true },
  ],
  FAILED_SUSPENDED: [
    { action: "resume", label: "Resume" },
    { action: "cancel", label: "Cancel", reason: true, danger: true },
  ],
};

function rows(v: unknown): Record<string, unknown>[] {
  if (Array.isArray(v)) return v as Record<string, unknown>[];
  if (v && typeof v === "object" && Array.isArray((v as { data?: unknown }).data)) return (v as { data: Record<string, unknown>[] }).data;
  return [];
}

export default function StandingOrderDetail() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");

  const order = useQuery({ queryKey: ["standing-order", id], queryFn: () => call<SO>("GET", `/v1/standing-orders/${id}`) });
  const executions = useQuery({
    queryKey: ["standing-order-executions", id],
    queryFn: () => call<unknown>("GET", `/v1/standing-orders/${id}/executions`),
    retry: false,
  });
  const act = useMutation({
    mutationFn: ({ action, reason: useReason }: { action: string; reason?: boolean }) =>
      call("POST", `/v1/standing-orders/${id}/${action}`, useReason ? { reason: reason || undefined } : {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["standing-order", id] });
      qc.invalidateQueries({ queryKey: ["standing-order-executions", id] });
    },
  });

  const s = order.data;
  if (order.isLoading) return <Loading />;
  const status = String(s?.status ?? "");
  const ccy = s?.currency as string | undefined;

  return (
    <>
      <PageHeader title="Standing order"
        subtitle={s && <><Mono>{String(s.deposit_account_id ?? s.id)}</Mono> · <Money amount={s.amount as string} currency={ccy} /> · {String(s.frequency)} · <Badge value={status} /></>} />
      <ErrorBanner error={order.error ?? act.error} />

      <Card title="Lifecycle" className="mb-6">
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Reason"><Input placeholder="Reason for the change" value={reason} onChange={(e) => setReason(e.target.value)} className="min-w-64" /></Field>
          {(ACTIONS[status] ?? []).map((x) => (
            <Button key={x.action} variant={x.danger ? "danger" : "primary"} disabled={act.isPending}
              onClick={() => act.mutate({ action: x.action, reason: x.reason })}>{x.label}</Button>
          ))}
        </div>
      </Card>

      {s && <DetailSections sections={standingOrderDetailSections(s)} />}

      <Card title="Executions" className="mt-6">
        {executions.isLoading ? <Loading /> : (
          <Table rows={rows(executions.data)} empty="No executions."
            columns={[
              { key: "scheduled_date", header: "Scheduled" },
              { key: "attempt_no", header: "Attempt" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "payment_id", header: "Payment", render: (r) => <Mono>{String(r.payment_id ?? "—").slice(0, 8)}</Mono> },
              { key: "failure_code", header: "Failure code", render: (r) => String(r.failure_code ?? "—") },
              { key: "failure_reason", header: "Detail", render: (r) => String(r.failure_reason ?? "—") },
            ]} />
        )}
      </Card>
    </>
  );
}
