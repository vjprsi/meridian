import { expect, test, type BrowserContext, type Page, type Route } from "@playwright/test";

const AGENT = "0199b000-0000-7000-8000-0000000000a1";
const FLOAT = "0199b000-0000-7000-8000-0000000000f1";
const OUTLET = "0199b000-0000-7000-8000-0000000000c1";
const DEVICE = "0199b000-0000-7000-8000-0000000000d1";
const TENANT = "0199a000-0000-7000-8000-00000000000a";

/** Verifies an ES256 (P1363, base64url) device signature with the registered SPKI public key (base64). */
async function verifyEs256(publicKeyB64: string, signatureB64Url: string, text: string): Promise<boolean> {
  const key = await crypto.subtle.importKey("spki", Buffer.from(publicKeyB64, "base64"), { name: "ECDSA", namedCurve: "P-256" },
    false, ["verify"]);
  return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, Buffer.from(signatureB64Url, "base64url"),
    new TextEncoder().encode(text));
}
const TS = "2026-10-06T08:00:00Z";
const base = { created_at: TS, updated_at: TS, row_version: 1 };
const page1 = <T,>(data: T[]) => ({ data, has_more: false, next_cursor: null });
const json = (route: Route, body: unknown, status = 200, headers: Record<string, string> = {}) =>
  route.fulfill({ status, contentType: "application/json", headers, body: JSON.stringify(body) });

/** Visible element by test id (Next keeps previously visited routes mounted but hidden). */
const vis = (page: Page, id: string) => page.locator(`[data-testid="${id}"]:visible`);

interface Captured { url: string; key: string | null; body: Record<string, unknown>; proof?: string; headers?: Record<string, string> }

/**
 * Mocks just enough of the agent channel (/v1/agent only: the app never calls a domain service) for sign-in, setup
 * with device registration, home and a cash-in. Any other /v1 path answers 404 and is recorded.
 */
async function mockApi(context: BrowserContext, cashIns: Captured[], devices: Captured[], strays: string[]) {
  // context.route also sees requests the service worker makes (reference-data cache).
  await context.route("**/v1/**", async (route) => {
    const req = route.request();
    const u = new URL(req.url());
    const p = u.pathname;
    if (!p.startsWith("/v1/agent/")) strays.push(`${req.method()} ${p}`);
    if (p === "/v1/agent/me") {
      return json(route, { ...base, id: AGENT, agent_code: "AG-001", agent_level: "AGENT", legal_name: "Duka la Amani",
        trading_name: "Amani Shop", contact_msisdn: "+254700000100", currency: "KES", status: "ACTIVE", float_wallet_id: FLOAT });
    }
    if (p === "/v1/agent/me/outlets") {
      return json(route, page1([{ ...base, id: OUTLET, agent_id: AGENT, outlet_code: "OUT-1", name: "Amani Main", country: "KE", status: "ACTIVE" }]));
    }
    if (p === "/v1/agent/devices" && req.method() === "POST") {
      const body = req.postDataJSON() as Record<string, unknown>;
      devices.push({ url: p, key: req.headers()["idempotency-key"] ?? null, body, headers: req.headers() });
      return json(route, { ...base, id: "0199b000-0000-7000-8000-0000000000k1", agent_id: AGENT, agent_device_id: DEVICE,
        agent_outlet_id: OUTLET, key_algorithm: body.key_algorithm, public_key: body.public_key, key_fingerprint: "ab".repeat(32),
        status: "ACTIVE" });
    }
    if (p === "/v1/agent/me/float-balance") {
      return json(route, { ...base, id: FLOAT, ledger_account_id: FLOAT, currency: "KES", posted_debits: "0", posted_credits: "50000",
        posted_balance: "50000", pending_debits: "0", pending_credits: "0", available_balance: "50000", account_version: 1 });
    }
    if (p === "/v1/agent/me/float-wallet") return json(route, { ...base, id: FLOAT, has_pin: false, wallet_type: "AGENT", status: "ACTIVE" });
    if (p === `/v1/agent/devices/${DEVICE}`) {
      return json(route, { ...base, id: DEVICE, agent_id: AGENT, device_type: "SMARTPHONE", serial_number: "PWA", status: "ACTIVE" });
    }
    if (p === "/v1/agent/cash-in") {
      const body = req.postDataJSON() as Record<string, unknown>;
      cashIns.push({ url: p, key: req.headers()["idempotency-key"] ?? null, body, proof: req.headers()["x-agent-device-proof"],
        headers: req.headers() });
      const replay = cashIns.filter((c) => c.key === req.headers()["idempotency-key"]).length > 1;
      return json(route, { ...base, id: "0199b000-0000-7000-8000-0000000000e1", reference: "WT-0001", txn_type: "CASH_IN",
        status: "COMPLETED", amount: body.amount, currency: "KES", fee_amount: "0", tax_amount: "0", commission_amount: "0" },
        200, replay ? { "Idempotent-Replayed": "true" } : {});
    }
    if (req.method() === "GET") return json(route, page1([]));
    return json(route, { title: "Not mocked", status: 404, code: "not_mocked" }, 404);
  });
}

/** Waits until the service worker controls the page and has precached the app shell. */
async function shellCached(page: Page) {
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await page.waitForFunction(async () => {
    const name = (await caches.keys()).find((k) => k.startsWith("shell-"));
    return !!name && !!(await (await caches.open(name)).match("/cash-in")) && !!navigator.serviceWorker.controller;
  }, undefined, { timeout: 30_000 });
}

test.describe("offline mode", () => {
  test("captures a cash-in offline, refuses cash-out, then syncs it with its Idempotency-Key", async ({ page, context }) => {
    const cashIns: Captured[] = [];
    const devices: Captured[] = [];
    const strays: string[] = [];
    await mockApi(context, cashIns, devices, strays);

    await page.goto("/login");
    await shellCached(page);
    await page.getByRole("button", { name: /dev mode/i }).click();
    await expect(page).toHaveURL(/\/setup/);
    // the agent comes from the sign-in (/v1/agent/me): only the outlet is chosen
    await page.getByRole("button", { name: /Amani Main/ }).click();
    const pins = page.locator('input[type="password"]');
    await pins.nth(0).fill("135790");
    await pins.nth(1).fill("135790");
    await page.getByRole("button", { name: /^Continue$/ }).click();
    await expect(page).toHaveURL(/\/$/);
    // device binding: the public half of a WebCrypto P-256 key pair was registered at setup
    expect(devices).toHaveLength(1);
    expect(devices[0].body).toMatchObject({ agent_outlet_id: OUTLET, key_algorithm: "ES256" });
    expect(devices[0].headers?.["x-agent-id"]).toBeTruthy();
    const publicKey = String(devices[0].body.public_key);
    expect(Buffer.from(publicKey, "base64").length).toBe(91);   // SubjectPublicKeyInfo of a P-256 key
    await expect(vis(page, "float-balance")).toContainText("50,000");
    await expect(vis(page, "connectivity")).toHaveAttribute("data-state", "online");
    // Let the router prefetch the visible links (the service worker keeps those payloads for offline navigation).
    await page.waitForLoadState("networkidle");

    await context.setOffline(true);
    await expect(vis(page, "connectivity")).toHaveAttribute("data-state", "offline");
    await expect(vis(page, "offline-banner")).toBeVisible();

    await page.getByRole("link", { name: "Cash-out" }).first().click();
    await expect(vis(page, "cashout-offline")).toBeVisible();

    await page.getByRole("link", { name: "Cash-in" }).first().click();
    await page.getByLabel("Customer phone").fill("0712345678");
    await page.getByLabel("Re-enter the phone number").fill("0712345678");
    await page.getByLabel(/^Amount/).fill("150");
    await page.getByRole("button", { name: "Continue" }).click();
    await page.getByRole("button", { name: "Confirm" }).click();
    await expect(vis(page, "op-outcome")).toHaveAttribute("data-status", "PENDING");
    await expect(vis(page, "pending-badge")).toContainText("1");
    expect(cashIns).toHaveLength(0);

    // The outbox in IndexedDB holds no readable customer data.
    const raw = await page.evaluate(() => new Promise<string>((resolve) => {
      const r = indexedDB.open("meridian-agent");
      r.onsuccess = () => {
        const g = r.result.transaction("outbox").objectStore("outbox").getAll();
        g.onsuccess = () => resolve(JSON.stringify(g.result));
      };
    }));
    expect(raw).toContain("agentapp-");
    expect(raw).not.toContain("712345678");

    // A reload while offline comes from the service worker, forgets the key, and asks for the app PIN; the
    // queued operation survives and decrypts again.
    await page.reload();
    await page.getByLabel("App PIN").fill("000000");
    await page.getByRole("button", { name: "Unlock" }).click();
    await expect(page.getByText("Wrong PIN.")).toBeVisible();
    await page.getByLabel("App PIN").fill("135790");
    await page.getByRole("button", { name: "Unlock" }).click();
    await expect(vis(page, "pending-badge")).toContainText("1");
    expect(cashIns).toHaveLength(0);

    await context.setOffline(false);
    await expect(vis(page, "pending-badge")).toHaveCount(0, { timeout: 20_000 });
    await page.getByRole("link", { name: "More" }).click();
    await page.getByRole("link", { name: "Sync" }).click();
    await expect(vis(page, "outbox-list")).toContainText("SYNCED");
    expect(cashIns).toHaveLength(1);
    expect(cashIns[0].key).toMatch(/^agentapp-[0-9a-f-]{36}$/);
    // the agent channel sets the float wallet, currency and channel itself: the app sends only what the agent typed
    expect(cashIns[0].body).toMatchObject({ payee_msisdn: "+254712345678", amount: "150", agent_outlet_id: OUTLET });
    expect(cashIns[0].body).not.toHaveProperty("pin");
    expect(cashIns[0].body).not.toHaveProperty("agent_wallet_id");
    // the replayed money operation carries a device proof signed by the registered key
    const proof = cashIns[0].proof ?? "";
    const [v, device, ts, sig] = proof.split(".");
    expect(v).toBe("v1");
    expect(device).toBe(DEVICE);
    const text = ["MERIDIAN-AGENT-DEVICE-PROOF-V1", `tenant=${TENANT}`, `agent=${AGENT}`, `device=${DEVICE}`, "method=POST",
      "path=/v1/agent/cash-in", `idempotency_key=${cashIns[0].key}`, `timestamp=${ts}`].join("\n");
    expect(await verifyEs256(publicKey, sig, text)).toBe(true);
    expect(strays).toEqual([]);
  });
});

test("installable PWA: manifest and service worker serve the app shell offline", async ({ page, context }) => {
  const m = await page.request.get("/manifest.webmanifest");
  expect(m.ok()).toBeTruthy();
  const manifest = await m.json();
  expect(manifest.display).toBe("standalone");
  expect(manifest.icons.map((i: { sizes: string }) => i.sizes)).toEqual(expect.arrayContaining(["192x192", "512x512"]));

  await page.goto("/login");
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  await page.waitForFunction(async () => (await caches.keys()).some((k) => k.startsWith("shell-")) &&
    !!(await (await caches.open((await caches.keys()).find((k) => k.startsWith("shell-"))!)).match("/cash-in")));
  await context.setOffline(true);
  await page.goto("/login");
  await expect(page.getByRole("button", { name: /dev mode/i })).toBeVisible();
  await expect(vis(page, "connectivity")).toHaveAttribute("data-state", "offline");
});
