import { expect, test } from "@playwright/test";
import { USERS, devLogin, mockBusiness } from "./mock";

const nav = (page: import("@playwright/test").Page) => page.getByRole("navigation", { name: "Main" });

test("admin-only navigation is shown to ADMIN users only", async ({ page }) => {
  await mockBusiness(page);
  await devLogin(page, USERS.admin);
  for (const name of ["Users", "Approval rules", "Integrations"]) {
    await expect(nav(page).getByRole("link", { name, exact: true })).toBeVisible();
  }
});

test("a VIEWER sees no admin navigation and no initiation actions, and admin pages refuse them", async ({ page }) => {
  await mockBusiness(page);
  await devLogin(page, USERS.viewer);
  await expect(nav(page).getByRole("link", { name: "Accounts", exact: true })).toBeVisible();
  await expect(nav(page).getByRole("link", { name: "Security log", exact: true })).toBeVisible();
  for (const name of ["Users", "Approval rules", "Integrations", "Approval queue", "Bulk & payroll"]) {
    await expect(nav(page).getByRole("link", { name, exact: true })).toHaveCount(0);
  }
  await nav(page).getByRole("link", { name: "Payments", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Payments" })).toBeVisible();
  await expect(page.getByRole("link", { name: "New payment" })).toHaveCount(0);

  await page.goto("/admin/users");
  await expect(page.getByText("You are not entitled to do this")).toBeVisible();
  await page.goto("/payments/new");
  await expect(page.getByText("You are not entitled to initiate payments")).toBeVisible();
});

test("a maker whose entitlement was revoked gets the server's not_entitled denial", async ({ page }) => {
  const state = await mockBusiness(page);
  await devLogin(page, USERS.revoked);
  await expect(nav(page).getByRole("link", { name: "Users", exact: true })).toHaveCount(0);
  await expect(nav(page).getByRole("link", { name: "Integrations", exact: true })).toHaveCount(0);

  await page.getByRole("link", { name: "New payment" }).first().click();
  await page.getByLabel(/From account/).selectOption({ label: "Operating · 0012300001 (KES)" });
  await page.getByLabel("Beneficiary").selectOption({ label: "Acme · 0098765432 (KES)" });
  await page.getByLabel(/^Amount/).fill("99.00");
  await page.getByRole("button", { name: "Create draft" }).click();

  const alert = page.locator("[role=alert][data-code=not_entitled]");
  await expect(alert).toBeVisible();
  await expect(alert).toContainText("You are not entitled to do this");
  await expect(alert).toContainText("You may not initiate payments from this account");
  await expect(page).toHaveURL(/\/payments\/new/);
  expect(state.calls.filter((c) => c.path === "/v1/business/payments").map((c) => c.status)).toEqual([403]);
});
