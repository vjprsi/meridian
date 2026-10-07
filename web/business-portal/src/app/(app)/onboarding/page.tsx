"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api, idem, unwrap } from "@/api/client";
import type { OnboardingApplication, OnboardingStep } from "@/api/types";
import { ActionButton } from "@/components/actions";
import { AdminOnly } from "@/components/admin-only";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Notice, PageHeader, Select, Summary, Table,
} from "@/components/ui";

/**
 * KYB (know your business) through the business channel only (/v1/business/onboarding/*): the bank's published
 * business journeys, the business's own applications, their steps (company details, directors and UBOs), documents
 * and submission. Ownership is enforced by the channel; risk scores and review notes are never shown here.
 */

const DOC_TYPES = ["CORPORATE_DOCUMENT", "CERTIFICATE", "ID_CARD", "PASSPORT", "PROOF_OF_ADDRESS", "BANK_STATEMENT", "OTHER"];

type Party = { name: string; role: string; ownership_percentage: string; country: string };

interface StepField { name: string; type?: string; label_key?: string; required?: boolean; enum?: string[] }

function fieldsOf(step: OnboardingStep | undefined): StepField[] {
  const raw = (step as unknown as { fields?: unknown })?.fields;
  if (!Array.isArray(raw)) return [];
  return raw.map((f) => (typeof f === "string" ? { name: f } : (f as StepField))).filter((f) => !!f?.name);
}

/** Directors, shareholders and UBOs declared on a UBO_DECLARATION step. */
function PartiesForm({ step, onSubmit, pending }: { step: OnboardingStep; onSubmit: (input: Record<string, unknown>) => void; pending: boolean }) {
  const [rows, setRows] = useState<Party[]>([{ name: "", role: "DIRECTOR", ownership_percentage: "", country: "" }]);
  const set = (i: number, k: keyof Party, v: string) => setRows((r) => r.map((x, j) => (j === i ? { ...x, [k]: v } : x)));
  function submit(e: FormEvent) {
    e.preventDefault();
    const clean = rows.filter((r) => r.name.trim());
    onSubmit({
      directors: clean.filter((r) => r.role === "DIRECTOR").map((r) => ({ name: r.name.trim(), country: r.country || undefined })),
      ubos: clean.filter((r) => r.role !== "DIRECTOR").map((r) => ({ name: r.name.trim(), role: r.role,
        ownership_percentage: r.ownership_percentage || undefined, country: r.country || undefined })),
    });
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-3" aria-label={`Step ${step.step_code}`}>
      <p className="text-muted">Declare every director and every person owning or controlling 25% or more.</p>
      {rows.map((r, i) => (
        <div key={i} className="grid gap-2 sm:grid-cols-4">
          <Field label={`Name #${i + 1}`}><Input value={r.name} onChange={(e) => set(i, "name", e.target.value)} required={i === 0} /></Field>
          <Field label={`Role #${i + 1}`}>
            <Select value={r.role} onChange={(e) => set(i, "role", e.target.value)} options={[
              { value: "DIRECTOR", label: "Director" }, { value: "SHAREHOLDER", label: "Shareholder" },
              { value: "ULTIMATE_BENEFICIAL_OWNER", label: "Ultimate beneficial owner" }]} />
          </Field>
          <Field label={`Ownership % #${i + 1}`} optional>
            <Input value={r.ownership_percentage} inputMode="decimal" onChange={(e) => set(i, "ownership_percentage", e.target.value)} />
          </Field>
          <Field label={`Country #${i + 1}`} optional>
            <Input value={r.country} maxLength={2} onChange={(e) => set(i, "country", e.target.value.toUpperCase())} />
          </Field>
        </div>
      ))}
      <div className="flex flex-wrap justify-between gap-2">
        <Button variant="secondary" size="sm" onClick={() => setRows((r) => [...r, { name: "", role: "ULTIMATE_BENEFICIAL_OWNER", ownership_percentage: "", country: "" }])}>
          Add person
        </Button>
        <Button type="submit" disabled={pending}>{pending ? "Saving…" : "Save declaration"}</Button>
      </div>
    </form>
  );
}

/** Generic step: the journey's typed fields, else free key/value pairs. */
function StepForm({ step, onSubmit, pending }: { step: OnboardingStep; onSubmit: (input: Record<string, unknown>) => void; pending: boolean }) {
  const fields = fieldsOf(step);
  const [values, setValues] = useState<Record<string, string>>({});
  function submit(e: FormEvent) {
    e.preventDefault();
    onSubmit(Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== "")));
  }
  const shown: StepField[] = fields.length > 0 ? fields.filter((f) => f.type !== "DOCUMENT")
    : [{ name: "legal_name", required: true }, { name: "registration_number", required: true }, { name: "country" }];
  return (
    <form onSubmit={submit} className="flex flex-col gap-3" aria-label={`Step ${step.step_code}`}>
      <div className="grid gap-3 sm:grid-cols-2">
        {shown.map((f) => (
          <Field key={f.name} label={f.name.replaceAll("_", " ")} optional={!f.required}>
            {f.enum ? (
              <Select value={values[f.name] ?? ""} onChange={(e) => setValues((s) => ({ ...s, [f.name]: e.target.value }))}
                options={f.enum.map((v) => ({ value: v, label: v }))} placeholder="—" />
            ) : (
              <Input value={values[f.name] ?? ""} required={f.required}
                type={f.type === "DATE" ? "date" : f.type === "EMAIL" ? "email" : "text"}
                onChange={(e) => setValues((s) => ({ ...s, [f.name]: e.target.value }))} />
            )}
          </Field>
        ))}
      </div>
      <div className="flex justify-end"><Button type="submit" disabled={pending}>{pending ? "Saving…" : "Save step"}</Button></div>
    </form>
  );
}

async function sha256Hex(file: File): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function Documents({ app, steps }: { app: OnboardingApplication; steps: OnboardingStep[] }) {
  const qc = useQueryClient();
  const r = useRun();
  const [file, setFile] = useState<File | null>(null);
  const [step, setStep] = useState(steps.find((s) => s.step_type === "DOCUMENT_CAPTURE")?.step_code ?? steps[0]?.step_code ?? "");
  const [type, setType] = useState(DOC_TYPES[0]);
  const docs = useQuery({
    queryKey: ["kyb-docs", app.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/applications/{application_id}/documents",
      { params: { path: { application_id: app.id }, query: { limit: 100 } } })).data,
  });
  async function upload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    const res = await r.run(async (key) => {
      const reg = unwrap(await api.POST("/v1/business/onboarding/applications/{application_id}/documents", {
        params: { path: { application_id: app.id }, header: idem(key) },
        body: { step_code: step, document_type: type, file_name: file.name, content_type: file.type || "application/pdf" } })) as
        { document_id: string; url?: string; method?: string; headers?: Record<string, string> };
      if (reg.url) {
        await fetch(reg.url, { method: reg.method ?? "PUT", headers: reg.headers ?? { "Content-Type": file.type }, body: file });
      }
      return unwrap(await api.POST("/v1/business/onboarding/applications/{application_id}/documents/{document_id}/complete", {
        params: { path: { application_id: app.id, document_id: reg.document_id }, header: idem(key + "-complete") },
        body: { size_bytes: file.size, checksum: await sha256Hex(file) } }));
    });
    if (res.ok) {
      setFile(null);
      await qc.invalidateQueries({ queryKey: ["kyb-docs", app.id] });
    }
  }
  return (
    <div className="space-y-3">
      <form onSubmit={upload} className="grid gap-3 sm:grid-cols-4 sm:items-end" aria-label="Upload a document">
        <Field label="For step"><Select value={step} onChange={(e) => setStep(e.target.value)}
          options={steps.map((s) => ({ value: s.step_code, label: s.step_code }))} /></Field>
        <Field label="Document type"><Select value={type} onChange={(e) => setType(e.target.value)}
          options={DOC_TYPES.map((t) => ({ value: t, label: t.replaceAll("_", " ").toLowerCase() }))} /></Field>
        <Field label="File"><Input type="file" accept="application/pdf,image/png,image/jpeg"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>
        <Button type="submit" disabled={!file || r.pending}>{r.pending ? "Uploading…" : "Upload"}</Button>
      </form>
      <ErrorBanner error={r.error} />
      <Async q={docs} empty={<Empty title="No documents yet" />}>
        {(rows) => (
          <Table rows={rows} rowKey={(d) => d.id} caption="Documents" columns={[
            { header: "File", cell: (d) => d.file_name },
            { header: "Type", cell: (d) => d.document_type },
            { header: "Uploaded", cell: (d) => <DateTime value={d.created_at} /> },
            { header: "Status", cell: (d) => <Badge value={d.status} /> },
          ]} />
        )}
      </Async>
    </div>
  );
}

function Application({ app }: { app: OnboardingApplication }) {
  const qc = useQueryClient();
  const r = useRun();
  const open = app.status === "STARTED" || app.status === "IN_PROGRESS";
  const steps = useQuery({
    queryKey: ["kyb-steps", app.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/applications/{application_id}/steps",
      { params: { path: { application_id: app.id }, query: { limit: 100 } } })).data,
  });
  const next = useQuery({
    queryKey: ["kyb-next", app.id], enabled: open,
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/applications/{application_id}/next-step",
      { params: { path: { application_id: app.id } } })),
  });
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ["kyb-steps", app.id] }),
    qc.invalidateQueries({ queryKey: ["kyb-next", app.id] }), qc.invalidateQueries({ queryKey: ["kyb-applications"] })]);
  async function save(step: OnboardingStep, input: Record<string, unknown>) {
    const res = await r.run(async (key) => unwrap(await api.POST("/v1/business/onboarding/applications/{application_id}/steps/{step_code}", {
      params: { path: { application_id: app.id, step_code: step.step_code }, header: idem(key) }, body: { input } })));
    if (res.ok) await refresh();
  }
  const current = next.data?.step_code ? next.data : undefined;
  return (
    <div className="space-y-5">
      <Summary cols={4} items={[
        { label: "Application", value: <span className="font-mono text-xs">{app.application_number}</span> },
        { label: "Status", value: <Badge value={app.status} /> },
        { label: "Progress", value: `${app.progress_percent}%` },
        { label: "Started", value: <DateTime value={app.created_at} /> },
      ]} />
      {(app.info_requests as unknown[] | null | undefined)?.length ? (
        <Notice tone="warn">The bank asked for more information; see the steps marked pending.</Notice>
      ) : null}
      <Async q={steps} empty={<Empty title="No steps" />}>
        {(rows) => (
          <Table rows={rows} rowKey={(s) => s.id} caption="Steps" columns={[
            { header: "#", cell: (s) => s.sequence },
            { header: "Step", cell: (s) => s.step_code },
            { header: "Type", cell: (s) => s.step_type.replaceAll("_", " ").toLowerCase() },
            { header: "Status", cell: (s) => <Badge value={s.status} /> },
          ]} />
        )}
      </Async>
      {open && current && (
        <Card title={`Next: ${current.step_code}`}>
          {current.step_type === "UBO_DECLARATION"
            ? <PartiesForm key={current.step_code} step={current} pending={r.pending} onSubmit={(i) => save(current, i)} />
            : <StepForm key={current.step_code} step={current} pending={r.pending} onSubmit={(i) => save(current, i)} />}
          <ErrorBanner error={r.error} className="mt-3" />
        </Card>
      )}
      {open && steps.data && <Card title="Documents"><Documents app={app} steps={steps.data} /></Card>}
      {open && (
        <div className="flex justify-end">
          <ActionButton label="Submit for review" variant="primary" size="md" confirmLabel="Submit"
            title="Submit the application to the bank?"
            description="The bank reviews the business, its directors and owners. You can no longer change the application afterwards."
            run={async (_v, key) => unwrap(await api.POST("/v1/business/onboarding/applications/{application_id}/submit", {
              params: { path: { application_id: app.id }, header: idem(key) } }))}
            onDone={() => refresh()} />
        </div>
      )}
    </div>
  );
}

export default function OnboardingPage() {
  const [openId, setOpenId] = useState<string | null>(null);
  const journeys = useQuery({
    queryKey: ["kyb-journeys"],
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/journeys", { params: { query: { limit: 50 } } })).data,
  });
  const apps = useQuery({
    queryKey: ["kyb-applications"],
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/applications", { params: { query: { limit: 50 } } })).data,
  });
  const parties = useQuery({
    queryKey: ["kyb-parties"],
    queryFn: async () => unwrap(await api.GET("/v1/business/onboarding/related-parties", { params: { query: { limit: 100 } } })).data,
  });
  const open = apps.data?.find((a) => a.id === openId);
  return (
    <AdminOnly>
      <PageHeader title="Business verification" subtitle="KYB: company details, directors and beneficial owners, documents"
        actions={(
          <ActionButton<OnboardingApplication> label="Start application" variant="primary" size="md" confirmLabel="Start"
            fields={[{ name: "onboarding_journey_id", label: "Journey", type: "select", required: true,
              options: (journeys.data ?? []).map((j) => ({ value: j.id, label: j.name })) }]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/onboarding/applications", {
              params: { header: idem(key) }, body: { onboarding_journey_id: v.onboarding_journey_id } }))}
            onDone={(a) => setOpenId(a.id)} />
        )} />
      <Card flush>
        <Async q={apps} empty={<div className="p-4"><Empty title="No applications yet">Start one on the bank&apos;s business journey.</Empty></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(a) => a.id} caption="KYB applications" columns={[
              { header: "Application", cell: (a) => <span className="font-mono text-xs">{a.application_number}</span> },
              { header: "Started", cell: (a) => <DateTime value={a.created_at} /> },
              { header: "Progress", cell: (a) => `${a.progress_percent}%` },
              { header: "Status", cell: (a) => <Badge value={a.status} /> },
              { header: "", align: "right", cell: (a) => (
                <Button size="sm" variant="secondary" onClick={() => setOpenId(openId === a.id ? null : a.id)}>
                  {openId === a.id ? "Hide" : "Open"}
                </Button>
              ) },
            ]} />
          )}
        </Async>
      </Card>
      {open && <Card className="mt-5" title={`Application ${open.application_number}`}><Application app={open} /></Card>}
      <Card className="mt-5" title="Directors, owners and signatories on record" flush>
        <Async q={parties} empty={<div className="p-4"><Empty title="None on record yet" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(p) => p.id} caption="Related parties" columns={[
              { header: "Relationship", cell: (p) => <Badge value={p.relationship_type} tone="muted" /> },
              { header: "Position", cell: (p) => p.position_title ?? "—" },
              { header: "Ownership", cell: (p) => (p.ownership_percentage ? `${p.ownership_percentage}%` : "—") },
              { header: "Verified", cell: (p) => <Badge value={p.verified ? "VERIFIED" : "PENDING"} /> },
            ]} />
          )}
        </Async>
      </Card>
    </AdminOnly>
  );
}
