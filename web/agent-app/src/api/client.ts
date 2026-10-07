"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths, components } from "./agent-channel";
import { authHeaders, loadSession } from "@/lib/auth";
import { loadProfile } from "@/lib/agent-context";
import { reportReachability } from "@/lib/connectivity";
import { deviceProof } from "@/lib/device-key";
import type { Problem } from "@/lib/outbox/types";

export type { Problem };

export class ApiError extends Error {
  constructor(public problem: Problem) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

/** True when the failure means "could not reach the platform" (as opposed to a refusal). */
export function isUnreachable(e: unknown): boolean {
  if (e instanceof ApiError) return (e.problem.status ?? 0) >= 502 && (e.problem.status ?? 0) <= 504;
  return e instanceof TypeError;
}

/** fetch that keeps the connectivity indicator honest. */
async function trackedFetch(input: Request): Promise<Response> {
  try {
    const r = await fetch(input);
    reportReachability(r.status < 502 || r.status > 504);
    return r;
  } catch (e) {
    reportReachability(false);
    throw e;
  }
}

/** Money movement: the channel accepts a recent sign-in or a proof signed by this device's registered key. */
const MONEY = [/^\/v1\/agent\/cash-in$/, /^\/v1\/agent\/cash-out(\/[^/]+\/confirm)?$/, /^\/v1\/agent\/bill-payments$/,
  /^\/v1\/agent\/airtime-topups$/, /^\/v1\/agent\/float-requests$/];

const auth: Middleware = {
  async onRequest({ request }) {
    for (const [k, v] of Object.entries(authHeaders())) request.headers.set(k, v);
    // Every write is idempotent: a retry of the same click must not double-post. Money operations pass their own
    // key (the outbox record's), so a replay after a lost response is recognised by the server.
    if (request.method === "POST" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    const path = new URL(request.url, "http://local").pathname;
    const session = loadSession();
    const profile = loadProfile();
    if (request.method === "POST" && MONEY.some((re) => re.test(path)) && session && profile?.deviceId) {
      const proof = await deviceProof({ tenant: session.tenantId, agent: profile.agentId, device: profile.deviceId,
        method: "POST", path, idempotencyKey: request.headers.get("Idempotency-Key") ?? "" });
      if (proof) request.headers.set("X-Agent-Device-Proof", proof);
    }
    return request;
  },
};

/**
 * Typed client generated from the agent channel contract (npm run gen:api). Every request goes to /v1/agent/... on
 * this origin (Next proxies /v1 to the edge gateway); the app never calls a domain service directly.
 */
export const api = createClient<paths>({ baseUrl: "", fetch: trackedFetch });
api.use(auth);

type S = components["schemas"];
export type Wallet = S["Wallet"];
export type WalletTransaction = S["WalletTransaction"];
export type AccountBalance = S["AccountBalance"];
export type Agent = S["Agent"];
export type AgentOutlet = S["AgentOutlet"];
export type AgentFloat = S["AgentFloat"];
export type AgentFloatRequest = S["AgentFloatRequest"];
export type AgentTill = S["AgentTill"];
export type AgentDevice = S["AgentDevice"];
export type AgentDeviceKey = S["AgentDeviceKey"];
export type AgentPerformanceDaily = S["AgentPerformanceDaily"];
export type AgentCustomerLookup = S["AgentCustomerLookup"];
export type CashManagementRequest = S["CashManagementRequest"];
export type CommissionEarning = S["CommissionEarning"];
export type Biller = S["Biller"];
export type BillerProduct = S["BillerProduct"];
export type BillInquiry = S["BillInquiry"];
export type BillPayment = S["BillPayment"];
export type AirtimeTopup = S["AirtimeTopup"];
export type OnboardingJourney = S["OnboardingJourney"];
export type OnboardingApplication = S["OnboardingApplication"];
export type OnboardingStep = S["OnboardingStep"];
export type OnboardingOfflineBatch = S["OnboardingOfflineBatch"];
export type Document = S["Document"];
export type FloatRequestCreate = S["AgentMeFloatRequestsCreateRequest"];
export type CashInCreate = S["AgentMeCashInCreateRequest"];
export type BillPaymentCreate = S["AgentMeBillPaymentsCreateRequest"];
export type AirtimeCreate = S["AgentMeAirtimeCreateRequest"];
export type OfflineBatchCreate = S["AgentMeOfflineBatchesCreateRequest"];

/** Fee quote (computed result of agentMeTransactionsQuote, see the operation summary). */
export interface Quote {
  txn_type: string;
  amount: string;
  currency: string;
  fee_amount: string;
  tax_amount: string;
  total_debit_amount: string;
  fee_rule_id?: string | null;
  payee_wallet_id?: string | null;
  payee_name?: string | null;
  limits_ok: boolean;
  limit_violation?: string | null;
}

/** Unwraps an openapi-fetch result: returns data or throws ApiError with the problem body. */
export function unwrap<T>(r: { data?: T; error?: unknown; response: Response }): T {
  if (r.error !== undefined || !r.response.ok) {
    const p = (r.error ?? {}) as Problem;
    throw new ApiError({ status: r.response.status, ...(typeof p === "object" ? p : { detail: String(p) }) });
  }
  return r.data as T;
}

/** Problem from any thrown error, for display. */
export function problemOf(e: unknown): Problem {
  if (e instanceof ApiError) return e.problem;
  if (e instanceof TypeError) return { title: "Network error", detail: "The platform could not be reached." };
  return { detail: e instanceof Error ? e.message : String(e) };
}

/** A new idempotency key for an online money operation (kept for the lifetime of one confirmation screen). */
export function newKey(prefix = "agentapp"): string {
  return `${prefix}-${crypto.randomUUID()}`;
}

/** Idempotency-Key header (required by every POST in the contracts). */
export function idem(key: string = newKey()): { "Idempotency-Key": string } {
  return { "Idempotency-Key": key };
}
