#!/bin/bash
# Enable AWS SES e-mail delivery for a tenant. Run ON the box once SES is verified + SMTP creds exist.
#   SES_SMTP_PASSWORD='<ses smtp password>' tools/e2e/configure_ses.sh <region> <smtp_username> <tenant_id> [sender]
# The password is taken ONLY from the env (never argv, never the DB). It is injected into the
# notification-service process environment via a /proc-preserving relaunch, then an EMAIL provider is created
# that references it by name (credentials_ref), and a test e-mail is sent.
set -u
REGION="${1:?region}"; USER_SMTP="${2:?smtp_username}"; TENANT="${3:?tenant_id}"; SENDER="${4:-vjprsi@gmail.com}"
: "${SES_SMTP_PASSWORD:?set SES_SMTP_PASSWORD in the environment}"
cd ~/meridian
HOST="email-smtp.${REGION}.amazonaws.com"
GW="http://localhost:18000"

echo "== 1/3 relaunch notification-service with SES_SMTP_PASSWORD in its env =="
PID=$(cat .run/notification-service.pid)
tr '\0' '\n' < /proc/$PID/environ | grep -v '^SES_SMTP_PASSWORD=' > /tmp/ns.env
echo "SES_SMTP_PASSWORD=${SES_SMTP_PASSWORD}" >> /tmp/ns.env
tr '\0' '\n' < /proc/$PID/cmdline > /tmp/ns.cmd
kill "$PID"; for i in $(seq 1 12); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done; kill -9 "$PID" 2>/dev/null
python3.12 - <<'PY'
import os, subprocess
env = {}
for l in open('/tmp/ns.env'):
    l = l.rstrip('\n')
    if '=' in l:
        k, v = l.split('=', 1); env[k] = v
argv = [l.rstrip('\n') for l in open('/tmp/ns.cmd') if l.strip()]
log = open('.run/logs/notification-service.log', 'ab')
p = subprocess.Popen(argv, env=env, stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                     start_new_session=True, cwd=os.path.expanduser('~/meridian'))
open('.run/notification-service.pid', 'w').write(str(p.pid))
print('relaunched notification-service pid', p.pid)
PY
for i in $(seq 1 40); do sleep 2; H=$(curl -s -o /dev/null -w '%{http_code}' "$GW/actuator/health"); [ "$i" -ge 8 ] && break; done
echo "gateway health after restart: $(curl -s -o /dev/null -w '%{http_code}' "$GW/actuator/health")"

echo "== 2/3 create the AWS SES EMAIL provider for tenant $TENANT (secret only by reference) =="
curl -s -X POST "$GW/v1/notification-providers" \
  -H "X-Tenant-Id: $TENANT" -H "X-User: ops" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(python3.12 -c 'import uuid;print(uuid.uuid4())')" \
  -d "{\"channel\":\"EMAIL\",\"provider_name\":\"AWS SES\",\"priority\":1,\"status\":\"ACTIVE\",\"sender_id\":\"$SENDER\",\"config\":{\"smtp_host\":\"$HOST\",\"smtp_port\":587,\"smtp_username\":\"$USER_SMTP\",\"credentials_ref\":\"SES_SMTP_PASSWORD\"}}" \
  -w "\nprovider create: %{http_code}\n" | tail -3

echo "== 3/3 send a live test e-mail to $SENDER =="
curl -s -X POST "$GW/v1/notifications" \
  -H "X-Tenant-Id: $TENANT" -H "X-User: ops" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(python3.12 -c 'import uuid;print(uuid.uuid4())')" \
  -d "{\"recipient_type\":\"RAW\",\"channel\":\"EMAIL\",\"destination\":\"$SENDER\",\"category\":\"TRANSACTIONAL\",\"subject\":\"Meridian SES live test\",\"body\":\"This is a live AWS SES test from Meridian. If you can read this in your inbox, real e-mail delivery works.\"}" \
  -w "\nnotification: %{http_code}\n" | tail -4
echo "Check the notification status + SES message id; then confirm receipt in the real inbox."
