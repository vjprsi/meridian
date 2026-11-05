"use client";

import { useParams, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Pager, Timeline, amountCell, dateCell, usePaged, type ActionField, type Row } from "@/components/ops";
import { DetailSections } from "@/components/detail";
import { treasuryDealDetailSections } from "@/lib/entities/treasury";

type Kind = "mm" | "fx" | "sec";

const BASE: Record<Kind, string> = {
  mm: "/v1/treasury/money-market-deals",
  fx: "/v1/treasury/fx-deals",
  sec: "/v1/treasury/securities-trades",
};
const DEAL_TYPE: Record<Kind, string> = { mm: "MONEY_MARKET", fx: "FX", sec: "SECURITY" };
const LABEL: Record<Kind, string> = { mm: "Money market deal", fx: "FX deal", sec: "Securities trade" };

const REASON: ActionField[] = [{ name: "reason", label: "Reason", required: true, type: "textarea" }];

interface Act { label: string; action: string; fields?: ActionField[]; variant?: "primary" | "secondary" | "danger";
  description?: string; body?: Record<string, unknown> }

/** Lifecycle actions offered per deal family and status (the service enforces the state machine and four-eyes). */
function actionsFor(kind: Kind, d: Row): Act[] {
  const s = String(d.status);
  const cancel: Act = { label: "Cancel deal", action: "cancel", fields: REASON, variant: "danger",
    description: "Cancels the deal before settlement and releases its counterparty limit reservations." };
  const verify: Act = { label: "Verify", action: "verify", description: "Back-office verification. You cannot verify a deal you captured." };
  const confirm: Act = { label: "Confirm", action: "confirm", variant: "secondary",
    description: "Mark confirmed. Without a matched inbound confirmation a manual reference is required.",
    fields: [{ name: "manual_reference", label: "Manual confirmation reference" }] };
  if (kind === "sec") {
    return s === "PENDING" ? [{ label: "Settle", action: "settle", description: "Settle the trade, update the holding and post to the ledger." }, cancel] : [];
  }
  if (kind === "mm") {
    switch (s) {
      case "CAPTURED": return [verify, cancel];
      case "VERIFIED": return [confirm, cancel];
      case "CONFIRMED": return [{ label: "Settle start leg", action: "settle", description: "Generate settlement instructions and post the start leg to the ledger." }, cancel];
      case "SETTLED": return [
        { label: "Mature", action: "mature", description: "Process maturity: repay principal and interest." },
        { label: "Roll over", action: "rollover", variant: "secondary", fields: [
          { name: "new_rate", label: "New rate (%)", required: true, type: "number" },
          { name: "new_maturity_date", label: "New maturity date", type: "date" },
          { name: "rollover_type", label: "Rollover type", required: true, options: ["PRINCIPAL_ONLY", "PRINCIPAL_AND_INTEREST", "NONE"] },
        ] },
        ...(d.is_call ? [{ label: "Call deal", action: "call", variant: "secondary" as const,
          fields: [{ name: "call_date", label: "Call date", required: true, type: "date" as const }] }] : []),
        { label: "Accrue interest", action: "accrue", variant: "secondary",
          fields: [{ name: "as_of", label: "Accrue through", required: true, type: "date" }] },
      ];
      default: return [];
    }
  }
  const revalue: Act = { label: "Revalue (MTM)", action: "revalue", variant: "secondary", fields: [
    { name: "valuation_date", label: "Valuation date", required: true, type: "date" },
    { name: "market_rate", label: "Market rate", type: "number", hint: "Defaults to the reference-data REVALUATION rate" },
  ] };
  const settle: Act = { label: "Settle leg", action: "settle", fields: [{ name: "leg_type", label: "Leg", required: true, options: ["NEAR", "FAR"] }] };
  const fix: Act = { label: "Record NDF fixing", action: "fix", variant: "secondary",
    fields: [{ name: "fixing_rate", label: "Fixing rate", required: true, type: "number" }] };
  switch (s) {
    case "CAPTURED": return [verify, cancel];
    case "VERIFIED": return [confirm, cancel];
    case "CONFIRMED": return [settle, ...(d.non_deliverable ? [fix] : []), revalue, cancel];
    case "SETTLED": return [settle, revalue];
    default: return [];
  }
}

function Settlements({ dealId, kind }: { dealId: string; kind: Kind }) {
  const list = usePaged("treasury-deals", "/v1/treasury/settlements", { deal_id: dealId, deal_type: DEAL_TYPE[kind] });
  const inv = [["treasury-deals"]];
  return (
    <Card title="Settlements" className="mb-6">
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No settlement flows yet."
          columns={[
            { key: "flow_type", header: "Flow" },
            { key: "direction", header: "Dir" },
            { key: "amount", header: "Amount", align: "right", render: amountCell() },
            { key: "value_date", header: "Value date" },
            { key: "settlement_reference", header: "Reference", render: (r) => String(r.settlement_reference ?? r.failure_reason ?? "—") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => (
              <div className="flex justify-end gap-1">
                {r.status === "PENDING" && <Action size="sm" label="Instruct" path={`/v1/treasury/settlements/${r.id}/instruct`}
                  description="Release the settlement as a payment (pay flows) or await receipt (receive flows)." invalidate={inv} />}
                {(r.status === "PENDING" || r.status === "INSTRUCTED") && <>
                  <Action size="sm" variant="secondary" label="Mark settled" path={`/v1/treasury/settlements/${r.id}/mark-settled`}
                    fields={[{ name: "reference", label: "Nostro statement reference" }]} invalidate={inv} />
                  <Action size="sm" variant="danger" label="Fail" path={`/v1/treasury/settlements/${r.id}/fail`}
                    fields={REASON} invalidate={inv} />
                </>}
              </div>
            ) },
          ]} />
      )}
      <Pager p={list} />
    </Card>
  );
}

function Confirmations({ dealId, kind }: { dealId: string; kind: Kind }) {
  const list = usePaged("treasury-deals", "/v1/treasury/confirmations", { deal_id: dealId, deal_type: DEAL_TYPE[kind] });
  const [err, setErr] = useState<unknown>(null);
  const inv = [["treasury-deals"]];
  return (
    <Card title="Confirmations" className="mb-6" actions={kind !== "sec" && (
      <Action size="sm" variant="secondary" label="Generate & send" title="Generate outbound confirmation"
        path="/v1/treasury/confirmations/generate" body={{ deal_type: DEAL_TYPE[kind], deal_id: dealId }}
        fields={[{ name: "channel", label: "Channel", options: ["SWIFT", "EMAIL", "PLATFORM", "MANUAL"] }]}
        description={`Generates our ${kind === "mm" ? "MT320" : "MT300"} confirmation for this deal.`} invalidate={inv} />
    )}>
      <ErrorBanner error={list.error ?? err} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No confirmations."
          columns={[
            { key: "direction", header: "Dir" },
            { key: "channel", header: "Channel" },
            { key: "our_reference", header: "Our ref", render: (r) => <Mono>{String(r.our_reference ?? "—")}</Mono> },
            { key: "their_reference", header: "Their ref" },
            { key: "sent_at", header: "Sent", render: dateCell("sent_at") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => (
              <div className="flex justify-end gap-1">
                <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => {
                  setErr(null);
                  download(`/v1/treasury/confirmations/${r.id}/message`, `confirmation-${String(r.our_reference ?? r.id)}.txt`).catch(setErr);
                }}>Message</Button>
                {(r.status === "RECEIVED" || r.status === "UNMATCHED") && (
                  <Action size="sm" label="Match" path={`/v1/treasury/confirmations/${r.id}/match`} body={{ deal_id: dealId }}
                    description="Match this inbound confirmation to the deal; a MATCHED confirmation confirms a verified deal." invalidate={inv} />
                )}
                {!["DISPUTED", "CANCELLED", "MATCHED"].includes(String(r.status)) && (
                  <Action size="sm" variant="danger" label="Dispute" path={`/v1/treasury/confirmations/${r.id}/dispute`}
                    fields={REASON} invalidate={inv} />
                )}
              </div>
            ) },
          ]} />
      )}
      <Pager p={list} />
    </Card>
  );
}

function DealDetail() {
  const { id } = useParams<{ id: string }>();
  const params = useSearchParams();
  const kind = (["mm", "fx", "sec"].includes(params.get("kind") ?? "") ? params.get("kind") : "mm") as Kind;
  const [err, setErr] = useState<unknown>(null);
  const deal = useQuery({ queryKey: ["treasury-deals", "detail", kind, id], queryFn: () => call<Row>("GET", `${BASE[kind]}/${id}`) });
  const d = deal.data;
  if (deal.isLoading) return <Loading />;
  const acts = d ? actionsFor(kind, d) : [];
  const legs = (d?.legs as Row[] | undefined) ?? [];
  return (
    <>
      <PageHeader title={d ? String(d.deal_ref ?? LABEL[kind]) : LABEL[kind]}
        subtitle={d && <>{LABEL[kind]} · <Mono>{String(d.id)}</Mono> · <Badge value={String(d.status)} /></>}
        actions={d && kind !== "sec" && (
          <Button variant="secondary" onClick={() => {
            setErr(null);
            download(`${BASE[kind]}/${id}/confirmation-message`, `${String(d.deal_ref ?? id)}-${kind === "mm" ? "MT320" : "MT300"}.txt`).catch(setErr);
          }}>{kind === "mm" ? "MT320" : "MT300"} text</Button>
        )} />
      <ErrorBanner error={deal.error ?? err} />
      {d && acts.length > 0 && (
        <Card title="Lifecycle" className="mb-6">
          <div className="flex flex-wrap gap-2">
            {acts.map((a) => (
              <Action key={a.action} label={a.label} variant={a.variant} path={`${BASE[kind]}/${id}/${a.action}`}
                fields={a.fields} body={a.body} description={a.description} invalidate={[["treasury-deals"]]} />
            ))}
          </div>
        </Card>
      )}
      {d && <DetailSections className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-2" sections={treasuryDealDetailSections(d, kind)} />}
      {d && (
        <Card title="Timeline" className="mb-6">
          <Timeline items={[
            { at: d.created_at, title: "Captured", detail: String(d.created_by ?? "") },
            ...(d.verified_at ? [{ at: d.verified_at, title: "Verified", detail: String(d.verified_by ?? "") }] : []),
            ...(d.confirmed_at ? [{ at: d.confirmed_at, title: "Confirmed" }] : []),
            ...(d.settled_at ? [{ at: d.settled_at, title: "Settled" }] : []),
            ...(d.matured_at ? [{ at: d.matured_at, title: "Matured" }] : []),
            ...(d.status === "CANCELLED" ? [{ at: d.updated_at, title: "Cancelled", detail: String(d.cancel_reason ?? "") }] : []),
          ]} />
        </Card>
      )}
      {kind === "fx" && d && (
        <Card title="Legs" className="mb-6">
          <Table rows={legs} empty="No legs."
            columns={[
              { key: "leg_type", header: "Leg" },
              { key: "buy_sell", header: "Side" },
              { key: "base_amount", header: "Base", align: "right", render: amountCell("base_amount", "base_currency") },
              { key: "quote_amount", header: "Quote", align: "right", render: amountCell("quote_amount", "quote_currency") },
              { key: "deal_rate", header: "Rate", align: "right" },
              { key: "forward_points", header: "Fwd pts", align: "right" },
              { key: "value_date", header: "Value date" },
              { key: "settlement_status", header: "Settlement", render: (r) => <Badge value={String(r.settlement_status)} /> },
            ]} />
        </Card>
      )}
      {d && <Settlements dealId={id} kind={kind} />}
      {d && <Confirmations dealId={id} kind={kind} />}
    </>
  );
}

export default function Page() {
  return <Suspense><DealDetail /></Suspense>;
}
