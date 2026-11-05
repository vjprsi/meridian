"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { Action, Details, Timeline, dateCell, rows, type Row } from "@/components/ops";
import { cardDisputeDetailSections } from "@/lib/entities/dispute";
import { DISPUTE_OUTCOMES, DISPUTE_STAGES, EVIDENCE_TYPES, clean } from "../../shared";

const CLOSED = ["WON", "LOST", "WITHDRAWN", "EXPIRED"];
const ESCALATION_STAGES = DISPUTE_STAGES.filter((s) => s === "PRE_ARBITRATION" || s === "ARBITRATION");

function DisputeDetail() {
  const { id } = useParams<{ id: string }>();
  const dispute = useQuery({ queryKey: ["card-dispute", id], queryFn: () => call<Row>("GET", `/v1/card-disputes/${id}`) });
  const events = useQuery({
    queryKey: ["card-dispute-events", id],
    queryFn: () => call<unknown>("GET", `/v1/card-disputes/${id}/events?limit=100`),
  });
  const evidence = useQuery({
    queryKey: ["card-dispute-evidence", id],
    queryFn: () => call<unknown>("GET", `/v1/card-dispute-evidence?card_dispute_id=${id}&limit=100`),
  });
  const d = dispute.data;
  const inv = [["card-dispute", id], ["card-dispute-events", id], ["card-disputes"]];

  if (dispute.isLoading) return <Loading />;
  const status = String(d?.status ?? "");
  const stage = String(d?.stage ?? "");
  const open = d && !CLOSED.includes(status) && stage !== "CLOSED";
  const ccy = String(d?.currency ?? "");
  const pc = Number(d?.provisional_credit_amount ?? 0);

  return (
    <>
      <PageHeader title={d ? `${String(d.category).replaceAll("_", " ")} dispute` : "Dispute"}
        subtitle={d && <>{String(d.scheme)} · stage {stage.replaceAll("_", " ")} · <Badge value={status} />
          {d.assigned_to ? <> · assigned to {String(d.assigned_to)}</> : null}</>}
        actions={open && <>
          <Action label="Assign" variant="secondary" path={`/v1/card-disputes/${id}/assign`} invalidate={inv}
            fields={[{ name: "assigned_to", label: "Analyst", required: true, initial: String(d.assigned_to ?? "") }]} />
          <Action label="AI summary" variant="secondary" path={`/v1/card-disputes/${id}/ai-summary`} invalidate={inv}
            description="Generates a copilot summary and a suggested reason code." />
        </>} />
      <ErrorBanner error={dispute.error} />
      {d && (
        <>
          <div className="mb-6">
            <DetailSections sections={cardDisputeDetailSections(d as Record<string, unknown>)} />
          </div>
          {open && (
            <Card title="Next steps" className="mb-6">
              <div className="flex flex-wrap gap-2">
                {pc === 0 && (
                  <Action label="Provisional credit" path={`/v1/card-disputes/${id}/provisional-credit`} invalidate={inv}
                    description="Credits the cardholder while the dispute is investigated."
                    fields={[{ name: "amount", label: `Amount (${ccy})`, type: "number", required: true, initial: String(d.disputed_amount ?? "") }]} />
                )}
                {["INQUIRY"].includes(stage) && (
                  <Action label="Request retrieval" variant="secondary" path={`/v1/card-disputes/${id}/request-retrieval`}
                    invalidate={inv} description="Requests a copy of the transaction receipt from the acquirer."
                    fields={[{ name: "note", label: "Note", type: "textarea" }]} />
                )}
                {["INQUIRY", "RETRIEVAL"].includes(stage) && (
                  <Action label="Submit chargeback" path={`/v1/card-disputes/${id}/submit-chargeback`} invalidate={inv}
                    description="Submits the chargeback to the scheme; attached evidence is marked as submitted."
                    fields={[
                      { name: "reason_code", label: "Reason code", required: true, initial: String(d.reason_code ?? d.suggested_reason_code ?? "") },
                      { name: "amount", label: `Amount (${ccy})`, type: "number", required: true, initial: String(d.disputed_amount ?? "") },
                    ]} />
                )}
                {stage === "FIRST_CHARGEBACK" && (
                  <Action label="Record representment" variant="secondary" path={`/v1/card-disputes/${id}/record-representment`}
                    invalidate={inv} description="Record the merchant's representment received through the scheme."
                    fields={[
                      { name: "amount", label: `Amount (${ccy})`, type: "number", required: true },
                      { name: "scheme_case_id", label: "Scheme case ID" },
                      { name: "note", label: "Note", type: "textarea" },
                    ]} />
                )}
                {stage === "REPRESENTMENT" && (
                  <>
                    <Action label="Accept representment" variant="danger" path={`/v1/card-disputes/${id}/accept-representment`}
                      invalidate={inv} description="The case is lost. Provisional credit is reversed unless the issuer absorbs the loss."
                      fields={[
                        { name: "customer_liable", label: "Customer liable", options: ["true", "false"], required: true },
                        { name: "note", label: "Note", type: "textarea" },
                      ]}
                      body={(v) => clean(v, ["customer_liable"])} />
                    <Action label="Escalate" variant="secondary" path={`/v1/card-disputes/${id}/escalate`} invalidate={inv}
                      fields={[
                        { name: "stage", label: "Escalate to", options: ESCALATION_STAGES, required: true },
                        { name: "note", label: "Note", type: "textarea" },
                      ]} />
                  </>
                )}
                {stage === "PRE_ARBITRATION" && (
                  <Action label="Escalate to arbitration" variant="secondary" path={`/v1/card-disputes/${id}/escalate`}
                    invalidate={inv} body={{ stage: "ARBITRATION" }} fields={[{ name: "note", label: "Note", type: "textarea" }]} />
                )}
                <Action label="Close dispute" variant="danger" path={`/v1/card-disputes/${id}/close`} invalidate={inv}
                  description="Posts the outcome. Four-eyes applies to an issuer write-off: the dispute's creator cannot close it."
                  fields={[
                    { name: "outcome", label: "Outcome", options: DISPUTE_OUTCOMES, required: true },
                    { name: "recovered_amount", label: `Recovered amount (${ccy})`, type: "number" },
                    { name: "customer_liable", label: "Customer liable", options: ["false", "true"] },
                    { name: "note", label: "Note", type: "textarea" },
                  ]}
                  body={(v) => clean(v, ["customer_liable"])} />
              </div>
            </Card>
          )}
          {pc > 0 && !d.provisional_credit_reversed && CLOSED.includes(status) && status !== "WON" && (
            <Card title="Provisional credit" className="mb-6">
              <div className="flex items-center gap-4 text-sm">
                <span>Provisional credit of <Money amount={d.provisional_credit_amount as string} currency={ccy} /> is still with the cardholder.</span>
                <Action label="Reverse provisional credit" variant="danger" path={`/v1/card-disputes/${id}/reverse-provisional-credit`}
                  invalidate={inv} fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
              </div>
            </Card>
          )}
          <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
            <Card title="Dispute" className="lg:col-span-2">
              <Details items={[
                ["Disputed", <Money key="a" amount={d.disputed_amount as string} currency={ccy} />],
                ["Chargeback", d.chargeback_amount ? <Money key="b" amount={d.chargeback_amount as string} currency={ccy} /> : null],
                ["Representment", d.representment_amount ? <Money key="c" amount={d.representment_amount as string} currency={ccy} /> : null],
                ["Provisional credit", <Money key="d" amount={d.provisional_credit_amount as string} currency={ccy} />],
                ["PC reversed", d.provisional_credit_reversed],
                ["Recovered", <Money key="e" amount={d.recovered_amount as string} currency={ccy} />],
                ["Written off", <Money key="f" amount={d.written_off_amount as string} currency={ccy} />],
                ["Outcome", d.outcome],
                ["Reason code", d.reason_code], ["Suggested code", d.suggested_reason_code],
                ["Transaction date", d.transaction_date], ["Scheme case", d.scheme_case_id],
                ["Stage deadline", d.stage_deadline], ["Regulatory deadline", d.regulatory_deadline],
                ["Card", <Link key="g" className="text-accent hover:underline" href={`/cards/${String(d.card_id)}`}><Mono>{String(d.card_id)}</Mono></Link>],
                ["Authorisation", d.card_authorization_id
                  ? <Link key="h" className="text-accent hover:underline" href={`/cards/authorisations?id=${String(d.card_authorization_id)}`}>
                      <Mono>{String(d.card_authorization_id)}</Mono></Link> : null],
                ["Customer", d.customer_id
                  ? <Link key="i" className="text-accent hover:underline" href={`/customers/${String(d.customer_id)}`}><Mono>{String(d.customer_id)}</Mono></Link> : null],
                ["Submitted", d.submitted_at], ["Closed", d.closed_at], ["Opened", d.created_at],
              ]} />
              {d.customer_description ? (
                <div className="mt-4 text-sm"><p className="mb-1 text-xs font-medium text-muted">Customer description</p>
                  <p>{String(d.customer_description)}</p></div>
              ) : null}
              {d.ai_summary ? (
                <div className="mt-4 rounded bg-accent-soft p-3 text-sm"><p className="mb-1 text-xs font-medium text-accent">Copilot summary</p>
                  <p>{String(d.ai_summary)}</p></div>
              ) : null}
            </Card>
            <Card title="Timeline">
              <ErrorBanner error={events.error} />
              {events.isLoading ? <Loading /> : (
                <Timeline items={rows(events.data).map((e) => ({
                  at: e.created_at,
                  title: String(e.event_type ?? "").replaceAll("_", " "),
                  detail: [
                    e.from_stage || e.to_stage ? `${String(e.from_stage ?? "—")} → ${String(e.to_stage ?? "—")}` : null,
                    e.amount ? `${String(e.amount)} ${ccy}` : null,
                    e.note, e.actor_id ?? e.created_by,
                  ].filter(Boolean).map(String).join(" · "),
                }))} />
              )}
            </Card>
          </div>
          <Card title="Evidence" actions={open && (
            <Action label="Attach evidence" variant="secondary" path="/v1/card-dispute-evidence"
              invalidate={[["card-dispute-evidence", id]]}
              description="Attach a document already uploaded to the document service."
              fields={[
                { name: "evidence_type", label: "Type", options: EVIDENCE_TYPES, required: true },
                { name: "document_id", label: "Document ID", required: true },
                { name: "description", label: "Description", type: "textarea" },
              ]}
              body={(v) => ({ ...clean(v), card_dispute_id: id })} />
          )}>
            <ErrorBanner error={evidence.error} />
            {evidence.isLoading ? <Loading /> : (
              <Table rows={rows(evidence.data)} empty="No evidence attached."
                columns={[
                  { key: "evidence_type", header: "Type" },
                  { key: "description", header: "Description" },
                  { key: "document_id", header: "Document", render: (r) => <Mono>{String(r.document_id)}</Mono> },
                  { key: "submitted_to_scheme", header: "Submitted", render: (r) =>
                    <Badge value={r.submitted_to_scheme ? "SUBMITTED" : "NOT SUBMITTED"} tone={r.submitted_to_scheme ? "good" : "muted"} /> },
                  { key: "created_at", header: "Added", render: dateCell("created_at") },
                  { key: "actions", header: "", render: (r) => !r.submitted_to_scheme && open ? (
                    <Action size="sm" variant="danger" label="Remove" method="DELETE" path={`/v1/card-dispute-evidence/${String(r.id)}`}
                      invalidate={[["card-dispute-evidence", id]]} description="Remove this evidence from the dispute." />
                  ) : null },
                ]} />
            )}
          </Card>
        </>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><DisputeDetail /></Suspense>;
}
