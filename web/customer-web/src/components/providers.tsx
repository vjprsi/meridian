"use client";

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { loadSession, saveSession, type Session } from "@/lib/auth";
import { I18nProvider } from "@/lib/i18n";
import { ThemeProvider } from "@/lib/theme";
import { ApiError, isSessionRevoked } from "@/api/client";

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
  const [session, setSession] = useState<Session | null>(null);
  const [client] = useState(() => {
    // A session signed out elsewhere ("sign out everywhere", or revoked from another device): drop it locally; the
    // shell then sends the customer to the sign-in screen.
    const onError = (e: unknown) => {
      if (isSessionRevoked(e)) {
        saveSession(null);
        setSession(null);
      }
    };
    return new QueryClient({
      queryCache: new QueryCache({ onError }),
      mutationCache: new MutationCache({ onError }),
      // gcTime keeps account data in memory only briefly after a screen is left; nothing is persisted.
      defaultOptions: { queries: { retry, staleTime: 15_000, gcTime: 5 * 60_000, refetchOnWindowFocus: true } },
    });
  });
  const [ready, setReady] = useState(false);
  useEffect(() => {
    // Session lives in sessionStorage, which only exists in the browser: read it once after mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSession(loadSession());
    setReady(true);
  }, []);
  return (
    <QueryClientProvider client={client}>
      <I18nProvider>
        <ThemeProvider>
          <SessionContext.Provider value={{ session, ready, setSession }}>{children}</SessionContext.Provider>
        </ThemeProvider>
      </I18nProvider>
    </QueryClientProvider>
  );
}
