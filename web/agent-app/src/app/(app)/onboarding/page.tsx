"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import {
  ApiError, idem, onboarding, problemOf, unwrap, type OnboardingApplication, type OnboardingJourney, type OnboardingStep,
} from "@/api/client";
import { OpOutcome } from "@/components/receipt";
import { Badge, Button, Card, Field, Input, Notice, PageTitle, ProblemBox, Row, Select, Spinner } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { isMsisdn, toMsisdn } from "@/lib/config";
import { useConnectivity } from "@/lib/connectivity";
import { deviceSerial } from "@/lib/device";
import { docTypeOf, uploadDocument } from "@/lib/documents";
import { useT } from "@/lib/i18n";
import { compressPhoto } from "@/lib/image";
import { toB64 } from "@/lib/outbox/crypto";
import { buildOfflineBatch } from "@/lib/outbox/executor";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { submit } from "@/lib/outbox/runtime";
import type { OutboxView, Problem } from "@/lib/outbox/types";

const ID_TYPES = ["NATIONAL_ID", "PASSPORT", "DRIVING_LICENCE", "RESIDENCE_PERMIT"];
type Details = Record<string, string>;
interface StepDef { code: string; type: string; fields?: string[] }

/**
 * Assisted onboarding. Online: create the application on a published journey that allows the AGENT channel
 * (onboardingApplicationsCreate), store the ID photo (document-service), then walk the server-driven steps
 * (onboardingApplicationsNextStep / Steps) filling each step's fields from what was captured, and submit
 * (onboardingApplicationsSubmit). Offline: the capture is queued and synced as an offline batch
 * (onboardingOfflineBatchesCreate); the photo is uploaded once the application exists.
 */
export default function OnboardingPage() {
  const t = useT();
  const p = useProfile();
  const offline = useConnectivity() === "offline";
  const journeys = useQuery({
    queryKey: ["journeys"],
    staleTime: 10 * 60_000,
    queryFn: async () => unwrap(await onboarding.GET("/v1/onboarding/journeys", { params: { query: { status: "PUBLISHED", limit: 100 } } })).data,
  });
  const [journeyId, setJourneyId] = useState("");
  const [d, setD] = useState<Details>({ id_type: "NATIONAL_ID", gender: "FEMALE" });
  const [photo, setPhoto] = useState<Blob | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [app, setApp] = useState<{ app: OnboardingApplication; documentId?: string } | null>(null);
  const [queued, setQueued] = useState<OutboxView | null>(null);

  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);
  if (!p) return null;

  const list = (journeys.data ?? []).filter((j) => (j.channels as unknown as string[]).includes("AGENT"));
  const journey = list.find((j) => j.id === journeyId);
  const set = (k: string) => (e: { target: { value: string } }) => setD((x) => ({ ...x, [k]: e.target.value }));

  async function onPhoto(f: File | undefined) {
    if (!f) return;
    try {
      const b = await compressPhoto(f);
      setPhoto(b);
      if (preview) URL.revokeObjectURL(preview);
      setPreview(URL.createObjectURL(b));
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  function reset() {
    setD({ id_type: "NATIONAL_ID", gender: "FEMALE" }); setPhoto(null); setPreview(null); setApp(null); setQueued(null); setProblem(null);
  }

  async function start(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    const phone = toMsisdn(d.phone ?? "");
    if (!isMsisdn(phone)) { setProblem({ detail: `${t("customerPhone")}: ${phone}` }); return; }
    if (!journey) return;
    const collected: Details = { first_name: d.first_name, last_name: d.last_name, date_of_birth: d.date_of_birth,
      gender: d.gender, id_type: d.id_type, id_number: d.id_number, phone, ...(d.email ? { email: d.email } : {}) };
    setBusy(true);
    try {
      if (offline) {
        const clientRef = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
        const capturedAt = new Date().toISOString();
        const request = await buildOfflineBatch(clientRef, p!.agentId, p!.deviceId ?? deviceSerial(), capturedAt, {
          onboarding_journey_id: journey.id, phone, ...(d.email ? { email: d.email } : {}), collected_data: collected,
        });
        const attachment = photo ? { contentType: "image/jpeg", fileName: `id-${clientRef}.jpg`, documentType: docTypeOf(d.id_type),
          base64: toB64(new Uint8Array(await photo.arrayBuffer())) } : undefined;
        setQueued(await submit("ONBOARDING", {
          request, attachment,
          display: { client_ref: clientRef, Customer: `${d.first_name} ${d.last_name}`, Phone: phone, Journey: journey.name },
        }, { offline: true }));
        return;
      }
      const number = `AGT-${new Date().toISOString().slice(0, 10).replace(/-/g, "")}-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
      const created = unwrap(await onboarding.POST("/v1/onboarding/applications", {
        params: { header: idem(`onb-${number}`) },
        body: { application_number: number, onboarding_journey_id: journey.id, channel: "AGENT", agent_id: p!.agentId,
          phone, email: d.email || undefined, collected_data: collected, device_fingerprint: p!.deviceId ?? deviceSerial() },
      }));
      let documentId: string | undefined;
      if (photo) {
        const doc = await uploadDocument({ ownerType: "ONBOARDING_APPLICATION", ownerId: created.id,
          bytes: new Uint8Array(await photo.arrayBuffer()), contentType: "image/jpeg", fileName: `id-${number}.jpg`,
          documentType: docTypeOf(d.id_type), keyPrefix: `onb-${number}`, documentNumber: d.id_number });
        documentId = doc.id;
      }
      setApp({ app: created, documentId });
    } catch (x) {
      setProblem(x instanceof OfflineLimitError ? { title: t("limitsExceeded"), code: x.code, detail: x.message } : problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  if (queued) {
    return (
      <div className="flex flex-col gap-4">
        <PageTitle>{t("onboarding")}</PageTitle>
        <Notice tone="warn">{t("applicationQueued")}</Notice>
        <OpOutcome view={queued} onNew={reset} />
      </div>
    );
  }
  if (app && journey) {
    return (
      <div className="flex flex-col gap-4">
        <PageTitle>{t("onboarding")}</PageTitle>
        <StepRunner journey={journey} application={app.app} details={{ ...d, phone: toMsisdn(d.phone ?? "") }}
          documentId={app.documentId} onNew={reset} />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("onboarding")}</PageTitle>
      {offline && <Notice tone="warn">{t("savedOffline")}</Notice>}
      <form onSubmit={start} className="flex flex-col gap-4">
        {journeys.isLoading && <Spinner />}
        {journeys.error && <ProblemBox problem={problemOf(journeys.error)} />}
        <Field label={t("journey")}>
          <Select value={journeyId} onChange={(e) => setJourneyId(e.target.value)} required
            options={[{ value: "", label: "—" }, ...list.map((j) => ({ value: j.id, label: `${j.name} (v${j.version}, ${j.target_tier})` }))]} />
        </Field>
        <Field label={t("customerPhone")}><Input type="tel" inputMode="tel" value={d.phone ?? ""} onChange={set("phone")} required /></Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("firstName")}><Input value={d.first_name ?? ""} onChange={set("first_name")} required autoComplete="off" /></Field>
          <Field label={t("lastName")}><Input value={d.last_name ?? ""} onChange={set("last_name")} required autoComplete="off" /></Field>
        </div>
        <Field label={t("dateOfBirth")}><Input type="date" value={d.date_of_birth ?? ""} onChange={set("date_of_birth")} required /></Field>
        <Field label={t("gender")}>
          <Select value={d.gender} onChange={set("gender")} options={["FEMALE", "MALE", "OTHER", "UNDISCLOSED"]} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("idType")}><Select value={d.id_type} onChange={set("id_type")} options={ID_TYPES} /></Field>
          <Field label={t("idNumber")}><Input value={d.id_number ?? ""} onChange={set("id_number")} required autoComplete="off" /></Field>
        </div>
        <Field label="Email"><Input type="email" value={d.email ?? ""} onChange={set("email")} autoComplete="off" /></Field>
        <Field label={t("idPhoto")}>
          {/* capture="environment" opens the rear camera on phones; desktops fall back to a file picker. */}
          <input type="file" accept="image/*" capture="environment" onChange={(e) => void onPhoto(e.target.files?.[0])}
            className="min-h-12 rounded-[var(--radius)] border-2 border-dashed border-border bg-surface p-3" />
        </Field>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        {preview && <img src={preview} alt={t("idPhoto")} className="max-h-56 rounded-[var(--radius)] border border-border object-contain" />}
        <ProblemBox problem={problem} />
        <Button type="submit" block disabled={busy || !journey}>{t("startApplication")}</Button>
      </form>
    </div>
  );
}

/** Walks the server-driven steps, pre-filling each step's fields; asks only for what is still missing. */
function StepRunner({ journey, application, details, documentId, onNew }:
  { journey: OnboardingJourney; application: OnboardingApplication; details: Details; documentId?: string; onNew: () => void }) {
  const t = useT();
  const defs = ((journey.definition as { steps?: StepDef[] }).steps ?? []);
  const [step, setStep] = useState<OnboardingStep | null>(null);
  const [extra, setExtra] = useState<Details>({});
  const [final, setFinal] = useState<OnboardingApplication | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const values: Details = { ...details, ...(documentId ? { document_id: documentId, document_type: docTypeOf(details.id_type) } : {}), ...extra };
  const def = step ? defs.find((s) => s.code === step.step_code) : undefined;
  const missing = (def?.fields ?? []).filter((f) => !values[f]);

  async function next() {
    try {
      setStep(unwrap(await onboarding.GET("/v1/onboarding/applications/{id}/next-step", { params: { path: { id: application.id } } })));
    } catch (x) {
      if (x instanceof ApiError && x.problem.status === 404) { setStep(null); setDone(true); } else setProblem(problemOf(x));
    }
  }

  useEffect(() => {
    const id = setTimeout(() => void next(), 0);
    return () => clearTimeout(id);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function submitStep(e?: FormEvent) {
    e?.preventDefault();
    if (!step) return;
    setBusy(true);
    setProblem(null);
    try {
      const input: Record<string, unknown> = {};
      for (const f of def?.fields ?? []) input[f] = values[f];
      if (step.step_type === "DOCUMENT_CAPTURE" && documentId) Object.assign(input, { document_id: documentId, document_type: values.document_type });
      if (step.step_type === "TERMS_ACCEPTANCE") input.accepted = true;
      const r = unwrap(await onboarding.POST("/v1/onboarding/applications/{id}/steps/{step_code}", {
        params: { path: { id: application.id, step_code: step.step_code }, header: idem() }, body: { input } }));
      if (r.status === "FAILED") setProblem({ title: `${step.step_code}: FAILED`, detail: r.error ?? undefined });
      else await next();
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  // Steps whose fields are all known are sent without stopping.
  useEffect(() => {
    if (!step || busy || problem || missing.length > 0 || step.status === "WAITING_REVIEW") return;
    const id = setTimeout(() => void submitStep(), 0);
    return () => clearTimeout(id);
  }, [step?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function submitApp() {
    setBusy(true);
    setProblem(null);
    try {
      setFinal(unwrap(await onboarding.POST("/v1/onboarding/applications/{id}/submit", {
        params: { path: { id: application.id }, header: idem(`onb-submit-${application.id}`) } })));
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={application.application_number} actions={<Badge value={final?.status ?? application.status} />}>
      {final ? (
        <div className="flex flex-col gap-3">
          <Notice tone={final.status === "REJECTED" ? "bad" : "good"}>{t("applicationSubmitted")}</Notice>
          <dl>
            <Row label={t("status")}><Badge value={final.status} /></Row>
            {final.decision && <Row label="Decision">{final.decision}</Row>}
            {final.risk_level && <Row label="Risk">{final.risk_level}</Row>}
          </dl>
          <Button onClick={onNew}>{t("newOperation")}</Button>
        </div>
      ) : done ? (
        <div className="flex flex-col gap-3">
          <ProblemBox problem={problem} />
          <Button onClick={submitApp} disabled={busy}>{t("submit")}</Button>
        </div>
      ) : step ? (
        <form onSubmit={submitStep} className="flex flex-col gap-3">
          <p className="font-semibold">{t("stepOf")} {step.sequence}: {step.step_type.replaceAll("_", " ")} <span className="text-muted">({step.step_code})</span></p>
          {step.status === "WAITING_REVIEW" && <Notice tone="warn">{t("needsReview")}</Notice>}
          {missing.map((f) => (
            <Field key={f} label={f.replaceAll("_", " ")}>
              <Input value={extra[f] ?? ""} onChange={(e) => setExtra((x) => ({ ...x, [f]: e.target.value }))} required />
            </Field>
          ))}
          <ProblemBox problem={problem} />
          <Button type="submit" disabled={busy}>{t("continue")}</Button>
        </form>
      ) : <><Spinner /><ProblemBox problem={problem} /></>}
    </Card>
  );
}
