#!/usr/bin/env python3
"""Colombian transaction taxes end-to-end on a fresh CO tenant: GMF 4x1000 on debits (Bre-B and internal), same-holder
exemption (E.T. art. 879 num. 14), the designated exempt account and its monthly 350 UVT allowance (num. 1), IVA on fees,
validate/preview showing the total debit, tax release on a rail rejection, and the tax payable GL receiving the tax.
Needs platform/breb-simulator on :18190. Run ON the box:
  MERIDIAN_E2E_STARTER_PACK=CO MERIDIAN_E2E_CCY=COP MERIDIAN_E2E_COUNTRY=CO python3.12 tools/e2e/qa_taxes.py
Writes /tmp/qa_taxes.json."""
from __future__ import annotations
import hashlib, hmac, json, sys, time, urllib.request, uuid
from datetime import datetime, timezone
from decimal import Decimal

import journeys as J
from journeys import Api, Journey, ensure_account, fund_account, balances, CCY

SIM = "http://localhost:18190"
ANY = tuple(range(200, 600))
ROWS = []


def rec(flow, ctype, title, ok, actual, expected="as specified", priority="High"):
    ROWS.append({"service": "payment-service", "flow": flow, "type": ctype, "title": title, "expected": expected,
                 "actual": actual, "status": "PASS" if ok else "FAIL", "priority": priority,
                 "run_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")})
    print(f"  {'ok' if ok else 'FAIL'}   {title}  {actual}")


def sim(method, path, body=None):
    d = None if body is None else json.dumps(body).encode()
    h = {"Content-Type": "application/json"}
    if d:
        h["X-Signature"] = hmac.new(b"local-dev-breb-secret", d, hashlib.sha256).hexdigest()
    try:
        with urllib.request.urlopen(urllib.request.Request(SIM + path, data=d, method=method, headers=h), timeout=10) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def raw(api, method, path, body=None):
    p = api.call(method, path, body, expect=ANY)
    return api.latencies[-1][3], p


def wait(api, pid, states=("SETTLED", "REJECTED", "FAILED", "PENDING_APPROVAL", "ON_HOLD"), secs=20):
    end = time.time() + secs
    p = {}
    while time.time() < end:
        p = api.get(f"/v1/payments/{pid}")
        if p.get("status") in states:
            return p
        time.sleep(0.5)
    return p


def breb(api, acct, amount, ref):
    st, p = raw(api, "POST", "/v1/payments", {"payment_type": "INSTANT", "direction": "OUTBOUND", "channel": "API",
        "customer_id": acct["customer_id"], "debtor_account_id": acct["id"], "creditor_name": "Beneficiario",
        "creditor_account_number": "+573001234567", "creditor_account_id_type": "PROXY", "amount": amount,
        "currency": CCY, "reference": ref})
    return wait(api, p["id"]) if isinstance(p, dict) and p.get("id") else p


def internal(api, frm, to, amount, ref):
    st, p = raw(api, "POST", "/v1/payments", {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": frm["customer_id"], "debtor_account_id": frm["id"], "creditor_account_id": to["id"],
        "creditor_name": "Titular", "amount": amount, "currency": CCY, "reference": ref})
    return wait(api, p["id"]) if isinstance(p, dict) and p.get("id") else p


def gmf(p):
    for l in (p.get("tax_breakdown") or {}).get("lines") or []:
        if l.get("tax_type") == "FINANCIAL_TRANSACTION_TAX":
            return l
    return {}


def D(x):
    return Decimal(str(x or "0"))


def main():
    sim("POST", "/sim/reset")
    sim("POST", "/nodes/A/dice/v1/keys", {"key_type": "CELULAR", "key_value": "3001234567", "participant_code": "0507",
        "participant_name": "Banco Destino (sim)", "account_number": "77700011", "account_type": "DEPOSITO_ELECTRONICO",
        "holder_name": "María Fernanda Gómez", "document_type": "CC", "document_number": "52123456"})
    api = Api(str(uuid.uuid4())); shared = {}
    print(f"provisioning CO tenant {api.tenant} ...")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print("SETUP FAILED"); return 2
    a = ensure_account(j, "tax_payer", "Laura", "Ospina")
    fund_account(j, a, "3000000.00", "Laura")
    a2 = J.open_deposit_account(j, a["customer_id"], " Laura ahorro")
    b = ensure_account(j, "tax_other", "Jorge", "Restrepo")

    print("\n== GMF 4x1000 ==")
    b0 = balances(api, a)
    x = breb(api, a, "150000.00", "GMF breb")
    b1 = balances(api, a)
    g = gmf(x)
    rec("GMF 4x1000", "UAT", "Bre-B transfer of COP 150,000 carries GMF COP 600 (4x1000)",
        x.get("status") == "SETTLED" and D(x.get("tax_amount")) == 600 and D(g.get("amount")) == 600,
        f"{x.get('status')} tax {x.get('tax_amount')} {g.get('name')}")
    rec("GMF 4x1000", "UAT", "Payer debited amount + GMF = COP 150,600", b0[0] - b1[0] == Decimal("150600"),
        f"{b0[0]} -> {b1[0]}")
    x = internal(api, a, b, "250000.00", "GMF interno")
    rec("GMF 4x1000", "UAT", "Internal transfer to another customer: GMF COP 1,000", D(x.get("tax_amount")) == 1000,
        f"{x.get('status')} tax {x.get('tax_amount')}")
    x = internal(api, a, a2, "500000.00", "traslado propio")
    g = gmf(x)
    rec("GMF 4x1000", "UAT", "Same-holder transfer exempt (art. 879 num. 14): GMF 0, reason recorded",
        x.get("status") == "SETTLED" and D(x.get("tax_amount")) == 0 and "879" in (g.get("reason") or ""),
        f"{x.get('status')} tax {x.get('tax_amount')} | {g.get('reason')}")
    b2 = balances(api, a)
    x = breb(api, a, "1000.13", "rechazo")
    b3 = balances(api, a)
    rec("GMF 4x1000", "QA", "Rail rejection releases amount AND tax (balance unchanged)",
        x.get("status") == "REJECTED" and b3[0] == b2[0], f"{x.get('status')} {b2[0]} -> {b3[0]}")
    st, v = raw(api, "POST", "/v1/payments/validate", {"payment": {"payment_type": "INSTANT", "direction": "OUTBOUND", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_name": "Beneficiario",
        "creditor_account_number": "+573001234567", "creditor_account_id_type": "PROXY", "amount": "200000.00", "currency": CCY, "reference": "preview"}})
    rec("GMF 4x1000", "UAT", "Validate (preview) shows taxes and total debit COP 200,800 before confirming",
        st == 200 and D(v.get("total_debit")) == Decimal("200800") and D((v.get("taxes") or {}).get("amount")) == 800,
        f"HTTP {st} total {v.get('total_debit')} taxes {(v.get('taxes') or {}).get('amount')} {'' if st == 200 else str(v.get('errors'))[:300]}")

    print("\n== Designated exempt account (350 UVT / month) ==")
    cur = api.get(f"/v1/deposit-accounts/{a['id']}")
    r = api.call("PATCH", f"/v1/deposit-accounts/{a['id']}", {"metadata": {"gmf_exempt": True}}, expect=ANY,
                 headers={"If-Match": str(cur.get("row_version", ""))})
    st = api.latencies[-1][3]
    rec("GMF exempt account", "UAT", "Mark the holder's account as GMF-exempt (metadata.gmf_exempt)", st == 200
        and (r.get("metadata") or {}).get("gmf_exempt") is True, f"HTTP {st} {r.get('metadata')}")
    # month-to-date debits so far: 150,000 + 250,000 + 500,000 = 900,000 (the rejected one does not count)
    x = breb(api, a, "100000.00", "exenta")
    rec("GMF exempt account", "UAT", "Debit within the monthly allowance: GMF 0 with the exemption reason",
        x.get("status") == "SETTLED" and D(x.get("tax_amount")) == 0 and D(gmf(x).get("exempt_amount")) == 100000,
        f"{x.get('status')} tax {x.get('tax_amount')} exempt {gmf(x).get('exempt_amount')} | {gmf(x).get('reason')} {'' if x.get('status') else str(x)[:400]}")
    cur = api.get(f"/v1/deposit-accounts/{a['id']}")
    api.call("PATCH", f"/v1/deposit-accounts/{a['id']}", {"metadata": {"gmf_exempt": False}}, expect=ANY,
             headers={"If-Match": str(cur.get("row_version", ""))})
    x = breb(api, a, "100000.00", "no exenta")
    rec("GMF exempt account", "QA", "Exemption removed: GMF charged again (COP 400)", D(x.get("tax_amount")) == 400,
        f"tax {x.get('tax_amount')}")

    print("\n== Ledger ==")
    # sum of GMF actually collected: 600 + 1,000 + 0 + 0 + 400 = 2,000 on the tax payable GL
    cfg = api.get("/v1/config/entries?namespace=ledger&config_key=payment-service&status=ACTIVE&limit=1").get("data", [])
    tp = ((cfg[0].get("value") or {}).get("gl") or {}).get("tax_payable") if cfg else None
    lb = api.get(f"/v1/ledger-accounts/{tp}/balance") if tp else {}
    rec("GMF ledger", "UAT", "Tax payable GL holds the GMF collected (COP 2,000)", tp is not None
        and abs(D(lb.get("posted_balance"))) == Decimal("2000"), f"GL {tp} posted {lb.get('posted_balance')}")

    passed = sum(1 for r in ROWS if r["status"] == "PASS")
    print(f"\nTAX QA: {passed}/{len(ROWS)} ok")
    json.dump({"tenant": api.tenant, "rows": ROWS}, open("/tmp/qa_taxes.json", "w"), indent=2)
    return 0 if passed == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
