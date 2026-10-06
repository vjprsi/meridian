"use client";

import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Money, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, Stat, Tabs, amountCell, usePaged, type ActionField, type Row } from "@/components/ops";

/** Ratios are fractions (1.0 = 100%); show them as percentages without float arithmetic beyond display. */
function pct(v: unknown): string {
  if (v === undefined || v === null || v === "") return "—";
  return `${(Number(v) * 100).toFixed(1)}%`;
}

function latest(rows: Row[]): Row | undefined {
  return [...rows].sort((a, b) => String(b.as_of_date ?? "").localeCompare(String(a.as_of_date ?? ""))
    || String(b.created_at ?? "").localeCompare(String(a.created_at ?? "")))[0];
}

const STATUS = ["DRAFT", "CALCULATED", "REVIEWED", "APPROVED", "SUBMITTED"] as const;

/** Calculation inputs: entity, date, currency, minimum and the position lines as JSON. */
function calcFields(example: string): ActionField[] {
  return [
    { name: "legal_entity_id", label: "Legal entity ID", required: true },
    { name: "as_of_date", label: "As-of date", required: true, type: "date" },
    { name: "currency", label: "Currency", required: true, placeholder: "EUR" },
    { name: "regulatory_minimum", label: "Regulatory minimum", type: "number", hint: "Fraction, default 1.0 (100%)" },
    { name: "positions", label: "Position lines (JSON array)", required: true, type: "textarea", placeholder: example },
  ];
}

function calcBody(v: Record<string, string>) {
  let positions: unknown;
  try {
    positions = JSON.parse(v.positions);
  } catch {
    throw new Error("Position lines must be a valid JSON array");
  }
  if (!Array.isArray(positions)) throw new Error("Position lines must be a JSON array");
  return { legal_entity_id: v.legal_entity_id, as_of_date: v.as_of_date, currency: v.currency.toUpperCase(),
    regulatory_minimum: v.regulatory_minimum || undefined, positions };
}

const LCR_EXAMPLE = '[{"line":"1","code":"L1_CASH","category":"HQLA","amount":"1000000","factor":"1"}]';
const NSFR_EXAMPLE = '[{"line":"1","code":"CET1","side":"ASF","amount":"1000000","factor":"1"}]';

function RatioHistory({ kind, filters }: { kind: "lcr" | "nsfr"; filters: Record<string, string> }) {
  const [status, setStatus] = useState<Record<string, string>>({});
  const list = usePaged("liquidity", `/v1/treasury/liquidity/${kind}`, { ...filters, ...status });
  const ratioKey = kind === "lcr" ? "lcr_ratio" : "nsfr_ratio";
  return (
    <>
      <Filters specs={[{ name: "status", label: "Status", options: STATUS }, { name: "as_of_date", label: "As of", type: "date" }]}
        value={status} onChange={setStatus} />
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty={`No ${kind.toUpperCase()} calculations.`}
          columns={[
            { key: "as_of_date", header: "As of" },
            { key: "currency", header: "Ccy" },
            ...(kind === "lcr" ? [
              { key: "total_hqla", header: "HQLA", align: "right" as const, render: amountCell("total_hqla") },
              { key: "net_outflows", header: "Net outflows", align: "right" as const, render: amountCell("net_outflows") },
            ] : [
              { key: "available_stable_funding", header: "ASF", align: "right" as const, render: amountCell("available_stable_funding") },
              { key: "required_stable_funding", header: "RSF", align: "right" as const, render: amountCell("required_stable_funding") },
            ]),
            { key: ratioKey, header: "Ratio", align: "right", render: (r) => (
              <span className={r.breach ? "font-semibold text-bad" : "font-medium"}>{pct(r[ratioKey])}</span>) },
            { key: "regulatory_minimum", header: "Min", align: "right", render: (r) => pct(r.regulatory_minimum) },
            { key: "status", header: "Status", render: (r) => <span className="flex gap-1"><Badge value={String(r.status)} />
              {r.breach ? <Badge value="BREACH" tone="bad" /> : null}</span> },
            { key: "approved_by", header: "Approved by" },
            { key: "actions", header: "", render: (r) => (r.status === "CALCULATED" || r.status === "REVIEWED") ? (
              <Action size="sm" label="Approve" path={`/v1/treasury/liquidity/${kind}/${r.id}/approve`}
                fields={[{ name: "comment", label: "Comment" }]} invalidate={[["liquidity"]]}
                description="Approve this calculation for regulatory reporting. You cannot approve a calculation you ran." />
            ) : null },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function CashPositions({ filters }: { filters: Record<string, string> }) {
  const [f, setF] = useState<Record<string, string>>({});
  const list = usePaged("liquidity", "/v1/treasury/liquidity/cash-positions", { ...filters, ...f });
  return (
    <>
      <Filters specs={[{ name: "position_type", label: "Type", options: ["ACTUAL", "PROJECTED", "FORECAST"] },
        { name: "position_date", label: "Date", type: "date" }]} value={f} onChange={setF} />
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No cash positions."
          columns={[
            { key: "position_date", header: "Date" },
            { key: "position_type", header: "Type" },
            { key: "currency", header: "Ccy" },
            { key: "opening_balance", header: "Opening", align: "right", render: amountCell("opening_balance") },
            { key: "expected_inflows", header: "Inflows", align: "right", render: amountCell("expected_inflows") },
            { key: "expected_outflows", header: "Outflows", align: "right", render: amountCell("expected_outflows") },
            { key: "closing_balance", header: "Closing", align: "right", render: amountCell("closing_balance") },
            { key: "shortfall", header: "Shortfall", align: "right", render: (r) => r.shortfall && Number(r.shortfall) > 0
              ? <span className="text-bad"><Money amount={r.shortfall as string} currency={r.currency as string} /></span> : "—" },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

function StressResults({ filters }: { filters: Record<string, string> }) {
  const list = usePaged("liquidity", "/v1/treasury/liquidity/stress-results", { legal_entity_id: filters.legal_entity_id });
  return (
    <>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No stress test results."
          columns={[
            { key: "as_of_date", header: "As of" },
            { key: "liquidity_stress_scenario_id", header: "Scenario", render: (r) => <span className="font-mono text-xs">{String(r.liquidity_stress_scenario_id).slice(0, 8)}…</span> },
            { key: "stressed_outflows", header: "Stressed outflows", align: "right", render: amountCell("stressed_outflows") },
            { key: "stressed_inflows", header: "Stressed inflows", align: "right", render: amountCell("stressed_inflows") },
            { key: "counterbalancing_capacity", header: "CBC", align: "right", render: amountCell("counterbalancing_capacity") },
            { key: "net_position", header: "Net", align: "right", render: amountCell("net_position") },
            { key: "survival_days", header: "Survival days", align: "right" },
            { key: "breach", header: "", render: (r) => r.breach ? <Badge value="BREACH" tone="bad" /> : <Badge value="OK" tone="good" /> },
          ]} />
      )}
      <Pager p={list} />
    </>
  );
}

const TABS = [
  { id: "lcr", label: "LCR" },
  { id: "nsfr", label: "NSFR" },
  { id: "cash", label: "Cash positions" },
  { id: "stress", label: "Stress tests" },
] as const;

function Liquidity() {
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [tab, setTab] = useState<(typeof TABS)[number]["id"]>("lcr");
  const lcr = usePaged("liquidity", "/v1/treasury/liquidity/lcr", filters, { limit: 50 });
  const nsfr = usePaged("liquidity", "/v1/treasury/liquidity/nsfr", filters, { limit: 50 });
  const l = latest(lcr.rows);
  const n = latest(nsfr.rows);
  return (
    <>
      <PageHeader title="Liquidity" subtitle="Basel III liquidity coverage (LCR) and net stable funding (NSFR), cash positions and stress tests"
        actions={<>
          <Action variant="secondary" label="Project cash positions" path="/v1/treasury/liquidity/cash-positions/project"
            fields={[{ name: "legal_entity_id", label: "Legal entity ID", required: true },
              { name: "from_date", label: "From", required: true, type: "date" },
              { name: "days", label: "Horizon (days)", required: true, type: "number", initial: "30" }]}
            body={(v) => ({ legal_entity_id: v.legal_entity_id, from_date: v.from_date, days: Number(v.days) })}
            description="Recompute projected positions from treasury deal flows and scheduled payments." invalidate={[["liquidity"]]} />
          <Action variant="secondary" label="Calculate NSFR" path="/v1/treasury/liquidity/nsfr/calculate"
            fields={calcFields(NSFR_EXAMPLE)} body={calcBody} invalidate={[["liquidity"]]}
            description="Lines: {line, code, side ASF/RSF, amount, factor}." />
          <Action label="Calculate LCR" path="/v1/treasury/liquidity/lcr/calculate"
            fields={calcFields(LCR_EXAMPLE)} body={calcBody} invalidate={[["liquidity"]]}
            description="Lines: {line, code, category HQLA/OUTFLOW/INFLOW, amount, factor}." />
        </>} />
      <Card className="mb-6">
        <Filters specs={[{ name: "legal_entity_id", label: "Legal entity ID" }, { name: "currency", label: "Currency", placeholder: "EUR" }]}
          value={filters} onChange={setFilters} />
        <ErrorBanner error={lcr.error ?? nsfr.error} />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Stat label="LCR (latest)" value={lcr.isLoading ? "…" : pct(l?.lcr_ratio)} tone={l ? (l.breach ? "bad" : "good") : undefined}
            hint={l ? `${l.as_of_date} · ${l.currency} · min ${pct(l.regulatory_minimum)} · ${l.status}` : "No calculation"} />
          <Stat label="HQLA" value={l ? <Money amount={l.total_hqla as string} currency={l.currency as string} /> : "—"}
            hint={l ? <>net outflows <Money amount={l.net_outflows as string} currency={l.currency as string} /></> : undefined} />
          <Stat label="NSFR (latest)" value={nsfr.isLoading ? "…" : pct(n?.nsfr_ratio)} tone={n ? (n.breach ? "bad" : "good") : undefined}
            hint={n ? `${n.as_of_date} · ${n.currency} · min ${pct(n.regulatory_minimum)} · ${n.status}` : "No calculation"} />
          <Stat label="Stable funding" value={n ? <Money amount={n.available_stable_funding as string} currency={n.currency as string} /> : "—"}
            hint={n ? <>required <Money amount={n.required_stable_funding as string} currency={n.currency as string} /></> : undefined} />
        </div>
      </Card>
      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />
        {tab === "lcr" && <RatioHistory kind="lcr" filters={filters} />}
        {tab === "nsfr" && <RatioHistory kind="nsfr" filters={filters} />}
        {tab === "cash" && <CashPositions filters={filters} />}
        {tab === "stress" && <StressResults filters={filters} />}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><Liquidity /></Suspense>;
}
