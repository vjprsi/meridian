"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Pager, Timeline, dateCell, rows, statusCell, usePaged, type Row } from "@/components/ops";
import { ChecksTable, DocumentsTable } from "@/app/(console)/kyc/kyc-tables";

type Application = Row;

const OPEN = ["STARTED", "IN_PROGRESS"];
const DECIDABLE = ["SUBMITTED", "UNDER_REVIEW", "REFERRED"];
const LIVE = [...OPEN, ...DECIDABLE];

function ApplicationDetail() {
  const { id } = useParams<{ id: string }>();
  const appKey = ["onboarding-application", id];
  const app = useQuery({ queryKey: appKey, queryFn: () => call<Application>("GET", `/v1/onboarding/applications/${id}`) });
  const steps = usePaged("onboarding-application", `/v1/onboarding/applications/${id}/steps`, {}, { limit: 100 });
  const checks = usePaged("kyc", "/v1/kyc/checks", { onboarding_application_id: id });
  const a = app.data;
  const customerId = a?.customer_id ? String(a.customer_id) : "";
  const docs = usePaged("kyc", "/v1/kyc/documents", { customer_id: customerId }, { enabled: !!customerId });
  const journey = useQuery({
    queryKey: ["onboarding-journey", a?.onboarding_journey_id],
    queryFn: () => call<Row>("GET", `/v1/onboarding/journeys/${a!.onboarding_journey_id}`),
    enabled: !!a?.onboarding_journey_id,
    retry: false,
  });

  if (app.isLoading) return <Loading />;
  const status = String(a?.status ?? "");
  const invalidate = [["onboarding-application"], ["onboarding-applications"], ["kyc"]];
  const stepCodes = steps.rows.map((s) => String(s.step_code));

  return (
    <>
      <PageHeader title={a ? `Application ${String(a.application_number ?? "")}` : "Application"}
        subtitle={a && <><Mono>{String(a.id)}</Mono> · {String(a.channel)} · <Badge value={status} />
          {a.decision ? <> · decision <Badge value={String(a.decision)} /></> : null}</>}
        actions={a && <>
          {OPEN.includes(status) && (
            <Action label="Submit for decision" variant="secondary" path={`/v1/onboarding/applications/${id}/submit`}
              description="Runs the journey's decision rules on the data collected so far." invalidate={invalidate} />
          )}
          {DECIDABLE.includes(status) && <>
            <Action label="Approve" path={`/v1/onboarding/applications/${id}/approve`}
              description="Approves the application as checker. The customer and selected products are created by the onboarding service."
              fields={[{ name: "comment", label: "Comment", type: "textarea" }]} invalidate={invalidate} />
            <Action label="Request info" variant="secondary" path={`/v1/onboarding/applications/${id}/request-info`}
              description="The applicant is asked to provide more information; selected steps are re-opened."
              fields={[{ name: "message", label: "Message to applicant", type: "textarea", required: true },
                { name: "step_codes", label: "Step codes to reopen", placeholder: "comma-separated", hint: stepCodes.join(", ") }]}
              body={(v) => ({ message: v.message,
                step_codes: v.step_codes ? v.step_codes.split(",").map((s) => s.trim()).filter(Boolean) : undefined })}
              invalidate={invalidate} />
            <Action label="Reject" variant="danger" path={`/v1/onboarding/applications/${id}/reject`}
              description="Rejection is final; the applicant is notified."
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} invalidate={invalidate} />
          </>}
          {LIVE.includes(status) && (
            <Action label="Send resume link" variant="secondary" path={`/v1/onboarding/applications/${id}/resume`}
              description="Sends the applicant a new resume link with a fresh expiry." invalidate={invalidate} />
          )}
          {OPEN.includes(status) && (
            <Action label="Abandon" variant="danger" path={`/v1/onboarding/applications/${id}/abandon`}
              description="Marks the application as abandoned; it can no longer progress." invalidate={invalidate} />
          )}
        </>} />
      <ErrorBanner error={app.error} />
      {a && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Card title="Application" className="lg:col-span-2">
            <Details items={[
              ["Journey", journey.data ? `${journey.data.name} (${journey.data.code} v${journey.data.version})` : a.onboarding_journey_id],
              ["Customer", customerId ? <Link className="text-accent hover:underline" href={`/customers/${customerId}`}>{customerId}</Link> : null],
              ["Phone", a.phone], ["Email", a.email],
              ["Current step", a.current_step], ["Progress", `${a.progress_percent ?? 0}%`],
              ["Risk score", a.risk_score], ["Risk level", a.risk_level ? <Badge value={String(a.risk_level)} /> : null],
              ["Agent", a.agent_id], ["Branch", a.branch_id],
              ["Submitted", a.submitted_at], ["Submitted by", a.submitted_by],
              ["Reviewed", a.reviewed_at], ["Reviewed by", a.reviewed_by],
              ["Review comment", a.review_comment], ["Rejection reason", a.rejection_reason],
              ["Expires", a.expires_at], ["Completed", a.completed_at],
              ["IP address", a.ip_address], ["Device", a.device_fingerprint],
            ]} />
          </Card>
          <Card title="Decision">
            <div className="space-y-4 text-sm">
              <div>
                <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted">Reasons</p>
                {!Array.isArray(a.decision_reasons) || a.decision_reasons.length === 0 ? <p className="text-muted">—</p> : (
                  <ul className="list-disc pl-5">
                    {(a.decision_reasons as unknown[]).map((r, i) => (
                      <li key={i}>{typeof r === "string" ? r : JSON.stringify(r)}</li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted">Risk factors</p>
                <Json value={a.risk_factors} />
              </div>
              {Array.isArray(a.info_requests) && a.info_requests.length > 0 && (
                <div>
                  <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted">Information requests</p>
                  <Timeline items={rows(a.info_requests).map((r) => ({
                    at: r.requested_at ?? r.at ?? r.created_at, title: String(r.message ?? "Request"),
                    detail: Array.isArray(r.step_codes) ? `steps: ${(r.step_codes as string[]).join(", ")}` : undefined,
                  }))} />
                </div>
              )}
            </div>
          </Card>
          <Card title="Steps" className="lg:col-span-3">
            <ErrorBanner error={steps.error} />
            {steps.isLoading ? <Loading /> : (
              <Table rows={[...steps.rows].sort((x, y) => Number(x.sequence ?? 0) - Number(y.sequence ?? 0))}
                empty="No steps recorded."
                columns={[
                  { key: "sequence", header: "#", align: "right" },
                  { key: "step_code", header: "Step", render: (r) => <span className="font-medium">{String(r.step_code)}</span> },
                  { key: "step_type", header: "Type" },
                  { key: "status", header: "Status", render: statusCell() },
                  { key: "attempts", header: "Attempts", align: "right" },
                  { key: "started_at", header: "Started", render: dateCell("started_at") },
                  { key: "completed_at", header: "Completed", render: dateCell("completed_at") },
                  { key: "error", header: "Error", render: (r) => r.error ? <span className="text-bad">{String(r.error)}</span> : "—" },
                ]} />
            )}
            <Pager p={steps} />
          </Card>
          <Card title="KYC checks" className="lg:col-span-3">
            <ErrorBanner error={checks.error} />
            {checks.isLoading ? <Loading /> : (
              <ChecksTable rows={checks.rows} empty="No KYC checks for this application." />
            )}
            <Pager p={checks} />
          </Card>
          <Card title="Documents" className="lg:col-span-3">
            {!customerId ? <p className="text-sm text-muted">Documents are listed once the application is linked to a customer.</p> : <>
              <ErrorBanner error={docs.error} />
              {docs.isLoading ? <Loading /> : <DocumentsTable rows={docs.rows} />}
              <Pager p={docs} />
            </>}
          </Card>
          <Card title="Collected data" className="lg:col-span-3">
            <Json value={a.collected_data} />
          </Card>
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><ApplicationDetail /></Suspense>;
}
