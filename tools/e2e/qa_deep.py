#!/usr/bin/env python3.12
"""Deep correctness suite: amount/precision/currency/enum/injection fuzz on real resources,
REAL-ID cross-tenant isolation (two provisioned tenants), deep idempotency, and concurrency/races
(double-spend, same-key concurrent creates). Reuses the e2e harness. Stdlib only."""
import json, os, sys, uuid, time, urllib.request, urllib.error, threading
from decimal import Decimal
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import journeys as J  # noqa: E402

BASE = J.BASE
RESULTS = []
_lock = threading.Lock()


def rec(cat, name, ok, expected, actual, detail="", severity=None):
    with _lock:
        RESULTS.append({"cat": cat, "name": name, "ok": bool(ok), "expected": expected,
                        "actual": str(actual), "detail": detail,
                        "severity": (severity or ("" if ok else "MEDIUM"))})
    print(f"  [{'PASS' if ok else 'FAIL'}] {cat} :: {name}  (exp {expected} / got {actual}) {('- '+detail) if detail else ''}")


def raw(api, method, path, body=None, key=None, drop_tenant=False):
    url = BASE + path
    h = {"X-User": "qa.deep", "Accept": "application/json", "X-Roles": J.CHECKER_ROLES}
    if not drop_tenant:
        h["X-Tenant-Id"] = api.tenant
    data = None
    if body is not None:
        data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    if method in ("POST", "PUT", "PATCH"):
        h["Idempotency-Key"] = key or str(uuid.uuid4())
        if data is None:
            data = b"{}"; h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, (json.loads(r.read() or b"null"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None
    except Exception as e:
        return 0, f"TRANSPORT:{e}"


def code_of(p):
    return p.get("code") if isinstance(p, dict) else None


def leaky(p):
    s = json.dumps(p) if not isinstance(p, str) else p
    return any(m in s for m in ("\n\tat ", "org.springframework", "java.lang.", "Exception", "SQLException"))


def no5xx(cat, name, st, p, severity="HIGH"):
    """Record a failure iff the endpoint 5xx'd or leaked internals; else pass."""
    bad = (st == 0) or (500 <= st <= 599) or leaky(p)
    rec(cat, name, not bad, "4xx, no 5xx/leak", st,
        f"code={code_of(p)}" + (" LEAK" if leaky(p) else ""), severity if bad else "")
    return not bad


# ----------------------------------------------------------------------------- setup two tenants
def setup_tenant(name):
    t = str(uuid.uuid4())
    api = J.Api(t)
    sh = {}
    j = J.Journey("S", name, api, sh)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print(f"SETUP {name} FAILED"); sys.exit(2)
    return api, j, sh


print(f"DEEP suite via {BASE}")
apiA, jA, shA = setup_tenant("TenantA")
A = J.ensure_account(jA, "a", "Alice", "Deep")
B = J.ensure_account(jA, "b", "Bob", "Deep")
J.fund_account(jA, A, "20000.00", "Alice")
print("Tenant A ready:", {"A": A["account_number"], "B": B["account_number"]})
apiB, jB, shB = setup_tenant("TenantB")
C = J.ensure_account(jB, "c", "Carol", "Deep")
print("Tenant B ready:", {"C": C["account_number"]})


def tbody(amount, debtor=A, creditor=B, ccy="KES", ref="deep", **over):
    b = {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
         "customer_id": debtor["customer_id"], "debtor_account_id": debtor["id"],
         "creditor_account_id": creditor["id"], "creditor_name": "Bob Deep",
         "amount": amount, "currency": ccy, "reference": ref}
    b.update(over)
    return b


# ============================================================ 1. AMOUNT / PRECISION boundary matrix
print("\n== 1. amount/precision/currency boundary matrix (payments) ==")
INVALID_AMOUNTS = ["-100.00", "-0.00000001", "0", "0.00", "0.000000001", "1.123456789",
                   "abc", "NaN", "Infinity", "-Infinity", "1e5", "1,000.00", "", " 100 ",
                   ".5", "100.", "1" * 40, "0x10", "100.00.00", None, "99999999999999999999999.99"]
for a in INVALID_AMOUNTS:
    st, p = raw(apiA, "POST", "/v1/payments", tbody(a, ref="amt"))
    ok_status = st in (400, 422)
    bad5 = (500 <= st <= 599) or st == 0 or leaky(p)
    rec("amount", f"invalid amount {a!r} rejected", ok_status and not bad5, "400/422",
        st, f"code={code_of(p)}", "HIGH" if bad5 else ("MEDIUM" if not ok_status else ""))
VALID_AMOUNTS = ["0.00000001", "1", "100", "100.00", "0.01", "12345.67890123"[:13]]
for a in VALID_AMOUNTS:
    st, p = raw(apiA, "POST", "/v1/payments", tbody(a, ref="vamt"))
    ok = st in (200, 201, 202)
    rec("amount", f"valid amount {a!r} accepted", ok, "2xx", st, f"code={code_of(p)}",
        "MEDIUM" if not ok else "")
CCYS_BAD = ["US", "KESX", "kes", "XXX", "", "123", "K", "USDD", None, "ke s"]
for c in CCYS_BAD:
    st, p = raw(apiA, "POST", "/v1/payments", tbody("100.00", ccy=c, ref="ccy"))
    ok_status = st in (400, 422)
    bad5 = (500 <= st <= 599) or st == 0 or leaky(p)
    rec("currency", f"invalid currency {c!r} rejected", ok_status and not bad5, "400/422", st,
        f"code={code_of(p)}", "HIGH" if bad5 else ("MEDIUM" if not ok_status else ""))

# ============================================================ 2. ENUM fuzz
print("\n== 2. enum fuzz ==")
for field, bad in [("payment_type", ["ALIEN", "", "123", "internal_transfer", "DROP", None]),
                   ("direction", ["SIDEWAYS", "", "internal", None]),
                   ("channel", ["TELEPATHY", "", None])]:
    for v in bad:
        st, p = raw(apiA, "POST", "/v1/payments", tbody("100.00", ref="enum", **{field: v}))
        ok_status = st in (400, 422)
        bad5 = (500 <= st <= 599) or st == 0 or leaky(p)
        rec("enum", f"{field}={v!r} rejected", ok_status and not bad5, "400/422", st,
            f"code={code_of(p)}", "HIGH" if bad5 else ("MEDIUM" if not ok_status else ""))

# ============================================================ 3. INJECTION / oversize on string fields
print("\n== 3. injection / oversize / unicode on string fields ==")
PAYLOADS = {
    "sql": "'; DROP TABLE payment; --",
    "xss": "<script>alert(1)</script>",
    "nullbyte": "ab\x00cd",
    "unicode": "Zoé-Ωμέγα-名前-😀",
    "oversize": "Z" * 20000,
    "crlf": "line1\r\nSet-Cookie: x=1",
    "template": "${jndi:ldap://x/a}",
}
for nm, val in PAYLOADS.items():
    st, p = raw(apiA, "POST", "/v1/payments", tbody("100.00", ref="inj", creditor_name=val))
    # acceptable: 2xx (stored safely) OR 4xx (rejected). Not 5xx, not leak.
    ok = no5xx("injection", f"creditor_name {nm} handled (no 5xx/leak)", st, p)
    # if accepted, verify it round-trips without executing (just that id returned)
    if st in (200, 201) and isinstance(p, dict) and p.get("id"):
        pass

# ============================================================ 4. REAL cross-tenant isolation
print("\n== 4. REAL-id cross-tenant isolation (tenant B reads tenant A) ==")
# seed a real payment in tenant A
st, pay = raw(apiA, "POST", "/v1/payments", tbody("250.00", ref="xtenant"))
payA = pay.get("id") if isinstance(pay, dict) else None
custA = A["customer_id"]
acctA = A["id"]
for label, path in [("customer", f"/v1/customers/{custA}"),
                    ("deposit-account", f"/v1/deposit-accounts/{acctA}"),
                    ("payment", f"/v1/payments/{payA}" if payA else None)]:
    if not path:
        continue
    st, p = raw(apiB, "GET", path)           # tenant B asking for tenant A's object
    ok = st == 404
    sev = "CRITICAL" if st == 200 else ("MEDIUM" if st not in (403, 404) else "")
    rec("isolation", f"tenant B cannot read A's {label} (RLS)", ok, "404", st,
        f"code={code_of(p)}" + (" !! LEAKED DATA" if st == 200 else ""), sev)
# control: tenant A CAN read its own
st, p = raw(apiA, "GET", f"/v1/customers/{custA}")
rec("isolation", "control: tenant A reads own customer", st == 200, "200", st, "", "")

# ============================================================ 5. DEEP idempotency
print("\n== 5. deep idempotency ==")
k = str(uuid.uuid4())
ids = []
for i in range(8):
    st, p = raw(apiA, "POST", "/v1/payments", tbody("300.00", ref="idemrep"), key=k)
    if isinstance(p, dict):
        ids.append(p.get("id"))
uniq = set(x for x in ids if x)
rec("idempotency", "8x replay same key+body => ONE payment", len(uniq) == 1, "1 unique id",
    f"{len(uniq)} unique", f"ids={list(uniq)[:2]}", "HIGH" if len(uniq) != 1 else "")
st, p = raw(apiA, "POST", "/v1/payments", tbody("999.00", ref="idemdiff"), key=k)
rec("idempotency", "same key + different body => 422", st == 422, "422", st,
    f"code={code_of(p)}", "HIGH" if st != 422 else "")

# ============================================================ 6. CONCURRENCY: double-spend race
print("\n== 6. concurrency: double-spend race ==")
# fresh debtor funded with exactly 1000; fire 20 concurrent 100-transfers (would need 2000)
D = J.ensure_account(jA, "draceD", "Dan", "Race")
E = J.ensure_account(jA, "draceE", "Eve", "Race")
J.fund_account(jA, D, "1000.00", "Dan")
pre_d = J.balances(apiA, D)[2]
pre_e = J.balances(apiA, E)[2]
K = 20
AMOUNT = "100.00"


def fire(i):
    st, p = raw(apiA, "POST", "/v1/payments",
                {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
                 "customer_id": D["customer_id"], "debtor_account_id": D["id"],
                 "creditor_account_id": E["id"], "creditor_name": "Eve Race",
                 "amount": AMOUNT, "currency": "KES", "reference": f"race{i}"})
    return st, (p.get("id") if isinstance(p, dict) else None)


with ThreadPoolExecutor(max_workers=K) as ex:
    fired = list(ex.map(fire, range(K)))
created = [pid for st, pid in fired if pid]
settled = 0
for pid in created:
    try:
        fx = J.payment_until(apiA, pid, ("SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED"), timeout=40)
        if fx.get("status") in ("SETTLED", "COMPLETED"):
            settled += 1
    except Exception:
        pass
post_d = J.balances(apiA, D)[2]
post_e = J.balances(apiA, E)[2]
debited = pre_d - post_d
credited = post_e - pre_e
no_overdraft = post_d >= 0
conserved = (debited == credited)
consistent = (debited == Decimal(AMOUNT) * settled)
within = (settled <= 10)
print(f"    fired={K} created={len(created)} settled={settled} "
      f"pre_d={pre_d} post_d={post_d} debited={debited} credited={credited}")
rec("concurrency", "double-spend: no overdraft (debtor balance >= 0)", no_overdraft,
    ">=0", f"{post_d}", f"settled={settled}", "CRITICAL" if not no_overdraft else "")
rec("concurrency", "double-spend: value conserved (debited==credited)", conserved,
    "equal", f"{debited} vs {credited}", "", "CRITICAL" if not conserved else "")
rec("concurrency", "double-spend: at most 10 of 20 settle (1000/100)", within and consistent,
    "<=10 & consistent", f"settled={settled} debited={debited}", "",
    "CRITICAL" if (not within or not consistent) else "")

# ============================================================ 7. CONCURRENCY: same-key stampede
print("\n== 7. concurrency: same idempotency-key stampede ==")
k2 = str(uuid.uuid4())


def fire_same(i):
    st, p = raw(apiA, "POST", "/v1/payments", tbody("77.00", ref="stampede"), key=k2)
    return (p.get("id") if isinstance(p, dict) else None)


with ThreadPoolExecutor(max_workers=12) as ex:
    sids = list(ex.map(fire_same, range(12)))
u2 = set(x for x in sids if x)
rec("concurrency", "12 concurrent same-key POSTs => ONE payment", len(u2) == 1, "1 unique id",
    f"{len(u2)} unique", f"ids={list(u2)[:3]}", "HIGH" if len(u2) != 1 else "")

# ============================================================ summary
total = len(RESULTS)
fails = [r for r in RESULTS if not r["ok"]]
bycat = {}
for r in RESULTS:
    bycat.setdefault(r["cat"], [0, 0])
    bycat[r["cat"]][0] += 1
    if not r["ok"]:
        bycat[r["cat"]][1] += 1
print(f"\n== DEEP SUITE: {total} checks, {len(fails)} FAIL ==")
for c, (n, f) in sorted(bycat.items()):
    print(f"  {c:12s} {n:4d}  {f:3d} fail")
sev_order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "": 3}
fails.sort(key=lambda r: sev_order.get(r["severity"], 3))
print("\n-- FAILURES --")
for r in fails:
    print(f"  [{r['severity'] or 'MED'}] {r['cat']} :: {r['name']}  exp {r['expected']} got {r['actual']}  {r['detail']}")
json.dump({"total": total, "fails": len(fails), "results": RESULTS}, open("/tmp/qa_deep.json", "w"), default=str)
print("\nwrote /tmp/qa_deep.json")
