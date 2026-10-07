import { createHash } from "node:crypto";
import { expect, test, type Page, type Request, type Route } from "@playwright/test";

/**
 * Sign-up against a mocked public applicant surface (/v1/onboarding/public/applications/*): no sign-in, the tenant
 * as ?tenant_id, the applicant token in X-Applicant-Token. The mock is stateful: it walks a small journey
 * (phone OTP -> personal details -> identity document -> terms) and records every call.
 */
const TENANT = "0199a000-0000-7000-8000-00000000000a";
const APP = "0199d000-0000-7000-8000-0000000000a1";
const DOC = "0199d000-0000-7000-8000-0000000000d1";
const BASE = `/v1/onboarding/public/applications`;
const ALERT = "[role=alert]:not(#__next-route-announcer__)";

interface Call { method: string; path: string; query: URLSearchParams; token: string | null; auth: string | null;
  tenantHeader: string | null; body: unknown }

const iso = (msFromNow: number) => new Date(Date.now() + msFromNow).toISOString();
const problem = (route: Route, status: number, body: Record<string, unknown>, headers: Record<string, string> = {}) =>
  route.fulfill({ status, contentType: "application/problem+json", headers, body: JSON.stringify({ status, ...body }) });
const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });

const STEPS = [
  { step_code: "PHONE", step_type: "PHONE_VERIFICATION", fields: [{ name: "phone_number", type: "PHONE", label_key: "onboarding.field.phone_number", required: true }] },
  { step_code: "PERSONAL", step_type: "PERSONAL_DETAILS", fields: [
    { name: "first_name", type: "STRING", label_key: "onboarding.field.first_name", required: true, min_length: 2, max_length: 40 },
    { name: "last_name", type: "STRING", label_key: "onboarding.field.last_name", required: true, max_length: 40 },
    { name: "date_of_birth", type: "DATE", label_key: "onboarding.field.date_of_birth", required: true },
    { name: "nationality", type: "COUNTRY", label_key: "onboarding.field.nationality", required: true },
    { name: "national_id_number", type: "STRING", label_key: "onboarding.field.national_id_number", required: true, pattern: "[0-9]{7,9}" },
    { name: "kyc_profile_id", type: "STRING", label_key: "onboarding.field.kyc_profile_id", required: false },
  ] },
  { step_code: "ID_DOC", step_type: "DOCUMENT_CAPTURE", fields: [{ name: "id_document_id", type: "DOCUMENT", label_key: "onboarding.field.id_document_id", required: true }] },
  { step_code: "TERMS", step_type: "TERMS_ACCEPTANCE", fields: [{ name: "terms_accepted", type: "BOOLEAN", label_key: "onboarding.field.terms_accepted", required: true }] },
];

interface Options {
  /** Expiry of the token issued by start (ms from now). */
  startTtlMs?: number;
  /** Overrides per call (return true when handled). */
  override?: (call: Call, route: Route) => Promise<boolean> | boolean;
  steps?: typeof STEPS;
}

async function mockApplicant(page: Page, calls: Call[], opts: Options = {}) {
  const steps = opts.steps ?? STEPS;
  let done = 0;
  let tokenSeq = 1;
  let validToken = "";
  let submitted = false;
  const tokenBody = (ttl: number) => {
    validToken = `oat_token${tokenSeq++}`;
    return { id: `0199d000-0000-7000-8000-00000000t00${tokenSeq}`, created_at: iso(0), updated_at: iso(0), row_version: 0,
      onboarding_application_id: APP, expires_at: iso(ttl), applicant_token: validToken,
      application_number: "APP-20261007-K7QX2M4P", current_step: steps[0].step_code };
  };
  const application = () => ({ id: APP, application_number: "APP-20261007-K7QX2M4P", status: submitted ? "SUBMITTED" : done ? "IN_PROGRESS" : "STARTED",
    channel: "WEB", onboarding_journey_id: TENANT, progress_percent: Math.round((done / steps.length) * 100),
    submitted_at: submitted ? iso(0) : null, created_at: iso(0), updated_at: iso(0), row_version: done });
  const stepRow = (i: number, status = "PENDING") => ({ id: `0199d000-0000-7000-8000-00000000s00${i}`, onboarding_application_id: APP,
    sequence: i + 1, status, attempts: 0, created_at: iso(0), updated_at: iso(0), row_version: 0, ...steps[i] });

  await page.route("**/upload/**", (route) => route.fulfill({ status: 200, body: "" }));
  await page.route("**/v1/**", async (route: Route) => {
    const req: Request = route.request();
    const url = new URL(req.url());
    const h = req.headers();
    const call: Call = { method: req.method(), path: url.pathname, query: url.searchParams, token: h["x-applicant-token"] ?? null,
      auth: h["authorization"] ?? null, tenantHeader: h["x-tenant-id"] ?? null, body: req.postDataJSON?.() ?? null };
    calls.push(call);
    if (opts.override && await opts.override(call, route)) return;
    const p = url.pathname;
    if (p === `${BASE}/start`) return json(route, tokenBody(opts.startTtlMs ?? 30 * 60_000), 201);
    if (!p.startsWith(`${BASE}/${APP}`)) return problem(route, 404, { code: "not_found" });
    if (call.token !== validToken) return problem(route, 401, { code: "applicant_token_invalid", title: "Unauthorized", detail: "The applicant token is not valid" });
    const rest = p.slice(`${BASE}/${APP}`.length);
    if (rest === "/token/refresh") return json(route, tokenBody(30 * 60_000));
    if (rest === "") return json(route, application());
    if (rest === "/next-step") {
      if (done >= steps.length) return problem(route, 404, { code: "no_next_step", detail: "All steps are complete; submit the application" });
      return json(route, stepRow(done));
    }
    if (rest === "/otp/send") {
      return json(route, { id: "0199d000-0000-7000-8000-0000000000o1", onboarding_application_id: APP, step_code: "PHONE", channel: "SMS",
        status: "PENDING", attempts: 0, max_attempts: 5, expires_at: iso(300_000), destination_masked: "+2547******78",
        resend_after: iso(30_000), created_at: iso(0), updated_at: iso(0), row_version: 0 });
    }
    if (rest === "/otp/verify") {
      const code = (call.body as { code: string }).code;
      if (code !== "123456") return problem(route, 422, { code: "otp_invalid", detail: "Wrong code; 4 attempts left" });
      done++;
      return json(route, stepRow(0, "COMPLETED"));
    }
    if (rest === "/documents") {
      return json(route, { document_id: DOC, step_code: "ID_DOC", method: "PUT", url: "http://localhost:13300/upload/doc-1",
        headers: { "Content-Type": "image/png" }, expires_at: iso(600_000) });
    }
    if (rest === `/documents/${DOC}/complete`) return json(route, { document_id: DOC, status: "UPLOADED" });
    const m = rest.match(/^\/steps\/(\w+)$/);
    if (m) {
      done++;
      return json(route, stepRow(steps.findIndex((s) => s.step_code === m[1]), "COMPLETED"));
    }
    if (rest === "/submit") {
      submitted = true;
      validToken = "revoked";
      return json(route, application());
    }
    return problem(route, 404, { code: "not_found" });
  });
}

const PNG = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000", "hex");

async function begin(page: Page) {
  await page.goto("/signup");
  await expect(page.getByRole("heading", { level: 1, name: "Open an account" })).toBeVisible();
  await page.getByRole("button", { name: "Start application" }).click();
}

async function fillPersonal(page: Page) {
  await page.getByLabel("First name").fill("Amina");
  await page.getByLabel("Last name").fill("Otieno");
  await page.getByLabel("Date of birth").fill("1990-04-12");
  await page.getByLabel("Nationality").selectOption("KE");
  await page.getByLabel("National ID number").fill("12345678");
}

test("sign-up: start -> OTP -> typed fields -> document -> terms -> submit, unauthenticated, token rotated", async ({ page }) => {
  const calls: Call[] = [];
  // the first token is close to expiry: the app rotates it before its first authenticated call
  await mockApplicant(page, calls, { startTtlMs: 2 * 60_000 });
  await begin(page);

  // OTP: client-side destination check, wrong code with attempts left, then the right code
  await expect(page.getByRole("heading", { name: "Your phone number" })).toBeVisible();
  await page.getByLabel("Mobile number").fill("0712");
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByLabel("Mobile number")).toHaveAccessibleDescription(/international format/);
  expect(calls.some((c) => c.path.endsWith("/otp/send"))).toBe(false);
  await page.getByLabel("Mobile number").fill("+254 712 345 678");
  await page.getByRole("button", { name: "Send code" }).click();
  await expect(page.getByText("We sent a 6-digit code to +2547******78.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send a new code" })).toBeDisabled();
  await expect(page.getByText(/You can request a new code in \d+ s/)).toBeVisible();
  expect((calls.find((c) => c.path.endsWith("/otp/send"))!.body as { destination: string }).destination).toBe("+254712345678");
  await page.getByLabel("Verification code").fill("111111");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page.locator(ALERT)).toContainText("That code is not right. 4 attempts left.");
  await page.getByLabel("Verification code").fill("123456");
  await page.getByRole("button", { name: "Verify" }).click();

  // typed fields: required and length rules are checked before anything is sent
  await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByLabel("First name")).toHaveAccessibleDescription(/This field is required/);
  await expect(page.getByLabel("First name")).toBeFocused();
  await page.getByLabel("First name").fill("A");
  await page.getByLabel("National ID number").fill("12-34");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByLabel("First name")).toHaveAccessibleDescription(/at least 2 characters/);
  await expect(page.getByLabel("National ID number")).toHaveAccessibleDescription(/format is not valid/);
  expect(calls.some((c) => c.path.endsWith("/steps/PERSONAL"))).toBe(false);
  await expect(page.getByLabel(/kyc profile/i)).toHaveCount(0); // bank-managed field is never rendered
  await fillPersonal(page);
  await page.getByRole("button", { name: "Continue" }).click();

  // document: pre-signed PUT, then complete with size and SHA-256, then the step references the document id
  await expect(page.getByRole("heading", { name: "Identity document" })).toBeVisible();
  await page.getByLabel("Type of document").selectOption("PASSPORT");
  await page.getByLabel("Photo of your identity document").setInputFiles({ name: "passport.png", mimeType: "image/png", buffer: PNG });
  const put = page.waitForRequest((r) => r.url().includes("/upload/doc-1") && r.method() === "PUT");
  await page.getByRole("button", { name: "Continue" }).click();
  expect((await put).postDataBuffer()?.equals(PNG)).toBe(true);

  // terms: a required consent must be ticked
  await expect(page.getByRole("heading", { name: "Terms and conditions" })).toBeVisible();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("You need to accept to continue.")).toBeVisible();
  await page.getByLabel("I accept the terms and conditions").check();
  await page.getByRole("button", { name: "Continue" }).click();

  await expect(page.getByRole("heading", { name: "Ready to submit" })).toBeVisible();
  await page.getByRole("button", { name: "Submit application" }).click();
  await expect(page.getByRole("heading", { name: "Application status" })).toBeVisible();
  await expect(page.getByText("Thanks, your application was submitted.")).toBeVisible();
  await expect(page.getByText("APP-20261007-K7QX2M4P")).toBeVisible();

  // request bodies
  const doc = calls.find((c) => c.path.endsWith("/documents"))!;
  expect(doc.body).toEqual({ step_code: "ID_DOC", document_type: "PASSPORT", file_name: "passport.png", content_type: "image/png" });
  const complete = calls.find((c) => c.path.endsWith(`/documents/${DOC}/complete`))!;
  expect(complete.body).toEqual({ size_bytes: PNG.length, checksum: createHash("sha256").update(PNG).digest("hex") });
  expect(calls.find((c) => c.path.endsWith("/steps/ID_DOC"))!.body).toEqual({ input: { id_document_id: DOC } });
  expect(calls.find((c) => c.path.endsWith("/steps/PERSONAL"))!.body).toEqual({ input: { first_name: "Amina", last_name: "Otieno",
    date_of_birth: "1990-04-12", nationality: "KE", national_id_number: "12345678" } });
  expect(calls.find((c) => c.path.endsWith("/steps/TERMS"))!.body).toEqual({ input: { terms_accepted: true } });
  const start = calls.find((c) => c.path.endsWith("/start"))!;
  expect(start.body).toMatchObject({ journey_code: "RETAIL_ONLINE", channel: "WEB" });
  expect((start.body as { device_fingerprint: string }).device_fingerprint).toMatch(/^web-/);

  // unauthenticated: tenant as ?tenant_id, no bearer or staff headers; the token was rotated before first use
  for (const c of calls) {
    expect(c.path, c.path).toMatch(/^\/v1\/onboarding\/public\/applications\//);
    expect(c.query.get("tenant_id")).toBe(TENANT);
    expect(c.auth).toBeNull();
    expect(c.tenantHeader).toBeNull();
  }
  expect(start.token).toBeNull();
  const refreshIdx = calls.findIndex((c) => c.path.endsWith("/token/refresh"));
  expect(refreshIdx).toBe(1);
  expect(calls[refreshIdx].token).toBe("oat_token1");
  for (const c of calls.slice(refreshIdx + 1)) expect(c.token, c.path).toBe("oat_token2");

  // the token never reaches localStorage, and it is gone from sessionStorage once submitted
  const storage = await page.evaluate(() => ({ local: JSON.stringify(localStorage), session: JSON.stringify(sessionStorage) }));
  expect(storage.local).not.toContain("oat_");
  expect(storage.session).not.toContain("oat_");
});

test("sign-up: an expired applicant token ends the session with a clear restart", async ({ page }) => {
  const calls: Call[] = [];
  await mockApplicant(page, calls, {
    override: (c, route) => c.path.endsWith("/next-step")
      ? problem(route, 401, { code: "applicant_token_expired", title: "Unauthorized", detail: "The applicant token has expired" }).then(() => true)
      : false,
  });
  await begin(page);
  await expect(page.getByRole("heading", { name: "Your application session has ended" })).toBeVisible();
  await expect(page.locator(ALERT)).toContainText("locked after 30 minutes without activity");
  const during = await page.evaluate(() => JSON.stringify(localStorage));
  expect(during).not.toContain("oat_");
  await page.getByRole("button", { name: "Start a new application" }).click();
  await expect(page.getByRole("heading", { name: "Let's get started" })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("meridian.signup.applicant"))).toBeNull();
});

test("sign-up: server field errors are shown next to their fields", async ({ page }) => {
  const calls: Call[] = [];
  await mockApplicant(page, calls, {
    steps: [STEPS[1]],
    override: (c, route) => c.path.endsWith("/steps/PERSONAL")
      ? problem(route, 422, { code: "invalid_fields", title: "Unprocessable", detail: "Step PERSONAL has invalid fields", errors: [
        { field: "input.national_id_number", code: "pattern_mismatch", message: "national_id_number has an invalid format" },
        { field: "input.date_of_birth", code: "invalid_date", message: "date_of_birth must be a date (YYYY-MM-DD)" },
      ] }).then(() => true)
      : false,
  });
  await begin(page);
  await expect(page.getByRole("heading", { name: "About you" })).toBeVisible();
  await fillPersonal(page);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByLabel("National ID number")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByLabel("National ID number")).toHaveAccessibleDescription(/The format is not valid/);
  await expect(page.getByLabel("Date of birth")).toHaveAccessibleDescription(/Enter a valid date/);
  await expect(page.getByText("Please correct the fields marked below.")).toBeVisible();
  await expect(page.getByLabel("Date of birth")).toBeFocused();
  // in Kiswahili too
  await page.getByLabel("Language").selectOption("sw");
  await expect(page.getByRole("heading", { name: "Kuhusu wewe" })).toBeVisible();
});

test("sign-up: 429 waits for Retry-After and a locked code asks for a new one", async ({ page }) => {
  const calls: Call[] = [];
  let starts = 0;
  await mockApplicant(page, calls, {
    override: async (c, route) => {
      if (c.path.endsWith("/start") && starts++ === 0) {
        await problem(route, 429, { code: "rate_limited", title: "Too Many Requests", detail: "Too many applications from this device; try again later" },
          { "Retry-After": "2" });
        return true;
      }
      if (c.path.endsWith("/otp/verify")) {
        await problem(route, 422, { code: "otp_locked", detail: "Too many wrong codes; request a new code" });
        return true;
      }
      return false;
    },
  });
  await begin(page);
  await expect(page.locator(ALERT)).toContainText(/try again in [12] seconds/);
  await expect(page.getByRole("button", { name: "Start application" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Start application" })).toBeEnabled({ timeout: 5_000 });
  await page.getByRole("button", { name: "Start application" }).click();
  await expect(page.getByRole("heading", { name: "Your phone number" })).toBeVisible();
  await page.getByLabel("Mobile number").fill("+254712345678");
  await page.getByRole("button", { name: "Send code" }).click();
  await page.getByLabel("Verification code").fill("999999");
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page.locator(ALERT)).toContainText("Too many wrong codes");
  await expect(page.getByRole("button", { name: "Verify" })).toBeDisabled();
  await expect(page.getByLabel("Verification code")).toBeDisabled();
});
