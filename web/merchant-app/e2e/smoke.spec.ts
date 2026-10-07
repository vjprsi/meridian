import { expect, test } from "@playwright/test";
import { FOREIGN_SALE, FOREIGN_STORE, MERCHANT, STORE_A, TENANT, USERS, devLogin, mockMerchant } from "./mock";

test("a cashier starts a sale, the QR is shown and the sale turns paid while the app polls", async ({ page }) => {
  const state = await mockMerchant(page);
  await devLogin(page, USERS.cashier);
  // one store in scope: chosen automatically, no picker
  await expect(page.getByTestId("current-store")).toContainText("Moi Avenue Shop");

  await page.getByRole("navigation", { name: "Quick" }).getByRole("link", { name: "New sale" }).click();
  await expect(page.getByRole("heading", { name: "New sale" })).toBeVisible();
  await page.getByLabel(/^Amount/).fill("12.50");
  await page.getByLabel(/^Reference/).fill("INV-42");
  await page.getByRole("button", { name: "Show QR code" }).click();

  const qr = page.getByTestId("sale-qr");
  await expect(qr).toBeVisible();
  await expect(qr.locator("svg")).toBeVisible();
  await expect(qr).toHaveAttribute("data-payload", /^000201/);
  await expect(page.getByTestId("sale-status")).toHaveText("Waiting for payment…");

  // the mock answers PAID on the third read of the sale: the app polls every 2 s
  await expect(page.getByTestId("sale-status")).toContainText("Paid", { timeout: 15_000 });
  await expect(qr).toHaveCount(0);

  const posts = state.calls.filter((c) => c.path === "/v1/merchant/sales");
  expect(posts).toHaveLength(1);
  expect(posts[0].key).toBeTruthy();
  const created = state.sales.find((s) => s.cashier_user_id === USERS.cashier.id);
  expect(created?.merchant_store_id).toBe(STORE_A);
  expect(created?.amount).toBe("12.50");
});

test("a cashier cannot approve a refund: no approve button, and the server refuses the call", async ({ page }) => {
  const state = await mockMerchant(page);
  await devLogin(page, USERS.cashier);
  await page.getByRole("navigation", { name: "Quick" }).getByRole("link", { name: "Refunds" }).click();
  await expect(page.getByRole("heading", { name: "Refunds" })).toBeVisible();
  const item = page.getByTestId("refund-list").getByRole("listitem").first();
  await expect(item).toContainText("Damaged item");
  await expect(item.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await expect(item.getByRole("button", { name: "Reject" })).toHaveCount(0);

  // calling the API directly is refused by the server (role_not_allowed)
  const refundId = state.refunds[0].id as string;
  const res = await page.evaluate(async ([id, tenant, merchant, user]) => {
    const r = await fetch(`/v1/merchant/refunds/${id}/approve`, { method: "POST", headers: {
      "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID(), "X-Tenant-Id": tenant,
      "X-Merchant-Id": merchant, "X-Merchant-User-Id": user }, body: "{}" });
    return { status: r.status, body: await r.json() };
  }, [refundId, TENANT, MERCHANT, USERS.cashier.id]);
  expect(res.status).toBe(403);
  expect(res.body.code).toBe("role_not_allowed");
  expect(state.refunds[0].status).toBe("PENDING_APPROVAL");

  // the requester's own request offers no approve button either; another manager (the owner) gets it
  await page.getByRole("navigation", { name: "Quick" }).getByRole("button", { name: "More" }).click();
  await page.getByRole("dialog", { name: "Menu" }).getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await devLogin(page, USERS.manager);
  await page.goto("/refunds");
  await expect(page.getByTestId("refund-list").getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("an owner approves another user's refund", async ({ page }) => {
  const state = await mockMerchant(page);
  await devLogin(page, USERS.owner);
  await page.getByTestId("store-list").getByRole("button", { name: /Moi Avenue Shop/ }).click();
  await page.goto("/refunds");
  const item = page.getByTestId("refund-list").getByRole("listitem").first();
  await item.getByRole("button", { name: "Approve" }).click();
  const dialog = page.getByRole("dialog", { name: "Approve refund" });
  await dialog.getByLabel(/Comment/).fill("ok");
  await dialog.getByRole("button", { name: "Approve", exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(state.refunds[0].status).toBe("COMPLETED");
  expect(state.refunds[0].decided_by).toBe(USERS.owner.id);
});

test("stores of other merchants are never offered and their sales show not found", async ({ page }) => {
  await mockMerchant(page);
  await devLogin(page, USERS.owner);
  // two stores in scope: the picker lists exactly the stores the server returned
  await expect(page.getByRole("heading", { name: "Choose your store" })).toBeVisible();
  const list = page.getByTestId("store-list");
  await expect(list.getByRole("button")).toHaveCount(2);
  await expect(list).toContainText("Moi Avenue Shop");
  await expect(list).toContainText("Westlands Kiosk");
  await expect(list).not.toContainText(FOREIGN_STORE);
  await list.getByRole("button", { name: /Westlands Kiosk/ }).click();
  await expect(page.getByTestId("current-store")).toContainText("Westlands Kiosk");

  await page.goto(`/sales/${FOREIGN_SALE}`);
  await expect(page.getByText("Sale not found")).toBeVisible();
  await expect(page.getByTestId("sale-qr")).toHaveCount(0);
});

test("the app switches to Swahili", async ({ page }) => {
  await mockMerchant(page);
  await devLogin(page, USERS.cashier);
  await page.getByRole("combobox", { name: "Language" }).selectOption("sw");
  await expect(page.getByRole("navigation", { name: "Quick" }).getByRole("link", { name: "Mauzo mapya" })).toBeVisible();
});
