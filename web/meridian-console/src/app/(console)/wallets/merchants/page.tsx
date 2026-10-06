"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

const TYPES = ["INDIVIDUAL", "SOLE_TRADER", "COMPANY", "GOVERNMENT", "NGO"] as const;
const STATUSES = ["ONBOARDING", "ACTIVE", "SUSPENDED", "TERMINATED"] as const;

function Merchants() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("merchants", "/v1/merchants", filters);

  return (
    <>
      <PageHeader title="Merchants" subtitle="Wallet-accepting merchants, their stores and settlements" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "merchant_type", label: "Type", options: TYPES },
          { name: "merchant_code", label: "Merchant code" },
          { name: "mcc", label: "MCC" },
          { name: "country", label: "Country" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/merchants/${r.id}`)} empty="No merchants match."
            columns={[
              { key: "merchant_code", header: "Code", render: (r) => <Mono>{str(r.merchant_code)}</Mono> },
              { key: "trading_name", header: "Trading name", render: (r) => <span className="font-medium">{str(r.trading_name)}</span> },
              { key: "merchant_type", header: "Type" },
              { key: "mcc", header: "MCC" },
              { key: "settlement_frequency", header: "Settlement" },
              { key: "onboarded_at", header: "Onboarded", render: dateCell("onboarded_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Merchants /></Suspense>;
}
