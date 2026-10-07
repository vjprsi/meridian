import { expect, test, type Page, type Route } from "@playwright/test";

const TENANT = "0199a000-0000-7000-8000-00000000000a";
const ME = "0199b000-0000-7000-8000-000000000001";
/** Problem banners (Next adds its own empty role=alert route announcer). */
const ALERT = "[role=alert]:not(#__next-route-announcer__)";

const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });

const customer = { id: ME, customer_number: "C-000123", customer_type: "INDIVIDUAL", status: "ACTIVE", first_name: "Amina",
  last_name: "Otieno", display_name: "Amina Otieno", legal_entity_id: TENANT, us_person: false, kyc_tier: "TIER_2", row_version: 1 };
const account = (id: string, owner: string, nickname: string) => ({ id, customer_id: owner, nickname, account_type: "CURRENT",
  account_number: `00012345${id.slice(-4)}`, currency: "KES", status: "ACTIVE", product_id: TENANT, product_version_id: TENANT,
  legal_entity_id: TENANT, mandate_rule: "ANY_ONE", minimum_balance: "0" });

const notFound = { type: "about:blank", title: "Not Found", status: 404, code: "not_found",
  detail: "account 0199c000-0000-7000-8000-00000000bbb2 was not found" };

interface Seen { url: string; customer: string | null; tenant: string | null }

/**
 * Mocks the gateway: the customer channel (/v1/me/*) for one signed-in customer, plus the dev-mode customer picker.
 * The channel decides who "me" is, so the mock answers 404 for any other account, like the server does.
 */
async function mockGateway(page: Page, seen: Seen[]) {
  await page.route("**/v1/**", async (route: Route) => {
    const url = new URL(route.request().url());
    const h = route.request().headers();
    seen.push({ url: url.pathname + url.search, customer: h["x-customer-id"] ?? null, tenant: h["x-tenant-id"] ?? null });
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    const p = url.pathname;
    if (p === `/v1/customers/${ME}`) return json(customer);
    if (p === "/v1/customers/search") return json(page1([customer]));
    if (p === "/v1/me/profile") return json(customer);
    if (p === "/v1/me/accounts") return json(page1([account("0199c000-0000-7000-8000-00000000aaa1", ME, "Everyday")]));
    if (p === "/v1/me/accounts/0199c000-0000-7000-8000-00000000bbb2") {
      return route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify(notFound) });
    }
    if (p.endsWith("/balance")) return json({ id: "x", ledger_account_id: "x", currency: "KES", available_balance: "15250.50", posted_balance: "15300.00", pending_debits: "49.50" });
    if (p.startsWith("/v1/")) return json(page1([]));
    return route.continue();
  });
}

async function devSession(page: Page) {
  await page.addInitScript(([tenant, me]) => {
    sessionStorage.setItem("meridian.customer.session",
      JSON.stringify({ mode: "dev", tenantId: tenant, customerId: me, user: "customer:C-000123" }));
  }, [TENANT, ME]);
}

test("login page is labelled, keyboard reachable and switches to Kiswahili", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { level: 1, name: "Sign in to Meridian" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign in securely" })).toBeVisible();
  await expect(page.getByLabel("Tenant ID")).toHaveValue(TENANT);
  await page.getByLabel("Language").selectOption("sw");
  await expect(page.getByRole("heading", { level: 1, name: "Ingia Meridian" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "sw");
  await page.getByLabel("Mwonekano").selectOption("dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
});

test("dev login with the gateway down shows a problem message", async ({ page }) => {
  await page.route("**/v1/**", (route) => route.fulfill({ status: 503, contentType: "application/problem+json",
    body: JSON.stringify({ title: "Service unavailable", status: 503, code: "upstream_unavailable", detail: "customer-service is not reachable", request_id: "req-1" }) }));
  await page.goto("/login");
  await page.getByLabel("Customer", { exact: true }).fill("Amina");
  await page.getByRole("button", { name: "Find customer" }).click();
  const alert = page.locator(ALERT);
  await expect(alert).toContainText("customer-service is not reachable");
  await expect(alert).toContainText("upstream_unavailable");
});

test("dev login picks a customer and every signed-in call goes to the customer channel as them", async ({ page }) => {
  const seen: Seen[] = [];
  await mockGateway(page, seen);
  await page.goto("/login");
  await page.getByLabel("Customer", { exact: true }).fill("Amina");
  await page.getByRole("button", { name: "Find customer" }).click();
  await page.getByRole("button", { name: /Amina Otieno/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Hello, Amina" })).toBeVisible();
  await expect(page.getByRole("link", { name: /Everyday/ })).toBeVisible();
  for (const prefix of ["/v1/me/accounts", "/v1/me/payments", "/v1/me/cards", "/v1/me/loans", "/v1/me/wallets"]) {
    expect(seen.find((s) => s.url.startsWith(prefix)), `${prefix} called`).toBeTruthy();
  }
  // after sign-in only the channel is called, as the picked customer (dev: X-Customer-Id), and the app never names
  // a customer in a query or path itself
  const signedIn = seen.filter((s) => !s.url.startsWith("/v1/customers"));
  expect(signedIn.length).toBeGreaterThan(5);
  for (const s of signedIn) {
    expect(s.url, s.url).toMatch(/^\/v1\/me\//);
    expect(s.url).not.toContain("customer_id");
    expect(s.customer).toBe(ME);
    expect(s.tenant).toBe(TENANT);
  }
});

test("app shell: skip link, landmarks, no horizontal scroll on a phone", async ({ page }) => {
  await mockGateway(page, []);
  await devSession(page);
  await page.goto("/");
  await expect(page.getByRole("main")).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to main content" })).toBeFocused();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await expect(page.getByRole("navigation", { name: "Main" }).last()).toBeVisible();
});

test("an account outside the customer's profile is not shown", async ({ page }) => {
  await mockGateway(page, []);
  await devSession(page);
  await page.goto("/accounts/0199c000-0000-7000-8000-00000000bbb2");
  await expect(page.locator(ALERT)).toContainText("not available in your profile");
});

test("transfer form validates before any money moves", async ({ page }) => {
  const seen: Seen[] = [];
  await mockGateway(page, seen);
  await devSession(page);
  await page.goto("/move/transfer");
  // Only one own account in the mock: the screen explains why a transfer is not possible.
  await expect(page.getByText("You need two active accounts")).toBeVisible();
  expect(seen.some((s) => s.url.startsWith("/v1/me/payments/validate"))).toBe(false);
});

test("PWA: manifest and app-shell service worker", async ({ request }) => {
  const manifest = await request.get("/manifest.webmanifest");
  expect(manifest.ok()).toBe(true);
  expect((await manifest.json()).name).toBe("Meridian Bank");
  const sw = await request.get("/sw.js");
  expect(sw.headers()["cache-control"]).toContain("no-store");
  const body = await sw.text();
  expect(body).toContain('url.pathname.startsWith("/v1/")');
});
