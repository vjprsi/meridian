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
  async rewrites() {
    return [{ source: "/v1/:path*", destination: `${gateway}/v1/:path*` }];
  },
};

export default nextConfig;
