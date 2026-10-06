"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

const WALLET_TYPES = ["CUSTOMER", "MERCHANT", "AGENT", "SUPER_AGENT", "FLOAT", "COMMISSION", "ESCROW", "G2P", "TREASURY"] as const;
const WALLET_STATUSES = ["PENDING", "ACTIVE", "DORMANT", "SUSPENDED", "BLOCKED", "CLOSED"] as const;

function Wallets() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("wallets", "/v1/wallets", filters);

  return (
    <>
      <PageHeader title="Wallets" subtitle="Mobile-money wallets of customers, merchants and agents" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: WALLET_STATUSES },
          { name: "wallet_type", label: "Type", options: WALLET_TYPES },
          { name: "msisdn", label: "MSISDN", placeholder: "+254…" },
          { name: "wallet_number", label: "Wallet number" },
          { name: "currency", label: "Currency", placeholder: "KES" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/${r.id}`)}
            empty="No wallets match."
            columns={[
              { key: "wallet_number", header: "Number", render: (r) => <Mono>{str(r.wallet_number)}</Mono> },
              { key: "display_name", header: "Name", render: (r) => <span className="font-medium">{str(r.display_name)}</span> },
              { key: "wallet_type", header: "Type" },
              { key: "msisdn", header: "MSISDN" },
              { key: "currency", header: "Currency" },
              { key: "kyc_tier", header: "KYC tier", align: "right" },
              { key: "last_activity_at", header: "Last activity", render: dateCell("last_activity_at") },
              { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Wallets /></Suspense>;
}
