"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths as WalletPaths, components as WalletComponents } from "./wallet";
import type { paths as AgentPaths, components as AgentComponents } from "./agent-network";
import type { paths as CommissionPaths, components as CommissionComponents } from "./commission";
import type { paths as BillerPaths, components as BillerComponents } from "./biller";
import type { paths as OnboardingPaths, components as OnboardingComponents } from "./onboarding";
import type { paths as DocumentPaths, components as DocumentComponents } from "./document";
import { authHeaders } from "@/lib/auth";
import { reportReachability } from "@/lib/connectivity";
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

const auth: Middleware = {
  onRequest({ request }) {
    for (const [k, v] of Object.entries(authHeaders())) request.headers.set(k, v);
    // Every write is idempotent: a retry of the same click must not double-post. Money operations pass their own
    // key (the outbox record's), so a replay after a lost response is recognised by the server.
    if (request.method === "POST" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    return request;
  },
};

function client<P extends object>() {
  const c = createClient<P>({ baseUrl: "", fetch: trackedFetch });
  c.use(auth);
  return c;
}

/**
 * Typed clients generated from the owning services' contracts (npm run gen:api). Requests go to /v1/... on this
 * origin; Next proxies them to the edge gateway.
 */
export const wallet = client<WalletPaths>();
export const agents = client<AgentPaths>();
export const commission = client<CommissionPaths>();
export const biller = client<BillerPaths>();
export const onboarding = client<OnboardingPaths>();
export const documents = client<DocumentPaths>();

type WS = WalletComponents["schemas"];
type AS = AgentComponents["schemas"];
type CS = CommissionComponents["schemas"];
type BS = BillerComponents["schemas"];
type OS = OnboardingComponents["schemas"];
type DS = DocumentComponents["schemas"];
export type Wallet = WS["Wallet"];
export type WalletTransaction = WS["WalletTransaction"];
export type AccountBalance = WS["AccountBalance"];
export type Agent = AS["Agent"];
export type AgentOutlet = AS["AgentOutlet"];
export type AgentFloat = AS["AgentFloat"];
export type AgentFloatRequest = AS["AgentFloatRequest"];
export type AgentFloatRequestCreate = AS["AgentFloatRequestCreate"];
export type AgentTill = AS["AgentTill"];
export type AgentDevice = AS["AgentDevice"];
export type AgentPerformanceDaily = AS["AgentPerformanceDaily"];
export type CashManagementRequest = AS["CashManagementRequest"];
export type CommissionEarning = CS["CommissionEarning"];
export type Biller = BS["Biller"];
export type BillerProduct = BS["BillerProduct"];
export type BillInquiry = BS["BillInquiry"];
export type BillPayment = BS["BillPayment"];
export type BillPaymentCreate = BS["BillPaymentCreate"];
export type AirtimeTopup = BS["AirtimeTopup"];
export type AirtimeTopupCreate = BS["AirtimeTopupCreate"];
export type OnboardingJourney = OS["OnboardingJourney"];
export type OnboardingApplication = OS["OnboardingApplication"];
export type OnboardingStep = OS["OnboardingStep"];
export type OnboardingOfflineBatch = OS["OnboardingOfflineBatch"];
export type Document = DS["Document"];

/** Fee quote (computed result of walletTransactionsQuote, see the operation summary). */
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
