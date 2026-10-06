"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Json, Pager, usePaged, type Row } from "@/components/ops";

/** A result needs a decision while it is a potential match; confirmed / false-positive results are final. */
const REVIEWABLE = ["POTENTIAL_MATCH", "PENDING", "ERROR"];

function MatchEntry({ entryId }: { entryId: string }) {
  const entry = useQuery({ queryKey: ["sanctions-entry", entryId], retry: false, staleTime: 300_000,
    queryFn: () => call<Row>("GET", `/v1/risk/sanctions/list-entries/${entryId}`) });
  if (!entry.data) return <Mono>{entryId.slice(0, 8)}</Mono>;
  const e = entry.data;
  return (
    <span className="text-xs">
      <span className="font-medium">{String(e.primary_name)}</span> · {String(e.entry_type ?? "")}
      {e.nationality ? ` · ${String(e.nationality)}` : ""}
      {Array.isArray(e.programs) && e.programs.length > 0 ? <span className="text-muted"> · {e.programs.join(", ")}</span> : null}
    </span>
  );
}

function ScreeningResult() {
  const { id } = useParams<{ id: string }>();
  const result = useQuery({ queryKey: ["screening-results", id], queryFn: () => call<Row>("GET", `/v1/risk/sanctions/results/${id}`) });
  const reqId = result.data?.screening_request_id as string | undefined;
  const request = useQuery({ queryKey: ["screening-request", reqId], enabled: !!reqId, retry: false,
    queryFn: () => call<Row>("GET", `/v1/risk/sanctions/screenings/${reqId}`) });
  const matches = usePaged("screening-results", `/v1/risk/sanctions/results/${id}/matches`, {}, { limit: 50 });

  if (result.isLoading) return <Loading />;
  const r = result.data;
  const req = request.data;
  const inv = [["screening-results"]];
  const reviewable = r && REVIEWABLE.includes(String(r.status));

  return (
    <>
      <PageHeader title={req ? String(req.full_name) : "Screening result"}
        subtitle={r && <><Mono>{String(r.id)}</Mono> · {String(r.subject_type ?? "").replaceAll("_", " ")} · <Badge value={String(r.status)} /></>}
        actions={r && <>
          <Action label="Rescreen now" variant="secondary" path={`/v1/risk/sanctions/results/${id}/rescreen`} invalidate={inv}
            description="Screens the subject again against the current list versions." />
          {reviewable && <>
            <Action label="False positive" variant="secondary" path={`/v1/risk/sanctions/results/${id}/review`} invalidate={inv}
              title="Clear as false positive"
              description="Records that none of the candidate matches is the subject. Optionally whitelists the pairs so they are suppressed on future screenings."
              body={(v) => ({ status: "FALSE_POSITIVE", decision_reason: v.decision_reason,
                whitelist_false_positives: v.whitelist === "yes" })}
              fields={[
                { name: "decision_reason", label: "Decision reason", type: "textarea", required: true },
                { name: "whitelist", label: "Whitelist the false-positive pairs", required: true, options: ["no", "yes"] },
              ]} />
            <Action label="Confirm true match" variant="danger" path={`/v1/risk/sanctions/results/${id}/review`} invalidate={inv}
              title="Confirm sanctions match"
              description="Confirms the subject is a listed party. Four-eyes: you must not be the analyst who dispositioned the matches. Downstream blocking and reporting follow."
              body={(v) => ({ status: "CONFIRMED_MATCH", decision_reason: v.decision_reason })}
              fields={[{ name: "decision_reason", label: "Decision reason", type: "textarea", required: true }]} />
          </>}
        </>} />
      <ErrorBanner error={result.error} />
      {r && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <Card title="Result">
            <Details cols={1} items={[
              ["Subject ID", <Mono key="s">{String(r.subject_id)}</Mono>],
              ["Highest score", r.highest_score],
              ["Matches", r.match_count],
              ["Suppressed (whitelisted)", r.suppressed_count],
              ["PEP", r.is_pep],
              ["Adverse media", r.has_adverse_media],
              ["Reviewed by", r.reviewed_by],
              ["Reviewed at", r.reviewed_at],
              ["Decision reason", r.decision_reason],
              ["Rescreen due", r.rescreen_due_at],
              ["Latency", r.latency_ms !== null && r.latency_ms !== undefined ? `${String(r.latency_ms)} ms` : null],
              ["Case", r.aml_case_id ? <Link key="k" className="text-accent hover:underline" href={`/risk/cases/${r.aml_case_id}`}>
                Open case</Link> : null],
            ]} />
          </Card>
          <Card title="Screened subject">
            <ErrorBanner error={request.error} />
            {req ? (
              <>
                <Details cols={1} items={[
                  ["Full name", req.full_name],
                  ["Date of birth", req.date_of_birth],
                  ["Country", req.country],
                  ["Nationality", req.nationality],
                  ["Trigger", String(req.trigger ?? "").replaceAll("_", " ")],
                  ["Requested by", req.requested_by_service],
                  ["Threshold", req.threshold],
                  ["Lists", req.list_codes],
                ]} />
                {req.payload ? <div className="mt-4"><Json value={req.payload} /></div> : null}
              </>
            ) : request.isLoading ? <Loading /> : null}
          </Card>
          <Card title="Candidate matches" className="lg:col-span-2">
            <ErrorBanner error={matches.error} />
            {matches.isLoading ? <Loading /> : (
              <Table rows={matches.rows} empty="No candidate matches."
                columns={[
                  { key: "matched_name", header: "Matched name", render: (m) => <span className="font-medium">{String(m.matched_name)}</span> },
                  { key: "entry", header: "List entry", render: (m) => <MatchEntry entryId={String(m.sanctions_list_entry_id)} /> },
                  { key: "match_score", header: "Score", align: "right" },
                  { key: "match_algorithm", header: "Algorithm" },
                  { key: "disposition", header: "Disposition", render: (m) => <Badge value={String(m.disposition)}
                    tone={m.disposition === "TRUE_MATCH" ? "bad" : m.disposition === "FALSE_POSITIVE" ? "good" : "warn"} /> },
                  { key: "dispositioned_by", header: "By" },
                  { key: "actions", header: "", render: (m) => reviewable && m.disposition === "PENDING" ? (
                    <span className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                      <Action size="sm" label="Not them" variant="secondary" method="PATCH"
                        path={`/v1/risk/sanctions/matches/${m.id}`} body={{ disposition: "FALSE_POSITIVE" }} invalidate={inv}
                        title={`Discount match: ${String(m.matched_name)}`}
                        fields={[{ name: "disposition_reason", label: "Reason", type: "textarea", required: true }]} />
                      <Action size="sm" label="True match" variant="danger" method="PATCH"
                        path={`/v1/risk/sanctions/matches/${m.id}`} body={{ disposition: "TRUE_MATCH" }} invalidate={inv}
                        title={`Mark true match: ${String(m.matched_name)}`}
                        fields={[{ name: "disposition_reason", label: "Reason", type: "textarea", required: true }]} />
                    </span>
                  ) : <span className="text-xs text-muted">{String(m.disposition_reason ?? "")}</span> },
                ]} />
            )}
            <Pager p={matches} />
          </Card>
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><ScreeningResult /></Suspense>;
}
