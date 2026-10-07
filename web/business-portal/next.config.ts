import type { NextConfig } from "next";

// All API calls go to /v1/... on this origin; Next proxies them to the edge gateway (no CORS needed).
const gateway = process.env.MERIDIAN_GATEWAY_URL ?? "http://localhost:18000";

const nextConfig: NextConfig = {
  cacheComponents: true,
  partialPrefetching: true,
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
        // Business data must never be stored by the browser or any intermediary.
        source: "/v1/:path*",
        headers: [{ key: "Cache-Control", value: "no-store" }],
      },
    ];
  },
  async rewrites() {
    return [{ source: "/v1/:path*", destination: `${gateway}/v1/:path*` }];
  },
};

export default nextConfig;
