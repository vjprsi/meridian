"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Details, Json, Stat, rows, str, type Row } from "@/components/ops";

// WE-RECON-1 (G-10, D-ED-12): one safeguarding reconciliation run — the daily proof that the trust / e-money
// account fully backs outstanding liabilities, A − (B+C+D+E), assessed across every active safeguarding config.
function money(r: Record<string, unknown>, k: string) {
  const v = r[k];
  return v === null || v === undefined || v === "" ? "—" : String(v);
}

function SafeguardingDetail() {
  const { id } = useParams<{ id: string }>();
  const q = useQuery({ queryKey: ["safeguarding-runs", id], queryFn: () => call<Row>("GET", `/v1/reconciliation/safeguarding-runs/${id}`) });

  if (q.isLoading) return <Loading />;
  const r = q.data ?? {};
  const results = rows(r, "results");
  const shortfalls = rows(r, "shortfalls");
  const backed = !!r.backed;
  const alarm = !!r.alarm_raised;

  return (
    <>
      <PageHeader title="Safeguarding run"
        subtitle={<>As-of <Mono>{str(r.business_date)}</Mono> · {str(r.currency)} · <Badge value={str(r.status)} /></>} />

      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Trust balance (A)" value={money(r, "trust_balance")} />
        <Stat label="Liabilities (B+C+D+E)" value={money(r, "liability_total")} />
        <Stat label="Difference" value={money(r, "difference")} tone={backed ? "good" : "bad"} />
        <Stat label="Safeguarding" value={backed ? "Backed" : "Unbacked"} tone={backed ? "good" : "bad"}
          hint={alarm ? "Shortfall alarm raised" : undefined} />
      </div>

      <Card title="Run" className="mb-6">
        <Details items={[
          ["Business date", <Mono key="d">{str(r.business_date)}</Mono>],
          ["Formula", r.formula],
          ["Currency", r.currency],
          ["Configs assessed", r.configs],
          ["Open breaks", r.breaks_open],
          ["Balanced", r.balanced],
          ["Backed", r.backed],
          ["Alarm raised", r.alarm_raised],
          ["Started", r.started_at],
          ["Finished", r.finished_at],
          ["Status", <Badge key="s" value={str(r.status)} />],
        ]} />
      </Card>

      <Card title="Per-config results" className="mb-6">
        <Table rows={results} empty="No per-config breakdown recorded."
          columns={[
            { key: "label", header: "Config", render: (x) => <span className="font-medium">{str(x.label ?? x.role)}</span> },
            { key: "trust_balance", header: "Trust (A)", align: "right", render: (x) => <Mono>{money(x, "trust_balance")}</Mono> },
            { key: "liability_total", header: "Liabilities", align: "right", render: (x) => <Mono>{money(x, "liability_total")}</Mono> },
            { key: "difference", header: "Difference", align: "right", render: (x) => <Mono>{money(x, "difference")}</Mono> },
            { key: "status", header: "Status", render: (x) => <Badge value={str(x.status)} /> },
          ]} />
      </Card>

      {shortfalls.length > 0 && (
        <Card title="Shortfalls" className="mb-6">
          <Json value={r.shortfalls} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><SafeguardingDetail /></Suspense>;
}
