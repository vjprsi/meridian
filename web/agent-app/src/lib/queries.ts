"use client";

import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "@/api/client";
import { updateProfile, type AgentProfile } from "@/lib/agent-context";
import { useConnectivity } from "@/lib/connectivity";
import { useSession } from "@/components/providers";

export function today(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function startOfToday(): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}

/** Live queries run only with a session and connectivity; offline screens fall back to last known values. */
export function useLive(): boolean {
  const { session } = useSession();
  return useConnectivity() === "online" && !!session;
}

/** Float wallet balance from the ledger; remembers the available amount for offline float checks. */
export function useFloatBalance(p: AgentProfile | null) {
  const live = useLive();
  return useQuery({
    queryKey: ["float-balance", p?.floatWalletId],
    enabled: live && !!p?.floatWalletId,
    queryFn: async () => {
      const b = unwrap(await api.GET("/v1/agent/me/float-balance"));
      updateProfile({ floatAvailable: b.available_balance, floatAt: new Date().toISOString() });
      return b;
    },
  });
}

/** Float positions of the agent (thresholds for the low-float alert). */
export function useFloatPositions(p: AgentProfile | null) {
  const live = useLive();
  return useQuery({
    queryKey: ["agent-float", p?.agentId],
    enabled: live && !!p,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me/floats")).data,
  });
}

/** The agent's own float wallet record (has_pin decides whether the agent PIN is asked). */
export function useFloatWallet(p: AgentProfile | null) {
  const live = useLive();
  return useQuery({
    queryKey: ["float-wallet", p?.floatWalletId],
    enabled: live && !!p?.floatWalletId,
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me/float-wallet")),
  });
}
