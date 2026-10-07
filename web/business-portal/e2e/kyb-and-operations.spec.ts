import { expect, test, type Page } from "@playwright/test";
import { ACCOUNT, BUSINESS, USERS, devLogin, mockBusiness } from "./mock";

const now = () => new Date().toISOString();
const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });
const LOAN = "0199b000-0000-7000-8000-00000000a001";
const JOURNEY = "0199b000-0000-7000-8000-00000000b001";
const APP = "0199b000-0000-7000-8000-00000000c001";

/** Loans and the KYB channel endpoints, on top of the shared business mock (later routes take precedence). */
async function mockExtras(page: Page) {
  const calls: { method: string; path: string; body: unknown }[] = [];
  const steps = [
    { id: "0199b000-0000-7000-8000-00000000d001", onboarding_application_id: APP, step_code: "COMPANY", step_type: "BUSINESS_DETAILS",
      sequence: 1, status: "COMPLETED", attempts: 1, created_at: now(), updated_at: now(), row_version: 1 },
    { id: "0199b000-0000-7000-8000-00000000d002", onboarding_application_id: APP, step_code: "OWNERS", step_type: "UBO_DECLARATION",
      sequence: 2, status: "PENDING", attempts: 0, created_at: now(), updated_at: now(), row_version: 1 },
  ];
  const app = { id: APP, application_number: "APP-20261007-ABCD1234", onboarding_journey_id: JOURNEY, customer_id: BUSINESS,
    status: "IN_PROGRESS", channel: "WEB", progress_percent: 50, created_at: now(), updated_at: now(), row_version: 1 };
  await page.route("**/v1/business/loans**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    if (req.method() === "GET" && p === "/v1/business/loans") {
      return route.fulfill({ json: page1([{ id: LOAN, loan_number: "LN-0001", loan_type: "TERM", customer_id: BUSINESS,
        principal_amount: "500000.00", total_outstanding: "420000.00", currency: "KES", status: "ACTIVE", next_due_date: "2026-11-01",
        created_at: now(), updated_at: now(), row_version: 1 }]) });
    }
    if (req.method() === "POST" && p === `/v1/business/loans/${LOAN}/repay`) {
      calls.push({ method: "POST", path: p, body: req.postDataJSON() });
      // a LOAN_REPAYMENT rule covers it: held for approval (202) instead of executed
      return route.fulfill({ status: 202, json: { id: "0199e000-0000-7000-8000-0000000000f1", kind: "LOAN_REPAYMENT",
        status: "PENDING_APPROVAL", required_approvals: 2, approvals_received: 0, debtor_account_id: ACCOUNT, amount: "1500",
        currency: "KES", business_customer_id: BUSINESS, initiated_by: USERS.maker.id, created_at: now(), updated_at: now() } });
    }
    return route.fallback();
  });
  await page.route("**/v1/business/onboarding/**", async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    const m = req.method();
    if (m !== "GET") calls.push({ method: m, path: p, body: req.postDataJSON() });
    if (m === "GET" && p.endsWith("/journeys")) {
      return route.fulfill({ json: page1([{ id: JOURNEY, code: "KYB_KE", name: "Business account opening", status: "PUBLISHED",
        customer_type: "COMPANY", channels: ["WEB"], version: 1, target_tier: "TIER_3", expiry_days: 30, definition: {},
        created_at: now(), updated_at: now(), row_version: 1 }]) });
    }
    if (m === "GET" && p.endsWith("/applications")) return route.fulfill({ json: page1([app]) });
    if (m === "GET" && p.endsWith("/related-parties")) {
      return route.fulfill({ json: page1([{ id: "0199b000-0000-7000-8000-00000000e001", customer_id: BUSINESS,
        related_customer_id: "0199b000-0000-7000-8000-00000000e002", relationship_type: "DIRECTOR", position_title: "CEO",
        valid_from: "2020-01-01", verified: true, created_at: now(), updated_at: now(), row_version: 1 }]) });
    }
    if (m === "GET" && p.endsWith(`/applications/${APP}/steps`)) return route.fulfill({ json: page1(steps) });
    if (m === "GET" && p.endsWith(`/applications/${APP}/next-step`)) {
      return route.fulfill({ json: steps.find((s) => s.status !== "COMPLETED") ?? {} });
    }
    if (m === "GET" && p.endsWith(`/applications/${APP}/documents`)) return route.fulfill({ json: page1([]) });
    if (m === "POST" && p.endsWith(`/applications/${APP}/steps/OWNERS`)) {
      Object.assign(steps[1], { status: "COMPLETED", input: (req.postDataJSON() as { input: unknown }).input });
      return route.fulfill({ json: steps[1] });
    }
    if (m === "POST" && p.endsWith(`/applications/${APP}/submit`)) {
      Object.assign(app, { status: "SUBMITTED", progress_percent: 100 });
      return route.fulfill({ json: app });
    }
    return route.fulfill({ status: 404, contentType: "application/problem+json",
      body: JSON.stringify({ status: 404, code: "not_found", title: "not_found", detail: `No mock for ${m} ${p}` }) });
  });
  return calls;
}

test("a loan repayment covered by an approval rule is shown as sent for approval, not as executed", async ({ page }) => {
  await mockBusiness(page);
  const calls = await mockExtras(page);
  await devLogin(page, USERS.maker);
  await page.getByRole("link", { name: "Loans & credit lines" }).click();
  const row = page.getByRole("row", { name: /LN-0001/ });
  await row.getByRole("button", { name: "Repay" }).click();
  const dialog = page.getByRole("dialog", { name: "Repay LN-0001" });
  await dialog.getByLabel("From account").selectOption({ label: "Operating · 0012300001 (KES)" });
  await dialog.getByLabel(/Amount/).fill("1500");
  await dialog.getByRole("button", { name: "Repay" }).click();
  const held = page.getByTestId("held-for-approval");
  await expect(held).toContainText("Loan repayment sent for approval");
  await expect(held).toContainText("2 approvals");
  await expect(held.getByRole("link", { name: "View approval" })).toHaveAttribute("href", "/payments/0199e000-0000-7000-8000-0000000000f1");
  expect(calls.filter((c) => c.path.endsWith("/repay"))).toHaveLength(1);
});

test("an admin completes KYB through the business channel: owners declared, application submitted", async ({ page }) => {
  await mockBusiness(page);
  const calls = await mockExtras(page);
  await devLogin(page, USERS.admin);
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Business verification", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Business verification" })).toBeVisible();
  await expect(page.getByRole("row", { name: /CEO/ })).toBeVisible();
  await page.getByRole("row", { name: /APP-20261007-ABCD1234/ }).getByRole("button", { name: "Open" }).click();
  await expect(page.getByRole("heading", { name: "Next: OWNERS" })).toBeVisible();
  await page.getByLabel("Name #1").fill("Jane Founder");
  await page.getByLabel("Role #1").selectOption("ULTIMATE_BENEFICIAL_OWNER");
  await page.getByLabel(/Ownership % #1/).fill("60");
  await page.getByRole("button", { name: "Save declaration" }).click();
  await expect.poll(() => calls.filter((c) => c.path.endsWith("/steps/OWNERS")).length).toBe(1);
  const declared = calls.find((c) => c.path.endsWith("/steps/OWNERS"))!.body as { input: { ubos: { name: string }[] } };
  expect(declared.input.ubos[0].name).toBe("Jane Founder");

  await page.getByRole("button", { name: "Submit for review" }).click();
  await page.getByRole("dialog", { name: "Submit the application to the bank?" }).getByRole("button", { name: "Submit" }).click();
  await expect.poll(() => calls.filter((c) => c.path.endsWith("/submit")).length).toBe(1);
  // every KYB call went to the business channel, never to onboarding-service directly
  expect(calls.every((c) => c.path.startsWith("/v1/business/"))).toBe(true);
});

test("non-admin users do not see business verification", async ({ page }) => {
  await mockBusiness(page);
  await devLogin(page, USERS.maker);
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Business verification" })).toHaveCount(0);
});
