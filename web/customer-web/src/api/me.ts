"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/providers";
import { ApiError, cards, customers, deposits, loans, payments, unwrap, wallets } from "./client";
import type { Card, DepositAccount, Loan, Wallet } from "./types";

/**
 * Customer scoping. The platform has no "me" surface: every list is filtered here with customer_id = the signed-in
 * customer, and every fetch-by-id is checked against that customer before it is shown. This is a presentation
 * guard only; the services do not (yet) restrict a customer token to its own records (see README, "API gaps").
 */

export function useCustomerId(): string {
  const { session } = useSession();
  return session?.customerId ?? "";
}

/** Thrown when a record fetched by id does not belong to the signed-in customer. Rendered like a 404. */
export function notMine(): ApiError {
  return new ApiError({ status: 404, code: "not_found", title: "Not found",
    detail: "This item is not available in your profile." });
}

export function assertMine<T extends { customer_id?: string | null }>(row: T, customerId: string): T {
  if (!customerId || row.customer_id !== customerId) throw notMine();
  return row;
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
    queryFn: async () => unwrap(await customers.GET("/v1/customers/{id}", { params: { path: { id: c } } })),
  });
}

/**
 * Accounts the customer may see: accounts they own (customer_id) plus accounts where they are an active holder
 * with can_view (joint, signatory...). Closed and rejected accounts are hidden.
 */
export function useMyAccounts() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.accounts(c), enabled: !!c,
    queryFn: async (): Promise<(DepositAccount & { holder_role?: string; can_transact?: boolean })[]> => {
      const [owned, holdings] = await Promise.all([
        deposits.GET("/v1/deposit-accounts", { params: { query: { customer_id: c, limit: 100 } } }).then(unwrap),
        deposits.GET("/v1/account-holders", { params: { query: { customer_id: c, status: "ACTIVE", limit: 100 } } })
          .then(unwrap).catch(() => ({ data: [] as never[] })),
      ]);
      const list: (DepositAccount & { holder_role?: string; can_transact?: boolean })[] =
        owned.data.filter((a) => a.customer_id === c).map((a) => ({ ...a, holder_role: "PRIMARY", can_transact: true }));
      const have = new Set(list.map((a) => a.id));
      const extra = holdings.data.filter((h) => h.customer_id === c && h.can_view && !have.has(h.deposit_account_id));
      const fetched = await Promise.all(extra.map(async (h) => {
        const r = await deposits.GET("/v1/deposit-accounts/{id}", { params: { path: { id: h.deposit_account_id } } });
        return r.data ? { ...r.data, holder_role: h.role, can_transact: h.can_transact } : null;
      }));
      for (const a of fetched) if (a) list.push(a);
      return list.filter((a) => a.status !== "CLOSED" && a.status !== "REJECTED");
    },
  });
}

export function useAccountBalance(id: string | undefined) {
  return useQuery({
    queryKey: ["balance", id], enabled: !!id,
    queryFn: async () => unwrap(await deposits.GET("/v1/deposit-accounts/{id}/balance", { params: { path: { id: id! } } })),
  });
}

export function useMyWallets() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.wallets(c), enabled: !!c,
    queryFn: async (): Promise<Wallet[]> =>
      unwrap(await wallets.GET("/v1/wallets", { params: { query: { customer_id: c, wallet_type: "CUSTOMER", limit: 50 } } }))
        .data.filter((w) => w.customer_id === c && w.status !== "CLOSED"),
  });
}

export function useWalletBalance(id: string | undefined) {
  return useQuery({
    queryKey: ["wallet-balance", id], enabled: !!id,
    queryFn: async () => unwrap(await wallets.GET("/v1/wallets/{id}/balance", { params: { path: { id: id! } } })),
  });
}

export function useMyCards() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.cards(c), enabled: !!c,
    queryFn: async (): Promise<Card[]> =>
      unwrap(await cards.GET("/v1/cards", { params: { query: { customer_id: c, limit: 50 } } }))
        .data.filter((x) => x.customer_id === c && x.status !== "CLOSED" && x.status !== "REPLACED"),
  });
}

export function useMyLoans() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.loans(c), enabled: !!c,
    queryFn: async (): Promise<Loan[]> =>
      unwrap(await loans.GET("/v1/loans", { params: { query: { customer_id: c, limit: 50 } } }))
        .data.filter((l) => l.customer_id === c),
  });
}

export function useMyPayees() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.payees(c), enabled: !!c,
    queryFn: async () =>
      unwrap(await payments.GET("/v1/payees", { params: { query: { customer_id: c, limit: 100 } } }))
        .data.filter((p) => p.customer_id === c && p.status !== "DELETED"),
  });
}

export function useMyPayments(limit = 10) {
  const c = useCustomerId();
  return useQuery({
    queryKey: [...qk.payments(c), limit], enabled: !!c,
    queryFn: async () =>
      unwrap(await payments.GET("/v1/payments", { params: { query: { customer_id: c, limit } } }))
        .data.filter((p) => p.customer_id === c),
  });
}

/** One of the customer's accounts by id (from the scoped list: an id outside it is "not found"). */
export function useMyAccount(id: string) {
  const accounts = useMyAccounts();
  const account = accounts.data?.find((a) => a.id === id);
  const error = accounts.error ?? (accounts.data && !account ? notMine() : null);
  return { account, isPending: accounts.isPending, error };
}
