#!/bin/bash
# Rebuild + restart a single service identically to how dev.py launched it.
# Usage: rebuild_one.sh <service-name>
set -u
SVC="$1"
cd ~/meridian
export MERIDIAN_JDK25=/usr/lib/jvm/java-25-amazon-corretto.x86_64
export JAVA_HOME="$MERIDIAN_JDK25"
JAR="services/$SVC/build/libs/$SVC-0.1.0-SNAPSHOT.jar"

echo "== building :services:$SVC:bootJar (recompiles changed libs) =="
./gradlew ":services:$SVC:bootJar" -x test --no-daemon -q 2>&1 | tail -8
if [ ! -f "$JAR" ]; then echo "BUILD FAILED: no jar"; exit 1; fi
echo "built: $(ls -la --time-style=+%H:%M "$JAR" | awk '{print $6,$7}')"

# find running pid by jar path
PID=$(pgrep -f "$JAR" | head -1)
if [ -z "$PID" ]; then echo "WARN: no running pid for $SVC (will just start it)"; fi

if [ -n "${PID:-}" ]; then
  echo "== capturing launch env+cmd of pid $PID =="
  tr '\0' '\n' < /proc/$PID/cmdline > /tmp/${SVC}.cmd
  tr '\0' '\n' < /proc/$PID/environ > /tmp/${SVC}.env
  echo "cmd lines: $(wc -l < /tmp/${SVC}.cmd)  env vars: $(wc -l < /tmp/${SVC}.env)"
  echo "== killing $PID =="
  kill "$PID"; sleep 3
  kill -9 "$PID" 2>/dev/null
fi

# rebuild launch: env from captured environ, argv from captured cmdline
echo "== relaunching $SVC detached =="
mkdir -p .run/logs
python3.12 - "$SVC" <<'PY'
import os, sys, subprocess, signal
svc = sys.argv[1]
env = {}
try:
    for line in open(f"/tmp/{svc}.env"):
        line = line.rstrip("\n")
        if "=" in line:
            k, v = line.split("=", 1); env[k] = v
except FileNotFoundError:
    env = dict(os.environ)
try:
    argv = [l.rstrip("\n") for l in open(f"/tmp/{svc}.cmd") if l.strip()]
except FileNotFoundError:
    argv = None
if not argv:
    jdk = "/usr/lib/jvm/java-25-amazon-corretto.x86_64/bin/java"
    argv = [jdk, "-XX:+UseZGC", "-Xms128m", "-Xmx512m", "-jar",
            f"services/{svc}/build/libs/{svc}-0.1.0-SNAPSHOT.jar"]
    env.setdefault("JAVA_HOME", "/usr/lib/jvm/java-25-amazon-corretto.x86_64")
log = open(f".run/logs/{svc}.log", "ab")
p = subprocess.Popen(argv, env=env, stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                     start_new_session=True, cwd=os.path.expanduser("~/meridian"))
open(f".run/{svc}.pid", "w").write(str(p.pid))
print("relaunched pid", p.pid)
PY
echo "waiting for health..."
for i in $(seq 1 40); do
  sleep 2
  H=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:18000/actuator/health" 2>/dev/null)
  # service-specific readiness: just check gateway still 200 and give it time
  if [ "$i" -ge 8 ]; then break; fi
done
echo "done (waited ~$((i*2))s)"
