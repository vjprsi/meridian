"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Select, Table } from "@/components/ui";
import { Stat, Tabs, qs, str, type Row } from "@/components/ops";

/* ---------------------------------------------------------------------------------------------------------------
 * Operational / MIS reports: run the governed operational saved-queries over the whitelisted analytics datasets,
 * show results in a table and export CSV / JSON. Consumes the real /v1/analytics/* endpoints. The governed
 * catalogue of operational queries + dashboards ships with analytics-service (operational-reports/*.yaml).
 * ------------------------------------------------------------------------------------------------------------- */

type Tab = "queries" | "dashboards";
const TABS = [{ id: "queries", label: "Saved reports" }, { id: "dashboards", label: "Dashboards" }] as const;

interface ParamDef { name: string; type?: string; required?: boolean; default?: unknown }
interface SavedQuery extends Row { id: string; name: string; description?: string; dataset?: string;
  visibility?: string; parameters?: ParamDef[]; last_run_at?: string; last_row_count?: number }
interface RunResult { columns: string[]; rows: Record<string, unknown>[]; row_count: number; matched_rows?: number; truncated?: boolean }
interface ExportJob extends Row { id: string; status: string; format?: string; row_count?: number; error?: string }

function useSavedQueries() {
  return useQuery({
    queryKey: ["ops-reports", "saved-queries"],
    queryFn: () => call<{ data: SavedQuery[] }>("GET", `/v1/analytics/saved-queries${qs({ limit: 200 })}`),
  });
}

/** Inputs for a saved query's declared parameters; returns the typed parameter values object. */
function ParamFields({ defs, values, onChange }:
  { defs: ParamDef[]; values: Record<string, string>; onChange: (v: Record<string, string>) => void }) {
  if (defs.length === 0) return <p className="text-sm text-muted">This report takes no parameters.</p>;
  return (
    <div className="flex flex-wrap items-end gap-3">
      {defs.map((p) => {
        const label = p.name + (p.required ? "" : " (optional)");
        const t = (p.type ?? "STRING").toUpperCase();
        return (
          <Field key={p.name} label={label}>
            {t === "BOOL" ? (
              <Select options={["", "true", "false"]} value={values[p.name] ?? ""}
                onChange={(e) => onChange({ ...values, [p.name]: e.target.value })} className="min-w-36" />
            ) : (
              <Input type={t === "DATE" ? "date" : t === "NUMBER" ? "number" : "text"}
                step={t === "NUMBER" ? "any" : undefined} value={values[p.name] ?? ""}
                onChange={(e) => onChange({ ...values, [p.name]: e.target.value })} className="min-w-40" />
            )}
          </Field>
        );
      })}
    </div>
  );
}

/** Coerce the string inputs into typed parameter values the DSL expects (number / bool / date / string). */
function buildParams(defs: ParamDef[], raw: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of defs) {
    const v = raw[p.name];
    if (v === undefined || v === "") continue;
    const t = (p.type ?? "STRING").toUpperCase();
    out[p.name] = t === "NUMBER" ? Number(v) : t === "BOOL" ? v === "true" : v;
  }
  return out;
}

function ResultTable({ result }: { result: RunResult }) {
  const columns = result.columns.map((c) => ({
    key: c,
    header: c,
    render: (r: Row) => <span className="num">{str(r[c])}</span>,
  }));
  return (
    <>
      <div className="mb-3 flex flex-wrap gap-4 text-xs text-muted">
        <span>{result.row_count} row{result.row_count === 1 ? "" : "s"}</span>
        {result.matched_rows !== undefined && <span>{result.matched_rows} matched</span>}
        {result.truncated && <span className="text-warn">truncated at limit</span>}
      </div>
      <div className="overflow-x-auto">
        <Table rows={result.rows as Row[]} columns={columns} empty="No rows for these parameters." />
      </div>
    </>
  );
}

/** Export panel: triggers an async export job, polls it, then offers the finished file for download. */
function ExportPanel({ query, params }: { query: SavedQuery; params: Record<string, unknown> }) {
  const [job, setJob] = useState<{ id: string; format: "CSV" | "JSON" } | null>(null);
  const start = useMutation({
    mutationFn: (format: "CSV" | "JSON") =>
      call<ExportJob>("POST", `/v1/analytics/saved-queries/${query.id}/export`, { format, parameters: params }),
    onSuccess: (j, format) => setJob({ id: j.id, format }),
  });
  const poll = useQuery({
    queryKey: ["ops-reports", "export", job?.id],
    queryFn: () => call<ExportJob>("GET", `/v1/analytics/exports/${job!.id}`),
    enabled: !!job,
    refetchInterval: (q) => {
      const s = (q.state.data as ExportJob | undefined)?.status;
      return s && ["SUCCEEDED", "FAILED", "CANCELLED"].includes(s) ? false : 1500;
    },
  });
  const status = poll.data?.status;
  const ready = status === "SUCCEEDED";
  return (
    <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-border pt-4">
      <span className="text-sm font-medium">Export</span>
      <Button variant="secondary" disabled={start.isPending} onClick={() => start.mutate("CSV")}>Export CSV</Button>
      <Button variant="secondary" disabled={start.isPending} onClick={() => start.mutate("JSON")}>Export JSON</Button>
      {job && (
        <span className="flex items-center gap-2 text-sm">
          <Badge value={status ?? "QUEUED"} />
          {ready ? (
            <Button variant="primary" className="px-2.5 py-1 text-xs"
              onClick={() => download(`/v1/analytics/exports/${job.id}/${job.format.toLowerCase()}`,
                `${query.name.replace(/\s+/g, "_").toLowerCase()}.${job.format.toLowerCase()}`)}>
              Download {job.format}
            </Button>
          ) : status === "FAILED" ? (
            <span className="text-bad">{str(poll.data?.error)}</span>
          ) : (
            <span className="text-muted">preparing…</span>
          )}
        </span>
      )}
      <ErrorBanner error={start.error} />
    </div>
  );
}

function RunPanel({ query }: { query: SavedQuery }) {
  const defs = useMemo(() => query.parameters ?? [], [query]);
  const [raw, setRaw] = useState<Record<string, string>>(() =>
    Object.fromEntries(defs.map((p) => [p.name, p.default === undefined || p.default === null ? "" : String(p.default)])));
  const [limit, setLimit] = useState("100");
  const [params, setParams] = useState<Record<string, unknown>>({});
  const run = useMutation({
    mutationFn: () => {
      const p = buildParams(defs, raw);
      setParams(p);
      return call<RunResult>("POST", `/v1/analytics/saved-queries/${query.id}/run${qs({ limit })}`, { parameters: p });
    },
  });
  return (
    <Card className="mb-6" title={query.name}>
      {query.description && <p className="mb-4 text-sm text-muted">{query.description}</p>}
      <div className="mb-4 flex flex-wrap gap-4 text-xs text-muted">
        {query.dataset && <span>dataset <Mono>{query.dataset}</Mono></span>}
        {query.visibility && <Badge value={query.visibility} />}
      </div>
      <form onSubmit={(e) => { e.preventDefault(); run.mutate(); }} className="space-y-4">
        <ParamFields defs={defs} values={raw} onChange={setRaw} />
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Row limit (1–1000)">
            <Input type="number" min={1} max={1000} value={limit} onChange={(e) => setLimit(e.target.value)} className="min-w-28" />
          </Field>
          <Button type="submit" disabled={run.isPending}>{run.isPending ? "Running…" : "Run report"}</Button>
        </div>
      </form>
      <ErrorBanner error={run.error} />
      {run.data && (
        <div className="mt-5">
          <ResultTable result={run.data} />
          <ExportPanel query={query} params={params} />
        </div>
      )}
    </Card>
  );
}

function Queries() {
  const list = useSavedQueries();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queries = list.data?.data ?? [];
  const selected = queries.find((q) => q.id === selectedId) ?? null;
  return (
    <>
      {selected && <RunPanel key={selected.id} query={selected} />}
      <Card title="Operational reports">
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={queries as Row[]} empty="No saved reports yet." onRow={(r) => setSelectedId(String(r.id))}
            columns={[
              { key: "name", header: "Report", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "dataset", header: "Dataset", render: (r) => <Mono>{str(r.dataset)}</Mono> },
              { key: "visibility", header: "Visibility", render: (r) => <Badge value={str(r.visibility)} /> },
              { key: "last_row_count", header: "Last rows", align: "right", render: (r) => <span className="num">{str(r.last_row_count)}</span> },
              { key: "run", header: "", align: "right", render: (r) => (
                <Button variant="secondary" className="px-2.5 py-1 text-xs" onClick={() => setSelectedId(String(r.id))}>Run</Button>) },
            ]} />
        )}
      </Card>
    </>
  );
}

function Dashboards() {
  const list = useQuery({
    queryKey: ["ops-reports", "dashboards"],
    queryFn: () => call<{ data: Row[] }>("GET", `/v1/analytics/dashboards${qs({ limit: 200 })}`),
  });
  const boards = list.data?.data ?? [];
  const system = boards.filter((b) => b.is_system).length;
  return (
    <>
      <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3">
        <Stat label="Dashboards" value={boards.length} />
        <Stat label="System" value={system} />
      </div>
      <Card title="Dashboards">
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={boards} empty="No dashboards yet."
            columns={[
              { key: "name", header: "Dashboard", render: (r) => <span className="font-medium">{str(r.name)}</span> },
              { key: "description", header: "Description", render: (r) => <span className="text-muted">{str(r.description)}</span> },
              { key: "is_system", header: "System", render: (r) => r.is_system ? <Badge value="SYSTEM" /> : <span className="text-muted">—</span> },
              { key: "refresh_seconds", header: "Refresh (s)", align: "right", render: (r) => <span className="num">{str(r.refresh_seconds)}</span> },
              { key: "tags", header: "Tags", render: (r) => str(Array.isArray(r.tags) ? (r.tags as string[]).join(", ") : r.tags) },
            ]} />
        )}
      </Card>
    </>
  );
}

function Operational() {
  const router = useRouter();
  const params = useSearchParams();
  const tab: Tab = params.get("tab") === "dashboards" ? "dashboards" : "queries";
  return (
    <>
      <PageHeader title="Operational reports"
        subtitle="Run operational / MIS reports over the governed analytics datasets and export CSV / JSON" />
      <Card>
        <Tabs tabs={TABS} value={tab} onChange={(t) => router.replace(`/reporting/operational?tab=${t}`)} />
        {tab === "queries" ? <Queries /> : <Dashboards />}
      </Card>
    </>
  );
}

export default function PageRoot() {
  return <Suspense><Operational /></Suspense>;
}
