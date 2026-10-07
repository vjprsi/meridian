import type { NextConfig } from "next";

// All API calls go to /v1/... on this origin; Next proxies them to the edge gateway (no CORS needed).
const gateway = process.env.MERIDIAN_GATEWAY_URL ?? "http://localhost:18000";
// The service worker is registered as /sw.js?v=<build id>: every release installs a fresh worker and drops old caches.
// Kept in the environment so build workers that re-read this file agree on the same id.
process.env.BUILD_ID ??= Date.now().toString(36);
const buildId = process.env.BUILD_ID;

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
  generateBuildId: async () => buildId,
  env: { NEXT_PUBLIC_BUILD_ID: buildId },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // SEC-22: no framing (clickjacking), no MIME sniffing, no referrer leakage of tokens or ids in URLs.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy",
            value: "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(), geolocation=(), microphone=(), payment=(), usb=()" },
        ],
      },
      {
        // Merchant data must never be stored by the browser, the service worker or any intermediary.
        source: "/v1/:path*",
        headers: [{ key: "Cache-Control", value: "no-store" }],
      },
      {
        // The service worker itself is always revalidated so a new release takes over promptly.
        source: "/sw.js",
        headers: [{ key: "Cache-Control", value: "no-cache" }],
      },
    ];
  },
  async rewrites() {
    return [{ source: "/v1/:path*", destination: `${gateway}/v1/:path*` }];
  },
};

export default nextConfig;
