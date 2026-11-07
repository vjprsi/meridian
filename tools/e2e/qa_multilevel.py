#!/usr/bin/env python3
"""Clean classification of the multi-level approval -> execution path: a 2-approver payment WITH sufficient funds
must SETTLE after both levels are approved. If it settles, the path works (original DEFECT-003 was a test artifact);
if it hangs, there is a real multi-level-execution defect."""
import os, sys, uuid
from decimal import Decimal
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import journeys as J

TENANT = sys.argv[1] if len(sys.argv) > 1 else "e579d080-5048-4b54-b0a0-ec227669e59c"
api = J.Api(TENANT)

accts = [a for a in api.get("/v1/deposit-accounts?limit=30").get("data", [])
         if a.get("status") == "ACTIVE" and a.get("ledger_account_id")]
if len(accts) < 2:
    print("need 2 active accounts, found", len(accts)); sys.exit(2)
a, b = accts[0], accts[1]
print("debtor", a["account_number"], "creditor", b["account_number"])

def ledbal(acct): return Decimal(str(api.get(f"/v1/ledger-accounts/{acct['ledger_account_id']}/balance").get("posted_balance")))

# fund debtor generously
api.post("/v1/payments/inbound", {"rail_code": "E2E-RTGS", "end_to_end_id": f"E2E{uuid.uuid4().hex[:18]}",
         "amount": "600000.00", "currency": "KES", "debtor_name": "Employer", "debtor_account_number": "1234567890",
         "creditor_account_number": a["account_number"], "remittance_unstructured": "fund"})
J.wait_for("funded", lambda: ledbal(a) >= Decimal("600000") or None, timeout=25)
pa0, pb0 = ledbal(a), ledbal(b)
print("balances before: debtor", pa0, "creditor", pb0)

# 2-level payment (300k should require 2 approvers like 500k did; within balance so it can settle)
pay = api.post("/v1/payments", {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "WEB",
       "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
       "creditor_name": "Creditor", "amount": "300000.00", "currency": "KES", "reference": "qa 2-level"})
pid = pay["id"]
try: J.fraud_cleared(api, pid)
except Exception as e: print("fraud note:", str(e)[:120])
x = J.payment_until(api, pid, ("PENDING_APPROVAL", "SETTLED", "COMPLETED"), timeout=20)
levels = x.get("status")
print("after create:", levels)
hist = api.get(f"/v1/payments/{pid}/status-history").get("data", [])
lvl_note = next((h.get("detail") for h in hist if "level" in (h.get("detail") or "")), "")
print("approval note:", lvl_note)
if x["status"] == "PENDING_APPROVAL":
    try:
        J.approve_via_inbox(api, "payment", pid)
    except Exception as e:
        print("approve_via_inbox:", str(e)[:200])
try:
    fin = J.payment_until(api, pid, ("SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED"), timeout=40)
    st = fin["status"]
except Exception as e:
    st = f"stuck:{str(e)[:120]}"
pa1, pb1 = ledbal(a), ledbal(b)
print("final status:", st, "| balances after: debtor", pa1, "creditor", pb1)
moved = (pb1 - pb0) == Decimal("300000") and (pa0 - pa1) == Decimal("300000")
if st in ("SETTLED", "COMPLETED") and moved:
    print("VERDICT: OK — multi-level approval executes correctly (DEFECT-003 was a test artifact)")
elif st in ("SETTLED", "COMPLETED"):
    print("VERDICT: SETTLED but balances off:", pa0, "->", pa1, ";", pb0, "->", pb1)
else:
    print("VERDICT: DEFECT — multi-level approved payment did not execute (status", st, ")")
