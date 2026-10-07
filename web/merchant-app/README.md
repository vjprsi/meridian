# Meridian Merchant app (web/merchant-app)

Installable, mobile-first PWA for merchants and their cashiers (Next.js 16 / React 19 / Tailwind 4, port **13600**).
Every signed-in screen calls only the merchant channel BFF (`/v1/merchant/*`, merchant-channel-service), whose
contract generates the typed client (`npm run gen:api` -> `src/api/gen/merchant-channel.d.ts`).

## Run

```bash
npm install
npm run gen:api          # after generated/openapi/services/merchant-channel-service.openapi.yaml changes
npm run dev              # http://localhost:13600 (proxies /v1/* to MERIDIAN_GATEWAY_URL, default http://localhost:18000)
npm run typecheck && npm run lint && npm run build
npx playwright test      # smoke tests against the production build with a mocked API (local Chrome; PW_CHANNEL=msedge|chromium)
```

Environment: `MERIDIAN_GATEWAY_URL`, `NEXT_PUBLIC_OIDC_AUTHORITY` (default `http://localhost:15180/realms/meridian`),
`NEXT_PUBLIC_OIDC_CLIENT_ID` (default `meridian-merchant-app`).

Sign-in: Keycloak public client `meridian-merchant-app` (authorization code + PKCE; tokens carry `channels:merchant`,
`merchant_id`, `merchant_user_id`, `tenant_id`, `auth_time`, `acr`; demo users `demo.merchant.owner`,
`demo.merchant.cashier`), or the developer sign-in for services in dev security mode (X-Tenant-Id, X-Merchant-Id,
X-Merchant-User-Id, X-User, X-Auth-Time).

## Screens

Sign-in, store picker (stores come from `GET /v1/merchant/me`; the choice is kept on the device), new sale (dynamic
EMVCo QR rendered in the browser from `qr_payload`, live status by polling the sale every 2 s), sales history,
transactions with refundable amount and refund request, refunds (approve / reject for OWNER and MANAGER, never on
one's own request; approval needs a recent sign-in), payment links, settlements with CSV reports and the fee (MDR)
statement, staff and roles (OWNER), payout account with two-person change approval, developers (API clients, keys,
webhooks; OWNER) and the activity log. English and Kiswahili (language switcher, stored in localStorage).

## Security notes

- The server decides everything (merchant and user from the token, role, store scope, four-eyes, refund cap); the app
  only hides actions the user cannot take.
- Writes carry an Idempotency-Key; on `401 step_up_required` the app re-authenticates (OIDC popup with `max_age`,
  `prompt=login`) and retries with the same key.
- SEC-22 headers on every page (`frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy:
  no-referrer`, restrictive `Permissions-Policy`); `/v1/*` responses are `Cache-Control: no-store`.
- The service worker (`public/sw.js`) caches the app shell only: hashed `/_next/static` assets, icons, the manifest
  and the `/offline` page. It never caches `/v1/*` responses or any page HTML; old caches are dropped on activation.
- Session in sessionStorage (cleared on sign-out); only the language, theme and selected store id live in localStorage.
