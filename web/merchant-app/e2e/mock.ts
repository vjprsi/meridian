import { expect, type Page, type Route } from "@playwright/test";

/**
 * A stateful in-test mock of the merchant channel (/v1/merchant/*) for one merchant with two stores of its own plus a
 * store of another merchant. The signed-in user comes from the dev-mode X-Merchant-User-Id header, like the server
 * takes it from the token; roles, store scope (404 for a sale of a store outside the user's scope or of another
 * merchant), four-eyes and role denials behave like merchant-channel-service.
 */
export const TENANT = "0199a000-0000-7000-8000-00000000000a";
export const MERCHANT = "0199a000-0000-7000-8000-0000000d0001";
export const STORE_A = "0199b000-0000-7000-8000-00000000a001";
export const STORE_B = "0199b000-0000-7000-8000-00000000a002";
/** A store of another merchant: never returned by /me, its sales answer 404. */
export const FOREIGN_STORE = "0199b000-0000-7000-8000-00000000f001";
export const FOREIGN_SALE = "0199e000-0000-7000-8000-00000000f5a1";
export const TXN = "0199c000-0000-7000-8000-000000000101";

type Role = "OWNER" | "MANAGER" | "CASHIER" | "VIEWER";
export interface MockUser { id: string; name: string; role: Role; stores: string[] }

export const USERS = {
  owner: { id: "0199a000-0000-7000-8000-0000000d0a01", name: "Olive Owner", role: "OWNER", stores: [STORE_A, STORE_B] },
  manager: { id: "0199a000-0000-7000-8000-0000000d0a03", name: "Max Manager", role: "MANAGER", stores: [STORE_A] },
  cashier: { id: "0199a000-0000-7000-8000-0000000d0a02", name: "Cate Cashier", role: "CASHIER", stores: [STORE_A] },
} satisfies Record<string, MockUser>;

const ALL: MockUser[] = Object.values(USERS);
const now = () => new Date().toISOString();
const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });

export interface WriteCall { method: string; path: string; user: string; key: string | null; status: number }

export interface MockState {
  sales: Record<string, unknown>[];
  refunds: Record<string, unknown>[];
  calls: WriteCall[];
  /** Polls of each sale; a sale turns PAID on the third read. */
  polls: Record<string, number>;
}

function store(id: string, name: string, code: string, merchant = MERCHANT) {
  return { id, merchant_id: merchant, store_code: code, name, city: "Nairobi", country: "KE", status: "ACTIVE",
    created_at: now(), updated_at: now(), row_version: 1 };
}
const STORES = [store(STORE_A, "Moi Avenue Shop", "S-001"), store(STORE_B, "Westlands Kiosk", "S-002")];

function userJson(u: MockUser) {
  return { id: u.id, merchant_id: MERCHANT, display_name: u.name, email: `${u.name.split(" ")[0].toLowerCase()}@duka.test`,
    role: u.role, all_stores: u.role === "OWNER", status: "ACTIVE", created_at: now(), updated_at: now(), row_version: 1 };
}

function problem(route: Route, status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  return route.fulfill({ status, contentType: "application/problem+json", headers,
    body: JSON.stringify({ type: "about:blank", title: code, status, code, detail }) });
}

export async function mockMerchant(page: Page): Promise<MockState> {
  const state: MockState = {
    sales: [{ id: FOREIGN_SALE, merchant_id: "0199a000-0000-7000-8000-0000000d0999", merchant_store_id: FOREIGN_STORE,
      cashier_user_id: "x", amount: "10.00", currency: "KES", status: "PENDING", created_at: now(), updated_at: now(), row_version: 1 }],
    refunds: [{ id: "0199f000-0000-7000-8000-000000000001", merchant_id: MERCHANT, merchant_store_id: STORE_A,
      original_transaction_id: TXN, amount: "40.00", currency: "KES", reason: "Damaged item", status: "PENDING_APPROVAL",
      requested_by: USERS.manager.id, created_at: now(), updated_at: now(), row_version: 1 }],
    calls: [], polls: {},
  };

  await page.route("**/v1/merchant/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const method = req.method();
    const h = req.headers();
    const user = ALL.find((u) => u.id === h["x-merchant-user-id"]);
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (!user || h["x-merchant-id"] !== MERCHANT || h["x-tenant-id"] !== TENANT) {
      return problem(route, 401, "unauthenticated", "No merchant user");
    }
    const record = (status: number) => {
      if (method !== "GET") state.calls.push({ method, path: p, user: user.id, key: h["idempotency-key"] ?? null, status });
    };
    const inScope = (storeId: unknown) => user.stores.includes(String(storeId));

    if (method === "GET") {
      if (p === "/v1/merchant/me") {
        return json({ user: userJson(user), merchant: { id: MERCHANT, merchant_code: "M-1001", trading_name: "Duka Bora",
          currency: "KES", status: "ACTIVE" }, stores: STORES.filter((s) => inScope(s.id)) });
      }
      if (p === "/v1/merchant/terminals") return json(page1([]));
      if (p === "/v1/merchant/sales") {
        return json(page1(state.sales.filter((s) => s.merchant_id === MERCHANT && inScope(s.merchant_store_id))));
      }
      const sale = /^\/v1\/merchant\/sales\/([^/]+)$/.exec(p);
      if (sale) {
        const s = state.sales.find((x) => x.id === sale[1]);
        if (!s || s.merchant_id !== MERCHANT || !inScope(s.merchant_store_id)) return problem(route, 404, "not_found", "sale not found");
        state.polls[s.id as string] = (state.polls[s.id as string] ?? 0) + 1;
        if (s.status === "PENDING" && state.polls[s.id as string] >= 3) {
          Object.assign(s, { status: "PAID", paid_at: now(), wallet_transaction_id: TXN });
        }
        return json(s);
      }
      if (p === "/v1/merchant/refunds") {
        const st = url.searchParams.get("status");
        return json(page1(state.refunds.filter((r) => inScope(r.merchant_store_id) && (!st || r.status === st))));
      }
      if (p === "/v1/merchant/staff") {
        if (user.role !== "OWNER" && user.role !== "MANAGER") return problem(route, 403, "role_not_allowed", "Staff is for owners and managers");
        return json(page1(ALL.map(userJson)));
      }
      return json(page1([]));
    }

    if (p === "/v1/merchant/sales" && method === "POST") {
      const body = req.postDataJSON() as Record<string, string>;
      if (!inScope(body.store_id)) {
        record(404);
        return problem(route, 404, "not_found", "store not found");
      }
      const id = `0199e000-0000-7000-8000-${String(state.sales.length + 1).padStart(12, "0")}`;
      const s = { id, merchant_id: MERCHANT, merchant_store_id: body.store_id, merchant_terminal_id: body.terminal_id ?? null,
        cashier_user_id: user.id, amount: body.amount, currency: "KES", reference: body.reference ?? null,
        merchant_qr_id: "0199d000-0000-7000-8000-000000000001",
        qr_payload: "00020101021226330010io.meridian0115M-1001520458125303404540512.505802KE5909Duka Bora6007Nairobi6304ABCD",
        status: "PENDING", expires_at: new Date(Date.now() + 15 * 60_000).toISOString(), created_at: now(), updated_at: now(),
        row_version: 1 };
      state.sales.unshift(s);
      record(200);
      return json(s);
    }

    const refund = /^\/v1\/merchant\/refunds\/([^/]+)\/(approve|reject|cancel)$/.exec(p);
    if (refund && method === "POST") {
      const r = state.refunds.find((x) => x.id === refund[1]);
      if (!r || !inScope(r.merchant_store_id)) {
        record(404);
        return problem(route, 404, "not_found", "refund not found");
      }
      if (refund[2] !== "cancel" && user.role !== "OWNER" && user.role !== "MANAGER") {
        record(403);
        return problem(route, 403, "role_not_allowed", "Only an owner or a manager may decide refunds");
      }
      if (refund[2] !== "cancel" && r.requested_by === user.id) {
        record(422);
        return problem(route, 422, "four_eyes_violation", "You cannot decide your own refund request");
      }
      Object.assign(r, { status: refund[2] === "approve" ? "COMPLETED" : refund[2] === "reject" ? "REJECTED" : "CANCELLED",
        decided_by: user.id, decided_at: now() });
      record(200);
      return json(r);
    }

    record(404);
    return problem(route, 404, "not_found", `No mock for ${method} ${p}`);
  });
  return state;
}

/** Signs in on the dev login form as one of the mock users and waits for the signed-in frame. */
export async function devLogin(page: Page, user: MockUser) {
  await page.goto("/login");
  await page.getByLabel("Tenant id").fill(TENANT);
  await page.getByLabel("Merchant id").fill(MERCHANT);
  await page.getByLabel("Merchant user id").fill(user.id);
  await page.getByLabel("Your name (X-User)").fill(user.name);
  await page.getByRole("button", { name: "Sign in (dev)" }).click();
  await expect(page.getByTestId("merchant-name")).toHaveText("Duka Bora");
}
