import { expect, test, type Page } from "@playwright/test";
import { USERS, devLogin, mockBusiness, signOut, type MockState } from "./mock";

/** Approves the one payment in the approval queue, including the step-up the mock asks for on the first try. */
async function approveFromQueue(page: Page, state: MockState, userId: string) {
  await page.getByRole("link", { name: /Approval queue/ }).click();
  await expect(page.getByRole("heading", { name: "Approval queue" })).toBeVisible();
  const row = page.getByRole("row", { name: /Acme Supplies Ltd/ });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "Approve", exact: true }).click();

  const confirm = page.getByRole("dialog", { name: "Approve" });
  await confirm.getByLabel(/Comment/).fill("Looks right");
  await confirm.getByRole("button", { name: "Approve", exact: true }).click();

  // The server answers 401 step_up_required: the portal asks to confirm the identity, then retries.
  const stepUp = page.getByRole("dialog", { name: "Confirm it's you" });
  await expect(stepUp).toBeVisible();
  await stepUp.getByRole("button", { name: "Confirm and continue" }).click();
  await expect(stepUp).toBeHidden();
  await expect(confirm).toBeHidden();

  const approves = state.calls.filter((c) => c.user === userId && c.path.endsWith("/approve"));
  expect(approves.map((c) => c.status)).toEqual([401, 200]);
  expect(approves[0].key).toBeTruthy();
  expect(approves[1].key).toBe(approves[0].key);
  expect(approves[1].authTime ?? 0).toBeGreaterThanOrEqual(approves[0].authTime ?? 0);
}

test("a maker drafts and submits a domestic payment; two approvers release it with step-up", async ({ page }) => {
  const state = await mockBusiness(page);

  // Maker: draft and submit.
  await devLogin(page, USERS.maker);
  await expect(page.getByRole("link", { name: /Approval queue/ })).toHaveCount(0);
  await page.getByRole("link", { name: "New payment" }).first().click();
  await expect(page.getByRole("heading", { name: "New payment" })).toBeVisible();
  await page.getByLabel("Kind").selectOption("DOMESTIC");
  await page.getByLabel(/From account/).selectOption({ label: "Operating · 0012300001 (KES)" });
  await page.getByLabel("Beneficiary").selectOption({ label: "Acme · 0098765432 (KES)" });
  await page.getByLabel(/^Amount/).fill("2500.00");
  await page.getByLabel(/^Reference/).fill("INV-2026-118");
  await page.getByRole("button", { name: "Create draft" }).click();

  await expect(page.getByTestId("payment-status")).toHaveText("Draft");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await page.getByRole("dialog", { name: "Submit for approval" }).getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByTestId("payment-status")).toHaveText("Pending approval");
  await expect(page.getByTestId("approvals-progress")).toHaveText("0 / 2");
  // The initiator never gets an approve action on their own payment.
  await expect(page.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
  await expect(page.getByText("You initiated this payment")).toBeVisible();
  const paymentUrl = page.url();
  const create = state.calls.find((c) => c.path === "/v1/business/payments");
  expect(create?.key).toBeTruthy();
  await signOut(page);

  // Approver 1: approves after a step-up; the payment needs one more approval.
  await devLogin(page, USERS.approver1);
  await expect(page.getByTestId("queue-count")).toHaveText("1");
  await approveFromQueue(page, state, USERS.approver1.id);
  await expect(page.getByText("Nothing waiting for your approval")).toBeVisible();
  await page.goto(paymentUrl);
  await expect(page.getByTestId("payment-status")).toHaveText("Pending approval");
  await expect(page.getByTestId("approvals-progress")).toHaveText("1 / 2");
  await expect(page.getByRole("row", { name: /Amy Approver/ })).toBeVisible();
  await signOut(page);

  // Approver 2: the final approval releases the payment.
  await devLogin(page, USERS.approver2);
  await approveFromQueue(page, state, USERS.approver2.id);
  await page.goto(paymentUrl);
  await expect(page.getByTestId("payment-status")).toHaveText("Released");
  await expect(page.getByTestId("approvals-progress")).toHaveText("2 / 2");
  await signOut(page);
});

test("an ADMIN initiator, who may approve in general, gets no approve action on their own payment", async ({ page }) => {
  await mockBusiness(page);
  await devLogin(page, USERS.admin);
  await expect(page.getByRole("link", { name: /Approval queue/ })).toBeVisible();
  await page.getByRole("link", { name: "New payment" }).first().click();
  await page.getByLabel(/From account/).selectOption({ label: "Operating · 0012300001 (KES)" });
  await page.getByLabel("Beneficiary").selectOption({ label: "Acme · 0098765432 (KES)" });
  await page.getByLabel(/^Amount/).fill("10.00");
  await page.getByRole("button", { name: "Create draft" }).click();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await page.getByRole("dialog", { name: "Submit for approval" }).getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByTestId("payment-status")).toHaveText("Pending approval");
  await expect(page.getByText("You initiated this payment")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Cancel payment" })).toBeVisible();
});
