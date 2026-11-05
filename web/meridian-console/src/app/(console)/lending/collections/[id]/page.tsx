"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, PageHeader, Table } from "@/components/ui";
import { DetailSections } from "@/components/detail";
import { collectionCaseDetailSections } from "@/lib/entities/collection";
import { Action, Pager, Stat, Tabs, Timeline, usePaged, type Row } from "@/components/ops";
import { Bucket, CONTACT_ACTIONS, CONTACT_OUTCOMES, FREQUENCIES, LEGAL_STATUSES, RESOLUTIONS, Section, money, typed } from "../../_components/common";

type Tab = "contacts" | "promises" | "plans" | "nba" | "legal";
const TABS: { id: Tab; label: string }[] = [
  { id: "contacts", label: "Contact history" },
  { id: "promises", label: "Promises to pay" },
  { id: "plans", label: "Payment plans" },
  { id: "nba", label: "Next best actions" },
  { id: "legal", label: "Legal" },
];

const OPEN = ["OPEN", "IN_PROGRESS", "PROMISE_ACTIVE", "PLAN_ACTIVE", "LEGAL"];

/** Where the exposure behind a case can be opened in the console. */
function exposureHref(c: Row): string | null {
  if (c.exposure_type === "LOAN" || c.exposure_type === "BNPL") return `/lending/loans/${c.exposure_id}`;
  return null;
}

function CaseDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("contacts");
  const kase = useQuery({ queryKey: ["collection-case", id], queryFn: () => call<Row>("GET", `/v1/collections/cases/${id}`) });
  const inv = [["collection-case", id], [`collection-case:${id}`], ["collection-cases"]];

  if (kase.isLoading) return <Loading />;
  const c = kase.data;
  const status = String(c?.status ?? "");
  const open = OPEN.includes(status);
  const cur = String(c?.currency ?? "");
  const href = c ? exposureHref(c) : null;

  return (
    <>
      <PageHeader title={c ? `Collections case ${String(c.case_number ?? "")}` : "Collections case"}
        subtitle={c && <>{String(c.exposure_type)} · <Badge value={status} />{c.is_disputed ? <> · <Badge value="DISPUTED" tone="bad" /></> : null}
          {c.is_vulnerable ? <> · <Badge value="VULNERABLE" tone="warn" /></> : null}</>} />
      <ErrorBanner error={kase.error} />
      {c && (
        <>
          <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Stat label="Overdue" value={money(c, "overdue_amount")} tone="bad" hint={<>outstanding {money(c, "total_outstanding")}</>} />
            <Stat label="Days past due" value={String(c.days_past_due ?? 0)} hint={<><Bucket value={c.delinquency_bucket} /> · max {String(c.max_days_past_due ?? "—")}</>} />
            <Stat label="Broken promises" value={String(c.broken_promises ?? 0)} tone={Number(c.broken_promises ?? 0) > 0 ? "warn" : undefined}
              hint={<>priority {String(c.priority_score ?? "—")}</>} />
            <Stat label="Collector" value={<span className="text-base">{String(c.assigned_to ?? "unassigned")}</span>}
              hint={c.agency_name ? <>agency {String(c.agency_name)}</> : <>next action <DateTime value={c.next_action_at as string} /></>} />
          </div>
          {open && (
            <Card title="Case actions" className="mb-6">
              <Section>
                <Action label="Log contact" path={`/v1/collections/cases/${id}/log-action`} invalidate={inv} onDone={() => setTab("contacts")}
                  fields={[
                    { name: "action_type", label: "Action", required: true, options: CONTACT_ACTIONS },
                    { name: "outcome", label: "Outcome", required: true, options: CONTACT_OUTCOMES },
                    { name: "contact_channel_address", label: "Phone / address used" },
                    { name: "notes", label: "Notes", type: "textarea" },
                  ]} />
                <Action label="Promise to pay" variant="secondary" path={`/v1/collections/cases/${id}/promise`} invalidate={inv}
                  onDone={() => setTab("promises")}
                  fields={[
                    { name: "promised_amount", label: `Amount (${cur})`, required: true, type: "number" },
                    { name: "promised_date", label: "Promised date", required: true, type: "date" },
                    { name: "channel", label: "Channel" },
                  ]} />
                <Action label="Payment plan" variant="secondary" path={`/v1/collections/cases/${id}/payment-plan`} invalidate={inv}
                  onDone={() => setTab("plans")} description="Propose a payment plan for the arrears."
                  fields={[
                    { name: "installment_count", label: "Instalments", required: true, type: "number" },
                    { name: "frequency", label: "Frequency", required: true, options: FREQUENCIES, initial: "MONTHLY" },
                    { name: "start_date", label: "Start date", required: true, type: "date" },
                    { name: "total_amount", label: "Total amount", type: "number", hint: "Defaults to the arrears" },
                    { name: "interest_frozen", label: "Freeze interest", options: ["false", "true"] },
                  ]}
                  body={typed(["installment_count"], ["interest_frozen"])} />
                <Action label="Next best action" variant="secondary" path={`/v1/collections/cases/${id}/next-best-action`} invalidate={inv}
                  onDone={() => setTab("nba")} description="Score the next best action for this case (deterministic model with reason codes)." />
                <Action label="Assign" variant="secondary" path={`/v1/collections/cases/${id}/assign`} invalidate={inv}
                  fields={[
                    { name: "assigned_to", label: "Collector (user id)", initial: String(c.assigned_to ?? "") },
                    { name: "collection_queue_id", label: "Queue ID", initial: String(c.collection_queue_id ?? "") },
                  ]} />
                <Action label={c.is_disputed ? "Clear dispute" : "Mark disputed"} variant="secondary" path={`/v1/collections/cases/${id}/mark-dispute`}
                  invalidate={inv} fields={[{ name: "notes", label: "Notes", type: "textarea" }]}
                  body={typed([], [], { is_disputed: !c.is_disputed })} />
                <Action label="Send to agency" variant="secondary" path={`/v1/collections/cases/${id}/send-to-agency`} invalidate={inv}
                  description="Outsource this case to an external collection agency."
                  fields={[{ name: "agency_name", label: "Agency", required: true }]} />
                <Action label="Start legal" variant="danger" path={`/v1/collections/cases/${id}/start-legal`} invalidate={inv}
                  onDone={() => setTab("legal")} description="Propose legal proceedings. A second user must approve them."
                  fields={[
                    { name: "claim_amount", label: `Claim amount (${cur})`, required: true, type: "number", initial: String(c.total_outstanding ?? "") },
                    { name: "law_firm", label: "Law firm" },
                    { name: "notes", label: "Notes", type: "textarea" },
                  ]} />
                <Action label="Close case" variant="danger" path={`/v1/collections/cases/${id}/close`} invalidate={inv}
                  fields={[
                    { name: "resolution", label: "Resolution", required: true, options: RESOLUTIONS },
                    { name: "notes", label: "Notes", type: "textarea" },
                  ]} />
              </Section>
            </Card>
          )}
          {href && <p className="mb-4 text-sm"><Link href={href} className="text-accent hover:underline">Open the {String(c.exposure_type).toLowerCase()} exposure →</Link></p>}
          <DetailSections sections={collectionCaseDetailSections(c as Record<string, unknown>)} className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-2" />
        </>
      )}
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === "contacts" && <Contacts id={id} />}
      {tab === "promises" && <Promises id={id} />}
      {tab === "plans" && <Plans id={id} />}
      {tab === "nba" && <NextBest id={id} />}
      {tab === "legal" && <Legal id={id} />}
    </>
  );
}

function Contacts({ id }: { id: string }) {
  const p = usePaged(`collection-case:${id}`, `/v1/collections/cases/${id}/actions`, {}, { limit: 50 });
  return (
    <Card title="Contact history">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Timeline items={p.rows.map((a) => ({
          at: a.performed_at ?? a.created_at,
          title: `${String(a.action_type ?? "").replaceAll("_", " ")} · ${String(a.outcome ?? "").replaceAll("_", " ")}`,
          detail: <>{a.performed_by ? `by ${String(a.performed_by)}` : "automated"}{a.contact_channel_address ? ` · ${String(a.contact_channel_address)}` : ""}
            {a.notes ? ` · ${String(a.notes)}` : ""}</>,
        }))} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function Promises({ id }: { id: string }) {
  const p = usePaged(`collection-case:${id}`, "/v1/collections/promises", { collection_case_id: id });
  const inv = [["collection-case", id], [`collection-case:${id}`]];
  return (
    <Card title="Promises to pay">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No promises recorded."
          columns={[
            { key: "promised_date", header: "Promised for" },
            { key: "promised_amount", header: "Amount", align: "right", render: (r) => money(r, "promised_amount") },
            { key: "paid_amount", header: "Paid", align: "right", render: (r) => money(r, "paid_amount") },
            { key: "channel", header: "Channel" },
            { key: "taken_by", header: "Taken by" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)}
              tone={r.status === "KEPT" ? "good" : r.status === "BROKEN" ? "bad" : r.status === "ACTIVE" ? "info" : undefined} /> },
            { key: "actions", header: "", render: (r) => r.status === "ACTIVE" ? (
              <Action label="Cancel" size="sm" variant="danger" path={`/v1/collections/promises/${r.id}/cancel`} invalidate={inv}
                fields={[{ name: "reason", label: "Reason" }]} />
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function Plans({ id }: { id: string }) {
  const p = usePaged(`collection-case:${id}`, "/v1/collections/payment-plans", { collection_case_id: id });
  const inv = [["collection-case", id], [`collection-case:${id}`], ["collection-cases"]];
  return (
    <Card title="Payment plans">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No payment plans."
          columns={[
            { key: "created_at", header: "Proposed", render: (r) => <DateTime value={r.created_at as string} /> },
            { key: "total_amount", header: "Total", align: "right", render: (r) => money(r, "total_amount") },
            { key: "paid_amount", header: "Paid", align: "right", render: (r) => money(r, "paid_amount") },
            { key: "installment_count", header: "Instalments", align: "right" },
            { key: "frequency", header: "Frequency" },
            { key: "start_date", header: "Start" },
            { key: "interest_frozen", header: "Interest frozen", render: (r) => (r.interest_frozen ? "yes" : "no") },
            { key: "status", header: "Status", render: (r) => <span className="flex gap-1"><Badge value={String(r.status)} />
              {r.requires_approval && r.status === "PROPOSED" ? <Badge value="NEEDS APPROVAL" tone="warn" /> : null}</span> },
            { key: "actions", header: "", render: (r) => (
              <div className="flex gap-1">
                {r.status === "PROPOSED" && (
                  <Action label="Activate" size="sm" path={`/v1/collections/payment-plans/${r.id}/activate`} invalidate={inv}
                    description="Activate the agreed plan. Plans outside policy need a second user." />
                )}
                {(r.status === "PROPOSED" || r.status === "ACTIVE") && (
                  <Action label="Cancel" size="sm" variant="danger" path={`/v1/collections/payment-plans/${r.id}/cancel`} invalidate={inv}
                    fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
                )}
              </div>
            ) },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function NextBest({ id }: { id: string }) {
  const p = usePaged(`collection-case:${id}`, "/v1/collections/next-best-actions", { collection_case_id: id });
  const inv = [["collection-case", id], [`collection-case:${id}`]];
  return (
    <Card title="Next best actions">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No suggestions yet. Use “Next best action” to score one."
          columns={[
            { key: "created_at", header: "Scored", render: (r) => <DateTime value={r.created_at as string} /> },
            { key: "suggested_action", header: "Suggested" },
            { key: "suggested_at_time", header: "When", render: (r) => <DateTime value={r.suggested_at_time as string} /> },
            { key: "propensity_to_pay", header: "Propensity", align: "right" },
            { key: "explanation", header: "Why", render: (r) => <span className="text-xs">{String(r.explanation ?? ((r.reason_codes as unknown[] | null) ?? []).join(", "))}</span> },
            { key: "model_version", header: "Model" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "SUGGESTED" ? (
              <div className="flex gap-1">
                <Action label="Accept" size="sm" path={`/v1/collections/next-best-actions/${r.id}/accept`} invalidate={inv}
                  description="Accept and schedule the suggested action." />
                <Action label="Reject" size="sm" variant="secondary" path={`/v1/collections/next-best-actions/${r.id}/reject`} invalidate={inv}
                  fields={[{ name: "reason", label: "Reason", required: true }]} />
              </div>
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function Legal({ id }: { id: string }) {
  const p = usePaged(`collection-case:${id}`, "/v1/collections/legal-proceedings", { collection_case_id: id });
  const inv = [["collection-case", id], [`collection-case:${id}`], ["collection-cases"]];
  return (
    <Card title="Legal proceedings">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No legal proceedings."
          columns={[
            { key: "created_at", header: "Proposed", render: (r) => <DateTime value={r.created_at as string} /> },
            { key: "requested_by", header: "By" },
            { key: "law_firm", header: "Law firm" },
            { key: "court", header: "Court" },
            { key: "case_reference", header: "Reference" },
            { key: "claim_amount", header: "Claim", align: "right", render: (r) => money(r, "claim_amount") },
            { key: "next_hearing_date", header: "Next hearing" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "PENDING_APPROVAL" ? (
              <div className="flex gap-1">
                <Action label="Approve" size="sm" path={`/v1/collections/legal-proceedings/${r.id}/approve`} invalidate={inv}
                  fields={[{ name: "comment", label: "Comment" }]} description="Approve litigation (you must not be the requester)." />
                <Action label="Reject" size="sm" variant="danger" path={`/v1/collections/legal-proceedings/${r.id}/reject`} invalidate={inv}
                  fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
              </div>
            ) : !["CLOSED", "REJECTED", "SETTLED"].includes(String(r.status)) ? (
              <Action label="Advance" size="sm" variant="secondary" path={`/v1/collections/legal-proceedings/${r.id}/advance`} invalidate={inv}
                description="Move the proceeding to its next stage."
                fields={[
                  { name: "status", label: "New stage", required: true, options: LEGAL_STATUSES },
                  { name: "court", label: "Court", initial: String(r.court ?? "") },
                  { name: "case_reference", label: "Case reference", initial: String(r.case_reference ?? "") },
                  { name: "filed_at", label: "Filed on", type: "date" },
                  { name: "next_hearing_date", label: "Next hearing", type: "date" },
                  { name: "judgement_amount", label: "Judgement amount", type: "number" },
                  { name: "legal_costs", label: "Legal costs", type: "number" },
                  { name: "notes", label: "Notes", type: "textarea" },
                ]} />
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

export default function Page() {
  return <Suspense><CaseDetail /></Suspense>;
}
