"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { loadSession, type Session } from "@/lib/auth";

const SessionContext = createContext<{ session: Session | null; setSession: (s: Session | null) => void }>({
  session: null,
  setSession: () => {},
});

export function useSession() {
  return useContext(SessionContext);
}

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: 1, staleTime: 10_000, refetchOnWindowFocus: false } },
  }));
  const [session, setSession] = useState<Session | null>(null);
  useEffect(() => setSession(loadSession()), []);
  return (
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={{ session, setSession }}>{children}</SessionContext.Provider>
    </QueryClientProvider>
  );
}
