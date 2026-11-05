"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, dateCell, statusCell, usePaged, type Row } from "@/components/ops";
import { cardProgramFormSections } from "@/lib/entities/card";
import { FUNDING_MODELS, PROGRAM_STATUSES, PROGRAM_TYPES, SCHEMES } from "../shared";

function Programs() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("card-programs", "/v1/card-programs", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Row>("POST", "/v1/card-programs", body),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ["card-programs"] });
      if (p?.id) router.push(`/cards/programs/${String(p.id)}`);
    },
  });

  return (
    <>
      <PageHeader title="Card programs" subtitle="BIN ranges, scheme, funding model and risk settings per issuing program"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New program"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={cardProgramFormSections} submitLabel="Create program"
            initial={{
              scheme: "VISA", funding_model: "DEBIT", program_type: "CONSUMER", pan_length: "16",
              jit_timeout_ms: "1500", stand_in_enabled: "true", fraud_policy: "FAIL_OPEN", fraud_timeout_ms: "80",
              fraud_decline_score: "800", fx_markup_rate: "0", auth_hold_days: "7", renewal_window_days: "60",
            }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
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
