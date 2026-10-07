"use client";

import { useSyncExternalStore } from "react";

/**
 * The agent profile this device works for: chosen once after sign-in (agent, outlet, device) and kept in
 * localStorage so the app can start and capture offline work without the network. Only identifiers and display
 * names are kept here (no balances of customers, no secrets).
 */
export interface AgentProfile {
  tenantId: string;
  agentId: string;
  agentCode: string;
  agentName: string;
  agentLevel: string;
  parentAgentId?: string | null;
  superAgentId?: string | null;
  currency: string;
  floatWalletId?: string | null;
  commissionWalletId?: string | null;
  outletId?: string | null;
  outletName?: string | null;
  deviceId?: string | null;
  /** Last float balance read from the ledger, and when (bounds offline float debits). */
  floatAvailable?: string | null;
  floatAt?: string | null;
}

const KEY = "meridian.agentapp.profile";
const listeners = new Set<() => void>();
let cached: AgentProfile | null | undefined;
let raw: string | null = null;

function read(): AgentProfile | null {
  if (typeof window === "undefined") return null;
  try {
    const r = localStorage.getItem(KEY);
    if (r !== raw || cached === undefined) {
      raw = r;
      cached = r ? (JSON.parse(r) as AgentProfile) : null;
    }
  } catch {
    cached = null;
  }
  return cached ?? null;
}

export function loadProfile(): AgentProfile | null {
  return read();
}

export function saveProfile(p: AgentProfile | null) {
  try {
    if (p) localStorage.setItem(KEY, JSON.stringify(p));
    else localStorage.removeItem(KEY);
  } catch { /* ignore */ }
  cached = undefined;
  listeners.forEach((l) => l());
}

export function updateProfile(patch: Partial<AgentProfile>) {
  const p = read();
  if (p) saveProfile({ ...p, ...patch });
}

export function useProfile(): AgentProfile | null {
  return useSyncExternalStore(
    (cb) => { listeners.add(cb); return () => listeners.delete(cb); },
    read,
    () => null,
  );
}

/** Binding of the outbox key to this tenant and agent. */
export function bindingOf(p: Pick<AgentProfile, "tenantId" | "agentId">): string {
  return `${p.tenantId}:${p.agentId}`;
}
