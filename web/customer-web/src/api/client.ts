"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths as CustomerPaths } from "./gen/customer";
import type { paths as OnboardingPaths } from "./gen/onboarding";
import type { paths as KycPaths } from "./gen/kyc";
import type { paths as DocumentPaths } from "./gen/document";
import type { paths as DepositPaths } from "./gen/deposit";
import type { paths as StatementPaths } from "./gen/statement";
import type { paths as PaymentPaths } from "./gen/payment";
import type { paths as CardPaths } from "./gen/card";
import type { paths as LoanPaths } from "./gen/loan";
import type { paths as WalletPaths } from "./gen/wallet";
import type { paths as InsightsPaths } from "./gen/insights";
import type { paths as RewardsPaths } from "./gen/rewards";
import type { paths as InboxPaths } from "./gen/inbox";
import type { paths as NotificationPaths } from "./gen/notification";
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
  constructor(public problem: Problem) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

const auth: Middleware = {
  onRequest({ request }) {
    for (const [k, v] of Object.entries(authHeaders())) request.headers.set(k, v);
    // Every write is idempotent: a retry of the same click must not double-post. Money movement passes its own
    // key (kept for the whole confirmation) so a resubmitted confirmation reuses it.
    if (request.method === "POST" && !request.headers.has("Idempotency-Key")) {
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
 * Typed clients generated from the owning services' contracts (npm run gen:api). Requests go to /v1/... on this
 * origin; Next proxies them to the edge gateway.
 */
export const customers = client<CustomerPaths>();
export const onboarding = client<OnboardingPaths>();
export const kyc = client<KycPaths>();
export const documents = client<DocumentPaths>();
export const deposits = client<DepositPaths>();
export const statements = client<StatementPaths>();
export const payments = client<PaymentPaths>();
export const cards = client<CardPaths>();
export const loans = client<LoanPaths>();
export const wallets = client<WalletPaths>();
export const insights = client<InsightsPaths>();
export const rewards = client<RewardsPaths>();
export const inbox = client<InboxPaths>();
export const notifications = client<NotificationPaths>();

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

async function sha256Hex(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

type DocumentType = NonNullable<DocumentPaths["/v1/documents"]["post"]["requestBody"]>["content"]["application/json"]["document_type"];

/**
 * Stores a file in document-service: create the document record, PUT the bytes to the pre-signed URL, then confirm
 * with size and SHA-256 (which starts virus scanning). Returns the document id.
 */
export async function uploadDocument(file: File, ownerType: string, ownerId: string, documentType: DocumentType,
  headers?: Record<string, string>):
  Promise<string> {
  const doc = unwrap(await documents.POST("/v1/documents", {
    params: { header: idem() }, headers,
    body: { owner_type: ownerType, owner_id: ownerId, document_type: documentType, file_name: file.name,
      content_type: file.type || "application/octet-stream", classification: "CONFIDENTIAL", uploaded_via: "WEB" },
  }));
  const target = unwrap(await documents.POST("/v1/documents/{id}/upload-url", {
    params: { path: { id: doc.id! }, header: idem() }, headers })) as
    { method?: string; url?: string; headers?: Record<string, string> };
  if (!target.url) throw new ApiError({ detail: "Upload URL missing from document-service response" });
  const bytes = await file.arrayBuffer();
  const put = await fetch(target.url, { method: target.method ?? "PUT", headers: target.headers ?? {}, body: bytes });
  if (!put.ok) throw new ApiError({ status: put.status, title: "Upload failed", detail: put.statusText });
  unwrap(await documents.POST("/v1/documents/{id}/complete-upload", {
    params: { path: { id: doc.id! }, header: idem() }, headers, body: { size_bytes: bytes.byteLength, checksum: await sha256Hex(bytes) },
  }));
  return doc.id!;
}
