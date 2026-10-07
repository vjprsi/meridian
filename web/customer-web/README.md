# Meridian customer web banking

Retail customer app (mobile first, installable PWA) on http://localhost:13300. Same stack as `web/meridian-console`:
Next.js 16.4 (Cache Components), React 19.3, Tailwind 4, openapi-fetch + React Query, oidc-client-ts.

```bash
npm install
npm run gen:api     # typed clients from generated/openapi/services/<service>.openapi.yaml -> src/api/gen
npm run dev         # http://localhost:13300 (/v1/* proxied to MERIDIAN_GATEWAY_URL, default http://localhost:18000)
npm run typecheck   # next typegen && tsc
npm run build
npm run test:e2e    # Playwright smoke against `npm run start`, mocked/absent backend, local Chrome (PW_CHANNEL=msedge)
```

Sign-in: OIDC (Keycloak realm `meridian`, code + PKCE, client `NEXT_PUBLIC_OIDC_CLIENT_ID`, default
`meridian-customer-web`; the token must carry `tenant_id` and `customer_id` claims), or dev mode: pick the tenant and a
customer on `/login`; requests then carry `X-Tenant-Id` / `X-User`. Every screen is limited to that customer
(`src/api/me.ts`).

Routes: `/login`, `/signup` (journey-driven onboarding, document upload, status and KYC checks), `/` (accounts,
wallets, cards, loans, recent activity), `/accounts/[id]` (balance, transactions, statements, confirmation letter),
`/move` (history), `/move/transfer`, `/move/pay`, `/move/payees`, `/move/receipt/[id]`, `/standing-orders`, `/cards`,
`/cards/[id]` (freeze, channel controls, limits, report lost/stolen with replacement), `/loans`, `/loans/[id]`
(schedule, repayments, repay), `/insights` (spending by category, budgets, insight cards), `/rewards`, `/inbox`,
`/inbox/[id]`, `/profile` (details, contacts with OTP, addresses, notification preferences, devices), `/kyc`, `/offline`.

Money movement: validate (`POST /v1/payments/validate`) -> review -> step-up (OIDC re-login popup with `max_age=0`
when the last login is older than 5 minutes; explicit confirmation in dev mode) -> create with one Idempotency-Key per
confirmation -> receipt (PDF via `/v1/payments/{id}/receipt`).

PWA: `app/manifest.ts` + `public/sw.js`. The worker caches only the app shell (`/_next/static`, icons, manifest,
`/offline`); `/v1/*`, `/auth/*` and credentialed requests bypass it, pages are network-only, and `/v1/*` responses
are served with `Cache-Control: no-store`. Session data lives in `sessionStorage` only.

Accessibility and i18n: WCAG 2.2 AA tokens for light and dark (system or chosen), visible focus, 44px targets, labelled
controls with described errors, skip link, landmarks, status text never colour-only. Messages in `src/messages/en.ts`
and `src/messages/sw.ts` (Kiswahili; the type requires every English key).

## API gaps (customer channel)

The services expose staff-shaped APIs only. This app scopes everything client-side, which is a presentation guard,
not authorization. Needed server-side:

1. **No customer identity.** The `meridian` realm has no customer client, no customer users and no `customer_id`
   claim mapper; `meridian-console` (the only public client) grants every domain scope plus `meridian:admin`.
2. **No "me" surface.** No `GET /v1/me` / `/v1/customers/me`; services never bind `customer_id` from the token, so any
   principal with the domain scope can read any customer's data by choosing the filter or id (BOLA/IDOR):
   - customer chosen by a query/path parameter: `customersGet`, `customerContacts*`, `customerAddressesList`,
     `customerDevices*`, `depositAccountsList`, `accountHoldersList`, `walletsList`, `cardsList`, `loansList`,
     `paymentsList`, `payeesList`, `kycProfilesList`, `kycDocumentsList`, `spendingSummariesByCategory`, `budgetsList`,
     `financialInsightsList`, `pointsAccountsList`, `cashbackTransactionsList`, `secureMessageThreadsList`,
     `announcementsActive`, `notificationsList` / `notificationPreferencesList` (`recipient_id`);
   - no customer filter at all, only a parent id: `depositAccountsBalance` / `Transactions` / `ConfirmationLetter`,
     `statementsList` (`deposit_account_id`), `statementsDownloadLink`, `standingOrdersList`, `cardControlsList`,
     `cardSpendLimitsList` (`card_id`), `loansSchedule`, `loansRepayments`, `pointsAccountsEntries`,
     `secureMessageThreadsMessages`, `budgetsCurrentPeriod`;
   - get/act by id without an ownership check: `paymentsGet`, `paymentsReceipt`, `cardsGet`, `cardsFreeze` /
     `Unfreeze` / `Block` / `Replace`, `cardControlsUpdate`, `cardSpendLimitsUpdate`, `payeesVerify` / `Trust` /
     `Delete`, `standingOrdersPause` / `Resume` / `Cancel`, `loansGet`, `loansRepay` (`source_account_id` not checked
     against the caller), `budgetsArchive`, `financialInsightsDismiss`, `notificationsMarkRead`,
     `secureMessageThreadsGet` / `Read`, `customerDevicesRevoke`;
   - writes that trust ownership fields in the body: `paymentsCreate` (`customer_id`, `debtor_account_id`),
     `payeesCreate`, `standingOrdersCreate` (`deposit_account_id`), `statementsCreate`, `budgetsCreate`,
     `secureMessageThreadsCreate`, `kycDocumentsCreate`, `documentsCreate` (`owner_id`), `notificationPreferencesCreate`.
3. **Inbox trusts the caller's role.** `sender_type`, `opened_by` and `reader_type` come from the body (a customer can
   post as `STAFF`), and `include_internal=true` returns staff-only notes to whoever asks.
4. **No customer step-up.** MFA factors exist only for staff users (`/v1/users/{user_id}/mfa-factors`); no service
   checks `acr`/`amr`/`auth_time`, although `payeesTrust` is documented as "after step-up". The app's re-login is
   client-side only.
5. **No customer sessions.** `sessionsList` / `sessionsRevoke` are staff sessions (`user_id`); the app lists and revokes
   customer devices instead.
6. **Sign-up has no public surface.** `onboardingJourneysList` / `onboardingApplicationsCreate` need an authenticated
   tenant principal; the client must invent `application_number`; there is no applicant token bound to an application
   (anyone with its id can read or advance it). OTP (`customerContactsSendVerification` / `Verify`) needs an existing
   customer, and onboarding has no send/verify-code operation for `PHONE_VERIFICATION`. Step inputs are only listed
   as field names in the journey definition (no types, labels or validation), so the form is inferred.
7. **Untyped responses** (`type: object` in the contract): `paymentsValidate`, `spendingSummaries*`,
   `documentsUploadUrl` / `DownloadUrl`, `statementsDownloadLink`.
8. **Transactions are raw ledger entries** (`depositAccountsTransactions`): no counterparty, merchant or category for a
   customer-friendly feed; enrichment (`transactionEnrichments*`) is a separate lookup per entry.
9. Tracker screens not built yet: passkey login and MFA (needs 1 and 4), tax certificates, open-banking consents,
   P2P/wallet transfers, bill payments, card PIN and PAN reveal.
