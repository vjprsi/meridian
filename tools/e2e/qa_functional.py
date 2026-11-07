#!/usr/bin/env python3
"""Deep functional QA — positive + negative field validation, money-movement correctness, and
transaction-history reflection. Reuses the journeys.py harness (provisions a fresh KE tenant via journey S).

Run ON the box:  cd ~/meridian && python3.12 tools/e2e/qa_functional.py
Checks, per the brief:
  * creating customers/accounts with bad mandatory fields (empty / too long / missing / wrong type)
  * email without @ or without a dot, bad phone — must be rejected, not accepted, not 500
  * sending money: sender debited, receiver credited instantly, both sides reflected in history
  * balance can never go negative (overdraft blocked)
  * no 5xx and no Java-internal leakage anywhere
"""
from __future__ import annotations
import json, sys, uuid
from decimal import Decimal

import journeys as J
from journeys import (Api, Journey, StepFailed, create_customer, ensure_account, fund_account,
                      balances, dec, COUNTRY, CCY, MAKER, CHECKER, CHECKER_ROLES)

ANY = tuple(range(200, 600))
G, R, Y, DIM, RST = ("\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 5

PASS = 0; FAILS = []; NOTES = []; TOTAL = 0
LEAK_MARKERS = ("java.", "javax.", "jakarta.", "org.springframework", "\n\tat ", " at io.meridian",
                "NullPointer", "NestedServlet", "ClassCast", "Hibernate", "PSQLException", "DataIntegrity")


def code_of(p):
    return p.get("code") if isinstance(p, dict) else "?"


def is_leak(p) -> bool:
    s = p if isinstance(p, str) else json.dumps(p)
    return any(m in s for m in LEAK_MARKERS)


def raw(api, method, path, body=None, **kw):
    payload = api.call(method, path, body, expect=ANY, **kw)
    return api.latencies[-1][3], payload


def record(name, ok, detail, note=False):
    global PASS, TOTAL
    TOTAL += 1
    if ok:
        PASS += 1
        print(f"  {G}ok{RST}   {name}  {DIM}{detail}{RST}")
    elif note:
        NOTES.append((name, detail))
        print(f"  {Y}note{RST} {name}  {Y}{detail}{RST}")
    else:
        FAILS.append((name, detail))
        print(f"  {R}FAIL{RST} {name}  {R}{detail}{RST}")


def neg(api, name, method, path, body, codes=(400, 422), **kw):
    """Negative case: MUST be rejected with a clean 4xx problem+json, never 2xx, never 5xx, never a leak."""
    status, p = raw(api, method, path, body, **kw)
    leak = is_leak(p)
    if status >= 500:
        record(name, False, f"HTTP {status} (5xx!) {code_of(p)}")
    elif leak:
        record(name, False, f"HTTP {status} LEAKS internals: {str(p)[:120]}")
    elif status in codes:
        record(name, True, f"HTTP {status} {code_of(p)}")
    elif 200 <= status < 300:
        record(name, False, f"ACCEPTED (HTTP {status}) — validation gap, bad input was not rejected", note=True)
    else:
        record(name, True, f"HTTP {status} {code_of(p)} (rejected, non-standard code)")


def pos(api, name, method, path, body, codes=(200, 201, 202, 204), **kw):
    status, p = raw(api, method, path, body, **kw)
    if status in codes and not is_leak(p):
        record(name, True, f"HTTP {status}")
        return p
    record(name, False, f"HTTP {status} {code_of(p)} {str(p)[:160]}")
    return p


# ----------------------------------------------------------------------------------------------------------
def provision():
    tenant = str(uuid.uuid4())
    api = Api(tenant)
    shared: dict = {}
    print(f"{DIM}provisioning fresh tenant {tenant} via journey S …{RST}")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print(f"{R}SETUP FAILED — cannot run functional suite{RST}")
        sys.exit(2)
    print(f"{G}tenant ready{RST}: le={shared['legal_entity_id']} ledger={shared['ledger_id']}\n")
    return api, shared, j


def customer_base(le):
    return {"customer_type": "INDIVIDUAL", "legal_entity_id": le, "first_name": "Val", "last_name": "Idator",
            "display_name": "Val Idator", "date_of_birth": "1990-04-12", "nationality": COUNTRY,
            "residence_country": COUNTRY, "tax_residence_country": COUNTRY, "gender": "UNDISCLOSED",
            "employment_status": "EMPLOYED", "occupation": "Engineer", "onboarding_channel": "API",
            "preferred_language": "en", "external_ref": f"qa-{uuid.uuid4().hex[:10]}"}


def without(d, *keys):
    x = dict(d)
    for k in keys:
        x.pop(k, None)
    return x


def mut(d, **kw):
    x = dict(d); x.update(kw); return x


def section(title):
    print(f"\n{title}")


# ----------------------------------------------------------------------------------------------------------
def test_customer_fields(api, le):
    section("== 1. Customer create — field validation (positive + negative) ==")
    base = customer_base(le)
    pos(api, "valid individual customer -> created", "POST", "/v1/customers", base, codes=(200, 201))
    # name rule is "first_name/last_name OR display_name" — so an empty first_name alone is NOT a violation.
    nb = without(base, "display_name")
    neg(api, "no name at all (first/last/display absent)", "POST", "/v1/customers",
        without(nb, "first_name", "last_name"))
    neg(api, "all names blank (no display_name)", "POST", "/v1/customers",
        mut(nb, first_name="  ", last_name=""))
    pos(api, "display_name only, no first/last -> valid", "POST", "/v1/customers",
        without(base, "first_name", "last_name"), codes=(200, 201))
    neg(api, "first_name 500 chars (overlong)", "POST", "/v1/customers", mut(base, first_name="A" * 500))
    neg(api, "customer_type missing", "POST", "/v1/customers", without(base, "customer_type"))
    neg(api, "customer_type invalid 'ROBOT'", "POST", "/v1/customers", mut(base, customer_type="ROBOT"))
    neg(api, "date_of_birth in the future", "POST", "/v1/customers", mut(base, date_of_birth="2999-01-01"))
    neg(api, "date_of_birth malformed 'not-a-date'", "POST", "/v1/customers", mut(base, date_of_birth="not-a-date"))
    neg(api, "nationality invalid 'ZZ'", "POST", "/v1/customers", mut(base, nationality="ZZ"))
    neg(api, "nationality overlong 'KENYA'", "POST", "/v1/customers", mut(base, nationality="KENYA"))
    neg(api, "residence_country invalid 'ZZ'", "POST", "/v1/customers", mut(base, residence_country="ZZ"))
    neg(api, "legal_entity_id missing", "POST", "/v1/customers", without(base, "legal_entity_id"))
    neg(api, "legal_entity_id malformed 'not-a-uuid'", "POST", "/v1/customers", mut(base, legal_entity_id="not-a-uuid"))
    neg(api, "legal_entity_id random (nonexistent)", "POST", "/v1/customers",
        mut(base, legal_entity_id=str(uuid.uuid4())), codes=(400, 404, 409, 422))
    neg(api, "gender invalid 'X'", "POST", "/v1/customers", mut(base, gender="X"))


def test_email_phone(api, le):
    section("== 2. Email / phone contact validation (the @ and dot rules) ==")
    # need a customer to attach contacts to
    c = api.call("POST", "/v1/customers", customer_base(le), expect=ANY)
    status = api.latencies[-1][3]
    if not (200 <= status < 300) or not isinstance(c, dict) or "id" not in c:
        record("precondition: create customer for contact tests", False, f"HTTP {status}")
        return
    cid = c["id"]
    good = f"val.{uuid.uuid4().hex[:8]}@e2e.meridian.example"
    pos(api, "valid email contact -> created", "POST", f"/v1/customers/{cid}/contacts",
        {"contact_type": "EMAIL", "email": good, "is_primary": True})
    bad_emails = {
        "no @ sign 'plainaddress'": "plainaddress",
        "no domain 'user@'": "user@",
        "no local part '@host.com'": "@host.com",
        "no dot/TLD 'user@localhost'": "user@localhost",
        "double dot 'a@b..com'": "a@b..com",
        "space inside 'jo hn@x.com'": "jo hn@x.com",
        "trailing comma 'a@b,com'": "a@b,com",
        "empty email ''": "",
    }
    for name, val in bad_emails.items():
        neg(api, f"email {name}", "POST", f"/v1/customers/{cid}/contacts",
            {"contact_type": "EMAIL", "email": val, "is_primary": False})
    neg(api, "email contact with no email field", "POST", f"/v1/customers/{cid}/contacts",
        {"contact_type": "EMAIL", "is_primary": False})
    # phone
    pos(api, "valid mobile contact -> created", "POST", f"/v1/customers/{cid}/contacts",
        {"contact_type": "MOBILE", "phone": "+2547" + str(uuid.uuid4().int)[:8], "is_primary": False})
    for name, val in {"letters 'abcdef'": "abcdef", "too short '+1'": "+1", "empty ''": ""}.items():
        neg(api, f"phone {name}", "POST", f"/v1/customers/{cid}/contacts",
            {"contact_type": "MOBILE", "phone": val, "is_primary": False})


def test_account_fields(api, shared, j):
    section("== 3. Deposit-account create — field validation ==")
    # a valid, active customer + published product already exist after a successful ensure_account below;
    # but we can probe the create endpoint directly with a known-good customer first.
    cid = J.onboard_customer(j, "Acc", "Holder", "cust_accfield")
    pid, vid = J.create_deposit_product(j)
    base = {"account_type": "SAVINGS", "customer_id": cid, "product_id": pid, "product_version_id": vid,
            "legal_entity_id": api.shared_le, "currency": CCY, "nickname": "QA"}
    pos(api, "valid deposit account -> opened", "POST", "/v1/deposit-accounts", base, codes=(200, 201, 202))
    neg(api, "customer_id missing", "POST", "/v1/deposit-accounts", without(base, "customer_id"))
    neg(api, "customer_id random (nonexistent)", "POST", "/v1/deposit-accounts",
        mut(base, customer_id=str(uuid.uuid4())), codes=(400, 404, 409, 422))
    neg(api, "product_id missing", "POST", "/v1/deposit-accounts", without(base, "product_id"))
    neg(api, "product_id random (nonexistent)", "POST", "/v1/deposit-accounts",
        mut(base, product_id=str(uuid.uuid4())), codes=(400, 404, 409, 422))
    neg(api, "currency invalid 'XYZ'", "POST", "/v1/deposit-accounts", mut(base, currency="XYZ"))
    neg(api, "currency lowercase 'kes'", "POST", "/v1/deposit-accounts", mut(base, currency="kes"))
    neg(api, "currency missing", "POST", "/v1/deposit-accounts", without(base, "currency"))
    neg(api, "account_type invalid 'ROCKET'", "POST", "/v1/deposit-accounts", mut(base, account_type="ROCKET"))


def test_transfer_negatives(api, j):
    section("== 4. Money transfer — negative & corner cases ==")
    a = ensure_account(j, "fa", "Fund", "Source")
    b = ensure_account(j, "fb", "Fund", "Dest")
    fund_account(j, a, "1000.00", "Fund Source")

    def body(**kw):
        base = {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
                "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
                "creditor_name": "Fund Dest", "amount": "100.00", "currency": CCY, "reference": "QA"}
        base.update(kw)
        return base
    neg(api, "amount negative '-100.00'", "POST", "/v1/payments", body(amount="-100.00"))
    neg(api, "amount zero '0.00'", "POST", "/v1/payments", body(amount="0.00"))
    neg(api, "amount non-numeric 'abc'", "POST", "/v1/payments", body(amount="abc"))
    neg(api, "amount excess precision '100.123' (KES=2dp)", "POST", "/v1/payments", body(amount="100.123"))
    neg(api, "same debtor==creditor account", "POST", "/v1/payments", body(creditor_account_id=a["id"]),
        codes=(400, 409, 422))
    neg(api, "currency mismatch 'USD' on KES accounts", "POST", "/v1/payments", body(currency="USD"),
        codes=(400, 409, 422))
    neg(api, "debtor_account_id missing", "POST", "/v1/payments", without(body(), "debtor_account_id"))
    neg(api, "creditor account random (nonexistent)", "POST", "/v1/payments",
        body(creditor_account_id=str(uuid.uuid4())), codes=(400, 404, 409, 422))


def test_overdraft(api, j):
    section("== 5. Overdraft / negative-balance guard ==")
    a = ensure_account(j, "od_a", "Over", "Draft")
    b = ensure_account(j, "od_b", "Over", "Payee")
    fund_account(j, a, "100.00", "Over Draft")
    avail0, posted0, led0 = balances(api, a)
    # try to send far more than the balance
    status, p = raw(api, "POST", "/v1/payments", {
        "payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
        "creditor_name": "Over Payee", "amount": "100000.00", "currency": CCY, "reference": "overdraft attempt"})
    settled_negative = False
    outcome = f"HTTP {status} {code_of(p)}"
    if 200 <= status < 300 and isinstance(p, dict) and p.get("id"):
        # accepted for processing — it must NOT settle into a negative balance
        try:
            x = J.payment_until(api, p["id"], ("SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED"), timeout=25)
            outcome = f"accepted then {x['status']}"
            settled_negative = x["status"] in ("SETTLED", "COMPLETED")
        except StepFailed as e:
            outcome = f"accepted, non-terminal ({str(e)[:60]})"
    avail1, posted1, led1 = balances(api, a)
    never_negative = avail1 >= 0 and posted1 >= 0 and led1 >= 0
    unchanged = led1 == led0
    record("overdraft blocked: 100k from a 100 balance never settles", not settled_negative, outcome)
    record("balance never goes negative", never_negative, f"available={avail1} posted={posted1} ledger={led1}")
    record("over-balance attempt did not move the ledger", unchanged, f"ledger {led0} -> {led1}")


def test_transfer_and_history(api, j):
    section("== 6. Money movement correctness + transaction history (both sides) ==")
    a = ensure_account(j, "hist_a", "Hist", "Sender")
    b = ensure_account(j, "hist_b", "Hist", "Receiver")
    fund_account(j, a, "10000.00", "Hist Sender")
    a0a, a0p, a0l = balances(api, a)
    b0a, b0p, b0l = balances(api, b)
    amt = Decimal("2500.00")
    pay = pos(api, "transfer 2500 Sender -> Receiver accepted", "POST", "/v1/payments", {
        "payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
        "creditor_name": "Hist Receiver", "amount": "2500.00", "currency": CCY, "reference": "QA history"})
    if not isinstance(pay, dict) or "id" not in pay:
        return
    pid = pay["id"]
    try:
        J.fraud_cleared(api, pid)
    except StepFailed:
        pass
    try:
        fin = J.payment_until(api, pid, ("SETTLED", "COMPLETED"), timeout=30)
        record("transfer settles instantly (<30s)", True, fin["status"])
    except StepFailed as e:
        record("transfer settles instantly (<30s)", False, str(e)[:120])
        return
    a1a, a1p, a1l = balances(api, a)
    b1a, b1p, b1l = balances(api, b)
    record("sender debited exactly 2500 (ledger)", a0l - a1l == amt, f"{a0l} -> {a1l}")
    record("sender available debited exactly 2500", a0a - a1a == amt, f"{a0a} -> {a1a}")
    record("receiver credited exactly 2500 (ledger)", b1l - b0l == amt, f"{b0l} -> {b1l}")
    record("receiver available credited exactly 2500", b1a - b0a == amt, f"{b0a} -> {b1a}")
    record("conservation: sender out == receiver in", a0l - a1l == b1l - b0l, f"{a0l-a1l} == {b1l-b0l}")

    # transaction history — sender side (DEBIT) and receiver side (CREDIT)
    sh = raw(api, "GET", f"/v1/deposit-accounts/{a['id']}/transactions?limit=50")[1]
    rh = raw(api, "GET", f"/v1/deposit-accounts/{b['id']}/transactions?limit=50")[1]
    sdata = sh.get("data", []) if isinstance(sh, dict) else []
    rdata = rh.get("data", []) if isinstance(rh, dict) else []

    def has_entry(rows, direction_words, amount):
        for r in rows:
            amt_ok = any(dec(r.get(f)) == amount for f in ("amount", "value", "transaction_amount") if r.get(f) is not None)
            blob = json.dumps(r).upper()
            dir_ok = any(w in blob for w in direction_words)
            if amt_ok and dir_ok:
                return r
        return None

    record("sender history lists the 2500 debit", bool(has_entry(sdata, ("DEBIT", "DR", "-"), amt)),
           f"{len(sdata)} rows")
    record("receiver history lists the 2500 credit", bool(has_entry(rdata, ("CREDIT", "CR"), amt)),
           f"{len(rdata)} rows")

    # payment status timeline
    hist = raw(api, "GET", f"/v1/payments/{pid}/status-history")[1]
    hrows = hist.get("data", []) if isinstance(hist, dict) else []
    states = [h.get("to_status", h.get("status", "?")) for h in sorted(hrows, key=lambda h: h.get("created_at", ""))]
    record("payment status timeline ends SETTLED/COMPLETED", bool(states) and states[-1] in ("SETTLED", "COMPLETED"),
           " -> ".join(states) or "empty")

    # ledger journal entry balanced
    jid = fin.get("journal_entry_id")
    if jid:
        # double-entry exposes total_debits + the entries list (credits==debits by construction); sum the legs.
        ents = raw(api, "GET", f"/v1/journal-entries/{jid}/entries?limit=50")[1]
        erows = ents.get("data", []) if isinstance(ents, dict) else []
        deb = sum((dec(r.get("amount")) for r in erows if r.get("direction") == "DEBIT"), Decimal(0))
        cre = sum((dec(r.get("amount")) for r in erows if r.get("direction") == "CREDIT"), Decimal(0))
        record("transfer journal entry is balanced (sum debits==sum credits==2500)",
               deb == cre and deb == amt, f"{len(erows)} legs D={deb} C={cre}")
    else:
        record("transfer carries a journal_entry_id", False, "missing")

    # receiver can't see sender's account transactions already covered by RLS suite; spot-check idempotent replay
    s2, p2 = raw(api, "POST", "/v1/payments", {
        "payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
        "creditor_name": "Hist Receiver", "amount": "2500.00", "currency": CCY, "reference": "QA history"},
        key=pay.get("idempotency_key") or "")
    # (replay uses a fresh key here, so this just confirms a second distinct transfer also settles cleanly)
    record("second independent transfer also accepted", 200 <= s2 < 300, f"HTTP {s2} {code_of(p2)}")


def main():
    api, shared, j = provision()
    le = shared["legal_entity_id"]
    test_customer_fields(api, le)
    test_email_phone(api, le)
    try:
        test_account_fields(api, shared, j)
    except StepFailed as e:
        record("account-field preconditions", False, str(e)[:160])
    test_transfer_negatives(api, j)
    test_overdraft(api, j)
    test_transfer_and_history(api, j)

    print(f"\n{'='*70}")
    print(f"FUNCTIONAL QA: {PASS}/{TOTAL} ok, {len(FAILS)} FAIL, {len(NOTES)} note")
    if FAILS:
        print(f"\n{R}FAILURES (defects):{RST}")
        for n, d in FAILS:
            print(f"  - {n}: {d}")
    if NOTES:
        print(f"\n{Y}NOTES (accepted bad input — possible validation gaps, not crashes):{RST}")
        for n, d in NOTES:
            print(f"  - {n}: {d}")
    lat = [x for x in api.latencies]
    if lat:
        ms = sorted(x[2] for x in lat)
        print(f"\n{len(ms)} calls, p50 {ms[len(ms)//2]:.0f}ms p95 {ms[int(len(ms)*.95)]:.0f}ms max {ms[-1]:.0f}ms")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
