"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged, type FilterSpec } from "@/components/ops";
import { EntityForm } from "@/components/entity-form";
import { BULK_FILE_FORMAT, BULK_PAYMENT_STATUS, bulkPaymentCreate, bulkPaymentFormSections } from "@/lib/entities/payment";

const FILTERS: FilterSpec[] = [
  { name: "status", label: "Status", options: BULK_PAYMENT_STATUS },
  { name: "file_format", label: "Format", options: BULK_FILE_FORMAT },
  { name: "customer_id", label: "Customer ID", placeholder: "UUID" },
  { name: "debtor_account_id", label: "Debtor account", placeholder: "UUID" },
  { name: "requested_execution_date", label: "Execution date", type: "date" },
];

// PAY-01: bulk (batch) payments — payroll / supplier runs created via the API with item lines, four-eyes release.
function BulkPayments() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("bulk-payments", "/v1/bulk-payments", filters);
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<{ id: string }>("POST", bulkPaymentCreate.path, body),
    onSuccess: (b) => { qc.invalidateQueries({ queryKey: ["bulk-payments"] }); router.push(`/payments/bulk/${b.id}`); },
  });

  return (
    <>
      <PageHeader title="Bulk payments" subtitle="Payroll and supplier batches — item lines, four-eyes release"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : bulkPaymentCreate.label}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={bulkPaymentFormSections} submitLabel={bulkPaymentCreate.submitLabel}
            initial={bulkPaymentCreate.initial} pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters specs={FILTERS} value={filters} onChange={setFilters} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/payments/bulk/${r.id}`)}
            empty={Object.values(filters).some(Boolean) ? "No batches match these filters." : "No bulk payments yet."}
            columns={[
              { key: "name", header: "Batch", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "file_format", header: "Format", render: (r) => str(r.file_format).replaceAll("_", " ") },
              { key: "item_count", header: "Items", align: "right", render: (r) => str(r.item_count) },
              { key: "total_amount", header: "Total", align: "right", render: (r) =>
                <Money amount={r.total_amount as string} currency={r.currency as string} /> },
              { key: "customer_id", header: "Customer", render: (r) => <Mono>{str(r.customer_id)}</Mono> },
              { key: "requested_execution_date", header: "Execution", render: dateCell("requested_execution_date") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><BulkPayments /></Suspense>;
}
