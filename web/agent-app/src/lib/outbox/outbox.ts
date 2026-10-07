import { deriveKey, fromB64, open, randomBytes, seal, toB64, type Sealed } from "./crypto";
import type { OutboxStore } from "./store";
import {
  FLOAT_DEBITS, type AttemptOutcome, type Blocker, type ExecContext, type Executor, type FlushReport, type OpKind,
  type OpPayload, type OpResult, type OutboxRecord, type OutboxView, type Problem,
} from "./types";
import { cmp, fromMinor, toMinor } from "../money";

/**
 * Conservative limits for operations captured while offline (the server cannot check limits, fraud or the float
 * in real time). Amounts are decimal strings in the agent's currency.
 */
export interface OfflinePolicy {
  /** Largest single operation accepted offline. */
  maxAmountPerOp: string;
  /** Total of all unsynced operations. */
  maxTotalAmount: string;
  /** Number of unsynced operations. */
  maxCount: number;
  /** Offline capture stops this many hours after the last successful contact with the server. */
  maxOfflineHours: number;
}

export const DEFAULT_POLICY: OfflinePolicy = {
  maxAmountPerOp: "5000",
  maxTotalAmount: "20000",
  maxCount: 10,
  maxOfflineHours: 12,
};

export type LimitCode = "amount_over_limit" | "total_over_limit" | "count_over_limit" | "offline_too_long"
  | "float_insufficient" | "locked";

export class OfflineLimitError extends Error {
  constructor(public code: LimitCode, message: string) {
    super(message);
  }
}

export class OutboxLockedError extends Error {
  constructor() {
    super("The outbox is locked; unlock it with the app PIN");
  }
}

export interface EnqueueOptions {
  /** True when the operation is captured without connectivity: the offline policy applies. */
  offline: boolean;
  /** Last successful server contact (ISO); required for the offline-age limit. */
  lastOnlineAt?: string | null;
  /** Last known available float; offline float debits may not exceed it net of other unsynced debits. */
  floatAvailable?: string | null;
}

/** Decision for one attempt's outcome. Pure function: easy to test and to reason about. */
export type Decision =
  | { action: "synced"; duplicate: boolean; result: OpResult }
  | { action: "rejected"; result: OpResult }
  | { action: "verify"; result: OpResult }
  | { action: "retry"; blocker: Blocker; result: OpResult; clearPin?: boolean };

export function reconcile(o: AttemptOutcome): Decision {
  if (o.kind === "ok") {
    return { action: "synced", duplicate: o.replayed, result: { httpStatus: o.status, duplicate: o.replayed, body: o.body } };
  }
  if (o.kind === "network") {
    return { action: "retry", blocker: "NETWORK", result: { note: o.message } };
  }
  const p: Problem = o.problem ?? {};
  const result: OpResult = { httpStatus: o.status, problem: p };
  if (o.status === 401) return { action: "retry", blocker: "AUTH", result };
  if (o.status === 408 || o.status === 425 || o.status === 429 || o.status >= 500) {
    return { action: "retry", blocker: "NETWORK", result };
  }
  if (o.status === 409 && p.code === "request_in_progress") return { action: "retry", blocker: "IN_PROGRESS", result };
  if (p.code === "pin_required") return { action: "retry", blocker: "NEEDS_PIN", result };
  if (p.code === "invalid_pin") return { action: "retry", blocker: "NEEDS_PIN", result, clearPin: true };
  if (p.code === "idempotency_key_reused") return { action: "verify", result };
  return { action: "rejected", result };
}

const VERIFIER = "meridian-agent-outbox-v1";

interface KeyMeta { salt: string; verifier: Sealed; binding: string; iterations: number }
interface LockMeta { failures: number; lockedUntil?: string }

export interface OutboxOptions {
  policy?: OfflinePolicy;
  now?: () => Date;
  uuid?: () => string;
  iterations?: number;
}

/**
 * Encrypted, ordered, idempotent outbox for operations captured offline (or whose online attempt did not reach
 * a definite outcome). Each record gets its id and Idempotency-Key once, at capture; every replay re-sends the same
 * body with the same key, so the server applies it at most once.
 */
export class Outbox {
  private key: CryptoKey | null = null;
  private flushing: Promise<FlushReport> | null = null;
  policy: OfflinePolicy;
  private readonly now: () => Date;
  private readonly uuid: () => string;
  private readonly iterations?: number;

  constructor(private readonly store: OutboxStore, opts: OutboxOptions = {}) {
    this.policy = opts.policy ?? DEFAULT_POLICY;
    this.now = opts.now ?? (() => new Date());
    this.uuid = opts.uuid ?? (() => globalThis.crypto.randomUUID());
    this.iterations = opts.iterations;
  }

  get unlocked(): boolean {
    return this.key !== null;
  }

  async isInitialized(binding?: string): Promise<boolean> {
    const m = await this.store.getMeta<KeyMeta>("key");
    return !!m && (binding === undefined || m.binding === binding);
  }

  /** First use on this device for this agent: creates the salt and the PIN verifier. */
  async setup(pin: string, binding: string): Promise<void> {
    if (!/^\d{6}$/.test(pin)) throw new Error("The app PIN must be 6 digits");
    const existing = await this.store.getMeta<KeyMeta>("key");
    if (existing && (await this.all()).some((r) => r.status === "PENDING")) {
      throw new Error("Unsynced operations exist; they must be synced before the app PIN can be reset");
    }
    // Final records sealed under the previous key would be unreadable: drop them (they are on the server).
    for (const r of await this.all()) await this.store.delete(r.id);
    const salt = randomBytes(16);
    const iterations = this.iterations ?? 210_000;
    const key = await deriveKey(pin, salt, binding, iterations);
    const verifier = await seal(key, VERIFIER, "verifier");
    await this.store.setMeta("key", { salt: toB64(salt), verifier, binding, iterations } satisfies KeyMeta);
    await this.store.setMeta("lock", { failures: 0 } satisfies LockMeta);
    this.key = key;
  }

  /** Unlocks with the app PIN. Five failures lock unlocking for 5 minutes, doubling after each further failure. */
  async unlock(pin: string): Promise<boolean> {
    const m = await this.store.getMeta<KeyMeta>("key");
    if (!m) throw new Error("The outbox has not been set up on this device");
    const lock = (await this.store.getMeta<LockMeta>("lock")) ?? { failures: 0 };
    if (lock.lockedUntil && new Date(lock.lockedUntil) > this.now()) {
      throw new OfflineLimitError("locked", `Too many wrong PINs; try again after ${new Date(lock.lockedUntil).toLocaleTimeString()}`);
    }
    try {
      const key = await deriveKey(pin, fromB64(m.salt), m.binding, m.iterations);
      const v = await open<string>(key, m.verifier, "verifier");
      if (v !== VERIFIER) throw new Error("bad verifier");
      this.key = key;
      await this.store.setMeta("lock", { failures: 0 } satisfies LockMeta);
      return true;
    } catch {
      const failures = lock.failures + 1;
      const next: LockMeta = { failures };
      if (failures >= 5) {
        const minutes = 5 * 2 ** (failures - 5);
        next.lockedUntil = new Date(this.now().getTime() + minutes * 60_000).toISOString();
      }
      await this.store.setMeta("lock", next);
      return false;
    }
  }

  lock(): void {
    this.key = null;
  }

  private requireKey(): CryptoKey {
    if (!this.key) throw new OutboxLockedError();
    return this.key;
  }

  async all(): Promise<OutboxRecord[]> {
    return this.store.all();
  }

  async decrypt(rec: OutboxRecord): Promise<OutboxView> {
    const key = this.requireKey();
    const payload = await open<OpPayload>(key, rec.payload, rec.id);
    const result = rec.result ? await open<OpResult>(key, rec.result, rec.id + ":result") : undefined;
    return { ...rec, payload, result };
  }

  async list(): Promise<OutboxView[]> {
    const recs = await this.store.all();
    return Promise.all(recs.map((r) => this.decrypt(r)));
  }

  /** Count and total of unsynced records (amounts need the key). */
  async pending(): Promise<{ count: number; total: string; floatDebits: string }> {
    const views = (await this.list()).filter((v) => v.status === "PENDING");
    let total = BigInt(0);
    let floatDebits = BigInt(0);
    for (const v of views) {
      total += toMinor(v.payload.amount);
      if (FLOAT_DEBITS.has(v.kind)) floatDebits += toMinor(v.payload.amount);
    }
    return { count: views.length, total: fromMinor(total), floatDebits: fromMinor(floatDebits) };
  }

  /** Validates an operation against the offline policy; throws OfflineLimitError when it must be refused. */
  async checkPolicy(kind: OpKind, amount: string | undefined, opts: EnqueueOptions): Promise<void> {
    if (!opts.offline) return;
    const p = this.policy;
    const last = opts.lastOnlineAt ? new Date(opts.lastOnlineAt) : null;
    if (!last || this.now().getTime() - last.getTime() > p.maxOfflineHours * 3_600_000) {
      throw new OfflineLimitError("offline_too_long",
        `Offline capture is only allowed within ${p.maxOfflineHours} h of the last successful connection`);
    }
    const pend = await this.pending();
    if (pend.count + 1 > p.maxCount) {
      throw new OfflineLimitError("count_over_limit", `At most ${p.maxCount} operations may wait for sync`);
    }
    if (amount !== undefined) {
      if (cmp(amount, p.maxAmountPerOp) > 0) {
        throw new OfflineLimitError("amount_over_limit", `Offline operations are limited to ${p.maxAmountPerOp} each`);
      }
      if (toMinor(pend.total) + toMinor(amount) > toMinor(p.maxTotalAmount)) {
        throw new OfflineLimitError("total_over_limit", `Unsynced operations may not exceed ${p.maxTotalAmount} in total`);
      }
      if (FLOAT_DEBITS.has(kind) && opts.floatAvailable !== undefined && opts.floatAvailable !== null
        && toMinor(pend.floatDebits) + toMinor(amount) > toMinor(opts.floatAvailable)) {
        throw new OfflineLimitError("float_insufficient", "Not enough float (last known balance less unsynced operations)");
      }
    }
  }

  /**
   * Captures an operation: allocates its order, id and Idempotency-Key, encrypts the payload and persists it.
   * `prepare` may complete the payload once the Idempotency-Key is known (e.g. a device signature that covers the
   * key), so every replay re-sends exactly the same, already signed, body.
   */
  async enqueue(kind: OpKind, payload: OpPayload, opts: EnqueueOptions,
    prepare?: (idempotencyKey: string) => Promise<OpPayload>): Promise<OutboxRecord> {
    const key = this.requireKey();
    await this.checkPolicy(kind, payload.amount, opts);
    const id = this.uuid();
    const idempotencyKey = `agentapp-${id}`;
    const final = prepare ? await prepare(idempotencyKey) : payload;
    if ("pin" in final.request) throw new Error("PINs must never be stored in the outbox");
    const seq = await this.store.nextSeq();
    const ts = this.now().toISOString();
    const rec: OutboxRecord = {
      id, seq, kind, status: "PENDING", idempotencyKey, createdAt: ts, updatedAt: ts,
      createdOffline: opts.offline, attempts: 0, payload: await seal(key, final, id),
    };
    await this.store.put(rec);
    return rec;
  }

  /**
   * Replays PENDING records strictly in capture order. A record that cannot be decided now (no network, expired
   * session, PIN needed, still processing) stops the run so later records never overtake it. Runs never overlap:
   * a call made during a run starts after it (and then finds only what is still pending).
   */
  flush(executor: Executor, ctx: ExecContext = {}, only?: string): Promise<FlushReport> {
    const next = (this.flushing ?? Promise.resolve()).catch(() => undefined).then(() => this.run(executor, ctx, only));
    const tracked = next.finally(() => { if (this.flushing === tracked) this.flushing = null; });
    tracked.catch(() => undefined);
    this.flushing = tracked;
    return next;
  }

  private async run(executor: Executor, ctx: ExecContext, only?: string): Promise<FlushReport> {
    const key = this.requireKey();
    const report: FlushReport = { attempted: 0, synced: 0, duplicates: 0, rejected: 0, review: 0, remaining: 0 };
    const queue = (await this.store.all()).filter((r) => r.status === "PENDING");
    let stopped = false;
    for (const rec of queue) {
      if (stopped) { report.remaining++; continue; }
      const payload = await open<OpPayload>(key, rec.payload, rec.id);
      rec.attempts += 1;
      rec.lastAttemptAt = this.now().toISOString();
      report.attempted++;
      let outcome: AttemptOutcome;
      try {
        outcome = await executor.execute(rec, payload, ctx);
      } catch (e) {
        outcome = { kind: "network", message: e instanceof Error ? e.message : String(e) };
      }
      let d = reconcile(outcome);
      if (d.action === "verify") {
        const found = executor.lookup ? await executor.lookup(rec, payload).catch(() => undefined) : null;
        if (found === undefined) {
          d = { action: "retry", blocker: "NETWORK", result: { ...d.result, note: "lookup failed" } };
        } else if (found) {
          d = { action: "synced", duplicate: true, result: { duplicate: true, body: found, note: "matched by Idempotency-Key" } };
        } else {
          rec.status = "NEEDS_REVIEW";
        }
      }
      if (d.action === "synced") {
        rec.status = "SYNCED";
        rec.blocker = undefined;
        report.synced++;
        if (d.duplicate) report.duplicates++;
      } else if (d.action === "rejected") {
        rec.status = "REJECTED";
        rec.blocker = undefined;
        report.rejected++;
      } else if (d.action === "verify") {
        report.review++;
        rec.blocker = undefined;
      } else {
        rec.blocker = d.blocker;
        if (d.clearPin) ctx.agentPin = undefined;
        report.blocked = d.blocker;
        report.remaining++;
        stopped = true;
      }
      rec.updatedAt = this.now().toISOString();
      rec.result = await seal(key, d.result, rec.id + ":result");
      await this.store.put(rec);
      if (only && rec.id === only && !stopped) {
        report.remaining += queue.length - queue.indexOf(rec) - 1;
        break;
      }
    }
    return report;
  }

  /** Removes a final record (synced, rejected or reviewed) from the device. PENDING records cannot be removed. */
  async remove(id: string): Promise<void> {
    const r = await this.store.get(id);
    if (!r) return;
    if (r.status === "PENDING") throw new Error("Unsynced operations cannot be deleted");
    await this.store.delete(id);
  }

  /** Housekeeping: drops synced records older than the retention window (default 7 days). */
  async purgeSynced(maxAgeMs = 7 * 24 * 3_600_000): Promise<number> {
    const cutoff = this.now().getTime() - maxAgeMs;
    let n = 0;
    for (const r of await this.store.all()) {
      if (r.status === "SYNCED" && new Date(r.updatedAt).getTime() < cutoff) {
        await this.store.delete(r.id);
        n++;
      }
    }
    return n;
  }
}
