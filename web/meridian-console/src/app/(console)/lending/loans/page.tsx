"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, usePaged } from "@/components/ops";
import { BUCKETS, Bucket, ECL_STAGES, LOAN_STATUSES, LOAN_TYPES, money } from "../_components/common";

function Loans() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("loans", "/v1/loans", filters);

  return (
    <>
      <PageHeader title="Loans" subtitle="Booked loans: balances, arrears and IFRS 9 stage" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: LOAN_STATUSES },
          { name: "loan_type", label: "Loan type", options: LOAN_TYPES },
          { name: "delinquency_bucket", label: "DPD bucket", options: BUCKETS },
          { name: "ecl_stage", label: "ECL stage", options: ECL_STAGES },
          { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/lending/loans/${r.id}`)} empty="No loans match."
            columns={[
              { key: "loan_number", header: "Number", render: (r) => <Mono>{String(r.loan_number ?? r.id)}</Mono> },
              { key: "loan_type", header: "Type" },
              { key: "principal_amount", header: "Principal", align: "right", render: (r) => money(r, "principal_amount") },
              { key: "total_outstanding", header: "Outstanding", align: "right", render: (r) => money(r, "total_outstanding") },
              { key: "overdue_amount", header: "Overdue", align: "right", render: (r) => money(r, "overdue_amount") },
              { key: "days_past_due", header: "DPD", align: "right" },
              { key: "delinquency_bucket", header: "Bucket", render: (r) => <Bucket value={r.delinquency_bucket} /> },
              { key: "ecl_stage", header: "Stage" },
              { key: "next_due_date", header: "Next due" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Loans /></Suspense>;
}
