"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Filters, Pager, Tabs, amountCell, dateCell, str, usePaged } from "@/components/ops";

const LEVELS = ["SUPER_AGENT", "AGENT", "SUB_AGENT"] as const;
const STATUSES = ["ONBOARDING", "ACTIVE", "SUSPENDED", "TERMINATED"] as const;
const REQ_TYPES = ["TOP_UP", "REBALANCE", "WITHDRAW", "TRANSFER"] as const;
const REQ_STATUSES = ["REQUESTED", "APPROVED", "REJECTED", "COMPLETED", "CANCELLED", "FAILED"] as const;

type Tab = "agents" | "float-requests";

function Agents() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("agents");
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [reqFilters, setReqFilters] = useState<Record<string, string>>({ status: "REQUESTED" });
  const agents = usePaged("agents", "/v1/agents", filters, { enabled: tab === "agents" });
  const requests = usePaged("agent-float-requests", "/v1/agent-float-requests", reqFilters, { enabled: tab === "float-requests" });

  return (
    <>
      <PageHeader title="Agents" subtitle="Agent network: super-agents, agents and sub-agents, and their float" />
      <Tabs value={tab} onChange={setTab} tabs={[{ id: "agents", label: "Agents" }, { id: "float-requests", label: "Float requests" }]} />
      {tab === "agents" ? (
        <Card>
          <Filters value={filters} onChange={setFilters} specs={[
            { name: "status", label: "Status", options: STATUSES },
            { name: "agent_level", label: "Level", options: LEVELS },
            { name: "agent_code", label: "Agent code" },
          ]} />
          <ErrorBanner error={agents.error} />
          {agents.isLoading ? <Loading /> : (
            <Table rows={agents.rows} onRow={(r) => router.push(`/wallets/agents/${r.id}`)} empty="No agents match."
              columns={[
                { key: "agent_code", header: "Code", render: (r) => <Mono>{str(r.agent_code)}</Mono> },
                { key: "legal_name", header: "Name", render: (r) => <span className="font-medium">{str(r.trading_name ?? r.legal_name)}</span> },
                { key: "agent_level", header: "Level" },
                { key: "contact_msisdn", header: "Contact" },
                { key: "currency", header: "Currency" },
                { key: "risk_rating", header: "Risk" },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
              ]} />
          )}
          <Pager p={agents} />
        </Card>
      ) : (
        <Card>
          <Filters value={reqFilters} onChange={setReqFilters} specs={[
            { name: "status", label: "Status", options: REQ_STATUSES },
            { name: "request_type", label: "Type", options: REQ_TYPES },
          ]} />
          <ErrorBanner error={requests.error} />
          {requests.isLoading ? <Loading /> : (
            <Table rows={requests.rows} empty="No float requests."
              columns={[
                { key: "created_at", header: "Raised", render: dateCell("created_at") },
                { key: "request_type", header: "Type" },
                { key: "requesting_agent_id", header: "Agent", render: (r) => (
                  <button className="text-accent hover:underline" onClick={() => router.push(`/wallets/agents/${r.requesting_agent_id}`)}>
                    <Mono>{String(r.requesting_agent_id).slice(0, 8)}…</Mono>
                  </button>) },
                { key: "amount", header: "Amount", align: "right", render: amountCell() },
                { key: "created_by", header: "Maker" },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                { key: "actions", header: "", render: (r) => r.status === "REQUESTED" ? (
                  <div className="flex gap-1">
                    <Action label="Approve" size="sm" path={`/v1/agent-float-requests/${r.id}/approve`}
                      invalidate={[["agent-float-requests"], ["agent"]]}
                      description="Checker action: executes the float movement in the wallet service."
                      fields={[{ name: "comment", label: "Comment" }]} />
                    <Action label="Reject" size="sm" variant="danger" path={`/v1/agent-float-requests/${r.id}/reject`}
                      invalidate={[["agent-float-requests"]]} fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} />
                  </div>) : null },
              ]} />
          )}
          <Pager p={requests} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><Agents /></Suspense>;
}
