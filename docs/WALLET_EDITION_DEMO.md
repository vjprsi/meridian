# Meridian Core — Wallet Edition demo

> How to stand up and demo the **Wallet Edition** (the mobiquity Pay equivalent) on the local stack.
> See `docs/EDITIONS_AND_MOBIQUITY_ALIGNMENT.md` for the edition model and gap analysis, and
> `docs/research/MOBIQUITY_PAY_V12_FDD.md` for the mobiquity capability catalogue the demo is measured against.

## What the demo shows

A single demo tenant provisioned from the **country-neutral, English-first `WALLET` starter pack**
(`services/tenant-service/src/main/resources/starter-packs/wallet-2026.11.0.yaml`), running the mobile-money
lifecycle end to end against the running services through the edge gateway.

The Wallet Edition deployment profile is `deploy/editions/values-wallet.yaml` (51 services on, 17 off — accounts,
cards, lending, treasury and open banking are off by default; see `generated/editions/wallet.json`).

## Prerequisites (local stack)

Same recipe as the main e2e run (see `docs/DEV_TOOLS.md` / README). On this machine the Docker engine runs inside
WSL, so start a keep-alive first, then the infra and the services:

```bash
# 1. keep WSL/dockerd alive (background) — see memory: local-dev-environment
wsl.exe -d Ubuntu-22.04 -e sleep infinity &

# 2. infra (Postgres, Kafka, Keycloak, Redis, OpenSearch, Grafana, Mailpit, SeaweedFS)
#    (run inside WSL against infra/local docker-compose)
docker compose -f infra/local/docker-compose.yml up -d

# 3. the services + edge gateway (phase 4 = all; the Wallet Edition subset is enough but phase 4 is simplest)
python tools/dev.py up --phase 4
```

Gateway: `http://localhost:18000` · Mailpit: `http://localhost:18025` · SMTP: `localhost:11025`.

> Note: booting the full service set is CPU-heavy. Prefer running this when you can watch the machine.

## Run the demo

```bash
# setup (WALLET pack) + wallet journey + end of day
tools/demo/wallet-demo.sh                 # = MERIDIAN_E2E_STARTER_PACK=WALLET python tools/e2e/journeys.py S D G

# or the full retail+wallet set
tools/demo/wallet-demo.sh S A B C D G
```

PowerShell equivalent:

```powershell
$env:MERIDIAN_E2E_STARTER_PACK = "WALLET"
python tools/e2e/journeys.py S D G
```

`S` provisions a fresh tenant from the WALLET pack (legal entity, ledger chart + e-money system accounts,
per-service ledger routing, treasury + commission wallets, wallet products, KYC-tier limits, tariffs, agent
commission plan, notifications, EOD pack, approval policies — maker-checker items approved as the checker). Re-runs
are safe (new codes per run); reuse a tenant with `--tenant <uuid>`.

## Journey map vs the 12-step Wallet MVP script

The MVP demo script is defined in `docs/EDITIONS_AND_MOBIQUITY_ALIGNMENT.md` §7.1. Status against the current build:

| # | MVP step | e2e journey | Status |
|---|---|---|---|
| 1 | Operator setup from wallet starter pack | `S` | ✅ live |
| 2 | Float supply chain (treasury → agent float, O2C) | `D` (treasury fund, agent float top-up via inbox) | ✅ live (operator stock/O2C hardening = WE-FLOAT-1) |
| 3 | Customer onboarding + KYC tier upgrade | `A` + wallet tier limits | ✅ live |
| 4 | Cash-in at agent + commission | `D` (cash-in via agent) | ✅ live |
| 5 | Cash-in to unregistered → claim (passcode) | — | ⛔ Wave 2 (WE-TXN-1) |
| 6 | P2P (registered) + alias | `D` (P2P with PIN) | ✅ live |
| 7 | Bill & airtime | (biller/airtime ops exist) | 🟡 add steps |
| 8 | Merchant QR pay + commission sharing | (merchant QR ops exist) | 🟡 add steps / WE-PRICE-1 |
| 9 | Cash-out at agent (PIN) | `D` (cash-out with PIN confirm) | ✅ live |
| 9b | ATM cash-out (OTP, auto-rollback) | — | ⛔ Wave 2 (WE-TXN-2) |
| 10 | Order & Pay resilience (ambiguous settlement) | — | ⛔ Wave 2 (WE-ETM-1) |
| 11 | Reverse a mis-send (correction + dispute) | — | ⛔ Wave 2 (WE-OPS-1) |
| 12 | EOD trust / e-money safeguarding reconciliation | `G` (EOD) + WE-RECON-1 | 🟡 EOD live; safeguarding recon = WE-RECON-1 |

**Live today:** S, A, B, C, D (wallet), G (EOD) — the core onboard → float → cash-in → P2P → cash-out → EOD loop
runs on the Wallet Edition profile. The ⛔ steps depend on Wave 1/2 gap closures (`WE-*` in `spec/plan.yaml`).
