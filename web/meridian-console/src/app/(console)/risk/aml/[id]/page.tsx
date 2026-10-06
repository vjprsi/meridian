"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, DateTime, ErrorBanner, Loading, Mono, Money, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Pager, amountCell, usePaged, type Row } from "@/components/ops";
import { CASE_TYPES, Severity } from "../../_shared";

/** Alerts can be worked while OPEN or TRIAGED (the service's WORKABLE set). */
const WORKABLE = ["OPEN", "TRIAGED"];

function AmlAlert() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const alert = useQuery({ queryKey: ["tm-alerts", id], queryFn: () => call<Row>("GET", `/v1/risk/monitoring/alerts/${id}`) });
  const scenarioId = alert.data?.tm_scenario_id as string | undefined;
  const scenario = useQuery({ queryKey: ["tm-scenario", scenarioId], enabled: !!scenarioId, retry: false,
    queryFn: () => call<Row>("GET", `/v1/risk/monitoring/scenarios/${scenarioId}`) });
  const txns = usePaged("tm-alerts", `/v1/risk/monitoring/alerts/${id}/transactions`, {}, { limit: 50 });

  if (alert.isLoading) return <Loading />;
  const a = alert.data;
  const inv = [["tm-alerts"]];
  const workable = a && WORKABLE.includes(String(a.status));

  return (
    <>
      <PageHeader title="Monitoring alert"
        subtitle={a && <><Mono>{String(a.id)}</Mono> · <Severity value={a.severity} /> · <Badge value={String(a.status)} /></>}
        actions={a && workable && <>
          <Action label="Assign" variant="secondary" path={`/v1/risk/monitoring/alerts/${id}/assign`} invalidate={inv}
            fields={[{ name: "assigned_to", label: "Analyst", required: true, initial: String(a.assigned_to ?? "") }]} />
          <Action label="Close — no action" variant="secondary" path={`/v1/risk/monitoring/alerts/${id}/close`} invalidate={inv}
            title="Close without a case" description="Record why the activity is explained and needs no investigation."
            fields={[
              { name: "closed_reason", label: "Closed reason", required: true,
                options: ["EXPLAINED_ACTIVITY", "KNOWN_CUSTOMER_PATTERN", "DATA_QUALITY", "DUPLICATE", "BELOW_RISK_APPETITE", "OTHER"] },
              { name: "triage_note", label: "Triage note", type: "textarea", required: true },
            ]} />
          <Action label="Escalate to case" variant="danger" path={`/v1/risk/monitoring/alerts/${id}/escalate`} invalidate={inv}
            description="Opens a new AML case, or attaches the alert to an existing case if you give its ID."
            onDone={(r) => { const c = r as Row | undefined; if (c?.id) router.push(`/risk/cases/${c.id}`); }}
            fields={[
              { name: "aml_case_id", label: "Existing case ID", placeholder: "leave empty to open a new case" },
              { name: "case_type", label: "Case type", options: CASE_TYPES, initial: "AML" },
              { name: "reason", label: "Reason", type: "textarea", required: true },
            ]} />
        </>} />
      <ErrorBanner error={alert.error} />
      {a && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Card title="Alert" className="lg:col-span-2">
            <Details items={[
              ["Customer", <Link key="c" className="text-accent hover:underline" href={`/customers/${a.customer_id}`}>
                <Mono>{String(a.customer_id)}</Mono></Link>],
              ["Account", a.account_id ? <Mono key="a">{String(a.account_id)}</Mono> : null],
              ["Score", a.score],
              ["Total", <Money key="m" amount={a.total_amount as string} currency={a.currency as string} />],
              ["Transactions", a.transaction_count],
              ["Period start", a.period_start],
              ["Period end", a.period_end],
              ["Assigned to", a.assigned_to],
              ["Raised", a.created_at],
              ["Triage note", a.triage_note],
              ["Closed reason", a.closed_reason],
              ["Closed at", a.closed_at],
              ["Case", a.aml_case_id ? <Link key="k" className="text-accent hover:underline" href={`/risk/cases/${a.aml_case_id}`}>
                Open case</Link> : null],
            ]} />
          </Card>
          <Card title="Scenario">
            <ErrorBanner error={scenario.error} />
            {scenario.data ? (
              <Details cols={1} items={[
                ["Code", scenario.data.code],
                ["Name", scenario.data.name],
                ["Type", String(scenario.data.scenario_type ?? "").replaceAll("_", " ")],
                ["Run mode", scenario.data.run_mode],
                ["Status", <Badge key="s" value={String(scenario.data.status)} />],
              ]} />
            ) : <Mono>{String(a.tm_scenario_id ?? "—")}</Mono>}
          </Card>
          <Card title="Why it fired" className="lg:col-span-3">
            <Json value={a.reason} />
          </Card>
          <Card title="Contributing transactions" className="lg:col-span-3">
            <ErrorBanner error={txns.error} />
            {txns.isLoading ? <Loading /> : (
              <Table rows={txns.rows} empty="No contributing transactions recorded."
                columns={[
                  { key: "occurred_at", header: "When", render: (r) => <DateTime value={r.occurred_at as string} /> },
                  { key: "transaction_type", header: "Type" },
                  { key: "direction", header: "Direction", render: (r) => <Badge value={String(r.direction ?? "—")}
                    tone={r.direction === "CREDIT" ? "good" : "info"} /> },
                  { key: "counterparty_name", header: "Counterparty" },
                  { key: "amount", header: "Amount", align: "right", render: amountCell() },
                  { key: "transaction_id", header: "Transaction", render: (r) => <Mono>{String(r.transaction_id ?? "—")}</Mono> },
                ]} />
            )}
            <Pager p={txns} />
          </Card>
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><AmlAlert /></Suspense>;
}
