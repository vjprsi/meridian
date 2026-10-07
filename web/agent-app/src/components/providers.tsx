"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { SESSION_KEY, loadSession, oidc, saveSession, type Session } from "@/lib/auth";
import { useLang } from "@/lib/i18n";
import { config } from "@/lib/config";
import { lockApp, useAutoSync } from "@/lib/outbox/runtime";

const SessionContext = createContext<{ session: Session | null; ready: boolean; setSession: (s: Session | null) => void }>({
  session: null,
  ready: false,
  setSession: () => {},
});

const sessionListeners = new Set<() => void>();
let sessionRaw: string | null | undefined;
let sessionCached: Session | null = null;
const noop = () => () => {};

function notifySession() {
  sessionListeners.forEach((l) => l());
}

function subscribeSession(cb: () => void) {
  sessionListeners.add(cb);
  const onStorage = (e: StorageEvent) => { if (e.key === SESSION_KEY) cb(); };
  window.addEventListener("storage", onStorage);
  return () => { sessionListeners.delete(cb); window.removeEventListener("storage", onStorage); };
}

/** Stable snapshot of the stored session (re-parsed only when the stored value changes). */
function sessionSnapshot(): Session | null {
  let raw: string | null = null;
  try { raw = window.sessionStorage.getItem(SESSION_KEY); } catch { /* ignore */ }
  if (raw !== sessionRaw) {
    sessionRaw = raw;
    sessionCached = loadSession();
  }
  return sessionCached;
}

export function useSession() {
  return useContext(SessionContext);
}

/** Registers the service worker (production builds, or NEXT_PUBLIC_SW=1 in dev). */
function useServiceWorker() {
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    if (process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_SW !== "1") return;
    navigator.serviceWorker.register(`/sw.js?v=${process.env.NEXT_PUBLIC_BUILD_ID ?? "dev"}`, { scope: "/", updateViaCache: "none" }).catch(() => {});
  }, []);
}

/** Locks the app (forgets the outbox key and agent PIN) after a period without interaction. */
function useIdleLock() {
  useEffect(() => {
    let t: ReturnType<typeof setTimeout>;
    const reset = () => {
      clearTimeout(t);
      t = setTimeout(lockApp, config.idleLockMinutes * 60_000);
    };
    const events = ["pointerdown", "keydown", "touchstart"] as const;
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }));
    reset();
    return () => { clearTimeout(t); events.forEach((e) => window.removeEventListener(e, reset)); };
  }, []);
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      // Low bandwidth: no refetch on focus, short retry, keep data while offline.
      queries: { retry: 1, staleTime: 30_000, gcTime: 30 * 60_000, refetchOnWindowFocus: false, networkMode: "always" },
      mutations: { retry: 0, networkMode: "always" },
    },
  }));
  const session = useSyncExternalStore(subscribeSession, sessionSnapshot, () => null);
  const ready = useSyncExternalStore(noop, () => true, () => false);
  const setSession = useCallback((s: Session | null) => { saveSession(s); notifySession(); }, []);
  const lang = useLang();
  useEffect(() => {
    // SSO sessions: start the OIDC client so tokens renew silently while the app is open.
    if (loadSession()?.mode === "oidc") void oidc().then((m) => m.getUser()).catch(() => {});
  }, []);
  useEffect(() => { document.documentElement.lang = lang; }, [lang]);
  useServiceWorker();
  useIdleLock();
  useAutoSync();
  return (
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={{ session, ready, setSession }}>{children}</SessionContext.Provider>
    </QueryClientProvider>
  );
}
