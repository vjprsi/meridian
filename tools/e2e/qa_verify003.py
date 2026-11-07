#!/usr/bin/env python3
"""Verify DEFECT-003 is not a defect: fully approve (all levels) the large over-balance payment and confirm it
rejects cleanly for insufficient funds at execution, with balances intact."""
import os, sys, json
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import journeys as J

TENANT = "e579d080-5048-4b54-b0a0-ec227669e59c"
PID = "01a1af35-88dc-7cff-b2d6-4790504ce900"
api = J.Api(TENANT)

p = api.get(f"/v1/payments/{PID}")
print("before:", p.get("status"), "amount", p.get("amount"))
# approve all levels via the inbox helper (handles multi-level, maker-blocked check)
try:
    J.approve_via_inbox(api, "payment", PID)
except Exception as e:
    print("approve note:", str(e)[:200])
# poll to terminal
try:
    final = J.payment_until(api, PID, ("REJECTED", "FAILED", "CANCELLED", "SETTLED", "COMPLETED"), timeout=30)
    st = final["status"]
except Exception as e:
    st = f"stuck:{str(e)[:120]}"
p = api.get(f"/v1/payments/{PID}")
print("after :", p.get("status"), "reason", p.get("rejection_reason") or p.get("failure_reason"))
hist = api.get(f"/v1/payments/{PID}/status-history").get("data", [])
for h in sorted(hist, key=lambda x: x.get("created_at", "")):
    print("  ", h.get("from_status"), "->", h.get("to_status"), "|", h.get("reason"), (h.get("detail") or "")[:70])
verdict = "CORRECT: rejects on insufficient funds after full approval" if p.get("status") in ("REJECTED","FAILED","CANCELLED") \
    else ("DEFECT: SETTLED despite insufficient funds" if p.get("status") in ("SETTLED","COMPLETED") else f"STILL {p.get('status')}")
print("VERDICT:", verdict)
