"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, Money, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, rows, type Row } from "@/components/ops";
import { CASE_TYPES, Severity } from "../../_shared";

const TYPOLOGIES = ["ACCOUNT_TAKEOVER", "CARD_NOT_PRESENT", "CARD_PRESENT_COUNTERFEIT", "AUTHORISED_PUSH_PAYMENT_SCAM",
  "FIRST_PARTY", "SYNTHETIC_IDENTITY", "MONEY_MULE", "FRIENDLY_FRAUD", "SIM_SWAP", "INSIDER", "OTHER"];
const ACTIVE = ["OPEN", "IN_REVIEW", "ESCALATED"];

function FraudAlert() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const alert = useQuery({ queryKey: ["fraud-alerts", id], queryFn: () => call<Row>("GET", `/v1/risk/fraud/alerts/${id}`) });
  const scoreId = alert.data?.fraud_score_id as string | undefined;
  const score = useQuery({ queryKey: ["fraud-score", scoreId], enabled: !!scoreId, retry: false,
    queryFn: () => call<Row>("GET", `/v1/risk/fraud/scores/${scoreId}`) });
  const hits = useQuery({ queryKey: ["fraud-score", scoreId, "hits"], enabled: !!scoreId, retry: false,
    queryFn: () => call<unknown>("GET", `/v1/risk/fraud/scores/${scoreId}/rule-hits?limit=100`) });
  const explanation = useQuery({ queryKey: ["fraud-score", scoreId, "explanation"], enabled: !!scoreId, retry: false,
    queryFn: () => call<Row>("GET", `/v1/risk/fraud/scores/${scoreId}/explanation`) });

  if (alert.isLoading) return <Loading />;
  const a = alert.data;
  const inv = [["fraud-alerts"]];
  const open = a && ACTIVE.includes(String(a.status));

  return (
    <>
      <PageHeader title="Fraud alert"
        subtitle={a && <><Mono>{String(a.id)}</Mono> · <Severity value={a.severity} /> · <Badge value={String(a.status)} /></>}
        actions={a && open && <>
          <Action label="Assign" variant="secondary" path={`/v1/risk/fraud/alerts/${id}/assign`} invalidate={inv}
            fields={[{ name: "assigned_to", label: "Analyst", required: true, initial: String(a.assigned_to ?? "") }]} />
          <Action label="Contact customer" variant="secondary" path={`/v1/risk/fraud/alerts/${id}/contact-customer`}
            description="Sends the customer a confirm-or-deny message about the flagged activity." invalidate={inv}
            fields={[{ name: "channel", label: "Channel", required: true, options: ["PUSH", "SMS", "EMAIL", "WHATSAPP", "IN_APP", "USSD", "VOICE"] }]} />
          <Action label="False positive" variant="secondary" path={`/v1/risk/fraud/alerts/${id}/resolve`}
            body={{ status: "FALSE_POSITIVE" }} invalidate={inv} title="Resolve as false positive"
            fields={[{ name: "resolution", label: "Resolution", type: "textarea", required: true }]} />
          <Action label="Confirm fraud" variant="danger" path={`/v1/risk/fraud/alerts/${id}/resolve`}
            body={{ status: "CONFIRMED_FRAUD" }} invalidate={inv} title="Confirm fraud"
            description="Marks the activity as confirmed fraud. Downstream blocking and loss handling follow the fraud policy."
            fields={[
              { name: "typology", label: "Typology", options: TYPOLOGIES, initial: String(a.typology ?? "") },
              { name: "resolution", label: "Resolution", type: "textarea", required: true },
            ]} />
          <Action label="Escalate to case" variant="danger" path={`/v1/risk/fraud/alerts/${id}/escalate`} invalidate={inv}
            description="Opens (or attaches to) an AML/fraud investigation case."
            onDone={(r) => { const c = r as Row | undefined; if (c?.id) router.push(`/risk/cases/${c.id}`); }}
            fields={[
              { name: "case_type", label: "Case type", required: true, options: CASE_TYPES, initial: "FRAUD" },
              { name: "reason", label: "Reason", type: "textarea", required: true },
            ]} />
          <Action label="Close" variant="ghost" path={`/v1/risk/fraud/alerts/${id}/resolve`} body={{ status: "CLOSED" }}
            invalidate={inv} title="Close alert" fields={[{ name: "resolution", label: "Resolution", type: "textarea", required: true }]} />
        </>} />
      <ErrorBanner error={alert.error} />
      {a && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <Card title="Alert" className="lg:col-span-2">
            <Details items={[
              ["Subject", `${String(a.subject_type ?? "—").replaceAll("_", " ")}`],
              ["Subject ID", <Mono key="s">{String(a.subject_id ?? "—")}</Mono>],
              ["Customer", a.customer_id ? <Link key="c" className="text-accent hover:underline" href={`/customers/${a.customer_id}`}>
                <Mono>{String(a.customer_id)}</Mono></Link> : null],
              ["Amount", <Money key="m" amount={a.amount as string} currency={a.currency as string} />],
              ["Typology", a.typology ? String(a.typology).replaceAll("_", " ") : null],
              ["Reason codes", a.reason_codes],
              ["Assigned to", a.assigned_to],
              ["SLA due", a.sla_due_at],
              ["Customer contacted", a.customer_contacted],
              ["Raised", a.created_at],
              ["Resolution", a.resolution],
              ["Resolved by", a.resolved_by],
              ["Resolved at", a.resolved_at],
              ["Case", a.aml_case_id ? <Link key="k" className="text-accent hover:underline" href={`/risk/cases/${a.aml_case_id}`}>
                Open case</Link> : null],
            ]} />
          </Card>
          {scoreId && (
            <>
              <Card title="Score">
                <ErrorBanner error={score.error} />
                {score.data && (
                  <Details cols={1} items={[
                    ["Decision", <Badge key="d" value={String(score.data.decision)} />],
                    ["Score", score.data.score],
                    ["Model score", score.data.model_score],
                    ["Rule score", score.data.rule_score],
                    ["Model", [score.data.model_id, score.data.model_version].filter(Boolean).join(" v") || null],
                    ["Channel", score.data.channel],
                    ["IP address", score.data.ip_address],
                    ["Counterparty country", score.data.counterparty_country],
                    ["MCC", score.data.merchant_category_code],
                  ]} />
                )}
              </Card>
              <Card title="Explanation">
                <ErrorBanner error={explanation.error} />
                {explanation.isLoading ? <Loading /> : <Json value={explanation.data} />}
              </Card>
              <Card title="Rules fired" className="lg:col-span-2">
                <ErrorBanner error={hits.error} />
                <Table rows={rows(hits.data)} empty="No rules fired."
                  columns={[
                    { key: "fraud_rule_id", header: "Rule", render: (r) => <Mono>{String(r.fraud_rule_id ?? "—")}</Mono> },
                    { key: "rule_version", header: "Version" },
                    { key: "action", header: "Action", render: (r) => <Badge value={String(r.action ?? "—")} /> },
                    { key: "score_adjustment", header: "Score Δ", align: "right" },
                    { key: "shadow", header: "Shadow", render: (r) => (r.shadow ? "yes" : "no") },
                    { key: "matched_values", header: "Matched", render: (r) => <Mono>{JSON.stringify(r.matched_values ?? {})}</Mono> },
                  ]} />
              </Card>
            </>
          )}
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><FraudAlert /></Suspense>;
}
