"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/providers";
import { useSelectedStore } from "@/lib/store";
import { api, unwrap } from "./client";
import type { MerchantMe, MerchantRole } from "./types";

/**
 * Data of the signed-in merchant user, all from the merchant channel (/v1/merchant/*). The server decides who the
 * merchant and the user are (token claims; dev mode headers) and enforces roles and store scope; the helpers here
 * only hide actions the user cannot perform. A 403 / 404 is still shown when it happens.
 */
export function useMe() {
  const { session } = useSession();
  const uid = session?.merchantUserId ?? "";
  return useQuery({
    queryKey: ["me", uid], enabled: !!uid, staleTime: 60_000,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/me")) as unknown as MerchantMe,
  });
}

export interface Permissions {
  role?: MerchantRole;
  userId?: string;
  isOwner: boolean;
  /** OWNER or MANAGER: approve refunds, run payment links, see settlements. */
  isManager: boolean;
  /** May start sales and request refunds (OWNER, MANAGER, CASHIER). */
  canSell: boolean;
  /** May see merchant-level money (settlements, fees, links): everyone but cashiers. */
  canSeeBooks: boolean;
  /** Read-only user. */
  readOnly: boolean;
}

export function permissions(me: MerchantMe | undefined): Permissions {
  const role = me?.user?.role;
  return {
    role, userId: me?.user?.id,
    isOwner: role === "OWNER",
    isManager: role === "OWNER" || role === "MANAGER",
    canSell: role === "OWNER" || role === "MANAGER" || role === "CASHIER",
    canSeeBooks: role === "OWNER" || role === "MANAGER" || role === "VIEWER",
    readOnly: role === "VIEWER",
  };
}

export function usePermissions(): Permissions & { loaded: boolean } {
  const me = useMe();
  return { ...permissions(me.data), loaded: me.isSuccess };
}

/** The stores the user may work in (from /me) and the selected one (only if it is among them). */
export function useStores() {
  const me = useMe();
  const selected = useSelectedStore();
  const stores = me.data?.stores ?? [];
  const store = stores.find((s) => s.id === selected);
  return { stores, store, selectedId: store?.id ?? "", loaded: me.isSuccess };
}

export function useTerminals(storeId: string) {
  return useQuery({
    queryKey: ["terminals", storeId], enabled: !!storeId,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/terminals",
      { params: { query: { store_id: storeId, limit: 200 } } })).data,
  });
}

export function useStaff(enabled = true) {
  return useQuery({
    queryKey: ["staff"], enabled,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/staff", { params: { query: { limit: 200 } } })).data,
  });
}

/** Today as YYYY-MM-DD (local). */
export function today(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** Start of a local day as an ISO timestamp (for from/to filters). */
export function dayStart(date: string): string | undefined {
  return date ? new Date(`${date}T00:00:00`).toISOString() : undefined;
}

export function dayEnd(date: string): string | undefined {
  return date ? new Date(`${date}T23:59:59.999`).toISOString() : undefined;
}
