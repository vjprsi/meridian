"""Journey H: customer channel (/v1/me BFF): own data only, server-side ownership checks, step-up for payments."""
from __future__ import annotations

import time
from decimal import Decimal

from journeys import (Journey, _short, dec, ensure_account, fail, fund_account, journey, open_deposit_account,
                      wait_for)


def _rows(page) -> list:
    return page.get("data", []) if isinstance(page, dict) else (page or [])


def me(customer_id: str, step_up: bool = False) -> dict:
    """Dev-mode customer identity headers (JWT mode: customer token with customer_id / auth_time claims)."""
    h = {"X-Customer-Id": customer_id}
    if step_up:
        # dev-mode equivalent of a fresh second-factor sign-in (JWT: auth_time + acr "2" + amr)
        h["X-Auth-Time"] = str(int(time.time()))
        h["X-Acr"] = "2"
        h["X-Amr"] = "pwd,otp"
    return h


@journey("H", "Customer channel: /v1/me own accounts, cross-customer denial, step-up own transfer")
def journey_h(j: Journey):
    api, sh = j.api, j.shared
    a1 = ensure_account(j, "account_a", "Amina", "Otieno")
    cust_a = sh["cust_account_a"]
    a2 = j.step("deposit: open Amina's second account", open_deposit_account, j, cust_a, " Amina savings 2")
    b1 = ensure_account(j, "account_b", "Brian", "Kamau")
    cust_b = sh["cust_account_b"]
    fund_account(j, a1, "5000.00", "Amina (customer channel)")

    def my_accounts():
        ids = {r["id"] for r in _rows(api.get("/v1/me/accounts", headers=me(cust_a)))}
        missing = {a1["id"], a2["id"]} - ids
        if missing:
            fail(f"own accounts missing from /v1/me/accounts: {missing}")
        if b1["id"] in ids:
            fail("another customer's account is listed")
        return sorted(ids)
    j.step("me: Amina lists only her own accounts", my_accounts)

    j.step("me: Amina reads her balance", api.get, f"/v1/me/accounts/{a1['id']}/balance", headers=me(cust_a))
    j.step("me: Brian cannot read Amina's account (404)", api.get, f"/v1/me/accounts/{a1['id']}",
           headers=me(cust_b), expect=404)
    j.step("me: Brian cannot read Amina's balance (404)", api.get, f"/v1/me/accounts/{a1['id']}/balance",
           headers=me(cust_b), expect=404)

    transfer = {"kind": "OWN_TRANSFER", "from_account_id": a1["id"], "to_account_id": a2["id"],
                "amount": "250.00", "reference": "e2e own transfer"}

    def no_step_up():
        body = api.post("/v1/me/payments", transfer, headers=me(cust_a), expect=401)
        if (body or {}).get("code") != "step_up_required":
            fail(f"expected step_up_required, got {_short(body)}")
        return body.get("code")
    j.step("me: payment without recent authentication needs step-up (401)", no_step_up)

    j.step("me: Brian cannot pay from Amina's account (404)", api.post, "/v1/me/payments",
           {**transfer, "from_account_id": a1["id"], "to_account_id": b1["id"]}, headers=me(cust_b, True),
           expect=(403, 404))

    forged = {**transfer, "customer_id": cust_b}  # unknown/forged fields must not change ownership
    # the forged customer_id must be ignored: the payment is Amina's (checked below)
    pay = j.step("me: Amina transfers 250.00 between her accounts after step-up", api.post, "/v1/me/payments",
                 forged, headers=me(cust_a, True), expect=(200, 201, 422))
    if isinstance(pay, dict) and pay.get("code"):
        # strict body validation (unknown field rejected) is also acceptable: retry without it
        pay = j.step("me: transfer without the forged field", api.post, "/v1/me/payments", transfer,
                     headers=me(cust_a, True), expect=(200, 201))

    def settled():
        p = api.get(f"/v1/me/payments/{pay['id']}", headers=me(cust_a))
        if p.get("customer_id") not in (None, cust_a):
            fail(f"payment recorded for another customer: {_short(p)}")
        return p if p.get("status") in ("SETTLED", "COMPLETED") else None
    j.step("me: payment settles and belongs to Amina", wait_for, "own transfer settled", settled, 30)

    def moved():
        b = api.get(f"/v1/me/accounts/{a2['id']}/balance", headers=me(cust_a))
        avail = dec(b.get("available_balance") or b.get("posted_balance"))
        return avail if avail >= Decimal("250") else None
    j.step("me: second account shows the 250.00", wait_for, "balance moved", moved, 30)

    j.step("me: Brian cannot read Amina's payment (404)", api.get, f"/v1/me/payments/{pay['id']}",
           headers=me(cust_b), expect=404)
    j.step("me: Brian's payment list does not contain it", lambda: None if pay["id"] not in
           {r["id"] for r in _rows(api.get("/v1/me/payments", headers=me(cust_b)))} else fail("leaked payment"))
    return {"payment": pay["id"], "customer_a": cust_a, "customer_b": cust_b}
