import { defineConfig } from "@playwright/test";

/**
 * Smoke tests against the production build (npm run build first) with no Meridian backend: the business channel
 * (/v1/business/*) is mocked per test with a stateful in-test mock (page.route). Uses the locally installed Chrome
 * (no browser download): PW_CHANNEL=msedge to use Edge, PW_CHANNEL=chromium for Playwright's own Chromium.
 */
const channel = process.env.PW_CHANNEL ?? "chrome";

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://localhost:13500",
    channel: channel === "chromium" ? undefined : channel,
    viewport: { width: 1366, height: 900 },
  },
  webServer: {
    command: "npm run start",
    url: "http://localhost:13500/login",
    reuseExistingServer: true,
    timeout: 90_000,
  },
});
