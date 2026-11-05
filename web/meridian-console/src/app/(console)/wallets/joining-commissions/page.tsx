"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, amountCell, str, usePaged } from "@/components/ops";

// WE-PRICE-1 (G-05): joining / registration commission plans (one-off bonus paid to a beneficiary role on
// registration, first cash-in or first debit). Maker-checker lifecycle like the rest of the hierarchy config.
const STATUSES = ["DRAFT", "PENDING_APPROVAL", "ACTIVE", "RETIRED", "REJECTED"] as const;
const BENEFICIARIES = ["AGENT", "SUPER_AGENT", "SUB_AGENT", "MERCHANT", "BILLER", "PLATFORM", "CUSTOMER",
  "AGGREGATOR", "SERVICE_PROVIDER"] as const;

function JoiningCommissions() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("joining-commissions", "/v1/joining-commissions", filters);

  return (
    <>
      <PageHeader title="Joining commissions" subtitle="One-off registration / first-activity commission plans"
        actions={
          <Action label="New joining commission" path="/v1/joining-commissions" invalidate={[["joining-commissions"]]}
            title="Create joining commission" description="Created as a draft; submit it for maker-checker approval."
            fields={[
              { name: "code", label: "Code", required: true },
              { name: "name", label: "Name", required: true },
              { name: "currency", label: "Currency", required: true, placeholder: "KES" },
              { name: "valid_from", label: "Valid from", type: "date", required: true },
            ]}
            onDone={(r) => { const id = (r as { id?: string })?.id; if (id) router.push(`/wallets/joining-commissions/${id}`); }} />
        } />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "beneficiary", label: "Beneficiary", options: BENEFICIARIES },
          { name: "code", label: "Code" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/wallets/joining-commissions/${r.id}`)}
            empty="No joining commissions."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{str(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "beneficiary", header: "Beneficiary", render: (r) => str(r.beneficiary).replaceAll("_", " ") },
              { key: "register_amount", header: "Register", align: "right", render: amountCell("register_amount") },
              { key: "first_cash_in_amount", header: "1st cash-in", align: "right", render: amountCell("first_cash_in_amount") },
              { key: "valid_from", header: "Valid from", render: (r) => str(r.valid_from) },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><JoiningCommissions /></Suspense>;
}
