import { defineConfig } from "@playwright/test";

// Smoke tests against the production build with the platform API mocked in the browser (no backend needed).
// Run: npm run build && npm run test:e2e   (uses the installed Chrome; PW_CHANNEL=chromium for Playwright's own)
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:13401",
    channel: process.env.PW_CHANNEL ?? "chrome",
    headless: true,
  },
  webServer: {
    command: "npx next start -p 13401",
    url: "http://localhost:13401/login",
    reuseExistingServer: false,
    timeout: 60_000,
    env: { MERIDIAN_GATEWAY_URL: "http://127.0.0.1:9" },
  },
});
