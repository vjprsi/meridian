"use client";

import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, Money, PageHeader, Table } from "@/components/ui";
import { Action, Details, Tabs, rows, type Row } from "@/components/ops";

// WE-OPS-1 (G-09, D-ED-13): one wallet transaction dispute (GSMA reversal-policy aligned).
// RAISED -> UNDER_INVESTIGATION -> RESOLVED (CORRECTION raises a maker-checker correction, NO_ACTION just closes)
// | REJECTED | WITHDRAWN.
const INVESTIGATABLE = ["RAISED"];
const OPEN = ["RAISED", "UNDER_INVESTIGATION"];
const RESOLUTION_TYPES = ["CORRECTION", "NO_ACTION"] as const;

const TABS = [
  { id: "detail", label: "Detail" },
  { id: "evidence", label: "Evidence" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function DisputeDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("detail");
  const key = ["wallet-disputes", id];
  const inv = [["wallet-disputes"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/risk/wallet-disputes/${id}`) });
  const evidence = useQuery({ queryKey: [...key, "evidence"], enabled: tab === "evidence",
    queryFn: () => call<Row>("GET", `/v1/risk/wallet-disputes/${id}/evidence`) });

  if (q.isLoading) return <Loading />;
  const d = q.data;
  const status = String(d?.status ?? "");

  return (
    <>
      <PageHeader title="Wallet dispute"
        subtitle={d && <><Mono>{String(d.reference ?? id)}</Mono> · {String(d.reason ?? "").replaceAll("_", " ")} · <Badge value={status} /></>}
        actions={d && <>
          {INVESTIGATABLE.includes(status) && (
            <Action label="Investigate" variant="secondary" path={`/v1/risk/wallet-disputes/${id}/investigate`} invalidate={inv}
              title="Open investigation"
              fields={[{ name: "note", label: "Note", type: "textarea" }]} />
          )}
          {status === "UNDER_INVESTIGATION" && (
            <Action label="Resolve" path={`/v1/risk/wallet-disputes/${id}/resolve`} invalidate={inv}
              title="Resolve dispute"
              description="CORRECTION raises a maker-checker correction (reversal); NO_ACTION closes with no money movement."
              fields={[
                { name: "resolution_type", label: "Resolution", required: true, options: RESOLUTION_TYPES },
                { name: "resolution_notes", label: "Resolution notes", type: "textarea" },
                { name: "correction_reason", label: "Correction reason", hint: "Required for a CORRECTION resolution." },
                { name: "correction_amount", label: "Correction amount", type: "number", hint: "Defaults to the disputed amount." },
                { name: "correction_rationale", label: "Correction rationale", type: "textarea" },
              ]} />
          )}
          {OPEN.includes(status) && (
            <Action label="Reject" variant="danger" path={`/v1/risk/wallet-disputes/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
          {OPEN.includes(status) && (
            <Action label="Withdraw" variant="ghost" path={`/v1/risk/wallet-disputes/${id}/withdraw`} invalidate={inv}
              description="Customer withdrew the dispute."
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={q.error} />

      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />

        {tab === "detail" && d && (
          <Details items={[
            ["Reference", <Mono key="r">{String(d.reference ?? "—")}</Mono>],
            ["Reason", String(d.reason ?? "").replaceAll("_", " ")],
            ["Amount", <Money key="a" amount={d.amount as string} currency={d.currency as string} />],
            ["Wallet", d.wallet_id ? <Mono key="w">{String(d.wallet_id)}</Mono> : null],
            ["Disputed txn", d.transaction_id ? <Mono key="t">{String(d.transaction_id)}</Mono> : null],
            ["Claimant", d.claimant ?? d.raised_by],
            ["Repeat claimant", d.repeat_claimant],
            ["SLA due", d.due_at],
            ["SLA breached", d.sla_breached],
            ["Raised", d.created_at],
            ["Resolution", d.resolution_type ? String(d.resolution_type).replaceAll("_", " ") : null],
            ["Resolution notes", d.resolution_notes],
            ["Correction txn", d.correction_id ? <Mono key="c">{String(d.correction_id)}</Mono> : null],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        )}

        {tab === "evidence" && (
          <>
            <div className="mb-4">
              <Action label="Add evidence" variant="secondary" path={`/v1/risk/wallet-disputes/${id}/evidence`}
                invalidate={[[...key, "evidence"]]}
                title="Attach evidence"
                fields={[
                  { name: "evidence_type", label: "Type", placeholder: "STATEMENT / SCREENSHOT / CORRESPONDENCE" },
                  { name: "description", label: "Description", type: "textarea", required: true },
                  { name: "document_id", label: "Document ID", placeholder: "uuid (optional)" },
                ]} />
            </div>
            <ErrorBanner error={evidence.error} />
            {evidence.isLoading ? <Loading /> : (
              <Table rows={rows(evidence.data, "items")} empty="No evidence attached."
                columns={[
                  { key: "created_at", header: "Added", render: (r) => <DateTime value={r.created_at as string} /> },
                  { key: "evidence_type", header: "Type", render: (r) => String(r.evidence_type ?? "—").replaceAll("_", " ") },
                  { key: "description", header: "Description" },
                  { key: "document_id", header: "Document", render: (r) => r.document_id ? <Mono>{String(r.document_id)}</Mono> : <span className="text-muted">—</span> },
                  { key: "created_by", header: "By" },
                ]} />
            )}
          </>
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><DisputeDetail /></Suspense>;
}
