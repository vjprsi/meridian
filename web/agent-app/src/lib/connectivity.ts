"use client";

import { useSyncExternalStore } from "react";

/**
 * Connectivity as the agent experiences it: the device must be online AND the platform reachable. navigator.onLine
 * alone says nothing about a dead uplink or a down gateway, so every API response feeds this store (network errors
 * and 502/503/504 from the proxy mean "unreachable"), and a light probe re-checks while offline.
 */
export type Connectivity = "online" | "offline";

const LAST_ONLINE_KEY = "meridian.agentapp.lastOnlineAt";
let state: Connectivity = "online";
let reachable = true;
const listeners = new Set<() => void>();
let probeTimer: ReturnType<typeof setInterval> | null = null;
let forcedOffline = false;

function compute(): Connectivity {
  if (typeof navigator === "undefined") return "online";
  return !forcedOffline && navigator.onLine && reachable ? "online" : "offline";
}

function emit() {
  const next = compute();
  if (next !== state) {
    state = next;
    if (state === "online") {
      stopProbe();
      window.dispatchEvent(new Event("meridian:online"));
    } else {
      startProbe();
    }
    listeners.forEach((l) => l());
  }
}

export function lastOnlineAt(): string | null {
  try { return localStorage.getItem(LAST_ONLINE_KEY); } catch { return null; }
}

/** Called by the API layer after every response or network failure. */
export function reportReachability(ok: boolean) {
  reachable = ok;
  if (ok) {
    try { localStorage.setItem(LAST_ONLINE_KEY, new Date().toISOString()); } catch { /* ignore */ }
  }
  emit();
}

/** Developer / training switch: behave as offline even when connected (also used by the smoke test). */
export function setForcedOffline(v: boolean) {
  forcedOffline = v;
  try { sessionStorage.setItem("meridian.agentapp.forceOffline", v ? "1" : ""); } catch { /* ignore */ }
  emit();
}

export function isForcedOffline() {
  return forcedOffline;
}

async function probe() {
  if (forcedOffline || !navigator.onLine) return;
  try {
    // Any answer from the gateway (even 401/404) proves the platform is reachable; 502-504 come from the proxy.
    const r = await fetch("/v1/billers?limit=1", { method: "GET", cache: "no-store", headers: { "X-Probe": "1" } });
    reportReachability(r.status < 502 || r.status > 504);
  } catch {
    reportReachability(false);
  }
}

function startProbe() {
  if (!probeTimer && typeof window !== "undefined") probeTimer = setInterval(probe, 15_000);
}

function stopProbe() {
  if (probeTimer) clearInterval(probeTimer);
  probeTimer = null;
}

let installed = false;
function install() {
  if (installed || typeof window === "undefined") return;
  installed = true;
  try { forcedOffline = sessionStorage.getItem("meridian.agentapp.forceOffline") === "1"; } catch { /* ignore */ }
  window.addEventListener("online", () => { emit(); void probe(); });
  window.addEventListener("offline", emit);
  state = compute();
  if (state === "offline") startProbe();
}

export function isOnline(): boolean {
  install();
  return compute() === "online";
}

export function useConnectivity(): Connectivity {
  return useSyncExternalStore(
    (cb) => { install(); listeners.add(cb); return () => listeners.delete(cb); },
    () => { install(); return state; },
    () => "online",
  );
}

export { probe as probeNow };
