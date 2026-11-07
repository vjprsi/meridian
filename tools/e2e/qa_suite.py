#!/usr/bin/env python3
"""Adversarial / negative / corner-case QA suite for the live Meridian stack.
Runs on the box (localhost gateway/Mailpit). Reuses the e2e harness for setup + happy-path helpers,
then runs a battery of negative, logic, error-contract, idempotency, ledger-integrity, isolation and
messaging checks. Writes structured results to /tmp/qa_adversarial.json. Stdlib only.
"""
import json, os, sys, uuid, urllib.request, urllib.error, time
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import journeys as J  # noqa: E402

BASE = J.BASE
RESULTS = []

def rec(cat, name, ok, expected, actual, detail="", severity=None, evidence=None):
    RESULTS.append({"category": cat, "name": name, "ok": bool(ok), "expected": expected,
                    "actual": actual, "detail": detail, "severity": (severity or ("" if ok else "MEDIUM")),
                    "evidence": evidence})
    tag = "PASS" if ok else "FAIL"
    print(f"  [{tag}] {cat} :: {name}  (exp {expected} / got {actual}) {('- '+detail) if detail else ''}")

def raw(method, path, body=None, headers=None, idem=True, tenant=None, user="qa.bot", roles=None):
    """Low-level call returning (status, payload) and NOT raising on error status."""
    url = BASE + path
    h = {"X-Tenant-Id": tenant or API.tenant, "X-User": user, "Accept": "application/json"}
    if roles: h["X-Roles"] = roles
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    if idem and method in ("POST", "PUT", "PATCH"):
        h["Idempotency-Key"] = str(uuid.uuid4())
    if headers: h.update(headers)
    if method == "POST" and data is None:
        data = b"{}"; h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, body_raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, body_raw = e.code, e.read()
    try:
        payload = json.loads(body_raw) if body_raw else None
    except ValueError:
        payload = body_raw.decode(errors="replace")
    return status, payload

def is_problem(p):
    """RFC 9457 problem+json with a stable machine code? returns (ok_shape, code)."""
    if not isinstance(p, dict): return False, None
    code = p.get("code") or p.get("error") or p.get("type")
    has_shape = ("title" in p or "detail" in p) and ("status" in p or True)
    looks_leaky = isinstance(p.get("detail"), str) and ("Exception" in p["detail"] or "\n\tat " in p["detail"] or "org.springframework" in p["detail"])
    return (has_shape and (p.get("code") is not None) and not looks_leaky), (p.get("code") or code)

def neg(cat, name, method, path, body, expect, want_code=None, severity="MEDIUM"):
    expect = (expect,) if isinstance(expect, int) else tuple(expect)
    st, p = raw(method, path, body)
    code_ok, code = is_problem(p)
    status_ok = st in expect
    ok = status_ok and code_ok and (want_code is None or code == want_code)
    detail = f"code={code}"
    if not status_ok: detail += " [WRONG STATUS]"
    if not code_ok: detail += " [not RFC9457 / leaky body]"
    if want_code and code != want_code: detail += f" [wanted code {want_code}]"
    rec(cat, name, ok, "/".join(map(str, expect)), st, detail, severity if not ok else "",
        evidence=(p if not ok else None))
    return st, p

# ---------------- setup (reuse the real starter pack) ----------------
TENANT = os.environ.get("QA_TENANT", str(uuid.uuid4()))
API = J.Api(TENANT)
SHARED = {}
print(f"QA suite via {BASE}  tenant={TENANT}")

def setup():
    j = J.Journey("S", "setup", API, SHARED)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print("SETUP FAILED — aborting"); sys.exit(2)
    return j

SETUPJ = setup()
A = J.ensure_account(SETUPJ, "account_a", "Alice", "Qa")
B = J.ensure_account(SETUPJ, "account_b", "Bob", "Qa")
J.fund_account(SETUPJ, A, "20000.00", "Alice")
LE = API.shared_le
PID, VID = SHARED["deposit_product"]
print("setup done:", {"account_a": A["account_number"], "account_b": B["account_number"]})

def transfer_body(amount, debtor=A, creditor=B, ccy="KES", ref="qa"):
    return {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
            "customer_id": debtor["customer_id"], "debtor_account_id": debtor["id"],
            "creditor_account_id": creditor["id"], "creditor_name": "Bob Qa",
            "amount": amount, "currency": ccy, "reference": ref}

# ================= TESTS =================

# --- A. Error contract / validation (RFC 9457 + stable codes) ---
neg("validation", "create customer with no customer_type", "POST", "/v1/customers",
    {"legal_entity_id": LE, "first_name": "X", "last_name": "Y"}, (400, 422))
neg("validation", "create customer with bad enum (customer_type=ALIEN)", "POST", "/v1/customers",
    {"customer_type": "ALIEN", "legal_entity_id": LE, "first_name": "X", "last_name": "Y"}, (400, 422))
neg("validation", "create customer with future date_of_birth", "POST", "/v1/customers",
    {"customer_type": "INDIVIDUAL", "legal_entity_id": LE, "first_name": "X", "last_name": "Y",
     "date_of_birth": "2999-01-01", "nationality": "KE"}, (400, 422))
neg("validation", "payment negative amount", "POST", "/v1/payments", transfer_body("-100.00"), (400, 422))
neg("validation", "payment zero amount", "POST", "/v1/payments", transfer_body("0.00"), (400, 422))
neg("validation", "payment currency too short (US)", "POST", "/v1/payments", transfer_body("100.00", ccy="US"), (400, 422))
neg("validation", "payment amount too many decimals", "POST", "/v1/payments", transfer_body("1.123456789"), (400, 422))
neg("validation", "malformed UUID in path", "GET", "/v1/customers/not-a-uuid", None, (400, 404))
neg("not_found", "GET non-existent customer (valid uuid)", "GET", f"/v1/customers/{uuid.uuid4()}", None, 404)
# unsupported method
st, p = raw("DELETE", f"/v1/payments/{uuid.uuid4()}", None)
rec("method", "DELETE on payments (unsupported) -> 405/404", st in (404, 405), "404/405", st, f"code={is_problem(p)[1]}", "LOW" if st not in (404,405) else "")

# --- B. Idempotency ---
k = str(uuid.uuid4())
st1, p1 = raw("POST", "/v1/payments", transfer_body("300.00", ref="idem1"), headers={"Idempotency-Key": k}, idem=False)
st2, p2 = raw("POST", "/v1/payments", transfer_body("300.00", ref="idem1"), headers={"Idempotency-Key": k}, idem=False)
same = isinstance(p1, dict) and isinstance(p2, dict) and p1.get("id") == p2.get("id")
rec("idempotency", "replay same key+body returns same payment", same, "same id", f"{st1}/{st2}",
    f"id1={p1.get('id') if isinstance(p1,dict) else p1} id2={p2.get('id') if isinstance(p2,dict) else p2}",
    "HIGH" if not same else "")
# reused key, different body -> 422 idempotency_key_reused
st3, p3 = raw("POST", "/v1/payments", transfer_body("999.00", ref="idem2"), headers={"Idempotency-Key": k}, idem=False)
code3 = is_problem(p3)[1]
rec("idempotency", "reused key + different body -> 422", st3 == 422, "422", st3, f"code={code3}", "HIGH" if st3 != 422 else "")
# missing Idempotency-Key on a write
st4, p4 = raw("POST", "/v1/payments", transfer_body("100.00"), idem=False)
rec("idempotency", "POST without Idempotency-Key is rejected", st4 in (400, 422), "400/422", st4,
    f"code={is_problem(p4)[1]}", "MEDIUM" if st4 not in (400, 422) else "")

# --- C. Money logic ---
def bal(acct): return J.balances(API, acct)
pre = bal(A)
st, p = raw("POST", "/v1/payments", transfer_body("500000.00", ref="overdraw"))
# either validation 422, or created then goes REJECTED/FAILED; balance must not move
outcome = ""
if st in (200, 201) and isinstance(p, dict):
    try:
        fx = J.payment_until(API, p["id"], ("REJECTED", "FAILED", "CANCELLED", "SETTLED", "COMPLETED"), timeout=20)
        outcome = fx["status"]
    except Exception as e:
        outcome = f"timeout:{e}"
post = bal(A)
bal_safe = post[2] == pre[2]
ok_if = (st in (400, 422)) or (outcome in ("REJECTED", "FAILED", "CANCELLED"))
rec("money", "transfer above balance is refused, balance unchanged", ok_if and bal_safe,
    "422 or REJECTED; balance stable", f"{st}/{outcome}", f"ledger {pre[2]}->{post[2]}",
    "CRITICAL" if (outcome in ("SETTLED", "COMPLETED") or not bal_safe) else ("HIGH" if not ok_if else ""))
# debtor == creditor
st, p = raw("POST", "/v1/payments", transfer_body("100.00", creditor=A, ref="selfpay"))
self_ok = st in (400, 422) or (isinstance(p, dict) and p.get("status") in ("REJECTED", "FAILED"))
rec("money", "transfer to same account refused", self_ok, "422 or REJECTED", st, f"code={is_problem(p)[1] if st>=400 else p.get('status') if isinstance(p,dict) else p}", "MEDIUM" if not self_ok else "")

# --- D. Ledger integrity ---
# make a clean 2500 transfer and verify double-entry + cross-service balance consistency
st, pay = raw("POST", "/v1/payments", transfer_body("2500.00", ref="ledgchk"))
if st in (200, 201) and isinstance(pay, dict):
    try:
        fin = J.payment_until(API, pay["id"], ("SETTLED", "COMPLETED"), timeout=25)
        je_id = fin.get("journal_entry_id")
        if je_id:
            je = API.get(f"/v1/journal-entries/{je_id}")
            lines = je.get("lines") or je.get("entries") or []
            td = sum(Decimal(str(l.get("amount", "0"))) for l in lines if (l.get("direction") or l.get("side")) in ("DEBIT", "DR"))
            tc = sum(Decimal(str(l.get("amount", "0"))) for l in lines if (l.get("direction") or l.get("side")) in ("CREDIT", "CR"))
            balanced = td == tc and td > 0
            rec("ledger", "journal entry balances (sum debits == sum credits)", balanced, "debits==credits",
                f"DR {td} / CR {tc}", f"{len(lines)} legs", "CRITICAL" if not balanced else "")
        else:
            rec("ledger", "settled payment carries a journal_entry_id", False, "journal_entry_id", "absent", "", "HIGH")
        # cross-service: deposit posted balance == ledger posted balance
        da = API.get(f"/v1/deposit-accounts/{A['id']}/balance")
        la = API.get(f"/v1/ledger-accounts/{A['ledger_account_id']}/balance")
        consistent = Decimal(str(da.get("posted_balance"))) == Decimal(str(la.get("posted_balance")))
        rec("ledger", "deposit posted balance == ledger posted balance", consistent, "equal",
            f"{da.get('posted_balance')} vs {la.get('posted_balance')}", "", "CRITICAL" if not consistent else "")
    except Exception as e:
        rec("ledger", "ledger integrity after transfer", False, "settled+balanced", "error", str(e)[:300], "HIGH")
# double reversal must not double-post (SEC-05)
try:
    je_id = fin.get("journal_entry_id")
    if je_id:
        r1 = raw("POST", f"/v1/journal-entries/{je_id}/reverse", {"reason": "qa reversal 1"})
        r2 = raw("POST", f"/v1/journal-entries/{je_id}/reverse", {"reason": "qa reversal 2"})
        # second reverse of an already-reversed entry should be 409
        second_blocked = r2[0] in (409, 422)
        rec("ledger", "double reversal blocked (already_reversed)", second_blocked, "409/422 on 2nd",
            f"{r1[0]}/{r2[0]}", f"code={is_problem(r2[1])[1]}", "HIGH" if not second_blocked else "")
except Exception as e:
    rec("ledger", "double reversal guard", False, "409 on 2nd", "error", str(e)[:200], "MEDIUM")

# --- E. Maker-checker / authorization ---
st, p = raw("POST", f"/v1/approval-requests/{uuid.uuid4()}/approve", {"comment": "x"}, roles=J.CHECKER_ROLES)
rec("authz", "approve non-existent approval request -> 404", st == 404, "404", st, f"code={is_problem(p)[1]}", "LOW" if st != 404 else "")

# --- F. Pagination / limits ---
st, p = raw("GET", "/v1/customers?limit=1000", None)
clamped = st == 200 and isinstance(p, dict) and len(p.get("data", [])) <= 200
rec("pagination", "limit=1000 is clamped to <=200", clamped, "<=200 rows, 200", f"{st}/{len(p.get('data',[])) if isinstance(p,dict) else '?'}", "", "LOW" if not clamped else "")
st, p = raw("GET", "/v1/customers?limit=0", None)
rec("pagination", "limit=0 rejected or clamped", st in (200, 400, 422), "200/400/422", st, f"code={is_problem(p)[1] if st>=400 else 'ok'}", "LOW" if st not in (200,400,422) else "")
st, p = raw("GET", "/v1/customers?cursor=not-a-real-cursor", None)
rec("pagination", "bad cursor handled (400/422, not 500)", st in (200, 400, 422), "not 500", st, f"code={is_problem(p)[1] if st>=400 else 'ok'}", "MEDIUM" if st >= 500 else "")

# --- G. Cross-tenant isolation (RLS) ---
other = str(uuid.uuid4())
st, p = raw("GET", f"/v1/customers/{A['customer_id']}", None, tenant=other)
isolated = st == 404
rec("isolation", "customer of tenant A invisible to tenant B (RLS)", isolated, "404", st,
    f"code={is_problem(p)[1] if st>=400 else 'LEAKED'}", "CRITICAL" if not isolated else "")
st, p = raw("GET", f"/v1/deposit-accounts/{A['id']}", None, tenant=other)
rec("isolation", "deposit account of tenant A invisible to tenant B", st == 404, "404", st,
    f"{'LEAKED' if st<400 else is_problem(p)[1]}", "CRITICAL" if st != 404 else "")

# --- H. Corner cases ---
longname = "Z" * 5000
st, p = raw("POST", "/v1/customers", {"customer_type": "INDIVIDUAL", "legal_entity_id": LE,
            "first_name": longname, "last_name": "Qa", "nationality": "KE"}, )
rec("corner", "over-long name (5000 chars) rejected by maxLength", st in (400, 422), "400/422", st,
    f"code={is_problem(p)[1]}", "LOW" if st not in (400, 422) else "")
st, p = raw("POST", "/v1/customers", {"customer_type": "INDIVIDUAL", "legal_entity_id": LE,
            "first_name": "Zoé-Łukasz 王", "last_name": "Qa", "nationality": "KE",
            "residence_country": "KE", "tax_residence_country": "KE", "gender": "UNDISCLOSED",
            "employment_status": "EMPLOYED", "occupation": "Eng", "onboarding_channel": "API",
            "preferred_language": "en", "external_ref": f"qa-{uuid.uuid4().hex[:8]}"})
uni_ok = st in (200, 201) and isinstance(p, dict) and p.get("first_name") == "Zoé-Łukasz 王"
rec("corner", "unicode name round-trips", uni_ok, "201 + exact echo", st, "", "MEDIUM" if not uni_ok else "")
# duplicate unique: product code twice
dupcode = f"DUP{J.run_id()}"
r1 = raw("POST", "/v1/products", {"code": dupcode, "name": "dup", "family": "SAVINGS", "legal_entity_id": LE, "currency": "KES"})
r2 = raw("POST", "/v1/products", {"code": dupcode, "name": "dup2", "family": "SAVINGS", "legal_entity_id": LE, "currency": "KES"})
dup_ok = r2[0] in (409, 422)
rec("corner", "duplicate product code rejected (unique)", dup_ok, "409/422", f"{r1[0]}/{r2[0]}",
    f"code={is_problem(r2[1])[1]}", "MEDIUM" if not dup_ok else "")

# --- I. Messages / notifications / emails (SEC-10 redaction + delivery) ---
# the B-journey payment fired a payment alert; check notifications for the customer
def check_notifications():
    data = API.get(f"/v1/notifications?recipient_id={A['customer_id']}&limit=50").get("data", [])
    pay_alerts = [n for n in data if (n.get("source_event_type") or "").startswith("payments.")]
    channels = sorted({f"{n.get('channel')}:{n.get('status')}" for n in data})
    # redaction: no 6-digit OTP visible in any stored body
    leak = [n for n in data if isinstance(n.get("body"), str) and __import__("re").search(r"\bOTP\b.*\d{6}|\b\d{6}\b", n.get("body",""))
            and (n.get("category") == "SECURITY" or "code" in (n.get("subject","").lower()))]
    return data, pay_alerts, channels, leak
try:
    J.wait_for("payment notification", lambda: (lambda d: d[1] or None)(check_notifications()), timeout=20)
except Exception:
    pass
data, pay_alerts, channels, leak = check_notifications()
rec("messaging", "payment alert notification generated for customer", len(pay_alerts) > 0,
    ">=1 payments.* alert", f"{len(pay_alerts)} alerts", f"channels={channels}", "MEDIUM" if not pay_alerts else "")
rec("messaging", "no live OTP leaked in stored notification bodies (SEC-10)", len(leak) == 0,
    "0 leaks", f"{len(leak)} leaks", "", "CRITICAL" if leak else "")
# Mailpit: a real e-mail was delivered to the customer during onboarding
try:
    import re as _re
    base = os.environ.get("MERIDIAN_MAILPIT_URL", "http://localhost:18025")
    with urllib.request.urlopen(f"{base}/api/v1/messages?limit=50", timeout=10) as r:
        msgs = json.loads(r.read()).get("messages", [])
    rec("messaging", "emails delivered to Mailpit during journeys", len(msgs) > 0, ">=1 email", f"{len(msgs)} in Mailpit", "", "MEDIUM" if not msgs else "")
except Exception as e:
    rec("messaging", "Mailpit reachable / emails delivered", False, ">=1 email", "error", str(e)[:200], "MEDIUM")

# ---------------- summary ----------------
fails = [r for r in RESULTS if not r["ok"]]
bycat = {}
for r in RESULTS:
    bycat.setdefault(r["category"], [0, 0])
    bycat[r["category"]][0 if r["ok"] else 1] += 1
print("\n== QA ADVERSARIAL SUMMARY ==")
print(f"total {len(RESULTS)}  pass {len(RESULTS)-len(fails)}  FAIL {len(fails)}")
for c, (pz, fz) in sorted(bycat.items()):
    print(f"  {c:12} pass {pz}  fail {fz}")
if fails:
    print("\n-- FAILURES --")
    for r in fails:
        print(f"  [{r['severity']}] {r['category']} :: {r['name']}  (exp {r['expected']} got {r['actual']}) {r['detail']}")
open("/tmp/qa_adversarial.json", "w").write(json.dumps({"tenant": TENANT, "results": RESULTS}, indent=2, default=str))
print("\nwrote /tmp/qa_adversarial.json")
