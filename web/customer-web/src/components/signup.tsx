"use client";

import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import { ApiError } from "@/api/client";
import { sendOtp, uploadApplicantDocument, submitStep, verifyOtp } from "@/api/applicant";
import type { OnboardingOtp, OnboardingStep, StepFieldDef } from "@/api/types";
import { useI18n, useT, type Translate } from "@/lib/i18n";
import {
  COUNTRIES, E164, EMAIL, ID_DOCUMENT_TYPES, applicantFields, documentTypeFor, initialValue, serverFieldErrors, toJson,
  validateField, type FieldIssue, type RawValue,
} from "@/lib/step-fields";
import type { MessageKey } from "@/messages/en";
import { FileField, checkFile } from "@/components/file-field";
import { Button, Card, ErrorBanner, Field, Input, Notice, Select, Textarea } from "@/components/ui";

/** Message key built at runtime (unknown keys translate to "" so callers can fall back). */
const k = (s: string) => s as MessageKey;

export function humanize(name: string) {
  const s = name.replace(/_document_id$|_id$/, "").replaceAll("_", " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function stepTitle(t: Translate, step: OnboardingStep) {
  return t(k(`stepType.${step.step_type}`)) || humanize(step.step_code);
}

function fieldLabel(t: Translate, f: StepFieldDef) {
  return t(k(f.label_key)) || t(k(`onboarding.field.${f.name}`)) || humanize(f.name);
}

function issueText(t: Translate, issue: FieldIssue) {
  return t(k(`fieldError.${issue.code}`), issue.vars) || t("form.required");
}

/** Epoch ms `ms` from now (event handlers only). */
const fromNow = (ms: number) => Date.now() + ms;

/** Seconds left until `until` (epoch ms), ticking once a second; 0 when past or unset. */
export function useCountdown(until: number | null): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!until || until <= Date.now()) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [until]);
  return until ? Math.max(0, Math.ceil((until - now) / 1000)) : 0;
}

/** The moment a 429 says the action may be retried (null for any other error). */
export function useRetryAt(error: unknown): number | null {
  const [at, setAt] = useState<number | null>(null);
  useEffect(() => {
    const s = error instanceof ApiError && error.problem.status === 429 ? error.retryAfterS : undefined;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setAt(s ? fromNow(s * 1000) : null);
  }, [error]);
  return at;
}

/**
 * Problem banner for the applicant flow: a 429 is explained with a live "try again in N seconds" countdown (the action
 * stays disabled meanwhile, see useRetryAt); anything else is the standard problem banner.
 */
export function ApplicantError({ error, retryIn, className }: { error: unknown; retryIn: number; className?: string }) {
  const t = useT();
  if (!error) return null;
  if (error instanceof ApiError && error.problem.status === 429) {
    const text = error.problem.code === "otp_send_limit" ? t("otp.sendLimit")
      : retryIn > 0 ? t("signup.rateLimited", { s: retryIn }) : t("signup.rateLimitedNoTime");
    return (
      <div role="alert" className={`rounded-[var(--radius)] border border-warn/40 bg-warn-soft px-4 py-3 text-sm text-warn ${className ?? ""}`}>
        <p className="font-semibold">{text}</p>
        {error.problem.code && <p className="mt-1 text-xs opacity-90">{t("error.code")} <span className="font-mono">{error.problem.code}</span></p>}
      </div>
    );
  }
  return <ErrorBanner error={error} className={className} />;
}

/** Moves focus to the first invalid control after a failed submit (keyboard and screen reader users land on it). */
function useFocusFirstInvalid(trigger: number) {
  const ref = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (!trigger) return;
    ref.current?.querySelector<HTMLElement>("[aria-invalid=true]")?.focus();
  }, [trigger]);
  return ref;
}

// ------------------------------------------------------------------ typed fields step

/**
 * A step rendered from its typed fields (next-step `fields`), validated on the client with the server's rules and
 * submitted to steps/{code}. DOCUMENT fields upload the file through the public documents action first and send the
 * document id. Server field errors (`input.<field>`) are shown next to their fields.
 */
export function FieldsStep({ step, onDone }: { step: OnboardingStep; onDone: () => void }) {
  const t = useT();
  const fields = useMemo(() => applicantFields(step.fields), [step.fields]);
  const previous = (step.input ?? {}) as Record<string, unknown>;
  const [values, setValues] = useState<Record<string, RawValue>>(() =>
    Object.fromEntries(fields.map((f) => [f.name, initialValue(f, previous[f.name])])));
  const [files, setFiles] = useState<Record<string, File | null>>({});
  const [docTypes, setDocTypes] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [attempt, setAttempt] = useState(0);
  const uploaded = useRef(new Map<File, string>());
  const formRef = useFocusFirstInvalid(attempt);
  const mustAccept = step.step_type === "TERMS_ACCEPTANCE" || step.step_type === "SIGNATURE";

  const save = useMutation({
    mutationFn: async () => {
      const input: Record<string, unknown> = {};
      for (const f of fields) {
        if (f.type === "DOCUMENT") {
          const file = files[f.name];
          if (!file) continue;
          let id = uploaded.current.get(file);
          if (!id) {
            id = await uploadApplicantDocument(step.step_code, file,
              documentTypeFor(f.name, step.step_type) ?? docTypes[f.name] ?? "ID_CARD");
            uploaded.current.set(file, id);
          }
          input[f.name] = id;
        } else {
          const v = toJson(f, values[f.name]);
          if (v !== undefined) input[f.name] = v;
        }
      }
      return submitStep(step.step_code, input);
    },
    onSuccess: (s) => { if (s.status !== "FAILED") onDone(); },
    onError: (e) => {
      if (!(e instanceof ApiError)) return;
      const server = serverFieldErrors(e.problem.errors);
      const next: Record<string, string> = {};
      for (const [name, err] of Object.entries(server)) {
        next[name] = t(k(`fieldError.${err.code}`), {}) || err.message;
        if (err.code === "document_not_found") {
          const file = files[name];
          if (file) uploaded.current.delete(file);
        }
      }
      setErrors(next);
      if (Object.keys(next).length) setAttempt((n) => n + 1);
    },
  });
  const retryAt = useRetryAt(save.error);
  const retryIn = useCountdown(retryAt);

  function submit(e: FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    for (const f of fields) {
      if (f.type === "DOCUMENT") {
        const file = files[f.name] ?? null;
        if (!file && !f.required) continue;
        const m = checkFile(file, t);
        if (m) er[f.name] = m;
        else if (!documentTypeFor(f.name, step.step_type) && !docTypes[f.name]) er[`${f.name}.type`] = t("fieldError.invalid_value");
        continue;
      }
      const issue = validateField(f, toJson(f, values[f.name]), mustAccept);
      if (issue) er[f.name] = issueText(t, issue);
    }
    setErrors(er);
    if (Object.keys(er).length) { setAttempt((n) => n + 1); return; }
    save.reset();
    save.mutate();
  }

  const hasErrors = Object.keys(errors).length > 0;
  return (
    <Card title={stepTitle(t, step)}>
      {step.status === "FAILED" && step.error && <Notice tone="warn">{t("signup.stepFailed", { error: step.error })}</Notice>}
      {save.data?.status === "FAILED" && <Notice tone="warn">{t("signup.stepFailed", { error: save.data.error ?? "" })}</Notice>}
      {save.data?.status === "WAITING_REVIEW" && <Notice>{t("signup.waitingReview")}</Notice>}
      <form ref={formRef} noValidate onSubmit={submit} className="mt-2 flex flex-col gap-4" aria-describedby={hasErrors ? `${step.step_code}-summary` : undefined}>
        {hasErrors && <p id={`${step.step_code}-summary`} role="alert" className="text-sm font-medium text-bad">{t("signup.fixErrors")}</p>}
        {fields.length === 0 && <p className="text-sm text-muted">{t("signup.noFields")}</p>}
        {fields.map((f) => (
          <FieldControl key={f.name} f={f} value={values[f.name]} error={errors[f.name]}
            onChange={(v) => setValues((s) => ({ ...s, [f.name]: v }))}
            file={files[f.name] ?? null} onFile={(file) => setFiles((s) => ({ ...s, [f.name]: file }))}
            docType={documentTypeFor(f.name, step.step_type) === null ? (docTypes[f.name] ?? "") : null}
            docTypeError={errors[`${f.name}.type`]}
            onDocType={(v) => setDocTypes((s) => ({ ...s, [f.name]: v }))} />
        ))}
        {save.isPending && fields.some((f) => f.type === "DOCUMENT") && <p role="status" className="text-sm text-muted">{t("signup.uploading")}</p>}
        <ApplicantError error={Object.keys(serverFieldErrors((save.error as ApiError | null)?.problem?.errors)).length ? null : save.error} retryIn={retryIn} />
        <Button type="submit" disabled={save.isPending || retryIn > 0}>{save.isPending ? t("common.working") : t("signup.continue")}</Button>
      </form>
    </Card>
  );
}

function FieldControl({ f, value, error, onChange, file, onFile, docType, docTypeError, onDocType }: {
  f: StepFieldDef; value: RawValue | undefined; error?: string; onChange: (v: RawValue) => void;
  file: File | null; onFile: (f: File | null) => void;
  /** null when the document type follows from the field; else the applicant's choice. */
  docType: string | null; docTypeError?: string; onDocType: (v: string) => void;
}) {
  const t = useT();
  const { intl } = useI18n();
  const label = fieldLabel(t, f);
  const text = typeof value === "string" ? value : "";
  const set = (v: string) => onChange(v);
  const auto: Record<string, string> = { first_name: "given-name", last_name: "family-name", middle_name: "additional-name",
    date_of_birth: "bday", email: "email", phone_number: "tel", address_line1: "address-line1", address_line2: "address-line2",
    city: "address-level2", postal_code: "postal-code", country: "country", nationality: "off", occupation: "organization-title" };

  switch (f.type) {
    case "BOOLEAN": {
      const id = `bool-${f.name}`;
      return (
        <div className="flex flex-col gap-1">
          <label className="flex min-h-11 items-center gap-3 text-sm">
            <input type="checkbox" checked={value === true} aria-invalid={error ? true : undefined}
              aria-describedby={error ? `${id}-err` : undefined} aria-required={f.required || undefined}
              onChange={(e) => onChange(e.target.checked)} className="h-5 w-5 accent-[var(--accent)]" />
            {label}
          </label>
          {error && <p id={`${id}-err`} className="text-xs font-medium text-bad">{error}</p>}
        </div>
      );
    }
    case "DOCUMENT":
      return (
        <div className="flex flex-col gap-3">
          {docType !== null && (
            <Field label={t("signup.documentType")} error={docTypeError}>
              <Select value={docType} onChange={(e) => onDocType(e.target.value)} options={[
                { value: "", label: t("form.choose") },
                ...ID_DOCUMENT_TYPES.map((d) => ({ value: d, label: t(k({ ID_CARD: "kyc.nationalId", PASSPORT: "kyc.passport",
                  DRIVING_LICENSE: "kyc.driving", RESIDENCE_PERMIT: "kyc.residence" }[d])) })),
              ]} />
            </Field>
          )}
          <FileField label={f.required ? label : `${label} (${t("common.optional")})`} file={file} error={error} onChange={onFile} />
        </div>
      );
    case "ENUM":
      return (
        <Field label={label} optional={!f.required} error={error}>
          <Select value={text} onChange={(e) => set(e.target.value)} aria-required={f.required || undefined} options={[
            { value: "", label: t("form.choose") },
            ...(f.enum ?? []).map((v) => ({ value: v, label: t(k(`${f.label_key}.${v}`)) || humanize(v) })),
          ]} />
        </Field>
      );
    case "COUNTRY": {
      let names: Intl.DisplayNames | null = null;
      try { names = new Intl.DisplayNames([intl], { type: "region" }); } catch { /* fall back to codes */ }
      const options = COUNTRIES.map((c) => ({ value: c, label: names?.of(c) ?? c }))
        .sort((a, b) => a.label.localeCompare(b.label, intl));
      return (
        <Field label={label} optional={!f.required} error={error}>
          <Select value={text} onChange={(e) => set(e.target.value)} aria-required={f.required || undefined}
            autoComplete={auto[f.name] ?? "off"} options={[{ value: "", label: t("form.choose") }, ...options]} />
        </Field>
      );
    }
    case "TEXT":
      return (
        <Field label={label} optional={!f.required} error={error}>
          <Textarea value={text} onChange={(e) => set(e.target.value)} aria-required={f.required || undefined}
            maxLength={f.max_length} />
        </Field>
      );
    default: {
      const type = f.type === "DATE" ? "date" : f.type === "EMAIL" ? "email" : f.type === "PHONE" ? "tel" : "text";
      const inputMode = f.type === "INTEGER" ? "numeric" : f.type === "DECIMAL" ? "decimal" : f.type === "PHONE" ? "tel"
        : f.type === "EMAIL" ? "email" : undefined;
      return (
        <Field label={label} optional={!f.required} error={error} hint={f.type === "PHONE" ? t("otp.phoneHint") : undefined}>
          <Input type={type} inputMode={inputMode} value={text} onChange={(e) => set(e.target.value)}
            aria-required={f.required || undefined} maxLength={f.max_length} spellCheck={false}
            autoComplete={auto[f.name] ?? (f.type === "EMAIL" ? "email" : f.type === "PHONE" ? "tel" : undefined)} />
        </Field>
      );
    }
  }
}

// ------------------------------------------------------------------ OTP step

/** Message for a failed verification (422 otp_*), with the attempts left when the server says so. */
function otpProblem(t: Translate, e: unknown): string | null {
  if (!(e instanceof ApiError)) return null;
  switch (e.problem.code) {
    case "otp_invalid": {
      const n = e.problem.detail?.match(/(\d+)\s+attempts?\s+left/)?.[1];
      return n ? t("otp.wrong", { n }) : t("otp.wrongNoCount");
    }
    case "otp_locked": return t("otp.locked");
    case "otp_expired": return t("otp.expired");
    case "otp_not_requested": return t("otp.notRequested");
    default: return null;
  }
}

/**
 * Phone / email verification: send a one-time code to the destination (otp/send), then verify it (otp/verify), which
 * completes the step. Resend is offered after the server's cooldown (resend_after); after max_attempts wrong codes
 * the code is locked and only a new code helps.
 */
export function OtpStep({ step, onDone }: { step: OnboardingStep; onDone: () => void }) {
  const t = useT();
  const phone = step.step_type === "PHONE_VERIFICATION";
  const [destination, setDestination] = useState("");
  const [destError, setDestError] = useState<string>();
  const [otp, setOtp] = useState<OnboardingOtp | null>(null);
  const [code, setCode] = useState("");
  const [codeError, setCodeError] = useState<string>();
  const [locked, setLocked] = useState(false);
  const [resendAt, setResendAt] = useState<number | null>(null);
  const resendIn = useCountdown(resendAt);
  const codeBox = useRef<HTMLDivElement>(null);

  const normalized = phone ? destination.replace(/[\s\-().]/g, "") : destination.trim();
  const send = useMutation({
    mutationFn: () => sendOtp(step.step_code, normalized),
    onSuccess: (o) => {
      setOtp(o);
      setCode("");
      setCodeError(undefined);
      setLocked(false);
      setResendAt(o.resend_after ? Date.parse(o.resend_after) : fromNow(30_000));
      verify.reset();
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        const dest = e.problem.errors?.find((x) => x.field === "destination");
        if (dest) setDestError(t(k(`fieldError.${dest.code}`)) || dest.message);
        if (e.problem.code === "otp_resend_too_soon" && e.retryAfterS) setResendAt(fromNow(e.retryAfterS * 1000));
      }
    },
  });
  const verify = useMutation({
    mutationFn: () => verifyOtp(step.step_code, code.trim()),
    onSuccess: () => onDone(),
    onError: (e) => {
      if (e instanceof ApiError && e.problem.code === "otp_locked") setLocked(true);
      if (e instanceof ApiError && e.problem.code === "otp_expired") setLocked(true);
    },
  });
  const sendRetryIn = useCountdown(useRetryAt(send.error));
  const verifyRetryIn = useCountdown(useRetryAt(verify.error));

  useEffect(() => { if (otp) codeBox.current?.querySelector("input")?.focus(); }, [otp]);

  function requestCode(e?: FormEvent) {
    e?.preventDefault();
    const ok = phone ? E164.test(normalized) : EMAIL.test(normalized);
    setDestError(ok ? undefined : t(phone ? "fieldError.invalid_phone" : "fieldError.invalid_email"));
    if (ok) send.mutate();
  }

  function submitCode(e: FormEvent) {
    e.preventDefault();
    const ok = /^\d{6}$/.test(code.trim());
    setCodeError(ok ? undefined : t("otp.codeInvalid"));
    if (ok) verify.mutate();
  }

  const verifyMsg = otpProblem(t, verify.error);
  return (
    <Card title={stepTitle(t, step)}>
      {!otp ? (
        <form noValidate onSubmit={requestCode} className="flex flex-col gap-4">
          <Field label={t(phone ? "otp.phoneLabel" : "otp.emailLabel")} hint={t(phone ? "otp.phoneHint" : "otp.emailHint")} error={destError}>
            <Input type={phone ? "tel" : "email"} inputMode={phone ? "tel" : "email"} autoComplete={phone ? "tel" : "email"}
              value={destination} onChange={(e) => setDestination(e.target.value)} aria-required spellCheck={false} />
          </Field>
          <ApplicantError error={send.error instanceof ApiError && send.error.problem.errors?.some((x) => x.field === "destination") ? null : send.error}
            retryIn={sendRetryIn} />
          <Button type="submit" disabled={send.isPending || sendRetryIn > 0}>{send.isPending ? t("common.working") : t("otp.send")}</Button>
        </form>
      ) : (
        <div ref={codeBox} className="flex flex-col gap-4">
          <Notice>{t("otp.sentTo", { dest: otp.destination_masked ?? normalized })}</Notice>
          <form noValidate onSubmit={submitCode} className="flex flex-col gap-4">
            <Field label={t("otp.code")} hint={t("otp.codeHint")} error={codeError}>
              <Input value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]*" maxLength={6} aria-required
                className="font-mono tracking-[0.4em]" disabled={locked} />
            </Field>
            {verifyMsg
              ? <div role="alert" className="rounded-[var(--radius)] border border-bad/40 bg-bad-soft px-4 py-3 text-sm font-medium text-bad">{verifyMsg}</div>
              : <ApplicantError error={verify.error} retryIn={verifyRetryIn} />}
            <Button type="submit" disabled={verify.isPending || locked || verifyRetryIn > 0}>
              {verify.isPending ? t("common.working") : t("otp.verify")}
            </Button>
          </form>
          <div className="flex flex-col gap-2 border-t border-border pt-4">
            <p aria-live="polite" className="text-xs text-muted">{resendIn > 0 ? t("otp.resendIn", { s: resendIn }) : ""}</p>
            <ApplicantError error={send.error} retryIn={sendRetryIn} />
            <div className="flex flex-wrap gap-2">
              <Button variant={locked ? "primary" : "secondary"} onClick={() => send.mutate()}
                disabled={send.isPending || resendIn > 0 || sendRetryIn > 0}>{t("otp.resend")}</Button>
              <Button variant="ghost" onClick={() => { setOtp(null); send.reset(); verify.reset(); setLocked(false); }}>{t("otp.change")}</Button>
            </div>
          </div>
        </div>
      )}
    </Card>
  );
}
