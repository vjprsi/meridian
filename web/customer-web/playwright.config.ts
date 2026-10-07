import { defineConfig } from "@playwright/test";

/**
 * Smoke tests against the production build (npm run build first) with no Meridian backend: API calls are either
 * mocked per test (page.route) or fail as they would with the gateway down. Uses the locally installed Chrome
 * (no browser download): PW_CHANNEL=msedge to use Edge instead.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://localhost:13300",
    channel: process.env.PW_CHANNEL ?? "chrome",
    serviceWorkers: "block",
    viewport: { width: 390, height: 844 },
  },
  webServer: {
    command: "npm run start",
    url: "http://localhost:13300/login",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
