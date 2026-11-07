#!/usr/bin/env python3
"""Sanitize the badRequest handler so MethodArgumentTypeMismatch/HttpMessageNotReadable
no longer leak Java class names in the response body."""
import io, sys
P = "libs/meridian-web/src/main/java/io/meridian/web/error/ProblemHandler.java"
src = io.open(P, encoding="utf-8").read()

OLD = """    ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", e.getMessage(), req, List.of());
    }"""
NEW = """    ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest req) {
        String detail;
        if (e instanceof MethodArgumentTypeMismatchException m) {
            detail = "Parameter '" + snake(m.getName()) + "' has an invalid value for its expected type";
        } else if (e instanceof MissingRequestHeaderException h) {
            detail = "Required header '" + h.getHeaderName() + "' is missing";
        } else {
            detail = "Request body is malformed or could not be read";
        }
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", detail, req, List.of());
    }"""

if NEW.split("{", 1)[1][:40] in src:
    print("ALREADY PATCHED"); sys.exit(0)
if OLD not in src:
    print("ANCHOR NOT FOUND — aborting"); sys.exit(2)
src = src.replace(OLD, NEW)
io.open(P, "w", encoding="utf-8").write(src)
print("PATCHED badRequest handler (no more leaked Java internals)")
