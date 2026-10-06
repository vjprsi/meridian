"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, usePaged } from "@/components/ops";
import { APPLICATION_STATUSES, CHANNELS, LOAN_TYPES, money } from "../_components/common";

function Applications() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("loan-applications", "/v1/loan-applications", filters);

  return (
    <>
      <PageHeader title="Loan applications" subtitle="Origination pipeline: decisioning, offers, signature and booking" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: APPLICATION_STATUSES },
          { name: "loan_type", label: "Loan type", options: LOAN_TYPES },
          { name: "channel", label: "Channel", options: CHANNELS },
          { name: "assigned_to", label: "Underwriter", placeholder: "user id" },
          { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
          { name: "created_after", label: "Created after", type: "date" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/lending/applications/${r.id}`)}
            empty="No applications match."
            columns={[
              { key: "application_number", header: "Number", render: (r) => <Mono>{String(r.application_number ?? r.id)}</Mono> },
              { key: "loan_type", header: "Type" },
              { key: "purpose", header: "Purpose" },
              { key: "requested_amount", header: "Requested", align: "right", render: (r) => money(r, "requested_amount") },
              { key: "requested_term_months", header: "Term (m)", align: "right" },
              { key: "risk_grade", header: "Grade" },
              { key: "assigned_to", header: "Underwriter" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "created_at", header: "Created", render: (r) => <DateTime value={r.created_at as string} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Applications /></Suspense>;
}
