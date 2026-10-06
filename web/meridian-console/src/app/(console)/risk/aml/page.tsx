"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, amountCell, statusCell, usePaged } from "@/components/ops";
import { SEVERITIES, Severity } from "../_shared";

const STATUSES = ["OPEN", "TRIAGED", "ESCALATED", "CLOSED_NO_ACTION", "CLOSED_CASE_OPENED"];

function AmlAlerts() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "OPEN" });
  const list = usePaged("tm-alerts", "/v1/risk/monitoring/alerts", filters);

  return (
    <>
      <PageHeader title="AML monitoring alerts"
        subtitle="Transaction-monitoring scenario hits awaiting triage: close with rationale or escalate to an AML case" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "severity", label: "Severity", options: SEVERITIES },
          { name: "assigned_to", label: "Assignee", placeholder: "analyst user" },
          { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
          { name: "account_id", label: "Account ID", placeholder: "uuid" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/aml/${r.id}`)} empty="No monitoring alerts match."
            columns={[
              { key: "created_at", header: "Raised", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "severity", header: "Severity", render: (r) => <Severity value={r.severity} /> },
              { key: "score", header: "Score", align: "right" },
              { key: "customer_id", header: "Customer", render: (r) => <Mono>{String(r.customer_id ?? "—").slice(0, 8)}</Mono> },
              { key: "transaction_count", header: "Txns", align: "right" },
              { key: "total_amount", header: "Total", align: "right", render: amountCell("total_amount") },
              { key: "period", header: "Period", render: (r) => <span className="text-xs">
                <DateTime value={r.period_start as string} /> → <DateTime value={r.period_end as string} /></span> },
              { key: "assigned_to", header: "Assignee", render: (r) => String(r.assigned_to ?? "unassigned") },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><AmlAlerts /></Suspense>;
}
