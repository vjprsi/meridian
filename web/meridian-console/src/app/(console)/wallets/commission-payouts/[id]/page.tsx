"use client";

import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, Money, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, Tabs, amountCell, str, usePaged, type Row } from "@/components/ops";

// WE-PRICE-1 (G-05): one commission payout run and its per-beneficiary lines.
// PENDING_APPROVAL -> (approve) RUNNING -> SUCCEEDED | PARTIALLY_SUCCEEDED | FAILED; cancel while open; retry failed lines.
const APPROVABLE = ["PENDING_APPROVAL"];
const CANCELLABLE = ["PENDING_APPROVAL", "RUNNING"];
const RETRYABLE = ["PARTIALLY_SUCCEEDED", "FAILED"];

const TABS = [
  { id: "detail", label: "Detail" },
  { id: "lines", label: "Payout lines" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function PayoutRunDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("detail");
  const key = ["commission-payout-runs", id];
  const inv = [["commission-payout-runs"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/commission-payout-runs/${id}`) });
  const lines = usePaged("commission-payout-runs", `/v1/commission-payout-runs/${id}/lines`, {},
    { enabled: tab === "lines", limit: 50 });

  if (q.isLoading) return <Loading />;
  const r = q.data;
  const status = String(r?.status ?? "");

  return (
    <>
      <PageHeader title="Commission payout run"
        subtitle={r && <>{str(r.period_start)} → {str(r.period_end)} · {str(r.payout_frequency)} · <Badge value={status} /></>}
        actions={r && <>
          {APPROVABLE.includes(status) && (
            <Action label="Approve & run" path={`/v1/commission-payout-runs/${id}/approve`} invalidate={inv}
              title="Approve payout run"
              description="Releases the accrued commission to each beneficiary wallet. The checker must differ from the maker." />
          )}
          {RETRYABLE.includes(status) && (
            <Action label="Retry failed" variant="secondary" path={`/v1/commission-payout-runs/${id}/retry-failed`} invalidate={inv}
              title="Retry failed payout lines"
              description="Re-attempts only the lines that did not pay out." />
          )}
          {CANCELLABLE.includes(status) && (
            <Action label="Cancel" variant="danger" path={`/v1/commission-payout-runs/${id}/cancel`} invalidate={inv}
              confirmLabel="Cancel run"
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={q.error} />

      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />

        {tab === "detail" && r && (
          <Details items={[
            ["Period", <span key="p">{str(r.period_start)} → {str(r.period_end)}</span>],
            ["Frequency", r.payout_frequency],
            ["Currency", r.currency],
            ["Created", r.created_at],
            ["Updated", r.updated_at],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        )}

        {tab === "lines" && (
          <>
            <ErrorBanner error={lines.error} />
            {lines.isLoading ? <Loading /> : (
              <Table rows={lines.rows} empty="No payout lines."
                columns={[
                  { key: "beneficiary_wallet_id", header: "Beneficiary wallet", render: (x) => <Mono>{str(x.beneficiary_wallet_id)}</Mono> },
                  { key: "earning_count", header: "Earnings", align: "right", render: (x) => str(x.earning_count) },
                  { key: "gross_amount", header: "Gross", align: "right", render: amountCell("gross_amount") },
                  { key: "tax_amount", header: "Tax", align: "right", render: amountCell("tax_amount") },
                  { key: "net_amount", header: "Net", align: "right", render: amountCell("net_amount") },
                  { key: "attempt", header: "Attempt", align: "right", render: (x) => str(x.attempt) },
                  { key: "payout_status", header: "Status", render: (x) => <Badge value={str(x.payout_status)} /> },
                ]} />
            )}
            <Pager p={lines} />
          </>
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><PayoutRunDetail /></Suspense>;
}
