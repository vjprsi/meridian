"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { EntityForm } from "@/components/entity-form";
import { Filters, Pager, dateCell, statusCell, usePaged, type Row } from "@/components/ops";
import { buildCardIssueBody, cardIssueFormSections } from "@/lib/entities/card";
import { CARD_STATUSES, FORM_FACTORS, expiry, masked } from "./shared";

function Cards() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const list = usePaged("cards", "/v1/cards", filters);

  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => call<Row>("POST", "/v1/cards", buildCardIssueBody(body)),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["cards"] });
      if (c?.id) router.push(`/cards/${String(c.id)}`);
    },
  });

  return (
    <>
      <PageHeader title="Cards" subtitle="Issued debit, prepaid and credit cards. Card numbers are always masked."
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "Issue card"}</Button>} />
      {open && (
        <div className="mb-8">
          <EntityForm sections={cardIssueFormSections} submitLabel="Issue card"
            initial={{ form_factor: "VIRTUAL", linked_account_type: "DEPOSIT_ACCOUNT" }}
            pending={create.isPending} error={create.error} onSubmit={(body) => create.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: CARD_STATUSES },
          { name: "form_factor", label: "Form factor", options: FORM_FACTORS },
          { name: "last4", label: "Last 4", placeholder: "1234" },
          { name: "customer_id", label: "Customer ID" },
          { name: "card_program_id", label: "Program ID" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/cards/${r.id}`)}
            empty={Object.values(filters).some(Boolean) ? "No cards match." : "No cards issued yet."}
            columns={[
              { key: "pan", header: "Card", render: (r) => <span className="font-mono">{masked(r)}</span> },
              { key: "cardholder_name", header: "Cardholder", render: (r) => <span className="font-medium">{String(r.cardholder_name ?? "—")}</span> },
              { key: "form_factor", header: "Form" },
              { key: "expiry", header: "Expiry", render: (r) => <span className="num">{expiry(r)}</span> },
              { key: "linked_account_type", header: "Funding" },
              { key: "customer_id", header: "Customer", render: (r) => <Mono>{String(r.customer_id ?? "—").slice(0, 8)}</Mono> },
              { key: "created_at", header: "Issued", render: dateCell("created_at") },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Cards /></Suspense>;
}
