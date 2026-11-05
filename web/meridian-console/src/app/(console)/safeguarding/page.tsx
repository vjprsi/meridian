"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, str, usePaged } from "@/components/ops";

// WE-RECON-1 (G-10, D-ED-12): trust / e-money safeguarding EOD reconciliation A-(B+C+D+E).
const STATUSES = ["BALANCED", "TIMING", "AMOUNT", "SHORTFALL"] as const;

function num(r: Record<string, unknown>, k: string) {
  const v = r[k];
  return v === null || v === undefined || v === "" ? "—" : String(v);
}

function Safeguarding() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("safeguarding-runs", "/v1/reconciliation/safeguarding-runs", filters);

  return (
    <>
      <PageHeader title="Safeguarding reconciliation"
        subtitle="Daily proof that the trust account fully backs outstanding e-money: A − (B+C+D+E) = 0" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "business_date", label: "Business date", placeholder: "YYYY-MM-DD" },
          { name: "currency", label: "Currency", placeholder: "KES" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/safeguarding/${r.id}`)}
            empty="No safeguarding runs yet."
            columns={[
              { key: "business_date", header: "As-of", render: (r) => <Mono>{str(r.business_date)}</Mono> },
              { key: "trust_balance", header: "Trust (A)", align: "right", render: (r) => <Mono>{num(r, "trust_balance")}</Mono> },
              { key: "liability_total", header: "Liabilities (B+C+D+E)", align: "right", render: (r) => <Mono>{num(r, "liability_total")}</Mono> },
              { key: "difference", header: "A−(B+…+E)", align: "right", render: (r) => <Mono>{num(r, "difference")}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "backed", header: "Backed", render: (r) => <Badge value={r.backed ? "BACKED" : "UNBACKED"} /> },
              { key: "alarm_raised", header: "Alarm", render: (r) => r.alarm_raised ? <Badge value="SHORTFALL" /> : <span className="text-muted">—</span> },
              { key: "status", header: "Status", render: (r) => <Badge value={str(r.status)} /> },
              { key: "created_at", header: "Run at", render: dateCell("created_at") },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Safeguarding /></Suspense>;
}
