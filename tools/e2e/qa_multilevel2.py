#!/usr/bin/env python3
"""Decisive multi-level test: approve EACH successive per-level workflow request (with distinct approvers) until the
payment is terminal. If it SETTLES -> system correct (the earlier 'defect' was a test harness gap). If it still hangs
after every pending request is approved -> real defect."""
import os, sys, time, uuid
from decimal import Decimal
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import journeys as J

TENANT = sys.argv[1] if len(sys.argv) > 1 else "e579d080-5048-4b54-b0a0-ec227669e59c"
api = J.Api(TENANT)
RH = "approver,checker,admin,ops,risk,credit,finance,compliance,treasury"

accts = [a for a in api.get("/v1/deposit-accounts?limit=30").get("data", [])
         if a.get("status") == "ACTIVE" and a.get("ledger_account_id")]
a, b = accts[0], accts[1]
def ledbal(x): return Decimal(str(api.get(f"/v1/ledger-accounts/{x['ledger_account_id']}/balance").get("posted_balance")))
# ensure debtor funded
if ledbal(a) < Decimal("400000"):
    api.post("/v1/payments/inbound", {"rail_code": "E2E-RTGS", "end_to_end_id": f"E2E{uuid.uuid4().hex[:18]}",
             "amount": "600000.00", "currency": "KES", "debtor_name": "Emp", "debtor_account_number": "1234567890",
             "creditor_account_number": a["account_number"], "remittance_unstructured": "fund"})
    J.wait_for("funded", lambda: ledbal(a) >= Decimal("400000") or None, timeout=25)
pa0, pb0 = ledbal(a), ledbal(b)
pay = api.post("/v1/payments", {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "WEB",
       "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
       "creditor_name": "Creditor", "amount": "300000.00", "currency": "KES", "reference": "qa 2-level v2"})
pid = pay["id"]
try: J.fraud_cleared(api, pid)
except Exception as e: print("fraud:", str(e)[:100])
print("created", pid, "before balances", pa0, pb0)

approver_n = 0
for rnd in range(1, 10):
    p = api.get(f"/v1/payments/{pid}")
    st = p.get("status")
    print(f"round {rnd}: payment status {st}")
    if st != "PENDING_APPROVAL":
        break
    # find a pending workflow approval request for this payment
    reqs = api.get(f"/v1/approval-requests?status=PENDING&resource_type=payment&resource_id={pid}&limit=5", user="qa.scan", roles=RH).get("data", [])
    if not reqs:
        time.sleep(2); continue  # next level's request may be opening
    req = reqs[0]; rid = req["id"]
    # approve this request fully with distinct fresh approvers
    for k in range(1, 6):
        approver_n += 1
        out = api.call("POST", f"/v1/approval-requests/{rid}/approve", {"comment": f"qa L{rnd} a{k}"},
                       user=f"qa.ap{approver_n}", roles=RH, expect=(200, 201, 202))
        s = out.get("status")
        print(f"    approve {rid[:8]} as qa.ap{approver_n} -> {s}")
        if s in ("APPROVED", "EXECUTED"):
            break
    time.sleep(2)

try:
    fin = J.payment_until(api, pid, ("SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED"), timeout=30)
    st = fin["status"]
except Exception as e:
    st = f"stuck:{str(e)[:100]}"
pa1, pb1 = ledbal(a), ledbal(b)
print("FINAL:", st, "| debtor", pa0, "->", pa1, "| creditor", pb0, "->", pb1)
moved = (pb1 - pb0) == Decimal("300000") and (pa0 - pa1) == Decimal("300000")
print("VERDICT:", "OK — multi-level executes and ledger moved correctly (no defect)" if (st in ("SETTLED","COMPLETED") and moved)
      else f"DEFECT — multi-level still not executing (status {st}, moved={moved})")
