"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Pager, Stat, Tabs, Timeline, rows, usePaged, type Row } from "@/components/ops";
import { Bucket, CustomerLink, DISBURSE_METHODS, REPAYMENT_SOURCES, RESTRUCTURE_TYPES, Section, money, typed } from "../../_components/common";

type Tab = "schedule" | "repayments" | "disbursements" | "restructures" | "write-offs" | "history" | "terms";
const TABS: { id: Tab; label: string }[] = [
  { id: "schedule", label: "Schedule" },
  { id: "repayments", label: "Repayments" },
  { id: "disbursements", label: "Disbursements" },
  { id: "restructures", label: "Restructures" },
  { id: "write-offs", label: "Write-offs" },
  { id: "history", label: "Status history" },
  { id: "terms", label: "Terms" },
];

const SERVICING = ["ACTIVE", "IN_ARREARS", "RESTRUCTURED"];
const UNPAID_INSTALMENT = ["DUE", "OVERDUE", "PARTIALLY_PAID"];

function LoanDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("schedule");
  const [quote, setQuote] = useState<Row | null>(null);
  const [result, setResult] = useState<{ title: string; value: unknown } | null>(null);
  const loan = useQuery({ queryKey: ["loan", id], queryFn: () => call<Row>("GET", `/v1/loans/${id}`) });
  const inv = [["loan", id], [`loan:${id}`], ["loans"]];
  const show = (title: string) => (value: unknown) => setResult({ title, value });

  if (loan.isLoading) return <Loading />;
  const l = loan.data;
  const status = String(l?.status ?? "");
  const cur = String(l?.currency ?? "");

  return (
    <>
      <PageHeader title={l ? `Loan ${String(l.loan_number ?? "")}` : "Loan"}
        subtitle={l && <>{String(l.loan_type)} · {money(l, "principal_amount")} over {String(l.term_months)} months at {String(l.interest_rate)}% ·{" "}
          <Badge value={status} /></>} />
      <ErrorBanner error={loan.error} />
      {l && (
        <>
          <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Stat label="Total outstanding" value={money(l, "total_outstanding")}
              hint={<>principal {money(l, "outstanding_principal")} · interest {money(l, "outstanding_interest")}</>} />
            <Stat label="Overdue" value={money(l, "overdue_amount")} tone={Number(l.overdue_amount ?? 0) > 0 ? "bad" : undefined}
              hint={<>fees {money(l, "outstanding_fees")} · penalties {money(l, "outstanding_penalties")}</>} />
            <Stat label="Days past due" value={String(l.days_past_due ?? 0)} tone={Number(l.days_past_due ?? 0) > 0 ? "warn" : "good"}
              hint={<><Bucket value={l.delinquency_bucket} /> · {String(l.ecl_stage ?? "—")}</>} />
            <Stat label="Next due" value={String(l.next_due_date ?? "—")} hint={<>maturity {String(l.maturity_date ?? "—")}</>} />
          </div>

          <Card title="Servicing actions" className="mb-6">
            <Section>
              {(status === "PENDING_DISBURSEMENT" || SERVICING.includes(status)) && (
                <Action label="Disburse" path={`/v1/loans/${id}/disburse`} invalidate={inv} onDone={show("Disbursement")}
                  description="Disburse the loan or its next tranche. The ledger debits the loan receivable and credits the destination."
                  fields={[
                    { name: "method", label: "Method", required: true, options: DISBURSE_METHODS },
                    { name: "amount", label: `Amount (${cur})`, type: "number", hint: "Leave empty for the full / next tranche amount" },
                    { name: "destination_account_id", label: "Destination account ID", initial: String(l.disbursement_account_id ?? "") },
                    { name: "destination_wallet_id", label: "Destination wallet ID", initial: String(l.disbursement_wallet_id ?? "") },
                    { name: "scheduled_date", label: "Scheduled date", type: "date" },
                  ]} />
              )}
              {SERVICING.includes(status) && <>
                <Action label="Record repayment" path={`/v1/loans/${id}/repay`} invalidate={inv} onDone={show("Repayment allocation")}
                  description="Apply a repayment through the allocation waterfall."
                  fields={[
                    { name: "amount", label: `Amount (${cur})`, required: true, type: "number" },
                    { name: "source", label: "Source", required: true, options: REPAYMENT_SOURCES },
                    { name: "value_date", label: "Value date", type: "date" },
                    { name: "source_account_id", label: "Source account ID" },
                    { name: "external_reference", label: "External reference" },
                  ]} />
                <Action label="Prepayment quote" variant="secondary" path={`/v1/loans/${id}/prepayment-quote`} invalidate={inv}
                  onDone={(r) => setQuote(r as Row)} description="Quote a full or partial prepayment."
                  fields={[
                    { name: "quote_type", label: "Quote type", required: true, options: ["FULL", "PARTIAL"] },
                    { name: "requested_amount", label: "Amount (partial only)", type: "number" },
                    { name: "partial_option", label: "Partial option", options: ["REDUCE_TERM", "REDUCE_INSTALMENT"] },
                  ]} />
                <Action label="Change rate" variant="secondary" path={`/v1/loans/${id}/change-rate`} invalidate={inv} onDone={show("Rate change")}
                  description="Change the interest rate and regenerate the remaining schedule."
                  fields={[
                    { name: "new_rate", label: "New rate (%)", required: true, type: "number" },
                    { name: "effective_date", label: "Effective date", required: true, type: "date" },
                    { name: "index_value", label: "Index value", type: "number" },
                    { name: "reason", label: "Reason", required: true, type: "textarea" },
                  ]} />
                <Action label="Restructure" variant="secondary" path={`/v1/loans/${id}/restructure`} invalidate={inv}
                  onDone={() => setTab("restructures")}
                  description="Propose a restructure; the schedule preview is computed and it then goes through submit / approve / apply."
                  fields={[
                    { name: "restructure_type", label: "Type", required: true, options: RESTRUCTURE_TYPES },
                    { name: "new_term_months", label: "New term (months)", type: "number" },
                    { name: "new_interest_rate", label: "New rate (%)", type: "number" },
                    { name: "holiday_months", label: "Holiday months", type: "number" },
                    { name: "capitalise_arrears", label: "Capitalise arrears", options: ["false", "true"] },
                    { name: "settlement_amount", label: "Settlement amount", type: "number" },
                    { name: "effective_date", label: "Effective date", type: "date" },
                    { name: "reason", label: "Reason", required: true, type: "textarea" },
                  ]}
                  body={typed(["new_term_months", "holiday_months"], ["capitalise_arrears"])} />
                <Action label={l.auto_debit_enabled ? "Disable auto-debit" : "Enable auto-debit"} variant="secondary"
                  path={`/v1/loans/${id}/set-auto-debit`} invalidate={inv}
                  fields={l.auto_debit_enabled ? [] : [{ name: "repayment_account_id", label: "Repayment account ID",
                    initial: String(l.repayment_account_id ?? "") }]}
                  body={(v) => ({ auto_debit_enabled: !l.auto_debit_enabled, ...(v.repayment_account_id ? { repayment_account_id: v.repayment_account_id } : {}) })} />
                <Action label="Write off" variant="danger" path={`/v1/loans/${id}/write-off`} invalidate={inv}
                  onDone={() => setTab("write-offs")} description="Request a write-off. A second user must approve it before it posts."
                  fields={[
                    { name: "write_off_type", label: "Type", required: true, options: ["FULL", "PARTIAL"] },
                    { name: "principal_amount", label: "Principal amount (partial)", type: "number" },
                    { name: "reason", label: "Reason", required: true, type: "textarea" },
                  ]} />
              </>}
              {(status === "PENDING_DISBURSEMENT" || SERVICING.includes(status)) && (
                <Action label="Cancel loan" variant="danger" path={`/v1/loans/${id}/cancel`} invalidate={inv}
                  description="Cancel before disbursement or within the cooling-off period; disbursed funds are returned."
                  fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
              )}
              {status === "PAID_OFF" && (
                <Action label="Close loan" path={`/v1/loans/${id}/close`} invalidate={inv} description="Close this paid-off loan." />
              )}
              {l.loan_application_id ? (
                <Link href={`/lending/applications/${l.loan_application_id}`} className="text-sm text-accent hover:underline">Origination file →</Link>
              ) : null}
            </Section>
          </Card>
        </>
      )}

      {quote && (
        <Card title="Prepayment quote" className="mb-6" actions={<>
          {quote.id && quote.status !== "USED" ? (
            <Action label="Prepay with this quote" path={`/v1/loans/${id}/prepay`} invalidate={inv}
              onDone={(r) => { setQuote(null); setResult({ title: "Prepayment", value: r }); }}
              description="Apply the prepayment using this quote."
              fields={[{ name: "source", label: "Source", options: REPAYMENT_SOURCES }, { name: "source_account_id", label: "Source account ID" }]}
              body={typed([], [], { loan_prepayment_quote_id: quote.id })} />
          ) : null}
          <button className="text-xs text-muted hover:text-text" onClick={() => setQuote(null)}>Dismiss</button>
        </>}>
          <Json value={quote} />
        </Card>
      )}
      {result && (
        <Card title={result.title} className="mb-6" actions={<button className="text-xs text-muted hover:text-text" onClick={() => setResult(null)}>Dismiss</button>}>
          <Json value={result.value} />
        </Card>
      )}

      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === "schedule" && <Schedule id={id} />}
      {tab === "repayments" && <Repayments id={id} />}
      {tab === "disbursements" && <Disbursements id={id} />}
      {tab === "restructures" && <Restructures id={id} />}
      {tab === "write-offs" && <WriteOffs id={id} />}
      {tab === "history" && <History id={id} />}
      {tab === "terms" && l && <Terms l={l} />}
    </>
  );
}

function Schedule({ id }: { id: string }) {
  const p = usePaged(`loan:${id}`, `/v1/loans/${id}/schedule`, {}, { limit: 100 });
  const inv = [["loan", id], [`loan:${id}`]];
  return (
    <Card title="Repayment schedule">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No schedule generated yet."
          columns={[
            { key: "installment_no", header: "#", align: "right" },
            { key: "due_date", header: "Due" },
            { key: "principal_due", header: "Principal", align: "right", render: (r) => money(r, "principal_due", "_") },
            { key: "interest_due", header: "Interest", align: "right", render: (r) => money(r, "interest_due", "_") },
            { key: "fees_due", header: "Fees", align: "right", render: (r) => money(r, "fees_due", "_") },
            { key: "penalties_due", header: "Penalties", align: "right", render: (r) => money(r, "penalties_due", "_") },
            { key: "total_due", header: "Total", align: "right", render: (r) => <span className="font-medium">{money(r, "total_due", "_")}</span> },
            { key: "paid", header: "Paid", align: "right", render: (r) => money({ v: sum(r, ["principal_paid", "interest_paid", "fees_paid", "penalties_paid"]) }, "v", "_") },
            { key: "outstanding_balance_after", header: "Balance after", align: "right", render: (r) => money(r, "outstanding_balance_after", "_") },
            { key: "days_late", header: "Late", align: "right" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)}
              tone={r.status === "OVERDUE" ? "bad" : r.status === "PAID" ? "good" : r.status === "SCHEDULED" ? "muted" : "warn"} /> },
            { key: "actions", header: "", render: (r) => UNPAID_INSTALMENT.includes(String(r.status)) ? (
              <Action label="Waive" size="sm" variant="secondary" path={`/v1/schedule-installments/${r.id}/waive`} invalidate={inv}
                description={`Waive the remaining amount of instalment #${String(r.installment_no)}.`}
                fields={[
                  { name: "components", label: "Component", options: ["PENALTY", "FEE", "INTEREST", "PRINCIPAL"],
                    hint: "Leave empty to waive every component" },
                  { name: "reason", label: "Reason", required: true, type: "textarea" },
                ]}
                body={(v) => ({ reason: v.reason, ...(v.components ? { components: [v.components] } : {}) })} />
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

/** Sums decimal-string components for display only (money is never computed for postings in the UI). */
function sum(r: Row, keys: string[]): string {
  return keys.reduce((a, k) => a + Number(r[k] ?? 0), 0).toFixed(2);
}

function Repayments({ id }: { id: string }) {
  const p = usePaged(`loan:${id}`, `/v1/loans/${id}/repayments`, {});
  const inv = [["loan", id], [`loan:${id}`]];
  return (
    <Card title="Repayments">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No repayments received."
          columns={[
            { key: "received_at", header: "Received", render: (r) => <DateTime value={r.received_at as string} /> },
            { key: "value_date", header: "Value date" },
            { key: "source", header: "Source" },
            { key: "amount", header: "Amount", align: "right", render: (r) => money(r, "amount") },
            { key: "allocated_principal", header: "Principal", align: "right", render: (r) => money(r, "allocated_principal") },
            { key: "allocated_interest", header: "Interest", align: "right", render: (r) => money(r, "allocated_interest") },
            { key: "allocated_fees", header: "Fees", align: "right", render: (r) => money(r, "allocated_fees") },
            { key: "excess_amount", header: "Excess", align: "right", render: (r) => money(r, "excess_amount") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "POSTED" && !r.reversal_of_id ? (
              <Action label="Reverse" size="sm" variant="danger" path={`/v1/loan-repayments/${r.id}/reverse`} invalidate={inv}
                description="Reverse this repayment (e.g. returned direct debit). Its allocation is undone and the ledger posting mirrored."
                fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function Disbursements({ id }: { id: string }) {
  const p = usePaged(`loan:${id}`, "/v1/loan-disbursements", { loan_id: id });
  const inv = [["loan", id], [`loan:${id}`]];
  return (
    <Card title="Disbursements">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="Not disbursed yet."
          columns={[
            { key: "tranche_no", header: "Tranche", align: "right" },
            { key: "method", header: "Method" },
            { key: "amount", header: "Amount", align: "right", render: (r) => money(r, "amount") },
            { key: "fees_deducted", header: "Fees", align: "right", render: (r) => money(r, "fees_deducted") },
            { key: "net_amount", header: "Net", align: "right", render: (r) => money(r, "net_amount") },
            { key: "scheduled_date", header: "Scheduled" },
            { key: "disbursed_at", header: "Disbursed", render: (r) => <DateTime value={r.disbursed_at as string} /> },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "failure_reason", header: "Failure" },
            { key: "actions", header: "", render: (r) => (
              <div className="flex gap-1">
                {r.status === "FAILED" && (
                  <Action label="Retry" size="sm" path={`/v1/loan-disbursements/${r.id}/retry`} invalidate={inv} description="Retry this failed disbursement." />
                )}
                {(r.status === "SCHEDULED" || r.status === "FAILED") && (
                  <Action label="Cancel" size="sm" variant="danger" path={`/v1/loan-disbursements/${r.id}/cancel`} invalidate={inv}
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

function Restructures({ id }: { id: string }) {
  const p = usePaged(`loan:${id}`, "/v1/loan-restructures", { loan_id: id });
  const inv = [["loan", id], [`loan:${id}`], ["loans"]];
  return (
    <Card title="Restructures">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No restructures."
          columns={[
            { key: "created_at", header: "Proposed", render: (r) => <DateTime value={r.created_at as string} /> },
            { key: "restructure_type", header: "Type" },
            { key: "reason", header: "Reason" },
            { key: "new_term_months", header: "New term", align: "right" },
            { key: "new_interest_rate", header: "New rate", align: "right" },
            { key: "holiday_months", header: "Holiday", align: "right" },
            { key: "is_forborne", header: "Forborne", render: (r) => (r.is_forborne ? "yes" : "no") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => (
              <div className="flex gap-1">
                {r.status === "PROPOSED" && (
                  <Action label="Submit" size="sm" path={`/v1/loan-restructures/${r.id}/submit`} invalidate={inv} description="Submit this restructure for approval (maker)." />
                )}
                {r.status === "PENDING_APPROVAL" && <>
                  <Action label="Approve" size="sm" path={`/v1/loan-restructures/${r.id}/approve`} invalidate={inv}
                    fields={[{ name: "comment", label: "Comment" }]} description="Approve as checker (must differ from the maker)." />
                  <Action label="Reject" size="sm" variant="danger" path={`/v1/loan-restructures/${r.id}/reject`} invalidate={inv}
                    fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
                </>}
                {r.status === "APPROVED" && (
                  <Action label="Apply" size="sm" path={`/v1/loan-restructures/${r.id}/apply`} invalidate={inv}
                    description="Apply the approved restructure and generate the new schedule." />
                )}
              </div>
            ) },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function WriteOffs({ id }: { id: string }) {
  const p = usePaged(`loan:${id}`, "/v1/loan-write-offs", { loan_id: id });
  const inv = [["loan", id], [`loan:${id}`], ["loans"]];
  return (
    <Card title="Write-offs">
      <ErrorBanner error={p.error} />
      {p.isLoading ? <Loading /> : (
        <Table rows={p.rows} empty="No write-offs."
          columns={[
            { key: "created_at", header: "Requested", render: (r) => <DateTime value={r.created_at as string} /> },
            { key: "created_by", header: "By" },
            { key: "write_off_type", header: "Type" },
            { key: "total_amount", header: "Total", align: "right", render: (r) => money(r, "total_amount") },
            { key: "recovered_amount", header: "Recovered", align: "right", render: (r) => money(r, "recovered_amount") },
            { key: "reason", header: "Reason" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "PENDING_APPROVAL" ? (
              <div className="flex gap-1">
                <Action label="Approve & post" size="sm" variant="danger" path={`/v1/loan-write-offs/${r.id}/approve`} invalidate={inv}
                  fields={[{ name: "comment", label: "Comment" }]}
                  description="Approve and post the write-off to the ledger. You must not be the requester." />
                <Action label="Reject" size="sm" variant="secondary" path={`/v1/loan-write-offs/${r.id}/reject`} invalidate={inv}
                  fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
              </div>
            ) : null },
          ]} />
      )}
      <Pager p={p} />
    </Card>
  );
}

function History({ id }: { id: string }) {
  const q = useQuery({ queryKey: [`loan:${id}`, "history"], queryFn: () => call<unknown>("GET", `/v1/loans/${id}/status-history?limit=100`) });
  return (
    <Card title="Status and DPD history">
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : (
        <Timeline items={rows(q.data).map((h) => ({
          at: h.changed_at ?? h.created_at,
          status: String(h.to_status ?? ""),
          title: h.from_status ? `${String(h.from_status).replaceAll("_", " ")} → ${String(h.to_status).replaceAll("_", " ")}` : "Status set",
          detail: <>DPD {String(h.days_past_due ?? 0)} · bucket {String(h.delinquency_bucket ?? "—")}{h.ecl_stage ? ` · ${String(h.ecl_stage)}` : ""}
            {h.reason ? ` · ${String(h.reason)}` : ""}</>,
        }))} />
      )}
    </Card>
  );
}

function Terms({ l }: { l: Row }) {
  return (
    <Card title="Contract terms">
      <Details items={[
        ["Customer", <CustomerLink key="c" id={l.customer_id} />], ["Product", <Mono key="p">{String(l.product_id ?? "—")}</Mono>],
        ["Principal", money(l, "principal_amount")], ["Disbursed", money(l, "disbursed_amount")],
        ["Rate type", l.interest_rate_type], ["Rate", `${String(l.interest_rate)}%`], ["Rate index", l.rate_index],
        ["Margin", l.rate_margin], ["Penalty rate", l.penalty_rate], ["Day count", l.day_count],
        ["Schedule type", l.schedule_type], ["Frequency", l.repayment_frequency], ["Term (months)", l.term_months],
        ["Grace days", l.grace_period_days], ["Balloon", l.balloon_amount], ["Start date", l.start_date],
        ["First repayment", l.first_repayment_date], ["Maturity", l.maturity_date], ["Cooling off until", l.cooling_off_until],
        ["Auto-debit", l.auto_debit_enabled], ["Repayment account", l.repayment_account_id], ["Credit balance", money(l, "credit_balance")],
        ["Risk grade", l.risk_grade], ["Forborne", l.is_forborne], ["Last accrual", l.last_accrual_date], ["Paid off", l.paid_off_at],
        ["Closed", l.closed_at], ["Created", l.created_at],
      ]} />
    </Card>
  );
}

export default function Page() {
  return <Suspense><LoanDetail /></Suspense>;
}
