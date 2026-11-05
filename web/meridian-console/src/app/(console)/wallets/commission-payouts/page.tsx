"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-PRICE-1 (G-05): agent/merchant commission payout runs (MovilRed "commission disbursement"). A run accrues the
// due commission earnings for a period into per-beneficiary payout lines, pays them out under maker-checker approval,
// and can retry the failed lines.
const STATUSES = ["PENDING_APPROVAL", "RUNNING", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED"] as const;
const FREQUENCIES = ["REALTIME", "DAILY", "WEEKLY", "MONTHLY"] as const;

function CommissionPayouts() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("commission-payout-runs", "/v1/commission-payout-runs", filters);

  return (
    <>
      <PageHeader title="Commission payouts"
        subtitle="Agent / merchant commission disbursement runs (maker-checker)"
        actions={
          <Action label="New payout run" path="/v1/commission-payout-runs" invalidate={[["commission-payout-runs"]]}
            title="Create commission payout run"
            description="Accrues the due commission earnings for the period into per-beneficiary payout lines, pending approval."
            fields={[
              { name: "payout_frequency", label: "Frequency", required: true, options: FREQUENCIES },
              { name: "period_start", label: "Period start", type: "date", required: true },
              { name: "period_end", label: "Period end", type: "date", required: true },
              { name: "currency", label: "Currency", required: true, placeholder: "KES" },
            ]}
            onDone={(r) => { const id = (r as { id?: string })?.id; if (id) router.push(`/wallets/commission-payouts/${id}`); }} />
        } />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "payout_frequency", label: "Frequency", options: FREQUENCIES },
          { name: "currency", label: "Currency" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/commission-payouts/${r.id}`)}
            empty="No payout runs."
            columns={[
              { key: "period_start", header: "Period", render: (r) => <span>{str(r.period_start)} → {str(r.period_end)}</span> },
              { key: "payout_frequency", header: "Frequency" },
              { key: "currency", header: "Ccy" },
              { key: "created_at", header: "Created", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><CommissionPayouts /></Suspense>;
}
