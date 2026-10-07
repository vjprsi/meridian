"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths as MePaths } from "./gen/customer-channel";
import type { paths as CustomerPaths } from "./gen/customer";
import { authHeaders } from "@/lib/auth";

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
  /** Seconds to wait before retrying (429: Retry-After header or a server hint), when known. */
  constructor(public problem: Problem, public retryAfterS?: number) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

const auth: Middleware = {
  onRequest({ request }) {
    for (const [k, v] of Object.entries(authHeaders())) request.headers.set(k, v);
    // Every write is idempotent: a retry of the same click must not double-post. Money movement passes its own
    // key (kept for the whole confirmation) so a resubmitted confirmation reuses it.
    if (request.method !== "GET" && request.method !== "HEAD" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    return request;
  },
};

function client<P extends object>() {
  const c = createClient<P>({ baseUrl: "", headers: { Accept: "application/json" }, cache: "no-store" });
  c.use(auth);
  return c;
}

/**
 * Typed clients generated from the contracts (npm run gen:api). Requests go to /v1/... on this origin; Next proxies
 * them to the edge gateway.
 *
 * `me` is the customer channel (/v1/me/*, customer-channel-service): every signed-in screen uses only this client.
 * The server takes the customer from the token (dev: X-Customer-Id), checks that every referenced object is theirs,
 * builds the request bodies itself and requires a recent sign-in for money movement (401 step_up_required).
 * `customers` serves the dev-mode customer picker only. Sign-up has its own unauthenticated client (src/api/applicant.ts).
 */
export const me = client<MePaths>();
export const customers = client<CustomerPaths>();

/** Idempotency-Key header for a write; pass a key held for the whole confirmation to make resubmits safe. */
export function idem(key?: string): { "Idempotency-Key": string } {
  return { "Idempotency-Key": key ?? crypto.randomUUID() };
}

/** If-Match header carrying the resource's current row_version (optimistic locking). */
export function ifMatch(rowVersion: number | undefined): { "If-Match": string } {
  return { "If-Match": String(rowVersion ?? 0) };
}

/** Unwraps an openapi-fetch result: returns data or throws ApiError with the problem body. */
export function unwrap<T>(r: { data?: T; error?: unknown; response: Response }): T {
  if (r.error !== undefined || !r.response.ok) {
    const p = r.error;
    throw new ApiError({ status: r.response.status,
      ...(p && typeof p === "object" ? (p as Problem) : { detail: p ? String(p) : r.response.statusText }) });
  }
  return r.data as T;
}

/** Parses a JSON body; a non-JSON error body (e.g. a gateway HTML page) becomes a problem with that text as detail. */
function problemFrom(text: string, res: Response): Problem {
  try {
    return JSON.parse(text) as Problem;
  } catch {
    return { title: res.statusText, detail: text.slice(0, 300) || res.statusText };
  }
}

/**
 * Downloads a binary response (PDF receipt, statement file) with the session's auth headers and saves it locally.
 * The file goes straight to the user's device; nothing is kept in the app's caches.
 */
export async function download(path: string, filename: string): Promise<void> {
  const res = await fetch(path, { headers: authHeaders(), cache: "no-store" });
  if (!res.ok) throw new ApiError({ status: res.status, ...problemFrom(await res.text(), res) });
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

type MyDocumentType = NonNullable<MePaths["/v1/me/documents"]["post"]["requestBody"]>["content"]["application/json"]["document_type"];

/**
 * Uploads a file of the signed-in customer: register it (owner = the customer, set by the server), PUT the bytes to
 * the pre-signed URL, then confirm size and SHA-256 (starts virus scanning). Returns the document id.
 */
export async function uploadMyDocument(file: File, documentType: MyDocumentType): Promise<string> {
  const doc = unwrap(await me.POST("/v1/me/documents", {
    params: { header: idem() },
    body: { document_type: documentType, file_name: file.name, content_type: file.type || "application/octet-stream" },
  }));
  const target = unwrap(await me.POST("/v1/me/documents/{document_id}/upload-url", {
    params: { path: { document_id: doc.id! }, header: idem() } })) as { method?: string; url?: string; headers?: Record<string, string> };
  if (!target.url) throw new ApiError({ detail: "Upload URL missing from the response" });
  const bytes = await file.arrayBuffer();
  const put = await fetch(target.url, { method: target.method ?? "PUT", headers: target.headers ?? {}, body: bytes });
  if (!put.ok) throw new ApiError({ status: put.status, title: "Upload failed", detail: put.statusText });
  unwrap(await me.POST("/v1/me/documents/{document_id}/complete-upload", {
    params: { path: { document_id: doc.id! }, header: idem() }, body: { size_bytes: bytes.byteLength, checksum: await sha256Hex(bytes) },
  }));
  return doc.id!;
}
