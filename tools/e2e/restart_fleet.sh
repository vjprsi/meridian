#!/bin/bash
# Rolling restart of every detached Meridian service onto the freshly-built jars.
# One service is down at a time; the gateway and all other services stay up throughout.
# Each service is relaunched IDENTICALLY (argv + env captured from /proc/<pid>) and health-gated
# on its own port before moving to the next. The edge gateway is restarted last (brief full blip).
set -u
cd ~/meridian
export JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto.x86_64

port_of() { ss -ltnp 2>/dev/null | grep "pid=$1," | grep -oE ':[0-9]+ ' | head -1 | tr -d ': '; }

restart_one() {
  name="$1"
  pidf=".run/$name.pid"
  pid=$(cat "$pidf" 2>/dev/null)
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then echo "  [$name] not running — skip"; return; fi
  tr '\0' '\n' < /proc/$pid/cmdline > /tmp/rst.cmd 2>/dev/null
  tr '\0' '\n' < /proc/$pid/environ > /tmp/rst.env 2>/dev/null
  if [ ! -s /tmp/rst.cmd ]; then echo "  [$name] could not read cmdline — skip (pid=$pid)"; return; fi
  kill "$pid" 2>/dev/null
  for i in $(seq 1 12); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null
  newpid=$(python3.12 - "$name" <<'PY'
import os,sys,subprocess
name=sys.argv[1]
env={}
for l in open('/tmp/rst.env'):
    l=l.rstrip('\n')
    if '=' in l:
        k,v=l.split('=',1); env[k]=v
argv=[l.rstrip('\n') for l in open('/tmp/rst.cmd') if l.strip()]
log=open(f'.run/logs/{name}.log','ab')
p=subprocess.Popen(argv,env=env,stdout=log,stderr=log,stdin=subprocess.DEVNULL,
                   start_new_session=True,cwd=os.path.expanduser('~/meridian'))
open(f'.run/{name}.pid','w').write(str(p.pid))
print(p.pid)
PY
)
  ok=0
  for i in $(seq 1 75); do
    sleep 1
    p2=$(port_of "$newpid")
    if [ -n "$p2" ]; then
      h=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$p2/actuator/health" 2>/dev/null)
      if [ "$h" = "200" ]; then ok=1; echo "  [$name] UP pid=$newpid port=$p2 (${i}s)"; break; fi
    fi
  done
  [ "$ok" = "1" ] || echo "  [$name] !! NOT healthy after 75s (pid=$newpid) — see .run/logs/$name.log"
}

mkdir -p .run/logs
SERVICES=$(ls .run/*.pid 2>/dev/null | xargs -n1 basename | sed 's/\.pid$//' | grep -v '^gateway$' | sort)
TOTAL=$(echo "$SERVICES" | wc -l)
echo "=== Rolling restart of $TOTAL services + gateway  $(date +%H:%M:%S) ==="
n=0
for s in $SERVICES; do
  n=$((n+1)); echo "[$n/$TOTAL] $s"
  restart_one "$s"
done
echo "=== all services done; restarting edge gateway (brief full blip) ==="
restart_one "gateway"
echo "=== DONE $(date +%H:%M:%S) ==="
echo "--- final gateway health ---"; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18000/actuator/health
echo "--- services listening ---"; ss -ltnp 2>/dev/null | grep -c java
