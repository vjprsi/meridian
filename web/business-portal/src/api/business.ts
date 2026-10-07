"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/providers";
import { api, unwrap } from "./client";
import type { BusinessMe, BusinessRole, DepositAccount, MeEntitlement } from "./types";

/**
 * Data of the signed-in business user, all from the business channel (/v1/business/*). The server decides who the
 * business and the user are (token claims; dev mode headers) and enforces roles and entitlements; the helpers here only
 * hide actions the user cannot perform. A 403 (not_entitled / role_not_allowed) is still shown when it happens.
 */

export function useMe() {
  const { session } = useSession();
  const uid = session?.businessUserId ?? "";
  return useQuery({
    queryKey: ["me", uid], enabled: !!uid,
    queryFn: async () => unwrap(await api.GET("/v1/business/me")) as unknown as BusinessMe,
  });
}

export interface Permissions {
  role?: BusinessRole;
  userId?: string;
  isAdmin: boolean;
  /** May draft payments and other initiations (MAKER or ADMIN) on at least one account. */
  canInitiate: boolean;
  /** May approve payments (APPROVER or ADMIN). */
  canApprove: boolean;
  /** Maker-type changes that do not move money (payees, mandates, links, virtual accounts). */
  canMake: boolean;
  initiateOn: (accountId: string | null | undefined) => boolean;
  approveOn: (accountId: string | null | undefined) => boolean;
  entitlement: (accountId: string | null | undefined) => MeEntitlement | undefined;
}

export function permissions(me: BusinessMe | undefined): Permissions {
  const role = me?.user?.role;
  const ents = me?.entitlements ?? [];
  const isAdmin = role === "ADMIN";
  const maker = role === "ADMIN" || role === "MAKER";
  const approver = role === "ADMIN" || role === "APPROVER";
  const entitlement = (id: string | null | undefined) => ents.find((e) => e.deposit_account_id === id);
  return {
    role, userId: me?.user?.id, isAdmin,
    canInitiate: maker && (isAdmin || ents.some((e) => e.can_initiate)),
    canApprove: approver && (isAdmin || ents.some((e) => e.can_approve)),
    canMake: maker,
    initiateOn: (id) => maker && (isAdmin || !!entitlement(id)?.can_initiate),
    approveOn: (id) => approver && (isAdmin || !!entitlement(id)?.can_approve),
    entitlement,
  };
}

export function usePermissions(): Permissions & { loaded: boolean } {
  const me = useMe();
  return { ...permissions(me.data), loaded: me.isSuccess };
}

export function useAccounts() {
  return useQuery({
    queryKey: ["accounts"],
    queryFn: async () => unwrap(await api.GET("/v1/business/accounts", { params: { query: { limit: 200 } } })).data,
  });
}

export function accountLabel(a: Pick<DepositAccount, "nickname" | "account_number" | "currency"> | undefined) {
  if (!a) return "—";
  return `${a.nickname ? `${a.nickname} · ` : ""}${a.account_number} (${a.currency})`;
}

/** Options for an account picker; `filter` keeps e.g. only accounts the user may initiate on. */
export function accountOptions(accounts: DepositAccount[] | undefined, filter?: (a: DepositAccount) => boolean) {
  return (accounts ?? []).filter((a) => !filter || filter(a)).map((a) => ({ value: a.id, label: accountLabel(a) }));
}

export function usePayees() {
  return useQuery({
    queryKey: ["payees"],
    queryFn: async () => unwrap(await api.GET("/v1/business/payees", { params: { query: { limit: 200 } } })).data,
  });
}

export function useUsers(enabled = true) {
  return useQuery({
    queryKey: ["users"], enabled,
    queryFn: async () => unwrap(await api.GET("/v1/business/users", { params: { query: { limit: 200 } } })).data,
  });
}

export function useApprovalQueue(enabled = true) {
  return useQuery({
    queryKey: ["approval-queue"], enabled,
    queryFn: async () => unwrap(await api.GET("/v1/business/approval-queue", { params: { query: { limit: 200 } } })).data,
  });
}

/** Today as YYYY-MM-DD (local). */
export function today(offsetDays = 0): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
