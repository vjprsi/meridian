"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, dateCell, statusCell, usePaged, type Row } from "@/components/ops";
import { FUNDING_MODELS, PROGRAM_STATUSES, PROGRAM_TYPES, SCHEMES, clean } from "../shared";

function Programs() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("card-programs", "/v1/card-programs", filters);

  return (
    <>
      <PageHeader title="Card programs" subtitle="BIN ranges, scheme, funding model and risk settings per issuing program"
        actions={
          <Action label="New program" path="/v1/card-programs" invalidate={[["card-programs"]]}
            description="Programs start as DRAFT, go through scheme certification and are activated by a second user."
            fields={[
              { name: "code", label: "Code", required: true },
              { name: "name", label: "Name", required: true },
              { name: "legal_entity_id", label: "Legal entity ID", required: true },
              { name: "scheme", label: "Scheme", options: SCHEMES, required: true },
              { name: "funding_model", label: "Funding model", options: FUNDING_MODELS, required: true },
              { name: "program_type", label: "Program type", options: PROGRAM_TYPES },
              { name: "bin", label: "BIN", required: true, placeholder: "6–8 digits" },
              { name: "bin_range_start", label: "BIN range start", required: true },
              { name: "bin_range_end", label: "BIN range end", required: true },
              { name: "currency", label: "Currency", required: true, placeholder: "USD" },
              { name: "country", label: "Country (ISO)" },
              { name: "sponsor_bank", label: "Sponsor bank" },
            ]}
            body={(v) => clean(v)}
            onDone={(r) => { const p = r as Row | undefined; if (p?.id) router.push(`/cards/programs/${String(p.id)}`); }} />
        } />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: PROGRAM_STATUSES },
          { name: "scheme", label: "Scheme", options: SCHEMES },
          { name: "funding_model", label: "Funding", options: FUNDING_MODELS },
          { name: "program_type", label: "Type", options: PROGRAM_TYPES },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/cards/programs/${r.id}`)} empty="No card programs."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{String(r.code)}</Mono> },
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{String(r.name)}</span> },
              { key: "scheme", header: "Scheme" },
              { key: "funding_model", header: "Funding" },
              { key: "program_type", header: "Type" },
              { key: "bin", header: "BIN", render: (r) => <Mono>{String(r.bin ?? "—")}</Mono> },
              { key: "currency", header: "Ccy" },
              { key: "created_at", header: "Created", render: dateCell("created_at") },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Programs /></Suspense>;
}
