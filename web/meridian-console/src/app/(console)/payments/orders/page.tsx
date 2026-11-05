"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-ETM-1 (G-08): Order & Pay (ETM) — order/payment/fulfilment segregation + ambiguous settlement + R/Y/G alerts.
function Orders() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("payment-orders", "/v1/payment-orders", filters);

  return (
    <>
      <PageHeader title="Order & Pay (ETM)"
        subtitle="Order / payment / fulfilment states and ambiguous-case settlement health" />
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
