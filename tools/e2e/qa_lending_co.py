#!/usr/bin/env python3
"""Colombian lending rules end-to-end on a fresh CO tenant: TEA (tasa efectiva anual) disclosure, usury cap
(tasa de usura) on the contractual and the default rate, the cap pinned on the loan for audit, and the deposit
interest withholding (retención en la fuente) setting. Run ON the box:
  MERIDIAN_E2E_STARTER_PACK=CO MERIDIAN_E2E_CCY=COP MERIDIAN_E2E_COUNTRY=CO python3.12 tools/e2e/qa_lending_co.py
Writes /tmp/qa_lending_co.json."""
from __future__ import annotations
import json, sys, uuid
from datetime import datetime, timezone
from decimal import Decimal

import journeys as J
from journeys import Api, Journey, ensure_account, CCY
from journey_e_lending import create_loan_product

ANY = tuple(range(200, 600))
ROWS = []


def rec(flow, ctype, title, ok, actual, expected="as specified", priority="High"):
    ROWS.append({"service": "loan-service" if "Deposit" not in flow else "deposit-service", "flow": flow, "type": ctype,
                 "title": title, "expected": expected, "actual": actual, "status": "PASS" if ok else "FAIL",
                 "priority": priority, "run_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")})
    print(f"  {'ok' if ok else 'FAIL'}   {title}  {actual}")


def raw(api, method, path, body=None):
    p = api.call(method, path, body, expect=ANY)
    return api.latencies[-1][3], p


def D(x):
    return Decimal(str(x if x is not None else "0"))


def main():
    api = Api(str(uuid.uuid4())); shared = {}
    print(f"provisioning CO tenant {api.tenant} ...")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print("SETUP FAILED"); return 2
    a = ensure_account(j, "loan_borrower", "Sofia", "Herrera")
    pid, vid = create_loan_product(j)

    print("\n== TEA disclosure ==")
    sim = {"product_id": pid, "product_version_id": vid, "currency": CCY, "principal_amount": "400000",
           "term_months": 12, "repayment_frequency": "MONTHLY"}
    st, s = raw(api, "POST", "/v1/loans/simulate", dict(sim, interest_rate="0.18"))
    cap = s.get("usury_cap") or {}
    rec("Lending TEA", "UAT", "Simulation discloses TEA: 18% nominal monthly = 19.5618% EA",
        st == 200 and D(s.get("effective_annual_rate")) == Decimal("0.195618"), f"HTTP {st} TEA {s.get('effective_annual_rate')} APR {s.get('apr')}")
    rec("Lending TEA", "UAT", "Simulation shows the usury cap, the highest nominal rate and within_cap=true",
        cap.get("within_cap") is True and D(cap.get("max_effective_annual_rate")) == Decimal("0.2502") and D(cap.get("max_nominal_rate")) > 0,
        f"cap {cap.get('max_effective_annual_rate')} max nominal {cap.get('max_nominal_rate')} {cap.get('modality')} within={cap.get('within_cap')}")
    st, s = raw(api, "POST", "/v1/loans/simulate", dict(sim, interest_rate="0.24"))
    rec("Lending TEA", "QA", "Simulation at 24% nominal (26.82% EA) flags within_cap=false",
        st == 200 and (s.get("usury_cap") or {}).get("within_cap") is False and D(s.get("effective_annual_rate")) == Decimal("0.268242"),
        f"HTTP {st} TEA {s.get('effective_annual_rate')} within={(s.get('usury_cap') or {}).get('within_cap')}")

    print("\n== Usury cap at booking ==")
    loan = {"customer_id": a["customer_id"], "product_id": pid, "product_version_id": vid, "currency": CCY,
            "principal_amount": "400000", "term_months": 12, "repayment_frequency": "MONTHLY",
            "disbursement_account_id": a["id"], "repayment_account_id": a["id"], "legal_entity_id": api.shared_le,
            "loan_type": "INSTALMENT", "interest_rate_type": "FIXED", "schedule_type": "ANNUITY"}
    st, r = raw(api, "POST", "/v1/loans", dict(loan, interest_rate="0.24"))
    rec("Lending usury cap", "QA", "Booking above the usury cap is refused (422 usury_rate_exceeded) with the max rate",
        st == 422 and r.get("code") == "usury_rate_exceeded" and "highest nominal" in (r.get("detail") or ""),
        f"HTTP {st} {r.get('code')} | {(r.get('detail') or '')[:140]}")
    st, r = raw(api, "POST", "/v1/loans", dict(loan, interest_rate="0.18", penalty_rate="0.30"))
    rec("Lending usury cap", "QA", "Default (moratorium) rate above the cap is refused",
        st == 422 and r.get("code") == "usury_rate_exceeded" and "moratorium" in (r.get("detail") or ""),
        f"HTTP {st} {r.get('code')} | {(r.get('detail') or '')[:120]}")
    st, r = raw(api, "POST", "/v1/loans", dict(loan, interest_rate="0.18", penalty_rate="0.22"))
    pt = (r.get("product_terms") or {}) if isinstance(r, dict) else {}
    rec("Lending usury cap", "UAT", "Booking within the cap succeeds; TEA and the cap are pinned on the loan",
        st in (200, 201) and D(pt.get("effective_annual_rate")) == Decimal("0.195618")
        and (pt.get("usury_cap") or {}).get("within_cap") is True,
        f"HTTP {st} {r.get('status') if isinstance(r, dict) else ''} TEA {pt.get('effective_annual_rate')} cap {(pt.get('usury_cap') or {}).get('reference')} {'' if st in (200, 201) else str(r)[:300]}")

    print("\n== Deposit interest withholding ==")
    cfg = api.get("/v1/config/entries?namespace=ledger&config_key=deposit-service&status=ACTIVE&limit=1").get("data", [])
    w = (cfg[0].get("value") or {}).get("withholding_tax_rate") if cfg else None
    rec("Deposit withholding", "UAT", "Tenant deposit settings carry retención en la fuente 7% on interest", D(w) == Decimal("0.07"),
        f"withholding_tax_rate {w}")

    passed = sum(1 for r in ROWS if r["status"] == "PASS")
    print(f"\nLENDING CO QA: {passed}/{len(ROWS)} ok")
    json.dump({"tenant": api.tenant, "rows": ROWS}, open("/tmp/qa_lending_co.json", "w"), indent=2)
    return 0 if passed == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
