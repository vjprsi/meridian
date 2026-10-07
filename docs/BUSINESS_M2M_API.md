# Machine-to-machine business API access

How a business's own systems (ERP, treasury, payroll) call the business channel (`/v1/business`) without a person
signing in, with the same object-level authorization, entitlements and approval matrix as the portal.

## Model

| Piece | Where | What it holds |
|---|---|---|
| API client | identity-service `api_client` | `business_customer_id` (the owning business) and `business_user_id` (its TECHNICAL user); `allowed_scopes` = `channels:business` only; SERVER client, `CLIENT_SECRET_BASIC` (secret shown once) or `PRIVATE_KEY_JWT` (https `jwks_uri`) |
| Technical user | business-channel-service `business_user` | `user_type = TECHNICAL`, `client_id` (public client identifier), `api_client_id`, role MAKER or VIEWER, account entitlements like any user |
| IdP client | Keycloak (or the bank's IdP) | client credentials; `clientId` = identity-service `client_id`; hardcoded claims `business_customer_id`, `business_user_id`, `m2m=true`, `tenant_id`; default scope `channels:business`; audience `meridian-api` |

identity-service enforces the binding (`BusinessClientBinding`): a bound client is a SERVER client holding only
`channels:business`, both ids are set together, and `channels:business` is never granted to an unbound client.

## Lifecycle

1. A business ADMIN creates the client in the portal (`POST /v1/business/api-clients`, step-up), choosing the
   technical role (MAKER or VIEWER, default VIEWER) and the authentication method. The channel creates the TECHNICAL
   user first (id derived from the business, the admin and the request's Idempotency-Key, so a retry reuses it), then
   the identity-service client tagged with both ids, then records the client's `client_id` on the technical user.
2. The bank provisions the IdP client from the identity-service record (`platform.api_client.created` event: client id,
   auth method / secret hash or JWKS, business tags as hardcoded claims). `tools/keycloak_realm.py` ships a demo client
   (`meridian-business-m2m-demo`) showing the exact mappers.
3. The ADMIN grants the technical user entitlements per account (`PUT /v1/business/users/{id}/entitlements/{account}`),
   e.g. initiate on the payroll account only, with a per-payment cap.
4. Suspending the client (`POST /v1/business/api-clients/{id}/suspend`) suspends the technical user at once: its tokens
   are refused (`business_user_inactive`) even before they expire.

## Token and enforcement

A machine-to-machine token is a business token (`channels:business` + `business_customer_id` + `business_user_id`)
with the `m2m` marker (boolean claim) and `azp` = the client. `BusinessContext.fromJwt` exposes it as
`BusinessPrincipal(m2m = true, clientId = azp)`; dev mode uses the `X-Business-M2M-Client` header (dropped by the edge
gateway in JWT mode).

business-channel-service (`CurrentUser.bind`, before every handler):

- an m2m token must name a TECHNICAL user of that business whose `client_id` equals the token's client;
- an interactive (portal) token must name a HUMAN user; a technical user can never sign in interactively;
- otherwise 403 `business_user_type_mismatch` (audited); first-sign-in bootstrap never applies to m2m tokens.

Role and entitlements come from the technical user's record, never from the token:

- technical users are MAKER or VIEWER only (creation and role changes refuse ADMIN / APPROVER with
  `technical_role_not_allowed`), so a machine never approves, administers users, rules, cards or integrations;
- a MAKER client drafts and submits payments, bulk files and operations on accounts it is entitled to initiate on;
  everything it submits goes through the approval matrix and needs human approvers (self-approval rules unchanged);
- step-up: client credentials authenticate at every token issue, so the token's `iat` stands for the authentication
  time (`meridian.business.step-up-max-age` applies; the `acr` requirement does not). Clients request a fresh token for
  money movement.

Writes and refusals are audited in `business_action` under the technical user's id; downstream calls carry
`business-user:<technical user id>` as the acting principal, so payment-service four-eyes checks see a distinct maker.

## Not covered yet

- Automatic IdP client provisioning from identity-service events (today: an operator step or IaC).
- Per-client rate limits for the business channel beyond identity-service's `rate_limit_policy_id` at the gateway.
- mTLS-bound (RFC 8705) business clients; FAPI profile for business clients.
