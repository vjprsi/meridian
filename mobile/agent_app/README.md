# Meridian agent app (Flutter)

Native, offline-first agent outlet app for iOS and Android: the mobile counterpart of `web/agent-app` (same outbox
design and device-key contract). Every call goes to the agent channel `/v1/agent/*` (agent-channel-service). See
`../README.md` for the toolchain, environments and native builds.

```bash
F=/d/DevTools/flutter/bin/flutter
$F pub get && $F analyze && $F test
$F build web --dart-define-from-file=env/dev.json     # smoke build (outbox in memory on web)
```

## Screens

Sign-in (Keycloak `meridian-agent-mobile`, or the dev form: tenant, agent, user), Setup (outlet from
`/v1/agent/me/outlets`, device key generated and registered with `POST /v1/agent/devices`, 6-digit app PIN), Unlock
(app PIN), Home (float balance, online / offline, unsynced count, actions), Cash-in (customer lookup when online,
confirmation, capture), Cash-out (online only: lookup -> start -> customer confirms with PIN or OTP, typed by the
customer and never kept), Bill payment (billers, reference, amount), Float request (top-up / rebalance / withdraw),
History (server transactions and the operations on this device), Sync (pending in capture order, sent / refused,
sync now, remove final records).

## Offline outbox (`lib/src/outbox`)

- **Encrypted local store**: SQLite (`sqflite`) on devices; every record's payload and result are sealed with
  **AES-256-GCM** (fresh 96-bit IV, record id as AAD) under a key derived with **PBKDF2-HMAC-SHA-256** (210,000
  iterations, on a background isolate) from the app PIN and a random per-device salt bound to tenant + agent. The key
  exists only in memory while unlocked; a PIN verifier is stored, never the PIN. 5 wrong PINs lock for 5 minutes
  (doubling), 10 erase the queue and key material. Metadata (order, status, timestamps) is in clear, like the PWA.
- **Idempotency**: each record gets `Idempotency-Key = agentapp-<record id>` at capture and the exact same body on
  every replay.
- **Ordered replay**: pending records are sent strictly in capture order; a record that cannot be decided (network,
  401, PIN needed, request in progress) stops the run so later records never overtake it; runs never overlap.
  `idempotency_key_reused` is resolved with the channel's `idempotency_key` lookups (synced if found, needs review if
  not). Online captures go through the same outbox (sent at once).
- **Limits** offline: per operation 5,000, total 20,000, 10 operations, 12 h since the last successful contact, and
  float debits net of unsynced ones within the last known float. Cash-out is never captured offline.
- The agent wallet PIN, when the server asks (`pin_required`), is asked once and kept in memory for the session
  (cleared on lock and on `invalid_pin`); `PINs must never be stored` is enforced at enqueue.

## Device key (`lib/src/crypto/device_key.dart`)

ES256 key pair; public key registered as SubjectPublicKeyInfo. `DeviceKeyStore` interface with
`PlatformDeviceKeyStore` (method channel `io.meridian.agent/device_key`: Android Keystore with StrongBox when present
in `MainActivity.kt`, Secure Enclave in `AppDelegate.swift`; DER signatures converted to P1363) and the pure-Dart
`SoftwareDeviceKeyStore` (pointycastle, RFC 6979 deterministic signatures, private scalar in secure storage) used when
the platform channel is absent (web, tests, simulator). Money POSTs carry `X-Agent-Device-Proof:
v1.<device>.<epoch s>.<sig>` over the canonical text of agent-channel-service `DeviceKeys.proofPayload`, signed only
while the app is unlocked, so the outbox can replay outside the 5-minute sign-in window.

## Tests (26)

`outbox_test` (no plaintext at rest, AAD binding, per-agent keys, locked outbox, PIN lock-out and wipe, PIN reset
refused while unsynced, PINs never stored, one key per record across replays, strict ordering and stop-at-blocker,
refused records final, duplicates, idempotency_key_reused resolution, invalid_pin clears the PIN, no overlapping
flushes, removal / purge, all offline limits, reconcile table), `device_key_test` (SPKI, P1363 signatures verify,
canonical texts, DER conversion), `controller_test` (contract check against agent-channel-service.openapi.yaml; setup
registers the key; money POSTs carry a proof that verifies with the registered key; no proof while locked; offline
capture queued, replayed in order after `pin_required` with the PIN from memory only), widget tests (sign-in -> setup
-> home -> lock / unlock; offline cash-in -> sync screen -> sync; Kiswahili).

## Gaps

- Native code (keystore channel) not compiled here (no Android SDK / Xcode); devices without it fall back to the
  software key (not hardware-bound). No platform attestation (Play Integrity / App Attest) of the key.
- Web smoke build keeps the outbox in memory (no SQLite on web).
- Not yet on mobile (present in the PWA): airtime top-ups, customer onboarding and offline onboarding batches (the
  `batchPayload` text is implemented), tills, commissions, sub-agents, receipts, device heartbeat and key rotation
  screen, cash requests.
- The sync screen relies on manual "sync now" and captures (no background sync / WorkManager / BGTaskScheduler).
- SQLite metadata (status, timestamps) is not encrypted (sqlcipher would add that).
