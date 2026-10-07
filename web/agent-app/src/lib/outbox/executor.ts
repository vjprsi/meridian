"use client";

import { ApiError, api } from "@/api/client";
import { uploadDocument, type DocType } from "@/lib/documents";
import { batchPayload, sign } from "@/lib/device-key";
import { fromB64, toB64 } from "./crypto";
import type { AttemptOutcome, ExecContext, Executor, OpPayload, OutboxRecord, Problem } from "./types";

type Res = { data?: unknown; error?: unknown; response: Response };

/** Runs one openapi-fetch call and turns it into an attempt outcome (network failure, problem, or success). */
async function attempt(call: () => Promise<Res>): Promise<AttemptOutcome> {
  let r: Res;
  try {
    r = await call();
  } catch (e) {
    return { kind: "network", message: e instanceof Error ? e.message : String(e) };
  }
  if (r.response.ok) {
    return { kind: "ok", status: r.response.status, body: r.data,
      replayed: r.response.headers.get("Idempotent-Replayed") === "true" };
  }
  const p = (r.error && typeof r.error === "object" ? r.error : { detail: String(r.error ?? "") }) as Problem;
  return { kind: "http", status: r.response.status, problem: { status: r.response.status, ...p } };
}

const H = (key: string) => ({ "Idempotency-Key": key });

function withPin(body: Record<string, unknown>, ctx: ExecContext) {
  return ctx.agentPin ? { ...body, pin: ctx.agentPin } : body;
}

/** Uploads the ID photo captured with an offline onboarding and links it to the created application. */
async function uploadAttachment(rec: OutboxRecord, p: OpPayload, applicationId: string): Promise<AttemptOutcome> {
  const a = p.attachment!;
  try {
    const doc = await uploadDocument({
      applicationId, bytes: fromB64(a.base64), contentType: a.contentType,
      fileName: a.fileName, documentType: a.documentType as DocType, keyPrefix: rec.idempotencyKey,
    });
    return { kind: "ok", status: 200, body: doc, replayed: false };
  } catch (e) {
    if (e instanceof ApiError) return { kind: "http", status: e.problem.status ?? 422, problem: e.problem };
    return { kind: "network", message: e instanceof Error ? e.message : String(e) };
  }
}

/**
 * Executes outbox records against the agent channel (/v1/agent only). Money operations carry an
 * X-Agent-Device-Proof (added by the API client) so a replay later than the sign-in's step-up window still passes.
 */
export const apiExecutor: Executor = {
  async execute(rec, p, ctx) {
    const key = H(rec.idempotencyKey);
    const body = p.request;
    switch (rec.kind) {
      case "CASH_IN":
        return attempt(() => api.POST("/v1/agent/cash-in", { params: { header: key }, body: withPin(body, ctx) as never }));
      case "BILL_PAYMENT":
        return attempt(() => api.POST("/v1/agent/bill-payments", { params: { header: key }, body: withPin(body, ctx) as never }));
      case "AIRTIME":
        return attempt(() => api.POST("/v1/agent/airtime-topups", { params: { header: key }, body: withPin(body, ctx) as never }));
      case "FLOAT_REQUEST":
        return attempt(() => api.POST("/v1/agent/float-requests", { params: { header: key }, body: body as never }));
      case "ONBOARDING": {
        const batch = await attempt(() => api.POST("/v1/agent/offline-batches", { params: { header: key }, body: body as never }));
        if (batch.kind !== "ok") return batch;
        const b = batch.body as { status: string; result?: { records?: { client_ref: string; outcome: string; reason?: string; application_id?: string }[]; error?: string } };
        const mine = b.result?.records?.find((r) => r.client_ref === p.display.client_ref);
        if (!mine || (mine.outcome !== "APPLIED" && mine.outcome !== "ALREADY_APPLIED")) {
          return { kind: "http", status: 422, problem: { status: 422, code: mine?.reason ?? b.result?.error ?? b.status.toLowerCase(),
            title: `Offline batch ${b.status}`, detail: mine?.reason ?? b.result?.error ?? "The application was not created" } };
        }
        if (p.attachment && mine.application_id) {
          const up = await uploadAttachment(rec, p, mine.application_id);
          if (up.kind === "network") return up;
          if (up.kind === "http") {
            return { kind: "ok", status: batch.status, replayed: batch.replayed,
              body: { ...b, application_id: mine.application_id, document_problem: up.problem } };
          }
        }
        return { ...batch, body: { ...b, application_id: mine.application_id } };
      }
    }
  },

  /** Finds the object the agent channel created with this record's Idempotency-Key (unknown outcome). */
  async lookup(rec) {
    const query = { query: { idempotency_key: rec.idempotencyKey } };
    const first = (r: { data?: { data?: unknown[] }; response: Response }) => {
      if (!r.response.ok) throw new Error("lookup failed");
      return r.data?.data?.[0] ?? null;
    };
    switch (rec.kind) {
      case "CASH_IN": return first(await api.GET("/v1/agent/transactions", { params: query }));
      case "BILL_PAYMENT": return first(await api.GET("/v1/agent/bill-payments", { params: query }));
      case "AIRTIME": return first(await api.GET("/v1/agent/airtime-topups", { params: query }));
      case "FLOAT_REQUEST": return first(await api.GET("/v1/agent/float-requests", { params: query }));
      case "ONBOARDING": return first(await api.GET("/v1/agent/offline-batches", { params: query }));
    }
    return null;
  },
};

/**
 * Builds the offline-batch body for one onboarding captured offline, signed with the device key over the canonical
 * batch text (tenant, agent, device, the record's Idempotency-Key, capture window, count and the SHA-256 of the
 * payload). Called at capture, once the record's key is known, so every replay sends the same signed body.
 * payload_uri is a gzip data: URI; the contract limits it to 500 characters, so only a compact record fits.
 */
export async function buildOfflineBatch(o: { clientRef: string; tenantId: string; agentId: string; deviceId: string;
  capturedAt: string; idempotencyKey: string; application: Record<string, unknown> }): Promise<Record<string, unknown>> {
  const payload = JSON.stringify({ applications: [{ client_ref: o.clientRef, captured_at: o.capturedAt, channel: "AGENT", ...o.application }] });
  const gz = await new Response(new Blob([payload]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
  const uri = `data:application/gzip;base64,${toB64(gz)}`;
  if (uri.length > 500) {
    throw new Error("Too much data to queue offline (the offline batch payload is limited to 500 characters)");
  }
  const signature = await sign(await batchPayload({
    tenant: o.tenantId, agent: o.agentId, device: o.deviceId, idempotencyKey: o.idempotencyKey,
    capturedFrom: o.capturedAt, capturedTo: o.capturedAt, applicationCount: 1, payloadUri: uri,
  }));
  return {
    agent_device_id: o.deviceId,
    captured_from: o.capturedAt,
    captured_to: o.capturedAt,
    application_count: 1,
    payload_uri: uri,
    signature,
  };
}
