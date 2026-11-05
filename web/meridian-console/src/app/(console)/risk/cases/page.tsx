"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { caseFormSections } from "@/lib/entities/case";
import { Filters, Pager, amountCell, statusCell, usePaged } from "@/components/ops";
import { CASE_TYPES, SEVERITIES, Severity, overdue } from "../_shared";

const STATUSES = ["OPEN", "INVESTIGATING", "PENDING_APPROVAL", "ESCALATED", "SAR_FILED", "CLOSED"];

function Cases() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(params.get("new") === "1");
  const list = usePaged("aml-cases", "/v1/risk/cases", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Record<string, unknown>>("POST", "/v1/risk/cases", body),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["aml-cases"] });
      if (c?.id) router.push(`/risk/cases/${c.id}`);
    },
  });

  return (
    <>
      <PageHeader title="Investigation cases" subtitle="AML, fraud and sanctions cases: investigation, disposition (maker-checker) and SAR filing"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New case"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={caseFormSections} submitLabel="Open case"
            initial={{ priority: "MEDIUM" }} pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
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
