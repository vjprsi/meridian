import { describe, expect, it, beforeEach } from "vitest";
import { IDBFactory } from "fake-indexeddb";
import { deriveKey, open, randomBytes, seal } from "@/lib/outbox/crypto";
import { IdbOutboxStore, MemoryOutboxStore, type OutboxStore } from "@/lib/outbox/store";
import { Outbox, OfflineLimitError, OutboxLockedError, reconcile, type OfflinePolicy } from "@/lib/outbox/outbox";
import type { AttemptOutcome, Executor, OpPayload, OutboxRecord } from "@/lib/outbox/types";

const BINDING = "tenant-1:agent-1";
const PIN = "246810";
const FAST = 1_000; // PBKDF2 iterations in tests (production uses 210k)
const policy: OfflinePolicy = { maxAmountPerOp: "1000", maxTotalAmount: "2500", maxCount: 4, maxOfflineHours: 12 };

function cashIn(amount: string, msisdn = "+254700000001"): OpPayload {
  return {
    request: { agent_wallet_id: "w-agent", payee_msisdn: msisdn, amount, currency: "KES", channel: "AGENT_APP" },
    amount, currency: "KES", display: { customer: msisdn },
  };
}

const online = { offline: false };
const offline = () => ({ offline: true, lastOnlineAt: new Date().toISOString(), floatAvailable: "100000" });

/** Executor whose answers are scripted per call; records the order and keys it saw. */
class FakeExecutor implements Executor {
  calls: { id: string; seq: number; key: string; body: Record<string, unknown>; pin?: string }[] = [];
  constructor(private script: (rec: OutboxRecord, n: number) => AttemptOutcome, private found: unknown = null) {}
  async execute(rec: OutboxRecord, p: OpPayload, ctx: { agentPin?: string }) {
    this.calls.push({ id: rec.id, seq: rec.seq, key: rec.idempotencyKey, body: p.request, pin: ctx.agentPin });
    return this.script(rec, this.calls.length);
  }
  async lookup() { return this.found; }
}

const ok = (body: unknown = { id: "srv-1", status: "COMPLETED" }, replayed = false): AttemptOutcome =>
  ({ kind: "ok", status: 200, body, replayed });

async function newOutbox(store: OutboxStore = new MemoryOutboxStore()) {
  const ob = new Outbox(store, { policy, iterations: FAST });
  await ob.setup(PIN, BINDING);
  return { ob, store };
}

describe("encryption", () => {
  it("round-trips a value and rejects a wrong key, tampering and a moved ciphertext", async () => {
    const salt = randomBytes(16);
    const key = await deriveKey(PIN, salt, BINDING, FAST);
    const sealed = await seal(key, { msisdn: "+254700000001", amount: "50" }, "rec-1");
    expect(await open(key, sealed, "rec-1")).toEqual({ msisdn: "+254700000001", amount: "50" });

    const wrong = await deriveKey("135790", salt, BINDING, FAST);
    await expect(open(wrong, sealed, "rec-1")).rejects.toThrow();
    const otherAgent = await deriveKey(PIN, salt, "tenant-1:agent-2", FAST);
    await expect(open(otherAgent, sealed, "rec-1")).rejects.toThrow();
    await expect(open(key, sealed, "rec-2")).rejects.toThrow();
    const ct = sealed.ct.startsWith("A") ? "B" + sealed.ct.slice(1) : "A" + sealed.ct.slice(1);
    await expect(open(key, { ...sealed, ct }, "rec-1")).rejects.toThrow();
  });

  it("stores no plaintext customer data and refuses PINs in payloads", async () => {
    const { ob, store } = await newOutbox();
    await ob.enqueue("CASH_IN", cashIn("7531.25", "+254711222333"), online);
    const raw = JSON.stringify(await store.all());
    expect(raw).not.toContain("+254711222333");
    expect(raw).not.toContain("7531.25");
    const withPin = cashIn("10");
    withPin.request.pin = "1234";
    await expect(ob.enqueue("CASH_IN", withPin, online)).rejects.toThrow(/PIN/);
  });

  it("unlocks only with the right PIN and locks out after repeated failures", async () => {
    const store = new MemoryOutboxStore();
    const { ob } = await newOutbox(store);
    await ob.enqueue("CASH_IN", cashIn("20"), online);
    ob.lock();
    await expect(ob.list()).rejects.toBeInstanceOf(OutboxLockedError);
    expect(await ob.unlock("000000")).toBe(false);
    expect(await ob.unlock(PIN)).toBe(true);
    expect((await ob.list())[0].payload.amount).toBe("20");

    const again = new Outbox(store, { policy, iterations: FAST });
    for (let i = 0; i < 5; i++) expect(await again.unlock("999999")).toBe(false);
    await expect(again.unlock(PIN)).rejects.toBeInstanceOf(OfflineLimitError);
  });

  it("persists encrypted records in IndexedDB across instances", async () => {
    const factory = new IDBFactory();
    const s1 = new IdbOutboxStore("t-db", factory);
    const { ob } = await newOutbox(s1);
    const rec = await ob.enqueue("AIRTIME", { request: { target_msisdn: "+254700000009", amount: "30" }, amount: "30", display: {} }, online);
    const ob2 = new Outbox(new IdbOutboxStore("t-db", factory), { policy, iterations: FAST });
    expect(await ob2.unlock(PIN)).toBe(true);
    const views = await ob2.list();
    expect(views).toHaveLength(1);
    expect(views[0].id).toBe(rec.id);
    expect(views[0].payload.request.target_msisdn).toBe("+254700000009");
  });
});

describe("ordering and idempotency keys", () => {
  let ob: Outbox;
  beforeEach(async () => { ({ ob } = await newOutbox()); });

  it("assigns increasing sequence numbers and a unique Idempotency-Key per operation", async () => {
    const a = await ob.enqueue("CASH_IN", cashIn("10"), online);
    const b = await ob.enqueue("CASH_IN", cashIn("10"), online);
    const c = await ob.enqueue("BILL_PAYMENT", cashIn("10"), online);
    expect([a.seq, b.seq, c.seq]).toEqual([1, 2, 3]);
    expect(new Set([a.idempotencyKey, b.idempotencyKey, c.idempotencyKey]).size).toBe(3);
  });

  it("replays in capture order and stops at the first undecidable record, keeping the same key on retry", async () => {
    const recs = [];
    for (const amt of ["1", "2", "3", "4"]) recs.push(await ob.enqueue("CASH_IN", cashIn(amt), online));
    let down = true;
    const ex = new FakeExecutor((rec) => (down && rec.seq === 2 ? { kind: "network", message: "offline" } : ok()));
    const r1 = await ob.flush(ex);
    expect(ex.calls.map((c) => c.seq)).toEqual([1, 2]);
    expect(r1).toMatchObject({ synced: 1, remaining: 3, blocked: "NETWORK" });

    down = false;
    const r2 = await ob.flush(ex);
    expect(ex.calls.map((c) => c.seq)).toEqual([1, 2, 2, 3, 4]);
    expect(r2).toMatchObject({ synced: 3, remaining: 0 });
    // The retried record was sent twice with the same key and the same body.
    const second = ex.calls.filter((c) => c.seq === 2);
    expect(second[0].key).toBe(second[1].key);
    expect(second[0].body).toEqual(second[1].body);
    expect((await ob.all()).every((r) => r.status === "SYNCED")).toBe(true);
  });

  it("never overlaps runs: a record is sent once, and one captured during a run is sent by the next", async () => {
    await ob.enqueue("CASH_IN", cashIn("1"), online);
    let late: OutboxRecord | undefined;
    const ex = new FakeExecutor(() => ok());
    const slow: Executor = {
      execute: async (rec, p, ctx) => {
        if (!late) late = await ob.enqueue("CASH_IN", cashIn("2"), online); // captured while the first is in flight
        return ex.execute(rec, p, ctx);
      },
    };
    const [r1, r2] = await Promise.all([ob.flush(slow), ob.flush(slow, {}, "only-this-one-is-not-needed")]);
    expect(ex.calls.map((c) => c.seq)).toEqual([1, 2]);
    expect(r1.synced + r2.synced).toBe(2);
  });
});

describe("reconciliation", () => {
  it("classifies server outcomes", () => {
    expect(reconcile(ok({}, false))).toMatchObject({ action: "synced", duplicate: false });
    expect(reconcile(ok({}, true))).toMatchObject({ action: "synced", duplicate: true });
    expect(reconcile({ kind: "http", status: 422, problem: { code: "insufficient_funds" } })).toMatchObject({ action: "rejected" });
    expect(reconcile({ kind: "http", status: 409, problem: { code: "request_in_progress" } })).toMatchObject({ action: "retry", blocker: "IN_PROGRESS" });
    expect(reconcile({ kind: "http", status: 502, problem: {} })).toMatchObject({ action: "retry", blocker: "NETWORK" });
    expect(reconcile({ kind: "http", status: 401, problem: {} })).toMatchObject({ action: "retry", blocker: "AUTH" });
    expect(reconcile({ kind: "http", status: 422, problem: { code: "pin_required" } })).toMatchObject({ action: "retry", blocker: "NEEDS_PIN" });
    expect(reconcile({ kind: "http", status: 422, problem: { code: "invalid_pin" } })).toMatchObject({ clearPin: true });
    expect(reconcile({ kind: "http", status: 422, problem: { code: "idempotency_key_reused" } })).toMatchObject({ action: "verify" });
  });

  it("stores rejections with their problem, treats replays as success and does not block later records", async () => {
    const { ob } = await newOutbox();
    const a = await ob.enqueue("CASH_IN", cashIn("1"), online);
    const b = await ob.enqueue("CASH_IN", cashIn("2"), online);
    const c = await ob.enqueue("CASH_IN", cashIn("3"), online);
    const problem = { status: 422, code: "payee_not_active", detail: "The payee wallet is BLOCKED" };
    const ex = new FakeExecutor((rec) =>
      rec.id === a.id ? ok({ id: "t1" }, true) : rec.id === b.id ? { kind: "http", status: 422, problem } : ok({ id: "t3" }));
    const r = await ob.flush(ex);
    expect(r).toMatchObject({ synced: 2, duplicates: 1, rejected: 1, remaining: 0 });
    const views = Object.fromEntries((await ob.list()).map((v) => [v.id, v]));
    expect(views[a.id].status).toBe("SYNCED");
    expect(views[a.id].result?.duplicate).toBe(true);
    expect(views[b.id].status).toBe("REJECTED");
    expect(views[b.id].result?.problem).toEqual(problem);
    expect(views[c.id].status).toBe("SYNCED");
    await expect(ob.remove(c.id)).resolves.toBeUndefined();
  });

  it("asks for the agent PIN, keeps the record pending, and never persists the PIN", async () => {
    const { ob, store } = await newOutbox();
    await ob.enqueue("CASH_IN", cashIn("5"), online);
    const ex = new FakeExecutor((_r, n) => (n === 1 ? { kind: "http", status: 422, problem: { code: "pin_required" } } : ok()));
    expect(await ob.flush(ex)).toMatchObject({ blocked: "NEEDS_PIN", remaining: 1 });
    expect((await ob.all())[0]).toMatchObject({ status: "PENDING", blocker: "NEEDS_PIN" });
    expect(await ob.flush(ex, { agentPin: "4321" })).toMatchObject({ synced: 1 });
    expect(ex.calls[1].pin).toBe("4321");
    expect(JSON.stringify(await (store as MemoryOutboxStore).all())).not.toContain("4321");
  });

  it("resolves an unknown outcome by Idempotency-Key lookup, or flags it for review", async () => {
    const { ob } = await newOutbox();
    const a = await ob.enqueue("CASH_IN", cashIn("5"), online);
    const reused: AttemptOutcome = { kind: "http", status: 422, problem: { code: "idempotency_key_reused" } };
    await ob.flush(new FakeExecutor(() => reused, { id: "t-found", idempotency_key: a.idempotencyKey }));
    expect((await ob.list())[0]).toMatchObject({ status: "SYNCED", result: { duplicate: true } });

    const b = await ob.enqueue("CASH_IN", cashIn("6"), online);
    await ob.flush(new FakeExecutor(() => reused, null));
    expect((await ob.list()).find((v) => v.id === b.id)?.status).toBe("NEEDS_REVIEW");
  });
});

describe("offline policy", () => {
  it("enforces per-operation, total, count, float and age limits only when offline", async () => {
    const { ob } = await newOutbox();
    await expect(ob.enqueue("CASH_IN", cashIn("1000.01"), offline())).rejects.toMatchObject({ code: "amount_over_limit" });
    await ob.enqueue("CASH_IN", cashIn("5000"), online); // online capture is checked by the server, not here
    await expect(ob.enqueue("CASH_IN", cashIn("1"), offline())).rejects.toMatchObject({ code: "total_over_limit" });

    const fresh = (await newOutbox()).ob;
    await fresh.enqueue("CASH_IN", cashIn("1000"), offline());
    await fresh.enqueue("CASH_IN", cashIn("1000"), offline());
    await expect(fresh.enqueue("CASH_IN", cashIn("600"), offline())).rejects.toMatchObject({ code: "total_over_limit" });
    await fresh.enqueue("FLOAT_REQUEST", { request: {}, display: {} }, offline());
    await fresh.enqueue("CASH_IN", cashIn("100"), offline());
    await expect(fresh.enqueue("CASH_IN", cashIn("1"), offline())).rejects.toMatchObject({ code: "count_over_limit" });

    const f2 = (await newOutbox()).ob;
    await expect(f2.enqueue("CASH_IN", cashIn("10"), { offline: true, lastOnlineAt: null })).rejects.toMatchObject({ code: "offline_too_long" });
    const stale = new Date(Date.now() - 13 * 3_600_000).toISOString();
    await expect(f2.enqueue("CASH_IN", cashIn("10"), { offline: true, lastOnlineAt: stale })).rejects.toMatchObject({ code: "offline_too_long" });
    await f2.enqueue("CASH_IN", cashIn("300"), { ...offline(), floatAvailable: "500" });
    await expect(f2.enqueue("AIRTIME", cashIn("201"), { ...offline(), floatAvailable: "500" })).rejects.toMatchObject({ code: "float_insufficient" });
    await f2.enqueue("FLOAT_REQUEST", { request: {}, amount: "900", display: {} }, { ...offline(), floatAvailable: "500" });
  });
});
