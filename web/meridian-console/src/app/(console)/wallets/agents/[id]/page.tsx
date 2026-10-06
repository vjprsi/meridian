"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, dateCell, str, usePaged, type Row } from "@/components/ops";

const REASON = [{ name: "reason", label: "Reason", required: true, type: "textarea" as const }];

function AgentDetail() {
  const { id } = useParams<{ id: string }>();
  const agent = useQuery({ queryKey: ["agent", id], queryFn: () => call<Row>("GET", `/v1/agents/${id}`) });
  const floats = usePaged("agent-floats", `/v1/agents/${id}/float`);
  const outlets = usePaged("agent-outlets", `/v1/agents/${id}/outlets`);
  const subs = usePaged("agents", `/v1/agents/${id}/sub-agents`);

  if (agent.isLoading) return <Loading />;
  const a = agent.data;
  const s = String(a?.status ?? "");
  const inv = [["agent", id], ["agents"]];
  const walletLink = (v: unknown) => v ? <Link className="text-accent hover:underline" href={`/wallets/${v}`}><Mono>{String(v)}</Mono></Link> : null;

  return (
    <>
      <PageHeader title={str(a?.trading_name ?? a?.legal_name ?? "Agent")}
        subtitle={a && <><Mono>{str(a.agent_code)}</Mono> · {str(a.agent_level)} · {str(a.currency)} · <Badge value={s} /></>}
        actions={a && <>
          {(s === "ONBOARDING" || s === "SUSPENDED") && (
            <Action label="Activate" path={`/v1/agents/${id}/activate`} invalidate={inv}
              description="Checker action (different from the creator); needs at least one verified KYB document. Opens float and commission wallets on first activation."
              fields={[{ name: "comment", label: "Comment" }]} />
          )}
          {s === "ACTIVE" && (
            <Action label="Suspend" variant="secondary" path={`/v1/agents/${id}/suspend`} invalidate={inv} fields={REASON}
              description="Suspends the agent and its wallets." />
          )}
          {s !== "TERMINATED" && (
            <Action label="Terminate" variant="danger" path={`/v1/agents/${id}/terminate`} invalidate={inv}
              description="Terminates the agency contract. Requires no active sub-agents and zero float."
              fields={[...REASON, { name: "effective_date", label: "Effective date", type: "date" }]} />
          )}
          {s !== "TERMINATED" && (
            <Action label="Assign commission plan" variant="secondary" path={`/v1/agents/${id}/assign-commission-plan`} invalidate={inv}
              fields={[{ name: "commission_plan_id", label: "Commission plan ID", required: true },
                { name: "effective_from", label: "Effective from", type: "date", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={agent.error} />
      {a && (
        <Card title="Agent" className="mb-6">
          <Details items={[
            ["Legal name", a.legal_name],
            ["Registration no.", a.registration_number],
            ["Contact", a.contact_name],
            ["MSISDN", a.contact_msisdn],
            ["Email", a.contact_email],
            ["Parent agent", a.parent_agent_id ? <Link className="text-accent hover:underline" href={`/wallets/agents/${a.parent_agent_id}`}><Mono>{String(a.parent_agent_id)}</Mono></Link> : null],
            ["Float wallet", walletLink(a.float_wallet_id)],
            ["Commission wallet", walletLink(a.commission_wallet_id)],
            ["Commission plan", a.commission_plan_id],
            ["Max float", a.max_float_amount ? <Money amount={String(a.max_float_amount)} currency={a.currency as string} /> : null],
            ["KYC status", a.kyc_status],
            ["Risk rating", a.risk_rating],
            ["Contract", a.contract_start ? `${str(a.contract_start)} → ${str(a.contract_end)}` : null],
            ["Onboarded", a.onboarded_at],
            ["Status reason", a.status_reason],
          ]} />
        </Card>
      )}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card title="Float positions" className="lg:col-span-2">
          <ErrorBanner error={floats.error} />
          {floats.isLoading ? <Loading /> : (
            <Table rows={floats.rows} empty="No float positions."
              columns={[
                { key: "wallet_id", header: "Wallet", render: (r) => walletLink(r.wallet_id) },
                { key: "last_balance", header: "Last balance", align: "right", render: (r) => <Money amount={r.last_balance as string} currency={r.currency as string} /> },
                { key: "low_float_alert_threshold", header: "Low-float alert", align: "right",
                  render: (r) => <Money amount={r.low_float_alert_threshold as string} currency={r.currency as string} /> },
                { key: "auto_rebalance", header: "Auto rebalance", render: (r) => (r.auto_rebalance ? "yes" : "no") },
                { key: "last_checked_at", header: "Checked", render: dateCell("last_checked_at") },
                { key: "actions", header: "", render: (r) => (
                  <Action label="Check now" size="sm" variant="secondary" path={`/v1/agent-floats/${r.id}/check`}
                    invalidate={[["agent-floats"]]}
                    description="Reads the float balance from the ledger, raising the low-float alert and requesting a rebalance when due." />
                ) },
              ]} />
          )}
          <Pager p={floats} />
        </Card>
        <Card title="Outlets">
          <ErrorBanner error={outlets.error} />
          {outlets.isLoading ? <Loading /> : (
            <Table rows={outlets.rows} empty="No outlets."
              columns={[
                { key: "outlet_code", header: "Code", render: (r) => <Mono>{str(r.outlet_code)}</Mono> },
                { key: "name", header: "Name" },
                { key: "city", header: "City" },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
              ]} />
          )}
          <Pager p={outlets} />
        </Card>
        <Card title="Sub-agents">
          <ErrorBanner error={subs.error} />
          {subs.isLoading ? <Loading /> : (
            <Table rows={subs.rows} empty="No sub-agents."
              columns={[
                { key: "agent_code", header: "Code", render: (r) => <Link className="text-accent hover:underline" href={`/wallets/agents/${r.id}`}><Mono>{str(r.agent_code)}</Mono></Link> },
                { key: "legal_name", header: "Name", render: (r) => str(r.trading_name ?? r.legal_name) },
                { key: "agent_level", header: "Level" },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
              ]} />
          )}
          <Pager p={subs} />
        </Card>
      </div>
    </>
  );
}

export default function Page() {
  return <Suspense><AgentDetail /></Suspense>;
}
