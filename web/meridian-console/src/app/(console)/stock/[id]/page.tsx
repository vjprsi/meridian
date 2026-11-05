"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Card, Loading, Mono, Money, PageHeader } from "@/components/ui";
import { Action, Details, type Row } from "@/components/ops";

// WE-FLOAT-1 (G-02): one operator e-money stock / float operation (creation, allocation, reimbursement).
// Maker-checker: a REQUESTED operation is approved (-> COMPLETED), rejected or cancelled by a second user.
const DECIDABLE = ["REQUESTED"];

function StockDetail() {
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const key = ["stock-operations", id];
  const inv = [["stock-operations"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/stock-operations/${id}`) });

  if (q.isLoading) return <Loading />;
  const s = q.data;
  const status = String(s?.status ?? "");
  const ownRequest = s?.requested_by && s.requested_by === session?.user;

  return (
    <>
      <PageHeader title="Stock operation"
        subtitle={s && <><Mono>{String(s.reference ?? id)}</Mono> · {String(s.operation_type ?? "").replaceAll("_", " ")} · <Badge value={status} /></>}
        actions={s && DECIDABLE.includes(status) && (
          <>
            <Action label="Approve" path={`/v1/stock-operations/${id}/approve`} invalidate={inv}
              disabled={!!ownRequest}
              title="Approve stock operation"
              description="Executes the e-money stock movement. The checker must differ from the maker."
              fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
            <Action label="Reject" variant="danger" path={`/v1/stock-operations/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
            <Action label="Cancel" variant="ghost" path={`/v1/stock-operations/${id}/cancel`} invalidate={inv}
              confirmLabel="Cancel operation"
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          </>
        )} />

      {s && ownRequest && DECIDABLE.includes(status) && (
        <Card className="mb-6">
          <p className="rounded bg-warn-soft p-2 text-xs text-warn">You requested this operation; another user must approve it.</p>
        </Card>
      )}

      {s && (
        <Card title="Stock operation">
          <Details items={[
            ["Reference", <Mono key="r">{String(s.reference ?? "—")}</Mono>],
            ["Type", String(s.operation_type ?? "").replaceAll("_", " ")],
            ["Amount", <Money key="a" amount={s.amount as string} currency={s.currency as string} />],
            ["Agent / outlet", s.agent_id ? <Mono key="g">{String(s.agent_id)}</Mono> : (s.outlet_id ? <Mono key="o">{String(s.outlet_id)}</Mono> : null)],
            ["Source wallet", s.source_wallet_id ? <Mono key="sw">{String(s.source_wallet_id)}</Mono> : null],
            ["Target wallet", s.target_wallet_id ? <Mono key="tw">{String(s.target_wallet_id)}</Mono> : null],
            ["Reason", s.reason],
            ["Maker", s.requested_by],
            ["Created", s.created_at],
            ["Checker", s.approved_by],
            ["Decided", s.approved_at ?? s.decided_at],
            ["Comment", s.comment],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><StockDetail /></Suspense>;
}
