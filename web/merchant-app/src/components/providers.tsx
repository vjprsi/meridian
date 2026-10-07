"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { loadSession, oidc, saveSession, sessionFromOidc, type Session } from "@/lib/auth";
import { ThemeProvider } from "@/lib/theme";
import { ApiError } from "@/api/client";
import { StepUpProvider } from "./step-up";

const SessionContext = createContext<{ session: Session | null; ready: boolean; setSession: (s: Session | null) => void }>({
  session: null,
  ready: false,
  setSession: () => {},
});

export function useSession() {
  return useContext(SessionContext);
}

/** Retries transient failures once; never retries client errors (4xx carry a problem the user must act on). */
function retry(count: number, error: unknown) {
  if (error instanceof ApiError && (error.problem.status ?? 0) >= 400 && (error.problem.status ?? 0) < 500) return false;
  return count < 1;
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient({
    // gcTime keeps merchant data in memory only briefly after a screen is left; nothing is persisted.
    defaultOptions: { queries: { retry, staleTime: 10_000, gcTime: 5 * 60_000, refetchOnWindowFocus: true } },
  }));
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Session lives in sessionStorage, which only exists in the browser: read it once after mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSession(loadSession());
    setReady(true);
  }, []);
  useEffect(() => {
    if (session?.mode !== "oidc") return;
    // Silent renewal issues a new access token: keep the session's bearer token current (auth_time stays that of the
    // last interactive sign-in, so a renewal never counts as a step-up).
    const m = oidc();
    const onLoaded = (user: Parameters<Parameters<typeof m.events.addUserLoaded>[0]>[0]) => {
      const next = sessionFromOidc(user);
      if (next.merchantUserId !== session.merchantUserId) return;
      saveSession(next);
      setSession(next);
    };
    m.events.addUserLoaded(onLoaded);
    return () => m.events.removeUserLoaded(onLoaded);
  }, [session]);
  useEffect(() => {
    // Installable PWA: the worker caches the app shell only (static assets, icons, offline page), never /v1 data.
    if (!("serviceWorker" in navigator) || process.env.NODE_ENV !== "production") return;
    navigator.serviceWorker.register(`/sw.js?v=${process.env.NEXT_PUBLIC_BUILD_ID ?? "dev"}`,
      { scope: "/", updateViaCache: "none" }).catch(() => {});
  }, []);
  return (
    <QueryClientProvider client={client}>
      <ThemeProvider>
        <SessionContext.Provider value={{ session, ready, setSession }}>
          <StepUpProvider>{children}</StepUpProvider>
        </SessionContext.Provider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
