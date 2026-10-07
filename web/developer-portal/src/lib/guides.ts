/** Guide pages in reading order (used by the sidebar, the docs index and prev/next links). */
export const GUIDES = [
  { slug: "getting-started", title: "Getting started", blurb: "Sandbox tenant, an OAuth client, a token and your first call." },
  { slug: "authentication", title: "Authentication & scopes", blurb: "OAuth 2.0 client credentials, API keys and domain:read / domain:write scopes." },
  { slug: "idempotency", title: "Idempotency keys", blurb: "Retry any write safely with the Idempotency-Key header; optimistic locking with If-Match." },
  { slug: "pagination", title: "Pagination", blurb: "Keyset cursors: limit, cursor, has_more and next_cursor." },
  { slug: "errors", title: "Errors", blurb: "RFC 9457 problem+json, stable error codes and request ids." },
  { slug: "webhooks", title: "Webhooks", blurb: "Endpoints, subscriptions, retries, replays and HMAC signature verification." },
  { slug: "versioning", title: "Versioning & changelog", blurb: "URL versioning, compatibility rules and what changed." },
] as const;

export type GuideSlug = (typeof GUIDES)[number]["slug"];
