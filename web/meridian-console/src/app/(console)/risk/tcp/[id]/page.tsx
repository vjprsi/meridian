"use client";

import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, Loading, Mono, PageHeader } from "@/components/ui";
import { Action, Details, str, type Row } from "@/components/ops";

// WE-LIMIT-1 (G-04): one cumulative (KYC-tier) Transfer Control Profile. Lifecycle:
// DRAFT -(submit)-> PENDING_APPROVAL -(activate)-> ACTIVE -(deactivate)-> INACTIVE; reject returns it to draft.
const base = "/v1/risk/limits/tcp/cumulative";
const SUBMITTABLE = ["DRAFT"];
const ACTIVATABLE = ["DRAFT", "PENDING_APPROVAL", "INACTIVE"];
const REJECTABLE = ["DRAFT", "PENDING_APPROVAL"];
const DEACTIVATABLE = ["ACTIVE"];

function TcpDetail() {
  const { id } = useParams<{ id: string }>();
  const inv = [["tcp-cumulative"]];
  const q = useQuery({ queryKey: ["tcp-cumulative", id], queryFn: () => call<Row>("GET", `${base}/${id}`) });

  if (q.isLoading) return <Loading />;
  const t = q.data;
  const status = String(t?.status ?? "");

  return (
    <>
      <PageHeader title="Transfer control profile"
        subtitle={t && <><Mono>{str(t.code)}</Mono> · {str(t.regulatory_profile_code)} · <Badge value={status} /></>}
        actions={t && <>
          {SUBMITTABLE.includes(status) && (
            <Action label="Submit" variant="secondary" path={`${base}/${id}/submit`} invalidate={inv}
              title="Submit profile for approval" fields={[{ name: "comment", label: "Comment", type: "textarea" }]} />
          )}
          {ACTIVATABLE.includes(status) && (
            <Action label="Activate" path={`${base}/${id}/activate`} invalidate={inv}
              title="Activate profile" description="Makes this profile the live transfer-limit set for its KYC tier." />
          )}
          {REJECTABLE.includes(status) && (
            <Action label="Reject" variant="danger" path={`${base}/${id}/reject`} invalidate={inv}
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
          {DEACTIVATABLE.includes(status) && (
            <Action label="Deactivate" variant="ghost" path={`${base}/${id}/deactivate`} invalidate={inv}
              confirmLabel="Deactivate"
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
        </>} />

      {t && (
        <Card title="Profile">
          <Details items={[
            ["Code", <Mono key="c">{str(t.code)}</Mono>],
            ["Name", t.name],
            ["KYC tier", t.regulatory_profile_code],
            ["Direction", t.direction],
            ["Rule mode", t.rule_mode],
            ["Currency", t.currency],
            ["Yearly enabled", t.yearly_enabled],
            ["Breach action", t.breach_action],
            ["Priority", t.priority],
            ["Regulatory", t.is_regulatory],
            ["Created", t.created_at],
            ["Updated", t.updated_at],
            ["Status", <Badge key="s" value={status} />],
          ]} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><TcpDetail /></Suspense>;
}
