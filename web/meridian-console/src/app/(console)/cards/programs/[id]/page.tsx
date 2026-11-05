"use client";

import { useParams, useRouter } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Pager, Json, dateCell, rows, statusCell, usePaged, type Row } from "@/components/ops";
import { DetailSections } from "@/components/detail";
import { cardProgramDetailSections } from "@/lib/entities/card";
import { expiry, masked } from "../../shared";

function ProgramDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const program = useQuery({ queryKey: ["card-program", id], queryFn: () => call<Row>("GET", `/v1/card-programs/${id}`) });
  const products = useQuery({
    queryKey: ["card-products", id],
    queryFn: () => call<unknown>("GET", `/v1/card-products?card_program_id=${id}&limit=100`),
  });
  const cards = usePaged("cards", `/v1/card-programs/${id}/cards`);
  const p = program.data;
  const status = String(p?.status ?? "");
  const inv = [["card-program", id], ["card-programs"]];

  if (program.isLoading) return <Loading />;
  return (
    <>
      <PageHeader title={p ? String(p.name) : "Card program"}
        subtitle={p && <><Mono>{String(p.code)}</Mono> · {String(p.scheme)} {String(p.funding_model)} · <Badge value={status} />
          {p.status_reason ? <> · {String(p.status_reason)}</> : null}</>}
        actions={p && <>
          {status === "DRAFT" && (
            <Action label="Start certification" path={`/v1/card-programs/${id}/certify`} invalidate={inv}
              description="Moves the program into scheme certification." />
          )}
          {["CERTIFICATION", "SUSPENDED"].includes(status) && (
            <Action label={status === "SUSPENDED" ? "Reactivate" : "Approve go-live"} path={`/v1/card-programs/${id}/activate`}
              invalidate={inv} description="Four-eyes: the program's creator cannot activate it."
              fields={[{ name: "reason", label: "Reason" }]} />
          )}
          {status === "ACTIVE" && (
            <Action label="Suspend" variant="danger" path={`/v1/card-programs/${id}/suspend`} invalidate={inv}
              description="Suspends all authorisations on every card in this program."
              fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
          )}
        </>} />
      <ErrorBanner error={program.error} />
      {p && (
        <>
          <DetailSections sections={cardProgramDetailSections(p)} className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-2" />
          {Array.isArray(p.default_controls) && p.default_controls.length > 0 && (
            <Card title="Default controls" className="mb-6"><Json value={p.default_controls} /></Card>
          )}
        </>
      )}
      <Card title="Card products" className="mb-6">
        <ErrorBanner error={products.error} />
        {products.isLoading ? <Loading /> : (
          <Table rows={rows(products.data)} empty="No products on this program."
            columns={[
              { key: "code", header: "Code", render: (r) => <Mono>{String(r.code)}</Mono> },
              { key: "name", header: "Name" },
              { key: "form_factors", header: "Form factors", render: (r) => Array.isArray(r.form_factors) ? (r.form_factors as string[]).join(", ") : "—" },
              { key: "pin_policy", header: "PIN" },
              { key: "flags", header: "Enabled", render: (r) => ["contactless", "atm", "ecom", "international"]
                .filter((f) => r[`${f}_enabled`]).join(", ") || "—" },
              { key: "status", header: "Status", render: statusCell() },
              { key: "actions", header: "", render: (r) => (
                <div className="flex justify-end">
                  {r.status === "DRAFT" && (
                    <Action size="sm" label="Activate" path={`/v1/card-products/${String(r.id)}/activate`}
                      invalidate={[["card-products", id]]} description="Make this product available for issuing." />
                  )}
                  {r.status === "ACTIVE" && (
                    <Action size="sm" variant="danger" label="Retire" path={`/v1/card-products/${String(r.id)}/retire`}
                      invalidate={[["card-products", id]]} description="Stops issuing new cards on this product; existing cards are unaffected."
                      fields={[{ name: "reason", label: "Reason" }]} />
                  )}
                </div>
              ) },
            ]} />
        )}
      </Card>
      <Card title="Cards issued">
        <ErrorBanner error={cards.error} />
        {cards.isLoading ? <Loading /> : (
          <Table rows={cards.rows} onRow={(r) => router.push(`/cards/${r.id}`)} empty="No cards issued on this program."
            columns={[
              { key: "pan", header: "Card", render: (r) => <span className="font-mono">{masked(r)}</span> },
              { key: "cardholder_name", header: "Cardholder" },
              { key: "form_factor", header: "Form" },
              { key: "expiry", header: "Expiry", render: (r) => expiry(r) },
              { key: "created_at", header: "Issued", render: dateCell("created_at") },
              { key: "status", header: "Status", render: statusCell() },
            ]} />
        )}
        <Pager p={cards} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><ProgramDetail /></Suspense>;
}
