"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, amountCell, dateCell, str, usePaged, type Row } from "@/components/ops";
import type { Page as ApiPage } from "@/lib/types";

const STATUSES = ["DRAFT", "VALIDATING", "VALIDATED", "PENDING_APPROVAL", "APPROVED", "PROCESSING", "COMPLETED",
  "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "REJECTED"] as const;

function Disbursements() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const programs = useQuery({
    queryKey: ["disbursement-programs"], retry: false,
    queryFn: () => call<ApiPage<Row>>("GET", "/v1/disbursement-programs?limit=200"),
  });
  const programName = new Map((programs.data?.data ?? []).map((p) => [String(p.id), `${str(p.code)} · ${str(p.name)}`]));
  const activePrograms = (programs.data?.data ?? []).filter((p) => p.status === "ACTIVE");
  const batches = usePaged("disbursement-batches", "/v1/disbursement-batches", filters);

  return (
    <>
      <PageHeader title="Disbursement batches" subtitle="Bulk payouts (G2P, payroll, aid) to wallets, with multi-level approval"
        actions={activePrograms.length > 0 && (
          <Action label="New batch" path="/v1/disbursement-batches" invalidate={[["disbursement-batches"]]}
            description="Creates a draft batch; upload the beneficiary file on the batch page."
            onDone={(b) => router.push(`/wallets/disbursements/${(b as Row).id}`)}
            body={(v) => {
              const p = activePrograms.find((x) => String(x.code) === v.program);
              return { disbursement_program_id: p?.id, currency: p?.currency, description: v.description || undefined,
                scheduled_for: v.scheduled_for ? new Date(v.scheduled_for).toISOString() : undefined };
            }}
            fields={[{ name: "program", label: "Program (code)", required: true, options: activePrograms.map((p) => String(p.code)) },
              { name: "description", label: "Description" },
              { name: "scheduled_for", label: "Scheduled for", type: "datetime-local" }]} />
        )} />
      <ErrorBanner error={programs.error} />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "disbursement_program_id", label: "Program ID" },
          { name: "batch_reference", label: "Batch reference" },
        ]} />
        <ErrorBanner error={batches.error} />
        {batches.isLoading ? <Loading /> : (
          <Table rows={batches.rows} onRow={(r) => router.push(`/wallets/disbursements/${r.id}`)} empty="No batches match."
            columns={[
              { key: "batch_reference", header: "Reference", render: (r) => <Mono>{str(r.batch_reference)}</Mono> },
              { key: "program", header: "Program", render: (r) => programName.get(String(r.disbursement_program_id)) ?? <Mono>{String(r.disbursement_program_id).slice(0, 8)}…</Mono> },
              { key: "description", header: "Description" },
              { key: "total_count", header: "Lines", align: "right" },
              { key: "total_amount", header: "Total", align: "right", render: amountCell("total_amount") },
              { key: "approvals", header: "Approvals", align: "right", render: (r) => `${str(r.approvals_received)}/${str(r.approvals_required)}` },
              { key: "created_at", header: "Created", render: dateCell("created_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
            ]} />
        )}
        <Pager p={batches} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Disbursements /></Suspense>;
}
