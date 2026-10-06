#!/usr/bin/env python3
"""Local launcher for Meridian services (infra must be up: infra/local docker compose).

  python tools/dev.py up ledger-service deposit-service gateway   # build, create DBs, start, wait for health
  python tools/dev.py up --phase 1                                 # every phase-1 service + gateway
  python tools/dev.py up --reset ledger-service                    # recreate the service's local database
  python tools/dev.py status
  python tools/dev.py down [names...]                              # stop all or some
  python tools/dev.py logs ledger-service

Services run as `java -jar` on JDK 25 (MERIDIAN_JDK25 or D:/DevTools/jdk-25). Logs and pids live in .run/.
Docker commands run through MERIDIAN_DOCKER (default: WSL Ubuntu-22.04 with the docker group).
"""
from __future__ import annotations

import json
import os
import shlex
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / ".run"
MANIFEST = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
SERVICES = {s["name"]: s for s in MANIFEST}
GATEWAY = {"name": "gateway", "module": ":platform:edge-gateway", "port": 18000,
           "jar": ROOT / "platform" / "edge-gateway" / "build" / "libs"}
JDK = Path(os.environ.get("MERIDIAN_JDK25", "D:/DevTools/jdk-25"))
JAVA = str(JDK / "bin" / ("java.exe" if os.name == "nt" else "java"))
DOCKER = os.environ.get("MERIDIAN_DOCKER", "wsl.exe -d Ubuntu-22.04 -e sg docker -c")


def docker(cmd: str) -> str:
    parts = shlex.split(DOCKER) + [cmd]
    return subprocess.run(parts, capture_output=True, text=True, check=False).stdout.replace("\0", "")


def ensure_database(db: str, reset: bool = False) -> None:
    """Creates the service database if missing. Before the first release V001 is regenerated from the spec, so
    when the generated DDL changed since the last run (or --reset) the local dev database is recreated."""
    import hashlib
    ddl = ROOT / "generated" / "db" / db / "V001__init.sql"
    digest = hashlib.sha256(ddl.read_bytes()).hexdigest() if ddl.exists() else ""
    marker = RUN / f"{db}.schema"
    # Unknown to the launcher (e.g. loaded by apply-ddl.sh without Flyway history) or schema changed: recreate.
    changed = (not marker.exists()) or marker.read_text() != digest
    if reset or changed:
        print(f"  {'resetting' if reset else 'schema changed; recreating'} local database {db} (dev data discarded)")
        docker(f"docker exec meridian-postgres psql -U meridian -d meridian -c \"drop database if exists {db} with (force)\"")
    exists = docker(f"docker exec meridian-postgres psql -U meridian -d meridian -tAc "
                    f"\"select 1 from pg_database where datname='{db}'\"").strip()
    if exists != "1":
        docker(f"docker exec meridian-postgres psql -U meridian -d meridian -c \"create database {db}\"")
        print(f"  created database {db}")
        marker.write_text(digest)  # only databases the launcher created are tracked


def ensure_app_role() -> None:
    docker("docker exec meridian-postgres psql -U meridian -d meridian -c \"DO \\$\\$ BEGIN IF NOT EXISTS "
           "(SELECT FROM pg_roles WHERE rolname='meridian_app') THEN CREATE ROLE meridian_app LOGIN PASSWORD "
           "'meridian_app' NOSUPERUSER NOBYPASSRLS; END IF; END \\$\\$;\"")


def target(name: str) -> dict:
    if name == "gateway":
        return GATEWAY
    s = SERVICES.get(name)
    if not s:
        sys.exit(f"unknown service {name}; see generated/services.json")
    return {"name": name, "module": f":services:{name}", "port": s["port"], "database": s["database"],
            "jar": ROOT / "services" / name / "build" / "libs"}


def gradlew() -> list[str]:
    return [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))]


def healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/actuator/health", timeout=2) as r:
            return b'"UP"' in r.read()
    except Exception:
        return False


def pid_file(name: str) -> Path:
    return RUN / f"{name}.pid"


def running(name: str) -> int | None:
    pf = pid_file(name)
    if not pf.exists():
        return None
    pid = int(pf.read_text())
    try:
        if os.name == "nt":
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True).stdout
            return pid if str(pid) in out else None
        os.kill(pid, 0)
        return pid
    except OSError:
        return None


def up(names: list[str], reset: bool = False) -> None:
    RUN.mkdir(exist_ok=True)
    (RUN / "logs").mkdir(exist_ok=True)
    targets = [target(n) for n in names]
    print(f"building {len(targets)} module(s)...")
    subprocess.run(gradlew() + [f"{t['module']}:bootJar" for t in targets] + ["-q", "--console=plain"],
                   cwd=ROOT, check=True)
    if any("database" in t for t in targets):
        ensure_app_role()
    for t in targets:
        if running(t["name"]):
            print(f"  {t['name']} already running")
            continue
        if "database" in t:
            ensure_database(t["database"], reset)
        jar = next(p for p in sorted(t["jar"].glob("*.jar")) if not p.name.endswith("-plain.jar"))
        log = open(RUN / "logs" / f"{t['name']}.log", "w", encoding="utf-8")
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        proc = subprocess.Popen([JAVA, "-XX:+UseZGC", "-Xms128m", "-Xmx512m", "-jar", str(jar)],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, creationflags=flags,
                                # central maker-checker through workflow-service (tests default to local mode)
                                env={**os.environ, "MERIDIAN_APPROVALS_MODE": os.environ.get("MERIDIAN_APPROVALS_MODE", "workflow")})
        pid_file(t["name"]).write_text(str(proc.pid))
        print(f"  started {t['name']} (pid {proc.pid}, port {t['port']})")
    deadline = time.time() + 180
    pending = {t["name"]: t["port"] for t in targets}
    while pending and time.time() < deadline:
        for n, port in list(pending.items()):
            if healthy(port):
                print(f"  {n} UP on http://localhost:{port}")
                del pending[n]
        time.sleep(1)
    for n in pending:
        print(f"  {n} NOT healthy after 180s; see .run/logs/{n}.log")
    if pending:
        sys.exit(1)


def down(names: list[str]) -> None:
    names = names or [p.stem for p in RUN.glob("*.pid")]
    for n in names:
        pid = running(n)
        if pid:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
            else:
                os.kill(pid, signal.SIGTERM)
            print(f"  stopped {n}")
        pid_file(n).unlink(missing_ok=True)


def status() -> None:
    for pf in sorted(RUN.glob("*.pid")):
        n = pf.stem
        port = GATEWAY["port"] if n == "gateway" else SERVICES[n]["port"]
        state = "UP" if healthy(port) else ("running" if running(n) else "stopped")
        print(f"  {n:32} {state:8} http://localhost:{port}")


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "up":
        reset = "--reset" in args
        args = [a for a in args if a != "--reset"]
        if args[:1] == ["--phase"]:
            phase = int(args[1])
            args = [s["name"] for s in MANIFEST if s["phase"] <= phase] + ["gateway"]
        up(args, reset)
    elif cmd == "down":
        down(args)
    elif cmd == "status":
        status()
    elif cmd == "logs":
        print((RUN / "logs" / f"{args[0]}.log").read_text(encoding="utf-8")[-8000:])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
