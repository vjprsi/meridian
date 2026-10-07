#!/usr/bin/env python3
"""RPO / RTO probe tenant for game days (MR-GAME-DAY). Standard library only.

A synthetic writer posts sequenced, balanced ledger journal entries (0.01 between two internal accounts of a probe
ledger) through the public entry point (cell router or edge gateway), one at a time: entry n is sent only after entry
n-1 was acknowledged, so the database holds a prefix of the sequence (asynchronous replication keeps commit order).
Every acknowledgement is appended to a local journal (JSONL) with its time. After the failover the checker finds the
last sequence present in the promoted database (binary search over GET /v1/journal-entries?reference=...) and reports

  last acknowledged   highest seq the client saw acknowledged before / during the failure
  last present        highest seq present after promotion
  lost                acknowledged but missing = data loss (RPO in writes); RPO in time = ack time of the last
                      acknowledged entry - ack time of the last present one
  unavailability      longest run of failed writes (client-observed RTO), from the writer's journal

The ledger is tier 0 (docs/operations/BCP_DR.md): this is the number the RPO-0 claim is about.

    python tools/gameday/rpo_probe.py setup  [--base URL] [--tenant UUID] [--state FILE]   # legal entity, ledger, 2 accounts
    python tools/gameday/rpo_probe.py write  [--rate 5] [--duration 900]                   # Ctrl-C to stop
    python tools/gameday/rpo_probe.py check  [--base URL-of-the-promoted-side]
    python tools/gameday/rpo_probe.py report                                               # writer-side numbers only

Authentication: local dev stack (MERIDIAN_SECURITY_MODE=dev) sends X-Tenant-Id / X-User; anywhere else pass a bearer
token of a probe-tenant user or client with ledger:write (MERIDIAN_PROBE_TOKEN). Defaults: --base http://localhost:18000
(edge gateway), state file .run/rpo-probe.json, journal .run/rpo-probe.jsonl.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
DEFAULT_STATE = ROOT / ".run" / "rpo-probe.json"


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")


class Api:
    def __init__(self, base: str, tenant: str, token: str | None, timeout: float = 10):
        self.base, self.tenant, self.token, self.timeout = base.rstrip("/"), tenant, token, timeout

    def call(self, method: str, path: str, body=None, key: str | None = None):
        h = {"Accept": "application/json", "X-Tenant-Id": self.tenant, "X-User": "rpo-probe"}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if method == "POST":
            h["Idempotency-Key"] = key or str(uuid.uuid4())
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read()
                return r.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw) if raw else None
            except ValueError:
                return e.code, raw.decode(errors="replace")
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            return 0, str(e)


def load(path: Path) -> dict:
    if not path.exists():
        sys.exit(f"no probe state at {path}: run setup first")
    return json.loads(path.read_text(encoding="utf-8"))


def ok(status: int, body, what: str):
    if status not in (200, 201):
        sys.exit(f"{what}: HTTP {status} {body}")
    return body


def setup(a) -> None:
    state_path = Path(a.state)
    tenant = a.tenant or str(uuid.uuid4())
    api = Api(a.base, tenant, os.environ.get("MERIDIAN_PROBE_TOKEN"))
    run = uuid.uuid4().hex[:8]
    le = ok(*api.call("POST", "/v1/legal-entities", {"code": f"RPO{run}", "legal_name": "RPO probe entity",
                                                     "country": "GB", "base_currency": "GBP"}), "legal entity")
    book = ok(*api.call("POST", "/v1/ledgers", {"code": f"RPO-{run}", "name": "RPO probe ledger",
                                                "legal_entity_id": le["id"], "base_currency": "GBP"}), "ledger")
    accounts = []
    for code, typ, nb in (("RPO-A", "ASSET", "DEBIT"), ("RPO-B", "LIABILITY", "CREDIT")):
        acc = ok(*api.call("POST", "/v1/ledger-accounts", {
            "ledger_id": book["id"], "code": f"{code}-{run}", "name": f"RPO probe {code}", "account_type": typ,
            "normal_balance": nb, "currency": "GBP", "allow_negative": True, "owner_type": "BANK"}), "account")
        accounts.append(acc["id"])
    state = {"tenant": tenant, "base": a.base, "run": run, "ledger_id": book["id"], "accounts": accounts,
             "journal": str(state_path.with_suffix(".jsonl")), "created_at": now()}
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")
    print(f"probe tenant {tenant}, run {run}, ledger {book['id']} -> {state_path}")


def reference(state: dict, seq: int) -> str:
    return f"rpo:{state['run']}:{seq:09d}"


def journal_entries(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write(a) -> None:
    state = load(Path(a.state))
    api = Api(a.base or state["base"], state["tenant"], os.environ.get("MERIDIAN_PROBE_TOKEN"), timeout=a.timeout)
    journal = Path(state["journal"])
    done = [e for e in journal_entries(journal) if e.get("ack")]
    seq = (done[-1]["seq"] + 1) if done else 1
    interval = 1.0 / a.rate
    end = time.monotonic() + a.duration if a.duration else None
    failing_since = None
    print(f"writing from seq {seq} at {a.rate}/s to {api.base} (tenant {state['tenant']}); Ctrl-C to stop")
    with journal.open("a", encoding="utf-8") as out:
        try:
            while end is None or time.monotonic() < end:
                t0 = time.monotonic()
                body = {"ledger_id": state["ledger_id"], "entry_type": "STANDARD", "reference": reference(state, seq),
                        "description": "RPO probe", "custom_field_1": state["run"],
                        "entries": [
                            {"ledger_account_id": state["accounts"][0], "direction": "DEBIT", "amount": "0.01", "currency": "GBP"},
                            {"ledger_account_id": state["accounts"][1], "direction": "CREDIT", "amount": "0.01", "currency": "GBP"}]}
                # same Idempotency-Key for every retry of a seq: a write that committed but whose answer was lost is
                # not posted twice
                status, resp = api.call("POST", "/v1/journal-entries", body, key=f"rpo-{state['run']}-{seq}")
                ms = round((time.monotonic() - t0) * 1000, 1)
                if status in (200, 201):
                    rec = {"seq": seq, "ack": True, "at": now(), "ms": ms, "id": (resp or {}).get("id"), "status": status}
                    if failing_since:
                        rec["recovered_after_s"] = round(time.monotonic() - failing_since, 1)
                        print(f"  seq {seq} acknowledged again after {rec['recovered_after_s']} s of failures")
                        failing_since = None
                    seq += 1
                else:
                    detail = resp.get("code") if isinstance(resp, dict) else str(resp)[:120]
                    rec = {"seq": seq, "ack": False, "at": now(), "ms": ms, "status": status, "error": detail}
                    if failing_since is None:
                        failing_since = time.monotonic()
                        print(f"  seq {seq} failing: HTTP {status} {detail}")
                out.write(json.dumps(rec) + "\n")
                out.flush()
                time.sleep(max(0.0, interval - (time.monotonic() - t0)))
        except KeyboardInterrupt:
            pass
    print(f"stopped at seq {seq}; journal {journal}")


def exists(api: Api, state: dict, seq: int) -> bool:
    q = urllib.parse.urlencode({"ledger_id": state["ledger_id"], "reference": reference(state, seq), "limit": 1})
    status, body = api.call("GET", f"/v1/journal-entries?{q}")
    if status != 200:
        sys.exit(f"read of seq {seq}: HTTP {status} {body}")
    return bool((body or {}).get("data"))


def summary(entries: list[dict]) -> dict:
    acks = [e for e in entries if e.get("ack")]
    out = {"writes_acknowledged": len(acks), "last_acknowledged": acks[-1]["seq"] if acks else 0,
           "last_acknowledged_at": acks[-1]["at"] if acks else None}
    longest, start, prev_fail = 0.0, None, None
    for e in entries:
        t = dt.datetime.fromisoformat(e["at"])
        if not e.get("ack"):
            start = start or t
            prev_fail = t
        elif start:
            longest = max(longest, (t - start).total_seconds())
            start = None
    if start and prev_fail:
        longest = max(longest, (prev_fail - start).total_seconds())
        out["still_failing_since"] = start.isoformat()
    out["longest_unavailability_s"] = round(longest, 1)
    lat = sorted(e["ms"] for e in acks)
    if lat:
        out["latency_ms_p50"] = lat[len(lat) // 2]
        out["latency_ms_p99"] = lat[min(len(lat) - 1, int(len(lat) * 0.99))]
    return out


def check(a) -> None:
    state = load(Path(a.state))
    api = Api(a.base or state["base"], state["tenant"], os.environ.get("MERIDIAN_PROBE_TOKEN"))
    entries = journal_entries(Path(state["journal"]))
    s = summary(entries)
    last_ack = s["last_acknowledged"]
    # the database holds a prefix of the sequence: binary search the last present seq in [0, last_ack + margin]
    hi = last_ack + a.margin
    while exists(api, state, hi):          # writes may have committed after the client stopped seeing answers
        hi += a.margin
    lo = 0
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if exists(api, state, mid):
            lo = mid
        else:
            hi = mid - 1
    last_present = lo
    acks = {e["seq"]: e for e in entries if e.get("ack")}
    lost = max(0, last_ack - last_present)
    s.update({"last_present": last_present, "lost_acknowledged_writes": lost,
              "committed_but_unacknowledged": max(0, last_present - last_ack)})
    if lost and last_present in acks:
        gap = dt.datetime.fromisoformat(acks[last_ack]["at"]) - dt.datetime.fromisoformat(acks[last_present]["at"])
        s["rpo_seconds"] = round(gap.total_seconds(), 3)
    else:
        s["rpo_seconds"] = 0.0
    holes = [q for q in range(max(1, last_present - 20), last_present + 1) if not exists(api, state, q)]
    s["holes_in_last_20"] = holes            # must be empty: replication keeps commit order
    s["verdict"] = "RPO 0" if lost == 0 and not holes else f"DATA LOSS: {lost} acknowledged writes"
    print(json.dumps(s, indent=2))
    if lost or holes:
        sys.exit(1)


def report(a) -> None:
    state = load(Path(a.state))
    print(json.dumps(summary(journal_entries(Path(state["journal"]))), indent=2))


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("setup", "write", "check", "report"):
        s = sub.add_parser(name)
        s.add_argument("--state", default=str(DEFAULT_STATE))
        s.add_argument("--base", default="http://localhost:18000" if name == "setup" else None)
        if name == "setup":
            s.add_argument("--tenant")
        if name == "write":
            s.add_argument("--rate", type=float, default=5.0, help="writes per second (sequential)")
            s.add_argument("--duration", type=float, default=0, help="seconds; 0 = until Ctrl-C")
            s.add_argument("--timeout", type=float, default=5.0)
        if name == "check":
            s.add_argument("--margin", type=int, default=50)
    a = p.parse_args()
    {"setup": setup, "write": write, "check": check, "report": report}[a.cmd](a)


if __name__ == "__main__":
    main()
