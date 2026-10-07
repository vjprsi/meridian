# Meridian business portal

Business banking portal for SME owners and finance teams on http://localhost:13500. Same stack as `web/customer-web`:
Next.js 16.4 (Cache Components), React 19.3, Tailwind 4, openapi-fetch + React Query, oidc-client-ts.

```bash
npm install
npm run gen:api     # typed client from generated/openapi/services/business-channel-service.openapi.yaml -> src/api/gen
npm run dev         # http://localhost:13500 (/v1/* proxied to MERIDIAN_GATEWAY_URL, default http://localhost:18000)
npm run typecheck   # next typegen && tsc
npm run lint
npm run build
npm run test:e2e    # Playwright smoke against `npm run start` (build first), business channel fully mocked,
                    # local Chrome (PW_CHANNEL=msedge, or PW_CHANNEL=chromium after `npx playwright install chromium`)
```

## API

Every signed-in screen calls only the business channel `/v1/business/*` (`business-channel-service`, client `api` in
`src/api/client.ts`). The server takes the business and the portal user from the token, checks the user's role
(ADMIN, MAKER, APPROVER, VIEWER) and account entitlements, applies the approval matrix and requires a recent sign-in
for approvals, money movement and admin changes. `GET /v1/business/me` returns `{user, business, entitlements[]}`; the
portal uses it only to hide actions the user cannot take (`src/api/business.ts`) and still shows the server's refusal
when one happens.

- Every POST/PUT/PATCH/DELETE carries an `Idempotency-Key`: one UUID per user confirmation (`useRun` / `ActionButton`).
- Step-up: a 401 `{"code":"step_up_required"}` (`WWW-Authenticate: Bearer error="insufficient_user_authentication",
  max_age=<s>`) opens "Confirm it's you"; OIDC sessions sign in again in a popup (`max_age`, `prompt=login`), dev
  sessions refresh `X-Auth-Time`. The same request is then retried once with the **same** Idempotency-Key
  (`src/components/step-up.tsx`).
- Problems `{code, detail, status}` show `detail` under a clear headline for `not_entitled` / `role_not_allowed`
  ("You are not entitled to do this"), `self_approval_forbidden`, `out_of_sequence`, `already_decided`,
  `daily_limit_exceeded`, `bulk_has_invalid_items` and `step_up_required` (`src/messages/en.ts`).

## Sign-in

- **JWT (OIDC)**: Keycloak realm `meridian` at `http://localhost:15180/realms/meridian`, public PKCE client
  `meridian-business-portal` (`NEXT_PUBLIC_OIDC_AUTHORITY`, `NEXT_PUBLIC_OIDC_CLIENT_ID`), redirect
  `http://localhost:13500/auth/callback`. The access token carries scope `channels:business` and the claims
  `business_customer_id`, `business_user_id`, `tenant_id`, `auth_time` and `acr`; it is sent as a bearer token.
- **Dev mode** (local services in dev security mode): `/login` takes the tenant id, business customer id, business user
  id and a name; requests then carry `X-Tenant-Id`, `X-Business-Customer-Id`, `X-Business-User-Id`, `X-User` and
  `X-Auth-Time` (epoch seconds of the dev sign-in; a step-up refreshes it).

Demo users (realm `meridian`, business `0199a000-0000-7000-8000-0000000b0001`, tenant `0199a000-0000-7000-8000-00000000000a`):

| User | Password | |
|---|---|---|
| `demo.business.admin` | `Meridian#Business2026` | The first user to sign in for a business with no portal users becomes its ADMIN. |
| `demo.business.approver` | `Meridian#Approver2026` | The admin adds the approver in **Users** (role APPROVER) and gives entitlements; an IdP admin sets the returned id as the person's `business_user_id` attribute (Keycloak admin, Users -> demo.business.approver -> Attributes). |

## Screens

`/` cash position (totals per currency, pending approval, queue count) · `/accounts`, `/accounts/[id]` (balance,
transactions, statements request/download, CSV / camt.053 export) · `/payments` (status/kind/account filters),
`/payments/new` (OWN_TRANSFER, DOMESTIC to a payee, INTERNATIONAL with an FX quote selling the debit currency),
`/payments/[id]` (approvals, submit / approve / reject / cancel / retry release, downstream status, bulk report) ·
`/approvals` · `/bulk` (CSV upload or paste, BULK / PAYROLL, validation errors) · `/payees` · `/mandates` (and
collections) · `/fx` (quotes, deals) · `/collections` (virtual accounts, payment links) · `/cards`, `/cards/[id]`
(freeze, channels, limits, authorisations) · `/credit` (loans, credit lines) · `/statements` · `/admin/users`,
`/admin/users/[id]` (role, daily limit, suspend / reactivate / remove, entitlement matrix) · `/admin/rules` (approval
matrix) · `/integrations` (API clients, keys, webhooks, subscriptions, deliveries) · `/activity` (security log).

Security headers (SEC-22) come from `next.config.ts`: CSP `frame-ancestors 'none'; base-uri 'self'; form-action 'self';
object-src 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, a restrictive
`Permissions-Policy`, and `Cache-Control: no-store` on `/v1/*`. Session data lives in `sessionStorage` only.
