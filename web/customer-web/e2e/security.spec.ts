import { expect, test, type Page, type Request } from "@playwright/test";

const TENANT = "0199a000-0000-7000-8000-00000000000a";
const ME = "0199b000-0000-7000-8000-000000000001";
const CARD = "0199c000-0000-7000-8000-0000000cc001";
const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });

const session = (id: string, sid: string, label: string, current: boolean, extra: object = {}) => ({
  id, customer_id: ME, session_id: sid, device_label: label, ip_address: "203.0.113.7", last_ip_address: "203.0.113.7",
  first_seen_at: "2026-10-07T08:00:00Z", last_seen_at: "2026-10-07T09:00:00Z", new_device: false, status: "ACTIVE",
  idp_session_ended: false, is_current: current, row_version: 1, created_at: "2026-10-07T08:00:00Z", updated_at: "2026-10-07T09:00:00Z",
  ...extra,
});

const security = {
  authentication: { acr: null, level: 0, amr: [], auth_time: null, session_id: "dev-s1" },
  factors_available: false, factors: [], has_second_factor: null,
  step_up: { acr: "2", max_age_seconds: 300, operations: [] },
  enrolment: { authenticator_app: "CONFIGURE_TOTP", security_key: "webauthn-register", passkey: "webauthn-register-passwordless",
    remove: "delete_credential:{id}" },
};

async function devSession(page: Page) {
  await page.addInitScript(([tenant, me]) => {
    sessionStorage.setItem("meridian.customer.session",
      JSON.stringify({ mode: "dev", tenantId: tenant, customerId: me, user: "customer:C-000123", sid: "dev-s1" }));
  }, [TENANT, ME]);
}

test("security page lists sessions and signs out another session and the other devices", async ({ page }) => {
  const writes: { path: string; body: unknown; headers: Record<string, string> }[] = [];
  const gets: Request[] = [];
  await devSession(page);
  await page.route("**/v1/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (req.method() === "GET") gets.push(req);
    else writes.push({ path: p, body: req.postDataJSON(), headers: req.headers() });
    if (p === "/v1/me/security") return json(security);
    if (p === "/v1/me/sessions") return json(page1([
      session("0199d000-0000-7000-8000-000000000001", "dev-s1", "Chrome on Windows", true),
      session("0199d000-0000-7000-8000-000000000002", "s2", "Chrome on Android", false, { new_device: true }),
    ]));
    if (p.endsWith("/revoke")) return json(session("0199d000-0000-7000-8000-000000000002", "s2", "Chrome on Android", false, { status: "REVOKED" }));
    if (p === "/v1/me/sessions/revoke-all") return json(page1([]));
    return json(page1([]));
  });

  await page.goto("/security");
  await expect(page.getByRole("heading", { level: 1, name: "Security" })).toBeVisible();
  const sessions = page.getByRole("list", { name: "Where you are signed in" });
  await expect(sessions.getByRole("listitem")).toHaveCount(2);
  await expect(sessions.getByRole("listitem").first()).toContainText("This device");
  await expect(sessions.getByRole("listitem").nth(1)).toContainText("New device");
  // factors are managed at the identity provider: not available in a dev sign-in
  await expect(page.getByText("Development sign-in: second factors")).toBeVisible();
  await expect(page.getByRole("button", { name: "Add a passkey" })).toBeDisabled();

  // only other sessions have a sign-out button
  await expect(sessions.getByRole("button", { name: "Sign out" })).toHaveCount(1);
  await sessions.getByRole("button", { name: "Sign out" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Sign out" }).click();
  await expect.poll(() => writes.map((w) => w.path)).toContain("/v1/me/sessions/0199d000-0000-7000-8000-000000000002/revoke");

  await page.getByRole("button", { name: "Sign out everywhere" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Other devices only" }).click();
  await expect.poll(() => writes.find((w) => w.path === "/v1/me/sessions/revoke-all")?.body).toEqual({ include_current: false });

  // every call names this browser (new-device alerts) and the dev session; writes are idempotent
  for (const w of writes) {
    expect(w.headers["x-session-id"]).toBe("dev-s1");
    expect(w.headers["x-device-id"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(w.headers["idempotency-key"]).toBeTruthy();
  }
  expect(gets.every((g) => g.headers()["x-customer-id"] === ME)).toBe(true);
});

test("step-up: the confirmation carries acr 2 and a step_up_required challenge is answered by confirming again", async ({ page }) => {
  const unfreezes: Record<string, string>[] = [];
  await devSession(page);
  await page.route("**/v1/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
      route.fulfill({ status, contentType: "application/json", headers, body: JSON.stringify(body) });
    if (p === `/v1/me/cards/${CARD}`) return json({ id: CARD, customer_id: ME, status: "FROZEN", last4: "4242", card_type: "DEBIT", scheme: "VISA",
      form_factor: "PHYSICAL", cardholder_name: "AMINA OTIENO", expiry_month: 9, expiry_year: 2029,
      linked_account_type: "DEPOSIT_ACCOUNT", pin_set: true });
    if (p === "/v1/me/security") return json(security);
    if (p === `/v1/me/cards/${CARD}/unfreeze`) {
      unfreezes.push(req.headers());
      if (unfreezes.length === 1) {
        return json({ title: "Unauthorized", status: 401, code: "step_up_required",
          detail: "A recent authentication (within 300 s) is required" }, 401,
          { "WWW-Authenticate": 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=300' });
      }
      return json({ id: CARD, customer_id: ME, status: "ACTIVE", last4: "4242", form_factor: "PHYSICAL",
        cardholder_name: "AMINA OTIENO", linked_account_type: "DEPOSIT_ACCOUNT", pin_set: true });
    }
    return json(page1([]));
  });

  await page.goto(`/cards/${CARD}`);
  await page.getByRole("button", { name: "Unfreeze card" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Unfreeze card" }).click();
  await expect.poll(() => unfreezes.length).toBe(1);
  // the server's challenge does not show as an error: the next confirmation steps up again
  await expect(dialog.getByText("Dev mode: confirming records a fresh step-up")).toBeVisible();
  await dialog.getByRole("button", { name: "Unfreeze card" }).click();
  await expect.poll(() => unfreezes.length).toBe(2);
  for (const h of unfreezes) {
    expect(h["x-acr"]).toBe("2");
    expect(Number(h["x-auth-time"])).toBeGreaterThan(Date.now() / 1000 - 60);
  }
});

test("a session signed out elsewhere returns to the sign-in screen", async ({ page }) => {
  await devSession(page);
  await page.route("**/v1/**", (route) => route.fulfill({ status: 401, contentType: "application/problem+json",
    body: JSON.stringify({ title: "Unauthorized", status: 401, code: "session_revoked", detail: "This session was signed out; sign in again" }) }));
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);
});
