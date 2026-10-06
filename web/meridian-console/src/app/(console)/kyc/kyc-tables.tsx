"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Badge, Mono, Table } from "@/components/ui";
import { Action, dateCell, statusCell, type Row } from "@/components/ops";

/* KYC tables shared by the KYC workbench, the KYC profile and the onboarding application screens. */

const KYC = [["kyc"]];

/** Stops a row click from firing when an inline action (or its dialog) is used. */
export function Inline({ children }: { children: ReactNode }) {
  return <span className="flex justify-end gap-1" onClick={(e) => e.stopPropagation()}>{children}</span>;
}

export function riskTone(v: unknown): "good" | "warn" | "bad" | "muted" {
  return v === "HIGH" || v === "PROHIBITED" ? "bad" : v === "MEDIUM" ? "warn" : v === "LOW" ? "good" : "muted";
}

export function RiskBadge({ value }: { value: unknown }) {
  return value ? <Badge value={String(value)} tone={riskTone(value)} /> : <span className="text-muted">—</span>;
}

export function ProfileLink({ id }: { id: unknown }) {
  if (!id) return <span className="text-muted">—</span>;
  return (
    <Link href={`/kyc/${id}`} onClick={(e) => e.stopPropagation()} className="font-mono text-xs text-accent hover:underline">
      {String(id).slice(0, 8)}…
    </Link>
  );
}

const label = (v: unknown) => String(v ?? "").replaceAll("_", " ").toLowerCase();

export function ChecksTable({ rows, empty, onRow }: { rows: Row[]; empty?: string; onRow?: (r: Row) => void }) {
  return (
    <Table rows={rows} empty={empty ?? "No checks."} onRow={onRow}
      columns={[
        { key: "check_type", header: "Check", render: (r) => <span className="font-medium">{String(r.check_type)}</span> },
        { key: "kyc_profile_id", header: "Profile", render: (r) => <ProfileLink id={r.kyc_profile_id} /> },
        { key: "status", header: "Status", render: statusCell() },
        { key: "score", header: "Score", align: "right" },
        { key: "result_summary", header: "Result" },
        { key: "reason_codes", header: "Reasons", render: (r) => <Mono>{(r.reason_codes as string[] | null)?.join(", ") || "—"}</Mono> },
        { key: "requested_at", header: "Requested", render: dateCell("requested_at") },
        { key: "actions", header: "", render: (r) => ["FAILED", "ERROR", "INCONCLUSIVE", "EXPIRED"].includes(String(r.status)) ? (
          <Inline>
            <Action size="sm" variant="secondary" label="Retry" path={`/v1/kyc/checks/${r.id}/retry`}
              description={`Re-runs the ${label(r.check_type)} check with the same provider or the configured fallback provider.`}
              fields={[{ name: "provider", label: "Provider", options: ["SAME_PROVIDER", "FALLBACK_PROVIDER"], required: true }]}
              body={(v) => ({ use_fallback: v.provider === "FALLBACK_PROVIDER" })} invalidate={KYC} />
          </Inline>
        ) : null },
      ]} />
  );
}

export function DocumentsTable({ rows, empty }: { rows: Row[]; empty?: string }) {
  return (
    <Table rows={rows} empty={empty ?? "No documents."}
      columns={[
        { key: "document_type", header: "Type", render: (r) => <span className="font-medium">{String(r.document_type)}</span> },
        { key: "document_number", header: "Number", render: (r) => <Mono>{String(r.document_number ?? "—")}</Mono> },
        { key: "issuing_country", header: "Country" },
        { key: "expiry_date", header: "Expires" },
        { key: "captured_channel", header: "Channel" },
        { key: "status", header: "Status", render: statusCell() },
        { key: "rejection_reason", header: "Rejection reason" },
        { key: "actions", header: "", render: (r) => ["UPLOADED", "PROCESSING"].includes(String(r.status)) ? (
          <Inline>
            <Action size="sm" label="Accept" path={`/v1/kyc/documents/${r.id}/accept`}
              description={`Accept this ${label(r.document_type)} after manual review.`}
              fields={[{ name: "comment", label: "Comment" }]} invalidate={KYC} />
            <Action size="sm" variant="danger" label="Reject" path={`/v1/kyc/documents/${r.id}/reject`}
              description={`Reject this ${label(r.document_type)}; the customer will be asked for a new one.`}
              fields={[{ name: "reason", label: "Reason", required: true, type: "textarea" }]} invalidate={KYC} />
          </Inline>
        ) : null },
      ]} />
  );
}

export function ScreeningsTable({ rows, empty }: { rows: Row[]; empty?: string }) {
  return (
    <Table rows={rows} empty={empty ?? "No screenings."}
      columns={[
        { key: "screening_type", header: "Type", render: (r) => <span className="font-medium">{String(r.screening_type)}</span> },
        { key: "screened_name", header: "Screened name" },
        { key: "kyc_profile_id", header: "Profile", render: (r) => <ProfileLink id={r.kyc_profile_id} /> },
        { key: "match_count", header: "Matches", align: "right" },
        { key: "trigger", header: "Trigger" },
        { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)}
          tone={r.status === "CLEAR" || r.status === "FALSE_POSITIVE" ? "good" : r.status === "POTENTIAL_MATCH" ? "warn" : "bad"} /> },
        { key: "created_at", header: "Screened", render: dateCell("created_at") },
        { key: "actions", header: "", render: (r) => r.status === "POTENTIAL_MATCH" ? (
          <Inline>
            <Action size="sm" label="Disposition" path={`/v1/kyc/screenings/${r.id}/disposition`}
              description={<>Resolve the potential {label(r.screening_type)} match for <b>{String(r.screened_name)}</b>
                {" "}({String(r.match_count ?? 0)} candidate matches). A confirmed match blocks the customer.</>}
              fields={[{ name: "status", label: "Outcome", options: ["FALSE_POSITIVE", "CONFIRMED_MATCH"], required: true },
                { name: "review_comment", label: "Review comment", type: "textarea", required: true }]}
              invalidate={KYC} />
          </Inline>
        ) : null },
      ]} />
  );
}

export function ReviewsTable({ rows, empty }: { rows: Row[]; empty?: string }) {
  return (
    <Table rows={rows} empty={empty ?? "No reviews."}
      columns={[
        { key: "trigger", header: "Trigger", render: (r) => <span className="font-medium">{String(r.trigger)}</span> },
        { key: "kyc_profile_id", header: "Profile", render: (r) => <ProfileLink id={r.kyc_profile_id} /> },
        { key: "due_date", header: "Due" },
        { key: "assigned_to", header: "Assignee", render: (r) => <Mono>{r.assigned_to ? String(r.assigned_to).slice(0, 8) + "…" : "unassigned"}</Mono> },
        { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)}
          tone={r.status === "OVERDUE" ? "bad" : r.status === "COMPLETED" ? "good" : r.status === "CANCELLED" ? "muted" : "warn"} /> },
        { key: "outcome", header: "Outcome" },
        { key: "actions", header: "", render: (r) => ["SCHEDULED", "DUE", "IN_PROGRESS", "OVERDUE"].includes(String(r.status)) ? (
          <Inline>
            <Action size="sm" variant="secondary" label="Assign" path={`/v1/kyc/reviews/${r.id}/assign`}
              fields={[{ name: "assigned_to", label: "Analyst user ID", required: true, placeholder: "uuid" }]} invalidate={KYC} />
            <Action size="sm" variant="secondary" label="Request update" path={`/v1/kyc/reviews/${r.id}/request-update`}
              description="Asks the customer to refresh their documents or data."
              fields={[{ name: "message", label: "Message to customer", type: "textarea" }]} invalidate={KYC} />
            <Action size="sm" label="Complete" path={`/v1/kyc/reviews/${r.id}/complete`}
              fields={[{ name: "outcome", label: "Outcome", required: true, placeholder: "e.g. NO_CHANGE, RISK_UPGRADED, EXIT" },
                { name: "outcome_notes", label: "Notes", type: "textarea" }]} invalidate={KYC} />
          </Inline>
        ) : null },
      ]} />
  );
}

export function RiskAssessmentsTable({ rows, empty }: { rows: Row[]; empty?: string }) {
  return (
    <Table rows={rows} empty={empty ?? "No risk assessments."}
      columns={[
        { key: "created_at", header: "Assessed", render: dateCell("created_at") },
        { key: "trigger", header: "Trigger" },
        { key: "model_version", header: "Model", render: (r) => <Mono>{String(r.model_version ?? "—")}</Mono> },
        { key: "score", header: "Score", align: "right" },
        { key: "risk_level", header: "Level", render: (r) => <RiskBadge value={r.risk_level} /> },
        { key: "override_level", header: "Override", render: (r) => r.override_level
          ? <span><RiskBadge value={r.override_level} /> <span className="text-xs text-muted">{String(r.override_reason ?? "")}</span></span> : "—" },
      ]} />
  );
}
