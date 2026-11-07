#!/usr/bin/env python3.12
"""Contract sweep: hit EVERY generated endpoint with adversarial inputs and assert no 5xx,
no leaked stacktraces, proper 404 on random ids, and tenant isolation on the header.
Reads tools/e2e/endpoints.json (produced by extract_endpoints.py under 3.11).
Fresh throwaway tenant => accidental writes are isolated. Stdlib only."""
import json, os, sys, uuid, time, urllib.request, urllib.error, threading
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("MERIDIAN_GATEWAY_URL", "http://localhost:18000").rstrip("/")
TENANT = str(uuid.uuid4())
OTHER_TENANT = str(uuid.uuid4())
ENDPOINTS = json.load(open(os.path.join(HERE, "endpoints.json")))
WORKERS = int(os.environ.get("QA_WORKERS", "8"))

RESULTS = []
_lock = threading.Lock()
_counter = {"n": 0}


def raw(method, path, body=None, tenant=TENANT, drop_tenant=False, idem=True):
    url = BASE + path
    h = {"X-User": "qa.contract", "Accept": "application/json",
         "X-Roles": "admin,ops,approver,checker,risk,compliance,finance,treasury,credit"}
    if not drop_tenant:
        h["X-Tenant-Id"] = tenant
    data = None
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
        else:
            data = body.encode() if isinstance(body, str) else body
            h["Content-Type"] = "application/json"
    if idem and method in ("POST", "PUT", "PATCH"):
        h["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, rawb = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, rawb = e.code, e.read()
    except Exception as e:
        return 0, f"TRANSPORT:{e}", 0.0
    ms = (time.perf_counter() - t0) * 1000
    try:
        payload = json.loads(rawb) if rawb else None
    except ValueError:
        payload = rawb.decode(errors="replace")
    return status, payload, ms


def leaky(p):
    s = p if isinstance(p, str) else (json.dumps(p) if p is not None else "")
    markers = ("\n\tat ", "Exception", "org.springframework", "java.lang.", "Caused by:",
               "jakarta.", "SQLException", "psql", "Hibernate", "NullPointer", "stacktrace")
    return any(m in s for m in markers)


def has_code(p):
    return isinstance(p, dict) and (p.get("code") is not None)


def rec(cat, service, method, path, probe, status, payload, ms, ok, severity="", note=""):
    with _lock:
        _counter["n"] += 1
        RESULTS.append({"cat": cat, "service": service, "method": method, "path": path,
                        "probe": probe, "status": status, "ok": ok, "severity": severity,
                        "note": note, "ms": round(ms, 1),
                        "evidence": (str(payload)[:400] if not ok else None)})


def classify_fail(status, payload):
    """Return (is_fail, severity, note)."""
    if status == 0:
        return True, "HIGH", "transport/connection error"
    if 500 <= status <= 599:
        return True, "CRITICAL", f"server error {status}"
    if leaky(payload):
        return True, "HIGH", "leaked stacktrace/internal in body"
    return False, "", ""


def probe_endpoint(e):
    svc, method, path = e["service"], e["method"], e["path"]
    pp = e["path_params"]
    # Build a path with random UUIDs substituted for path params
    rnd_path = path
    for name in pp:
        rnd_path = rnd_path.replace("{" + name + "}", str(uuid.uuid4()))
    has_id = bool(pp)

    # ---- safety: never bulk-DELETE a collection (no path param) ----
    if method == "DELETE" and not has_id:
        return

    # ---- Primary probe ----
    if has_id:
        # random id -> expect 4xx (404 ideally), never 5xx / 200-on-GET
        body = {} if method in ("POST", "PUT", "PATCH") else None
        st, pl, ms = raw(method, rnd_path, body)
        fail, sev, note = classify_fail(st, pl)
        if not fail and method == "GET" and st == 200:
            fail, sev, note = True, "HIGH", "GET random-UUID returned 200 (isolation/notfound leak)"
        ok = not fail
        rec("notfound", svc, method, path, "random-id", st, pl, ms, ok, sev, note)
    else:
        if method == "GET":
            st, pl, ms = raw("GET", path + ("&" if "?" in path else "?") + "limit=5")
            fail, sev, note = classify_fail(st, pl)
            rec("list", svc, method, path, "list-limit5", st, pl, ms, not fail, sev, note)
            # pagination boundaries
            for q in ("limit=201", "limit=0", "limit=-1", "limit=abc", "cursor=%ZZnotacursor"):
                sep = "&" if "?" in path else "?"
                st, pl, ms = raw("GET", path + sep + q)
                fail, sev, note = classify_fail(st, pl)
                rec("pagination", svc, method, path, q, st, pl, ms, not fail, sev, note)
        else:
            # collection write: empty body then malformed json
            st, pl, ms = raw(method, path, {})
            fail, sev, note = classify_fail(st, pl)
            if not fail and st in (200, 201) and e["body_required"]:
                note = "accepted empty body despite body_required (soft)"
            rec("emptybody", svc, method, path, "empty{}", st, pl, ms, not fail, sev, note)
            st, pl, ms = raw(method, path, "{not valid json,,,")
            fail, sev, note = classify_fail(st, pl)
            rec("malformed", svc, method, path, "malformed-json", st, pl, ms, not fail, sev, note)

    # ---- Sampled probes: missing tenant + bad method (every 4th endpoint) ----
    if (hash((svc, path, method)) % 4) == 0:
        probe_path = rnd_path if has_id else path
        st, pl, ms = raw(method if method != "DELETE" else "GET", probe_path,
                         {} if method in ("POST", "PUT", "PATCH") else None, drop_tenant=True)
        # expect 401/400; a 2xx with no tenant is an isolation failure
        fail, sev, note = classify_fail(st, pl)
        if not fail and st in (200, 201, 202, 204):
            fail, sev, note = True, "CRITICAL", "2xx with NO tenant header (auth/isolation bypass)"
        rec("notenant", svc, method, path, "no-tenant-header", st, pl, ms, not fail, sev, note)


def main():
    print(f"CONTRACT sweep via {BASE}  tenant={TENANT}  endpoints={len(ENDPOINTS)}  workers={WORKERS}")
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        list(ex.map(probe_endpoint, ENDPOINTS))
    dur = time.time() - t0
    total = len(RESULTS)
    fails = [r for r in RESULTS if not r["ok"]]
    bycat = {}
    for r in RESULTS:
        c = r["cat"]
        bycat.setdefault(c, [0, 0])
        bycat[c][0] += 1
        if not r["ok"]:
            bycat[c][1] += 1
    print(f"\n== CONTRACT SWEEP: {total} probes in {dur:.0f}s, {len(fails)} FAIL ==")
    for c, (n, f) in sorted(bycat.items()):
        print(f"  {c:12s} {n:5d} probes  {f:4d} fail")
    sev_order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "": 3}
    fails.sort(key=lambda r: sev_order.get(r["severity"], 3))
    print(f"\n-- FAILURES (top 60 of {len(fails)}) --")
    for r in fails[:60]:
        print(f"  [{r['severity'] or 'MED'}] {r['status']} {r['method']} {r['path']} ({r['probe']}) "
              f"{r['service']} :: {r['note']}  {('| '+r['evidence']) if r['evidence'] else ''}")
    out = {"tenant": TENANT, "base": BASE, "total": total, "fails": len(fails),
           "bycat": bycat, "results": RESULTS}
    json.dump(out, open("/tmp/qa_contract.json", "w"), default=str)
    print("\nwrote /tmp/qa_contract.json")


if __name__ == "__main__":
    main()
