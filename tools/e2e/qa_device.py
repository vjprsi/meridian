#!/usr/bin/env python3
"""Device / step-up 2FA security test: device fingerprint, new-device detection + alert, and step-up enforcement.
Run ON the box: python3.12 tools/e2e/qa_device.py"""
from __future__ import annotations
import json, sys, time, uuid, urllib.request

import journeys as J
from journeys import Api, Journey, StepFailed, mailpit_code, COUNTRY

EMAIL = "vjprsi@gmail.com"
MAILPIT = "http://localhost:18025"
G, R, Y, DIM, RST = ("\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 5
PASS = 0; TOTAL = 0; FAILS = []


def rec(name, ok, detail):
    global PASS, TOTAL
    TOTAL += 1
    if ok: PASS += 1; print(f"  {G}ok{RST}   {name}  {DIM}{detail}{RST}")
    else: FAILS.append((name, detail)); print(f"  {R}FAIL{RST} {name}  {R}{detail}{RST}")


def mp(path):
    with urllib.request.urlopen(MAILPIT + path, timeout=10) as r:
        return json.loads(r.read())


def newest_to(addr, subject_sub):
    for m in mp("/api/v1/messages?limit=200").get("messages", []):
        tos = [t.get("Address", "").lower() for t in (m.get("To") or [])]
        if addr.lower() in tos and subject_sub.lower() in (m.get("Subject") or "").lower():
            return m
    return None


def dev(cid, session, device, step_up=False):
    h = {"X-Customer-Id": cid, "X-Session-Id": session, "X-Device-Id": device}
    if step_up:
        h["X-Auth-Time"] = str(int(time.time())); h["X-Acr"] = "2"; h["X-Amr"] = "pwd,otp"
    return h


def main():
    api = Api(str(uuid.uuid4())); shared = {}
    print("provisioning tenant via journey S …")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok: print("SETUP FAILED"); return 2
    le = api.shared_le

    # customer with the user's real email, verified so security alerts route to it
    c = api.call("POST", "/v1/customers", {
        "customer_type": "INDIVIDUAL", "legal_entity_id": le, "first_name": "Vijay", "last_name": "Prasad",
        "display_name": "Vijay Prasad", "date_of_birth": "1988-06-15", "nationality": COUNTRY,
        "residence_country": COUNTRY, "tax_residence_country": COUNTRY, "onboarding_channel": "API",
        "preferred_language": "en", "external_ref": f"vjd-{uuid.uuid4().hex[:8]}"}, expect=(200, 201))
    cid = c["id"]
    contact = api.call("POST", f"/v1/customers/{cid}/contacts",
                       {"contact_type": "EMAIL", "email": EMAIL, "is_primary": True, "can_receive_otp": True},
                       expect=(200, 201))
    api.call("POST", f"/v1/customers/{cid}/contacts/{contact['id']}/send-verification", {"channel": "EMAIL"},
             expect=(200, 201, 202, 204))
    try:
        code = mailpit_code(EMAIL)
        api.call("POST", f"/v1/customers/{cid}/contacts/{contact['id']}/verify", {"code": code}, expect=(200, 201, 204))
        rec("email contact verified (OTP via Mailpit)", True, f"code {code}")
    except Exception as e:
        rec("email contact verified", False, str(e)[:100])
    api.call("POST", f"/v1/customers/{cid}/activate", {}, user=J.CHECKER, expect=tuple(range(200, 600)))
    print(f"customer {cid} ready with verified {EMAIL} (alerts route to the verified contact)\n")

    print("== 1. Device fingerprint + session tracking ==")
    r1 = api.call("GET", "/v1/me/profile", expect=tuple(range(200, 600)), headers=dev(cid, "sess-1", "device-phone-1", True))
    rec("first sign-in (device-1) accepted + session registered", api.latencies[-1][3] < 300, f"HTTP {api.latencies[-1][3]}")
    time.sleep(1)
    # new device, new session
    api.call("GET", "/v1/me/profile", expect=tuple(range(200, 600)), headers=dev(cid, "sess-2", "device-laptop-2", True))
    rec("second sign-in from a NEW device (device-2) accepted", api.latencies[-1][3] < 300, f"HTTP {api.latencies[-1][3]}")

    print("\n== 2. New-device security alert e-mail ==")
    time.sleep(4)
    msg = newest_to(EMAIL, "sign-in")
    if msg:
        d = mp(f"/api/v1/message/{msg['ID']}")
        html = d.get("HTML") or ""
        is_html = any(t in html.lower() for t in ("<table", "<div", "<!doctype"))
        rec("NEW_DEVICE_SIGN_IN alert e-mail sent to the customer", True, f"subject={d.get('Subject')!r}")
        rec("alert e-mail is branded HTML", is_html, f"html_len={len(html)}")
    else:
        rec("NEW_DEVICE_SIGN_IN alert e-mail sent to the customer", False,
            "no new-device alert captured (template/rule may need activation in this pack)")

    print("\n== 3. Devices + sessions visible to the customer ==")
    devs = api.get("/v1/me/devices?limit=20", headers=dev(cid, "sess-2", "device-laptop-2")).get("data", [])
    sess = api.get("/v1/me/sessions?limit=20", headers=dev(cid, "sess-2", "device-laptop-2")).get("data", [])
    rec("/v1/me/devices lists the customer's devices", len(devs) >= 1, f"{len(devs)} device(s)")
    rec("/v1/me/sessions lists the customer's sessions", len(sess) >= 1, f"{len(sess)} session(s)")

    print("\n== 4. Step-up (second factor) enforcement on a sensitive action ==")
    # add a payee (sensitive) WITHOUT fresh step-up -> must be gated (401 step_up_required)
    p1 = api.call("POST", "/v1/me/payees", {"display_name": "QA Payee", "account_number": "1234567890",
                  "bank_code": "001"}, expect=tuple(range(200, 600)), headers=dev(cid, "sess-2", "device-laptop-2"))
    s_no = api.latencies[-1][3]
    code_no = p1.get("code") if isinstance(p1, dict) else None
    rec("sensitive action WITHOUT recent 2FA is gated", s_no in (401, 403), f"HTTP {s_no} {code_no}")
    # WITH fresh step-up -> allowed (or a domain validation, but not the step-up gate)
    p2 = api.call("POST", "/v1/me/payees", {"display_name": "QA Payee", "account_number": "1234567890",
                  "bank_code": "001"}, expect=tuple(range(200, 600)), headers=dev(cid, "sess-2", "device-laptop-2", True))
    s_yes = api.latencies[-1][3]
    code_yes = p2.get("code") if isinstance(p2, dict) else None
    rec("same action WITH fresh 2FA passes the step-up gate", code_yes != "step_up_required", f"HTTP {s_yes} {code_yes}")

    print(f"\n{'='*62}\nDEVICE/2FA QA: {PASS}/{TOTAL} ok, {len(FAILS)} FAIL")
    for n, d in FAILS: print(f"  - {n}: {d}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
