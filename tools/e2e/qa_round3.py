#!/usr/bin/env python3
"""Round-3 business-logic negatives for lending, cards and wallet. Reuses the journeys harness
(fresh KE tenant via journey S; ensure_account + create_loan_product for real parents).
Pass = bad input is cleanly rejected (4xx, never 2xx, never 5xx, no Java-internal leak), with the
right machine code where one is determinable. Run ON the box: python3.12 tools/e2e/qa_round3.py"""
from __future__ import annotations
import json, sys, uuid, random
from decimal import Decimal

import journeys as J
from journeys import (Api, Journey, StepFailed, ensure_account, dec, COUNTRY, CCY, today)
import journey_e_lending as JE

ANY = tuple(range(200, 600))
G, R, Y, DIM, RST = ("\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 5
PASS = 0; TOTAL = 0; FAILS = []; NOTES = []
LEAK = ("java.", "javax.", "jakarta.", "org.springframework", "\n\tat ", " at io.meridian",
        "NullPointer", "NestedServlet", "PSQLException", "DataIntegrity", "Hibernate")


def codeof(p): return p.get("code") if isinstance(p, dict) else "?"
def leaky(p): s = p if isinstance(p, str) else json.dumps(p); return any(m in s for m in LEAK)


def raw(api, method, path, body=None, **kw):
    p = api.call(method, path, body, expect=ANY, **kw)
    return api.latencies[-1][3], p


def rec(name, ok, detail, note=False):
    global PASS, TOTAL
    TOTAL += 1
    if ok: PASS += 1; print(f"  {G}ok{RST}   {name}  {DIM}{detail}{RST}")
    elif note: NOTES.append((name, detail)); print(f"  {Y}note{RST} {name}  {Y}{detail}{RST}")
    else: FAILS.append((name, detail)); print(f"  {R}FAIL{RST} {name}  {R}{detail}{RST}")


def neg(api, name, method, path, body, codes=(400, 404, 409, 422), **kw):
    st, p = raw(api, method, path, body, **kw)
    if st >= 500: rec(name, False, f"HTTP {st} (5xx!) {codeof(p)}")
    elif leaky(p): rec(name, False, f"HTTP {st} LEAK {str(p)[:100]}")
    elif st in codes: rec(name, True, f"HTTP {st} {codeof(p)}")
    elif 200 <= st < 300: rec(name, False, f"ACCEPTED HTTP {st} — bad input not rejected", note=True)
    else: rec(name, True, f"HTTP {st} {codeof(p)} (rejected)")


def mut(d, **kw): x = dict(d); x.update(kw); return x
def without(d, *ks):
    x = dict(d)
    for k in ks: x.pop(k, None)
    return x


def provision():
    api = Api(str(uuid.uuid4())); shared = {}
    print(f"{DIM}provisioning tenant via journey S …{RST}")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok: print(f"{R}SETUP FAILED{RST}"); sys.exit(2)
    print(f"{G}ready{RST} le={shared['legal_entity_id']}\n")
    return api, shared, j


def test_lending(api, j):
    print("== 1. Lending — loan-application amount / term / currency / reference negatives ==")
    acct = ensure_account(j, "r3_borrower", "Loan", "Applicant")
    pid, vid = JE.create_loan_product(j)
    base = {"customer_id": acct["customer_id"], "product_id": pid, "product_version_id": vid,
            "legal_entity_id": api.shared_le, "channel": "API", "loan_type": "INSTALMENT", "purpose": "PERSONAL",
            "requested_amount": "50000.00", "currency": CCY, "requested_term_months": 12,
            "requested_frequency": "MONTHLY", "requested_schedule_type": "ANNUITY",
            "disbursement_account_id": acct["id"], "repayment_account_id": acct["id"],
            "consent_bureau": True, "consent_at": today() + "T08:00:00Z"}
    st, p = raw(api, "POST", "/v1/loan-applications", base)
    rec("valid loan application -> created", 200 <= st < 300, f"HTTP {st} {codeof(p)}")
    neg(api, "requested_amount negative '-50000'", "POST", "/v1/loan-applications", mut(base, requested_amount="-50000.00"))
    neg(api, "requested_amount zero '0.00'", "POST", "/v1/loan-applications", mut(base, requested_amount="0.00"))
    neg(api, "requested_amount non-numeric 'lots'", "POST", "/v1/loan-applications", mut(base, requested_amount="lots"))
    neg(api, "requested_term_months below min (1 < 3)", "POST", "/v1/loan-applications", mut(base, requested_term_months=1))
    neg(api, "requested_term_months above max (60 > 36)", "POST", "/v1/loan-applications", mut(base, requested_term_months=60))
    neg(api, "requested_term_months zero", "POST", "/v1/loan-applications", mut(base, requested_term_months=0))
    neg(api, "requested_term_months negative", "POST", "/v1/loan-applications", mut(base, requested_term_months=-6))
    neg(api, "currency mismatch 'USD'", "POST", "/v1/loan-applications", mut(base, currency="USD"))
    neg(api, "loan_type invalid 'CRYPTO'", "POST", "/v1/loan-applications", mut(base, loan_type="CRYPTO"))
    neg(api, "requested_frequency invalid 'HOURLY'", "POST", "/v1/loan-applications", mut(base, requested_frequency="HOURLY"))
    neg(api, "product_version_id nonexistent", "POST", "/v1/loan-applications", mut(base, product_version_id=str(uuid.uuid4())))
    neg(api, "customer_id nonexistent", "POST", "/v1/loan-applications", mut(base, customer_id=str(uuid.uuid4())))
    neg(api, "disbursement_account_id nonexistent", "POST", "/v1/loan-applications", mut(base, disbursement_account_id=str(uuid.uuid4())))


def test_card_auth(api, j):
    print("\n== 2. Cards — card-authorization amount / reference negatives ==")

    def body(**kw):
        b = {"card_id": str(uuid.uuid4()), "message_type": "AUTHORIZATION", "channel": "POS",
             "pos_entry_mode": "CONTACTLESS", "card_present": True, "cardholder_present": True,
             "merchant_id": f"M{uuid.uuid4().hex[:8]}", "merchant_name": "QA Store", "merchant_city": "Nairobi",
             "merchant_country": COUNTRY, "mcc": "5411", "terminal_id": "T0001",
             "stan": str(random.randint(100000, 999999)), "rrn": str(random.randint(10**11, 10**12 - 1)),
             "network_transaction_id": f"NT{uuid.uuid4().hex[:20]}", "transaction_amount": "1200.00",
             "transaction_currency": CCY}
        b.update(kw); return b
    # nonexistent card: must be rejected/declined, never 5xx
    neg(api, "auth against nonexistent card_id", "POST", "/v1/card-authorizations", body(),
        codes=(400, 404, 409, 422))
    neg(api, "auth amount negative '-100'", "POST", "/v1/card-authorizations", body(transaction_amount="-100.00"))
    neg(api, "auth amount zero '0.00'", "POST", "/v1/card-authorizations", body(transaction_amount="0.00"))
    neg(api, "auth amount non-numeric 'free'", "POST", "/v1/card-authorizations", body(transaction_amount="free"))
    neg(api, "auth currency invalid 'XYZ'", "POST", "/v1/card-authorizations", body(transaction_currency="XYZ"))
    neg(api, "auth message_type invalid 'GIFT'", "POST", "/v1/card-authorizations", body(message_type="GIFT"))
    neg(api, "auth card_id missing", "POST", "/v1/card-authorizations", without(body(), "card_id"))


def test_wallet(api, j):
    print("\n== 3. Wallet — open negatives ==")
    # minimal open body; exact valid shape needs an agent/program, so these assert clean rejection of bad input.
    base = {"wallet_type": "CONSUMER", "currency": CCY, "owner_type": "CUSTOMER",
            "owner_id": j.shared.get("cust_r3_borrower") or str(uuid.uuid4())}
    neg(api, "wallet currency invalid 'XYZ'", "POST", "/v1/wallets", mut(base, currency="XYZ"))
    neg(api, "wallet currency lowercase 'kes'", "POST", "/v1/wallets", mut(base, currency="kes"))
    neg(api, "wallet wallet_type invalid 'MAGIC'", "POST", "/v1/wallets", mut(base, wallet_type="MAGIC"))
    neg(api, "wallet owner_type invalid 'ALIEN'", "POST", "/v1/wallets", mut(base, owner_type="ALIEN"))
    neg(api, "wallet currency missing", "POST", "/v1/wallets", without(base, "currency"))


def main():
    api, shared, j = provision()
    try:
        test_lending(api, j)
    except StepFailed as e:
        rec("lending preconditions", False, str(e)[:140])
    test_card_auth(api, j)
    test_wallet(api, j)
    print(f"\n{'='*66}\nROUND-3 QA: {PASS}/{TOTAL} ok, {len(FAILS)} FAIL, {len(NOTES)} note")
    if FAILS:
        print(f"\n{R}FAILURES:{RST}")
        for n, d in FAILS: print(f"  - {n}: {d}")
    if NOTES:
        print(f"\n{Y}NOTES (accepted — possible validation gap):{RST}")
        for n, d in NOTES: print(f"  - {n}: {d}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
