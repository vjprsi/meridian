# Meridian customer app (Flutter)

Native retail banking app for iOS and Android: the mobile counterpart of `web/customer-web`. Every screen calls only
the customer channel `/v1/me/*` (customer-channel-service). See `../README.md` for the toolchain, environments and
native builds.

```bash
F=/d/DevTools/flutter/bin/flutter
$F pub get && $F analyze && $F test
$F build web --dart-define-from-file=env/dev.json     # smoke build
$F run -d chrome --dart-define-from-file=env/dev.json  # dev mode against local services (dev security mode)
```

## Screens

Sign-in (biometric unlock / browser sign-in / dev form), Home (accounts with available balances, wallets, cards,
loans), account and wallet detail with cursor-paged transactions, Pay (own transfer or payee: server validation ->
confirmation sheet with fee and Confirmation of Payee -> step-up -> payment), Payees (add with step-up, trust with
step-up, delete), Cards (card face, freeze / unfreeze with step-up, channel switches for online / contactless / ATM /
shops, spend limits with step-up, report lost or stolen with replacement), Loans (outstanding, schedule, repayments,
repay with step-up), Insights (spending by category this month, insight cards with dismiss), Inbox (secure message
threads with replies, new message, notifications), Security (sign-in level, second factors, biometric unlock switch,
signed-in devices, sign out one device, sign out everywhere), settings (theme system / light / dark, language
auto / English / Kiswahili).

## Security

- **Sign-in**: `meridian-customer-mobile` (public client, PKCE S256, custom-scheme redirect, same LoA browser flow as
  web banking). AppAuth uses the system browser (ASWebAuthenticationSession / Custom Tabs); the app never sees the
  password.
- **Step-up** (`AuthController.withStepUp`): money movement and security changes need acr 2 within the server window
  (300 s; the app asks again after 240 s). The app explains, then sends a new authorization request with
  `acr_values=2` and `max_age`. If the server still answers `401 step_up_required`, the app steps up with exactly the
  `WWW-Authenticate` challenge (RFC 9470) and retries **once with the same Idempotency-Key**. No second factor
  enrolled -> clear message (enrol in web banking).
- **Idempotency**: every write carries `Idempotency-Key`; payments, loan repayments and other confirmations generate
  one key per confirmation, reused for resubmits and the retry after step-up.
- **Token storage**: the access token stays in memory; only the refresh token is stored (Android Keystore AES-GCM /
  iOS Keychain `first_unlock_this_device`, not synced, `allowBackup=false`) and only when the device supports local
  authentication. It is used only after a `local_auth` prompt (biometrics with device-credential fallback). A failed
  refresh deletes it; sign-out and the switch in Security delete it.
- **Certificate pinning hook** (`security/pinning.dart`): `CertificatePinning` supplies the HTTP client; default
  `NoPinning`; `CaPinning` trusts only the given CA certificates (platform roots and user-added CAs refused).
- **Jailbreak / root hook** (`security/integrity.dart`): `DeviceIntegrityCheck`, default `NoopIntegrityCheck`. When a
  check reports a compromised device, the app shows a warning and (by default) refuses money movement and security
  changes before any step-up.
- Session ended elsewhere (`401 session_revoked`, "sign out everywhere" on another device) -> local sign-out and a
  notice on the start screen. `X-Device-Id` (random per installation) feeds new-device alerts.

## API client

Hand-written typed client (`lib/src/api/customer_api.dart`, models in `models.dart`) against
`generated/openapi/services/customer-channel-service.openapi.yaml`. `openapi-generator` (dart-dio) was not used: its
output needs `built_value` + `build_runner` code generation for the whole 13k-line contract (hundreds of models we do
not use). Instead `CustomerApi.operations` lists every (method, path) the app calls and `test/api_contract_test.dart`
fails if one disappears from the contract or a write loses its `Idempotency-Key` parameter. Models map only the members
the screens show; decimals stay strings (exact arithmetic in `core/money.dart`).

## Tests (28)

`api_contract_test` (operations exist, writes idempotent), `api_client_test` (page / ledger / spending / security
mapping, dev headers, Idempotency-Key handling, problem + RFC 9470 challenge mapping, `session_revoked` sign-out),
`step_up_test` (challenge parsing, freshness rules, step-up before the call, 401 -> step-up -> retry with the same key,
no retry for other problems, no second factor, compromised device), `auth_storage_test` (only the refresh token is
stored, only with biometrics; unlock requires the prompt; switch / sign-out delete it; money arithmetic), widget
tests (dev sign-in -> home summary; Kiswahili; transfer validate -> confirm -> step-up -> one payment; card freeze
without step-up and channel blocking; sign out everywhere).

## Gaps

- Not built natively here (no Android SDK / Xcode); iOS and Android runs untested.
- The web build is a smoke build only (dev auth mode: AppAuth has no web implementation).
- Pinning and integrity are hooks: no pinned CA set and no Play Integrity / App Attest / RASP provider is wired.
- Long-lived sessions: the realm has no `offline_access` scope, so biometric unlock works only while the Keycloak SSO
  session is alive (refresh token idle / max lifetime).
- audit-service maps Keycloak events of `meridian-customer-mobile` / `meridian-agent-mobile` to no actor type yet
  (`KeycloakEventMapper` lists the web clients only; services were out of scope).
- Not yet on mobile: standing orders, statements / receipts download, budgets, rewards, KYC documents, profile and
  contact editing, notification preferences, second-factor enrolment (done in web banking), push notifications.
