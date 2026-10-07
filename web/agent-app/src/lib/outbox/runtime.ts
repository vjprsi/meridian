"use client";

import { useEffect, useSyncExternalStore } from "react";
import { offlinePolicy } from "@/lib/config";
import { isOnline, lastOnlineAt } from "@/lib/connectivity";
import { loadProfile } from "@/lib/agent-context";
import { setSigningUnlocked } from "@/lib/device-key";
import { apiExecutor } from "./executor";
import { Outbox, type EnqueueOptions } from "./outbox";
import { IdbOutboxStore, MemoryOutboxStore } from "./store";
import type { FlushReport, OpKind, OpPayload, OutboxView } from "./types";

/**
 * The app-wide outbox: one per page lifetime. The key (from the app PIN) and the agent's wallet PIN live only in
 * this module's memory; locking, signing out, idle timeout or closing the app forgets both.
 */
const store = typeof indexedDB !== "undefined" ? new IdbOutboxStore() : new MemoryOutboxStore();
export const outbox = new Outbox(store, { policy: offlinePolicy });

let agentPin: string | undefined;
let pinTimer: ReturnType<typeof setTimeout> | null = null;

interface Snapshot {
  unlocked: boolean;
  views: OutboxView[];
  pending: number;
  syncing: boolean;
  last?: FlushReport & { at: string };
  hasAgentPin: boolean;
}

let snap: Snapshot = { unlocked: false, views: [], pending: 0, syncing: false, hasAgentPin: false };
const listeners = new Set<() => void>();

function set(patch: Partial<Snapshot>) {
  snap = { ...snap, ...patch };
  listeners.forEach((l) => l());
}

export async function refresh() {
  if (!outbox.unlocked) {
    const all = await outbox.all().catch(() => []);
    set({ unlocked: false, views: [], pending: all.filter((r) => r.status === "PENDING").length });
    return;
  }
  const views = await outbox.list();
  set({ unlocked: true, views, pending: views.filter((v) => v.status === "PENDING").length });
}

/** Remembers the agent wallet PIN in memory for a few minutes so queued operations can be replayed. */
export function rememberAgentPin(pin: string | undefined, minutes = 5) {
  agentPin = pin || undefined;
  if (pinTimer) clearTimeout(pinTimer);
  if (agentPin) pinTimer = setTimeout(() => { agentPin = undefined; set({ hasAgentPin: false }); }, minutes * 60_000);
  set({ hasAgentPin: !!agentPin });
}

export async function unlock(pin: string): Promise<boolean> {
  const ok = await outbox.unlock(pin);
  // The device key signs (offline batches, device proofs) only while the app is unlocked with the app PIN.
  setSigningUnlocked(ok);
  await refresh();
  if (ok) void syncNow();
  return ok;
}

export async function setupPin(pin: string, binding: string) {
  await outbox.setup(pin, binding);
  setSigningUnlocked(true);
  await refresh();
}

export function lockApp() {
  outbox.lock();
  setSigningUnlocked(false);
  rememberAgentPin(undefined);
  void refresh();
}

/** Replays the queue (in order) when online and unlocked. Safe to call any time. */
export async function syncNow(only?: string): Promise<FlushReport | null> {
  if (!outbox.unlocked || !isOnline()) return null;
  set({ syncing: true });
  try {
    const ctx = { agentPin };
    const report = await outbox.flush(apiExecutor, ctx, only);
    if (ctx.agentPin !== agentPin) rememberAgentPin(ctx.agentPin);
    set({ last: { ...report, at: new Date().toISOString() } });
    return report;
  } finally {
    set({ syncing: false });
    await refresh();
  }
}

/**
 * Captures a money operation. Online: it is persisted first (so a crash or a lost response cannot lose or double
 * it) and sent at once, in order behind anything older. Offline: it is checked against the offline policy and
 * left for sync. Returns the record as it stands afterwards.
 */
export async function submit(kind: OpKind, payload: OpPayload,
  opts?: { pin?: string; offline?: boolean; prepare?: (idempotencyKey: string) => Promise<OpPayload> }): Promise<OutboxView> {
  const offline = opts?.offline ?? !isOnline();
  const profile = loadProfile();
  const eo: EnqueueOptions = { offline, lastOnlineAt: lastOnlineAt(), floatAvailable: profile?.floatAvailable ?? null };
  const rec = await outbox.enqueue(kind, payload, eo, opts?.prepare);
  if (opts?.pin) rememberAgentPin(opts.pin);
  if (!offline) await syncNow(rec.id);
  else await refresh();
  const view = (await outbox.list()).find((v) => v.id === rec.id);
  if (!view) throw new Error("Operation not found after capture");
  // Ask the service worker to replay when connectivity returns, even if the agent leaves this screen.
  if (view.status === "PENDING") void requestBackgroundSync();
  return view;
}

async function requestBackgroundSync() {
  try {
    const reg = await navigator.serviceWorker?.ready;
    const sync = (reg as ServiceWorkerRegistration & { sync?: { register(tag: string): Promise<void> } })?.sync;
    await sync?.register("meridian-outbox");
  } catch { /* Background Sync unsupported: the online event and the timer cover it */ }
}

export function useOutbox(): Snapshot {
  return useSyncExternalStore(
    (cb) => { listeners.add(cb); return () => listeners.delete(cb); },
    () => snap,
    () => snap,
  );
}

/** Wires automatic sync: back-online event, service-worker background sync message, and a 60 s timer. */
export function useAutoSync() {
  useEffect(() => {
    void refresh();
    const run = () => { void syncNow(); };
    window.addEventListener("meridian:online", run);
    const onMsg = (e: MessageEvent) => { if (e.data?.type === "meridian:sync") run(); };
    navigator.serviceWorker?.addEventListener("message", onMsg);
    const t = setInterval(() => { if (snap.pending > 0) run(); }, 60_000);
    return () => {
      window.removeEventListener("meridian:online", run);
      navigator.serviceWorker?.removeEventListener("message", onMsg);
      clearInterval(t);
    };
  }, []);
}
