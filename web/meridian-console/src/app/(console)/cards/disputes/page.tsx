"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Card, ErrorBanner, Loading, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, amountCell, dateCell, statusCell, usePaged, type Row } from "@/components/ops";
import { DISPUTE_CATEGORIES, DISPUTE_STAGES, DISPUTE_STATUSES, SCHEMES, clean } from "../shared";

/** Highlights a stage deadline that is past or within three days. */
function Deadline({ value, now }: { value?: unknown; now: number }) {
  if (!value) return <span className="text-muted">—</span>;
  const days = Math.ceil((new Date(String(value)).getTime() - now) / 86_400_000);
  const cls = days < 0 ? "text-bad font-medium" : days <= 3 ? "text-warn font-medium" : "";
  return <span className={`num ${cls}`}>{String(value)}{days <= 3 && <span className="ml-1 text-xs">({days < 0 ? "overdue" : `${days}d`})</span>}</span>;
}

function Disputes() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "OPEN" });
  const list = usePaged("card-disputes", "/v1/card-disputes", filters);
  const [now] = useState(() => Date.now());

  return (
    <>
      <PageHeader title="Card disputes" subtitle="Chargeback queue: inquiry → chargeback → representment → arbitration"
        actions={
          <Action label="New dispute" path="/v1/card-disputes" invalidate={[["card-disputes"]]}
            fields={[
              { name: "card_id", label: "Card ID", required: true },
              { name: "card_authorization_id", label: "Authorisation ID" },
              { name: "category", label: "Category", options: DISPUTE_CATEGORIES, required: true },
              { name: "scheme", label: "Scheme", options: SCHEMES, required: true },
              { name: "disputed_amount", label: "Disputed amount", type: "number", required: true },
              { name: "currency", label: "Currency", required: true, placeholder: "USD" },
              { name: "transaction_date", label: "Transaction date", type: "date" },
              { name: "reason_code", label: "Reason code" },
              { name: "customer_description", label: "Customer description", type: "textarea" },
            ]}
            body={(v) => clean(v)}
            onDone={(r) => { const d = r as Row | undefined; if (d?.id) router.push(`/cards/disputes/${String(d.id)}`); }} />
        } />
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
