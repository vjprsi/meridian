"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Card, DateTime, Loading, Mono, Money, PageHeader } from "@/components/ui";
import { Action, Details, type Row } from "@/components/ops";

// WE-OPS-1 (G-09, D-ED-13): one maker-checker wallet transaction correction (reversal). A different user must
// approve the maker's request before the compensating REVERSAL transaction is posted.
const DECIDABLE = ["REQUESTED", "PENDING_APPROVAL"];

function CorrectionDetail() {
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const key = ["wallet-corrections", id];
  const inv = [["wallet-corrections"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/risk/wallet-corrections/${id}`) });

  if (q.isLoading) return <Loading />;
  const c = q.data;
  const status = String(c?.status ?? "");
  const ownRequest = c?.requested_by && c.requested_by === session?.user;

  return (
    <>
      <PageHeader title="Transaction correction"
        subtitle={c && <>{String(c.reason ?? "").replaceAll("_", " ")} · <Money amount={c.amount as string} currency={c.currency as string} /> · <Badge value={status} /></>}
        actions={c && DECIDABLE.includes(status) && (
          <>
            <Action label="Approve" path={`/v1/risk/wallet-corrections/${id}/approve`} invalidate={inv}
              disabled={!!ownRequest}
              title="Approve correction"
              description="Posts the compensating REVERSAL transaction. The checker must differ from the maker."
              fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
            <Action label="Reject" variant="danger" path={`/v1/risk/wallet-corrections/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          </>
        )} />

      {c && ownRequest && DECIDABLE.includes(status) && (
        <Card className="mb-6">
          <p className="rounded bg-warn-soft p-2 text-xs text-warn">You requested this correction; another user must approve it.</p>
        </Card>
      )}

      {c && (
        <Card title="Correction">
          <Details items={[
            ["Reason", String(c.reason ?? "").replaceAll("_", " ")],
            ["Amount", <Money key="a" amount={c.amount as string} currency={c.currency as string} />],
            ["Wallet", c.wallet_id ? <Mono key="w">{String(c.wallet_id)}</Mono> : null],
            ["Original txn", c.transaction_id ? <Mono key="t">{String(c.transaction_id)}</Mono> : null],
            ["Reversal txn", c.reversal_transaction_id ? <Mono key="rt">{String(c.reversal_transaction_id)}</Mono> : null],
            ["Rationale", c.rationale],
            ["Maker", c.requested_by],
            ["Requested", c.created_at],
            ["Checker", c.approved_by],
            ["Decided", c.approved_at ?? c.decided_at],
            ["Status", <Badge key="s" value={status} />],
          ]} />
          {c.reject_reason ? <p className="mt-4 text-sm text-muted">Rejected: {String(c.reject_reason)} <DateTime value={c.rejected_at as string} /></p> : null}
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><CorrectionDetail /></Suspense>;
}
