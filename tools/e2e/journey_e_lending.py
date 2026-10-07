"""Journey E: lending. Loan product (inbox), champion scorecard, application, bureau + score + affordability via
auto-decide, offer, accept, e-signature (sandbox provider callback, HMAC), booking in loan-service, disbursement into
the customer's deposit account, repayment from that account; schedule and ledger checks."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

from journeys import (BASE, CCY, CHECKER, Journey, StepFailed, _short, approve_via_inbox, balances, dec,
                      ensure_account, fail, fund_account, journey, run_id, today, wait_for)

LENDING_CALLBACK_SECRET = os.environ.get("MERIDIAN_LENDING_CALLBACK_SECRET", "dev-lending-callback-secret")


def create_loan_product(j: Journey) -> tuple[str, str]:
    """Instalment loan product: DEBIT_INTEREST 18% fixed, amount/term parameters; maker submits, checker approves
    in the workflow inbox, maker publishes."""
    api = j.api
    if "loan_product" in j.shared:
        return j.shared["loan_product"]
    code = f"LOAN{run_id()}"
    prod = j.step("product-catalog: create instalment loan product", api.post, "/v1/products", {
        "code": code, "name": f"E2E Personal Loan {code}", "family": "INSTALMENT_LOAN",
        "legal_entity_id": api.shared_le, "currency": CCY})
    ver = j.step("product-catalog: create loan version", api.post, "/v1/product-versions", {
        "product_id": prod["id"], "definition": {"loan_type": "INSTALMENT", "schedule_type": "ANNUITY"}})
    vid = ver["id"]

    def terms():
        api.post("/v1/interest-rate-configs", {"product_version_id": vid, "rate_kind": "DEBIT_INTEREST",
                 "rate_type": "FIXED", "currency": CCY, "effective_from": today(), "fixed_rate": "0.18",
                 "day_count": "ACT_365"})
        for key, typ, val in (("lending.min_amount", "AMOUNT", "1000"), ("lending.max_amount", "AMOUNT", "500000"),
                              ("lending.min_term_months", "INTEGER", "3"), ("lending.max_term_months", "INTEGER", "36"),
                              ("lending.offer_validity_days", "INTEGER", "14")):
            api.post("/v1/product-parameters", {"product_version_id": vid, "param_key": key, "param_type": typ,
                                                "value_text": val})
        api.post("/v1/fee-definitions", {"product_version_id": vid, "code": "ORIG", "name": "Origination fee",
                 "trigger": "LOAN_ORIGINATION", "calculation_method": "PERCENTAGE", "percentage": "0.01",
                 "currency": CCY})
        return "18% fixed, 1% origination fee, 1000..500000, 3..36 months"
    j.step("product-catalog: loan rate + lending parameters", terms)
    j.step("product-catalog: maker submits loan version", api.post, f"/v1/product-versions/{vid}/submit", {})
    j.step("workflow: checker approves loan version in inbox", approve_via_inbox, api, "product_version", vid)
    j.step("product-catalog: loan version APPROVED", lambda: wait_for(
        "loan version approved", lambda: api.get(f"/v1/product-versions/{vid}")["status"] in ("APPROVED", "PUBLISHED")))
    if api.get(f"/v1/product-versions/{vid}")["status"] != "PUBLISHED":
        j.step("product-catalog: publish loan version", api.post, f"/v1/product-versions/{vid}/publish", {})
    j.shared["loan_product"] = (prod["id"], vid)
    return prod["id"], vid


def champion_scorecard(j: Journey) -> str:
    """APPLICATION scorecard: base 600, age and income bins; >= 650 APPROVE (grade A), >= 600 REFER, else DECLINE.
    Created by the maker, activated as champion by the checker (model-risk four eyes)."""
    api = j.api
    sc = j.step("loan-origination: create APPLICATION scorecard", api.post, "/v1/scorecards", {
        "code": f"APP{run_id()}", "name": "E2E application scorecard", "scorecard_type": "APPLICATION",
        "is_active": True})
    ver = j.step("loan-origination: scorecard version (features, cut-offs)", api.post,
                 f"/v1/scorecards/{sc['id']}/versions", {
                     "version": "1.0", "base_points": 600, "target_score": 600, "target_odds": "50",
                     "points_to_double_odds": "20",
                     "features": [
                         {"name": "age", "missing_points": -50, "reason_code": "AGE", "description": "Applicant age",
                          "bins": [{"min": 18, "max": 25, "points": 10, "label": "18-24"},
                                   {"min": 25, "max": 70, "points": 40, "label": "25-69"}]},
                         {"name": "monthly_income", "missing_points": -80, "reason_code": "INCOME",
                          "description": "Verified monthly income",
                          "bins": [{"min": 0, "max": 20000, "points": 0, "label": "<20k"},
                                   {"min": 20000, "points": 60, "label": ">=20k"}]}],
                     "cutoffs": [{"min_score": 0, "outcome": "DECLINE", "risk_grade": "E"},
                                 {"min_score": 600, "outcome": "REFER", "risk_grade": "C", "rate_adjustment": "0.04"},
                                 {"min_score": 650, "outcome": "APPROVE", "risk_grade": "A", "rate_adjustment": "0"}]})
    j.step("loan-origination: maker cannot activate own scorecard (four eyes)", api.post,
           f"/v1/scorecards/{sc['id']}/versions/{ver['id']}/activate", {}, expect=(403, 409, 422))
    act = j.step("loan-origination: checker activates champion", api.post,
                 f"/v1/scorecards/{sc['id']}/versions/{ver['id']}/activate", {}, user=CHECKER)
    if act.get("status") != "CHAMPION":
        raise StepFailed(f"scorecard version not CHAMPION: {_short(act)}")
    return ver["id"]


def esign_callback(provider: str, envelope_id: str, status: str):
    """Simulates the e-signature provider's callback (public endpoint, HMAC-SHA256 over the raw body)."""
    body = json.dumps({"provider_envelope_id": envelope_id, "provider_status": status}).encode()
    sig = "sha256=" + hmac.new(LENDING_CALLBACK_SECRET.encode(), body, hashlib.sha256).hexdigest()
    req = urllib.request.Request(f"{BASE}/v1/e-signature-requests/callbacks/{provider.lower()}/events", data=body,
                                 method="POST", headers={"Content-Type": "application/json", "X-Meridian-Signature": sig,
                                                         "Idempotency-Key": str(uuid.uuid4())})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raise StepFailed(f"e-signature callback -> HTTP {e.code}: {e.read()[:800]!r}")


@journey("E", "Lending: application, champion scorecard auto-decide, offer, accept, e-sign, book, disburse, repay")
def journey_e(j: Journey):
    api = j.api
    # The sandbox bureau scores by the customer id's last hex digit (560 + 20*digit; 0 = no hit, 14/15 = thin/
    # delinquent files). The champion approves from 650, so borrow with a customer whose id ends in 5..13.
    acct = None
    for n in range(8):
        cand = ensure_account(j, f"borrower_{n}", "Wanjiku", f"Borrower{n}")
        if 5 <= int(cand["customer_id"][-1], 16) <= 13:
            acct = cand
            break
    if acct is None:
        fail("no sandbox-approvable borrower after 8 customers")
    cid = acct["customer_id"]
    pid, vid = create_loan_product(j)
    champion_scorecard(j)

    app = j.step("loan-origination: create application (50000 KES, 12 months)", api.post, "/v1/loan-applications", {
        "customer_id": cid, "product_id": pid, "product_version_id": vid, "legal_entity_id": api.shared_le,
        "channel": "API", "loan_type": "INSTALMENT", "purpose": "PERSONAL", "requested_amount": "50000.00",
        "currency": CCY, "requested_term_months": 12, "requested_frequency": "MONTHLY",
        "requested_schedule_type": "ANNUITY", "disbursement_account_id": acct["id"],
        "repayment_account_id": acct["id"], "consent_bureau": True, "consent_at": today() + "T08:00:00Z"})
    aid = app["id"]
    applicant = j.step("loan-origination: PRIMARY applicant", api.post, f"/v1/loan-applications/{aid}/applicants", {
        "customer_id": cid, "role": "PRIMARY", "date_of_birth": "1990-04-12", "employment_status": "EMPLOYED",
        "employer_name": "Employer Ltd", "occupation": "Engineer", "employment_start_date": "2018-01-01",
        "monthly_net_income": "120000.00", "dependants": 1, "residential_status": "TENANT"})

    def items():
        for t, amt in (("SALARY", "120000.00"), ("RENT", "25000.00"), ("LIVING_COSTS", "20000.00")):
            api.post(f"/v1/loan-applications/{aid}/financial-items", {
                "loan_applicant_id": applicant["id"], "item_type": t, "amount": amt, "currency": CCY,
                "frequency": "MONTHLY", "is_verified": True, "verification_source": "PAYSLIP"})
        return "salary 120000, rent 25000, living 20000 (monthly)"
    j.step("loan-origination: income and expenses", items)
    j.step("loan-origination: submit", api.post, f"/v1/loan-applications/{aid}/submit", {})

    def decide():
        try:
            return api.post(f"/v1/loan-applications/{aid}/auto-decide", {})
        except StepFailed as e:
            if "bureau_pending" not in str(e):
                raise
        return wait_for("auto-decision after the bureau report", lambda: api.post(
            f"/v1/loan-applications/{aid}/auto-decide", {}, expect=(200, 201, 409)), timeout=30)
    dec_ = j.step("loan-origination: auto-decide (bureau pull, champion score, affordability, policy)", decide)
    if dec_.get("outcome") != "APPROVED":
        raise StepFailed(f"decision {dec_.get('outcome')}: {_short(dec_, 1500)}")
    scores = api.get(f"/v1/loan-applications/{aid}/scores")
    j.step("loan-origination: credit score by the champion", lambda: (lambda s: f"score {s['score']} grade {s['risk_grade']} "
           f"({s.get('routing')})" if s else fail("no score"))((scores.get("data") or [None])[0]))

    def offer():
        offers = api.get(f"/v1/loan-applications/{aid}/offers").get("data", [])
        if offers:
            return offers[0]
        return api.post("/v1/loan-offers", {"loan_application_id": aid, "credit_decision_id": dec_["id"],
                        "offer_amount": dec_["approved_amount"], "currency": CCY,
                        "term_months": dec_.get("approved_term_months") or 12, "repayment_frequency": "MONTHLY",
                        "schedule_type": "ANNUITY", "interest_rate": dec_["approved_rate"]})
    off = j.step("loan-origination: offer issued", offer)
    if off.get("status") != "ISSUED":
        raise StepFailed(f"offer {off.get('status')}: {_short(off)}")
    j.step("loan-origination: offer schedule preview", lambda: (lambda p: f"{len(p.get('data', p.get('installments', [])))} "
           "instalments" if p else fail("empty"))(api.get(f"/v1/loan-offers/{off['id']}/schedule-preview")))
    env = j.step("loan-origination: customer accepts offer (e-signature envelope sent)", api.post,
                 f"/v1/loan-offers/{off['id']}/accept", {})
    env_id = env.get("provider_envelope_id") or env.get("id")
    j.step("e-signature: provider callback SIGNED (HMAC)", esign_callback, env.get("provider", "SANDBOX"), env_id, "SIGNED")

    def booked():
        a = api.get(f"/v1/loan-applications/{aid}")
        if a["status"] in ("BOOKED", "DISBURSED") and a.get("loan_id"):
            return a
        if a["status"] == "SIGNED" and a.get("booking_error"):
            fail(f"booking failed: {a['booking_error']}")
        return None
    app = j.step("loan-origination: application BOOKED in loan-service", lambda: wait_for("booked", booked, timeout=30))
    if app["status"] == "SIGNED":
        app = j.step("loan-origination: book", api.post, f"/v1/loan-applications/{aid}/book", {})
    loan_id = app["loan_id"]
    loan = j.step("loan-service: loan PENDING_DISBURSEMENT", lambda: (lambda l: l if l["status"] in
                  ("PENDING_DISBURSEMENT", "ACTIVE") else fail(f"loan {l['status']}"))(api.get(f"/v1/loans/{loan_id}")))
    fee_gl = j.shared["gl"]["fee_income"]
    fee0 = dec(api.get(f"/v1/ledger-accounts/{fee_gl}/balance").get("posted_balance"))
    before = balances(api, acct)
    j.step("loan-service: disburse to the deposit account", api.post, f"/v1/loans/{loan_id}/disburse", {
        "method": "DEPOSIT_ACCOUNT", "destination_account_id": acct["id"]})
    loan = j.step("loan-service: loan ACTIVE after disbursement", lambda: wait_for("loan active", lambda: (lambda l: l if
                  l["status"] == "ACTIVE" else None)(api.get(f"/v1/loans/{loan_id}")), timeout=30))
    principal = dec(loan["principal_amount"])
    fee = dec((loan.get("product_terms") or {}).get("origination_fee"))
    after = balances(api, acct)
    j.step("balances: deposit account credited with principal less fees", lambda: f"+{after[2] - before[2]}"
           if after[2] - before[2] == principal - fee and after[0] - before[0] == principal - fee
           else fail(f"deposit {before} -> {after}, principal {principal}, fee {fee}"))
    j.step("ledger: origination fee credited to fee income", lambda: (lambda f: f"fee {fee}" if fee > 0 and f - fee0 == fee
           else fail(f"fee income {fee0} -> {f}, loan fee {fee}"))(
        dec(api.get(f"/v1/ledger-accounts/{fee_gl}/balance").get("posted_balance"))))
    sched = j.step("loan-service: repayment schedule", api.get, f"/v1/loans/{loan_id}/schedule")
    rows = sched.get("data") or sched.get("installments") or sched.get("lines") or []

    def check_schedule():
        if len(rows) != int(loan.get("term_months") or 12):
            fail(f"{len(rows)} instalments: {_short(sched)}")
        total_principal = sum(dec(r.get("principal_due", r.get("principal_amount"))) for r in rows)
        if abs(total_principal - principal) > Decimal("0.05"):
            fail(f"schedule principal {total_principal} != {principal}")
        first = rows[0]
        return f"{len(rows)} x {first.get('total_due', first.get('installment_amount'))} from {first.get('due_date')}"
    j.step("loan-service: schedule covers the principal", check_schedule)

    def receivable():
        la = loan.get("ledger_account_id") or loan.get("receivable_account_id")
        if not la:
            fail(f"loan has no ledger account: {_short(loan)}")
        b = api.get(f"/v1/ledger-accounts/{la}/balance")
        if dec(b.get("posted_balance")) != principal:
            fail(f"receivable posted_balance {b.get('posted_balance')} != {principal}")
        return f"receivable {b.get('posted_balance')}"
    j.step("ledger: loan receivable carries the principal", receivable)
    inst = dec(rows[0].get("total_due", rows[0].get("installment_amount")))
    j.shared["loan_e"] = loan_id
    pre = balances(api, acct)
    rep = j.step(f"loan-service: repay first instalment {inst} from the deposit account", api.post,
                 f"/v1/loans/{loan_id}/repay", {"amount": str(inst), "source": "AUTO_DEBIT",
                                                "source_account_id": acct["id"], "external_reference": f"E2E{run_id()}"})
    post = balances(api, acct)
    j.step("balances: deposit debited by the instalment", lambda: f"-{pre[2] - post[2]}" if pre[2] - post[2] == inst
           else fail(f"deposit {pre} -> {post}, instalment {inst}"))
    loan = api.get(f"/v1/loans/{loan_id}")
    j.step("loan-service: outstanding principal reduced", lambda: f"outstanding {loan['outstanding_principal']}"
           if dec(loan["outstanding_principal"]) < principal else fail(_short(loan)))
    reps = j.step("loan-service: repayment recorded and allocated", api.get, f"/v1/loans/{loan_id}/repayments")
    j.final = {"application": aid, "loan": loan_id, "loan_status": loan["status"], "principal": str(principal),
               "outstanding_principal": loan["outstanding_principal"], "instalments": len(rows),
               "repayment": (reps.get("data") or [{}])[0].get("status"), "deposit_balance": str(post[2])}
