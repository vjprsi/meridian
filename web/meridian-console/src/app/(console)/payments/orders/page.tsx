"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";
import { EntityForm } from "@/components/entity-form";
import { paymentOrderCreate, paymentOrderFormSections } from "@/lib/entities/payment";

// WE-ETM-1 (G-08): Order & Pay (ETM) — order/payment/fulfilment segregation + ambiguous settlement + R/Y/G alerts.
function Orders() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("payment-orders", "/v1/payment-orders", filters);
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<{ id: string }>("POST", paymentOrderCreate.path, body),
    onSuccess: (o) => { qc.invalidateQueries({ queryKey: ["payment-orders"] }); router.push(`/payments/orders/${o.id}`); },
  });

  return (
    <>
      <PageHeader title="Order & Pay (ETM)"
        subtitle="Order / payment / fulfilment states and ambiguous-case settlement health"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : paymentOrderCreate.label}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={paymentOrderFormSections} submitLabel={paymentOrderCreate.submitLabel}
            initial={paymentOrderCreate.initial} pending={create.isPending} error={create.error}
            onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "order_status", label: "Order" },
          { name: "fulfilment_status", label: "Fulfilment" },
          { name: "alert_level", label: "Alert", options: ["GREEN", "YELLOW", "RED"] },
          { name: "reference", label: "Reference" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/payments/orders/${r.id}`)}
            empty="No orders."
            columns={[
              { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "order_status", header: "Order" },
              { key: "payment_status", header: "Payment" },
              { key: "fulfilment_status", header: "Fulfilment" },
              { key: "alert_level", header: "Alert", render: (r) => <Badge value={str(r.alert_level)} /> },
              { key: "created_at", header: "Created", render: dateCell("created_at") },
              { key: "order_status", header: "Status", render: (r) => <Badge value={str(r.order_status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Orders /></Suspense>;
}
