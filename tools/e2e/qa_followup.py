#!/usr/bin/env python3
"""Focused follow-up: money-integrity of insufficient-funds, and list-limit validation scope."""
import json, os, sys, uuid
from decimal import Decimal
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import journeys as J

API = J.Api(str(uuid.uuid4())); SHARED = {}
print("followup tenant", API.tenant)
sj = J.Journey("S", "setup", API, SHARED); J.JOURNEYS["S"][1](sj)
if not sj.ok: print("SETUP FAILED"); sys.exit(2)
LE = API.shared_le
A = J.ensure_account(sj, "account_a", "Alice", "Fu")   # will fund
B = J.ensure_account(sj, "account_b", "Bob", "Fu")
# a fresh UNFUNDED but ACTIVE account owned by Alice's customer (helper waits for approval + activates)
cidA = A["customer_id"]
unf = J.open_deposit_account(sj, cidA, " Unf")
J.fund_account(sj, A, "20000.00", "Alice")

def raw(method, path, body=None):
    return J.Api.call.__wrapped__ if False else None
def call(method, path, body=None, user=J.MAKER, roles=None, expect=None):
    try:
        return API.call(method, path, body, user=user, roles=roles,
                        expect=expect or (200,201,202,204,400,404,409,422))
    except J.StepFailed as e:
        return {"_error": str(e)}

out = {}

# T1: sub-threshold transfer from an UNFUNDED account -> should be rejected at initiation (insufficient funds), not approval
def tb(src, amt, ref):
    return {"payment_type":"INTERNAL_TRANSFER","direction":"INTERNAL","channel":"API","customer_id":src["customer_id"],
            "debtor_account_id":src["id"],"creditor_account_id":B["id"],"creditor_name":"Bob Fu","amount":amt,"currency":"KES","reference":ref}
import urllib.request, urllib.error
def post_raw(path, body):
    req = urllib.request.Request(J.BASE+path, data=json.dumps(body).encode(), method="POST",
        headers={"X-Tenant-Id":API.tenant,"X-User":J.MAKER,"Content-Type":"application/json","Accept":"application/json","Idempotency-Key":str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return r.status, json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b'null')
        except Exception: return e.code, None

st, p = post_raw("/v1/payments", tb(unf, "5000.00", "insuf-subthreshold"))
status_after = None
if st in (200,201) and isinstance(p, dict):
    try:
        x = J.payment_until(API, p["id"], ("REJECTED","FAILED","CANCELLED","SETTLED","COMPLETED"), timeout=20); status_after = x["status"]
    except Exception as e: status_after = f"stuck:{str(e)[:120]}"
else:
    status_after = f"http{st}:{(p or {}).get('code') if isinstance(p,dict) else p}"
out["T1_subthreshold_insufficient"] = {"http": st, "resolved": status_after,
    "verdict": "OK" if (st in (400,422) or status_after in ("REJECTED","FAILED","CANCELLED")) else "DEFECT: not rejected"}
print("T1", out["T1_subthreshold_insufficient"])

# T2: large over-balance transfer (above approval threshold) -> approve -> must NOT settle (would create money)
preA = J.balances(API, A); preB = J.balances(API, B)
st, p = post_raw("/v1/payments", tb(A, "500000.00", "insuf-approve"))
t2 = {"http": st}
if st in (200,201) and isinstance(p, dict):
    pidp = p["id"]
    try:
        J.payment_until(API, pidp, ("PENDING_APPROVAL",), timeout=20)
        req = J.find_pending_approval(API, "payment", pidp)
        # checker approves
        API.post(f"/v1/approval-requests/{req['id']}/approve", {"comment":"qa approve over-balance"}, user=J.CHECKER)
        try:
            fx = J.payment_until(API, pidp, ("SETTLED","COMPLETED","REJECTED","FAILED","CANCELLED"), timeout=25); t2["after_approval"]=fx["status"]
        except Exception as e: t2["after_approval"]=f"stuck:{str(e)[:120]}"
    except Exception as e:
        t2["flow_error"]=str(e)[:200]
else:
    t2["init"]=f"http{st}:{(p or {}).get('code') if isinstance(p,dict) else p}"
postA = J.balances(API, A); postB = J.balances(API, B)
t2["alice_ledger"]=f"{preA[2]}->{postA[2]}"; t2["bob_ledger"]=f"{preB[2]}->{postB[2]}"
money_created = postB[2] > preB[2]  # Bob should NOT be credited from an unfunded-beyond-balance transfer
t2["verdict"]="CRITICAL: money created / settled on insufficient funds" if (t2.get("after_approval") in ("SETTLED","COMPLETED") or money_created) else "OK: not settled, balances intact"
out["T2_overbalance_approved"]=t2
print("T2", t2)

# T3: list-limit validation scope across endpoints
def getst(path):
    req = urllib.request.Request(J.BASE+path, headers={"X-Tenant-Id":API.tenant,"X-User":J.MAKER,"Accept":"application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r: return r.status
    except urllib.error.HTTPError as e: return e.code
eps = ["/v1/customers","/v1/deposit-accounts","/v1/payments","/v1/ledger-accounts","/v1/products"]
out["T3_limit_validation"] = {ep: {"limit=1000": getst(ep+"?limit=1000"), "limit=0": getst(ep+"?limit=0"),
                                   "limit=50": getst(ep+"?limit=50")} for ep in eps}
print("T3", json.dumps(out["T3_limit_validation"]))

open("/tmp/qa_followup.json","w").write(json.dumps(out, indent=2, default=str))
print("\nwrote /tmp/qa_followup.json")
