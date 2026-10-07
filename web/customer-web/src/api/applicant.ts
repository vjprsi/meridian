"use client";

import createClient, { type Middleware } from "openapi-fetch";
import { useEffect, useSyncExternalStore } from "react";
import type { paths } from "./gen/onboarding-public";
import { ApiError, sha256Hex, type Problem } from "./client";
import type { ApplicantUpload, OnboardingApplicantToken, OnboardingApplication, OnboardingOtp, OnboardingStep } from "./types";

/**
 * Sign-up client for the public applicant surface of onboarding-service (/v1/onboarding/public/applications/*).
 *
 * No OIDC session and no staff headers: the tenant travels as ?tenant_id and, after start, the applicant token as
 * X-Applicant-Token. The token is short-lived (30 min by default), bound to one application, rotated with
 * token/refresh before it expires and revoked by the server on submit. It is kept in memory and mirrored to
 * sessionStorage (this tab only, so a reload resumes the application) and never written to localStorage.
 */

/** Tenant of the bank to apply to; when unset (local development) the start screen shows a tenant picker. */
export const CONFIG_TENANT = process.env.NEXT_PUBLIC_TENANT_ID ?? "";
/** Code of the tenant's PUBLISHED onboarding journey for web sign-up. */
export const CONFIG_JOURNEY = process.env.NEXT_PUBLIC_SIGNUP_JOURNEY ?? "RETAIL_ONLINE";

export const TOKEN_HEADER = "X-Applicant-Token";
const STORE_KEY = "meridian.signup.applicant";
const DEVICE_KEY = "meridian.signup.device";
const DONE_KEY = "meridian.signup.submitted";
/** Rotate the token when less than this is left (and on a timer that fires this long before expiry). */
export const REFRESH_MARGIN_MS = 5 * 60_000;

export interface ApplicantSession {
  tenantId: string;
  applicationId: string;
  applicationNumber?: string;
  token: string;
  expiresAt: string;
}

/** What the applicant sees after submitting (the token is revoked, so the application cannot be read again). */
export interface SubmittedApplication {
  applicationNumber?: string;
  status?: string;
  submittedAt?: string | null;
}

/** Why the applicant session ended without a submit: the restart screen explains it. */
export type EndReason = "token_expired" | "application_expired" | "forbidden";

export interface ApplicantState {
  session: ApplicantSession | null;
  ended: EndReason | null;
  submitted: SubmittedApplication | null;
}

const EMPTY: ApplicantState = { session: null, ended: null, submitted: null };
let state: ApplicantState = EMPTY;
let loaded = false;
const listeners = new Set<() => void>();

function read<T>(key: string): T | null {
  try {
    const raw = window.sessionStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function write(key: string, value: unknown) {
  try {
    if (value === null || value === undefined) window.sessionStorage.removeItem(key);
    else window.sessionStorage.setItem(key, JSON.stringify(value));
  } catch { /* storage unavailable: the session lives in memory for this tab */ }
}

function load() {
  if (loaded || typeof window === "undefined") return;
  loaded = true;
  state = { session: read<ApplicantSession>(STORE_KEY), ended: null, submitted: read<SubmittedApplication>(DONE_KEY) };
}

function set(next: Partial<ApplicantState>) {
  load();
  state = { ...state, ...next };
  if ("session" in next) write(STORE_KEY, next.session);
  if ("submitted" in next) write(DONE_KEY, next.submitted);
  listeners.forEach((l) => l());
}

export function applicantState(): ApplicantState {
  load();
  return state;
}

/** React view of the applicant session (sessionStorage is only read in the browser). */
export function useApplicant(): ApplicantState {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    applicantState,
    () => EMPTY,
  );
}

/** Forgets the application (restart): token, ended state and the submitted summary. */
export function resetApplicant() {
  set({ session: null, ended: null, submitted: null });
}

/** A random per-tab device id for the server's per-device start rate limit (no tracking across sessions). */
function deviceFingerprint(): string {
  let id = read<string>(DEVICE_KEY);
  if (!id) {
    id = `web-${crypto.randomUUID()}`;
    write(DEVICE_KEY, id);
  }
  return id;
}

// ------------------------------------------------------------------ client

const tokenHeader: Middleware = {
  onRequest({ request }) {
    const s = applicantState().session;
    if (s && !new URL(request.url, window.location.origin).pathname.endsWith("/applications/start")) {
      request.headers.set(TOKEN_HEADER, s.token);
    }
    return request;
  },
};

/** Typed client of the public applicant surface only (src/api/gen/onboarding-public.d.ts). No bearer token. */
export const applicantApi = createClient<paths>({ baseUrl: "", headers: { Accept: "application/json" }, cache: "no-store",
  credentials: "omit" });
applicantApi.use(tokenHeader);

const TOKEN_CODES = new Set(["applicant_token_required", "applicant_token_invalid", "applicant_token_expired"]);

/** Seconds from a Retry-After header (delta or HTTP date) or, failing that, an ISO time in the problem detail. */
export function retryAfterSeconds(res: Response, p: Problem): number | undefined {
  const h = res.headers.get("Retry-After");
  const fromDate = (ms: number) => Math.max(1, Math.ceil((ms - Date.now()) / 1000));
  if (h) {
    const n = Number(h);
    if (Number.isFinite(n)) return Math.max(1, Math.ceil(n));
    const d = Date.parse(h);
    if (!Number.isNaN(d)) return fromDate(d);
  }
  const iso = p.detail?.match(/\d{4}-\d{2}-\d{2}T[\d:.]+(?:Z|[+-]\d{2}:?\d{2})/)?.[0];
  if (iso && !Number.isNaN(Date.parse(iso))) return fromDate(Date.parse(iso));
  return undefined;
}

type Result<T> = { data?: T; error?: unknown; response: Response };

/**
 * Turns a response into data or an ApiError. A 401/403 on an authenticated call ends the applicant session (token
 * expired, revoked or refused) and 409 application_expired ends the application: the page then offers a restart.
 */
function check<T>(r: Result<T>, authenticated: boolean): T {
  if (r.error === undefined && r.response.ok) return r.data as T;
  const e = r.error;
  const p: Problem = { status: r.response.status,
    ...(e && typeof e === "object" ? (e as Problem) : { detail: e ? String(e) : r.response.statusText }) };
  const status = r.response.status;
  if (authenticated && (status === 401 || status === 403 || TOKEN_CODES.has(p.code ?? ""))) {
    set({ ended: status === 403 ? "forbidden" : "token_expired" });
  } else if (authenticated && status === 409 && p.code === "application_expired") {
    set({ ended: "application_expired" });
  }
  throw new ApiError(p, status === 429 ? retryAfterSeconds(r.response, p) : undefined);
}

function session(): ApplicantSession {
  const s = applicantState().session;
  if (!s) throw new ApiError({ status: 401, code: "applicant_token_required", detail: "No application in progress" });
  return s;
}

function adopt(t: OnboardingApplicantToken, tenantId: string, previous?: ApplicantSession) {
  if (!t.applicant_token) throw new ApiError({ detail: "The applicant token is missing from the response" });
  set({ ended: null, session: {
    tenantId,
    applicationId: t.onboarding_application_id,
    applicationNumber: t.application_number ?? previous?.applicationNumber,
    token: t.applicant_token,
    expiresAt: t.expires_at,
  } });
}

/** Starts an application on the journey and keeps the applicant token (returned once). */
export async function startApplication(tenantId: string, journeyCode: string, attestationToken?: string): Promise<void> {
  const r = await applicantApi.POST("/v1/onboarding/public/applications/start", {
    params: { query: { tenant_id: tenantId } },
    body: { journey_code: journeyCode, channel: "WEB", device_fingerprint: deviceFingerprint(),
      ...(attestationToken ? { attestation_token: attestationToken } : {}) },
  });
  set({ submitted: null });
  adopt(check(r, false), tenantId);
}

let refreshing: Promise<void> | null = null;

/** Rotates the applicant token (the old one is revoked by the server). Concurrent callers share one rotation. */
export function refreshToken(): Promise<void> {
  if (!refreshing) {
    refreshing = (async () => {
      const s = session();
      const r = await applicantApi.POST("/v1/onboarding/public/applications/{id}/token/refresh", {
        params: { path: { id: s.applicationId }, query: { tenant_id: s.tenantId } } });
      adopt(check(r, true), s.tenantId, s);
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

/** Rotates first when the token is close to expiry, so a long form never fails on submit. */
async function fresh(): Promise<ApplicantSession> {
  const s = session();
  if (Date.parse(s.expiresAt) - Date.now() < REFRESH_MARGIN_MS) await refreshToken();
  return session();
}

/** Keeps the token alive while the page is open: rotates it REFRESH_MARGIN_MS before it expires. */
export function useTokenRefresh(s: ApplicantSession | null) {
  useEffect(() => {
    if (!s) return;
    const due = Date.parse(s.expiresAt) - REFRESH_MARGIN_MS - Date.now();
    const timer = window.setTimeout(() => { refreshToken().catch(() => { /* ended state is set by check() */ }); },
      Math.max(0, Math.min(due, 2_147_000_000)));
    return () => window.clearTimeout(timer);
  }, [s]);
}

const ref = (s: ApplicantSession) => ({ path: { id: s.applicationId }, query: { tenant_id: s.tenantId } });

/** The applicant's own application (no risk, decision or review fields). */
export async function getApplication(): Promise<OnboardingApplication> {
  const s = await fresh();
  return check(await applicantApi.GET("/v1/onboarding/public/applications/{id}", { params: ref(s) }), true);
}

/** The current step with its typed fields, or null when every step is complete (404 no_next_step). */
export async function nextStep(): Promise<OnboardingStep | null> {
  const s = await fresh();
  const r = await applicantApi.GET("/v1/onboarding/public/applications/{id}/next-step", { params: ref(s) });
  if (r.response.status === 404 && (r.error as Problem | undefined)?.code === "no_next_step") return null;
  return check(r, true);
}

export async function submitStep(stepCode: string, input: Record<string, unknown>): Promise<OnboardingStep> {
  const s = await fresh();
  return check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/steps/{step_code}", {
    params: { path: { id: s.applicationId, step_code: stepCode }, query: { tenant_id: s.tenantId } }, body: { input } }), true);
}

export async function sendOtp(stepCode: string, destination: string): Promise<OnboardingOtp> {
  const s = await fresh();
  return check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/otp/send", {
    params: ref(s), body: { step_code: stepCode, destination } }), true);
}

export async function verifyOtp(stepCode: string, code: string): Promise<OnboardingStep> {
  const s = await fresh();
  return check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/otp/verify", {
    params: ref(s), body: { step_code: stepCode, code } }), true);
}

/**
 * Uploads a file for a step: the public documents action registers it with document-service (owner = this
 * application) and returns a pre-signed upload; the bytes are PUT there, then documents/{id}/complete confirms the
 * size and SHA-256 (starts virus scanning). Returns the document id to put in the step's DOCUMENT field.
 */
export async function uploadApplicantDocument(stepCode: string, file: File, documentType: string): Promise<string> {
  const s = await fresh();
  const target = check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/documents", {
    params: ref(s), body: { step_code: stepCode, document_type: documentType, file_name: file.name,
      content_type: file.type || "application/octet-stream" } }), true) as unknown as ApplicantUpload;
  if (!target.document_id || !target.url) throw new ApiError({ detail: "Upload URL missing from the response" });
  const bytes = await file.arrayBuffer();
  const put = await fetch(target.url, { method: target.method ?? "PUT", headers: target.headers ?? {}, body: bytes,
    credentials: "omit" });
  if (!put.ok) throw new ApiError({ status: put.status, title: "Upload failed", detail: put.statusText });
  const s2 = await fresh();
  check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/documents/{document_id}/complete", {
    params: { path: { id: s2.applicationId, document_id: target.document_id }, query: { tenant_id: s2.tenantId } },
    body: { size_bytes: bytes.byteLength, checksum: await sha256Hex(bytes) } }), true);
  return target.document_id;
}

/** Submits the application; the server revokes the token, so it is dropped here and only a summary is kept. */
export async function submitApplication(): Promise<OnboardingApplication> {
  const s = await fresh();
  const app = check(await applicantApi.POST("/v1/onboarding/public/applications/{id}/submit", { params: ref(s) }), true);
  set({ session: null, ended: null, submitted: {
    applicationNumber: app.application_number ?? s.applicationNumber, status: app.status, submittedAt: app.submitted_at } });
  return app;
}
