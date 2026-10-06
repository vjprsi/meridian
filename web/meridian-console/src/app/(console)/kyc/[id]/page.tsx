"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader } from "@/components/ui";
import { Action, Details, Pager, usePaged, type Row } from "@/components/ops";
import { ChecksTable, DocumentsTable, ReviewsTable, RiskAssessmentsTable, RiskBadge, ScreeningsTable } from "../kyc-tables";

function Flag({ ok, label }: { ok: unknown; label: string }) {
  return <Badge value={`${label} ${ok ? "✓" : "✗"}`} tone={ok ? "good" : "warn"} />;
}

function KycProfile() {
  const { id } = useParams<{ id: string }>();
  const profile = useQuery({ queryKey: ["kyc", "profile", id], queryFn: () => call<Row>("GET", `/v1/kyc/profiles/${id}`) });
  const checks = usePaged("kyc", `/v1/kyc/profiles/${id}/checks`);
  const assessments = usePaged("kyc", `/v1/kyc/profiles/${id}/risk-assessments`);
  const docs = usePaged("kyc", "/v1/kyc/documents", { kyc_profile_id: id });
  const screenings = usePaged("kyc", "/v1/kyc/screenings", { kyc_profile_id: id });
  const reviews = usePaged("kyc", "/v1/kyc/reviews", { kyc_profile_id: id });
  const p = profile.data;
  const inv = [["kyc"]];

  if (profile.isLoading) return <Loading />;
  return (
    <>
      <PageHeader title="KYC profile"
        subtitle={p && <><Mono>{String(p.id)}</Mono> · tier {String(p.achieved_tier ?? "—")} · <RiskBadge value={p.risk_level} /> · <Badge value={String(p.status)} /></>}
        actions={p && <>
          <Action label="Re-evaluate" variant="secondary" path={`/v1/kyc/profiles/${id}/evaluate`}
            description="Recomputes tier, risk and status from the current evidence (checks, documents, screenings)." invalidate={inv} />
          {!p.edd_required && (
            <Action label="Require EDD" variant="danger" path={`/v1/kyc/profiles/${id}/require-edd`}
              description="Flags the customer for enhanced due diligence; restrictions may apply until EDD is completed."
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} invalidate={inv} />
          )}
          {!!p.edd_required && !p.edd_completed_at && (
            <Action label="Complete EDD" path={`/v1/kyc/profiles/${id}/complete-edd`}
              fields={[{ name: "notes", label: "EDD findings", type: "textarea", required: true }]} invalidate={inv} />
          )}
        </>} />
      <ErrorBanner error={profile.error} />
      {p && (
        <div className="grid grid-cols-1 gap-6">
          <Card title="Profile">
            <div className="mb-4 flex flex-wrap gap-2">
              <Flag ok={p.identity_verified} label="Identity" />
              <Flag ok={p.address_verified} label="Address" />
              <Flag ok={p.screening_clear} label="Screening" />
              {!!p.edd_required && <Badge value={p.edd_completed_at ? "EDD COMPLETED" : "EDD REQUIRED"} tone={p.edd_completed_at ? "good" : "bad"} />}
            </div>
            <Details items={[
              ["Customer", <Link key="c" className="text-accent hover:underline" href={`/customers/${p.customer_id}`}>{String(p.customer_id)}</Link>],
              ["Risk score", p.risk_score],
              ["Verified", p.verified_at], ["Expires", p.expires_at],
              ["Next review", p.next_review_date], ["EDD completed", p.edd_completed_at],
              ["Last reviewed by", p.last_reviewed_by], ["Updated", p.updated_at],
              ["Notes", p.notes],
            ]} />
          </Card>
          <Section title="Checks" q={checks}><ChecksTable rows={checks.rows} /></Section>
          <Section title="Documents" q={docs}><DocumentsTable rows={docs.rows} /></Section>
          <Section title="Screenings" q={screenings}><ScreeningsTable rows={screenings.rows} /></Section>
          <Section title="Risk assessments" q={assessments}><RiskAssessmentsTable rows={assessments.rows} /></Section>
          <Section title="Reviews" q={reviews}><ReviewsTable rows={reviews.rows} /></Section>
        </div>
      )}
    </>
  );
}

function Section({ title, q, children }: { title: string; q: ReturnType<typeof usePaged>; children: React.ReactNode }) {
  return (
    <Card title={title}>
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : children}
      <Pager p={q} />
    </Card>
  );
}

export default function Page() {
  return <Suspense><KycProfile /></Suspense>;
}
