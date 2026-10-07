import { expect, test, type Page, type Route } from "@playwright/test";

const TENANT = "0199a000-0000-7000-8000-00000000000a";
const ME = "0199b000-0000-7000-8000-000000000001";
const OTHER = "0199b000-0000-7000-8000-000000000002";
/** Problem banners (Next adds its own empty role=alert route announcer). */
const ALERT = "[role=alert]:not(#__next-route-announcer__)";

const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });

const customer = { id: ME, customer_number: "C-000123", customer_type: "INDIVIDUAL", status: "ACTIVE", first_name: "Amina",
  last_name: "Otieno", display_name: "Amina Otieno", legal_entity_id: TENANT, us_person: false, kyc_tier: "TIER_2", row_version: 1 };
const account = (id: string, owner: string, nickname: string) => ({ id, customer_id: owner, nickname, account_type: "CURRENT",
  account_number: `00012345${id.slice(-4)}`, currency: "KES", status: "ACTIVE", product_id: TENANT, product_version_id: TENANT,
  legal_entity_id: TENANT, mandate_rule: "ANY_ONE", minimum_balance: "0" });

/** Mocks the gateway for one signed-in customer and records every API URL requested. */
async function mockGateway(page: Page, seen: string[]) {
  await page.route("**/v1/**", async (route: Route) => {
    const url = new URL(route.request().url());
    seen.push(url.pathname + url.search);
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    const p = url.pathname;
    if (p === `/v1/customers/${ME}`) return json(customer);
    if (p === "/v1/customers/search") return json(page1([customer]));
    if (p === "/v1/deposit-accounts") {
      // A misbehaving backend returning another customer's account must not leak into the UI.
      return json(page1([account("0199c000-0000-7000-8000-00000000aaa1", ME, "Everyday"),
        account("0199c000-0000-7000-8000-00000000bbb2", OTHER, "Someone else")]));
    }
    if (p.endsWith("/balance")) return json({ id: "x", ledger_account_id: "x", currency: "KES", available_balance: "15250.50", posted_balance: "15300.00", pending_debits: "49.50" });
    if (p === "/v1/payments") return json(page1([]));
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

test("dev login picks a customer and every call is scoped to them", async ({ page }) => {
  const seen: string[] = [];
  await mockGateway(page, seen);
  await page.goto("/login");
  await page.getByLabel("Customer", { exact: true }).fill("Amina");
  await page.getByRole("button", { name: "Find customer" }).click();
  await page.getByRole("button", { name: /Amina Otieno/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Hello, Amina" })).toBeVisible();
  await expect(page.getByRole("link", { name: /Everyday/ })).toBeVisible();
  await expect(page.getByText("Someone else")).toHaveCount(0);
  for (const prefix of ["/v1/deposit-accounts?", "/v1/payments?", "/v1/cards?", "/v1/loans?", "/v1/wallets?"]) {
    const call = seen.find((u) => u.startsWith(prefix));
    expect(call, `${prefix} called`).toBeTruthy();
    expect(call).toContain(`customer_id=${ME}`);
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
  const seen: string[] = [];
  await mockGateway(page, seen);
  await devSession(page);
  await page.goto("/move/transfer");
  // Only one own account in the mock: the screen explains why a transfer is not possible.
  await expect(page.getByText("You need two active accounts")).toBeVisible();
  expect(seen.some((u) => u.startsWith("/v1/payments/validate"))).toBe(false);
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
