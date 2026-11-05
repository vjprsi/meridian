"use client";

import { useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Mono, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, dateCell, statusCell, usePaged, type FilterSpec } from "@/components/ops";
import { EntityForm } from "@/components/entity-form";
import {
  APPLICATION_ARRAY_FIELDS, APPLICATION_JSON_FIELDS, JOURNEY_ARRAY_FIELDS, JOURNEY_JSON_FIELDS,
  onboardingApplicationFormSections, onboardingJourneyFormSections, transformOnboardingBody,
} from "@/lib/entities/onboarding";

const STATUSES = ["STARTED", "IN_PROGRESS", "SUBMITTED", "UNDER_REVIEW", "REFERRED", "APPROVED", "REJECTED", "ABANDONED", "EXPIRED"];

const FILTERS: FilterSpec[] = [
  { name: "status", label: "Status", options: STATUSES },
  { name: "decision", label: "Decision", options: ["AUTO_APPROVE", "REFER", "REJECT"] },
  { name: "risk_level", label: "Risk", options: ["LOW", "MEDIUM", "HIGH", "PROHIBITED"] },
  { name: "channel", label: "Channel", options: ["MOBILE_APP", "WEB", "USSD", "AGENT", "BRANCH", "API"] },
  { name: "created_after", label: "Created after", type: "date" },
  { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
];

/** Queue presets: the review queue is what a checker works through; "all" is for search. */
const QUEUES: { id: string; label: string; status?: string }[] = [
  { id: "referred", label: "Referred", status: "REFERRED" },
  { id: "review", label: "Under review", status: "UNDER_REVIEW" },
  { id: "submitted", label: "Submitted", status: "SUBMITTED" },
  { id: "all", label: "All" },
];

function riskTone(v: unknown) {
  return v === "HIGH" || v === "PROHIBITED" ? "bad" : v === "MEDIUM" ? "warn" : v === "LOW" ? "good" : "muted";
}

type CreateForm = "journey" | "application" | null;

function OnboardingQueue() {
  const router = useRouter();
  const qc = useQueryClient();
  const [filters, setFilters] = useState<Record<string, string>>({ status: "REFERRED" });
  const [form, setForm] = useState<CreateForm>(null);
  const created = filters.created_after ? `${filters.created_after}T00:00:00Z` : undefined;
  const list = usePaged("onboarding-applications", "/v1/onboarding/applications", { ...filters, created_after: created });

  const createJourney = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      call<{ id: string }>("POST", "/v1/onboarding/journeys", transformOnboardingBody(body, JOURNEY_ARRAY_FIELDS, JOURNEY_JSON_FIELDS)),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["onboarding-journeys"] }); setForm(null); },
  });
  const createApplication = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      call<{ id: string }>("POST", "/v1/onboarding/applications", transformOnboardingBody(body, APPLICATION_ARRAY_FIELDS, APPLICATION_JSON_FIELDS)),
    onSuccess: (a) => { qc.invalidateQueries({ queryKey: ["onboarding-applications"] }); router.push(`/onboarding/${a.id}`); },
  });

  return (
    <>
      <PageHeader title="Onboarding queue" subtitle="Applications from every channel; referred applications need a checker decision"
        actions={<>
          <Button variant={form === "journey" ? "secondary" : "primary"} onClick={() => setForm(form === "journey" ? null : "journey")}>
            {form === "journey" ? "Cancel" : "New journey"}
          </Button>
          <Button variant={form === "application" ? "secondary" : "primary"} onClick={() => setForm(form === "application" ? null : "application")}>
            {form === "application" ? "Cancel" : "New application"}
          </Button>
        </>} />
      {form === "journey" && (
        <div className="mb-8">
          <EntityForm sections={onboardingJourneyFormSections} submitLabel="Create journey"
            initial={{ customer_type: "INDIVIDUAL", target_tier: "TIER_1", expiry_days: "30" }}
            pending={createJourney.isPending} error={createJourney.error}
            onSubmit={(body) => createJourney.mutate(body)} />
        </div>
      )}
      {form === "application" && (
        <div className="mb-8">
          <EntityForm sections={onboardingApplicationFormSections} submitLabel="Create application"
            initial={{ channel: "BRANCH" }}
            pending={createApplication.isPending} error={createApplication.error}
            onSubmit={(body) => createApplication.mutate(body)} />
        </div>
      )}
      <div className="mb-4 flex flex-wrap gap-2">
        {QUEUES.map((q) => {
          const active = (filters.status ?? "") === (q.status ?? "");
          return (
            <button key={q.id} onClick={() => setFilters({ ...filters, status: q.status ?? "" })}
              className={`rounded-full border px-3 py-1 text-xs font-medium ${active
                ? "border-accent bg-accent-soft text-accent" : "border-border text-muted hover:text-text"}`}>
              {q.label}
            </button>
          );
        })}
      </div>
      <Card>
        <Filters key={JSON.stringify(filters)} specs={FILTERS} value={filters} onChange={setFilters} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows} onRow={(r) => router.push(`/onboarding/${r.id}`)}
            empty="No applications match these filters."
            columns={[
              { key: "application_number", header: "Application", render: (r) => <span className="font-medium">{String(r.application_number ?? "—")}</span> },
              { key: "channel", header: "Channel" },
              { key: "current_step", header: "Current step", render: (r) => <Mono>{String(r.current_step ?? "—")}</Mono> },
              { key: "progress_percent", header: "Progress", align: "right", render: (r) => `${r.progress_percent ?? 0}%` },
              { key: "risk_level", header: "Risk", render: (r) => r.risk_level
                ? <Badge value={String(r.risk_level)} tone={riskTone(r.risk_level)} /> : <span className="text-muted">—</span> },
              { key: "decision", header: "Decision", render: (r) => r.decision ? <Badge value={String(r.decision)} /> : <span className="text-muted">—</span> },
              { key: "status", header: "Status", render: statusCell() },
              { key: "submitted_at", header: "Submitted", render: dateCell("submitted_at") },
              { key: "created_at", header: "Started", render: dateCell("created_at") },
            ]} />
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><OnboardingQueue /></Suspense>;
}
