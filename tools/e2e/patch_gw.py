#!/usr/bin/env python3
"""Make the edge gateway return 400 malformed_request on a malformed request URI
(e.g. cursor=%ZZ) instead of letting URI.create(...) throw IllegalArgumentException -> 500."""
import io, sys
P = "platform/edge-gateway/src/main/java/io/meridian/gateway/ProxyController.java"
src = io.open(P, encoding="utf-8").read()

OLD = "        HttpRequest.Builder out = HttpRequest.newBuilder(URI.create(target)).timeout(props.timeout());"
NEW = (
    "        URI targetUri;\n"
    "        try {\n"
    "            targetUri = URI.create(target);\n"
    "        } catch (IllegalArgumentException e) {\n"
    "            problem(res, 400, \"malformed_request\", \"The request URL or query string is malformed\", requestId);\n"
    "            return;\n"
    "        }\n"
    "        HttpRequest.Builder out = HttpRequest.newBuilder(targetUri).timeout(props.timeout());"
)

if "URI targetUri;" in src:
    print("ALREADY PATCHED"); sys.exit(0)
if OLD not in src:
    print("ANCHOR NOT FOUND — aborting"); sys.exit(2)
src = src.replace(OLD, NEW)
io.open(P, "w", encoding="utf-8").write(src)
print("PATCHED edge gateway: malformed URI -> 400 malformed_request")
