"use client";

import { Action, type Row } from "@/components/ops";
import { download } from "@/api/client";

export const INV = [["reg-reporting"]];

/** Content endpoint per output format (the service serves XML/XBRL, CSV and JSON content; other formats are stored documents). */
export function outputPath(o: Row): string | null {
  switch (o.format) {
    case "XML":
    case "XBRL": return `/v1/reporting/outputs/${o.id}/xml`;
    case "CSV": return `/v1/reporting/outputs/${o.id}/csv`;
    case "JSON": return `/v1/reporting/outputs/${o.id}/json`;
    default: return null;
  }
}

export function downloadOutput(o: Row): Promise<void> {
  const p = outputPath(o);
  if (!p) return Promise.reject(new Error(`${String(o.format)} outputs are stored as documents; no content endpoint`));
  return download(p, String(o.file_name ?? `${o.id}.${String(o.format).toLowerCase()}`));
}

/** Four-eyes submission workflow: approve (checker) -> submit -> acknowledge / record rejection. */
export function SubmissionActions({ s }: { s: Row }) {
  const base = `/v1/reporting/submissions/${s.id}`;
  return (
    <div className="flex justify-end gap-1">
      {s.status === "PENDING_APPROVAL" && (
        <Action size="sm" label="Approve" path={`${base}/approve`} fields={[{ name: "comment", label: "Comment" }]} invalidate={INV}
          description="Sign off this submission. You cannot approve a submission you prepared." />
      )}
      {s.status === "APPROVED" && (
        <Action size="sm" label="Submit" path={`${base}/submit`} invalidate={INV}
          description="Send the report to the regulator through the gateway adapter. This cannot be undone." />
      )}
      {(s.status === "SUBMITTED" || s.status === "ACKNOWLEDGED") && <>
        {s.status === "SUBMITTED" && (
          <Action size="sm" variant="secondary" label="Acknowledge" path={`${base}/acknowledge`} invalidate={INV}
            fields={[{ name: "regulator_reference", label: "Regulator reference", required: true },
              { name: "acknowledgement_document_id", label: "Acknowledgement document ID" }]} />
        )}
        <Action size="sm" variant="danger" label="Record rejection" path={`${base}/record-rejection`} invalidate={INV}
          fields={[{ name: "rejection_reason", label: "Rejection reason", required: true, type: "textarea" }]} />
      </>}
    </div>
  );
}
