"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { Card, DateTime, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, amountCell, statusCell, usePaged } from "@/components/ops";
import { SEVERITIES, Severity, overdue } from "../_shared";

const STATUSES = ["OPEN", "IN_REVIEW", "CONFIRMED_FRAUD", "FALSE_POSITIVE", "ESCALATED", "CLOSED"];
const TYPOLOGIES = ["ACCOUNT_TAKEOVER", "CARD_NOT_PRESENT", "CARD_PRESENT_COUNTERFEIT", "AUTHORISED_PUSH_PAYMENT_SCAM",
  "FIRST_PARTY", "SYNTHETIC_IDENTITY", "MONEY_MULE", "FRIENDLY_FRAUD", "SIM_SWAP", "INSIDER", "OTHER"];
const SUBJECTS = ["CARD_AUTHORIZATION", "PAYMENT", "WALLET_TRANSACTION", "LOGIN", "ONBOARDING", "BENEFICIARY_CHANGE",
  "DEVICE_CHANGE", "PROFILE_CHANGE", "LOAN_APPLICATION"];

function FraudAlerts() {
  const router = useRouter();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "OPEN" });
  const list = usePaged("fraud-alerts", "/v1/risk/fraud/alerts", filters);

  return (
    <>
      <PageHeader title="Fraud alerts" subtitle="Alerts raised by real-time fraud scoring, worked by the fraud operations team" />
      <Card>
        <Filters value={filters} onChange={setFilters} specs={[
          { name: "status", label: "Status", options: STATUSES },
          { name: "severity", label: "Severity", options: SEVERITIES },
          { name: "typology", label: "Typology", options: TYPOLOGIES },
          { name: "subject_type", label: "Subject", options: SUBJECTS },
          { name: "assigned_to", label: "Assignee", placeholder: "analyst user" },
          { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
        ]} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/risk/fraud/${r.id}`)} empty="No fraud alerts match."
            columns={[
              { key: "created_at", header: "Raised", render: (r) => <DateTime value={r.created_at as string} /> },
              { key: "severity", header: "Severity", render: (r) => <Severity value={r.severity} /> },
              { key: "subject_type", header: "Subject", render: (r) => String(r.subject_type ?? "—").replaceAll("_", " ") },
              { key: "typology", header: "Typology", render: (r) => String(r.typology ?? "—").replaceAll("_", " ") },
              { key: "amount", header: "Amount", align: "right", render: amountCell() },
              { key: "assigned_to", header: "Assignee", render: (r) => String(r.assigned_to ?? "unassigned") },
              { key: "sla_due_at", header: "SLA due", render: (r) => (
                <span className={overdue(r.sla_due_at) && ["OPEN", "IN_REVIEW"].includes(String(r.status)) ? "text-bad" : undefined}>
                  <DateTime value={r.sla_due_at as string} />
                </span>) },
              { key: "status", header: "Status", render: statusCell() },
              { key: "id", header: "ID", render: (r) => <Mono>{String(r.id).slice(0, 8)}</Mono> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><FraudAlerts /></Suspense>;
}
