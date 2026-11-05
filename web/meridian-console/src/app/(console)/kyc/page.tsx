"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, PageHeader, Table } from "@/components/ui";
import { Filters, Pager, Tabs, dateCell, usePaged, type FilterSpec } from "@/components/ops";
import { EntityForm, type FormSection, type FormValues } from "@/components/entity-form";
import {
  kycCheckFormSections, kycDocumentFormSections, kycProfileFormSections, kycReviewFormSections, kycScreeningFormSections,
} from "@/lib/entities/kyc";
import { ChecksTable, DocumentsTable, ProfileLink, ReviewsTable, RiskBadge, ScreeningsTable } from "./kyc-tables";

type Tab = "reviews" | "checks" | "documents" | "screenings" | "profiles";

const TABS: { id: Tab; label: string }[] = [
  { id: "reviews", label: "Periodic reviews" },
  { id: "checks", label: "Checks" },
  { id: "documents", label: "Documents" },
  { id: "screenings", label: "Screenings" },
  { id: "profiles", label: "Profiles" },
];

const RISK = ["LOW", "MEDIUM", "HIGH", "PROHIBITED"];

/** Per tab: endpoint, filters and the default filter that makes it a work queue. */
const CONFIG: Record<Tab, { path: string; filters: FilterSpec[]; initial: Record<string, string> }> = {
  reviews: {
    path: "/v1/kyc/reviews",
    initial: { status: "DUE" },
    filters: [
      { name: "status", label: "Status", options: ["SCHEDULED", "DUE", "IN_PROGRESS", "COMPLETED", "OVERDUE", "CANCELLED"] },
      { name: "trigger", label: "Trigger", options: ["PERIODIC", "DOCUMENT_EXPIRY", "RISK_CHANGE", "SCREENING_HIT", "CUSTOMER_CHANGE", "REGULATOR_REQUEST", "MANUAL"] },
      { name: "due_date", label: "Due date", type: "date" },
      { name: "assigned_to", label: "Assignee", placeholder: "user uuid" },
    ],
  },
  checks: {
    path: "/v1/kyc/checks",
    initial: { status: "FAILED" },
    filters: [
      { name: "status", label: "Status", options: ["PENDING", "IN_PROGRESS", "PASSED", "FAILED", "INCONCLUSIVE", "ERROR", "EXPIRED"] },
      { name: "check_type", label: "Check type", options: ["DOCUMENT_OCR", "DOCUMENT_AUTHENTICITY", "LIVENESS", "FACE_MATCH",
        "DATABASE_VERIFICATION", "ADDRESS_VERIFICATION", "PHONE_VERIFICATION", "EMAIL_VERIFICATION", "REGISTRY_LOOKUP",
        "UBO_DISCOVERY", "TAX_ID_VERIFICATION", "BANK_ACCOUNT_VERIFICATION"] },
      { name: "onboarding_application_id", label: "Application ID", placeholder: "uuid" },
    ],
  },
  documents: {
    path: "/v1/kyc/documents",
    initial: { status: "UPLOADED" },
    filters: [
      { name: "status", label: "Status", options: ["UPLOADED", "PROCESSING", "ACCEPTED", "REJECTED", "EXPIRED"] },
      { name: "document_type", label: "Type", options: ["NATIONAL_ID", "PASSPORT", "DRIVING_LICENCE", "RESIDENCE_PERMIT",
        "UTILITY_BILL", "BANK_STATEMENT", "PROOF_OF_INCOME", "CERTIFICATE_OF_INCORPORATION", "ARTICLES_OF_ASSOCIATION",
        "SHAREHOLDER_REGISTER", "BOARD_RESOLUTION", "TAX_CERTIFICATE", "SELFIE", "OTHER"] },
      { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
    ],
  },
  screenings: {
    path: "/v1/kyc/screenings",
    initial: { status: "POTENTIAL_MATCH" },
    filters: [
      { name: "status", label: "Status", options: ["CLEAR", "POTENTIAL_MATCH", "CONFIRMED_MATCH", "FALSE_POSITIVE", "ERROR"] },
      { name: "screening_type", label: "Type", options: ["SANCTIONS", "PEP", "ADVERSE_MEDIA", "WATCHLIST", "INTERNAL_BLACKLIST"] },
      { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
    ],
  },
  profiles: {
    path: "/v1/kyc/profiles",
    initial: { status: "REFER" },
    filters: [
      { name: "status", label: "Status", options: ["NOT_STARTED", "IN_PROGRESS", "VERIFIED", "REFER", "REJECTED", "EXPIRED", "REVIEW_DUE"] },
      { name: "risk_level", label: "Risk", options: RISK },
      { name: "edd_required", label: "EDD required", options: ["true", "false"] },
      { name: "customer_id", label: "Customer ID", placeholder: "uuid" },
    ],
  },
};

/** Per tab: the create/initiate flow — its form sections, endpoint, button label and seed values. */
const CREATE: Record<Tab, { sections: FormSection[]; path: string; label: string; initial?: FormValues }> = {
  reviews: { sections: kycReviewFormSections, path: "/v1/kyc/reviews", label: "Open review", initial: { trigger: "MANUAL" } },
  checks: { sections: kycCheckFormSections, path: "/v1/kyc/checks", label: "Initiate check", initial: { check_type: "DOCUMENT_OCR" } },
  documents: { sections: kycDocumentFormSections, path: "/v1/kyc/documents", label: "Register document", initial: { document_type: "NATIONAL_ID" } },
  screenings: { sections: kycScreeningFormSections, path: "/v1/kyc/screenings", label: "Run screening", initial: { screening_type: "SANCTIONS", trigger: "MANUAL" } },
  profiles: { sections: kycProfileFormSections, path: "/v1/kyc/profiles", label: "Open profile" },
};

function KycWorkbench() {
  const router = useRouter();
  const path = usePathname();
  const params = useSearchParams();
  const qc = useQueryClient();
  const tab = (TABS.some((t) => t.id === params.get("tab")) ? params.get("tab") : "reviews") as Tab;
  const cfg = CONFIG[tab];
  const create = CREATE[tab];
  const [byTab, setByTab] = useState<Partial<Record<Tab, Record<string, string>>>>({});
  const [open, setOpen] = useState(false);
  const filters = byTab[tab] ?? cfg.initial;
  const list = usePaged("kyc", cfg.path, filters);
  const createM = useMutation({
    mutationFn: (body: Record<string, unknown>) => call("POST", create.path, body),
    onSuccess: (r) => {
      qc.invalidateQueries({ queryKey: ["kyc"] });
      setOpen(false);
      const id = (r as { id?: unknown } | null)?.id;
      if (tab === "profiles" && id) router.push(`/kyc/${id}`);
    },
  });

  return (
    <>
      <PageHeader title="KYC checks" subtitle="Verification checks, documents awaiting review, screening hits and periodic reviews"
        actions={<Button onClick={() => setOpen(!open)}>{open ? "Cancel" : create.label}</Button>} />
      <Tabs tabs={TABS} value={tab} onChange={(t) => { setOpen(false); router.replace(`${path}?tab=${t}`); }} />
      {open && (
        <div className="mb-8">
          <EntityForm key={tab} sections={create.sections} submitLabel={create.label} initial={create.initial ?? {}}
            pending={createM.isPending} error={createM.error} onSubmit={(body) => createM.mutate(body)} />
        </div>
      )}
      <Card>
        <Filters key={tab} specs={cfg.filters} value={filters} onChange={(v) => setByTab({ ...byTab, [tab]: v })} />
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          tab === "reviews" ? <ReviewsTable rows={list.rows} empty="No reviews match." />
          : tab === "checks" ? <ChecksTable rows={list.rows} empty="No checks match." />
          : tab === "documents" ? <DocumentsTable rows={list.rows} empty="No documents match." />
          : tab === "screenings" ? <ScreeningsTable rows={list.rows} empty="No screenings match." />
          : (
            <Table rows={list.rows} onRow={(r) => router.push(`/kyc/${r.id}`)} empty="No profiles match."
              columns={[
                { key: "id", header: "Profile", render: (r) => <ProfileLink id={r.id} /> },
                { key: "customer_id", header: "Customer", render: (r) => <span className="font-mono text-xs">{String(r.customer_id).slice(0, 8)}…</span> },
                { key: "achieved_tier", header: "Tier" },
                { key: "risk_level", header: "Risk", render: (r) => <RiskBadge value={r.risk_level} /> },
                { key: "risk_score", header: "Score", align: "right" },
                { key: "edd_required", header: "EDD", render: (r) => r.edd_required
                  ? <Badge value={r.edd_completed_at ? "EDD DONE" : "EDD REQUIRED"} tone={r.edd_completed_at ? "good" : "warn"} /> : "—" },
                { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} tone={r.status === "VERIFIED" ? "good" : r.status === "REFER" || r.status === "REVIEW_DUE" ? "warn" : undefined} /> },
                { key: "next_review_date", header: "Next review" },
                { key: "verified_at", header: "Verified", render: dateCell("verified_at") },
              ]} />
          )
        )}
        <Pager p={list} />
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><KycWorkbench /></Suspense>;
}
