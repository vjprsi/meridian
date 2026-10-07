"use client";

import createClient, { type Middleware } from "openapi-fetch";
import type { paths as IdentityPaths, components as IdentityComponents } from "./identity";
import type { paths as WebhookPaths, components as WebhookComponents } from "./webhook";
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
    // Every write is idempotent: a retry of the same click must not double-post.
    if (request.method === "POST" && !request.headers.has("Idempotency-Key")) {
      request.headers.set("Idempotency-Key", crypto.randomUUID());
    }
    return request;
  },
};

/**
 * Typed clients generated from the owning services' contracts (npm run gen:api):
 * identity-service (API clients, API keys, OAuth scopes) and webhook-service (endpoints, subscriptions,
 * deliveries, replays). Requests go to /v1/... on this origin; Next proxies them to the edge gateway.
 */
export const identity = createClient<IdentityPaths>({ baseUrl: "" });
identity.use(auth);
export const webhooks = createClient<WebhookPaths>({ baseUrl: "" });
webhooks.use(auth);

type IS = IdentityComponents["schemas"];
type WS = WebhookComponents["schemas"];
export type ApiClient = IS["ApiClient"];
export type ApiClientCreate = IS["ApiClientCreate"];
export type ApiKey = IS["ApiKey"];
export type OauthScope = IS["OauthScope"];
export type WebhookEndpoint = WS["WebhookEndpoint"];
export type WebhookEndpointCreate = WS["WebhookEndpointCreate"];
export type WebhookSubscription = WS["WebhookSubscription"];
export type WebhookDelivery = WS["WebhookDelivery"];
export type WebhookDeliveryAttempt = WS["WebhookDeliveryAttempt"];
export type WebhookReplayJob = WS["WebhookReplayJob"];

/** Unwraps an openapi-fetch result: returns data or throws ApiError with the problem body. */
export function unwrap<T>(r: { data?: T; error?: unknown; response: Response }): T {
  if (r.error !== undefined || !r.response.ok) {
    const p = (r.error ?? {}) as Problem;
    throw new ApiError({ status: r.response.status, ...(typeof p === "object" ? p : { detail: String(p) }) });
  }
  return r.data as T;
}

/** Outcome of a raw gateway call made by the try-it console. */
export interface RawResult {
  status: number;
  statusText: string;
  headers: [string, string][];
  body: string;
  ms: number;
  requestHeaders: Record<string, string>;
  url: string;
}

/**
 * Sends one request through the gateway proxy as the signed-in developer and reports status, headers, body and
 * wall-clock time. Never throws for HTTP errors: the console shows them as they are.
 */
export async function rawCall(method: string, url: string, body: string | undefined,
  extraHeaders: Record<string, string>): Promise<RawResult> {
  const requestHeaders: Record<string, string> = { Accept: "application/json", ...authHeaders(), ...extraHeaders };
  if (body !== undefined && body !== "") requestHeaders["Content-Type"] = "application/json";
  const t0 = performance.now();
  const res = await fetch(url, { method, headers: requestHeaders, body: body === undefined || body === "" ? undefined : body });
  const text = await res.text();
  const ms = performance.now() - t0;
  return {
    status: res.status, statusText: res.statusText, headers: [...res.headers.entries()], body: text, ms, url,
    requestHeaders: Object.fromEntries(Object.entries(requestHeaders).map(([k, v]) =>
      [k, k === "Authorization" ? `${v.slice(0, 16)}…` : v])),
  };
}
