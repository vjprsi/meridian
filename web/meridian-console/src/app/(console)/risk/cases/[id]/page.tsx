"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Mono, Money, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, Tabs, Timeline, rows, usePaged, type Row } from "@/components/ops";
import { Severity } from "../../_shared";

/** Case actions allowed per status (mirrors the service's CaseRules; the service remains the authority). */
const ASSIGNABLE = ["OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED"];
const ESCALATABLE = ["OPEN", "INVESTIGATING", "SAR_FILED"];
const ACTIVE = ["OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED"];
const LINKABLE = [...ACTIVE, "PENDING_APPROVAL"];
const DISPOSITIONS = ["NO_SUSPICION", "SAR_FILED", "EXIT_RELATIONSHIP", "ENHANCED_MONITORING", "ACCOUNT_RESTRICTED",
  "REFERRED_TO_LAW_ENFORCEMENT"];
const ALERT_SOURCES = ["FRAUD_ALERT", "TM_ALERT", "SCREENING_RESULT", "MANUAL_REFERRAL", "LAW_ENFORCEMENT_REQUEST", "WHISTLEBLOWER"];
const EVIDENCE_TYPES = ["STATEMENT", "KYC_DOCUMENT", "SCREENSHOT", "GRAPH_EXPORT", "CORRESPONDENCE", "OTHER"];
const SAR_TYPES = ["SAR", "STR", "CTR", "LCTR", "EFTR", "TFR"];

const TABS = [
  { id: "timeline", label: "Timeline" },
  { id: "alerts", label: "Linked alerts" },
  { id: "notes", label: "Notes" },
  { id: "tasks", label: "Tasks" },
  { id: "evidence", label: "Evidence" },
  { id: "dispositions", label: "Dispositions" },
  { id: "sar", label: "Regulatory reports" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function alertHref(a: Row): string | null {
  if (!a.alert_id) return null;
  if (a.source === "FRAUD_ALERT") return `/risk/fraud/${a.alert_id}`;
  if (a.source === "TM_ALERT") return `/risk/aml/${a.alert_id}`;
  if (a.source === "SCREENING_RESULT") return `/risk/sanctions/${a.alert_id}`;
  return null;
}

function CaseDetail() {
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const [tab, setTab] = useState<Tab>("timeline");
  const [restricted, setRestricted] = useState(false);
  const key = ["aml-cases", id];
  const inv = [["aml-cases"]];

  const kase = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/risk/cases/${id}`) });
  const timeline = useQuery({ queryKey: [...key, "timeline", restricted], enabled: tab === "timeline",
    queryFn: () => call<Row>("GET", `/v1/risk/cases/${id}/timeline${restricted ? "?include_restricted=true" : ""}`) });
  const alerts = usePaged("aml-cases", `/v1/risk/cases/${id}/alerts`, {}, { enabled: tab === "alerts", limit: 50 });
  const notes = usePaged("aml-cases", `/v1/risk/cases/${id}/notes`, {}, { enabled: tab === "notes", limit: 50 });
  const tasks = usePaged("aml-cases", "/v1/risk/case-tasks", { aml_case_id: id }, { enabled: tab === "tasks", limit: 50 });
  const evidence = usePaged("aml-cases", "/v1/risk/case-evidence", { aml_case_id: id }, { enabled: tab === "evidence", limit: 50 });
  const dispositions = usePaged("aml-cases", "/v1/risk/case-dispositions", { aml_case_id: id }, { limit: 50 });
  const sars = usePaged("aml-cases", "/v1/risk/sar-reports", { aml_case_id: id }, { enabled: tab === "sar", limit: 50 });
  const summary = useMutation({ mutationFn: () => call<Row>("POST", `/v1/risk/cases/${id}/ai-summary`, {}),
    onSuccess: () => { kase.refetch(); } });
  const exportSar = useMutation({ mutationFn: (s: Row) => download(`/v1/risk/sar-reports/${s.id}/export`, `${String(s.report_type)}-${String(s.id)}.xml`) });

  if (kase.isLoading) return <Loading />;
  const c = kase.data;
  const status = String(c?.status ?? "");
  const pending = dispositions.rows.find((d) => d.approval_status === "PENDING_APPROVAL");

  return (
    <>
      <PageHeader title={c ? String(c.title) : "Case"}
        subtitle={c && <><Mono>{String(c.case_number)}</Mono> · {String(c.case_type).replaceAll("_", " ")} · <Severity value={c.priority} />
          {" "}· <Badge value={status} /></>}
        actions={c && <>
          <Button variant="secondary" disabled={summary.isPending} onClick={() => summary.mutate()}>
            {summary.isPending ? "Summarising…" : "Generate summary"}</Button>
          {ASSIGNABLE.includes(status) && (
            <Action label="Assign" variant="secondary" path={`/v1/risk/cases/${id}/assign`} invalidate={inv}
              fields={[
                { name: "assigned_to", label: "Investigator", required: true, initial: String(c.assigned_to ?? "") },
                { name: "team", label: "Team", initial: String(c.team ?? "") },
              ]} />
          )}
          {ESCALATABLE.includes(status) && (
            <Action label="Escalate to MLRO" variant="secondary" path={`/v1/risk/cases/${id}/escalate`} invalidate={inv}
              fields={[
                { name: "escalated_to", label: "Escalate to", required: true, placeholder: "MLRO user" },
                { name: "reason", label: "Reason", type: "textarea", required: true },
              ]} />
          )}
          {ACTIVE.includes(status) && (
            <Action label="Propose disposition" variant="danger" path={`/v1/risk/cases/${id}/close`} invalidate={inv}
              title="Propose case disposition"
              description="Maker-checker: the case closes only when a different user approves this disposition."
              fields={[
                { name: "disposition", label: "Disposition", required: true, options: DISPOSITIONS },
                { name: "rationale", label: "Rationale", type: "textarea", required: true },
              ]} />
          )}
          {status === "CLOSED" && (
            <Action label="Reopen" variant="secondary" path={`/v1/risk/cases/${id}/reopen`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={kase.error ?? summary.error ?? exportSar.error} />

      {pending && (
        <Card title="Disposition awaiting approval" className="mb-6">
          <div className="flex flex-wrap items-center justify-between gap-4 text-sm">
            <div>
              <p><Badge value={String(pending.disposition)} /> proposed by <span className="font-medium">{String(pending.decided_by)}</span>{" "}
                <span className="text-muted"><DateTime value={pending.decided_at as string} /></span></p>
              <p className="mt-1 text-muted">{String(pending.rationale ?? "")}</p>
              {pending.decided_by === session?.user && (
                <p className="mt-2 rounded bg-warn-soft p-2 text-xs text-warn">You proposed this disposition; another user must approve it.</p>
              )}
            </div>
            <div className="flex gap-2">
              <Action label="Approve & close case" path={`/v1/risk/case-dispositions/${pending.id}/approve`} invalidate={inv}
                fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
              <Action label="Reject" variant="danger" path={`/v1/risk/case-dispositions/${pending.id}/reject`} invalidate={inv}
                description="The case returns to investigation."
                fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
            </div>
          </div>
        </Card>
      )}

      {c && (
        <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Card title="Case" className="lg:col-span-2">
            <Details items={[
              ["Customer", c.customer_id ? <Link key="c" className="text-accent hover:underline" href={`/customers/${c.customer_id}`}>
                <Mono>{String(c.customer_id)}</Mono></Link> : null],
              ["Risk score", c.risk_score],
              ["Amount", <Money key="m" amount={c.total_amount as string} currency={c.currency as string} />],
              ["Investigator", c.assigned_to],
              ["Team", c.team],
              ["Opened", c.opened_at],
              ["Due", c.due_at],
              ["Escalated to", c.escalated_to],
              ["Escalation reason", c.escalation_reason],
              ["Closed", c.closed_at],
              ["Tags", c.tags],
            ]} />
            {c.description ? <p className="mt-4 whitespace-pre-wrap text-sm">{String(c.description)}</p> : null}
          </Card>
          <Card title="Summary">
            {c.ai_summary ? <p className="whitespace-pre-wrap text-sm">{String(c.ai_summary)}</p>
              : <p className="text-sm text-muted">No summary yet. Generate one from the case facts.</p>}
          </Card>
        </div>
      )}

      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />

        {tab === "timeline" && (
          <>
            <label className="mb-4 flex items-center gap-2 text-sm text-muted">
              <input type="checkbox" checked={restricted} onChange={(e) => setRestricted(e.target.checked)} />
              Include restricted notes (tipping-off protected)
            </label>
            <ErrorBanner error={timeline.error} />
            {timeline.isLoading ? <Loading /> : (
              <Timeline items={rows(timeline.data, "items").map((it) => ({
                at: it.at, status: String(it.type ?? ""), title: String(it.summary ?? ""),
                detail: it.actor ? `by ${String(it.actor)}` : undefined,
              }))} />
            )}
          </>
        )}

        {tab === "alerts" && (
          <>
            {LINKABLE.includes(status) && (
              <div className="mb-4"><Action label="Link alert" variant="secondary" path={`/v1/risk/cases/${id}/alerts`} invalidate={inv}
                fields={[
                  { name: "source", label: "Source", required: true, options: ALERT_SOURCES },
                  { name: "alert_id", label: "Alert ID", placeholder: "uuid (not needed for manual referrals)" },
                  { name: "summary", label: "Summary" },
                  { name: "added_reason", label: "Why it belongs to this case", type: "textarea" },
                ]} /></div>
            )}
            <ErrorBanner error={alerts.error} />
            <Table rows={alerts.rows} empty="No alerts linked."
              columns={[
                { key: "created_at", header: "Linked", render: (r) => <DateTime value={r.created_at as string} /> },
                { key: "source", header: "Source", render: (r) => String(r.source ?? "").replaceAll("_", " ") },
                { key: "alert_id", header: "Alert", render: (r) => {
                  const href = alertHref(r);
                  return href ? <Link className="text-accent hover:underline" href={href}><Mono>{String(r.alert_id).slice(0, 8)}</Mono></Link>
                    : <Mono>{String(r.alert_id ?? "—")}</Mono>;
                } },
                { key: "summary", header: "Summary" },
                { key: "added_reason", header: "Reason" },
                { key: "created_by", header: "By" },
              ]} />
            <Pager p={alerts} />
          </>
        )}

        {tab === "notes" && (
          <>
            <div className="mb-4"><Action label="Add note" variant="secondary" path={`/v1/risk/cases/${id}/notes`} invalidate={inv}
              fields={[
                { name: "body", label: "Note", type: "textarea", required: true },
                { name: "visibility", label: "Visibility", required: true, options: ["INTERNAL", "RESTRICTED"],
                  hint: "RESTRICTED notes are hidden by default (tipping-off protection)." },
              ]} /></div>
            <ErrorBanner error={notes.error} />
            <Table rows={notes.rows} empty="No notes."
              columns={[
                { key: "created_at", header: "When", render: (r) => <DateTime value={r.created_at as string} /> },
                { key: "author", header: "Author" },
                { key: "visibility", header: "Visibility", render: (r) => <Badge value={String(r.visibility)}
                  tone={r.visibility === "RESTRICTED" ? "warn" : "muted"} /> },
                { key: "body", header: "Note", render: (r) => <span className="whitespace-pre-wrap">{String(r.body ?? "")}</span> },
              ]} />
            <Pager p={notes} />
          </>
        )}

        {tab === "tasks" && (
          <>
            <div className="mb-4"><Action label="New task" variant="secondary" path="/v1/risk/case-tasks" body={{ aml_case_id: id }}
              invalidate={inv} fields={[
                { name: "title", label: "Title", required: true },
                { name: "description", label: "Description", type: "textarea" },
                { name: "assigned_to", label: "Assignee" },
              ]} /></div>
            <ErrorBanner error={tasks.error} />
            <Table rows={tasks.rows} empty="No tasks."
              columns={[
                { key: "title", header: "Task", render: (r) => <span className="font-medium">{String(r.title)}</span> },
                { key: "assigned_to", header: "Assignee" },
                { key: "due_at", header: "Due", render: (r) => <DateTime value={r.due_at as string} /> },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
                { key: "completed_by", header: "Completed by" },
                { key: "actions", header: "", render: (r) => ["OPEN", "IN_PROGRESS"].includes(String(r.status)) ? (
                  <span className="flex gap-1">
                    {r.status === "OPEN" && (
                      <Action size="sm" label="Start" variant="secondary" method="PATCH" path={`/v1/risk/case-tasks/${r.id}`}
                        body={{ status: "IN_PROGRESS" }} invalidate={inv} title={`Start task: ${String(r.title)}`} />
                    )}
                    <Action size="sm" label="Complete" path={`/v1/risk/case-tasks/${r.id}/complete`} invalidate={inv}
                      title={`Complete task: ${String(r.title)}`} fields={[{ name: "note", label: "Note", type: "textarea" }]} />
                    <Action size="sm" label="Cancel" variant="ghost" method="PATCH" path={`/v1/risk/case-tasks/${r.id}`}
                      body={{ status: "CANCELLED" }} invalidate={inv} title={`Cancel task: ${String(r.title)}`} confirmLabel="Cancel task" />
                  </span>
                ) : null },
              ]} />
            <Pager p={tasks} />
          </>
        )}

        {tab === "evidence" && (
          <>
            <div className="mb-4"><Action label="Attach evidence" variant="secondary" path="/v1/risk/case-evidence"
              body={{ aml_case_id: id }} invalidate={inv}
              description="References a document already stored in the document service; the SHA-256 pins its content."
              fields={[
                { name: "document_id", label: "Document ID", required: true, placeholder: "uuid" },
                { name: "evidence_type", label: "Evidence type", required: true, options: EVIDENCE_TYPES },
                { name: "sha256", label: "SHA-256", required: true },
                { name: "description", label: "Description", type: "textarea" },
              ]} /></div>
            <ErrorBanner error={evidence.error} />
            <Table rows={evidence.rows} empty="No evidence attached."
              columns={[
                { key: "created_at", header: "Added", render: (r) => <DateTime value={r.created_at as string} /> },
                { key: "evidence_type", header: "Type", render: (r) => String(r.evidence_type ?? "").replaceAll("_", " ") },
                { key: "description", header: "Description" },
                { key: "document_id", header: "Document", render: (r) => <Mono>{String(r.document_id)}</Mono> },
                { key: "sha256", header: "SHA-256", render: (r) => <Mono>{String(r.sha256 ?? "").slice(0, 16)}…</Mono> },
                { key: "created_by", header: "By" },
              ]} />
            <Pager p={evidence} />
          </>
        )}

        {tab === "dispositions" && (
          <>
            <ErrorBanner error={dispositions.error} />
            <Table rows={dispositions.rows} empty="No dispositions proposed."
              columns={[
                { key: "decided_at", header: "Proposed", render: (r) => <DateTime value={r.decided_at as string} /> },
                { key: "disposition", header: "Disposition", render: (r) => String(r.disposition ?? "").replaceAll("_", " ") },
                { key: "rationale", header: "Rationale" },
                { key: "decided_by", header: "Maker" },
                { key: "approved_by", header: "Checker" },
                { key: "approval_comment", header: "Comment" },
                { key: "approval_status", header: "Status", render: (r) => <Badge value={String(r.approval_status)} /> },
              ]} />
            <Pager p={dispositions} />
          </>
        )}

        {tab === "sar" && (
          <>
            <div className="mb-4"><Action label="New report" variant="secondary" path="/v1/risk/sar-reports" body={{ aml_case_id: id }}
              invalidate={inv} title="Draft suspicious activity report"
              fields={[
                { name: "report_type", label: "Report type", required: true, options: SAR_TYPES },
                { name: "jurisdiction", label: "Jurisdiction (ISO)", required: true },
                { name: "regulator", label: "Regulator / FIU", required: true },
                { name: "suspicious_amount", label: "Suspicious amount", type: "number" },
                { name: "currency", label: "Currency" },
                { name: "filing_deadline", label: "Filing deadline", type: "date" },
                { name: "narrative", label: "Narrative", type: "textarea" },
              ]} /></div>
            <ErrorBanner error={sars.error} />
            <Table rows={sars.rows} empty="No regulatory reports for this case."
              columns={[
                { key: "report_type", header: "Type", render: (r) => <span className="font-medium">{String(r.report_type)}</span> },
                { key: "regulator", header: "Regulator", render: (r) => `${String(r.regulator ?? "—")} (${String(r.jurisdiction ?? "—")})` },
                { key: "suspicious_amount", header: "Amount", align: "right",
                  render: (r) => <Money amount={r.suspicious_amount as string} currency={r.currency as string} /> },
                { key: "filing_deadline", header: "Deadline" },
                { key: "regulator_reference", header: "FIU ref" },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
                { key: "actions", header: "", render: (r) => <SarActions sar={r} onExport={() => exportSar.mutate(r)} /> },
              ]} />
            <Pager p={sars} />
          </>
        )}
      </Card>
    </>
  );
}

/** SAR lifecycle: DRAFT/REJECTED -> PENDING_APPROVAL -> APPROVED -> SUBMITTED -> ACKNOWLEDGED | REJECTED. */
function SarActions({ sar, onExport }: { sar: Row; onExport: () => void }) {
  const s = String(sar.status);
  const base = `/v1/risk/sar-reports/${sar.id}`;
  const inv = [["aml-cases"]];
  return (
    <span className="flex flex-wrap gap-1">
      {["DRAFT", "REJECTED"].includes(s) && <>
        <Action size="sm" label="Draft narrative" variant="secondary" path={`${base}/draft-narrative`} invalidate={inv}
          description="Generates a narrative draft from the case facts into the report's draft field." />
        <Action size="sm" label="Submit for approval" path={`${base}/submit-for-approval`} invalidate={inv}
          fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
      </>}
      {s === "PENDING_APPROVAL" && (
        <Action size="sm" label="Approve (MLRO)" path={`${base}/approve`} invalidate={inv}
          fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
      )}
      {s === "APPROVED" && (
        <Action size="sm" label="File with FIU" variant="danger" path={`${base}/file`} invalidate={inv}
          description="Files the report with the financial intelligence unit. This cannot be undone."
          fields={[{ name: "channel", label: "Channel", required: true, options: ["API", "EXPORT", "PORTAL"] }]} />
      )}
      {s === "SUBMITTED" && <>
        <Action size="sm" label="Record acknowledgement" path={`${base}/acknowledge`} invalidate={inv}
          fields={[{ name: "regulator_reference", label: "FIU reference", required: true }]} />
        <Action size="sm" label="Record rejection" variant="danger" path={`${base}/record-rejection`} invalidate={inv}
          fields={[{ name: "rejection_reason", label: "Rejection reason", type: "textarea", required: true }]} />
      </>}
      {["APPROVED", "SUBMITTED", "ACKNOWLEDGED", "REJECTED"].includes(s) && (
        <Button variant="ghost" className="px-2.5 py-1 text-xs" onClick={onExport}>goAML XML</Button>
      )}
    </span>
  );
}

export default function Page() {
  return <Suspense><CaseDetail /></Suspense>;
}
