"use client";

import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Stat, Tabs, str, type Row } from "@/components/ops";
import { DetailSections } from "@/components/detail";
import { bulkPaymentDetailSections } from "@/lib/entities/payment";

// PAY-01: one bulk (batch) payment — the batch record, its item lines and the four-eyes lifecycle actions.
const TERMINAL = ["COMPLETED", "REJECTED", "CANCELLED"];

const TABS = [
  { id: "detail", label: "Detail" },
  { id: "items", label: "Items" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function BulkDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("detail");
  const key = ["bulk-payments", id];
  const inv = [["bulk-payments"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/bulk-payments/${id}`) });
  const items = useQuery({ queryKey: [...key, "items"], enabled: tab === "items",
    queryFn: () => call<Row[]>("GET", `/v1/bulk-payments/${id}/items`) });

  if (q.isLoading) return <Loading />;
  const b = q.data;
  const status = String(b?.status ?? "");
  const active = !TERMINAL.includes(status);

  return (
    <>
      <PageHeader title="Bulk payment"
        subtitle={b && <><span className="font-medium">{str(b.name)}</span> · <Money amount={b.total_amount as string} currency={b.currency as string} /> · <Badge value={status} /></>}
        actions={b && <>
          {active && (status === "UPLOADED" || status === "VALIDATING" || status === "VALIDATED") && (
            <Action label="Validate" variant="secondary" path={`/v1/bulk-payments/${id}/validate`} invalidate={inv}
              title="Re-run validation" description="Re-run validation of all items in the batch." />
          )}
          {status === "VALIDATED" && (
            <Action label="Submit" path={`/v1/bulk-payments/${id}/submit`} invalidate={inv}
              title="Submit for approval / execution" />
          )}
          {status === "PENDING_APPROVAL" && (
            <Action label="Approve" path={`/v1/bulk-payments/${id}/approve`} invalidate={inv}
              title="Approve and execute (four-eyes)"
              description="The submitter cannot approve the batch they submitted."
              fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
          )}
          {status === "PENDING_APPROVAL" && (
            <Action label="Reject" variant="secondary" path={`/v1/bulk-payments/${id}/reject`} invalidate={inv}
              title="Reject the submitted batch"
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
          {active && (
            <Action label="Cancel" variant="danger" path={`/v1/bulk-payments/${id}/cancel`} invalidate={inv}
              confirmLabel="Cancel batch"
              fields={[{ name: "reason", label: "Reason", type: "textarea" }]} />
          )}
        </>} />
      <ErrorBanner error={q.error} />

      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Items" value={str(b?.item_count)} />
        <Stat label="Valid" value={str(b?.valid_count)} tone="good" />
        <Stat label="Failed" value={str(b?.failed_count)} tone={Number(b?.failed_count) > 0 ? "bad" : undefined} />
        <Stat label="Settled" value={str(b?.settled_count)} />
      </div>

      {b && <div className="mb-6"><DetailSections sections={bulkPaymentDetailSections(b as Record<string, unknown>)} /></div>}

      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />
        {tab === "detail" && b && (
          <p className="text-sm text-muted">Full batch record is shown above. Open the Items tab for the line-by-line breakdown.</p>
        )}
        {tab === "items" && (
          <>
            <ErrorBanner error={items.error} />
            {items.isLoading ? <Loading /> : (
              <Table rows={(items.data ?? []) as Record<string, unknown>[]} empty="No items."
                columns={[
                  { key: "line_no", header: "Line", render: (r) => str(r.line_no) },
                  { key: "creditor_name", header: "Beneficiary", render: (r) => str(r.creditor_name) },
                  { key: "creditor_account_number", header: "Account", render: (r) => <Mono>{str(r.creditor_account_number)}</Mono> },
                  { key: "amount", header: "Amount", align: "right", render: (r) =>
                    <Money amount={r.amount as string} currency={r.currency as string} /> },
                  { key: "end_to_end_id", header: "E2E", render: (r) => <Mono>{str(r.end_to_end_id)}</Mono> },
                  { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
                ]} />
            )}
          </>
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><BulkDetail /></Suspense>;
}
