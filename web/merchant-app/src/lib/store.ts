"use client";

import { useSyncExternalStore } from "react";

/**
 * The store the cashier works in on this device (an id only; kept in localStorage so the app reopens on it). It is a
 * convenience: the server checks every sale, refund and listing against the user's store scope itself.
 */
const KEY = "meridian.merchant.store";
const listeners = new Set<() => void>();

function read(): string {
  try { return localStorage.getItem(KEY) ?? ""; } catch { return ""; }
}

export function setSelectedStore(id: string | null) {
  try {
    if (id) localStorage.setItem(KEY, id);
    else localStorage.removeItem(KEY);
  } catch { /* ignore */ }
  listeners.forEach((f) => f());
}

export function useSelectedStore(): string {
  return useSyncExternalStore((cb) => { listeners.add(cb); return () => listeners.delete(cb); }, read, () => "");
}
