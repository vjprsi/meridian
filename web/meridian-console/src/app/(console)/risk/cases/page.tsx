"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, amountCell, statusCell, usePaged, type Row } from "@/components/ops";
import { CASE_TYPES, SEVERITIES, Severity, overdue } from "../_shared";

const STATUSES = ["OPEN", "INVESTIGATING", "PENDING_APPROVAL", "ESCALATED", "SAR_FILED", "CLOSED"];

function Cases() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("aml-cases", "/v1/risk/cases", filters);

  return (
    <>
      <PageHeader title="Investigation cases" subtitle="AML, fraud and sanctions cases: investigation, disposition (maker-checker) and SAR filing"
        actions={<Action label="New case" path="/v1/risk/cases" invalidate={[["aml-cases"]]}
          onDone={(r) => { const c = r as Row | undefined; if (c?.id) router.push(`/risk/cases/${c.id}`); }}
          fields={[
            { name: "case_type", label: "Case type", required: true, options: CASE_TYPES },
            { name: "title", label: "Title", required: true },
            { name: "priority", label: "Priority", options: SEVERITIES },
            { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
            { name: "assigned_to", label: "Investigator" },
            { name: "description", label: "Description", type: "textarea" },
          ]} />} />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "case_type", label: "Type", options: CASE_TYPES },
          { name: "priority", label: "Priority", options: SEVERITIES },
          { name: "assigned_to", label: "Investigator" },
          { name: "team", label: "Team" },
          { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/cases/${r.id}`)} empty="No cases match."
            columns={[
              { key: "case_number", header: "Case", render: (r) => <Mono>{String(r.case_number ?? "—")}</Mono> },
              { key: "title", header: "Title", render: (r) => <span className="font-medium">{String(r.title ?? "—")}</span> },
              { key: "case_type", header: "Type", render: (r) => String(r.case_type ?? "—").replaceAll("_", " ") },
              { key: "priority", header: "Priority", render: (r) => <Severity value={r.priority} /> },
              { key: "total_amount", header: "Amount", align: "right", render: amountCell("total_amount") },
              { key: "assigned_to", header: "Investigator", render: (r) => String(r.assigned_to ?? "unassigned") },
              { key: "opened_at", header: "Opened", render: (r) => <DateTime value={r.opened_at as string} /> },
              { key: "due_at", header: "Due", render: (r) => (
                <span className={overdue(r.due_at) && r.status !== "CLOSED" ? "text-bad" : undefined}>
                  <DateTime value={r.due_at as string} /></span>) },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Cases /></Suspense>;
}
