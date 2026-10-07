#!/usr/bin/env python3
"""End-to-end business journeys against a running local Meridian stack (python tools/dev.py up --phase 4).

Every call goes through the edge gateway (default http://localhost:18000, override with MERIDIAN_GATEWAY_URL) in
dev security mode: X-Tenant-Id / X-User / X-Roles headers instead of tokens. Each run uses a fresh demo tenant, so the
script is idempotent across reruns. Every POST carries an Idempotency-Key.

    python tools/e2e/journeys.py            # all journeys
    python tools/e2e/journeys.py A B        # selected journeys
    python tools/e2e/journeys.py --tenant <uuid> D   # reuse a tenant (re-running is safe: new codes per run)
    python tools/e2e/journeys.py --manual-setup      # setup S by hand instead of the tenant starter pack

Journeys: A customer & account, B money movement, C maker-checker, D wallet, E lending, F cards, G end of day.
S (setup) always runs first: it applies the tenant starter pack (default KE, override with MERIDIAN_E2E_STARTER_PACK)
through tenant-service, approves its maker-checker items in the workflow inbox as the checker, activates its limit
rules, resumes the run until it completes, and reads the legal entity, ledger book and internal accounts from the
pack's item registry. --manual-setup creates the legal entity, book, accounts, routing and notification setup by hand.
A..C live in this file; D..G in tools/e2e/journey_<x>_*.py modules (registered with @journey). Stdlib only.
"""
from __future__ import annotations

import json
import os
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, timedelta
from decimal import Decimal

BASE = os.environ.get("MERIDIAN_GATEWAY_URL", "http://localhost:18000").rstrip("/")
MAKER = "e2e-maker"
CHECKER = "e2e-checker"
CHECKER_ROLES = "approver,checker,admin,ops,risk,credit,finance,compliance,treasury"
CCY = "KES"
COUNTRY = "KE"

GREEN, RED, YELLOW, DIM, RESET = ("\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m") if sys.stdout.isatty() else ("",) * 5


class StepFailed(Exception):
    pass


class Api:
    """Thin JSON client: records latency of every call."""

    def __init__(self, tenant: str):
        self.tenant = tenant
        self.latencies: list[tuple[str, str, float, int]] = []
        self.shared_le: str | None = None

    def call(self, method: str, path: str, body=None, user: str = MAKER, expect=None, key: str | None = None,
             roles: str | None = None, headers: dict | None = None):
        url = BASE + path
        data = None if body is None else json.dumps(body).encode()
        h = {"X-Tenant-Id": self.tenant, "X-User": user, "Accept": "application/json"}
        if roles is None and user == CHECKER:
            roles = CHECKER_ROLES
        if roles:
            h["X-Roles"] = roles
        if data is not None:
            h["Content-Type"] = "application/json"
        if method in ("POST", "PUT", "PATCH"):
            h["Idempotency-Key"] = key or str(uuid.uuid4())
        if headers:
            h.update(headers)
        if method == "POST" and data is None:
            data = b"{}"
            h["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, raw = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            raise StepFailed(f"{method} {path}: transport error {e}")
        ms = (time.perf_counter() - t0) * 1000
        self.latencies.append((method, _norm(path), ms, status))
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = raw.decode(errors="replace")
        if expect is None:
            expect = (200, 201, 202, 204)
        elif isinstance(expect, int):
            expect = (expect,)
        if status not in expect:
            raise StepFailed(f"{method} {path} -> HTTP {status} (expected {expect}) as {user}\n"
                             f"      request:  {_short(body)}\n      response: {_short(payload, 1500)}")
        return payload

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.call("POST", path, body if body is not None else {}, **kw)

    def patch(self, path, body, **kw):
        return self.call("PATCH", path, body, **kw)


def _norm(path: str) -> str:
    parts = []
    for p in path.split("?")[0].split("/"):
        try:
            uuid.UUID(p)
            parts.append("{id}")
        except ValueError:
            parts.append(p)
    return "/".join(parts)


def _short(v, n=600):
    s = v if isinstance(v, str) else json.dumps(v)
    return s if s is None or len(s) <= n else s[:n] + "..."


class Journey:
    def __init__(self, code: str, title: str, api: Api, shared: dict):
        self.code, self.title, self.api, self.shared = code, title, api, shared
        self.steps: list[tuple[str, bool, str]] = []
        self.ok = True
        self.final: dict = {}

    def step(self, name: str, fn, *args, optional=False, **kw):
        """Runs one step; on failure records it and (unless optional) aborts the journey."""
        t0 = time.perf_counter()
        try:
            out = fn(*args, **kw)
            ms = (time.perf_counter() - t0) * 1000
            detail = out if isinstance(out, str) else ""
            print(f"  {GREEN}PASS{RESET} {self.code}.{len(self.steps) + 1:02d} {name} {DIM}({ms:.0f} ms){RESET}"
                  + (f" {DIM}- {detail}{RESET}" if detail else ""))
            self.steps.append((name, True, detail))
            return out
        except Exception as e:  # noqa: BLE001 - report everything
            msg = str(e) if isinstance(e, StepFailed) else f"{type(e).__name__}: {e}\n{traceback.format_exc()}"
            tag = f"{YELLOW}WARN{RESET}" if optional else f"{RED}FAIL{RESET}"
            print(f"  {tag} {self.code}.{len(self.steps) + 1:02d} {name}\n      {msg}")
            self.steps.append((name, False, msg))
            if not optional:
                self.ok = False
                raise
            return None


def wait_for(desc: str, fn, timeout: float = 30, interval: float = 0.5):
    """Polls fn until it returns a truthy value; raises StepFailed with the last observation."""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except StepFailed as e:
            last = str(e)
        time.sleep(interval)
    raise StepFailed(f"timed out after {timeout:.0f}s waiting for {desc}; last observation: {_short(last, 1200)}")


def fail(msg: str):
    raise StepFailed(msg)


def today() -> str:
    return date.today().isoformat()


def dec(v) -> Decimal:
    return Decimal(str(v if v is not None else "0"))


def run_id() -> str:
    return uuid.uuid4().hex[:6].upper()


# ---------------------------------------------------------------------------------------------------------------
# Shared helpers: central approvals (workflow inbox)
# ---------------------------------------------------------------------------------------------------------------

def find_pending_approval(api: Api, resource_type: str, resource_id: str, action: str | None = None, timeout=20):
    def look():
        page = api.get(f"/v1/approval-requests?status=PENDING&resource_type={resource_type}&resource_id={resource_id}&limit=20",
                       user=CHECKER)
        for r in page.get("data", []):
            if action is None or r.get("action") == action:
                return r
        return None
    return wait_for(f"PENDING approval request {resource_type}/{resource_id}", look, timeout)


def approve_via_inbox(api: Api, resource_type: str, resource_id: str, action: str | None = None,
                      check_maker_blocked: bool = True) -> dict:
    """Finds the pending request, checks it is in the checker's inbox (and the maker cannot approve), approves it."""
    req = find_pending_approval(api, resource_type, resource_id, action)
    rid = req["id"]
    inbox = api.get("/v1/approval-requests/inbox?limit=100", user=CHECKER)
    if not any(r["id"] == rid for r in inbox.get("data", [])):
        raise StepFailed(f"approval request {rid} not in checker inbox: {_short(inbox)}")
    if check_maker_blocked:
        api.post(f"/v1/approval-requests/{rid}/approve", {"comment": "self approval"}, user=MAKER,
                 roles=CHECKER_ROLES, expect=(403, 409, 422))
    levels = 0
    while True:
        levels += 1
        out = api.post(f"/v1/approval-requests/{rid}/approve", {"comment": "e2e checker approves"},
                       user=CHECKER if levels == 1 else f"{CHECKER}{levels}")
        status = out.get("status")
        if status in ("APPROVED", "EXECUTED") or levels > 4:
            return out
        if status != "PENDING":
            raise StepFailed(f"approval request {rid} ended {status}: {_short(out)}")


# Journey modules register themselves here.
JOURNEYS: dict[str, tuple[str, callable]] = {}


def journey(code: str, title: str):
    def deco(fn):
        JOURNEYS[code] = (title, fn)
        return fn
    return deco

# Internal ledger accounts of the demo tenant: (key, code, name, type, normal balance)
INTERNAL_ACCOUNTS = [
    ("cash", "1000", "Vault cash", "ASSET", "DEBIT"),
    ("clearing", "1100", "Bank clearing / settlement", "ASSET", "DEBIT"),
    ("card_settlement", "1200", "Card scheme settlement", "ASSET", "DEBIT"),
    ("interest_receivable", "1300", "Loan interest receivable", "ASSET", "DEBIT"),
    ("fee_receivable", "1310", "Loan fee receivable", "ASSET", "DEBIT"),
    ("penalty_receivable", "1320", "Loan penalty receivable", "ASSET", "DEBIT"),
    ("interest_suspense", "2400", "Interest in suspense", "LIABILITY", "CREDIT"),
    ("tax_payable", "2500", "Tax payable", "LIABILITY", "CREDIT"),
    ("overpayment", "2600", "Loan overpayments", "LIABILITY", "CREDIT"),
    ("interest_income", "4000", "Interest income", "INCOME", "CREDIT"),
    ("fee_income", "4100", "Fee income", "INCOME", "CREDIT"),
    ("penalty_income", "4200", "Penalty income", "INCOME", "CREDIT"),
    ("recovery_income", "4300", "Recovery income", "INCOME", "CREDIT"),
    ("interchange_income", "4400", "Interchange income", "INCOME", "CREDIT"),
    ("interest_expense", "5000", "Interest expense", "EXPENSE", "DEBIT"),
    ("write_off_expense", "5100", "Write-off expense", "EXPENSE", "DEBIT"),
    ("waiver_expense", "5200", "Waiver expense", "EXPENSE", "DEBIT"),
    ("agency_commission", "5300", "Agency commission expense", "EXPENSE", "DEBIT"),
]


def ledger_routing(gl: dict) -> dict:
    """config-service namespace "ledger": one key per service, mirroring its meridian.<x>.* ledger properties."""
    return {
        "deposit-service": {"gl": {"interest_expense": gl["interest_expense"], "interest_income": gl["interest_income"],
                                   "fee_income": gl["fee_income"], "tax_payable": gl["tax_payable"],
                                   "clearing": gl["clearing"]}},
        "payment-service": {"gl": {"clearing": gl["clearing"], "fee_income": gl["fee_income"],
                                   "direct_debit_clearing": gl["clearing"]}},
        "wallet-service": {"gl": {"fee_income": gl["fee_income"], "tax_payable": gl["tax_payable"],
                                  "bank_clearing": gl["clearing"]}},
        "loan-service": {"gl": {k: gl[k] for k in (
            "interest_receivable", "interest_income", "interest_suspense", "fee_receivable", "fee_income",
            "penalty_receivable", "penalty_income", "write_off_expense", "recovery_income", "agency_commission",
            "clearing", "overpayment", "tax_payable", "waiver_expense")}},
        "card-clearing-service": {"clearing": {
            "settlement_accounts": {"VISA": gl["card_settlement"], "MASTERCARD": gl["card_settlement"],
                                    "LOCAL": gl["card_settlement"]},
            "fee_income_account": gl["fee_income"], "interchange_income_account": gl["interchange_income"],
            "nostro_account": gl["clearing"], "tax_account": gl["tax_payable"]}},
    }


def activate_config(api: Api, namespace: str, key: str, value: dict) -> dict:
    """config-service maker-checker: create DRAFT, submit (approval request), checker approves in the inbox,
    the entry becomes ACTIVE (activated by the approval outcome, or by the checker explicitly)."""
    e = api.post("/v1/config/entries", {"namespace": namespace, "config_key": key, "value": value,
                                         "change_reason": "e2e tenant bootstrap"})
    api.post(f"/v1/config/entries/{e['id']}/submit", {"change_reason": "e2e tenant bootstrap"})
    approve_via_inbox(api, "config_entry", e["id"], check_maker_blocked=False)

    def active():
        cur = api.get(f"/v1/config/entries/{e['id']}")
        return cur if cur["status"] == "ACTIVE" else None
    try:
        return wait_for(f"config entry {namespace}/{key} ACTIVE", active, timeout=10)
    except StepFailed:
        api.post(f"/v1/config/entries/{e['id']}/activate", {}, user=CHECKER)
        return wait_for(f"config entry {namespace}/{key} ACTIVE", active, timeout=10)


STARTER_PACK = os.environ.get("MERIDIAN_E2E_STARTER_PACK", "KE")
MANUAL_SETUP = False


def starter_pack_parameters() -> dict:
    """Overrides of the pack defaults for the local stack: real e-mail through Mailpit, alerts by e-mail / in-app."""
    smtp = os.environ.get("MERIDIAN_SMTP", "localhost:11025").split(":")
    return {"currency": CCY, "country": COUNTRY, "legal_name": "E2E Demo Bank Ltd", "alert_channels": ["EMAIL", "IN_APP"],
            "smtp_host": smtp[0], "smtp_port": int(smtp[1]), "email_sandbox": False,
            "email_sender": "no-reply@e2e.meridian.example", "sms_sender_id": "MERIDIAN"}


def approve_request(api: Api, rid: str) -> dict:
    """Checker approves one approval request (every level)."""
    levels = 0
    while True:
        levels += 1
        out = api.post(f"/v1/approval-requests/{rid}/approve", {"comment": "e2e checker approves starter pack item"},
                       user=CHECKER if levels == 1 else f"{CHECKER}{levels}", roles=CHECKER_ROLES)
        status = out.get("status")
        if status in ("APPROVED", "EXECUTED") or levels > 4:
            return out
        if status != "PENDING":
            raise StepFailed(f"approval request {rid} ended {status}: {_short(out)}")


def _run_summary(run: dict) -> str:
    bad = [f"{i['key']}: {i.get('outcome')}/{i.get('status')} {i.get('message', '')}"
           for s in run.get("results", []) for i in s.get("items", [])
           if i.get("outcome") in ("FAILED", "BLOCKED") or i.get("status") in ("FAILED", "BLOCKED")]
    return (f"{run.get('status')}: created {run.get('created_count')}, unchanged {run.get('unchanged_count')}, "
            f"awaiting {run.get('awaiting_approval_count')}, failed {run.get('failed_count')}"
            + (f"; {'; '.join(bad[:5])}" if bad else "") + (f"; error {run.get('error')}" if run.get("error") else ""))


@journey("S", "Setup: tenant starter pack (legal entity, ledger, routing, products, tariffs, limits, alerts, EOD)")
def journey_setup(j: Journey):
    if MANUAL_SETUP:
        return journey_setup_manual(j)
    api, sh = j.api, j.shared
    pack = STARTER_PACK

    def apply():
        run = api.post("/v1/starter-pack-runs/apply", {"pack_code": pack, "parameters": starter_pack_parameters()})
        if run["status"] == "RUNNING":  # longer than tenant-service's synchronous wait
            run = wait_for("starter pack run finished", lambda: (lambda r: r if r["status"] != "RUNNING" else None)(
                api.get(f"/v1/starter-pack-runs/{run['id']}")), timeout=300, interval=2)
        if run["status"] not in ("AWAITING_APPROVAL", "COMPLETED"):
            fail(_run_summary(run))
        sh["starter_pack_run"] = run
        return _run_summary(run) + f", {len(run.get('approval_request_ids') or [])} approval requests"
    j.step(f"starter pack: apply {pack} as the maker (maker-checker items submitted, not approved)", apply)
    pending = sh["starter_pack_run"].get("approval_request_ids") or []
    if pending:
        j.step("workflow: maker cannot approve the pack's own requests (four-eyes)", api.post,
               f"/v1/approval-requests/{pending[0]}/approve", {"comment": "self approval"}, user=MAKER,
               roles=CHECKER_ROLES, expect=(403, 409, 422))

        def approve_all():
            for rid in pending:
                approve_request(api, rid)
            return f"{len(pending)} requests approved"
        j.step("workflow: checker approves the pack's routing, products, plan and templates in the inbox", approve_all)

    def activate_limits():
        items = api.get(f"/v1/starter-pack-items?pack_code={pack}&status=AWAITING_ACTIVATION&limit=200").get("data", [])
        for i in items:
            # onboarding journeys are published (four-eyes) rather than activated
            action = "publish" if i["resource_path"].rstrip("/").endswith("/onboarding/journeys") else "activate"
            api.post(f"{i['resource_path']}/{i['resource_id']}/{action}", {"comment": "e2e checker activates"}, user=CHECKER)
        return f"{len(items)} items activated/published by the checker"
    j.step("checker activates the pack's limit rules and publishes its sign-up journey (four-eyes)", activate_limits)

    def resume():
        cur = sh["starter_pack_run"]
        if cur["status"] == "COMPLETED":
            return cur
        nxt = api.post(f"/v1/starter-pack-runs/{cur['id']}/resume", {})
        sh["starter_pack_run"] = nxt
        return nxt if nxt["status"] == "COMPLETED" else None
    done = j.step("starter pack: resume until COMPLETED (publishes products, activates wallets once routing is live)",
                  lambda: _run_summary(wait_for("starter pack run COMPLETED", resume, timeout=120, interval=3)))

    def registry():
        items = {i["item_key"]: i for i in api.get(f"/v1/starter-pack-items?pack_code={pack}&limit=200").get("data", [])}
        le, book = items["legal_entity"]["resource_id"], items["ledger_book"]["resource_id"]
        gl = {k.split("/", 1)[1]: i["resource_id"] for k, i in items.items() if k.startswith("ledger_book/")}
        missing = [k for k, *_ in INTERNAL_ACCOUNTS if k not in gl]
        if missing:
            fail(f"pack chart lacks {missing}")
        sh["legal_entity_id"] = api.shared_le = le
        sh["ledger_id"] = book
        sh["gl"] = gl
        return f"legal entity {le}, book {book}, {len(gl)} internal accounts"
    j.step("starter pack: legal entity, ledger book and internal accounts from the item registry", registry)
    j.step("notification: payment alert e-mail template ACTIVE", lambda: "ACTIVE" if any(
        t["channel"] == "EMAIL" for t in api.get("/v1/notification-templates?code=PAYMENT_SETTLED&status=ACTIVE").get("data", []))
        else fail("PAYMENT_SETTLED e-mail template not active"))
    j.final = {"legal_entity": sh["legal_entity_id"], "ledger": sh["ledger_id"], "starter_pack": f"{pack} {done}"}


def journey_setup_manual(j: Journey):
    """The original hand-made setup S (kept for comparison and as a fallback: --manual-setup)."""
    api, sh = j.api, j.shared
    le = j.step("tenant: create legal entity", api.post, "/v1/legal-entities", {
        "code": f"LE{run_id()}", "legal_name": "E2E Demo Bank Ltd", "country": COUNTRY, "base_currency": CCY})
    sh["legal_entity_id"] = api.shared_le = le["id"]
    book = j.step("ledger: create ledger book", api.post, "/v1/ledgers", {
        "code": f"MAIN-{run_id()}", "name": "E2E main book", "legal_entity_id": le["id"], "base_currency": CCY})
    sh["ledger_id"] = book["id"]
    gl = {}

    def accounts():
        for key, code, name, typ, nb in INTERNAL_ACCOUNTS:
            a = api.post("/v1/ledger-accounts", {"ledger_id": book["id"], "code": code, "name": name,
                                                 "account_type": typ, "normal_balance": nb, "currency": CCY,
                                                 "allow_negative": True, "owner_type": "BANK"})
            gl[key] = a["id"]
        return f"{len(gl)} accounts"
    j.step("ledger: create internal accounts", accounts)
    sh["gl"] = gl
    j.step("config: default ledger book (maker-checker via inbox)", activate_config, api, "ledger", "default",
           {"ledger_id": book["id"]})
    for svc, value in ledger_routing(gl).items():
        j.step(f"config: ledger routing for {svc} (maker-checker via inbox)", activate_config, api, "ledger", svc, value)

    def providers():
        smtp = os.environ.get("MERIDIAN_SMTP", "localhost:11025").split(":")
        api.post("/v1/notification-providers", {"channel": "EMAIL", "provider_name": "Local SMTP (Mailpit)",
                 "priority": 1, "sender_id": "no-reply@e2e.meridian.example", "status": "ACTIVE",
                 "config": {"smtp_host": smtp[0], "smtp_port": int(smtp[1])}})
        api.post("/v1/notification-providers", {"channel": "SMS", "provider_name": "Sandbox SMS", "priority": 1,
                 "sender_id": "MERIDIAN", "status": "ACTIVE", "config": {"sandbox": True}})
        api.post("/v1/notification-providers", {"channel": "PUSH", "provider_name": "Sandbox push", "priority": 1,
                 "status": "ACTIVE", "config": {"sandbox": True}})
        return "EMAIL via SMTP, SMS + PUSH sandbox"
    j.step("notification: tenant channel providers", providers)

    def alert_rule():
        for ch in ("EMAIL", "IN_APP"):
            t = api.post("/v1/notification-templates", {"code": "PAYMENT_SETTLED", "channel": ch, "locale": "en",
                         "category": "TRANSACTIONAL", "subject": "Payment {{status}}",
                         "body": "Your payment of {{amount}} {{currency}} is {{status}} (ref {{end_to_end_id}})."})
            api.post(f"/v1/notification-templates/{t['id']}/publish", {})
            approve_via_inbox(api, "notification_template", t["id"], check_maker_blocked=False)
            wait_for("template ACTIVE", lambda: api.get(f"/v1/notification-templates/{t['id']}")["status"] == "ACTIVE", 60)
        api.post("/v1/notification-rules", {"event_type": "payments.payment.settled", "template_code": "PAYMENT_SETTLED",
                 "channels": ["EMAIL", "IN_APP"], "recipient_expression": "CUSTOMER:customer_id",
                 "category": "TRANSACTIONAL", "is_active": True})
        return "EMAIL (customer's verified contact) / IN_APP alert on payments.payment.settled; templates approved in inbox"
    j.step("notification: payment alert template (inbox) + rule", alert_rule)
    j.final = {"legal_entity": le["id"], "ledger": book["id"]}



def main(argv: list[str]) -> int:
    global MANUAL_SETUP
    tenant = None
    if "--manual-setup" in argv:
        MANUAL_SETUP = True
        argv.remove("--manual-setup")
    if "--tenant" in argv:
        i = argv.index("--tenant")
        tenant = argv[i + 1]
        del argv[i:i + 2]
    selected = [a.upper() for a in argv if a.upper() != "S"] or sorted(c for c in JOURNEYS if c != "S")
    selected = ["S"] + selected  # setup always runs first (fresh tenant)
    unknown = [c for c in selected if c not in JOURNEYS]
    if unknown:
        print(f"unknown journey {' '.join(unknown)}; known: {' '.join(sorted(JOURNEYS))}")
        return 2
    tenant = tenant or str(uuid.uuid4())
    api = Api(tenant)
    shared: dict = {}
    print(f"Meridian e2e journeys via {BASE}  tenant={tenant}  maker={MAKER} checker={CHECKER}")
    results = []
    for code in selected:
        if code not in JOURNEYS:
            print(f"unknown journey {code}; known: {' '.join(sorted(JOURNEYS))}")
            return 2
        title, fn = JOURNEYS[code]
        print(f"\n== {code}: {title}")
        j = Journey(code, title, api, shared)
        t0 = time.perf_counter()
        try:
            fn(j)
        except Exception:  # noqa: BLE001 - already reported by step()
            j.ok = False
        if code == "S" and not j.ok:
            print("setup failed; journeys skipped")
            results.append((j, time.perf_counter() - t0))
            break
        results.append((j, time.perf_counter() - t0))
    print("\n== Summary")
    for j, secs in results:
        passed = sum(1 for _, ok, _ in j.steps if ok)
        state = f"{GREEN}PASS{RESET}" if j.ok else f"{RED}FAIL{RESET}"
        print(f"  {state} {j.code} {j.title}: {passed}/{len(j.steps)} steps, {secs:.1f}s"
              + (f"  final: {json.dumps(j.final)}" if j.final else ""))
    lat = sorted(api.latencies, key=lambda x: -x[2])
    if lat:
        ms = sorted(x[2] for x in lat)
        print(f"\n== Latency: {len(ms)} calls, p50 {ms[len(ms) // 2]:.0f} ms, p95 {ms[int(len(ms) * .95)]:.0f} ms, "
              f"max {ms[-1]:.0f} ms. Slowest:")
        for m, p, t, s in lat[:8]:
            print(f"     {t:7.0f} ms  {s}  {m} {p}")
    return 0 if all(j.ok for j, _ in results) else 1


# ---------------------------------------------------------------------------------------------------------------
# Journeys
# ---------------------------------------------------------------------------------------------------------------


def create_customer(api: Api, first: str, last: str) -> dict:
    le = api.shared_le
    c = api.post("/v1/customers", {
        "customer_type": "INDIVIDUAL", "legal_entity_id": le, "first_name": first, "last_name": last,
        "display_name": f"{first} {last}", "date_of_birth": "1990-04-12", "nationality": COUNTRY,
        "residence_country": COUNTRY, "tax_residence_country": COUNTRY, "gender": "UNDISCLOSED",
        "employment_status": "EMPLOYED", "occupation": "Engineer", "onboarding_channel": "API",
        "preferred_language": "en", "external_ref": f"e2e-{uuid.uuid4().hex[:10]}"})
    return c


def upload_document(api: Api, owner_type: str, owner_id: str, doc_type: str, name: str, content: bytes) -> str:
    """document-service: register, PUT the bytes to the pre-signed URL, complete the upload."""
    import hashlib
    d = api.post("/v1/documents", {"owner_type": owner_type, "owner_id": owner_id, "document_type": doc_type,
                                   "file_name": name, "content_type": "image/png", "classification": "CONFIDENTIAL"})
    up = api.post(f"/v1/documents/{d['id']}/upload-url", {})
    url = up.get("upload_url") or up.get("url")
    if url:
        req = urllib.request.Request(url, data=content, method=up.get("method", "PUT"),
                                     headers={"Content-Type": "image/png", **(up.get("headers") or {})})
        try:
            urllib.request.urlopen(req, timeout=20).read()
        except urllib.error.HTTPError as e:
            raise StepFailed(f"upload to {url} -> HTTP {e.code}: {e.read()[:300]!r}")
    api.post(f"/v1/documents/{d['id']}/complete-upload", {"size_bytes": len(content),
             "checksum": hashlib.sha256(content).hexdigest()})
    return d["id"]


def mailpit_code(address: str, timeout=30) -> str:
    """Reads the newest 6-digit code mailed to address from Mailpit (local SMTP sink, http://localhost:18025)."""
    import re
    base = os.environ.get("MERIDIAN_MAILPIT_URL", "http://localhost:18025")

    def look():
        q = urllib.parse.quote(f"to:{address}")
        with urllib.request.urlopen(f"{base}/api/v1/search?query={q}&limit=1", timeout=10) as r:
            msgs = json.loads(r.read()).get("messages", [])
        if not msgs:
            return None
        with urllib.request.urlopen(f"{base}/api/v1/message/{msgs[0]['ID']}", timeout=10) as r:
            body = json.loads(r.read())
        m = re.search(r"\b(\d{6})\b", body.get("Text", "") + " " + body.get("Subject", ""))
        return m.group(1) if m else None
    return wait_for(f"verification e-mail to {address} in Mailpit", look, timeout)


def kyc_customer(j: Journey, customer_id: str, first: str, last: str) -> dict:
    """Customer data + KYC evidence through the sandbox provider, evaluation, then a KYC tier upgrade that the
    checker approves in the workflow inbox."""
    api = j.api
    email = f"{first.lower()}.{uuid.uuid4().hex[:8]}@e2e.meridian.example"

    def contact_data():
        api.post(f"/v1/customers/{customer_id}/identifiers", {"identifier_type": "NATIONAL_ID",
                 "identifier_value": str(uuid.uuid4().int)[:8], "issuing_country": COUNTRY, "expiry_date": "2032-01-01"})
        api.post(f"/v1/customers/{customer_id}/addresses", {"address_type": "RESIDENTIAL", "line1": "1 Kenyatta Ave",
                 "city": "Nairobi", "country": COUNTRY, "valid_from": "2020-01-01"})
        api.post(f"/v1/customers/{customer_id}/contacts", {"contact_type": "MOBILE",
                 "phone": "+2547" + str(uuid.uuid4().int)[:8], "is_primary": False})
        return api.post(f"/v1/customers/{customer_id}/contacts", {"contact_type": "EMAIL", "email": email,
                        "is_primary": True, "can_receive_otp": True})
    contact = j.step("customer: identifier, address, mobile and e-mail contacts", contact_data)

    def verify_email():
        api.post(f"/v1/customers/{customer_id}/contacts/{contact['id']}/send-verification", {"channel": "EMAIL"})
        code = mailpit_code(email)
        api.post(f"/v1/customers/{customer_id}/contacts/{contact['id']}/verify", {"code": code})
        return f"code {code} delivered by notification-service via SMTP"
    j.step("customer: e-mail OTP (notification-service -> Mailpit) and verify", verify_email)

    def evidence():
        prov = j.shared.get("kyc_provider")
        if not prov:
            prov = api.post("/v1/kyc/providers", {"code": f"SANDBOX{run_id()}", "name": "Sandbox KYC",
                            "capabilities": ["DOCUMENT", "LIVENESS", "FACE_MATCH", "DATABASE", "ADDRESS",
                                             "SCREENING", "PHONE", "EMAIL"],
                            "base_url": "sandbox:", "credentials_ref": "vault://kyc/sandbox", "is_active": True})["id"]
            j.shared["kyc_provider"] = prov
        prof = api.post("/v1/kyc/profiles", {"customer_id": customer_id})
        pid = prof["id"]
        stored = upload_document(api, "CUSTOMER", customer_id, "ID_CARD", "national-id.png", b"PNG e2e national id")
        doc = api.post("/v1/kyc/documents", {"kyc_profile_id": pid, "customer_id": customer_id, "document_id": stored,
                       "document_type": "NATIONAL_ID", "document_number": str(uuid.uuid4().int)[:8],
                       "issuing_country": COUNTRY, "expiry_date": "2032-01-01"})
        if api.get(f"/v1/kyc/documents/{doc['id']}")["status"] != "ACCEPTED":
            api.post(f"/v1/kyc/documents/{doc['id']}/accept", {}, user=CHECKER)
        for ct in ("DOCUMENT_AUTHENTICITY", "LIVENESS", "FACE_MATCH", "ADDRESS_VERIFICATION"):
            api.post("/v1/kyc/checks", {"kyc_profile_id": pid, "check_type": ct, "kyc_provider_id": prov,
                                        "kyc_document_id": doc["id"]})
        api.post("/v1/kyc/screenings", {"kyc_profile_id": pid, "customer_id": customer_id, "screening_type": "SANCTIONS",
                 "trigger": "ONBOARDING", "screened_name": f"{first} {last}", "status": "CLEAR"})
        ev = api.post(f"/v1/kyc/profiles/{pid}/evaluate", {})
        if ev.get("status") != "VERIFIED":
            raise StepFailed(f"KYC profile not VERIFIED after evaluation: {_short(ev, 1200)}")
        return ev
    ev = j.step("kyc: sandbox provider, document upload (S3), checks, screening, evaluate -> VERIFIED", evidence)

    def tier():
        api.post(f"/v1/customers/{customer_id}/change-tier", {"to_tier": "TIER_2", "reason": "KYC verified (e2e)"})
        approve_via_inbox(api, "customer_kyc_tier", customer_id)
        return wait_for("customer TIER_2", lambda: api.get(f"/v1/customers/{customer_id}")["kyc_tier"] == "TIER_2" and "TIER_2")
    j.step("customer: KYC tier TIER_2 requested by maker, approved in workflow inbox", tier)
    return ev


def create_deposit_product(j: Journey, code_prefix="SAV") -> tuple[str, str]:
    """Product + version with a credit-interest rate, submitted by the maker, approved by the checker in the
    workflow inbox, published. Returns (product_id, version_id)."""
    api = j.api
    if "deposit_product" in j.shared:
        return j.shared["deposit_product"]
    code = f"{code_prefix}{run_id()}"
    prod = j.step("product-catalog: create deposit product", api.post, "/v1/products", {
        "code": code, "name": f"E2E Savings {code}", "family": "SAVINGS", "legal_entity_id": api.shared_le,
        "currency": CCY})
    ver = j.step("product-catalog: create version", api.post, "/v1/product-versions", {
        "product_id": prod["id"], "definition": {"account_type": "SAVINGS", "min_opening_balance": "0"}})
    vid = ver["id"]
    j.step("product-catalog: add credit interest rate", api.post, "/v1/interest-rate-configs", {
        "product_version_id": vid, "rate_kind": "CREDIT_INTEREST", "rate_type": "FIXED", "currency": CCY,
        "effective_from": today(), "fixed_rate": "0.05", "day_count": "ACT_365"})
    j.step("product-catalog: maker submits version", api.post, f"/v1/product-versions/{vid}/submit", {})
    j.step("workflow: checker approves product version in inbox (maker blocked)",
           approve_via_inbox, api, "product_version", vid)
    j.step("product-catalog: version shows APPROVED", lambda: wait_for(
        "product version APPROVED", lambda: api.get(f"/v1/product-versions/{vid}")["status"] in ("APPROVED", "PUBLISHED")
        and api.get(f"/v1/product-versions/{vid}")["status"]))
    if api.get(f"/v1/product-versions/{vid}")["status"] != "PUBLISHED":
        j.step("product-catalog: publish version", api.post, f"/v1/product-versions/{vid}/publish", {})
    j.step("product-catalog: product ACTIVE with published version", lambda: wait_for(
        "product active", lambda: api.get(f"/v1/products/{prod['id']}")["status"] == "ACTIVE" and "ACTIVE"))
    j.shared["deposit_product"] = (prod["id"], vid)
    return prod["id"], vid


def open_deposit_account(j: Journey, customer_id: str, label="") -> dict:
    api = j.api
    pid, vid = create_deposit_product(j)
    acct = j.step(f"deposit: open account{label}", api.post, "/v1/deposit-accounts", {
        "account_type": "SAVINGS", "customer_id": customer_id, "product_id": pid, "product_version_id": vid,
        "legal_entity_id": api.shared_le, "currency": CCY, "nickname": f"E2E{label}"})
    aid = acct["id"]
    if acct["status"] == "PENDING_APPROVAL":
        j.step(f"workflow: checker approves account opening{label} in inbox", approve_via_inbox, api, "deposit_account", aid)
    got = j.step(f"deposit: account{label} approved/active", lambda: wait_for(
        "deposit account approved", lambda: (lambda a: a if a["status"] not in ("PENDING_APPROVAL",) else None)(
            api.get(f"/v1/deposit-accounts/{aid}"))))
    if got["status"] in ("APPROVED", "PENDING_FUNDING"):
        got = j.step(f"deposit: activate account{label}", api.post, f"/v1/deposit-accounts/{aid}/activate", {})
    return api.get(f"/v1/deposit-accounts/{aid}")


@journey("A", "Customer & account: customer, KYC, product (inbox), deposit account (inbox), ledger account")
def journey_a(j: Journey):
    api = j.api
    cust = j.step("customer: create individual", create_customer, api, "Amina", "Otieno")
    cid = cust["id"]
    j.shared["customer_a"] = cid
    ev = kyc_customer(j, cid, "Amina", "Otieno")
    j.step("customer: activate", lambda: api.post(f"/v1/customers/{cid}/activate", {}, user=CHECKER)
           if api.get(f"/v1/customers/{cid}")["status"] != "ACTIVE" else "already ACTIVE")
    cust = api.get(f"/v1/customers/{cid}")
    acct = open_deposit_account(j, cid)
    j.shared["account_a"] = acct
    j.shared["cust_account_a"] = cid
    j.step("deposit: account ACTIVE", lambda: acct["status"] if acct["status"] == "ACTIVE" else fail(f"status {acct['status']}"))
    led = j.step("ledger: customer ledger account exists", lambda: api.get(f"/v1/ledger-accounts/{acct['ledger_account_id']}")
                 if acct.get("ledger_account_id") else fail(f"no ledger_account_id: {_short(acct)}"))
    j.step("ledger: ledger account owned by the deposit account, in the tenant's book",
           lambda: f"{led['code']} {led['currency']} {led['status']}"
           if led.get("owner_id") == acct["id"] and led.get("ledger_id") == j.shared["ledger_id"] else fail(_short(led)))
    j.final = {"customer": cid, "customer_status": cust["status"], "kyc_status": ev.get("status") if isinstance(ev, dict) else None,
               "account": acct["account_number"], "account_status": acct["status"], "ledger_account": led["id"]}


def onboard_customer(j: Journey, first: str, last: str, key: str) -> str:
    """Customer with verified KYC (TIER_2) and ACTIVE status; cached in shared[key]."""
    if key in j.shared:
        return j.shared[key]
    api = j.api
    cust = j.step(f"customer: create {first} {last}", create_customer, api, first, last)
    kyc_customer(j, cust["id"], first, last)
    j.step(f"customer: activate {first}", api.post, f"/v1/customers/{cust['id']}/activate", {}, user=CHECKER)
    j.shared[key] = cust["id"]
    return cust["id"]


def ensure_account(j: Journey, key: str, first: str, last: str) -> dict:
    if key in j.shared:
        return j.shared[key]
    cid = onboard_customer(j, first, last, "cust_" + key)
    acct = open_deposit_account(j, cid, f" {first}")
    j.shared[key] = acct
    return acct


def balances(api: Api, acct: dict) -> tuple[Decimal, Decimal, Decimal]:
    """(deposit available, deposit posted, ledger-service posted balance) of a deposit account."""
    b = api.get(f"/v1/deposit-accounts/{acct['id']}/balance")
    lb = api.get(f"/v1/ledger-accounts/{acct['ledger_account_id']}/balance")
    return dec(b.get("available_balance")), dec(b.get("posted_balance")), dec(lb.get("posted_balance"))


def fund_account(j: Journey, acct: dict, amount: str, label: str):
    """Inbound credit transfer received from a rail (payment-service /v1/payments/inbound)."""
    api = j.api
    p = j.step(f"payment: inbound credit {amount} {CCY} to {label}", api.post, "/v1/payments/inbound", {
        "rail_code": "E2E-RTGS", "end_to_end_id": f"E2E{uuid.uuid4().hex[:20]}", "amount": amount, "currency": CCY,
        "debtor_name": "Employer Ltd", "debtor_account_number": "1234567890",
        "creditor_account_number": acct["account_number"], "remittance_unstructured": "Salary"})
    done = j.step(f"payment: inbound credit to {label} SETTLED", lambda: wait_for(
        "inbound SETTLED", lambda: (lambda x: x if x["status"] in ("SETTLED", "COMPLETED") else
                                    (fail(f"inbound {x['status']}: {_short(x)}") if x["status"] in ("REJECTED", "FAILED") else None))(
            api.get(f"/v1/payments/{p['id']}")), timeout=30))
    return done


@journey("B", "Money movement: inbound funding, internal transfer, balances, status timeline, event consumers")
def journey_b(j: Journey):
    api = j.api
    a = ensure_account(j, "account_a", "Amina", "Otieno")
    b = ensure_account(j, "account_b", "Brian", "Kamau")
    a0 = balances(api, a)
    fund_account(j, a, "10000.00", "Amina")
    j.step("balances: deposit and ledger show +10000", lambda: (lambda x: f"available={x[0]} current={x[1]} ledger={x[2]}"
           if x[0] - a0[0] == Decimal("10000") and x[2] - a0[2] == Decimal("10000") else fail(f"balances {x} (before {a0})"))(
        balances(api, a)))
    fund_account(j, b, "500.00", "Brian")
    b0 = balances(api, b)
    a1 = balances(api, a)
    pay = j.step("payment: internal transfer 2500 Amina -> Brian", api.post, "/v1/payments", {
        "payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "API",
        "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
        "creditor_name": "Brian Kamau", "amount": "2500.00", "currency": CCY, "reference": "Rent October",
        "remittance_unstructured": "Rent October"})
    pid = pay["id"]
    j.shared["payment_b"] = pid
    j.step("fraud-service: payment scored and allowed before release", fraud_cleared, api, pid)
    final = j.step("payment: transfer reaches a final state", lambda: wait_for(
        "transfer settled", lambda: (lambda x: x if x["status"] in ("SETTLED", "COMPLETED") else
                                     (fail(f"payment {x['status']}: {_short(x)}") if x["status"] in ("REJECTED", "FAILED", "CANCELLED") else None))(
            api.get(f"/v1/payments/{pid}")), timeout=30))
    j.step("balances: Amina -2500, Brian +2500 (deposit + ledger)", lambda: (lambda x, y: f"Amina {x[2]}, Brian {y[2]}"
           if a1[2] - x[2] == Decimal("2500") and y[2] - b0[2] == Decimal("2500") and a1[0] - x[0] == Decimal("2500")
           else fail(f"Amina {a1}->{x}, Brian {b0}->{y}"))(balances(api, a), balances(api, b)))
    hist = j.step("payment: status timeline", api.get, f"/v1/payments/{pid}/status-history")
    j.step("payment: timeline has the lifecycle", lambda: " -> ".join(h.get("to_status", h.get("status", "?"))
           for h in sorted(hist.get("data", []), key=lambda h: h.get("created_at", ""))) or fail(_short(hist)))
    j.step("payment: tracker", api.get, f"/v1/payments/{pid}/tracker", optional=True)
    j.step("ledger: journal entry of the transfer", lambda: "{entry_count} legs, debits {total_debits}".format(
           **api.get(f"/v1/journal-entries/{final['journal_entry_id']}"))
           if final.get("journal_entry_id") else fail("payment has no journal_entry_id"), optional=True)
    # Kafka consumers
    j.step("audit-service: saw payment events (Kafka)", lambda: wait_for("audit events for the payment", lambda:
           (lambda d: f"{len(d)} audit events: " + ", ".join(sorted({e.get('action', '?') for e in d})) if d else None)(
               api.get(f"/v1/audit-events?resource_id={pid}&limit=50").get("data", [])), timeout=30), optional=True)
    j.step("search-service: payment indexed (Kafka)", lambda: wait_for("payment in search", lambda:
           (lambda r: f"{len(r)} hits" if r else None)(api.post("/v1/search/query", {
               "query_text": pay.get("end_to_end_id") or "Rent October", "entity_types": ["PAYMENT"], "limit": 5}).get("data", [])),
           timeout=5), optional=True)  # known gap: nothing feeds search-service yet (no CDC/event consumer)
    j.step("crm-service: customer timeline shows the payment (Kafka)", lambda: wait_for("timeline entry", lambda:
           (lambda d: f"{len(d)} timeline entries" if d else None)(
               api.get(f"/v1/servicing/customers/{a['customer_id']}/timeline?limit=50").get("data", [])), timeout=30),
           optional=True)
    j.step("notification-service: customer alerted by e-mail (Kafka -> rule -> customer contact -> SMTP)", lambda: wait_for("notification", lambda:
           (lambda d: f"{len(d)} alerts: " + ", ".join(sorted({f"{n.get('channel')}:{n.get('status')}" for n in d})) if d else None)(
               [n for n in api.get(f"/v1/notifications?recipient_id={a['customer_id']}&limit=50").get("data", [])
                if (n.get("source_event_type") or "").startswith("payments.")]), timeout=20),
           optional=True)
    x = balances(api, a)
    y = balances(api, b)
    j.final = {"payment": pid, "status": final["status"], "amina_balance": str(x[2]), "brian_balance": str(y[2])}


def fraud_cleared(api: Api, pid: str) -> str:
    """payment-service scores every payment in fraud-service before release (S14). A fresh tenant's default policy
    allows normal amounts; a cold fraud-service may exceed the scoring budget, which holds amounts at or above the
    fail-closed threshold: score again with the same key (fraud-service replays a completed evaluation)."""
    x = api.get(f"/v1/payments/{pid}")
    if x.get("status") == "ON_HOLD" and x.get("fraud_degraded"):
        x = api.post(f"/v1/payments/{pid}/fraud-rescore", {})
    if x.get("fraud_decision") in ("REVIEW", "BLOCK", "STEP_UP") or x.get("status") == "ON_HOLD":
        fail(f"fraud {x.get('fraud_decision')} ({x.get('status')}): {_short(x)}")
    return (f"fraud {x.get('fraud_decision')} score {x.get('fraud_score')}"
            + (" (scoring unavailable, failed open)" if x.get("fraud_degraded") else ""))


def payment_until(api: Api, pid: str, states: tuple, timeout=30) -> dict:
    terminal = {"SETTLED", "COMPLETED", "REJECTED", "FAILED", "CANCELLED", "RETURNED"}

    def look():
        x = api.get(f"/v1/payments/{pid}")
        if x["status"] in states:
            return x
        if x["status"] in terminal:
            fail(f"payment {pid} is {x['status']} (wanted {states}): {_short(x)}")
        return None
    return wait_for(f"payment {pid} in {states}", look, timeout)


@journey("C", "Maker-checker: payment above the approval threshold decided in the workflow inbox")
def journey_c(j: Journey):
    api = j.api
    a = ensure_account(j, "account_a", "Amina", "Otieno")
    b = ensure_account(j, "account_b", "Brian", "Kamau")
    fund_account(j, a, "50000.00", "Amina")

    def transfer(amount, ref):
        return api.post("/v1/payments", {
            "payment_type": "INTERNAL_TRANSFER", "direction": "INTERNAL", "channel": "WEB",
            "customer_id": a["customer_id"], "debtor_account_id": a["id"], "creditor_account_id": b["id"],
            "creditor_name": "Brian Kamau", "amount": amount, "currency": CCY, "reference": ref})
    a0, b0 = balances(api, a), balances(api, b)
    big = j.step("payment: maker initiates 20000 transfer (threshold 10000)", transfer, "20000.00", "Car deposit")
    pid = big["id"]
    j.step("fraud-service: payment scored and allowed before release", fraud_cleared, api, pid)
    j.step("payment: PENDING_APPROVAL (funds are reserved only once approved)", lambda: (lambda x, bal: f"{x['status']}, available {bal[0]}"
           if bal[2] == a0[2] else fail(f"balance moved before approval: {bal} (before {a0})"))(
        payment_until(api, pid, ("PENDING_APPROVAL",)), balances(api, a)))
    req = j.step("workflow: approval request in checker inbox (routed by amount)", find_pending_approval, api, "payment", pid)
    j.step("workflow: request carries amount/currency", lambda: f"{req['amount']} {req['currency']}, maker {req['maker']}"
           if dec(req.get("amount")) == Decimal("20000") and req.get("maker") == MAKER else fail(_short(req)))
    j.step("workflow: not in the maker's own inbox", lambda: "absent" if not any(
        r["id"] == req["id"] for r in api.get("/v1/approval-requests/inbox?limit=100", roles=CHECKER_ROLES).get("data", []))
        else fail("maker sees own request"))
    j.step("workflow: maker cannot approve (four-eyes)", api.post, f"/v1/approval-requests/{req['id']}/approve",
           {"comment": "self"}, roles=CHECKER_ROLES, expect=(403, 409, 422))
    j.step("payment-service: maker cannot approve directly either", api.post, f"/v1/payments/{pid}/approve", {},
           expect=(403, 409, 422))
    j.step("workflow: checker approves", api.post, f"/v1/approval-requests/{req['id']}/approve",
           {"comment": "verified with customer"}, user=CHECKER)
    done = j.step("payment-service: applies the decision -> SETTLED", payment_until, api, pid, ("SETTLED", "COMPLETED"))
    j.step("balances: Amina -20000, Brian +20000", lambda: (lambda x, y: f"Amina {x[2]}, Brian {y[2]}"
           if a0[2] - x[2] == Decimal("20000") and y[2] - b0[2] == Decimal("20000") else fail(f"{a0}->{x}; {b0}->{y}"))(
        balances(api, a), balances(api, b)))
    j.step("payment: approvals recorded", lambda: (lambda d: f"{len(d)} approval(s) by " + ", ".join(
        sorted({json.dumps(x.get('approved_by') or x.get('decided_by') or '?') for x in d})) if d else fail("no approvals"))(
        api.get(f"/v1/payments/{pid}/approvals").get("data", [])))
    # reject path
    a1 = balances(api, a)
    rej = j.step("payment: maker initiates a second 15000 transfer", transfer, "15000.00", "Second car deposit")
    payment_until(api, rej["id"], ("PENDING_APPROVAL",))
    req2 = find_pending_approval(api, "payment", rej["id"])
    j.step("workflow: checker rejects", api.post, f"/v1/approval-requests/{req2['id']}/reject",
           {"comment": "customer did not confirm"}, user=CHECKER)
    j.step("payment-service: REJECTED/CANCELLED, balance untouched", lambda: (lambda x, bal: f"{x['status']}, available {bal[0]}"
           if bal[0] == a1[0] and bal[2] == a1[2] else fail(f"balance {bal} != {a1}"))(
        payment_until(api, rej["id"], ("REJECTED", "CANCELLED")), balances(api, a)))
    j.final = {"approved_payment": pid, "status": done["status"], "rejected_payment": rej["id"],
               "amina_balance": str(balances(api, a)[2])}

# @@JOURNEYS@@

def _load_journey_modules():
    """Journeys D..G live in tools/e2e/journey_*.py; they import their helpers from this module (as `journeys`)."""
    import importlib
    import glob
    here = os.path.dirname(os.path.abspath(__file__))
    sys.modules.setdefault("journeys", sys.modules[__name__])
    if here not in sys.path:
        sys.path.insert(0, here)
    for f in sorted(glob.glob(os.path.join(here, "journey_*.py"))):
        importlib.import_module(os.path.splitext(os.path.basename(f))[0])


_load_journey_modules()

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
