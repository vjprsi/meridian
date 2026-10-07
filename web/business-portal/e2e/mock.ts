import { expect, type Page, type Route } from "@playwright/test";

/**
 * A stateful in-test mock of the business channel (/v1/business/*) for one business. The signed-in user comes from
 * the dev-mode X-Business-User-Id header, like the server takes it from the token; roles, entitlements, the approval
 * matrix (2 approvals), self-approval, step-up and entitlement denials behave like business-channel-service.
 */
export const TENANT = "0199a000-0000-7000-8000-00000000000a";
export const BUSINESS = "0199a000-0000-7000-8000-0000000b0001";
export const ACCOUNT = "0199c000-0000-7000-8000-0000000acc01";
export const PAYEE = "0199d000-0000-7000-8000-0000000aa001";

type Role = "ADMIN" | "MAKER" | "APPROVER" | "VIEWER";
export interface MockUser { id: string; name: string; role: Role; initiate: boolean; approve: boolean; deniedOnServer?: boolean }

export const USERS = {
  admin: { id: "0199a000-0000-7000-8000-0000000b0a01", name: "Ada Admin", role: "ADMIN", initiate: true, approve: true },
  maker: { id: "0199a000-0000-7000-8000-0000000b0a03", name: "Mo Maker", role: "MAKER", initiate: true, approve: false },
  approver1: { id: "0199a000-0000-7000-8000-0000000b0a02", name: "Amy Approver", role: "APPROVER", initiate: false, approve: true },
  approver2: { id: "0199a000-0000-7000-8000-0000000b0a04", name: "Ben Approver", role: "APPROVER", initiate: false, approve: true },
  viewer: { id: "0199a000-0000-7000-8000-0000000b0a05", name: "Vic Viewer", role: "VIEWER", initiate: false, approve: false },
  // The portal still shows him initiate rights, but the server has revoked them meanwhile: it answers 403 not_entitled.
  revoked: { id: "0199a000-0000-7000-8000-0000000b0a06", name: "Rex Revoked", role: "MAKER", initiate: true, approve: false, deniedOnServer: true },
} satisfies Record<string, MockUser>;

const ALL: MockUser[] = Object.values(USERS);
const now = () => new Date().toISOString();
const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });

export interface WriteCall { method: string; path: string; user: string; key: string | null; authTime: number | null; status: number }

export interface MockState {
  payments: Record<string, unknown>[];
  approvals: Record<string, unknown>[];
  calls: WriteCall[];
  /** Users that already passed the step-up challenge (the first approval of each user answers 401 step_up_required). */
  steppedUp: Set<string>;
}

const account = {
  id: ACCOUNT, account_number: "0012300001", iban: null, account_type: "CURRENT", nickname: "Operating", customer_id: BUSINESS,
  product_id: TENANT, product_version_id: TENANT, legal_entity_id: TENANT, currency: "KES", status: "ACTIVE", mandate_rule: "ANY_ONE",
  overdraft_limit: "0", minimum_balance: "0", created_at: now(), updated_at: now(), row_version: 1,
};
const payee = {
  id: PAYEE, customer_id: BUSINESS, name: "Acme Supplies Ltd", nickname: "Acme", beneficiary_type: "BUSINESS",
  account_id_type: "ACCOUNT_NUMBER", account_number: "0098765432", bank_name: "Other Bank", country: "KE", currency: "KES",
  status: "ACTIVE", trusted: false, created_at: now(), updated_at: now(), row_version: 1,
};

function userJson(u: MockUser) {
  return { id: u.id, business_customer_id: BUSINESS, display_name: u.name, email: `${u.name.split(" ")[0].toLowerCase()}@acme.test`,
    role: u.role, status: "ACTIVE", daily_limit: null, limit_currency: null, created_at: now(), updated_at: now(), row_version: 1 };
}

function problem(route: Route, status: number, code: string, detail: string, headers: Record<string, string> = {}) {
  return route.fulfill({ status, contentType: "application/problem+json", headers,
    body: JSON.stringify({ type: "about:blank", title: code, status, code, detail }) });
}

export async function mockBusiness(page: Page): Promise<MockState> {
  const state: MockState = { payments: [], approvals: [], calls: [], steppedUp: new Set() };

  await page.route("**/v1/business/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname;
    const method = req.method();
    const h = req.headers();
    const user = ALL.find((u) => u.id === h["x-business-user-id"]);
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (!user || h["x-business-customer-id"] !== BUSINESS || h["x-tenant-id"] !== TENANT) {
      return problem(route, 401, "unauthenticated", "No business user");
    }
    const record = (status: number) => {
      if (method !== "GET") state.calls.push({ method, path: p, user: user.id, key: h["idempotency-key"] ?? null,
        authTime: h["x-auth-time"] ? Number(h["x-auth-time"]) : null, status });
    };

    if (method === "GET") {
      if (p === "/v1/business/me") {
        return json({ user: userJson(user), business: { id: BUSINESS, display_name: "Acme Trading Ltd", customer_number: "B-0001" },
          entitlements: user.role === "ADMIN" ? [] : [{ deposit_account_id: ACCOUNT, can_view: true, can_initiate: user.initiate,
            can_approve: user.approve, max_amount: null }] });
      }
      if (p === "/v1/business/accounts") return json(page1([account]));
      if (p === `/v1/business/accounts/${ACCOUNT}`) return json(account);
      if (p === "/v1/business/cash-position") {
        return json({ accounts: [{ account_id: ACCOUNT, account_number: account.account_number, currency: "KES",
          available_balance: "1250000.00", posted_balance: "1250000.00" }],
          totals: [{ currency: "KES", available_balance: "1250000.00", posted_balance: "1250000.00", accounts: 1 }], pending_approval: [] });
      }
      if (p === "/v1/business/payees") return json(page1([payee]));
      if (p === "/v1/business/users") return json(page1(ALL.map(userJson)));
      if (p === "/v1/business/approval-queue") {
        return json(page1(state.payments.filter((x) => x.status === "PENDING_APPROVAL" && x.initiated_by !== user.id
          && user.approve && !state.approvals.some((a) => a.business_payment_id === x.id && a.business_user_id === user.id))));
      }
      if (p === "/v1/business/payments") return json(page1(state.payments));
      const m = /^\/v1\/business\/payments\/([^/]+)(\/approvals)?$/.exec(p);
      if (m) {
        const pay = state.payments.find((x) => x.id === m[1]);
        if (!pay) return problem(route, 404, "not_found", "payment not found");
        return m[2] ? json(page1(state.approvals.filter((a) => a.business_payment_id === pay.id))) : json(pay);
      }
      return json(page1([]));
    }

    if (p === "/v1/business/payments" && method === "POST") {
      if (!user.initiate || user.deniedOnServer) {
        record(403);
        return problem(route, 403, "not_entitled", "You may not initiate payments from this account");
      }
      const body = req.postDataJSON() as Record<string, string>;
      const pay = { id: `0199e000-0000-7000-8000-${String(state.payments.length + 1).padStart(12, "0")}`, created_at: now(),
        updated_at: now(), row_version: 1, business_customer_id: BUSINESS, kind: body.kind, status: "DRAFT",
        debtor_account_id: body.debtor_account_id, payee_id: body.payee_id ?? null, creditor_name: payee.name, amount: body.amount,
        currency: "KES", reference: body.reference ?? null, item_count: 1, invalid_count: 0, initiated_by: user.id,
        required_approvals: 0, approval_order: "ANY_ORDER", approvals_received: 0 };
      state.payments.unshift(pay);
      record(200);
      return json(pay);
    }

    const action = /^\/v1\/business\/payments\/([^/]+)\/(submit|approve|reject|cancel)$/.exec(p);
    if (action && method === "POST") {
      const pay = state.payments.find((x) => x.id === action[1]);
      if (!pay) return problem(route, 404, "not_found", "payment not found");
      if (action[2] === "submit") {
        Object.assign(pay, { status: "PENDING_APPROVAL", submitted_at: now(), required_approvals: 2, approval_order: "ANY_ORDER" });
        record(200);
        return json(pay);
      }
      if (action[2] === "approve") {
        if (pay.initiated_by === user.id) {
          record(403);
          return problem(route, 403, "self_approval_forbidden", "The initiator cannot approve their own payment");
        }
        if (!user.approve) {
          record(403);
          return problem(route, 403, "not_entitled", "You may not approve payments from this account");
        }
        if (!state.steppedUp.has(user.id)) {
          state.steppedUp.add(user.id);
          record(401);
          return problem(route, 401, "step_up_required", "Approving needs a recent sign-in",
            { "WWW-Authenticate": 'Bearer error="insufficient_user_authentication", max_age=300' });
        }
        if (state.approvals.some((a) => a.business_payment_id === pay.id && a.business_user_id === user.id)) {
          record(409);
          return problem(route, 409, "already_decided", "You already decided on this payment");
        }
        const received = Number(pay.approvals_received) + 1;
        state.approvals.push({ id: `0199f000-0000-7000-8000-${String(state.approvals.length + 1).padStart(12, "0")}`,
          created_at: now(), business_payment_id: pay.id, business_customer_id: BUSINESS, business_user_id: user.id,
          decision: "APPROVED", sequence: received, comment: (req.postDataJSON() as { comment?: string })?.comment ?? null, checksum: "x" });
        Object.assign(pay, { approvals_received: received, updated_at: now(),
          ...(received >= Number(pay.required_approvals) ? { status: "RELEASED", released_at: now(), released_by: user.id,
            payment_id: "0199a111-0000-7000-8000-000000000001", downstream_status: "SUBMITTED" } : {}) });
        record(200);
        return json(pay);
      }
    }
    record(404);
    return problem(route, 404, "not_found", `No mock for ${method} ${p}`);
  });
  return state;
}

/** Signs in on the dev login form as one of the mock users and waits for the dashboard. */
export async function devLogin(page: Page, user: MockUser) {
  await page.goto("/login");
  await page.getByLabel("Tenant id").fill(TENANT);
  await page.getByLabel("Business customer id").fill(BUSINESS);
  await page.getByLabel("Business user id").fill(user.id);
  await page.getByLabel("Your name (X-User)").fill(user.name);
  await page.getByRole("button", { name: "Sign in (dev)" }).click();
  await expect(page.getByRole("heading", { name: "Cash position" })).toBeVisible();
  await expect(page.getByTestId("current-user")).toContainText(user.name);
}

export async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
}
