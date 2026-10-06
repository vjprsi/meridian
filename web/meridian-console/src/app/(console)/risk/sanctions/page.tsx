"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, statusCell, usePaged } from "@/components/ops";

const STATUSES = ["PENDING", "CLEAR", "POTENTIAL_MATCH", "CONFIRMED_MATCH", "FALSE_POSITIVE", "ERROR"];
const SUBJECTS = ["CUSTOMER", "RELATED_PARTY", "PAYMENT", "PAYEE", "MERCHANT", "AGENT", "EMPLOYEE"];

function SanctionsHits() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "POTENTIAL_MATCH" });
  const list = usePaged("screening-results", "/v1/risk/sanctions/results", filters);

  return (
    <>
      <PageHeader title="Sanctions hits"
        subtitle="Screening results against sanctions, PEP and adverse-media lists; potential matches need a compliance decision" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "subject_type", label: "Subject", options: SUBJECTS },
          { name: "is_pep", label: "PEP", options: ["true", "false"] },
          { name: "subject_id", label: "Subject ID", placeholder: "uuid" },
          { name: "reviewed_by", label: "Reviewed by" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/sanctions/${r.id}`)} empty="No screening results match."
            columns={[
              { key: "created_at", header: "Screened", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "subject_type", header: "Subject", render: (r) => String(r.subject_type ?? "—").replaceAll("_", " ") },
              { key: "subject_id", header: "Subject ID", render: (r) => <Mono>{String(r.subject_id ?? "—").slice(0, 8)}</Mono> },
              { key: "highest_score", header: "Top score", align: "right" },
              { key: "match_count", header: "Matches", align: "right" },
              { key: "flags", header: "Flags", render: (r) => (
                <span className="flex gap-1">
                  {r.is_pep ? <Badge value="PEP" tone="warn" /> : null}
                  {r.has_adverse_media ? <Badge value="ADVERSE MEDIA" tone="warn" /> : null}
                  {!r.is_pep && !r.has_adverse_media ? <span className="text-muted">—</span> : null}
                </span>) },
              { key: "reviewed_by", header: "Reviewed by" },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><SanctionsHits /></Suspense>;
}
