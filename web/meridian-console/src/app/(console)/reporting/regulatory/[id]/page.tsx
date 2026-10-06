"use client";

import { useParams, useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Pager, Tabs, dateCell, qs, usePaged, type Row } from "@/components/ops";
import { INV, SubmissionActions, downloadOutput, outputPath } from "../_shared";

const TABS = [
  { id: "validation", label: "Validation" },
  { id: "outputs", label: "Outputs" },
  { id: "cells", label: "Cells" },
  { id: "adjustments", label: "Adjustments" },
  { id: "submissions", label: "Submissions" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function Validation({ id }: { id: string }) {
  const list = usePaged("reg-reporting", `/v1/reporting/runs/${id}/validation-results`, {}, { limit: 50 });
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No validation findings."
          columns={[
            { key: "severity", header: "Severity", render: (r) => <Badge value={String(r.severity)}
              tone={r.severity === "ERROR" ? "bad" : r.severity === "WARNING" ? "warn" : "muted"} /> },
            { key: "rule_code", header: "Rule", render: (r) => <Mono>{String(r.rule_code)}</Mono> },
            { key: "message", header: "Message" },
            { key: "cell_ref", header: "Cell" },
            { key: "expected_value", header: "Expected", align: "right" },
            { key: "actual_value", header: "Actual", align: "right" },
            { key: "revision", header: "Rev", align: "right" },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function Outputs({ id, runStatus }: { id: string; runStatus: string }) {
  const list = usePaged("reg-reporting", `/v1/reporting/runs/${id}/outputs`);
  const [err, setErr] = useState<unknown>(null);
  return (
    <>
      <ErrorBanner error={list.error ?? err} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No files produced yet."
          columns={[
            { key: "file_name", header: "File", render: (r) => <span className="font-medium">{String(r.file_name)}</span> },
            { key: "format", header: "Format" },
            { key: "revision", header: "Rev", align: "right" },
            { key: "record_count", header: "Records", align: "right" },
            { key: "size_bytes", header: "Size", align: "right", render: (r) => `${(Number(r.size_bytes ?? 0) / 1024).toFixed(1)} KB` },
            { key: "sha256", header: "SHA-256", render: (r) => <Mono>{String(r.sha256 ?? "").slice(0, 12)}</Mono> },
            { key: "actions", header: "", render: (r) => (
              <div className="flex justify-end gap-1">
                {outputPath(r) && (
                  <Button variant="ghost" className="px-2 py-1 text-xs"
                    onClick={() => { setErr(null); downloadOutput(r).catch(setErr); }}>Download</Button>
                )}
                {(runStatus === "SUCCEEDED" || runStatus === "PARTIALLY_SUCCEEDED") && (
                  <Action size="sm" label="Create submission" path="/v1/reporting/submissions"
                    body={(v) => ({ report_run_id: id, report_output_id: r.id, channel: v.channel,
                      deadline_at: v.deadline_at ? new Date(v.deadline_at).toISOString() : undefined })}
                    fields={[{ name: "channel", label: "Channel", required: true, options: ["API", "SFTP", "PORTAL_UPLOAD", "EMAIL", "MANUAL"] },
                      { name: "deadline_at", label: "Filing deadline", type: "datetime-local" }]}
                    description={`Prepare ${String(r.file_name)} for filing. Another user must approve it before it can be submitted.`}
                    invalidate={INV} />
                )}
              </div>
            ) },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function Cells({ id }: { id: string }) {
  const list = usePaged("reg-reporting", `/v1/reporting/runs/${id}/cells`, {}, { limit: 50 });
  const [cell, setCell] = useState<string | null>(null);
  const drill = useQuery({
    queryKey: ["reg-reporting", "drilldown", id, cell],
    queryFn: () => call<Row>("GET", `/v1/reporting/runs/${id}/drilldown${qs({ cell_ref: cell })}`),
    enabled: !!cell,
  });
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No computed cells." onRow={(r) => setCell(String(r.cell_ref))}
          columns={[
            { key: "cell_ref", header: "Cell", render: (r) => <Mono>{String(r.cell_ref)}</Mono> },
            { key: "label", header: "Label" },
            { key: "value", header: "Value", align: "right", render: (r) => <span className="num">{String(r.value ?? "—")}</span> },
            { key: "computed_value", header: "Computed", align: "right" },
            { key: "is_adjusted", header: "Adjusted", render: (r) => r.is_adjusted ? <Badge value="ADJUSTED" tone="warn" /> : "—" },
            { key: "source_count", header: "Sources", align: "right" },
            { key: "actions", header: "", render: (r) => (
              <span onClick={(e) => e.stopPropagation()}>
                <Action size="sm" variant="secondary" label="Adjust" title={`Adjust cell ${String(r.cell_ref)}`}
                  path={`/v1/reporting/runs/${id}/adjustments`} body={{ cell_ref: r.cell_ref }}
                  fields={[{ name: "adjusted_value", label: "Adjusted value", required: true, initial: String(r.value ?? "") },
                    { name: "reason", label: "Reason", required: true, type: "textarea" }]}
                  description="Manual adjustments need a checker's approval; the run is then re-validated and re-rendered as a new revision."
                  invalidate={INV} />
              </span>
            ) },
          ]} />
      )}
      <Pager p={list} />
      {cell && (
        <div className="mt-4 border-t border-border pt-4">
          <div className="mb-2 flex items-center justify-between">
            <p className="text-sm font-medium">Drill-down · <Mono>{cell}</Mono></p>
            <Button variant="ghost" className="px-2 py-1 text-xs" onClick={() => setCell(null)}>Close</Button>
          </div>
          <ErrorBanner error={drill.error} />
          {drill.isLoading ? <Loading /> : <Json value={drill.data} />}
        </div>
      )}
    </>
  );
}

function Adjustments({ id }: { id: string }) {
  const list = usePaged("reg-reporting", `/v1/reporting/runs/${id}/adjustments`);
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No manual adjustments."
          columns={[
            { key: "cell_ref", header: "Cell", render: (r) => <Mono>{String(r.cell_ref)}</Mono> },
            { key: "original_value", header: "Original", align: "right" },
            { key: "adjusted_value", header: "Adjusted", align: "right" },
            { key: "reason", header: "Reason" },
            { key: "adjusted_by", header: "By" },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => r.status === "PENDING_APPROVAL" ? (
              <div className="flex justify-end gap-1">
                <Action size="sm" label="Approve" path={`/v1/reporting/adjustments/${r.id}/approve`}
                  fields={[{ name: "comment", label: "Comment" }]} invalidate={INV}
                  description="Approving re-validates and re-renders the run as a new revision. You cannot approve your own adjustment." />
                <Action size="sm" variant="danger" label="Reject" path={`/v1/reporting/adjustments/${r.id}/reject`}
                  fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} invalidate={INV} />
              </div>
            ) : String(r.decision_comment ?? "") },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function RunSubmissions({ id }: { id: string }) {
  const list = usePaged("reg-reporting", "/v1/reporting/submissions", { report_run_id: id });
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="Not submitted yet. Create a submission from an output."
          columns={[
            { key: "channel", header: "Channel" },
            { key: "deadline_at", header: "Deadline", render: dateCell("deadline_at") },
            { key: "approved_by", header: "Approved by" },
            { key: "submitted_at", header: "Submitted", render: dateCell("submitted_at") },
            { key: "regulator_reference", header: "Regulator ref", render: (r) => String(r.regulator_reference ?? r.rejection_reason ?? r.gateway_message ?? "—") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => <SubmissionActions s={r} /> },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function RunDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("validation");
  const run = useQuery({ queryKey: ["reg-reporting", "run", id], queryFn: () => call<Row>("GET", `/v1/reporting/runs/${id}`) });
  const r = run.data;
  const def = useQuery({
    queryKey: ["reg-reporting", "definition", r?.report_definition_id],
    queryFn: () => call<Row>("GET", `/v1/reporting/definitions/${r!.report_definition_id}`),
    enabled: !!r?.report_definition_id,
  });
  if (run.isLoading) return <Loading />;
  const status = String(r?.status ?? "");
  const d = def.data;
  return (
    <>
      <PageHeader title={d ? `${d.code} · ${d.name}` : "Report run"}
        subtitle={r && <><Mono>{String(r.id)}</Mono> · as of {String(r.as_of_date)} · revision {String(r.revision)} · <Badge value={status} /></>}
        actions={r && <>
          {status === "QUEUED" && (
            <Action label="Execute" path={`/v1/reporting/runs/${id}/execute`} invalidate={INV}
              description="Collect source data, transform, validate and render the report now." />
          )}
          {["SUCCEEDED", "FAILED", "PARTIALLY_SUCCEEDED"].includes(status) && !r.superseded_by_run_id && (
            <Action variant="secondary" label="Re-run" title="Produce a corrected run"
              path={`/v1/reporting/runs/${id}/rerun`} fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
              description="Creates a new run that supersedes this one (resubmission)."
              invalidate={INV} onDone={(n) => { const nr = n as Row | undefined; if (nr?.id) router.push(`/reporting/regulatory/${nr.id}`); }} />
          )}
          {(status === "QUEUED" || status === "RUNNING") && (
            <Action variant="danger" label="Cancel run" path={`/v1/reporting/runs/${id}/cancel`}
              fields={[{ name: "reason", label: "Reason", type: "textarea" }]} invalidate={INV} />
          )}
        </>} />
      <ErrorBanner error={run.error} />
      {r?.error ? <ErrorBanner error={new Error(String(r.error))} /> : null}
      {r && (
        <Card title="Run" className="mb-6">
          <Details items={[
            ["Regulator", d ? `${d.regulator} (${d.jurisdiction})` : null], ["Format", d?.format],
            ["Period", r.period_start ? `${r.period_start} → ${r.period_end ?? ""}` : null], ["Trigger", r.trigger],
            ["Legal entity", <Mono key="le">{String(r.legal_entity_id)}</Mono>], ["Rows", r.row_count],
            ["Errors", r.error_count], ["Warnings", r.warning_count],
            ["Started", r.started_at], ["Finished", r.finished_at],
            ["Supersedes", r.supersedes_run_id ? <a key="s" className="text-accent hover:underline" href={`/reporting/regulatory/${r.supersedes_run_id}`}><Mono>{String(r.supersedes_run_id)}</Mono></a> : null],
            ["Superseded by", r.superseded_by_run_id ? <a key="sb" className="text-accent hover:underline" href={`/reporting/regulatory/${r.superseded_by_run_id}`}><Mono>{String(r.superseded_by_run_id)}</Mono></a> : null],
            ["Created by", r.created_by], ["Created", r.created_at],
          ]} />
        </Card>
      )}
      {r && (
        <Card>
          <Tabs tabs={TABS} value={tab} onChange={setTab} />
          {tab === "validation" && <Validation id={id} />}
          {tab === "outputs" && <Outputs id={id} runStatus={status} />}
          {tab === "cells" && <Cells id={id} />}
          {tab === "adjustments" && <Adjustments id={id} />}
          {tab === "submissions" && <RunSubmissions id={id} />}
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><RunDetail /></Suspense>;
}
