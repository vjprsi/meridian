"use client";

import { ApiError, agents, biller, onboarding, wallet } from "@/api/client";
import { uploadDocument, type DocType } from "@/lib/documents";
import { fromB64, sha256Hex, toB64 } from "./crypto";
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
const minutesBefore = (iso: string, m: number) => new Date(new Date(iso).getTime() - m * 60_000).toISOString();

function withPin(body: Record<string, unknown>, ctx: ExecContext) {
  return ctx.agentPin ? { ...body, pin: ctx.agentPin } : body;
}

/** Uploads the ID photo captured with an offline onboarding and links it to the created application. */
async function uploadAttachment(rec: OutboxRecord, p: OpPayload, applicationId: string): Promise<AttemptOutcome> {
  const a = p.attachment!;
  try {
    const doc = await uploadDocument({
      ownerType: "ONBOARDING_APPLICATION", ownerId: applicationId, bytes: fromB64(a.base64), contentType: a.contentType,
      fileName: a.fileName, documentType: a.documentType as DocType, keyPrefix: rec.idempotencyKey,
    });
    return { kind: "ok", status: 200, body: doc, replayed: false };
  } catch (e) {
    if (e instanceof ApiError) return { kind: "http", status: e.problem.status ?? 422, problem: e.problem };
    return { kind: "network", message: e instanceof Error ? e.message : String(e) };
  }
}

/** Executes outbox records against the platform with the typed clients. */
export const apiExecutor: Executor = {
  async execute(rec, p, ctx) {
    const key = H(rec.idempotencyKey);
    const body = p.request;
    switch (rec.kind) {
      case "CASH_IN":
        return attempt(() => wallet.POST("/v1/wallet-transactions/cash-in", {
          params: { header: key }, body: withPin(body, ctx) as never,
        }));
      case "BILL_PAYMENT":
        return attempt(() => biller.POST("/v1/bill-payments", { params: { header: key }, body: withPin(body, ctx) as never }));
      case "AIRTIME":
        return attempt(() => biller.POST("/v1/airtime-topups", { params: { header: key }, body: withPin(body, ctx) as never }));
      case "FLOAT_REQUEST":
        return attempt(() => agents.POST("/v1/agent-float-requests", { params: { header: key }, body: body as never }));
      case "ONBOARDING": {
        const batch = await attempt(() => onboarding.POST("/v1/onboarding/offline-batches", {
          params: { header: key }, body: body as never,
        }));
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

  async lookup(rec, p) {
    const since = minutesBefore(rec.createdAt, 10);
    const match = <T extends { idempotency_key?: string }>(rows: T[] | undefined) =>
      rows?.find((r) => r.idempotency_key === rec.idempotencyKey) ?? null;
    if (rec.kind === "CASH_IN") {
      const r = await wallet.GET("/v1/wallet-transactions", { params: { query: {
        txn_type: "CASH_IN", payer_wallet_id: String(p.request.agent_wallet_id), created_after: since, limit: 200 } } });
      if (!r.response.ok) throw new Error("lookup failed");
      return match(r.data?.data);
    }
    if (rec.kind === "BILL_PAYMENT") {
      const r = await biller.GET("/v1/bill-payments", { params: { query: {
        payer_wallet_id: String(p.request.payer_wallet_id), created_after: since, limit: 200 } } });
      if (!r.response.ok) throw new Error("lookup failed");
      return match(r.data?.data);
    }
    if (rec.kind === "AIRTIME") {
      const r = await biller.GET("/v1/airtime-topups", { params: { query: {
        payer_wallet_id: String(p.request.payer_wallet_id), created_after: since, limit: 200 } } });
      if (!r.response.ok) throw new Error("lookup failed");
      return match(r.data?.data);
    }
    return null;
  },
};

/**
 * Builds the inline offline-batch body for one onboarding captured offline (payload_uri is a gzip data: URI; the
 * contract limits it to 500 characters, so only a compact record fits).
 */
export async function buildOfflineBatch(clientRef: string, agentId: string, deviceFingerprint: string,
  capturedAt: string, application: Record<string, unknown>): Promise<Record<string, unknown>> {
  const payload = JSON.stringify({ applications: [{ client_ref: clientRef, captured_at: capturedAt, channel: "AGENT", ...application }] });
  const gz = await new Response(new Blob([payload]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
  const uri = `data:application/gzip;base64,${toB64(gz)}`;
  if (uri.length > 500) {
    throw new Error("Too much data to queue offline (the offline batch payload is limited to 500 characters)");
  }
  return {
    agent_id: agentId,
    device_fingerprint: deviceFingerprint,
    captured_from: capturedAt,
    captured_to: capturedAt,
    application_count: 1,
    payload_uri: uri,
    // The contract has no device signing key yet; the SHA-256 digest of the payload stands in (see API gaps).
    payload_signature: `sha256:${await sha256Hex(payload)}`,
  };
}
