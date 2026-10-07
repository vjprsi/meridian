"""Journey F: card program (go-live via the workflow inbox) -> product -> virtual debit card on a deposit account ->
authorisations (approve + decline) through the gateway -> clearing file matched -> balances."""
from __future__ import annotations

import random
import uuid
from decimal import Decimal

from journeys import (CCY, CHECKER, COUNTRY, Journey, StepFailed, _short, approve_via_inbox, balances, dec,
                      ensure_account, fail, fund_account, journey, run_id, today, wait_for)


def _bin() -> str:
    return "4" + "".join(random.choice("0123456789") for _ in range(5))


@journey("F", "Cards: program (inbox go-live), product, virtual card, auth approve/decline, clearing, balances")
def journey_f(j: Journey):
    api = j.api
    acct = ensure_account(j, "account_card", "Carol", "Wanjiru")
    fund_account(j, acct, "5000.00", "Carol")
    b0 = balances(api, acct)

    bin6 = _bin()
    prog = j.step("card: create VISA debit program", api.post, "/v1/card-programs", {
        "code": f"DEB{run_id()}", "name": "E2E Visa Debit", "legal_entity_id": j.shared["legal_entity_id"],
        "scheme": "VISA", "program_type": "CONSUMER", "funding_model": "DEBIT", "bin": bin6,
        "bin_range_start": bin6 + "00", "bin_range_end": bin6 + "99", "pan_length": 16,
        "currency": CCY, "country": COUNTRY, "fraud_policy": "FAIL_OPEN", "stand_in_enabled": False})
    pid = prog["id"]
    j.step("card: maker certifies program (submits go-live)", api.post, f"/v1/card-programs/{pid}/certify", {})
    j.step("workflow: checker approves program go-live in inbox (maker blocked)",
           approve_via_inbox, api, "card_program", pid)
    j.step("card: program ACTIVE", lambda: wait_for("program ACTIVE", lambda: (lambda p: p["status"] if p["status"] == "ACTIVE"
           else None)(api.get(f"/v1/card-programs/{pid}")), timeout=20))

    prod = j.step("card: create product", api.post, "/v1/card-products", {
        "card_program_id": pid, "code": f"VIRT{run_id()}", "name": "E2E virtual debit", "form_factors": ["VIRTUAL"]})
    j.step("card: activate product", api.post, f"/v1/card-products/{prod['id']}/activate", {})

    card = j.step("card: issue virtual card on Carol's deposit account", api.post, "/v1/cards", {
        "card_program_id": pid, "card_product_id": prod["id"], "customer_id": acct["customer_id"],
        "cardholder_name": "CAROL WANJIRU", "form_factor": "VIRTUAL", "linked_account_type": "DEPOSIT_ACCOUNT",
        "deposit_account_id": acct["id"], "ledger_account_id": acct["ledger_account_id"]})
    cid = card["id"]
    if card.get("status") != "ACTIVE":
        card = j.step("card: activate card", api.post, f"/v1/cards/{cid}/activate", {})
    j.step("card: card ACTIVE", lambda: card["status"] if card["status"] == "ACTIVE" else fail(_short(card)))

    def authorise(amount: str, merchant: str, mcc: str = "5411"):
        return api.post("/v1/card-authorizations", {
            "card_id": cid, "message_type": "AUTHORIZATION", "channel": "POS", "pos_entry_mode": "CONTACTLESS",
            "card_present": True, "cardholder_present": True, "merchant_id": f"M{run_id()}", "merchant_name": merchant,
            "merchant_city": "Nairobi", "merchant_country": COUNTRY, "mcc": mcc, "terminal_id": "T0001",
            "stan": str(random.randint(100000, 999999)), "rrn": str(random.randint(10**11, 10**12 - 1)),
            "network_transaction_id": f"NT{uuid.uuid4().hex[:20]}", "transaction_amount": amount,
            "transaction_currency": CCY})

    auth = j.step("card-auth: authorise 1200 at a supermarket (gateway)", authorise, "1200.00", "Naivas Supermarket")
    j.step("card-auth: approved", lambda: auth["status"] if auth.get("decision", auth.get("status")) in ("APPROVED",)
           or auth.get("status") == "APPROVED" else fail(_short(auth, 1500)))
    b1 = balances(api, acct)
    j.step("balances: available reduced by the 1200 hold, posted unchanged",
           lambda: f"available {b1[0]} posted {b1[1]}" if b0[0] - b1[0] == Decimal("1200") and b1[1] == b0[1]
           else fail(f"before {b0} after {b1}"))

    dec_auth = j.step("card-auth: authorise 50000 (above balance) -> declined", authorise, "50000.00", "Electronics Hub", "5732")
    j.step("card-auth: declined for insufficient funds", lambda: f"{dec_auth.get('status')} {dec_auth.get('decline_reason') or dec_auth.get('response_code')}"
           if dec_auth.get("status") == "DECLINED" else fail(_short(dec_auth, 1500)))
    b2 = balances(api, acct)
    j.step("balances: decline leaves available unchanged", lambda: str(b2[0]) if b2[0] == b1[0] else fail(f"{b1} -> {b2}"))

    rec = {"record_type": "PRESENTMENT", "card_id": cid, "network_transaction_id": auth.get("network_transaction_id"),
           "rrn": auth.get("rrn"), "auth_code": auth.get("auth_code") or auth.get("approval_code"),
           "merchant_id": auth.get("merchant_id"), "merchant_name": "Naivas Supermarket", "mcc": "5411",
           "merchant_country": COUNTRY, "transaction_date": today(), "transaction_amount": "1200.00",
           "transaction_currency": CCY, "billing_amount": "1200.00", "billing_currency": CCY,
           "settlement_amount": "1200.00", "settlement_currency": CCY}
    rec = {k: v for k, v in rec.items() if v is not None}
    f = j.step("card-clearing: incoming VISA clearing file with the presentment", api.post, "/v1/card-clearing/files", {
        "scheme": "VISA", "direction": "INCOMING", "file_format": "JSON", "file_name": f"VISA_IN_{run_id()}.json",
        "processing_date": today(), "card_program_id": pid, "currency": CCY, "records": [rec]})

    def matched():
        page = api.get(f"/v1/card-clearing/records?clearing_file_id={f['id']}&limit=10")
        rows = page.get("data", [])
        if not rows:
            return None
        r = rows[0]
        if r.get("match_status") in ("MATCHED", "FORCE_POST"):
            return f"{r['match_status']} by {r.get('match_rule')}"
        if r.get("match_status") in ("UNMATCHED", "EXCEPTION"):
            fail(f"clearing record {r.get('match_status')}: {_short(r, 1500)}")
        return None
    j.step("card-clearing: record matched to the authorisation", lambda: wait_for("clearing match", matched, timeout=20))
    j.step("card-auth: authorisation cleared", lambda: wait_for("auth cleared", lambda: (lambda a: a["status"]
           if a["status"] in ("CLEARED", "CAPTURED", "SETTLED", "COMPLETED") else None)(
               api.get(f"/v1/card-authorizations/{auth['id']}")), timeout=20))
    b3 = balances(api, acct)
    j.step("balances: posted -1200 after clearing; hold released (deposit + ledger)",
           lambda: f"available {b3[0]} posted {b3[1]} ledger {b3[2]}"
           if b0[1] - b3[1] == Decimal("1200") and b0[2] - b3[2] == Decimal("1200") and b0[0] - b3[0] == Decimal("1200")
           else fail(f"start {b0} after-auth {b1} after-clearing {b3}"))
    gl = j.shared["gl"]["card_settlement"]
    icx = j.shared["gl"]["interchange_income"]
    j.step("ledger: VISA settlement credited 1200, interchange income 13.80 (1.15%, per-tenant routing)",
           lambda: (lambda b, i: f"settlement credits {b['posted_credits']} debits {b['posted_debits']}, interchange {i['posted_balance']}"
                    if dec(b["posted_credits"]) == Decimal("1200") and dec(i["posted_credits"]) == Decimal("13.80")
                    else fail(f"settlement {_short(b)} interchange {_short(i)}"))(
               api.get(f"/v1/ledger-accounts/{gl}/balance"), api.get(f"/v1/ledger-accounts/{icx}/balance")))
    j.final = {"card": cid, "card_status": card["status"], "auth_approved": auth.get("status"),
               "auth_declined": dec_auth.get("status"), "available": str(b3[0]), "posted": str(b3[1])}
