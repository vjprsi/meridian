"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-TXN-2 (G-07): agent passcode / ATM OTP / cardless auth-code cash-out authorisations.
const TYPES = ["AGENT_PASSCODE", "ATM_OTP", "AUTH_CODE"] as const;
const STATUSES = ["ISSUED", "REDEEMED", "EXPIRED", "ROLLED_BACK", "REVERSED"] as const;

function Cashout() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("wallet-cashout-authorisations", "/v1/wallet-cashout-authorisations", filters);

  return (
    <>
      <PageHeader title="Cash-out authorisations" subtitle="Agent passcode, cardless ATM OTP and pre-authorised auth codes" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "auth_type", label: "Type", options: TYPES },
          { name: "status", label: "Status", options: STATUSES },
          { name: "reference", label: "Reference" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/cashout/${r.id}`)}
            empty="No cash-out authorisations."
            columns={[
              { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
              { key: "auth_type", header: "Type" },
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
  return <Suspense><Cashout /></Suspense>;
}
