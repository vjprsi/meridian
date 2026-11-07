#!/usr/bin/env python3
"""Mobile app surface: consume every /v1/me/* service as an authenticated customer (the customer-channel BFF the
Flutter app talks to). Confirms each endpoint is reachable (no 5xx, no leak), own-data only, and that writes need
step-up. Run ON the box: python3.12 tools/e2e/qa_mobile.py"""
from __future__ import annotations
import json, sys, time, uuid

import journeys as J
from journeys import Api, Journey, StepFailed, ensure_account, fund_account

ANY = tuple(range(200, 600))
G, R, Y, DIM, RST = ("\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 5
PASS = 0; TOTAL = 0; FAILS = []; NOTES = []
LEAK = ("java.", "jakarta.", "org.springframework", "\n\tat ", " at io.meridian", "NullPointer", "PSQLException")

# every collection the mobile app consumes from the /v1/me BFF
ME_GET = ["profile", "accounts", "cards", "wallets", "loans", "payments", "payees", "contacts", "addresses",
          "devices", "sessions", "security", "kyc", "documents", "notifications", "notification-preferences",
          "inbox", "activity", "insights", "budgets", "rewards", "announcements", "billers", "bill-payments",
          "airtime-topups", "merchant-payments", "money-requests", "standing-orders", "qr"]


def me(cid, step_up=False):
    h = {"X-Customer-Id": cid}
    if step_up:
        h["X-Auth-Time"] = str(int(time.time())); h["X-Acr"] = "2"; h["X-Amr"] = "pwd,otp"
    return h


def leaky(p): s = p if isinstance(p, str) else json.dumps(p); return any(m in s for m in LEAK)
def codeof(p): return p.get("code") if isinstance(p, dict) else "?"


def raw(api, method, path, headers, body=None):
    p = api.call(method, path, body, expect=ANY, headers=headers)
    return api.latencies[-1][3], p


def rec(name, ok, detail, note=False):
    global PASS, TOTAL
    TOTAL += 1
    if ok: PASS += 1; print(f"  {G}ok{RST}   {name}  {DIM}{detail}{RST}")
    elif note: NOTES.append((name, detail)); print(f"  {Y}note{RST} {name}  {Y}{detail}{RST}")
    else: FAILS.append((name, detail)); print(f"  {R}FAIL{RST} {name}  {R}{detail}{RST}")


def main():
    api = Api(str(uuid.uuid4())); shared = {}
    print("provisioning tenant via journey S …")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok: print("SETUP FAILED"); return 2
    a = ensure_account(j, "m_a", "Mobile", "Aisha")
    b = ensure_account(j, "m_b", "Mobile", "Ben")
    fund_account(j, a, "5000.00", "Mobile Aisha")
    ca, cb = shared["cust_m_a"], shared["cust_m_b"]
    print(f"\ncustomer A={ca}\n")

    print("== 1. Mobile BFF: GET every /v1/me/* collection as the authenticated customer ==")
    for coll in ME_GET:
        st, p = raw(api, "GET", f"/v1/me/{coll}?limit=20", me(ca))
        if st >= 500 or leaky(p):
            rec(f"GET /v1/me/{coll}", False, f"HTTP {st} {codeof(p)}{' LEAK' if leaky(p) else ''}")
        elif 200 <= st < 300:
            n = len(p.get("data", [])) if isinstance(p, dict) and isinstance(p.get("data"), list) else "obj"
            rec(f"GET /v1/me/{coll}", True, f"HTTP {st} ({n} rows)")
        elif st in (401, 403):
            rec(f"GET /v1/me/{coll}", True, f"HTTP {st} {codeof(p)} (auth/scope gate — reachable)", note=True)
        else:
            rec(f"GET /v1/me/{coll}", True, f"HTTP {st} {codeof(p)}")

    print("\n== 2. Own-data isolation (customer B must not see A's resources) ==")
    st, p = raw(api, "GET", f"/v1/me/accounts/{a['id']}", me(cb))
    rec("B cannot read A's account", st == 404, f"HTTP {st} {codeof(p)}")
    st, p = raw(api, "GET", f"/v1/me/accounts/{a['id']}/balance", me(cb))
    rec("B cannot read A's balance", st == 404, f"HTTP {st} {codeof(p)}")
    st, p = raw(api, "GET", f"/v1/me/accounts/{a['id']}", me(ca))
    rec("A can read A's account", 200 <= st < 300, f"HTTP {st}")

    print("\n== 3. Write requires step-up (second factor) ==")
    transfer = {"kind": "OWN_TRANSFER", "from_account_id": a["id"], "to_account_id": a["id"],
                "amount": "10.00", "reference": "mobile"}
    st, p = raw(api, "POST", "/v1/me/payments", me(ca), transfer)
    rec("payment WITHOUT step-up -> 401 step_up_required", st == 401 and codeof(p) == "step_up_required",
        f"HTTP {st} {codeof(p)}")

    print(f"\n{'='*64}\nMOBILE QA: {PASS}/{TOTAL} ok, {len(FAILS)} FAIL, {len(NOTES)} note")
    if FAILS:
        print(f"\n{R}FAILURES:{RST}")
        for n, d in FAILS: print(f"  - {n}: {d}")
    if NOTES:
        print(f"\n{Y}NOTES:{RST}")
        for n, d in NOTES: print(f"  - {n}: {d}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
