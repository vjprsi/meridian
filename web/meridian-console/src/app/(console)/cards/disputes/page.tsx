"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, ErrorBanner, Loading, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, amountCell, dateCell, statusCell, usePaged } from "@/components/ops";
import { cardDisputeFormSections } from "@/lib/entities/dispute";
import { DISPUTE_CATEGORIES, DISPUTE_STAGES, DISPUTE_STATUSES, SCHEMES } from "../shared";

/** Highlights a stage deadline that is past or within three days. */
function Deadline({ value, now }: { value?: unknown; now: number }) {
  if (!value) return <span className="text-muted">—</span>;
  const days = Math.ceil((new Date(String(value)).getTime() - now) / 86_400_000);
  const cls = days < 0 ? "text-bad font-medium" : days <= 3 ? "text-warn font-medium" : "";
  return <span className={`num ${cls}`}>{String(value)}{days <= 3 && <span className="ml-1 text-xs">({days < 0 ? "overdue" : `${days}d`})</span>}</span>;
}

function Disputes() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "OPEN" });
  const [open, setOpen] = useState(false);
  const list = usePaged("card-disputes", "/v1/card-disputes", filters);
  const [now] = useState(() => Date.now());

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Record<string, unknown>>("POST", "/v1/card-disputes", body),
    onSuccess: (d) => {
      qc.invalidateQueries({ queryKey: ["card-disputes"] });
      if (d?.id) router.push(`/cards/disputes/${String(d.id)}`);
    },
  });

  return (
    <>
      <PageHeader title="Card disputes" subtitle="Chargeback queue: inquiry → chargeback → representment → arbitration"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New dispute"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={cardDisputeFormSections} submitLabel="Raise dispute"
            pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: DISPUTE_STATUSES },
          { name: "stage", label: "Stage", options: DISPUTE_STAGES },
          { name: "category", label: "Category", options: DISPUTE_CATEGORIES },
          { name: "scheme", label: "Scheme", options: SCHEMES },
          { name: "assigned_to", label: "Assignee" },
          { name: "card_id", label: "Card ID" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/cards/disputes/${r.id}`)} empty="No disputes match."
            columns={[
              { key: "created_at", header: "Opened", render: dateCell("created_at") },
              { key: "category", header: "Category" },
              { key: "scheme", header: "Scheme" },
              { key: "disputed_amount", header: "Amount", align: "right", render: amountCell("disputed_amount") },
              { key: "stage", header: "Stage", render: (r) => String(r.stage ?? "—").replaceAll("_", " ") },
              { key: "stage_deadline", header: "Deadline", render: (r) => <Deadline value={r.stage_deadline} now={now} /> },
              { key: "assigned_to", header: "Assignee" },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Disputes /></Suspense>;
}
