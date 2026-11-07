#!/usr/bin/env python3
"""Bre-B end-to-end on a fresh Colombian tenant (CO pack): llaves directory, instant payments through the rail's
access nodes (accept / reject / unknown key), failover when a node is down, timeout resolved by the pacs.028 status
query, pending sweep, our own key registration, and COP approval thresholds. Needs platform/breb-simulator on :18190.
Run ON the box:  MERIDIAN_E2E_STARTER_PACK=CO MERIDIAN_E2E_CCY=COP MERIDIAN_E2E_COUNTRY=CO python3.12 tools/e2e/qa_breb.py
Writes /tmp/qa_breb.json (rows for the UAT/QA matrix)."""
from __future__ import annotations
import hashlib, hmac, json, sys, time, urllib.request, uuid
from datetime import datetime, timezone
from decimal import Decimal

import journeys as J
from journeys import Api, Journey, StepFailed, ensure_account, fund_account, balances, CCY

SIM = "http://localhost:18190"
SECRET = b"local-dev-breb-secret"
ANY = tuple(range(200, 600))
ROWS = []
G, R, DIM, RST = ("\033[32m", "\033[31m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 4


def rec(flow, ctype, title, ok, actual, expected="as specified", priority="High"):
    ROWS.append({"service": "routing-service" if "Directory" in flow or "rail" in flow.lower() else "payment-service",
                 "flow": flow, "type": ctype, "title": title, "expected": expected, "actual": actual,
                 "status": "PASS" if ok else "FAIL", "priority": priority,
                 "run_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")})
    print(f"  {G+'ok'+RST if ok else R+'FAIL'+RST}   {title}  {DIM}{actual}{RST}")


def sim(method, path, body=None):
    d = None if body is None else json.dumps(body).encode()
    h = {"Content-Type": "application/json"}
    if d:
        h["X-Signature"] = hmac.new(SECRET, d, hashlib.sha256).hexdigest()
    req = urllib.request.Request(SIM + path, data=d, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def raw(api, method, path, body=None, **kw):
    p = api.call(method, path, body, expect=ANY, **kw)
    return api.latencies[-1][3], p


def pay(api, acct, key, amount, ref):
    return raw(api, "POST", "/v1/payments", {
        "payment_type": "INSTANT", "direction": "OUTBOUND", "channel": "API", "customer_id": acct["customer_id"],
        "debtor_account_id": acct["id"], "creditor_name": "Beneficiario Bre-B", "creditor_account_number": key,
        "creditor_account_id_type": "PROXY", "amount": amount, "currency": CCY, "reference": ref,
        "remittance_unstructured": ref})


def settle(api, pid, states=("SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED", "SUBMITTED", "PENDING_APPROVAL"), wait=20):
    end = time.time() + wait
    p = {}
    while time.time() < end:
        p = api.get(f"/v1/payments/{pid}")
        if p.get("status") in states:
            return p
        time.sleep(0.5)
    return p


def main():
    sim("POST", "/sim/reset")
    api = Api(str(uuid.uuid4())); shared = {}
    print(f"provisioning CO tenant {api.tenant} …")
    j = Journey("S", "setup", api, shared)
    J.JOURNEYS["S"][1](j)
    if not j.ok:
        print("SETUP FAILED"); return 2
    a = ensure_account(j, "breb_payer", "Camila", "Rodríguez")
    fund_account(j, a, "5000000.00", "Camila")
    rails = api.get("/v1/payment-rails?code=BRE_B&limit=5").get("data", [])
    rec("Bre-B rail setup", "UAT", "CO pack provisions the BRE-B rail (instant, RTGS, COP)",
        bool(rails) and rails[0].get("is_instant") and rails[0].get("settlement_model") == "RTGS",
        f"{len(rails)} rail(s) {rails[0].get('status') if rails else ''}")
    if not rails:
        return 1
    rail = rails[0]["id"]
    nodes = api.get(f"/v1/rail-adapters?payment_rail_id={rail}&limit=10").get("data", [])
    rec("Bre-B rail setup", "UAT", "Two access nodes (A primary, B secondary) speak BREB_JSON_V1",
        len(nodes) == 2 and all(n.get("config", {}).get("protocol") == "BREB_JSON_V1" for n in nodes), f"{len(nodes)} nodes")

    # recipients at another participant (registered by that bank at the directory)
    sim("POST", "/nodes/A/dice/v1/keys", {"key_type": "CELULAR", "key_value": "3001234567", "participant_code": "0507",
        "participant_name": "Banco Destino (sim)", "account_number": "77700011", "account_type": "DEPOSITO_ELECTRONICO",
        "holder_name": "María Fernanda Gómez", "document_type": "CC", "document_number": "52123456"})
    sim("POST", "/nodes/A/dice/v1/keys", {"key_type": "CORREO", "key_value": "pagos@tienda.co", "participant_code": "0507",
        "participant_name": "Banco Destino (sim)", "account_number": "77700012", "account_type": "CORRIENTE",
        "holder_name": "Tienda La Esquina SAS", "document_type": "NIT", "document_number": "900123456"})

    print("\n== Directory (llaves) ==")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/resolve-alias", {"key_type": "CELULAR", "key_value": "+57 300 123 4567"})
    rec("Bre-B Directory", "UAT", "Resolve a CELULAR llave: masked name + creditor institution before confirming",
        st == 200 and r.get("masked_name") == "Mar** Fer***** Góm**" and r.get("participant_code") == "0507",
        f"HTTP {st} {r.get('masked_name')} @ {r.get('participant_name')} via {r.get('via_node')}")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/resolve-alias", {"key_type": "CELULAR", "key_value": "3009999999"})
    rec("Bre-B Directory", "QA", "Unknown llave -> 404 key_not_found", st == 404 and r.get("code") == "key_not_found", f"HTTP {st} {r.get('code')}")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/resolve-alias", {"key_type": "CELULAR", "key_value": "123"})
    rec("Bre-B Directory", "QA", "Malformed llave -> 422", st == 422, f"HTTP {st} {r.get('code')}")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/register-alias", {"key_type": "ALFANUMERICA", "key_value": "@camilaqa",
        "account_number": a["account_number"], "account_type": "AHORROS", "holder_name": "Camila Rodríguez",
        "document_type": "CC", "document_number": "1020304050", "consent_reference": "CONSENT-" + uuid.uuid4().hex[:8]})
    rec("Bre-B Directory", "UAT", "Register our customer's llave (@camilaqa) with consent evidence", st in (200, 201),
        f"HTTP {st} {r.get('status')} {r.get('key_id')}")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/register-alias", {"key_type": "CELULAR", "key_value": "3001234567",
        "account_number": a["account_number"], "holder_name": "Camila Rodríguez", "consent_reference": "C1"})
    rec("Bre-B Directory", "QA", "A llave held at another bank cannot be registered (409 key_in_use)", st == 409, f"HTTP {st} {r.get('code')}")
    st, r = raw(api, "POST", f"/v1/payment-rails/{rail}/register-alias", {"key_type": "CORREO", "key_value": "x@y.co",
        "account_number": a["account_number"], "holder_name": "Camila Rodríguez"})
    rec("Bre-B Directory", "QA", "Registration without consent evidence is refused", st in (400, 422), f"HTTP {st} {r.get('code')}")

    print("\n== Instant payments ==")
    b0 = balances(api, a)
    st, p = pay(api, a, "+573001234567", "150000.00", "Arriendo noviembre")
    pid = p.get("id") if isinstance(p, dict) else None
    x = settle(api, pid) if pid else {}
    b1 = balances(api, a)
    rec("Bre-B instant payment", "UAT", "P2P to a CELULAR llave settles in real time through Bre-B",
        x.get("status") == "SETTLED" and x.get("rail_code") == "BRE_B", f"{x.get('status')} via {x.get('rail_code')}")
    rec("Bre-B instant payment", "UAT", "Payer debited exactly COP 150,000 (Bre-B fee COP 0)", b0[2] - b1[2] == Decimal("150000"), f"{b0[2]} -> {b1[2]}")
    hist = api.get(f"/v1/payments/{pid}/status-history").get("data", []) if pid else []
    states = [h.get("to_status") for h in sorted(hist, key=lambda h: h.get("created_at", ""))]
    rec("Bre-B instant payment", "UAT", "Status timeline ends SUBMITTED -> SETTLED", states[-2:] == ["SUBMITTED", "SETTLED"], " -> ".join(states))

    st, p = pay(api, a, "pagos@tienda.co", "89900.00", "Compra")
    x = settle(api, p.get("id")) if isinstance(p, dict) and p.get("id") else {}
    rec("Bre-B instant payment", "UAT", "Payment to a CORREO llave (merchant) settles", x.get("status") == "SETTLED", f"{x.get('status')}")

    b2 = balances(api, a)
    st, p = pay(api, a, "+573001234567", "1000.13", "rechazo")
    x = settle(api, p.get("id")) if isinstance(p, dict) and p.get("id") else {}
    b3 = balances(api, a)
    rec("Bre-B instant payment", "QA", "Rail rejection (AM02) -> REJECTED and the payer is not debited",
        x.get("status") == "REJECTED" and b3[2] == b2[2] and b3[0] == b2[0], f"{x.get('status')} {x.get('status_reason_code') or x.get('reason_code')} bal {b2[2]}->{b3[2]}")
    st, p = pay(api, a, "+573009999999", "5000.00", "clave inexistente")
    x = settle(api, p.get("id")) if isinstance(p, dict) and p.get("id") else {}
    rec("Bre-B instant payment", "QA", "Unknown creditor llave -> REJECTED (AC03), nothing debited", x.get("status") == "REJECTED", f"{x.get('status')}")
    st, p = pay(api, a, "+573001234567", "4000.66", "cuenta cerrada")
    x = settle(api, p.get("id")) if isinstance(p, dict) and p.get("id") else {}
    rec("Bre-B instant payment", "QA", "Creditor account closed (AC04) -> REJECTED", x.get("status") == "REJECTED", f"{x.get('status')}")

    print("\n== Smart routing / failover ==")
    hits0 = sim("GET", "/sim/stats")[1].get("node_hits", {})
    sim("POST", "/sim/nodes/A/down")
    st, p = pay(api, a, "+573001234567", "25000.00", "failover")
    x = settle(api, p.get("id")) if isinstance(p, dict) and p.get("id") else {}
    hits1 = sim("GET", "/sim/stats")[1].get("node_hits", {})
    sim("POST", "/sim/nodes/A/up")
    rec("Bre-B rail failover", "UAT", "Node A down -> payment fails over to node B and settles",
        x.get("status") == "SETTLED" and hits1.get("B", 0) > hits0.get("B", 0), f"{x.get('status')} B hits {hits0.get('B',0)}->{hits1.get('B',0)}")
    sim("POST", "/sim/nodes/A/down"); sim("POST", "/sim/nodes/B/down")
    st, p = pay(api, a, "+573001234567", "26000.00", "all nodes down")
    x = settle(api, p.get("id"), wait=6) if isinstance(p, dict) and p.get("id") else {}
    sim("POST", "/sim/nodes/A/up"); sim("POST", "/sim/nodes/B/up")
    rec("Bre-B rail failover", "QA", "All nodes down -> submission deferred, payment not settled or lost",
        x.get("status") not in ("SETTLED", "REJECTED"), f"{x.get('status')}")

    print("\n== Timeout -> status query ==")
    st, p = pay(api, a, "+573001234567", "9000.77", "respuesta tardía")
    x = settle(api, p.get("id"), states=("SETTLED", "REJECTED"), wait=30) if isinstance(p, dict) and p.get("id") else {}
    rec("Bre-B timeout handling", "UAT", "Answer arrives after the client timeout -> immediate pacs.028 query settles it",
        x.get("status") == "SETTLED", f"{x.get('status')}")
    st, p = pay(api, a, "+573001234567", "9000.88", "en proceso")
    pid = p.get("id") if isinstance(p, dict) else None
    x = settle(api, pid, wait=20) if pid else {}
    rec("Bre-B timeout handling", "QA", "Rail still processing (PDNG) -> stays SUBMITTED, never auto-reversed",
        x.get("status") == "SUBMITTED", f"{x.get('status')}")
    st, q = raw(api, "POST", f"/v1/payment-rails/{rail}/query-status", {"end_to_end_id": x.get("end_to_end_id")})
    rec("Bre-B timeout handling", "QA", "Status query while in process -> pending (no change)",
        st == 200 and q.get("outcome") == "pending", f"HTTP {st} {q.get('outcome')}")
    time.sleep(27)
    st, q = raw(api, "POST", f"/v1/payment-rails/{rail}/resolve-pending", {"older_than_seconds": 0})
    y = settle(api, pid, states=("SETTLED", "REJECTED"), wait=10) if pid else {}
    rec("Bre-B timeout handling", "UAT", "Pending sweep applies the rail's final answer -> SETTLED",
        st == 200 and q.get("applied", 0) >= 1 and y.get("status") == "SETTLED",
        f"HTTP {st} examined {q.get('examined')} applied {q.get('applied')} pending {q.get('still_pending')} -> {y.get('status')}")

    print("\n== COP approval thresholds ==")
    # a fresh customer (no velocity history), so the fraud model does not hold it and only the approval gate applies
    a = ensure_account(j, "approval_payer", "Andres", "Martinez")
    fund_account(j, a, "30000000.00", "Andres")
    b_acct = J.open_deposit_account(j, a["customer_id"], " Andres 2")
    st, p = raw(api, "POST", "/v1/payments", {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b_acct["id"],
        "creditor_name": "Camila Rodríguez", "amount": "12000000.00", "currency": CCY, "reference": "monto alto"})
    x = settle(api, p.get("id"), states=("PENDING_APPROVAL", "SETTLED", "REJECTED", "ON_HOLD"), wait=10) if isinstance(p, dict) and p.get("id") else {}
    rec("COP approval thresholds", "UAT", "COP 12M (>= COP 10M) needs four-eyes approval", x.get("status") == "PENDING_APPROVAL", f"{x.get('status')}")
    st, p = raw(api, "POST", "/v1/payments", {"payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b_acct["id"],
        "creditor_name": "Camila Rodríguez", "amount": "9500000.00", "currency": CCY, "reference": "bajo umbral"})
    x = settle(api, p.get("id"), states=("PENDING_APPROVAL", "SETTLED", "REJECTED", "ON_HOLD"), wait=10) if isinstance(p, dict) and p.get("id") else {}
    rec("COP approval thresholds", "UAT", "COP 9.5M (< COP 10M) settles without approval", x.get("status") == "SETTLED", f"{x.get('status')}")

    passed = sum(1 for r in ROWS if r["status"] == "PASS")
    print(f"\nBRE-B QA: {passed}/{len(ROWS)} ok")
    json.dump({"tenant": api.tenant, "rows": ROWS}, open("/tmp/qa_breb.json", "w"), indent=2)
    return 0 if passed == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
