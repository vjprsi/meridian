import type { Sealed } from "./crypto";

/** Operations that may be captured while offline and replayed later. Cash-out is deliberately absent. */
export type OpKind = "CASH_IN" | "BILL_PAYMENT" | "AIRTIME" | "FLOAT_REQUEST" | "ONBOARDING";

/** Operations that spend the agent's float (they count against the last known float balance). */
export const FLOAT_DEBITS: ReadonlySet<OpKind> = new Set(["CASH_IN", "BILL_PAYMENT", "AIRTIME"]);

/**
 * PENDING: waiting to be sent (or re-sent). SYNCED: the server accepted it (possibly as an idempotent replay).
 * REJECTED: the server refused it with a problem; final. NEEDS_REVIEW: the outcome could not be established
 * automatically (e.g. the idempotency key was already used with another body and no matching transaction was found).
 */
export type OpStatus = "PENDING" | "SYNCED" | "REJECTED" | "NEEDS_REVIEW";

/** Why the queue stopped at a record (the record stays PENDING; later records wait to keep order). */
export type Blocker = "NETWORK" | "AUTH" | "NEEDS_PIN" | "IN_PROGRESS";

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

/** What is encrypted inside a record. Never contains a customer PIN or the agent's PIN. */
export interface OpPayload {
  /** The request body exactly as it will be sent (same body on every attempt, so idempotent replays match). */
  request: Record<string, unknown>;
  amount?: string;
  currency?: string;
  /** Human-readable fields for the pending list and the provisional receipt. */
  display: Record<string, string>;
  /** Optional binary attachment (e.g. ID document photo) as base64 with its content type. */
  attachment?: { contentType: string; base64: string; fileName: string; documentType: string };
}

/** Server outcome stored (encrypted) once a record leaves PENDING. */
export interface OpResult {
  httpStatus?: number;
  duplicate?: boolean;
  body?: unknown;
  problem?: Problem;
  note?: string;
}

export interface OutboxRecord {
  id: string;
  seq: number;
  kind: OpKind;
  status: OpStatus;
  idempotencyKey: string;
  createdAt: string;
  updatedAt: string;
  createdOffline: boolean;
  attempts: number;
  lastAttemptAt?: string;
  blocker?: Blocker;
  payload: Sealed;
  result?: Sealed;
}

/** A decrypted record for display. */
export interface OutboxView extends Omit<OutboxRecord, "payload" | "result"> {
  payload: OpPayload;
  result?: OpResult;
}

/** Raw result of one attempt as seen by the executor. */
export type AttemptOutcome =
  | { kind: "ok"; status: number; body: unknown; replayed: boolean }
  | { kind: "http"; status: number; problem: Problem }
  | { kind: "network"; message: string };

export interface ExecContext {
  /** Agent wallet PIN held in memory for this sync only (never persisted). */
  agentPin?: string;
}

/** Sends one record to the API. Implemented by the app (typed clients); tests use fakes. */
export interface Executor {
  execute(rec: OutboxRecord, payload: OpPayload, ctx: ExecContext): Promise<AttemptOutcome>;
  /**
   * Looks for a server-side object created with this record's idempotency key (used when the outcome is unknown,
   * e.g. idempotency_key_reused). Returns it, or null when nothing matches.
   */
  lookup?(rec: OutboxRecord, payload: OpPayload): Promise<unknown | null>;
}

export interface FlushReport {
  attempted: number;
  synced: number;
  duplicates: number;
  rejected: number;
  review: number;
  remaining: number;
  blocked?: Blocker;
}
