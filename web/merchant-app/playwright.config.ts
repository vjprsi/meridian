import { defineConfig } from "@playwright/test";

/**
 * Smoke tests against the production build (npm run build first) with no Meridian backend: the merchant channel
 * (/v1/merchant/*) is mocked per test with a stateful in-test mock (page.route). Uses the locally installed Chrome
 * (no browser download): PW_CHANNEL=msedge to use Edge, PW_CHANNEL=chromium for Playwright's own Chromium.
 * The viewport is a phone: the app is mobile-first for cashiers.
 */
const channel = process.env.PW_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://localhost:13600",
    channel: channel === "chromium" ? undefined : channel,
    viewport: { width: 412, height: 900 },
    serviceWorkers: "block",
  },
  webServer: {
    command: "npm run start",
    url: "http://localhost:13600/login",
    reuseExistingServer: true,
    timeout: 90_000,
  },
});
