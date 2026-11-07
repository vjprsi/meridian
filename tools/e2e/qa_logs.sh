#!/bin/bash
# Log-level correctness + secret-leak sweep across all service logs.
cd ~/meridian/.run/logs 2>/dev/null || { echo "no logs dir"; exit 1; }
echo "===== 1. ERROR / exception volume per service (top 30) ====="
for f in *.log; do
  e=$(grep -c -E "ERROR|SEVERE| 500 |Internal Server Error" "$f" 2>/dev/null)
  st=$(grep -c -E "^\s+at [a-z].*\(" "$f" 2>/dev/null)
  echo -e "$e\t$st\t$f"
done | sort -rn | head -30 | awk -F'\t' 'BEGIN{printf "%-6s %-6s %s\n","ERR","STACK","SERVICE"}{printf "%-6s %-6s %s\n",$1,$2,$3}'

echo ""
echo "===== 2. Distinct exception TYPES seen across all logs (with counts) ====="
grep -rhoE "[a-zA-Z0-9_.]*(Exception|Error)[: ]" *.log 2>/dev/null | sed 's/[: ]$//' | sort | uniq -c | sort -rn | head -40

echo ""
echo "===== 3. Confirm the pagination 500 root cause is LOGGED (ConstraintViolation / Unhandled) ====="
grep -rhE "Unhandled error on GET|ConstraintViolationException|must be less than or equal|must be greater than" *.log 2>/dev/null | sort | uniq -c | sort -rn | head -15

echo ""
echo "===== 4. SECRET / PII LEAK scan in logs (should be ZERO real hits) ====="
echo "-- 16-digit card-number-like sequences --"
grep -rhoE "\b[0-9]{13,19}\b" *.log 2>/dev/null | grep -vE "^1[0-9]{12}$" | sort -u | head -10
echo "-- 'password=' / 'secret=' / 'Authorization: Bearer' / private key --"
grep -rhiE "password[\"':= ]+[^ \"']{3,}|client_secret[\"':= ]|Authorization: Bearer [A-Za-z0-9]|BEGIN (RSA|PRIVATE)" *.log 2>/dev/null | grep -viE "password(_hash|Policy|_changed|_required|=\*\*\*|=\[redacted\]|=null)" | head -10
echo "-- OTP/one-time codes printed in clear (6-digit 'code' near otp/verification) --"
grep -rhiE "otp|one.?time|verification code" *.log 2>/dev/null | grep -oE "[0-9]{6}" | sort -u | head -10
echo "-- PAN/CVV field names with values --"
grep -rhiE "(\"?pan\"?|\"?cvv\"?|\"?cvc\"?)[\"':= ]+[0-9]{3,}" *.log 2>/dev/null | head -10

echo ""
echo "===== 5. Audit observability: audit-service activity ====="
echo -n "audit-service.log lines: "; wc -l < audit-service.log 2>/dev/null
grep -cE "audit|AuditEvent|recorded" audit-service.log 2>/dev/null | sed 's/^/audit-ish lines: /'

echo ""
echo "===== 6. WARN volume (routine vs noisy) top 15 ====="
for f in *.log; do w=$(grep -c "WARN" "$f" 2>/dev/null); echo -e "$w\t$f"; done | sort -rn | head -15

echo ""
echo "===== 7. Any OutOfMemory / crash / connection-pool exhaustion ====="
grep -rhE "OutOfMemory|Connection is not available|pool.*exhausted|Too many connections|FATAL|Killed|HikariPool.*Timeout" *.log 2>/dev/null | sort | uniq -c | sort -rn | head -15
echo "(end)"
