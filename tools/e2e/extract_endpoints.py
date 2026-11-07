#!/usr/bin/env python3.11
"""Parse every generated OpenAPI spec into a flat endpoint inventory (endpoints.json).
Run with python3.11 (has pyyaml). Stdlib + yaml only."""
import glob, json, yaml, os

SPEC_DIR = os.path.expanduser("~/meridian/generated/openapi/services")
out = []
for f in sorted(glob.glob(os.path.join(SPEC_DIR, "*.openapi.yaml"))):
    svc = os.path.basename(f).replace(".openapi.yaml", "")
    try:
        d = yaml.safe_load(open(f))
    except Exception as e:
        print("PARSE-ERR", f, e)
        continue
    paths = (d or {}).get("paths", {}) or {}
    for path, ops in paths.items():
        if not isinstance(ops, dict):
            continue
        # path-level params
        common_params = ops.get("parameters", []) or []
        for method, op in ops.items():
            if method.lower() not in ("get", "post", "put", "patch", "delete"):
                continue
            if not isinstance(op, dict):
                continue
            params = (op.get("parameters", []) or []) + common_params
            path_params = [p.get("name") for p in params
                           if isinstance(p, dict) and p.get("in") == "path"]
            query_params = [{"name": p.get("name"),
                             "required": bool(p.get("required")),
                             "schema": (p.get("schema") or {})}
                            for p in params if isinstance(p, dict) and p.get("in") == "query"]
            rb = op.get("requestBody") or {}
            body_required = bool(rb.get("required"))
            has_body = bool(rb)
            out.append({
                "service": svc,
                "path": path,
                "method": method.upper(),
                "operationId": op.get("operationId"),
                "path_params": path_params,
                "query_params": query_params,
                "body_required": body_required,
                "has_body": has_body,
                "responses": sorted((op.get("responses") or {}).keys()),
            })

by_method = {}
for e in out:
    by_method[e["method"]] = by_method.get(e["method"], 0) + 1
print(f"services={len(set(e['service'] for e in out))} endpoints={len(out)} by_method={by_method}")
json.dump(out, open(os.path.expanduser("~/meridian/tools/e2e/endpoints.json"), "w"))
print("wrote endpoints.json")
