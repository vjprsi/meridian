"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, rows, usePaged } from "@/components/ops";
import { BUCKETS, Bucket, CASE_STATUSES, EXPOSURE_TYPES, money } from "../_components/common";

function Collections() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("collection-cases", "/v1/collections/cases", filters);
  const queues = useQuery({ queryKey: ["collection-queues"], queryFn: () => call<unknown>("GET", "/v1/collections/queues?limit=100"),
    retry: false });

  return (
    <>
      <PageHeader title="Collections" subtitle="Delinquent exposures worked by collectors, agencies and legal" />
      <Card className="mb-6" title="Queues">
        <ErrorBanner error={queues.error} />
        {queues.isLoading ? <Loading /> : (
          <Table rows={rows(queues.data)} empty="No collection queues configured."
            columns={[
              { key: "code", header: "Code", render: (r) => <span className="font-medium">{String(r.code)}</span> },
              { key: "name", header: "Name" },
              { key: "team", header: "Team" },
              { key: "assignment_mode", header: "Assignment" },
              { key: "agents", header: "Collectors", align: "right", render: (r) => String((r.agent_user_ids as unknown[] | null)?.length ?? 0) },
              { key: "is_active", header: "Status", render: (r) => <Badge value={r.is_active ? "ACTIVE" : "INACTIVE"} /> },
              { key: "actions", header: "", render: (r) => (
                <div className="flex gap-1">
                  <button className="text-xs text-accent hover:underline" onClick={() => setFilters({ collection_queue_id: String(r.id) })}>Show cases</button>
                  <Action label="Rebalance" size="sm" variant="secondary" path={`/v1/collections/queues/${r.id}/rebalance`}
                    invalidate={[["collection-cases"]]} description="Redistribute this queue's open cases evenly across its collectors." />
                </div>
              ) },
            ]} />
        )}
      </Card>
      <Card title="Cases">
        <Filters key={JSON.stringify(filters)} value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: CASE_STATUSES },
          { name: "delinquency_bucket", label: "DPD bucket", options: BUCKETS },
          { name: "exposure_type", label: "Exposure", options: EXPOSURE_TYPES },
          { name: "is_disputed", label: "Disputed", options: ["true", "false"] },
          { name: "assigned_to", label: "Collector", placeholder: "user id" },
          { name: "collection_queue_id", label: "Queue ID", placeholder: "uuid" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/lending/collections/${r.id}`)} empty="No cases match."
            columns={[
              { key: "case_number", header: "Case", render: (r) => <Mono>{String(r.case_number ?? r.id)}</Mono> },
              { key: "exposure_type", header: "Exposure" },
              { key: "days_past_due", header: "DPD", align: "right" },
              { key: "delinquency_bucket", header: "Bucket", render: (r) => <Bucket value={r.delinquency_bucket} /> },
              { key: "overdue_amount", header: "Overdue", align: "right", render: (r) => money(r, "overdue_amount") },
              { key: "total_outstanding", header: "Outstanding", align: "right", render: (r) => money(r, "total_outstanding") },
              { key: "priority_score", header: "Priority", align: "right" },
              { key: "assigned_to", header: "Collector" },
              { key: "next_action_at", header: "Next action", render: (r) => <DateTime value={r.next_action_at as string} /> },
              { key: "status", header: "Status", render: (r) => <span className="flex gap-1"><Badge value={String(r.status)} />
                {r.is_disputed ? <Badge value="DISPUTED" tone="bad" /> : null}</span> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Collections /></Suspense>;
}
