import type { NextConfig } from "next";

// All API calls go to /v1/... on this origin; Next proxies them to the edge gateway (no CORS needed).
const gateway = process.env.MERIDIAN_GATEWAY_URL ?? "http://localhost:18000";

// One id per build: Next's build id and the service-worker version (a new release replaces the offline caches).
// Kept in the environment so build workers that re-read this file agree on the same id.
process.env.BUILD_ID ??= Date.now().toString(36);
const buildId = process.env.BUILD_ID;

const nextConfig: NextConfig = {
  generateBuildId: async () => buildId,
  env: { NEXT_PUBLIC_BUILD_ID: buildId },
  cacheComponents: true,
  partialPrefetching: true,
  // Small bundles for low-bandwidth outlets: no source maps shipped, compressed responses.
  productionBrowserSourceMaps: false,
  compress: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async rewrites() {
    return [{ source: "/v1/:path*", destination: `${gateway}/v1/:path*` }];
  },
  async headers() {
    return [
      {
        // The service worker must always be revalidated so a new release reaches agents on the next online load.
        source: "/sw.js",
        headers: [
          { key: "Cache-Control", value: "no-cache, no-store, must-revalidate" },
          { key: "Service-Worker-Allowed", value: "/" },
        ],
      },
      {
        source: "/:path*",
        headers: [
          // SEC-22: no framing
          { key: "Content-Security-Policy",
            value: "frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(self), geolocation=(self), microphone=()" },
        ],
      },
    ];
  },
};

export default nextConfig;
