#!/usr/bin/env bash
# Meridian Core — Wallet Edition demo tenant (WE-PKG-3).
#
# Provisions a fresh demo tenant from the country-neutral WALLET starter pack and runs the wallet-relevant
# end-to-end journeys against a running local stack. See docs/WALLET_EDITION_DEMO.md for prerequisites
# (WSL keep-alive, docker compose, `python tools/dev.py up --phase 4`).
#
# Usage:
#   tools/demo/wallet-demo.sh                 # setup + wallet + EOD (S D G)
#   tools/demo/wallet-demo.sh S A B C D G     # pick journeys
#   MERIDIAN_E2E_STARTER_PACK=WALLET ...       # already the default here
set -euo pipefail
cd "$(dirname "$0")/../.."

export MERIDIAN_E2E_STARTER_PACK="${MERIDIAN_E2E_STARTER_PACK:-WALLET}"
JOURNEYS=("$@")
if [ ${#JOURNEYS[@]} -eq 0 ]; then
  JOURNEYS=(S D G)
fi

echo "Meridian Core — Wallet Edition demo"
echo "  starter pack : ${MERIDIAN_E2E_STARTER_PACK}"
echo "  gateway      : ${MERIDIAN_GATEWAY_URL:-http://localhost:18000}"
echo "  journeys     : ${JOURNEYS[*]}"
echo

exec python3.12 tools/e2e/journeys.py "${JOURNEYS[@]}"
