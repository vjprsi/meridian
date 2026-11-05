"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-TXN-1 (G-03): unregistered / voucher flows (value held in e-money suspense against a one-time passcode).
const STATUSES = ["ISSUED", "CLAIMED", "EXPIRED", "ROLLED_BACK", "REVERSED"] as const;

function Vouchers() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("wallet-vouchers", "/v1/wallet-vouchers", filters);

  return (
    <>
      <PageHeader title="Vouchers" subtitle="Unregistered transfer vouchers held in e-money suspense" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "voucher_type", label: "Type" },
          { name: "reference", label: "Reference" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/vouchers/${r.id}`)}
            empty="No vouchers."
            columns={[
              { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
              { key: "voucher_type", header: "Type" },
              { key: "amount", header: "Amount", align: "right", render: (r) => <Mono>{str(r.amount)}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "expires_at", header: "Expires", render: dateCell("expires_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Vouchers /></Suspense>;
}
