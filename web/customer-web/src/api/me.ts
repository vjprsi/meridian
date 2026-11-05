"use client";

import { useQuery } from "@tanstack/react-query";
import { useSession } from "@/components/providers";
import { ApiError, me, meRequest, unwrap } from "./client";
import type {
  AirtimeTopup, BillInquiry, BillPayment, Biller, DepositAccount, MoneyRequest, QrCode, QrDecoded,
  ReferenceValidation, SecurityOverview, WalletHandleType, WalletLookupResult, WalletQuote, WalletTransaction,
  WalletTxnResult,
} from "./types";

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
  walletTxns: (id: string) => ["wallet-txns", id] as const,
  moneyRequests: (c: string) => ["money-requests", c] as const,
  billers: (c: string) => ["billers", c] as const,
  airtime: (c: string) => ["airtime-topups", c] as const,
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

/** The customer's sign-in security: authentication strength, second factors, step-up policy. */
export function useSecurity(enabled = true) {
  const c = useCustomerId();
  return useQuery({
    queryKey: ["security", c], enabled: !!c && enabled,
    queryFn: async () => unwrap(await me.GET("/v1/me/security")) as unknown as SecurityOverview,
  });
}

/** The customer's sign-in sessions (active first; is_current marks this one). */
export function useMySessions() {
  const c = useCustomerId();
  return useQuery({
    queryKey: ["sessions", c], enabled: !!c,
    queryFn: async () => unwrap(await me.GET("/v1/me/sessions", { params: { query: { limit: 50 } } })).data,
  });
}

/* ------------------------------------------------------------------------------------------------------------------
 * Consumer WALLET feature (mobile money). Queries below; the write functions (money movement) are plain async calls
 * run from a step-up confirmation (see components/wallet-flow.tsx → MoneyConfirm), so a 401 step_up_required is
 * handled exactly like the bank payment flow. Every write passes an Idempotency-Key kept for the whole confirmation.
 * These endpoints are typed by hand in api/types.ts until `npm run gen:api` adds them to the generated client.
 * ---------------------------------------------------------------------------------------------------------------- */

/**
 * Resolves the wallet a sub-page acts on: the one named in the URL (`?wallet=`), else the customer's default, else
 * the first usable wallet. `wallets` is the pickable list (ACTIVE only, or all when none are active yet).
 */
export function useWalletContext(preferredId?: string) {
  const q = useMyWallets();
  const all = q.data ?? [];
  const active = all.filter((w) => w.status === "ACTIVE");
  const wallets = active.length ? active : all;
  const wallet = wallets.find((w) => w.id === preferredId) ?? wallets.find((w) => w.is_default) ?? wallets[0];
  return { isPending: q.isPending, error: q.error, wallets, wallet };
}

/** One wallet's own balance (same AccountBalance shape as a deposit account); wraps the typed client. */
export function useWalletTransactions(walletId: string | undefined, limit = 20) {
  return useQuery({
    queryKey: [...qk.walletTxns(walletId ?? ""), limit], enabled: !!walletId,
    queryFn: async () => unwrap(await me.GET("/v1/me/wallets/{wallet_id}/transactions", {
      params: { path: { wallet_id: walletId! }, query: { limit } },
    })).data as WalletTransaction[],
  });
}

/** Money requests addressed to or raised by the customer (incoming to pay/decline, outgoing to cancel). */
export function useMoneyRequests() {
  const c = useCustomerId();
  return useQuery({
    queryKey: qk.moneyRequests(c), enabled: !!c,
    queryFn: async () => (await meRequest<{ data: MoneyRequest[] }>("GET", "/v1/me/money-requests", { query: { limit: 100 } })).data ?? [],
  });
}

/** Billers and airtime operators available to pay; pass a type to filter (e.g. AIRTIME). */
export function useBillers(billerType?: string) {
  const c = useCustomerId();
  return useQuery({
    queryKey: [...qk.billers(c), billerType ?? "all"], enabled: !!c,
    queryFn: async () => (await meRequest<{ data: Biller[] }>("GET", "/v1/me/billers", { query: { type: billerType, limit: 200 } })).data ?? [],
  });
}

/** One biller with its products (bundles/denominations). */
export function useBiller(id: string | undefined) {
  return useQuery({
    queryKey: ["biller", id], enabled: !!id,
    queryFn: () => mine(() => meRequest<Biller>("GET", `/v1/me/billers/${id}`)),
    retry: false,
  });
}

/** The customer's recent airtime top-ups. */
export function useAirtimeTopups(limit = 20) {
  const c = useCustomerId();
  return useQuery({
    queryKey: [...qk.airtime(c), limit], enabled: !!c,
    queryFn: async () => (await meRequest<{ data: AirtimeTopup[] }>("GET", "/v1/me/airtime-topups", { query: { limit } })).data ?? [],
  });
}

/** POST /v1/me/wallets/lookup — resolve a payee wallet by number, alias or phone number (no step-up). */
export function walletLookup(type: WalletHandleType, value: string) {
  return meRequest<WalletLookupResult>("POST", "/v1/me/wallets/lookup", { body: { type, value } });
}

export interface P2pInput { type: WalletHandleType; value: string; amount: string; note?: string }

/** POST /v1/me/wallets/{id}/p2p/quote — fees and resolved payee before confirmation (no step-up). */
export function walletP2pQuote(walletId: string, input: P2pInput) {
  return meRequest<WalletQuote>("POST", `/v1/me/wallets/${walletId}/p2p/quote`, { body: input });
}

/** POST /v1/me/wallets/{id}/p2p — send to another wallet (step-up). */
export function walletP2p(walletId: string, input: P2pInput & { quote_id?: string }, key: string) {
  return meRequest<WalletTxnResult>("POST", `/v1/me/wallets/${walletId}/p2p`, { body: input, key });
}

/** POST /v1/me/wallets/{id}/to-bank — move wallet funds to one of the customer's bank accounts (step-up). */
export function walletToBank(walletId: string, input: { account_id: string; amount: string; note?: string }, key: string) {
  return meRequest<WalletTxnResult>("POST", `/v1/me/wallets/${walletId}/to-bank`, { body: input, key });
}

/** POST /v1/me/wallets/{id}/from-bank — top the wallet up from one of the customer's bank accounts (step-up). */
export function walletFromBank(walletId: string, input: { account_id: string; amount: string; note?: string }, key: string) {
  return meRequest<WalletTxnResult>("POST", `/v1/me/wallets/${walletId}/from-bank`, { body: input, key });
}

export interface MoneyRequestInput { wallet_id: string; type: WalletHandleType; value: string; amount: string; note?: string }

/** POST /v1/me/money-requests — ask another wallet to pay you (step-up to protect the collect flow). */
export function createMoneyRequest(input: MoneyRequestInput, key: string) {
  return meRequest<MoneyRequest>("POST", "/v1/me/money-requests", { body: input, key });
}

/** POST /v1/me/money-requests/{id}/pay — pay an incoming request from a wallet (step-up). */
export function payMoneyRequest(id: string, walletId: string, key: string) {
  return meRequest<WalletTxnResult>("POST", `/v1/me/money-requests/${id}/pay`, { body: { wallet_id: walletId }, key });
}

/** POST /v1/me/money-requests/{id}/decline — decline an incoming request. */
export function declineMoneyRequest(id: string, key: string) {
  return meRequest<MoneyRequest>("POST", `/v1/me/money-requests/${id}/decline`, { key });
}

/** POST /v1/me/money-requests/{id}/cancel — cancel an outgoing request. */
export function cancelMoneyRequest(id: string, key: string) {
  return meRequest<MoneyRequest>("POST", `/v1/me/money-requests/${id}/cancel`, { key });
}

/** POST /v1/me/billers/{id}/validate-reference — check an account/meter number (no step-up). */
export function validateBillerReference(billerId: string, reference: string) {
  return meRequest<ReferenceValidation>("POST", `/v1/me/billers/${billerId}/validate-reference`, { body: { reference } });
}

export interface AirtimeInput { wallet_id: string; biller_id: string; biller_product_id?: string; msisdn: string; amount?: string }

/** POST /v1/me/airtime-topups — buy airtime or a bundle (step-up). */
export function createAirtimeTopup(input: AirtimeInput, key: string) {
  return meRequest<AirtimeTopup>("POST", "/v1/me/airtime-topups", { body: input, key });
}

/** POST /v1/me/bill-payments/inquire — look up a bill for a biller + reference (no step-up). */
export function billInquire(input: { wallet_id?: string; biller_id: string; reference: string }) {
  return meRequest<BillInquiry>("POST", "/v1/me/bill-payments/inquire", { body: input });
}

export interface BillPaymentInput { wallet_id: string; biller_id: string; reference: string; amount: string; bill_inquiry_id?: string }

/** POST /v1/me/bill-payments — pay a bill (step-up). */
export function createBillPayment(input: BillPaymentInput, key: string) {
  return meRequest<BillPayment>("POST", "/v1/me/bill-payments", { body: input, key });
}

/** POST /v1/me/qr/decode — resolve a scanned/pasted QR payload (no step-up). */
export function qrDecode(payload: string) {
  return meRequest<QrDecoded>("POST", "/v1/me/qr/decode", { body: { payload } });
}

/** POST /v1/me/qr/pay — pay a decoded QR from a wallet (step-up). */
export function qrPay(input: { wallet_id: string; payload: string; amount?: string }, key: string) {
  return meRequest<WalletTxnResult>("POST", "/v1/me/qr/pay", { body: input, key });
}

/** POST /v1/me/qr/generate — a receive-money QR for one of the customer's wallets. */
export function qrGenerate(input: { wallet_id: string; amount?: string; reference?: string }, key: string) {
  return meRequest<QrCode>("POST", "/v1/me/qr/generate", { body: input, key });
}

/** POST /v1/me/merchant-payments — pay a merchant by till number (step-up). */
export function createMerchantPayment(input: { wallet_id: string; merchant_till: string; amount: string; reference?: string }, key: string) {
  return meRequest<WalletTxnResult>("POST", "/v1/me/merchant-payments", { body: input, key });
}

/** POST /v1/me/cards/{id}/activate — activate a newly issued card (step-up). */
export function activateCard(cardId: string, key: string) {
  return meRequest<unknown>("POST", `/v1/me/cards/${cardId}/activate`, { key });
}
