"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";
import { loadSession } from "@/lib/auth";

/** RFC 9457 problem returned by every Meridian service. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
  request_id?: string;
  errors?: { field: string; code: string; message: string }[];
}

export class ApiError extends Error {
  constructor(public problem: Problem) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

const auth: Middleware = {
  onRequest({ request }) {
    const s = loadSession();
    if (s?.mode === "oidc" && s.accessToken) {
      request.headers.set("Authorization", `Bearer ${s.accessToken}`);
    } else if (s) {
      request.headers.set("X-Tenant-Id", s.tenantId);
      request.headers.set("X-User", s.user);
    }
    // Every write is idempotent: a retry of the same click must not double-post.
    if (request.method === "POST" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    return request;
  },
};

/** Typed client for every Meridian endpoint (types generated from the bundled OpenAPI contract). */
export const api = createClient<paths>({ baseUrl: "" });
api.use(auth);

/** Unwraps an openapi-fetch result: returns data or throws ApiError with the problem body. */
export function unwrap<T>(r: { data?: T; error?: unknown; response: Response }): T {
  if (r.error !== undefined || !r.response.ok) {
    const p = (r.error ?? {}) as Problem;
    throw new ApiError({ status: r.response.status, ...p });
  }
  return r.data as T;
}

/** Loosely typed call for actions whose responses are free-form objects. */
export async function call<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
  const s = loadSession();
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
  }
  if (method !== "GET") headers["Idempotency-Key"] = crypto.randomUUID();
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const json = parseBody(text, res);
  if (!res.ok) throw new ApiError({ status: res.status, ...(json ?? {}) });
  return json as T;
}

/** Parses a JSON body; a non-JSON error body (e.g. a gateway HTML page) becomes a problem with that text as detail. */
function parseBody(text: string, res: Response): Record<string, unknown> | undefined {
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    if (res.ok) throw new ApiError({ status: res.status, detail: "Unexpected non-JSON response" });
    return { title: res.statusText, detail: text.slice(0, 300) };
  }
}

function authHeaders(): Record<string, string> {
  const s = loadSession();
  const headers: Record<string, string> = {};
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
  }
  return headers;
}

/** Downloads a binary response (PDF receipt, report file) with the session's auth headers and saves it locally. */
export async function download(path: string, filename: string): Promise<void> {
  const res = await fetch(path, { headers: authHeaders() });
  if (!res.ok) {
    const json = parseBody(await res.text(), res);
    throw new ApiError({ status: res.status, ...(json ?? {}) });
  }
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
