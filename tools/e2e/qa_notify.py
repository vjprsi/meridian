#!/usr/bin/env python3
"""Create a customer with the user's REAL email + phone, run the full onboarding + notification flows, and
capture exactly what the platform emits (from Mailpit). Reports whether emails are HTML-templated or plain text.
Run ON the box: python3.12 tools/e2e/qa_notify.py"""
from __future__ import annotations
import json, sys, time, uuid, urllib.request, urllib.parse
from decimal import Decimal

import journeys as J
from journeys import Api, Journey, StepFailed, create_customer, kyc_customer, ensure_account, fund_account, CCY, COUNTRY

EMAIL = "vjprsi@gmail.com"
PHONE = "+573125764304"
MAILPIT = "http://localhost:18025"


def mp(path):
    with urllib.request.urlopen(MAILPIT + path, timeout=10) as r:
        return json.loads(r.read())


def messages_to(addr, limit=200):
    data = mp(f"/api/v1/messages?limit={limit}")
    out = []
    for m in data.get("messages", []):
        tos = [t.get("Address", "").lower() for t in (m.get("To") or [])]
        if addr.lower() in tos:
            out.append(m)
    return out


def detail(mid):
    return mp(f"/api/v1/message/{mid}")


def classify(html, text):
    h = html or ""
    has_markup = any(t in h.lower() for t in ("<table", "<div", "<body", "<!doctype", "<html", "<td", "style="))
    return "HTML (templated)" if has_markup else ("plain-text (no real HTML)" if h.strip() == (text or "").strip() or not has_markup else "unknown")


def main():
    api = Api(str(uuid.uuid4())); shared = {}
    print(f"provisioning tenant via journey S …")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print("SETUP FAILED"); return 2
    print(f"tenant={api.tenant} le={shared['legal_entity_id']}\n")

    # customer with the user's real contact details
    le = api.shared_le
    c = api.call("POST", "/v1/customers", {
        "customer_type": "INDIVIDUAL", "legal_entity_id": le, "first_name": "Vijay", "last_name": "Prasad",
        "display_name": "Vijay Prasad", "date_of_birth": "1988-06-15", "nationality": COUNTRY,
        "residence_country": COUNTRY, "tax_residence_country": COUNTRY, "gender": "UNDISCLOSED",
        "employment_status": "EMPLOYED", "occupation": "Engineer", "onboarding_channel": "API",
        "preferred_language": "en", "external_ref": f"vj-{uuid.uuid4().hex[:8]}"}, expect=(200, 201))
    cid = c["id"]
    print(f"created customer {cid} (Vijay Prasad)")

    # MOBILE + EMAIL contacts with the real address/number
    api.call("POST", f"/v1/customers/{cid}/contacts",
             {"contact_type": "MOBILE", "phone": PHONE, "is_primary": True, "can_receive_otp": True}, expect=(200, 201))
    econtact = api.call("POST", f"/v1/customers/{cid}/contacts",
                        {"contact_type": "EMAIL", "email": EMAIL, "is_primary": True, "can_receive_otp": True},
                        expect=(200, 201))
    print(f"added EMAIL={EMAIL} and MOBILE={PHONE} contacts")

    # trigger EMAIL verification OTP
    api.call("POST", f"/v1/customers/{cid}/contacts/{econtact['id']}/send-verification", {"channel": "EMAIL"},
             expect=(200, 201, 202, 204))
    print("triggered EMAIL verification OTP (-> notification-service -> SMTP/Mailpit)")
    # trigger SMS verification OTP (sandbox provider)
    try:
        mcontacts = api.get(f"/v1/customers/{cid}/contacts?limit=10").get("data", [])
        mob = next((x for x in mcontacts if x.get("contact_type") == "MOBILE"), None)
        if mob:
            api.call("POST", f"/v1/customers/{cid}/contacts/{mob['id']}/send-verification", {"channel": "SMS"},
                     expect=(200, 201, 202, 204, 400, 422, 500))
            print("triggered SMS verification OTP (sandbox provider)")
    except StepFailed as e:
        print("SMS trigger note:", str(e)[:120])

    time.sleep(3)
    # capture emails addressed to the user
    print(f"\n===== EMAILS captured in Mailpit for {EMAIL} =====")
    msgs = messages_to(EMAIL)
    print(f"{len(msgs)} message(s) to {EMAIL}")
    for m in msgs[:6]:
        d = detail(m["ID"])
        html, text = d.get("HTML") or "", d.get("Text") or ""
        print(f"\n  Subject : {d.get('Subject')}")
        print(f"  From    : {(d.get('From') or {}).get('Address')}")
        print(f"  Type    : {classify(html, text)}  (html_len={len(html)} text_len={len(text)})")
        print(f"  Body    : {(text or html)[:160]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
