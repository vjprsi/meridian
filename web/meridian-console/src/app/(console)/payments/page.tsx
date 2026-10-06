"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, usePaged, type FilterSpec } from "@/components/ops";
import { PAYMENT_STATUSES, PAYMENT_TYPES, DIRECTIONS, CHANNELS } from "./shared";

const FILTERS: FilterSpec[] = [
  { name: "status", label: "Status", options: PAYMENT_STATUSES },
  { name: "payment_type", label: "Type", options: PAYMENT_TYPES },
  { name: "direction", label: "Direction", options: DIRECTIONS },
  { name: "channel", label: "Channel", options: CHANNELS },
  { name: "created_after", label: "Created from", type: "date" },
  { name: "created_before", label: "Created to", type: "date" },
  { name: "end_to_end_id", label: "End-to-end ID", placeholder: "E2E reference" },
  { name: "customer_id", label: "Customer ID", placeholder: "UUID" },
];

/** Date filters are entered as days; the API takes instants (the "to" day is inclusive). */
function toApi(f: Record<string, string>): Record<string, string> {
  const out = { ...f };
  if (f.created_after) out.created_after = new Date(`${f.created_after}T00:00:00`).toISOString();
  if (f.created_before) {
    const d = new Date(`${f.created_before}T00:00:00`);
    d.setDate(d.getDate() + 1);
    out.created_before = d.toISOString();
  }
  return out;
}

function Payments() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("payments", "/v1/payments", toApi(filters));

  return (
    <>
      <PageHeader title="Payments" subtitle="Every payment on every rail, newest first"
        actions={<Button onClick={() => router.push("/payments/new")}>New payment</Button>} />
      <Card>
        <Filters specs={FILTERS} value={filters} onChange={setFilters} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/payments/${r.id}`)}
            empty={Object.values(filters).some(Boolean) ? "No payments match these filters." : "No payments yet."}
            columns={[
              { key: "created_at", header: "Created", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "end_to_end_id", header: "Reference", render: (r) => <Mono>{String(r.end_to_end_id ?? r.id)}</Mono> },
              { key: "payment_type", header: "Type", render: (r) => String(r.payment_type ?? "—").replaceAll("_", " ") },
              { key: "direction", header: "Direction" },
              { key: "creditor_name", header: "Counterparty", render: (r) =>
                String((r.direction === "INBOUND" ? r.debtor_name : r.creditor_name) ?? "—") },
              { key: "rail_code", header: "Rail", render: (r) => String(r.rail_code ?? "—") },
              { key: "amount", header: "Amount", align: "right", render: (r) =>
                <Money amount={r.amount as string} currency={r.currency as string} /> },
              { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Payments /></Suspense>;
}
