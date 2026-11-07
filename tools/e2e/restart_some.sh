#!/bin/bash
# Restart the named running processes (by .run/<name>.pid) onto their current jars, identically (argv + env from
# /proc), health-gated on their own port. Usage: restart_some.sh <name> [<name> ...]   (gateway = edge gateway)
set -u
cd ~/meridian
port_of() { ss -ltnp 2>/dev/null | grep "pid=$1," | grep -oE ':[0-9]+ ' | head -1 | tr -d ': '; }
for name in "$@"; do
  pidf=".run/$name.pid"; pid=$(cat "$pidf" 2>/dev/null)
  if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then echo "[$name] not running - skip"; continue; fi
  tr '\0' '\n' < /proc/$pid/cmdline > /tmp/rs.cmd; tr '\0' '\n' < /proc/$pid/environ > /tmp/rs.env
  kill "$pid"; for i in $(seq 1 12); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done; kill -9 "$pid" 2>/dev/null
  newpid=$(python3.12 - "$name" <<'PY'
import os,sys,subprocess
name=sys.argv[1]; env={}
for l in open('/tmp/rs.env'):
    l=l.rstrip('\n')
    if '=' in l: k,v=l.split('=',1); env[k]=v
argv=[l.rstrip('\n') for l in open('/tmp/rs.cmd') if l.strip()]
log=open(f'.run/logs/{name}.log','ab')
p=subprocess.Popen(argv,env=env,stdout=log,stderr=log,stdin=subprocess.DEVNULL,start_new_session=True,cwd=os.path.expanduser('~/meridian'))
open(f'.run/{name}.pid','w').write(str(p.pid)); print(p.pid)
PY
)
  ok=0
  for i in $(seq 1 90); do sleep 1; p2=$(port_of "$newpid")
    if [ -n "$p2" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$p2/actuator/health")" = "200" ]; then ok=1; echo "[$name] UP pid=$newpid port=$p2 (${i}s)"; break; fi
  done
  [ "$ok" = "1" ] || echo "[$name] !! NOT healthy after 90s - see .run/logs/$name.log"
done
