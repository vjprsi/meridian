"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, Tabs, dateCell, qs, usePaged, type Row } from "@/components/ops";
import type { Page } from "@/lib/types";
import { INV, SubmissionActions } from "./_shared";

type Tab = "runs" | "submissions";
const TABS = [{ id: "runs", label: "Report runs" }, { id: "submissions", label: "Submissions" }] as const;

function useDefinitions() {
  return useQuery({
    queryKey: ["reg-reporting", "definitions"],
    queryFn: () => call<Page<Row>>("GET", `/v1/reporting/definitions${qs({ limit: 200 })}`),
  });
}

function NewRun({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const qc = useQueryClient();
  const defs = useDefinitions();
  const [form, setForm] = useState({ report_definition_id: "", legal_entity_id: "", as_of_date: "", period_start: "", period_end: "" });
  const create = useMutation({
    mutationFn: async () => {
      const body = Object.fromEntries(Object.entries(form).filter(([, v]) => v !== ""));
      const run = await call<Row>("POST", "/v1/reporting/runs", { ...body, trigger: "MANUAL" });
      return run;
    },
    onSuccess: (r) => { INV.forEach((k) => qc.invalidateQueries({ queryKey: k })); onClose(); router.push(`/reporting/regulatory/${r.id}`); },
  });
  const active = (defs.data?.data ?? []).filter((d) => d.status === "ACTIVE");
  return (
    <Card title="New report run" className="mb-6">
      <ErrorBanner error={defs.error ?? create.error} />
      <form className="grid grid-cols-1 gap-4 md:grid-cols-3" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <Field label="Report">
          <select required value={form.report_definition_id} onChange={(e) => setForm({ ...form, report_definition_id: e.target.value })}
            className="rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm outline-none focus:border-accent">
            <option value="">{defs.isLoading ? "Loading…" : "Select a report"}</option>
            {active.map((d) => (
              <option key={String(d.id)} value={String(d.id)}>{String(d.code)} · {String(d.name)} ({String(d.regulator)}, {String(d.frequency)})</option>
            ))}
          </select>
        </Field>
        <Field label="Legal entity ID"><Input required value={form.legal_entity_id}
          onChange={(e) => setForm({ ...form, legal_entity_id: e.target.value })} /></Field>
        <Field label="As-of date"><Input required type="date" value={form.as_of_date}
          onChange={(e) => setForm({ ...form, as_of_date: e.target.value })} /></Field>
        <Field label="Period start"><Input type="date" value={form.period_start}
          onChange={(e) => setForm({ ...form, period_start: e.target.value })} /></Field>
        <Field label="Period end"><Input type="date" value={form.period_end}
          onChange={(e) => setForm({ ...form, period_end: e.target.value })} /></Field>
        <div className="flex items-end"><Button type="submit" disabled={create.isPending}>Create run</Button></div>
      </form>
    </Card>
  );
}

function Runs() {
  const router = useRouter();
  const defs = useDefinitions();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("reg-reporting", "/v1/reporting/runs", filters);
  const names = new Map((defs.data?.data ?? []).map((d) => [String(d.id), `${d.code} · ${d.name}`]));
  return (
    <>
      <Filters value={filters} onChange={setFilters} specs={[
        { name: "status", label: "Status", options: ["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIALLY_SUCCEEDED"] },
        { name: "trigger", label: "Trigger", options: ["SCHEDULE", "MANUAL", "API", "RESUBMISSION"] },
        { name: "as_of_date", label: "As of", type: "date" },
        { name: "legal_entity_id", label: "Legal entity ID" },
      ]} />
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No report runs." onRow={(r) => router.push(`/reporting/regulatory/${r.id}`)}
          columns={[
            { key: "report", header: "Report", render: (r) => <span className="font-medium">
              {names.get(String(r.report_definition_id)) ?? <Mono>{String(r.report_definition_id)}</Mono>}</span> },
            { key: "as_of_date", header: "As of" },
            { key: "revision", header: "Rev", align: "right" },
            { key: "trigger", header: "Trigger" },
            { key: "counts", header: "Errors / warnings", align: "right", render: (r) => (
              <span className="num"><span className={Number(r.error_count) > 0 ? "text-bad" : ""}>{String(r.error_count ?? 0)}</span>
                {" / "}<span className={Number(r.warning_count) > 0 ? "text-warn" : ""}>{String(r.warning_count ?? 0)}</span></span>) },
            { key: "finished_at", header: "Finished", render: dateCell("finished_at") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function Submissions() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("reg-reporting", "/v1/reporting/submissions", filters);
  return (
    <>
      <Filters value={filters} onChange={setFilters} specs={[
        { name: "status", label: "Status", options: ["PENDING_APPROVAL", "APPROVED", "SUBMITTED", "ACKNOWLEDGED", "ACCEPTED", "REJECTED", "RESUBMISSION_REQUIRED"] },
        { name: "channel", label: "Channel", options: ["API", "SFTP", "PORTAL_UPLOAD", "EMAIL", "MANUAL"] },
      ]} />
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No submissions."
          columns={[
            { key: "report_run_id", header: "Run", render: (r) => (
              <button className="font-mono text-xs text-accent hover:underline" onClick={() => router.push(`/reporting/regulatory/${r.report_run_id}`)}>
                {String(r.report_run_id).slice(0, 8)}…</button>) },
            { key: "channel", header: "Channel" },
            { key: "deadline_at", header: "Deadline", render: dateCell("deadline_at") },
            { key: "submitted_at", header: "Submitted", render: dateCell("submitted_at") },
            { key: "regulator_reference", header: "Regulator ref", render: (r) => String(r.regulator_reference ?? r.rejection_reason ?? "—") },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "actions", header: "", render: (r) => <SubmissionActions s={r} /> },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function Regulatory() {
  const router = useRouter();
  const params = useSearchParams();
  const tab: Tab = params.get("tab") === "submissions" ? "submissions" : "runs";
  const [open, setOpen] = useState(false);
  return (
    <>
      <PageHeader title="Regulatory reports" subtitle="Report runs, validation and regulator submissions (maker-checker)"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "New run"}</Button>} />
      {open && <NewRun onClose={() => setOpen(false)} />}
      <Card>
        <Tabs tabs={TABS} value={tab} onChange={(t) => router.replace(`/reporting/regulatory?tab=${t}`)} />
        {tab === "runs" ? <Runs /> : <Submissions />}
      </Card>
    </>
  );
}

export default function PageRoot() {
  return <Suspense><Regulatory /></Suspense>;
}
