#!/usr/bin/env python3
"""Dump primary POST endpoints + required request fields (and enum hints) for target services, from the
generated per-service OpenAPI specs. Run with python3.11 (has yaml) ON the box."""
import glob, os, sys, yaml

TARGETS = sys.argv[1:] or ["pricing", "sanctions", "rewards", "statement", "regreport", "collections",
                           "recon", "openbanking", "fee", "campaign"]
SPEC_DIR = os.path.expanduser("~/meridian/generated/openapi/services")


def required_of(spec, body_schema):
    # resolve a $ref one level
    if "$ref" in body_schema:
        ref = body_schema["$ref"].split("/")[-1]
        body_schema = spec.get("components", {}).get("schemas", {}).get(ref, {})
    req = body_schema.get("required", [])
    props = body_schema.get("properties", {})
    out = []
    for r in req:
        p = props.get(r, {})
        hint = p.get("enum") or p.get("format") or p.get("type")
        out.append(f"{r}:{hint}")
    return out


for path in sorted(glob.glob(os.path.join(SPEC_DIR, "*.openapi.yaml"))):
    name = os.path.basename(path).replace(".openapi.yaml", "")
    if not any(t in name for t in TARGETS):
        continue
    try:
        spec = yaml.safe_load(open(path))
    except Exception as e:
        print(f"!! {name}: {e}"); continue
    print(f"\n===== {name} =====")
    for p, ops in (spec.get("paths") or {}).items():
        post = ops.get("post")
        if not post:
            continue
        # skip action sub-paths (…/{id}/do-something) for the primary create view
        rb = (post.get("requestBody") or {}).get("content", {}).get("application/json", {}).get("schema")
        req = required_of(spec, rb) if rb else []
        tag = "CREATE" if p.count("{") == 0 else "ACTION"
        print(f"  [{tag}] POST {p}")
        if req:
            print(f"        required: {', '.join(req)}")
