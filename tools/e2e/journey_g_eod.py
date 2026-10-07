"""Journey G: end of day (business-day-service standard EOD pack, trigger policy, business date, EOD run)."""
from __future__ import annotations

import time
from collections import Counter
from decimal import Decimal
from datetime import datetime, timedelta, timezone

from journeys import (CHECKER, Journey, StepFailed, _short, ensure_account, fail, fund_account, journey,
                      wait_for)

# Business date of the legal entity (Africa/Nairobi, UTC+3, no DST): ledger value dating and deposit accrual bucket by
# UTC calendar date, so a business date taken from a machine clock west of UTC would accrue on yesterday's balance.
def business_date() -> str:
    return datetime.now(timezone(timedelta(hours=3))).date().isoformat()


TERMINAL_RUN = {"COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED", "SUCCEEDED", "PARTIALLY_COMPLETED"}


def _rows(page) -> list:
    return page.get("data", []) if isinstance(page, dict) else (page or [])


@journey("G", "End of day: standard EOD pack, trigger policy, business date, EOD run, per-job results")
def journey_g(j: Journey):
    api, sh = j.api, j.shared
    le = sh["legal_entity_id"]
    a = ensure_account(j, "account_a", "Amina", "Otieno")
    fund_account(j, a, "25000.00", "Amina (EOD accrual base)")

    pack = j.step("business-day: preview standard EOD pack", api.get, "/v1/operations/eod-jobs/standard-pack")
    installed = j.step("business-day: install standard EOD pack for the tenant", api.post,
                       "/v1/operations/eod-jobs/install-standard-pack", {})
    j.step("business-day: reinstall is idempotent", api.post, "/v1/operations/eod-jobs/install-standard-pack", {})

    def jobs():
        out, cursor = [], None
        while True:
            page = api.get("/v1/operations/eod-jobs?limit=200" + (f"&cursor={cursor}" if cursor else ""))
            out += _rows(page)
            cursor = page.get("next_cursor")
            if not page.get("has_more") or not cursor:
                return out
    defs = j.step("business-day: job definitions listed", jobs)
    j.step("business-day: pack installed every job", lambda: f"{len(defs)} jobs (pack version "
           f"{pack.get('version', installed.get('version', '?')) if isinstance(pack, dict) else '?'})"
           if len(defs) >= 50 else fail(f"only {len(defs)} jobs: {_short(installed)}"))

    ledger_job = next((d for d in defs if d["code"] == "LEDGER_EOD"), None)
    if ledger_job:
        j.step("business-day: point LEDGER_EOD at the tenant's ledger book and activate it", api.patch,
               f"/v1/operations/eod-jobs/{ledger_job['id']}",
               {"parameters": {**(ledger_job.get("parameters") or {}), "ledger_id": sh["ledger_id"]}, "is_active": True},
               headers={"If-Match": str(ledger_job.get("row_version", ""))} if ledger_job.get("row_version") else None)

    j.step("business-day: validate job graph", api.post, "/v1/operations/eod-jobs/validate", {}, optional=True)
    j.step("business-day: EOD trigger policy (cut-off 18:00 Africa/Nairobi, no auto-roll)", api.post,
           "/v1/operations/eod-trigger-policies", {"legal_entity_id": le, "is_enabled": True, "cut_off_time": "18:00",
                                                    "time_zone": "Africa/Nairobi", "auto_roll": False})
    j.step("business-day: trigger policy evaluation", api.get, "/v1/operations/eod-trigger-policies/evaluation",
           optional=True)

    day = j.step("business-day: open business date", api.post, "/v1/operations/business-days",
                 {"legal_entity_id": le, "business_date": business_date()})
    did = day["id"]
    j.step("business-day: cut-off", api.post, f"/v1/operations/business-days/{did}/cut-off", {}, user=CHECKER)
    t0 = time.time()
    started = j.step("business-day: start EOD", api.post, f"/v1/operations/business-days/{did}/start-eod", {},
                     user=CHECKER)

    def run_of_day():
        runs = _rows(api.get(f"/v1/operations/eod-runs?business_day_id={did}&limit=5"))
        return runs[0] if runs else None
    run = started if isinstance(started, dict) and started.get("business_day_id") == did and "status" in started \
        and "jobs_total" in started else None
    run = run or j.step("business-day: EOD run created", lambda: wait_for("EOD run", run_of_day, 15))
    rid = run["id"]

    def finished():
        r = api.get(f"/v1/operations/eod-runs/{rid}")
        return r if r.get("status") in TERMINAL_RUN else None
    final = j.step("business-day: EOD run reaches a terminal state", lambda: wait_for(
        "EOD run terminal", finished, timeout=240, interval=2))
    secs = time.time() - t0

    def job_runs():
        out, cursor = [], None
        while True:
            page = api.get(f"/v1/operations/eod-runs/{rid}/jobs?limit=200" + (f"&cursor={cursor}" if cursor else ""))
            out += _rows(page)
            cursor = page.get("next_cursor")
            if not page.get("has_more") or not cursor:
                return out
    jr = j.step("business-day: per-job results", job_runs)
    by_status = Counter(r.get("status") for r in jr)
    print(f"      EOD run {final.get('status')} in {secs:.1f}s; jobs {dict(by_status)}")
    bad = [r for r in jr if r.get("status") not in ("SUCCEEDED", "COMPLETED", "SKIPPED")]
    code_of = {d["id"]: d["code"] for d in defs}
    for r in bad:
        code = r.get("job_code") or code_of.get(r.get("eod_job_definition_id")) or r.get("eod_job_definition_id")
        print(f"      {r.get('status'):10} {code}: {_short(r.get('error_message') or r.get('error') or r.get('result'), 400)}")
    ledger_run = _rows(api.get(f"/v1/ledger-eod-runs?ledger_id={sh['ledger_id']}&limit=5"))
    j.final = {"business_day": did, "eod_run": rid, "run_status": final.get("status"), "seconds": round(secs, 1),
               "jobs": dict(by_status), "failed_jobs": sorted(str(r.get("job_code") or code_of.get(
                   r.get("eod_job_definition_id"))) for r in bad),
               "ledger_eod_runs": [x.get("status") for x in ledger_run]}
    j.step("business-day: every EOD job succeeded", lambda: f"{len(jr)} jobs" if not bad and final.get("status") in
           ("COMPLETED", "SUCCEEDED") else fail(f"run {final.get('status')}; not succeeded: {j.final['failed_jobs']}"))
    j.step("ledger: EOD snapshot run recorded for the tenant's book", lambda: ledger_run[0]["status"]
           if ledger_run else fail("no ledger-eod-run"))
    acc = _rows(api.get(f"/v1/deposit-accounts/{a['id']}/accruals?limit=10"))
    j.final["deposit_accruals"] = [(x.get("accrual_date"), x.get("accrued_amount") or x.get("amount")) for x in acc]
    j.step("deposit: EOD accrued interest on the funded account (25000 @ 5% ACT/365)",
           lambda: _short(j.final["deposit_accruals"]) if any(Decimal(str(x[1] or 0)) > 0 for x in j.final["deposit_accruals"])
           else fail(f"no positive interest accrual after EOD: {j.final['deposit_accruals']}"))
    j.step("business-day: roll to the next business date", api.post, f"/v1/operations/business-days/{did}/roll", {},
           user=CHECKER)
    cur = j.step("business-day: current business date advanced", api.get,
                 f"/v1/operations/business-days/current?legal_entity_id={le}")
    j.final["next_business_date"] = cur.get("business_date") if isinstance(cur, dict) else None
