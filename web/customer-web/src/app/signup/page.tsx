"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/api/client";
import {
  CONFIG_JOURNEY, CONFIG_TENANT, getApplication, nextStep, resetApplicant, startApplication, submitApplication,
  useApplicant, useTokenRefresh, type ApplicantSession, type EndReason, type SubmittedApplication,
} from "@/api/applicant";
import type { OnboardingStep } from "@/api/types";
import { DEFAULT_TENANT } from "@/lib/auth";
import { useI18n, useT } from "@/lib/i18n";
import type { MessageKey } from "@/messages/en";
import { ApplicantError, FieldsStep, OtpStep, stepTitle, useCountdown, useRetryAt } from "@/components/signup";
import { Preferences } from "@/components/shell";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Notice, Summary } from "@/components/ui";

/**
 * Self sign-up on the public applicant surface of onboarding-service (no sign-in, no staff endpoints):
 * start (tenant + journey code) -> next-step renders the step from its typed fields -> phone / email steps verify a
 * one-time code -> documents are uploaded through the public documents action -> submit -> status.
 * The applicant token lives in memory and sessionStorage only (src/api/applicant.ts) and is rotated before expiry.
 */
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export default function Signup() {
  const t = useT();
  const state = useApplicant();
  const [mounted, setMounted] = useState(false);
  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => setMounted(true), []);
  useTokenRefresh(state.session);
  const qc = useQueryClient();

  function restart() {
    qc.removeQueries({ queryKey: ["applicant"] });
    resetApplicant();
  }

  return (
    <main id="main" className="mx-auto w-full max-w-2xl px-4 py-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
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
      {!mounted ? <Loading />
        : state.ended ? <Ended reason={state.ended} onRestart={restart} />
        : state.submitted ? <Submitted app={state.submitted} onRestart={restart} />
        : state.session ? <Application session={state.session} />
        : <Start />}
      <section aria-label={t("prefs.title")} className="mt-8"><Preferences /></section>
    </main>
  );
}

function Start() {
  const t = useT();
  const [tenant, setTenant] = useState(CONFIG_TENANT || DEFAULT_TENANT);
  const [journey, setJourney] = useState(CONFIG_JOURNEY);
  const [err, setErr] = useState<{ tenant?: string; journey?: string }>({});
  const start = useMutation({ mutationFn: () => startApplication(tenant.trim(), journey.trim()) });
  const retryIn = useCountdown(useRetryAt(start.error));
  const noJourney = start.error instanceof ApiError && start.error.problem.code === "journey_not_found";

  function submit(e: FormEvent) {
    e.preventDefault();
    const er: typeof err = {};
    if (!UUID.test(tenant.trim())) er.tenant = t("signup.tenantInvalid");
    if (!journey.trim()) er.journey = t("form.required");
    setErr(er);
    if (!er.tenant && !er.journey) start.mutate();
  }

  return (
    <Card title={t("signup.start")}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-4">
        <p className="text-sm">{t("signup.intro")}</p>
        <p className="text-xs text-muted">{t("signup.privacy")}</p>
        {!CONFIG_TENANT && (
          <fieldset className="flex flex-col gap-4 rounded-[var(--radius)] border border-dashed border-border-strong p-3">
            <legend className="px-1 text-xs font-semibold text-muted">{t("signup.devSettings")}</legend>
            <Field label={t("login.tenant")} hint={t("signup.tenantHint")} error={err.tenant}>
              <Input value={tenant} onChange={(e) => setTenant(e.target.value)} spellCheck={false} autoComplete="off" />
            </Field>
            <Field label={t("signup.journey")} hint={t("signup.journeyHint")} error={err.journey}>
              <Input value={journey} onChange={(e) => setJourney(e.target.value)} spellCheck={false} autoComplete="off" />
            </Field>
          </fieldset>
        )}
        {noJourney ? <Notice tone="warn">{t("signup.noJourney")}</Notice> : <ApplicantError error={start.error} retryIn={retryIn} />}
        <Button type="submit" disabled={start.isPending || retryIn > 0}>{start.isPending ? t("common.working") : t("signup.begin")}</Button>
      </form>
    </Card>
  );
}

function Application({ session }: { session: ApplicantSession }) {
  const t = useT();
  const qc = useQueryClient();
  const app = useQuery({
    queryKey: ["applicant", session.applicationId, "application"],
    queryFn: getApplication,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const next = useQuery({
    queryKey: ["applicant", session.applicationId, "next-step"],
    queryFn: nextStep,
    enabled: app.isSuccess,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const changed = () => qc.invalidateQueries({ queryKey: ["applicant", session.applicationId] });

  // Move focus to the new step's heading so keyboard and screen-reader users start at the top of it.
  const stepBox = useRef<HTMLDivElement>(null);
  const stepCode = next.data?.step_code ?? (next.data === null ? "review" : undefined);
  const firstStep = useRef(true);
  useEffect(() => {
    if (!stepCode) return;
    if (firstStep.current) { firstStep.current = false; return; }
    const h = stepBox.current?.querySelector<HTMLElement>("h2");
    if (h) { h.tabIndex = -1; h.focus(); } // focus target only, not in the tab order
  }, [stepCode]);

  if (app.isPending) return <Loading />;
  if (app.error) return <ErrorBanner error={app.error} />;
  const a = app.data;
  const pct = a.progress_percent ?? 0;
  return (
    <div className="flex flex-col gap-5">
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span>
            <span className="block text-sm text-muted">{t("signup.application")} <span className="font-mono">{a.application_number ?? session.applicationNumber}</span></span>
            <Badge value={a.status} />
          </span>
          <span className="w-40">
            <span className="block text-right text-xs text-muted">{t("signup.progress", { pct })}</span>
            <span role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}
              aria-label={t("signup.progress", { pct })} className="mt-1 block h-2 rounded-full bg-surface-2">
              <span className="block h-2 rounded-full bg-accent" style={{ width: `${pct}%` }} />
            </span>
          </span>
        </div>
      </Card>
      <div ref={stepBox} className="[&_h2]:outline-none">
        {next.isPending ? <Loading />
          : next.error ? <ErrorBanner error={next.error} />
          : next.data === null ? <Review onRetry={changed} />
          : <StepView key={`${next.data.step_code}-${next.data.attempts}`} step={next.data} onDone={changed} />}
      </div>
    </div>
  );
}

function StepView({ step, onDone }: { step: OnboardingStep; onDone: () => void }) {
  const t = useT();
  return (
    <>
      <p className="sr-only" aria-live="polite">{stepTitle(t, step)}</p>
      {step.step_type === "PHONE_VERIFICATION" || step.step_type === "EMAIL_VERIFICATION"
        ? <OtpStep step={step} onDone={onDone} />
        : <FieldsStep step={step} onDone={onDone} />}
    </>
  );
}

function Review({ onRetry }: { onRetry: () => void }) {
  const t = useT();
  const submit = useMutation({
    mutationFn: submitApplication,
    onError: (e) => { if (e instanceof ApiError && e.problem.code === "incomplete_steps") onRetry(); },
  });
  const retryIn = useCountdown(useRetryAt(submit.error));
  return (
    <Card title={t("signup.review")}>
      <p className="text-sm">{t("signup.reviewBody")}</p>
      <ApplicantError error={submit.error} retryIn={retryIn} className="mt-3" />
      <Button className="mt-4" onClick={() => submit.mutate()} disabled={submit.isPending || retryIn > 0}>
        {submit.isPending ? t("common.working") : t("signup.submit")}
      </Button>
    </Card>
  );
}

function Submitted({ app, onRestart }: { app: SubmittedApplication; onRestart: () => void }) {
  const t = useT();
  const { intl } = useI18n();
  const status = app.status ?? "SUBMITTED";
  const tone = status === "APPROVED" ? "good" : status === "REJECTED" ? "warn" : "info";
  return (
    <Card title={t("signup.statusTitle")}>
      <Notice tone={tone}>{t(`appStatus.${status}` as MessageKey) || t("appStatus.SUBMITTED")}</Notice>
      <div className="mt-4">
        <Summary items={[
          { label: t("signup.reference"), value: <span className="font-mono">{app.applicationNumber ?? "—"}</span> },
          { label: t("signup.submittedAt"), value: app.submittedAt ? new Date(app.submittedAt).toLocaleString(intl) : "—" },
        ]} />
      </div>
      <p className="mt-4 text-sm">{t("signup.keepReference")}</p>
      {status === "APPROVED" && <p className="mt-2 text-sm">{t("signup.approvedNext")} <Link className="prose-link" href="/login">{t("login.title")}</Link></p>}
      <Button variant="secondary" className="mt-4" onClick={onRestart}>{t("signup.restart")}</Button>
    </Card>
  );
}

function Ended({ reason, onRestart }: { reason: EndReason; onRestart: () => void }) {
  const t = useT();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.focus(); }, []);
  return (
    <Card title={t("signup.endedTitle")}>
      <div ref={ref} tabIndex={-1} role="alert" className="text-sm outline-none">{t(`signup.ended.${reason}` as MessageKey)}</div>
      <Button className="mt-4" onClick={onRestart}>{t("signup.restart")}</Button>
    </Card>
  );
}
