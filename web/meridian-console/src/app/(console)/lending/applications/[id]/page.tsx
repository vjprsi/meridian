"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Json, Tabs, rows, type Row } from "@/components/ops";
import { DetailSections } from "@/components/detail";
import { loanApplicationDetailSections } from "@/lib/entities/loan";
import { CustomerLink, Section, money, typed } from "../../_components/common";

type Tab = "overview" | "applicants" | "documents" | "scores" | "decisions" | "offers";
const TABS: { id: Tab; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "applicants", label: "Applicants" },
  { id: "documents", label: "Documents" },
  { id: "scores", label: "Scores & affordability" },
  { id: "decisions", label: "Decisions" },
  { id: "offers", label: "Offers" },
];

/** Statuses from which the application can still be decided / worked by an underwriter. */
const DECIDABLE = ["SUBMITTED", "IN_REVIEW", "BUREAU_PENDING", "SCORED", "REFERRED"];
const WITHDRAWABLE = ["DRAFT", ...DECIDABLE, "APPROVED", "COUNTER_OFFERED"];

function useSub(id: string, name: string, path: string) {
  return useQuery({ queryKey: ["loan-application", id, name], queryFn: () => call<unknown>("GET", path) });
}

function ApplicationDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("overview");
  const [result, setResult] = useState<{ title: string; value: unknown } | null>(null);
  const app = useQuery({ queryKey: ["loan-application", id], queryFn: () => call<Row>("GET", `/v1/loan-applications/${id}`) });
  const inv = [["loan-application", id], ["loan-applications"]];
  const show = (title: string) => (value: unknown) => setResult({ title, value });

  if (app.isLoading) return <Loading />;
  const a = app.data;
  const status = String(a?.status ?? "");

  return (
    <>
      <PageHeader title={a ? `Application ${String(a.application_number ?? "")}` : "Loan application"}
        subtitle={a && <>{String(a.loan_type)} · {String(a.purpose)} · {money(a, "requested_amount")} · <Badge value={status} /></>} />
      <ErrorBanner error={app.error} />
      {a && (
        <Card title="Underwriting actions" className="mb-6">
          <Section>
            {status === "DRAFT" && (
              <Action label="Submit" path={`/v1/loan-applications/${id}/submit`} invalidate={inv}
                description="Submit for decisioning. Applicant, consent and amount checks run now." />
            )}
            {DECIDABLE.includes(status) && <>
              <Action label="Auto-decide" path={`/v1/loan-applications/${id}/auto-decide`} invalidate={inv} onDone={show("Automatic decision")}
                description="Run bureau, scorecard, affordability, policy rules and offer generation end to end." />
              <Action label="Score" variant="secondary" path={`/v1/loan-applications/${id}/score`} invalidate={inv} onDone={show("Credit score")}
                description="Run the champion (or routed challenger) scorecard." />
              <Action label="Affordability" variant="secondary" path={`/v1/loan-applications/${id}/affordability`} invalidate={inv}
                onDone={show("Affordability assessment")} description="Run DTI, DSR, residual income and stressed instalment checks." />
              <Action label="Manual decision" variant="secondary" path={`/v1/loan-applications/${id}/decide`} invalidate={inv}
                onDone={show("Decision request (awaiting a second approver)")}
                description="Propose a manual decision or override. A different user must approve it under Decisions."
                fields={[
                  { name: "outcome", label: "Outcome", required: true, options: ["APPROVED", "DECLINED", "REFER", "COUNTER_OFFER"] },
                  { name: "approved_amount", label: "Approved amount", type: "number" },
                  { name: "approved_term_months", label: "Approved term (months)", type: "number" },
                  { name: "approved_rate", label: "Approved rate (%)", type: "number" },
                  { name: "notes", label: "Notes", type: "textarea" },
                ]}
                body={typed(["approved_term_months"])} />
              <Action label="Assign" variant="secondary" path={`/v1/loan-applications/${id}/assign`} invalidate={inv}
                fields={[{ name: "assigned_to", label: "Underwriter (user id)", required: true, initial: String(a.assigned_to ?? "") }]} />
            </>}
            {status === "SIGNED" && (
              <Action label="Book loan" path={`/v1/loan-applications/${id}/book`} invalidate={inv}
                description="Book the loan in the loan service for this signed application." />
            )}
            {WITHDRAWABLE.includes(status) && (
              <Action label="Withdraw" variant="danger" path={`/v1/loan-applications/${id}/withdraw`} invalidate={inv}
                fields={[{ name: "reason", label: "Reason", type: "textarea" }]} description="Withdraw the application. This cannot be undone." />
            )}
            {a.loan_id ? <Link href={`/lending/loans/${a.loan_id}`} className="text-sm text-accent hover:underline">Open booked loan →</Link> : null}
          </Section>
          {a.booking_error ? <p className="mt-3 rounded bg-bad-soft p-2 text-xs text-bad">Booking failed: {String(a.booking_error)}</p> : null}
        </Card>
      )}
      {result && (
        <Card title={result.title} className="mb-6" actions={<button className="text-xs text-muted hover:text-text" onClick={() => setResult(null)}>Dismiss</button>}>
          <Json value={result.value} />
        </Card>
      )}
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {a && tab === "overview" && <Overview a={a} />}
      {tab === "applicants" && <Applicants id={id} />}
      {tab === "documents" && <Documents id={id} />}
      {tab === "scores" && <Scores id={id} />}
      {tab === "decisions" && <Decisions id={id} />}
      {tab === "offers" && <Offers id={id} />}
    </>
  );
}

function Overview({ a }: { a: Row }) {
  return <DetailSections sections={loanApplicationDetailSections(a as Record<string, unknown>)} />;
}

function Applicants({ id }: { id: string }) {
  const q = useSub(id, "applicants", `/v1/loan-applications/${id}/applicants?limit=100`);
  const fin = useSub(id, "financial-items", `/v1/loan-applications/${id}/financial-items?limit=200`);
  return (
    <div className="grid grid-cols-1 gap-6">
      <Card title="Applicants">
        <ErrorBanner error={q.error} />
        {q.isLoading ? <Loading /> : (
          <Table rows={rows(q.data)} empty="No applicants."
            columns={[
              { key: "role", header: "Role" },
              { key: "customer_id", header: "Customer", render: (r) => <CustomerLink id={r.customer_id} /> },
              { key: "employment_status", header: "Employment" },
              { key: "employer_name", header: "Employer" },
              { key: "monthly_net_income", header: "Net income / month", align: "right" },
              { key: "dependants", header: "Dependants", align: "right" },
              { key: "share_of_liability", header: "Liability share", align: "right" },
              { key: "bureau", header: "", render: (r) => (
                <Action label="Pull bureau" size="sm" variant="secondary" path={`/v1/loan-applications/${id}/bureau-pull`}
                  invalidate={[["loan-application", id]]} description="Pull a credit bureau report for this applicant."
                  fields={[{ name: "provider", label: "Provider" }, { name: "force", label: "Force a fresh pull", options: ["false", "true"], required: true }]}
                  body={typed([], ["force"], { loan_applicant_id: r.id })} />
              ) },
            ]} />
        )}
      </Card>
      <Card title="Income & expenditure">
        <ErrorBanner error={fin.error} />
        {fin.isLoading ? <Loading /> : (
          <Table rows={rows(fin.data)} empty="No financial items declared."
            columns={[
              { key: "item_type", header: "Item" },
              { key: "frequency", header: "Frequency" },
              { key: "amount", header: "Amount", align: "right", render: (r) => money(r, "amount") },
              { key: "is_verified", header: "Verified", render: (r) => <Badge value={r.is_verified ? "VERIFIED" : "UNVERIFIED"} tone={r.is_verified ? "good" : "warn"} /> },
              { key: "verification_source", header: "Source" },
            ]} />
        )}
      </Card>
    </div>
  );
}

function Documents({ id }: { id: string }) {
  const q = useSub(id, "documents", `/v1/loan-applications/${id}/documents?limit=100`);
  const inv = [["loan-application", id]];
  return (
    <Card title="Documents">
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : (
        <Table rows={rows(q.data)} empty="No documents requested."
          columns={[
            { key: "document_type", header: "Type" },
            { key: "is_mandatory", header: "Mandatory", render: (r) => (r.is_mandatory ? "yes" : "no") },
            { key: "document_id", header: "Document", render: (r) => <Mono>{String(r.document_id ?? "—")}</Mono> },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "verified_by", header: "Verified by" },
            { key: "rejection_reason", header: "Rejection reason" },
            { key: "actions", header: "", render: (r) => r.status === "UPLOADED" ? (
              <div className="flex gap-1">
                <Action label="Verify" size="sm" path={`/v1/loan-applications/${id}/documents/${r.id}/verify`} invalidate={inv}
                  description="Mark this document verified. The verifier must differ from the uploader." />
                <Action label="Reject" size="sm" variant="danger" path={`/v1/loan-applications/${id}/documents/${r.id}/reject`} invalidate={inv}
                  fields={[{ name: "rejection_reason", label: "Rejection reason", required: true, type: "textarea" }]} />
              </div>
            ) : null },
          ]} />
      )}
    </Card>
  );
}

function Scores({ id }: { id: string }) {
  const scores = useSub(id, "scores", `/v1/loan-applications/${id}/scores?limit=50`);
  const aff = useSub(id, "affordability", `/v1/affordability-assessments?loan_application_id=${id}&limit=50`);
  const bureau = useSub(id, "bureau", `/v1/bureau-reports?loan_application_id=${id}&limit=50`);
  return (
    <div className="grid grid-cols-1 gap-6">
      <Card title="Credit scores">
        <ErrorBanner error={scores.error} />
        {scores.isLoading ? <Loading /> : (
          <Table rows={rows(scores.data)} empty="Not scored yet."
            columns={[
              { key: "created_at", header: "When", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "score", header: "Score", align: "right" },
              { key: "risk_grade", header: "Grade" },
              { key: "probability_of_default", header: "PD", align: "right" },
              { key: "cutoff_outcome", header: "Cut-off" },
              { key: "routing", header: "Routing" },
              { key: "reason_codes", header: "Reasons", render: (r) => <span className="text-xs">{(r.reason_codes as unknown[] ?? []).map((x) => typeof x === "string" ? x : JSON.stringify(x)).join(", ") || "—"}</span> },
            ]} />
        )}
      </Card>
      <Card title="Affordability">
        <ErrorBanner error={aff.error} />
        {aff.isLoading ? <Loading /> : (
          <Table rows={rows(aff.data)} empty="No assessment yet."
            columns={[
              { key: "created_at", header: "When", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "verified_monthly_income", header: "Income", align: "right", render: (r) => money(r, "verified_monthly_income") },
              { key: "proposed_instalment", header: "Instalment", align: "right", render: (r) => money(r, "proposed_instalment") },
              { key: "debt_to_income", header: "DTI", align: "right" },
              { key: "debt_service_ratio", header: "DSR", align: "right" },
              { key: "disposable_income", header: "Disposable", align: "right", render: (r) => money(r, "disposable_income") },
              { key: "result", header: "Result", render: (r) => <Badge value={String(r.result)} tone={r.result === "PASS" ? "good" : r.result === "FAIL" ? "bad" : "warn"} /> },
            ]} />
        )}
      </Card>
      <Card title="Bureau reports">
        <ErrorBanner error={bureau.error} />
        {bureau.isLoading ? <Loading /> : (
          <Table rows={rows(bureau.data)} empty="No bureau reports."
            columns={[
              { key: "created_at", header: "When", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "provider", header: "Provider" },
              { key: "bureau_score", header: "Score", align: "right" },
              { key: "score_band", header: "Band" },
              { key: "delinquent_accounts", header: "Delinquent accts", align: "right" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            ]} />
        )}
      </Card>
    </div>
  );
}

function Decisions({ id }: { id: string }) {
  const decisions = useSub(id, "decisions", `/v1/loan-applications/${id}/decisions?limit=50`);
  const requests = useSub(id, "decision-requests", `/v1/loan-applications/${id}/decision-requests?limit=50`);
  const inv = [["loan-application", id], ["loan-applications"]];
  return (
    <div className="grid grid-cols-1 gap-6">
      <Card title="Manual decision requests (maker-checker)">
        <ErrorBanner error={requests.error} />
        {requests.isLoading ? <Loading /> : (
          <Table rows={rows(requests.data)} empty="No manual decision requests."
            columns={[
              { key: "created_at", header: "Raised", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "requested_by", header: "Maker" },
              { key: "outcome", header: "Outcome", render: (r) => <Badge value={String(r.outcome)} /> },
              { key: "approved_amount", header: "Amount", align: "right" },
              { key: "approved_term_months", header: "Term", align: "right" },
              { key: "approved_rate", header: "Rate", align: "right" },
              { key: "notes", header: "Notes" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "actions", header: "", render: (r) => r.status === "PENDING_APPROVAL" ? (
                <div className="flex gap-1">
                  <Action label="Approve" size="sm" path={`/v1/credit-decision-requests/${r.id}/approve`} invalidate={inv}
                    fields={[{ name: "comment", label: "Comment" }]} description="Approve this decision as checker (you must not be the maker)." />
                  <Action label="Reject" size="sm" variant="danger" path={`/v1/credit-decision-requests/${r.id}/reject`} invalidate={inv}
                    fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
                </div>
              ) : null },
            ]} />
        )}
      </Card>
      <Card title="Decision history">
        <ErrorBanner error={decisions.error} />
        {decisions.isLoading ? <Loading /> : (
          <Table rows={rows(decisions.data)} empty="No decisions yet."
            columns={[
              { key: "created_at", header: "When", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "method", header: "Method" },
              { key: "outcome", header: "Outcome", render: (r) => <Badge value={String(r.outcome)} /> },
              { key: "approved_amount", header: "Amount", align: "right" },
              { key: "approved_term_months", header: "Term", align: "right" },
              { key: "approved_rate", header: "Rate", align: "right" },
              { key: "risk_grade", header: "Grade" },
              { key: "decided_by", header: "Decided by" },
              { key: "reasons", header: "Reasons", render: (r) => <span className="text-xs">
                {[...(r.reason_codes as unknown[] ?? []), ...(r.decline_reasons as unknown[] ?? [])]
                  .map((x) => typeof x === "string" ? x : JSON.stringify(x)).join(", ") || "—"}</span> },
            ]} />
        )}
      </Card>
    </div>
  );
}

function Offers({ id }: { id: string }) {
  const offers = useSub(id, "offers", `/v1/loan-applications/${id}/offers?limit=50`);
  const inv = [["loan-application", id], ["loan-applications"]];
  return (
    <Card title="Offers">
      <ErrorBanner error={offers.error} />
      {offers.isLoading ? <Loading /> : (
        <Table rows={rows(offers.data)} empty="No offers issued."
          columns={[
            { key: "offer_amount", header: "Amount", align: "right", render: (r) => money(r, "offer_amount") },
            { key: "term_months", header: "Term", align: "right" },
            { key: "interest_rate", header: "Rate", align: "right" },
            { key: "apr", header: "APR", align: "right" },
            { key: "instalment_amount", header: "Instalment", align: "right", render: (r) => money(r, "instalment_amount") },
            { key: "total_repayable", header: "Total repayable", align: "right", render: (r) => money(r, "total_repayable") },
            { key: "expires_at", header: "Expires", render: (r) => <DateTime value={r.expires_at as string} /> },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "ISSUED" ? (
              <div className="flex gap-1">
                <Action label="Accept" size="sm" path={`/v1/loan-offers/${r.id}/accept`} invalidate={inv}
                  fields={[{ name: "provider", label: "E-signature provider" }]}
                  description="Record the customer's acceptance and create the e-signature envelope." />
                <Action label="Decline" size="sm" variant="secondary" path={`/v1/loan-offers/${r.id}/decline`} invalidate={inv}
                  fields={[{ name: "reason", label: "Reason" }]} description="Record that the customer declined this offer." />
                <Action label="Withdraw" size="sm" variant="danger" path={`/v1/loan-offers/${r.id}/withdraw`} invalidate={inv}
                  fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} description="Withdraw the offer (lender)." />
              </div>
            ) : null },
          ]} />
      )}
    </Card>
  );
}

export default function Page() {
  return <Suspense><ApplicationDetail /></Suspense>;
}
