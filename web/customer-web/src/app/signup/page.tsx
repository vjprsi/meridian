"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ApiError, idem, kyc, onboarding, unwrap, uploadDocument } from "@/api/client";
import type { OnboardingApplication, OnboardingJourney, OnboardingStep } from "@/api/types";
import { DEFAULT_TENANT, authHeaders, loadSession } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { FileField, checkFile } from "@/components/file-field";
import { Preferences } from "@/components/shell";
import {
  Async, Badge, Button, Card, Empty, ErrorBanner, Field, Input, Loading, Notice, Summary,
} from "@/components/ui";

/**
 * Self sign-up, driven by the tenant's published onboarding journey (server-driven UI):
 * create application -> next-step -> submit the step's fields (documents go to document-service first) -> ... ->
 * submit -> status (decision, KYC checks). The application id is kept in sessionStorage so a reload resumes it.
 */
const APP_KEY = "meridian.signup.application";
const TENANT_KEY = "meridian.signup.tenant";

interface StepDef { code: string; type: string; fields?: string[]; required?: boolean }

function stepsOf(j?: OnboardingJourney | null): StepDef[] {
  const def = j?.definition as { steps?: StepDef[] } | undefined;
  return Array.isArray(def?.steps) ? def!.steps! : [];
}

function fieldKind(name: string): "file" | "date" | "email" | "tel" | "check" | "text" {
  const n = name.toLowerCase();
  if (n.endsWith("document_id") || n.endsWith("_document") || n.includes("selfie")) return "file";
  if (n.includes("date") || n === "dob") return "date";
  if (n.includes("email")) return "email";
  if (n.includes("phone") || n.includes("msisdn") || n.includes("mobile")) return "tel";
  if (/^(accept|consent|agree|terms)|_(accepted|consent|agreed)$/.test(n)) return "check";
  return "text";
}

function humanize(name: string) {
  const s = name.replace(/_id$/, "").replaceAll("_", " ");
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export default function Signup() {
  const t = useT();
  const [tenant, setTenant] = useState(DEFAULT_TENANT);
  const [appId, setAppId] = useState<string | null>(null);
  const [restored, setRestored] = useState(false);
  useEffect(() => {
    try {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setAppId(sessionStorage.getItem(APP_KEY));
      setTenant(sessionStorage.getItem(TENANT_KEY) ?? DEFAULT_TENANT);
    } catch { /* ignore */ }
    setRestored(true);
  }, []);

  /** Applicants have no customer session yet: use an SSO session if there is one, else dev tenant headers. */
  const headers = loadSession()?.mode === "oidc" ? authHeaders() : { "X-Tenant-Id": tenant, "X-User": "web-signup" };

  function remember(id: string | null) {
    setAppId(id);
    try {
      if (id) { sessionStorage.setItem(APP_KEY, id); sessionStorage.setItem(TENANT_KEY, tenant); }
      else sessionStorage.removeItem(APP_KEY);
    } catch { /* ignore */ }
  }

  return (
    <main id="main" className="mx-auto w-full max-w-2xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/icon.svg" alt="" width={40} height={40} />
          <div>
            <h1 className="text-xl font-semibold">{t("signup.title")}</h1>
            <p className="text-sm text-muted">{t("signup.subtitle")}</p>
          </div>
        </div>
        <Link href="/login" className="prose-link text-sm">{t("signup.haveAccount")}</Link>
      </div>
      {!restored ? <Loading /> : appId
        ? <Application id={appId} headers={headers} onRestart={() => remember(null)} />
        : <Start tenant={tenant} setTenant={setTenant} headers={headers} onCreated={remember} />}
      <section aria-label={t("prefs.title")} className="mt-8"><Preferences /></section>
    </main>
  );
}

function Start({ tenant, setTenant, headers, onCreated }:
  { tenant: string; setTenant: (v: string) => void; headers: Record<string, string>; onCreated: (id: string) => void }) {
  const t = useT();
  const sso = loadSession()?.mode === "oidc";
  const journeys = useQuery({
    queryKey: ["journeys", tenant],
    enabled: /^[0-9a-fA-F-]{36}$/.test(tenant),
    queryFn: async () => unwrap(await onboarding.GET("/v1/onboarding/journeys", {
      params: { query: { status: "PUBLISHED", customer_type: "INDIVIDUAL", limit: 50 } }, headers }))
      .data.filter((j) => !Array.isArray(j.channels) || j.channels.length === 0 || (j.channels as string[]).includes("WEB")),
  });
  const [journeyId, setJourneyId] = useState("");
  const [form, setForm] = useState({ phone: "", email: "" });
  const [err, setErr] = useState<Record<string, string>>({});
  const create = useMutation({
    mutationFn: async (journey: string) => unwrap(await onboarding.POST("/v1/onboarding/applications", {
      params: { header: idem() }, headers,
      body: { onboarding_journey_id: journey, channel: "WEB",
        phone: form.phone.replace(/\s+/g, ""), email: form.email.trim() || undefined },
    })),
    onSuccess: (a) => onCreated(a.id!),
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    const chosen = journeyId || journeys.data?.[0]?.id;
    if (!chosen) er.journey = t("signup.noJourney");
    if (!/^\+?[0-9]{7,15}$/.test(form.phone.replace(/\s+/g, ""))) er.phone = t("profile.contactInvalid");
    if (form.email && !/^\S+@\S+\.\S+$/.test(form.email)) er.email = t("profile.contactInvalid");
    setErr(er);
    if (!Object.keys(er).length && chosen) create.mutate(chosen);
  }

  return (
    <Card title={t("signup.start")}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        {!sso && (
          <Field label={t("login.tenant")} hint={t("signup.tenantHint")}>
            <Input value={tenant} onChange={(e) => setTenant(e.target.value)} spellCheck={false} autoComplete="off" />
          </Field>
        )}
        <Async q={journeys} empty={<Notice tone="warn">{t("signup.noJourney")}</Notice>}>
          {(list) => list.length > 1 ? (
            <fieldset>
              <legend className="text-sm font-medium">{t("signup.product")}</legend>
              <div className="mt-2 flex flex-col gap-2">
                {list.map((j) => (
                  <label key={j.id} className="flex min-h-11 items-center gap-3 rounded-[var(--radius)] border border-border px-3">
                    <input type="radio" name="journey" value={j.id} checked={(journeyId || list[0].id) === j.id}
                      onChange={() => setJourneyId(j.id!)} className="h-5 w-5 accent-[var(--accent)]" />
                    {j.name}
                  </label>
                ))}
              </div>
            </fieldset>
          ) : <p className="text-sm text-muted">{list[0]?.name}</p>}
        </Async>
        {err.journey && <p className="text-xs font-medium text-bad">{err.journey}</p>}
        <Field label={t("profile.mobile")} hint={t("signup.phoneHint")} error={err.phone}>
          <Input type="tel" autoComplete="tel" inputMode="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        </Field>
        <Field label={t("profile.email")} optional error={err.email}>
          <Input type="email" autoComplete="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
        </Field>
        <ErrorBanner error={create.error} />
        <Button type="submit" disabled={create.isPending || !journeys.data?.length}>{create.isPending ? t("common.working") : t("signup.begin")}</Button>
        <p className="text-xs text-muted">{t("signup.otpNote")}</p>
      </form>
    </Card>
  );
}

const OPEN = ["STARTED", "IN_PROGRESS"];

function Application({ id, headers, onRestart }: { id: string; headers: Record<string, string>; onRestart: () => void }) {
  const t = useT();
  const app = useQuery({
    queryKey: ["application", id],
    queryFn: async () => unwrap(await onboarding.GET("/v1/onboarding/applications/{id}", { params: { path: { id } }, headers })),
    refetchInterval: (q) => (q.state.data && !OPEN.includes(q.state.data.status ?? "") && !["APPROVED", "REJECTED", "ABANDONED", "EXPIRED"].includes(q.state.data.status ?? "") ? 5000 : false),
  });
  const journey = useQuery({
    queryKey: ["journey", app.data?.onboarding_journey_id], enabled: !!app.data,
    queryFn: async () => unwrap(await onboarding.GET("/v1/onboarding/journeys/{id}", { params: { path: { id: app.data!.onboarding_journey_id } }, headers })),
  });

  return (
    <Async q={app}>
      {(a) => (
        <div className="flex flex-col gap-5">
          <Card>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <span>
                <span className="block text-sm text-muted">{t("signup.application")} {a.application_number}</span>
                <Badge value={a.status} />
              </span>
              <span className="w-40">
                <span className="block text-right text-xs text-muted">{t("signup.progress", { pct: a.progress_percent ?? 0 })}</span>
                <span role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={a.progress_percent ?? 0}
                  aria-label={t("signup.progress", { pct: a.progress_percent ?? 0 })} className="mt-1 block h-2 rounded-full bg-surface-2">
                  <span className="block h-2 rounded-full bg-accent" style={{ width: `${a.progress_percent ?? 0}%` }} />
                </span>
              </span>
            </div>
          </Card>
          {OPEN.includes(a.status ?? "")
            ? <NextStep app={a} journey={journey.data ?? null} headers={headers} onChanged={() => app.refetch()} />
            : <Status app={a} headers={headers} onRestart={onRestart} />}
        </div>
      )}
    </Async>
  );
}

function NextStep({ app, journey, headers, onChanged }:
  { app: OnboardingApplication; journey: OnboardingJourney | null; headers: Record<string, string>; onChanged: () => void }) {
  const t = useT();
  const id = app.id!;
  const next = useQuery({
    queryKey: ["next-step", id, app.progress_percent],
    queryFn: async (): Promise<OnboardingStep | null> => {
      const r = await onboarding.GET("/v1/onboarding/applications/{id}/next-step", { params: { path: { id } }, headers });
      if (r.response.status === 404) return null; // all steps complete
      return unwrap(r);
    },
  });
  const submitApp = useMutation({
    mutationFn: async () => unwrap(await onboarding.POST("/v1/onboarding/applications/{id}/submit", { params: { path: { id }, header: idem() }, headers })),
    onSuccess: onChanged,
  });

  if (next.isPending) return <Loading />;
  if (next.error) return <ErrorBanner error={next.error} />;
  if (!next.data) {
    return (
      <Card title={t("signup.review")}>
        <p className="text-sm">{t("signup.reviewBody")}</p>
        <ErrorBanner error={submitApp.error} className="mt-3" />
        <Button className="mt-4" onClick={() => submitApp.mutate()} disabled={submitApp.isPending}>
          {submitApp.isPending ? t("common.working") : t("signup.submit")}
        </Button>
      </Card>
    );
  }
  const def = stepsOf(journey).find((s) => s.code === next.data!.step_code);
  return <StepForm key={next.data.step_code} app={app} step={next.data} fields={def?.fields ?? []} headers={headers} onDone={onChanged} />;
}

function StepForm({ app, step, fields, headers, onDone }:
  { app: OnboardingApplication; step: OnboardingStep; fields: string[]; headers: Record<string, string>; onDone: () => void }) {
  const t = useT();
  const collected = (app.collected_data ?? {}) as Record<string, unknown>;
  const [values, setValues] = useState<Record<string, string | boolean>>(() =>
    Object.fromEntries(fields.map((f) => [f, (collected[f] as string | boolean | undefined) ?? (fieldKind(f) === "check" ? false : "")])));
  const [files, setFiles] = useState<Record<string, File | null>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: async () => {
      const input: Record<string, unknown> = {};
      for (const f of fields) {
        if (fieldKind(f) === "file") {
          const docType = f.includes("selfie") ? "SELFIE" : f.includes("address") ? "PROOF_OF_ADDRESS" : "ID_CARD";
          input[f] = await uploadDocument(files[f]!, "ONBOARDING_APPLICATION", app.id!, docType, headers);
        } else {
          input[f] = typeof values[f] === "string" ? (values[f] as string).trim() : values[f];
        }
      }
      return unwrap(await onboarding.POST("/v1/onboarding/applications/{id}/steps/{step_code}", {
        params: { path: { id: app.id!, step_code: step.step_code }, header: idem() }, headers, body: { input } }));
    },
    onSuccess: (s) => { if (s.status !== "FAILED") onDone(); },
  });

  function submit(e: FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    for (const f of fields) {
      const k = fieldKind(f);
      if (k === "file") { const m = checkFile(files[f] ?? null, t); if (m) er[f] = m; }
      else if (k === "check") { if (!values[f]) er[f] = t("form.required"); }
      else if (!String(values[f] ?? "").trim()) er[f] = t("form.required");
    }
    setErrors(er);
    if (!Object.keys(er).length) save.mutate();
  }

  const title = t(`stepType.${step.step_type}` as Parameters<typeof t>[0]) || humanize(step.step_code);
  return (
    <Card title={title}>
      {step.status === "FAILED" && step.error && <Notice tone="warn">{t("signup.stepFailed", { error: step.error })}</Notice>}
      {save.data?.status === "FAILED" && <Notice tone="warn">{t("signup.stepFailed", { error: save.data.error ?? "" })}</Notice>}
      {save.data?.status === "WAITING_REVIEW" && <Notice>{t("signup.waitingReview")}</Notice>}
      <form noValidate onSubmit={submit} className="mt-2 flex flex-col gap-4">
        {fields.length === 0 && <p className="text-sm text-muted">{t("signup.noFields")}</p>}
        {fields.map((f) => {
          const k = fieldKind(f);
          if (k === "file") {
            return <FileField key={f} label={humanize(f)} file={files[f] ?? null} error={errors[f]}
              onChange={(file) => setFiles({ ...files, [f]: file })} />;
          }
          if (k === "check") {
            return (
              <div key={f}>
                <label className="flex min-h-11 items-center gap-3 text-sm">
                  <input type="checkbox" checked={!!values[f]} aria-invalid={errors[f] ? true : undefined}
                    onChange={(e) => setValues({ ...values, [f]: e.target.checked })} className="h-5 w-5 accent-[var(--accent)]" />
                  {humanize(f)}
                </label>
                {errors[f] && <p className="text-xs font-medium text-bad">{errors[f]}</p>}
              </div>
            );
          }
          return (
            <Field key={f} label={humanize(f)} error={errors[f]}>
              <Input type={k} value={String(values[f] ?? "")} onChange={(e) => setValues({ ...values, [f]: e.target.value })}
                autoComplete={k === "email" ? "email" : k === "tel" ? "tel" : f.includes("first_name") ? "given-name"
                  : f.includes("last_name") ? "family-name" : f.includes("date_of_birth") ? "bday" : undefined} />
            </Field>
          );
        })}
        <ErrorBanner error={save.error} />
        <Button type="submit" disabled={save.isPending}>{save.isPending ? t("common.working") : t("signup.continue")}</Button>
      </form>
    </Card>
  );
}

function Status({ app, headers, onRestart }: { app: OnboardingApplication; headers: Record<string, string>; onRestart: () => void }) {
  const t = useT();
  const checks = useQuery({
    queryKey: ["onboarding-checks", app.id],
    queryFn: async () => {
      const r = await kyc.GET("/v1/kyc/checks", { params: { query: { onboarding_application_id: app.id!, limit: 20 } }, headers });
      if (r.response.status === 403) throw new ApiError({ status: 403, detail: t("signup.checksHidden") });
      return unwrap(r).data.filter((c) => c.onboarding_application_id === app.id);
    },
    retry: false,
  });
  const tone = app.status === "APPROVED" ? "good" : app.status === "REJECTED" ? "warn" : "info";
  return (
    <>
      <Card title={t("signup.statusTitle")}>
        <Notice tone={tone}>{t(`appStatus.${app.status ?? "SUBMITTED"}` as Parameters<typeof t>[0])}</Notice>
        <div className="mt-4">
          <Summary items={[
            { label: t("signup.submittedAt"), value: app.submitted_at ? new Date(app.submitted_at).toLocaleString() : "—" },
            ...(app.rejection_reason ? [{ label: t("signup.reason"), value: app.rejection_reason }] : []),
          ]} />
        </div>
        {app.status === "APPROVED" && <p className="mt-4 text-sm">{t("signup.approvedNext")} <Link className="prose-link" href="/login">{t("login.title")}</Link></p>}
        {["REJECTED", "ABANDONED", "EXPIRED"].includes(app.status ?? "") && (
          <Button variant="secondary" className="mt-4" onClick={onRestart}>{t("signup.restart")}</Button>
        )}
      </Card>
      <Card title={t("signup.checks")}>
        <Async q={checks} empty={<Empty title={t("signup.noChecks")} />}>
          {(rows) => (
            <ul className="divide-y divide-border">
              {rows.map((c) => (
                <li key={c.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                  <span>{c.check_type.replaceAll("_", " ").toLowerCase()}</span>
                  <Badge value={c.status} />
                </li>
              ))}
            </ul>
          )}
        </Async>
      </Card>
    </>
  );
}
