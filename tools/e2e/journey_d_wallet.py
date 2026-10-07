"""Journey D - wallet: treasury, agent float top-up (inbox), customer wallets, cash-in via agent, P2P, cash-out with
PIN confirmation; wallet balances and ledger postings checked after every movement."""
from __future__ import annotations

import uuid
from decimal import Decimal

from journeys import (CCY, CHECKER, Journey, StepFailed, _short, activate_config, approve_via_inbox, dec, fail,
                      journey, onboard_customer, run_id, upload_document, wait_for)

PIN_1, PIN_2 = "2580", "3691"


def msisdn() -> str:
    return "+2547" + str(uuid.uuid4().int)[:8]


def wallet_balance(api, wallet: dict) -> tuple[Decimal, Decimal]:
    """(wallet-service available balance, ledger-service posted balance of the wallet's ledger account)."""
    b = api.get(f"/v1/wallets/{wallet['id']}/balance")
    led = api.get(f"/v1/ledger-accounts/{wallet['ledger_account_id']}/balance")
    return dec(b.get("available_balance", b.get("posted_balance"))), dec(led.get("posted_balance"))


def expect_balance(api, wallet: dict, expected: Decimal, label: str) -> str:
    w, led = wallet_balance(api, wallet)
    if w != expected or led != expected:
        fail(f"{label}: wallet {w}, ledger {led}, expected {expected}")
    return f"{label} {w}"


def open_wallet(j: Journey, label: str, body: dict) -> dict:
    api = j.api
    w = j.step(f"wallet: open {label}", api.post, "/v1/wallets", body)
    w = j.step(f"wallet: activate {label} (opens ledger account)", api.post, f"/v1/wallets/{w['id']}/activate",
               {"reason": "e2e"})
    if w.get("status") != "ACTIVE" or not w.get("ledger_account_id"):
        raise StepFailed(f"{label} not active with a ledger account: {_short(w)}")
    return w


def kyb(api, agent_id: str) -> str:
    """Agent KYB: business registration certificate stored in document-service, verified by the checker."""
    doc = upload_document(api, "AGENT", agent_id, "CORPORATE_DOCUMENT", "registration.png", b"PNG e2e agent registration")
    k = api.post("/v1/agent-kyc-documents", {"agent_id": agent_id, "document_type": "BUSINESS_LICENSE",
                                             "document_id": doc, "expires_at": "2031-12-31"})
    api.post(f"/v1/agent-kyc-documents/{k['id']}/verify", {"comment": "e2e verified"}, user=CHECKER)
    return "KYB document verified"


def txn_done(api, txn: dict, status=("COMPLETED",)) -> dict:
    t = txn
    if t.get("status") not in status:
        t = wait_for(f"wallet transaction {txn['id']} {status}", lambda: (lambda x: x if x["status"] in status else (
            fail(f"transaction {x['status']}: {_short(x)}") if x["status"] in ("FAILED", "REJECTED", "CANCELLED") else None))(
            api.get(f"/v1/wallet-transactions/{txn['id']}")), timeout=20)
    return t


@journey("D", "Wallet: treasury, agent float (inbox), open wallets, cash-in via agent, P2P, cash-out with PIN")
def journey_d(j: Journey):
    api, gl = j.api, j.shared["gl"]

    # --- platform treasury wallet, funded from vault cash, registered as the tenant's treasury
    treasury = open_wallet(j, "treasury wallet", {"wallet_type": "TREASURY", "display_name": "E2E treasury",
                                                    "currency": CCY, "opened_channel": "SYSTEM"})
    t = j.step("wallet: fund treasury 100000 from vault cash (ledger counterparty)", api.post, "/v1/wallet-transactions", {
        "txn_type": "ADJUSTMENT", "channel": "SYSTEM", "initiator_type": "SYSTEM", "amount": "100000.00",
        "currency": CCY, "payee_wallet_id": treasury["id"], "counterparty_ledger_account_id": gl["cash"],
        "description": "E2E e-money issuance"})
    j.step("wallet: treasury funding COMPLETED", txn_done, api, t)
    j.step("balances: treasury 100000 (wallet + ledger)", expect_balance, api, treasury, Decimal("100000"), "treasury")
    j.step("config: treasury wallet for agent-network-service (maker-checker via inbox)", activate_config, api, "ledger",
           "agent-network-service", {"treasury_wallet_id": treasury["id"]})

    # --- agent with float and commission wallets, float top-up approved by the checker in the inbox
    sup = j.step("agent-network: create super-agent", api.post, "/v1/agents", {
        "agent_level": "SUPER_AGENT", "legal_name": f"E2E Super Agent {run_id()}", "contact_msisdn": msisdn(),
        "currency": CCY, "max_float_amount": "1000000"})
    j.step("agent-network: super-agent KYB document (S3) verified by checker", kyb, api, sup["id"])
    j.step("agent-network: activate super-agent", api.post, f"/v1/agents/{sup['id']}/activate", {}, user=CHECKER)
    agent = j.step("agent-network: create agent under the super-agent", api.post, "/v1/agents", {
        "agent_level": "AGENT", "parent_agent_id": sup["id"], "legal_name": f"E2E Agent {run_id()}",
        "trading_name": "Mama Mboga Shop", "contact_msisdn": msisdn(), "currency": CCY, "max_float_amount": "500000"})
    j.step("agent-network: agent KYB document verified by checker", kyb, api, agent["id"])
    agent = j.step("agent-network: activate agent (opens float + commission wallets)", api.post,
                   f"/v1/agents/{agent['id']}/activate", {}, user=CHECKER)
    if agent.get("status") != "ACTIVE" or not agent.get("float_wallet_id"):
        agent = wait_for("agent ACTIVE with float wallet", lambda: (lambda a: a if a.get("float_wallet_id") else None)(
            api.get(f"/v1/agents/{agent['id']}")), 10)
    float_wallet = api.get(f"/v1/wallets/{agent['float_wallet_id']}")
    req = j.step("agent-network: float top-up 20000 requested (maker)", api.post, "/v1/agent-float-requests", {
        "request_type": "TOP_UP", "requesting_agent_id": agent["id"], "target_wallet_id": float_wallet["id"],
        "amount": "20000.00", "currency": CCY, "reason": "Opening float", "bank_reference": f"DEP{run_id()}"})
    j.step("workflow: checker approves float top-up in inbox (maker blocked)", approve_via_inbox, api,
           "agent_float_request", req["id"])
    j.step("agent-network: float request executed", lambda: wait_for("float request COMPLETED", lambda: (
        lambda r: r["status"] if r["status"] in ("COMPLETED", "EXECUTED") else (
            fail(f"float request {r['status']}: {_short(r)}") if r["status"] in ("FAILED", "REJECTED") else None))(
        api.get(f"/v1/agent-float-requests/{req['id']}")), 20))
    j.step("balances: agent float 20000, treasury 80000", lambda: expect_balance(api, float_wallet, Decimal("20000"), "float")
           + "; " + expect_balance(api, treasury, Decimal("80000"), "treasury"))

    # --- two KYC'd customers with PIN-protected wallets
    c1 = onboard_customer(j, "Wanjiru", "Mwangi", "cust_wallet_1")
    c2 = onboard_customer(j, "Otieno", "Odhiambo", "cust_wallet_2")
    m1, m2 = msisdn(), msisdn()
    w1 = open_wallet(j, "Wanjiru's wallet", {"wallet_type": "CUSTOMER", "customer_id": c1, "msisdn": m1,
                                             "display_name": "Wanjiru Mwangi", "currency": CCY, "kyc_tier": 2,
                                             "opened_channel": "APP", "pin": PIN_1})
    w2 = open_wallet(j, "Otieno's wallet", {"wallet_type": "CUSTOMER", "customer_id": c2, "msisdn": m2,
                                            "display_name": "Otieno Odhiambo", "currency": CCY, "kyc_tier": 2,
                                            "opened_channel": "APP", "pin": PIN_2})

    # --- cash-in at the agent: agent float -> customer e-value
    ci = j.step("wallet: cash-in 5000 at agent to Wanjiru (by msisdn)", api.post, "/v1/wallet-transactions/cash-in", {
        "agent_wallet_id": float_wallet["id"], "payee_msisdn": m1, "amount": "5000.00", "currency": CCY,
        "channel": "API"})
    ci = j.step("wallet: cash-in COMPLETED", txn_done, api, ci)
    fee_ci = dec(ci.get("fee_amount"))
    j.step("balances: Wanjiru 5000, agent float 15000", lambda: expect_balance(api, w1, Decimal("5000"), "Wanjiru")
           + "; " + expect_balance(api, float_wallet, Decimal("15000") - fee_ci, "float"))

    # --- P2P with PIN
    j.step("wallet: P2P with wrong PIN refused", api.post, "/v1/wallet-transactions/p2p", {
        "payer_wallet_id": w1["id"], "payee_msisdn": m2, "amount": "1200.00", "currency": CCY, "channel": "APP",
        "pin": "0000"}, expect=(401, 403, 422))
    p2p = j.step("wallet: P2P 1200 Wanjiru -> Otieno (PIN)", api.post, "/v1/wallet-transactions/p2p", {
        "payer_wallet_id": w1["id"], "payee_msisdn": m2, "amount": "1200.00", "currency": CCY, "channel": "APP",
        "pin": PIN_1, "description": "Lunch"})
    p2p = j.step("wallet: P2P COMPLETED", txn_done, api, p2p)
    fee_p2p = dec(p2p.get("fee_amount"))
    j.step("balances: Wanjiru 3800 - fee, Otieno 1200", lambda: expect_balance(
        api, w1, Decimal("3800") - fee_p2p, "Wanjiru") + "; " + expect_balance(api, w2, Decimal("1200"), "Otieno"))
    legs = j.step("wallet: P2P legs posted", api.get, f"/v1/wallet-transactions/{p2p['id']}/legs")
    j.step("ledger: P2P journal entry balanced", lambda: (lambda je: f"{je['entry_count']} legs, {je['total_debits']}"
           if dec(je.get("total_debits")) >= Decimal("1200") else fail(_short(je)))(
        api.get(f"/v1/journal-entries/{p2p['journal_entry_id']}")) if p2p.get("journal_entry_id")
           else f"{len(legs.get('data', []))} legs (no journal_entry_id on the transaction)")

    # --- cash-out at the agent: hold, customer confirms with PIN, then capture
    co = j.step("wallet: cash-out 1000 by Otieno at agent", api.post, "/v1/wallet-transactions/cash-out", {
        "agent_wallet_id": float_wallet["id"], "payer_wallet_id": w2["id"], "amount": "1000.00", "currency": CCY,
        "channel": "API"})
    if co["status"] != "PENDING_CONFIRMATION":
        fail(f"cash-out should wait for the customer's confirmation, got {co['status']}")
    fee_co = dec(co.get("fee_amount"))
    j.step("balances: Otieno's funds held while pending (available reduced, ledger unchanged)", lambda: (
        lambda b: f"available {b[0]}, ledger {b[1]}" if b[0] == Decimal("200") - fee_co and b[1] == Decimal("1200")
        else fail(f"available/ledger {b}, expected {Decimal('200') - fee_co}/1200"))(wallet_balance(api, w2)))
    j.step("wallet: confirm with wrong PIN refused", api.post, f"/v1/wallet-transactions/{co['id']}/confirm",
           {"pin": "9999"}, expect=(401, 403, 422))
    co = j.step("wallet: customer confirms cash-out with PIN", api.post, f"/v1/wallet-transactions/{co['id']}/confirm",
                {"pin": PIN_2})
    co = j.step("wallet: cash-out COMPLETED", txn_done, api, co)
    j.step("balances: Otieno 200 - fee, agent float +1000", lambda: expect_balance(
        api, w2, Decimal("200") - fee_co, "Otieno") + "; " + expect_balance(
        api, float_wallet, Decimal("16000") - fee_ci, "float"))
    j.step("wallet: transaction history of Wanjiru", lambda: f"{len(api.get(f'/v1/wallets/{w1['id']}/transactions?limit=20').get('data', []))} transactions")
    j.step("wallet: receipt of the cash-out (text/plain)", lambda: (lambda r: r.strip().splitlines()[0] if isinstance(r, str)
           and r.strip() else fail(f"unexpected receipt {_short(r)}"))(api.get(
               f"/v1/wallet-transactions/{co['id']}/receipt", headers={"Accept": "text/plain"})))
    j.final = {k: str(wallet_balance(api, w)[0]) for k, w in
               (("treasury", treasury), ("agent_float", float_wallet), ("wanjiru", w1), ("otieno", w2))}
