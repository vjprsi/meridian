"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/providers";
import { ApiError, me, unwrap } from "./client";
import type { DepositAccount } from "./types";

/**
 * Data of the signed-in customer, all from the customer channel (/v1/me/*). The server decides who "me" is (token
 * customer_id claim; dev mode X-Customer-Id) and answers 404 for anything that is not theirs, so the app never sends
 * a customer id and never filters other customers' rows itself.
 */

export function useCustomerId(): string {
  const { session } = useSession();
  return session?.customerId ?? "";
}

export function isNotFound(e: unknown): boolean {
  return e instanceof ApiError && e.problem.status === 404;
}

/** The friendly form of the server's 404 for an object outside the customer's profile (or a missing one). */
export function notMine(): ApiError {
  return new ApiError({ status: 404, code: "not_found", title: "Not found",
    detail: "This item is not available in your profile." });
}

/** Runs a fetch of one object; the server's 404 (not the customer's, or gone) becomes {@link notMine}. */
export async function mine<T>(fetch: () => Promise<T>): Promise<T> {
  try {
    return await fetch();
  } catch (e) {
    if (isNotFound(e)) throw notMine();
    throw e;
  }
}

export const qk = {
  me: (c: string) => ["me", c] as const,
  accounts: (c: string) => ["accounts", c] as const,
  wallets: (c: string) => ["wallets", c] as const,
  cards: (c: string) => ["cards", c] as const,
  loans: (c: string) => ["loans", c] as const,
  payees: (c: string) => ["payees", c] as const,
  payments: (c: string) => ["payments", c] as const,
};

export function useMe() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.me(c), enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/profile")),
  });
}

export type MyAccount = DepositAccount & { holder_role?: string; can_transact?: boolean };

/**
 * Accounts the customer owns or holds with view rights (the server leaves out closed and rejected ones), each with
 * the customer's role: PRIMARY for owned accounts, else the holder role and whether they may pay from it.
 */
export function useMyAccounts() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.accounts(c), enabled: !!c,
    queryFn: async (): Promise<MyAccount[]> => {
      const [accounts, holdings] = await Promise.all([
        me.GET("/v1/me/accounts").then(unwrap),
        me.GET("/v1/me/accounts/holdings").then(unwrap).catch(() => ({ data: [] as never[] })),
      ]);
      return accounts.data.map((a) => {
        const h = holdings.data.find((x) => x.deposit_account_id === a.id);
        return h && a.customer_id !== c
          ? { ...a, holder_role: h.role, can_transact: h.can_transact }
          : { ...a, holder_role: "PRIMARY", can_transact: true };
      });
    },
  });
}

export function useAccountBalance(id: string | undefined) {
  return useQuery({
    queryKey: ["balance", id], enabled: !!id,
    queryFn: async () => unwrap(await me.GET("/v1/me/accounts/{account_id}/balance", { params: { path: { account_id: id! } } })),
  });
}

export function useMyWallets() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.wallets(c), enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/wallets", { params: { query: { limit: 50 } } })).data,
  });
}

export function useWalletBalance(id: string | undefined) {
  return useQuery({
    queryKey: ["wallet-balance", id], enabled: !!id,
    queryFn: async () => unwrap(await me.GET("/v1/me/wallets/{wallet_id}/balance", { params: { path: { wallet_id: id! } } })),
  });
}

export function useMyCards() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.cards(c), enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/cards", { params: { query: { limit: 50 } } })).data,
  });
}

export function useMyLoans() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.loans(c), enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/loans", { params: { query: { limit: 50 } } })).data,
  });
}

export function useMyPayees() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.payees(c), enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/payees", { params: { query: { limit: 100 } } })).data,
  });
}

export function useMyPayments(limit = 10) {
  const c = useCustomerId();
  return useQuery({
    queryKey: [...qk.payments(c), limit], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/payments", { params: { query: { limit } } })).data,
  });
}

/** One of the customer's accounts by id; the server answers 404 for an account that is not theirs. */
export function useMyAccount(id: string) {
  const accounts = useMyAccounts();
  const listed = accounts.data?.find((a) => a.id === id);
  const single = useQuery({
    queryKey: ["account", id], enabled: !!id && accounts.isSuccess && !listed,
    queryFn: () => mine(async (): Promise<MyAccount> =>
      ({ ...unwrap(await me.GET("/v1/me/accounts/{account_id}", { params: { path: { account_id: id } } })), can_transact: false })),
    retry: false,
  });
  const account = listed ?? single.data;
  const error = accounts.error ?? single.error ?? null;
  return { account, isPending: accounts.isPending || (!listed && single.isPending && single.fetchStatus !== "idle"), error };
}
