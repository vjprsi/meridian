"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./gen/merchant-channel";
import { authHeaders } from "@/lib/auth";

/** RFC 9457 problem returned by every Meridian service: {code, detail, status, ...}. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
  request_id?: string;
  errors?: { field?: string; code?: string; message?: string }[];
  [extra: string]: unknown;
}

export class ApiError extends Error {
  /**
   * @param stepUpMaxAge for 401 step_up_required: the max_age (seconds) from
   *   `WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<s>`, when given.
   */
  constructor(public problem: Problem, public stepUpMaxAge?: number) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }

  get code(): string | undefined {
    return this.problem.code;
  }
}

/** True when the server wants a fresh authentication before it performs the request. */
export function isStepUp(e: unknown): e is ApiError {
  return e instanceof ApiError && e.problem.status === 401
    && (e.problem.code === "step_up_required" || e.stepUpMaxAge !== undefined);
}

function stepUpMaxAge(res: Response): number | undefined {
  const h = res.headers.get("WWW-Authenticate") ?? "";
  if (!/insufficient_user_authentication/.test(h)) return undefined;
  const m = /max_age="?(\d+)"?/.exec(h);
  return m ? Number(m[1]) : 0;
}

const auth: Middleware = {
  onRequest({ request }) {
    for (const [k, v] of Object.entries(authHeaders())) request.headers.set(k, v);
    // Every write is idempotent: a retry of the same confirmation must not double-post. Actions pass their own key
    // (kept across a step-up retry); this only covers a write sent without one.
    if (request.method !== "GET" && request.method !== "HEAD" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    return request;
  },
};

/**
 * Typed client of the merchant channel (/v1/merchant/*, merchant-channel-service), generated from the contract
 * (npm run gen:api). Requests go to /v1/... on this origin; Next proxies them to the edge gateway. The server takes
 * the merchant and the app user from the token (dev: X-Merchant-Id / X-Merchant-User-Id), checks roles and store
 * scope, and requires a recent sign-in for refund approvals, payout account changes and admin changes.
 */
export const api = createClient<paths>({ baseUrl: "", headers: { Accept: "application/json" }, cache: "no-store" });
api.use(auth);

/** Idempotency-Key header for a write; pass the key held for the whole confirmation so a retry reuses it. */
export function idem(key?: string): { "Idempotency-Key": string } {
  return { "Idempotency-Key": key ?? crypto.randomUUID() };
}

/** Unwraps an openapi-fetch result: returns data or throws ApiError with the problem body. */
export function unwrap<T>(r: { data?: T; error?: unknown; response: Response }): T {
  if (r.error !== undefined || !r.response.ok) {
    const p = r.error;
    throw new ApiError({ status: r.response.status,
      ...(p && typeof p === "object" ? (p as Problem) : { detail: p ? String(p) : r.response.statusText }) },
      r.response.status === 401 ? stepUpMaxAge(r.response) : undefined);
  }
  return r.data as T;
}

/** Parses a JSON body; a non-JSON error body (e.g. a gateway HTML page) becomes a problem with that text as detail. */
function problemFrom(text: string, res: Response): Problem {
  try {
    return { status: res.status, ...(JSON.parse(text) as Problem) };
  } catch {
    return { status: res.status, title: res.statusText, detail: text.slice(0, 300) || res.statusText };
  }
}

/** Fetches a file (statement, export, bulk report) with the session's auth headers; throws ApiError on failure. */
export async function fetchFile(path: string): Promise<Blob> {
  const res = await fetch(path, { headers: authHeaders(), cache: "no-store" });
  if (!res.ok) throw new ApiError(problemFrom(await res.text(), res), res.status === 401 ? stepUpMaxAge(res) : undefined);
  return res.blob();
}

/** Saves a blob as a file on the user's device. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Downloads a file response and saves it locally; nothing is kept in the app's caches. */
export async function download(path: string, filename: string): Promise<void> {
  saveBlob(await fetchFile(path), filename);
}
