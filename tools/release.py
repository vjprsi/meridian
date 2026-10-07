#!/usr/bin/env python3
"""Release pinning, promotion, verification, rendering and change records for the deploy/ tree (PLT-GITOPS, GAP-05).

The deploy/ tree is the desired state Argo CD syncs (deploy/argocd). Each environment pins ONE release:

  deploy/<env>/images.yaml            release metadata + global.image.digests (component -> sha256 digest)
  deploy/<env>/cells/<cell>/cell.yaml `revision`: git commit of the Helm charts the cell renders

Sub-commands (all paths relative to the repository root):

  components                          list the components that get an image (same as ci.yml image-matrix)
  current   --env E                   print the release pinned in E (revision, source, pinnedAt)
  pin       --env E --revision SHA    pin a CI build: digests from --digests-dir (ci.yml digest artifacts),
                                      --digests-json or --resolve (crane digest <registry>/<name>:<SHA>)
  promote   --from A --to B           copy A's release (same revision, same digests) to B - promotion never rebuilds
  rollback  --env E --to REF          restore E's pins as they were at git REF (a commit, tag or change record's merge)
  verify    --env E                   cosign verify (keyless, identity of ci.yml on main, built from the pinned commit),
                                      cosign verify-attestation (CycloneDX SBOM) and Trivy (fixable CRITICAL) per digest
  render    --env E                   helm template every cell x chart; digest pins, no :latest; kubeconform -strict
                                      (CRD schemas from the datree catalog); --policies also runs the Kyverno CLI
  change-record --env E               markdown change record of the pending change (working tree vs HEAD): commits,
                                      ticket references, affected components, migrations, risk level, rollback plan
  check                               repository identity in deploy/pipeline.yaml matches Argo CD manifests, policies
                                      and base values; cell revisions match images.yaml; every component is pinned
  provenance --env E --base REF       pull-request gate: E's new pins existed verbatim in the previous environment
                                      (promotion) or in E's own history (rollback) - releases are never edited

Typical pipeline (.github/workflows/promote.yml): pin|promote|rollback -> verify --evidence -> render ->
change-record --evidence -> pull request. Local dry run: see deploy/README.md.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DEPLOY = ROOT / "deploy"
CHARTS = {"meridian": ROOT / "infra" / "helm" / "meridian", "meridian-router": ROOT / "infra" / "helm" / "meridian-router"}
RELEASE_NAMES = {"meridian": "meridian", "meridian-router": "router"}
ZERO = "0" * 40
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
K8S_VERSION = "1.33.0"
CRD_SCHEMAS = "https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

# Change-record classification
MIGRATION_RE = re.compile(r"(^|/)db/migration/|^generated/db/")
DESTRUCTIVE_SQL_RE = re.compile(
    r"\b(DROP\s+(TABLE|COLUMN|INDEX|CONSTRAINT|TYPE|SCHEMA|VIEW|FUNCTION)|RENAME\s+(COLUMN|TO|CONSTRAINT)|"
    r"ALTER\s+COLUMN\s+\S+\s+(TYPE|SET\s+NOT\s+NULL)|TRUNCATE)\b", re.I)
HIGH_RISK_PATHS = [
    (re.compile(r"^infra/helm/[^/]+/templates/|^tools/helm_chart\.py$"), "deployment templates (Helm chart) changed"),
    (re.compile(r"^services/card-[^/]+/"), "cardholder data environment (CDE) component changed"),
    (re.compile(r"^libs/meridian-web/.*/security/|^tools/keycloak_realm\.py$|^infra/local/keycloak"), "authentication / authorisation code changed"),
    (re.compile(r"^services/(ledger|gl|payment|identity|audit)-service/"), "money movement, ledger, identity or audit service changed"),
    (re.compile(r"^deploy/(base|prod)/|^deploy/argocd/|^deploy/policies/"), "deployment configuration / admission policy changed"),
]
DOC_ONLY_RE = re.compile(r"\.(md|txt)$|^docs/|^tracker/|^spec/plan\.yaml$|(^|/)src/test/|^tools/e2e/")


# --------------------------------------------------------------------------------------------------- helpers

def die(msg: str) -> None:
    print(f"release.py: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd: list[str], check: bool = True, cwd: Path = ROOT, **kw) -> subprocess.CompletedProcess:
    exe = shutil.which(cmd[0])
    if not exe:
        die(f"{cmd[0]} not found on PATH")
    return subprocess.run([exe, *cmd[1:]], cwd=cwd, check=check, text=True, capture_output=True, **kw)


def git(*args: str, check: bool = True) -> str:
    return run(["git", *args], check=check).stdout


def pipeline() -> dict:
    return yaml.safe_load((DEPLOY / "pipeline.yaml").read_text(encoding="utf-8"))


def env_names() -> list[str]:
    return [e["name"] for e in pipeline()["environments"]]


def env_conf(env: str) -> dict:
    for e in pipeline()["environments"]:
        if e["name"] == env:
            return e
    die(f"unknown environment {env!r} (deploy/pipeline.yaml: {', '.join(env_names())})")


def components() -> list[str]:
    names = [s["name"] for s in json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))]
    return names + [c for c in pipeline()["platformComponents"] if c not in names]


def cells(env: str) -> list[tuple[str, Path, dict]]:
    out = []
    for f in sorted((DEPLOY / env / "cells").glob("*/cell.yaml")):
        out.append((f.parent.name, f, yaml.safe_load(f.read_text(encoding="utf-8"))))
    if not out:
        die(f"deploy/{env}/cells/*/cell.yaml: no cells")
    return out


def images_path(env: str) -> Path:
    return DEPLOY / env / "images.yaml"


def load_images(env: str, text: str | None = None) -> dict:
    if text is None:
        p = images_path(env)
        if not p.exists():
            return {"release": {"revision": ZERO}, "global": {"image": {"tag": "", "digests": {}}}}
        text = p.read_text(encoding="utf-8")
    return yaml.safe_load(text) or {}


def release_of(doc: dict) -> dict:
    return doc.get("release") or {"revision": ZERO}


def digests_of(doc: dict) -> dict:
    return ((doc.get("global") or {}).get("image") or {}).get("digests") or {}


def write_images(env: str, revision: str, digests: dict, source: str, change_record: str = "") -> None:
    missing = [c for c in components() if c not in digests]
    if missing:
        die(f"no digest for: {', '.join(missing)}")
    bad = {c: d for c, d in digests.items() if not DIGEST_RE.match(d)}
    if bad:
        die(f"not a sha256 digest: {bad}")
    if not SHA_RE.match(revision):
        die(f"revision must be a full 40-character commit sha: {revision!r}")
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    lines = [
        "# Managed by tools/release.py (PLT-GITOPS) - do not edit by hand: pin / promote / rollback rewrite this file.",
        f"# The release pinned in {env}: Helm values layer 2 for both charts (deploy/argocd/applicationsets).",
        "release:",
        f"  revision: \"{revision}\"",
        f"  source: {json.dumps(source)}",
        f"  pinnedAt: \"{now}\"",
        f"  changeRecord: {json.dumps(change_record)}",
        "global:",
        "  image:",
        f"    tag: \"{revision}\"   # version label only: every image is pulled by digest",
        "    digests:",
    ]
    lines += [f"      {c}: \"{digests[c]}\"" for c in components()]
    images_path(env).write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    for _, path, conf in cells(env):
        text = path.read_text(encoding="utf-8")
        new = re.sub(r"(?m)^revision: .*$", f"revision: \"{revision}\"", text)
        if not re.search(r"(?m)^revision: ", text):
            die(f"{path}: no `revision:` line")
        path.write_text(new, encoding="utf-8", newline="\n")


def set_change_record(env: str, record: str) -> None:
    p = images_path(env)
    text = re.sub(r'(?m)^  changeRecord: .*$', f"  changeRecord: {json.dumps(record)}", p.read_text(encoding="utf-8"))
    p.write_text(text, encoding="utf-8", newline="\n")


# --------------------------------------------------------------------------------------------------- commands

def cmd_components(_a) -> None:
    print("\n".join(components()))


def cmd_current(a) -> None:
    rel = release_of(load_images(a.env))
    if a.field:
        print(rel.get(a.field, ""))
    else:
        print(json.dumps(rel, indent=2))


def cmd_pin(a) -> None:
    conf = pipeline()
    digests: dict[str, str] = {}
    if a.digests_dir:
        for f in Path(a.digests_dir).rglob("*.digest"):
            digests[f.stem] = f.read_text(encoding="utf-8").strip()
    elif a.digests_json:
        digests = json.loads(Path(a.digests_json).read_text(encoding="utf-8"))
    elif a.resolve:
        def resolve(c):
            return c, run(["crane", "digest", f"{conf['registry']}/{c}:{a.revision}"]).stdout.strip()
        with concurrent.futures.ThreadPoolExecutor(8) as ex:
            digests = dict(ex.map(resolve, components()))
    else:
        die("pin needs --digests-dir, --digests-json or --resolve")
    write_images(a.env, a.revision, digests, a.source or f"ci build of {a.revision}")
    print(f"pinned {a.env} to {a.revision} ({len(components())} digests)")


def cmd_promote(a) -> None:
    order = env_names()
    if order.index(a.to) != order.index(a.frm) + 1 and not a.allow_skip:
        die(f"{a.to} is promoted from {env_conf(a.to)['from']}, not {a.frm} (deploy/pipeline.yaml)")
    src = load_images(a.frm)
    rel = release_of(src)
    if rel["revision"] == ZERO:
        die(f"{a.frm} has no release pinned")
    for _, _, conf in cells(a.frm):
        if conf["revision"] != rel["revision"]:
            die(f"{a.frm} cell {conf['cell']} renders {conf['revision']}, images.yaml says {rel['revision']}")
    write_images(a.to, rel["revision"], digests_of(src), f"promoted from {a.frm} ({rel.get('source', '')})")
    print(f"promoted {rel['revision']} {a.frm} -> {a.to}")


def cmd_rollback(a) -> None:
    rel_path = f"deploy/{a.env}/images.yaml"
    try:
        text = git("show", f"{a.to}:{rel_path}")
    except subprocess.CalledProcessError:
        die(f"{rel_path} does not exist at {a.to}")
    old = load_images(a.env, text)
    rel = release_of(old)
    if rel["revision"] == ZERO:
        die(f"{a.env} had no release pinned at {a.to}")
    write_images(a.env, rel["revision"], digests_of(old), f"rollback to the pins of {a.to} ({rel.get('source', '')})")
    print(f"rolled {a.env} back to revision {rel['revision']} (pins of {a.to})")


def cmd_verify(a) -> None:
    conf = pipeline()
    doc = load_images(a.env)
    rel = release_of(doc)
    digests = digests_of(doc)
    signer = conf["signer"]
    ident = ["--certificate-oidc-issuer", signer["issuer"], "--certificate-identity", signer["identity"],
             "--certificate-github-workflow-sha", rel["revision"]]

    def check(component: str) -> dict:
        ref = f"{conf['registry']}/{component}@{digests[component]}"
        r = {"component": component, "ref": ref}
        p = run(["cosign", "verify", *ident, ref], check=False)
        r["signature"] = p.returncode == 0
        r["signatureError"] = "" if r["signature"] else p.stderr.strip()[-400:]
        p = run(["cosign", "verify-attestation", "--type", "cyclonedx", *ident, ref], check=False)
        r["sbom"] = p.returncode == 0
        r["sbomError"] = "" if r["sbom"] else p.stderr.strip()[-400:]
        if a.skip_trivy:
            r["trivy"] = None
        else:
            p = run(["trivy", "image", "--quiet", "--severity", "CRITICAL", "--ignore-unfixed", "--exit-code", "1",
                     "--format", "json", "--output", os.devnull, ref], check=False)
            r["trivy"] = p.returncode == 0
            r["trivyError"] = "" if r["trivy"] else (p.stderr.strip()[-400:] or "fixable CRITICAL vulnerabilities")
        return r

    with concurrent.futures.ThreadPoolExecutor(a.parallel) as ex:
        results = list(ex.map(check, components()))
    failed = [r for r in results if not r["signature"] or not r["sbom"] or r["trivy"] is False]
    evidence = {
        "environment": a.env, "revision": rel["revision"], "verifiedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "signer": signer, "trivy": "skipped" if a.skip_trivy else "CRITICAL, fixable, exit-code 1",
        "results": results, "passed": not failed,
    }
    if a.evidence:
        Path(a.evidence).write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    for r in failed:
        print(f"FAIL {r['component']}: signature={r['signature']} sbom={r['sbom']} trivy={r['trivy']} "
              f"{r.get('signatureError') or r.get('sbomError') or r.get('trivyError', '')}", file=sys.stderr)
    print(f"verify {a.env}: {len(results) - len(failed)}/{len(results)} components passed")
    if failed:
        sys.exit(1)


def rendered_images(docs: list[dict]) -> list[str]:
    out = []
    for d in docs:
        if not d:
            continue
        spec = (((d.get("spec") or {}).get("template") or {}).get("spec") or {}) if d.get("kind") != "Pod" else d.get("spec", {})
        for key in ("initContainers", "containers"):
            out += [c["image"] for c in spec.get(key) or []]
    return out


def cmd_render(a) -> None:
    out_dir = Path(a.out) if a.out else Path(tempfile.mkdtemp(prefix="meridian-render-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    envs = env_names() if a.env == "all" else [a.env]
    files = []
    for env in envs:
        for cell, _, conf in cells(env):
            for chart, chart_dir in CHARTS.items():
                values = [DEPLOY / "base" / f"{chart}.yaml", images_path(env), DEPLOY / env / f"{chart}.yaml",
                          DEPLOY / env / "cells" / cell / f"{chart}.yaml"]
                ns = conf["namespace"] if chart == "meridian" else conf["routerNamespace"]
                cmd = ["helm", "template", RELEASE_NAMES[chart], str(chart_dir), "-n", ns,
                       "--kube-version", K8S_VERSION]
                for v in values:
                    if v.exists():
                        cmd += ["-f", str(v)]
                p = run(cmd, check=False)
                if p.returncode:
                    die(f"helm template {env}/{cell}/{chart} failed:\n{p.stderr}")
                target = out_dir / f"{env}--{cell}--{chart}.yaml"
                docs = [d for d in yaml.safe_load_all(p.stdout) if d]
                for d in docs:   # what Argo CD applies: the Application's namespace for objects without one
                    d.setdefault("metadata", {}).setdefault("namespace", ns)
                target.write_text(yaml.safe_dump_all(docs, sort_keys=False), encoding="utf-8")
                images = rendered_images(docs)
                bad = [i for i in images if "@sha256:" not in i or i.split("@")[0].endswith(":latest")]
                if bad or not images:
                    die(f"{env}/{cell}/{chart}: images not pinned by digest: {bad or 'no images rendered'}")
                regs = {i.rsplit("/", 1)[0] for i in images}
                if regs != {pipeline()["registry"]}:
                    die(f"{env}/{cell}/{chart}: images from {regs}, expected only {pipeline()['registry']}")
                files.append(target)
                print(f"rendered {env}/{cell}/{chart}: {len(docs)} objects, {len(images)} digest-pinned images")
    if a.kubeconform:
        # the rendered cells plus the Argo CD manifests and the Kyverno policies themselves (CRD schemas)
        own = [str(f) for f in sorted((DEPLOY / "argocd").rglob("*.yaml"))]
        own += [str(f) for f in sorted((DEPLOY / "policies").glob("*.yaml"))]
        p = run(["kubeconform", "-strict", "-summary", "-kubernetes-version", K8S_VERSION,
                 "-schema-location", "default", "-schema-location", a.schema_location or CRD_SCHEMAS,
                 *map(str, files), *own], check=False)
        print(p.stdout.strip())
        if p.returncode:
            die(f"kubeconform failed:\n{p.stderr}")
    if a.policies:
        policies = [str(f) for f in sorted((DEPLOY / "policies").glob("*.yaml")) if "verify-images" not in f.name]
        p = run(["kyverno", "apply", *policies, "--resource", *map(str, files), "--policy-report", "--output", str(out_dir / "policy-report.yaml")],
                check=False)
        tail = "\n".join(p.stdout.strip().splitlines()[-6:])
        print(tail)
        if p.returncode:
            die(f"kyverno apply reported violations:\n{p.stdout[-4000:]}\n{p.stderr[-2000:]}")
    print(f"manifests in {out_dir}")


# ------------------------------------------------------------------------------------------- change records

def plan_ids() -> set[str]:
    text = (ROOT / "spec" / "plan.yaml").read_text(encoding="utf-8")
    return set(re.findall(r"\{id: ([A-Z][A-Z0-9-]+)", text))


TICKET_RE = re.compile(r"(?<![\w/-])([A-Z][A-Z0-9]{1,9}(?:-[A-Z0-9]+)+)(?![\w-])|(?<![\w&])#(\d+)\b")


def tickets_in(text: str, known: set[str]) -> list[str]:
    found = []
    for m in TICKET_RE.finditer(text):
        if m.group(2):
            found.append(f"#{m.group(2)}")
        elif re.search(r"-\d+$", m.group(1)) or m.group(1) in known:
            found.append(m.group(1))
    return list(dict.fromkeys(found))


def commit_exists(rev: str) -> bool:
    return bool(rev) and rev != ZERO and run(["git", "cat-file", "-e", f"{rev}^{{commit}}"], check=False).returncode == 0


def cmd_change_record(a) -> None:
    env = a.env
    conf = pipeline()
    new_doc = load_images(env)
    new_rel = release_of(new_doc)
    try:
        old_doc = load_images(env, git("show", f"{a.base_ref}:deploy/{env}/images.yaml"))
    except subprocess.CalledProcessError:
        old_doc = {"release": {"revision": ZERO}}
    old_rel = release_of(old_doc)
    base, rev = (a.base_revision or old_rel["revision"]), (a.revision or new_rel["revision"])
    if not commit_exists(rev):
        die(f"revision {rev} is not a commit in this clone (fetch with full history)")
    initial = not commit_exists(base)
    known = plan_ids()

    if initial:
        log_range = [rev, "-n", str(a.max_commits)]
        changed = []
    elif base == rev:
        log_range, changed = None, []
    else:
        log_range = [f"{base}..{rev}"]
        changed = [l for l in git("diff", "--name-only", base, rev).splitlines() if l]
    commits = []
    if log_range:
        raw = git("log", "--no-merges", "--format=%H%x1f%an%x1f%aI%x1f%s%x1f%b%x1e", *log_range)
        for rec in raw.split("\x1e"):
            rec = rec.strip("\n")
            if not rec:
                continue
            sha, author, date, subject, body = (rec.split("\x1f") + [""] * 5)[:5]
            commits.append({"sha": sha, "author": author, "date": date[:10], "subject": subject,
                            "tickets": tickets_in(subject + "\n" + body, known)})
    all_tickets = list(dict.fromkeys(t for c in commits for t in c["tickets"]))
    untracked = [c for c in commits if not c["tickets"]]

    # affected components and migrations
    comp = set(components())
    affected, shared = set(), []
    for f in changed:
        m = re.match(r"^(services|platform)/([^/]+)/", f)
        if m and m.group(2) in comp:
            affected.add(m.group(2))
        elif re.match(r"^(libs|build-logic|gradle)/|^build\.gradle\.kts$|^infra/docker/", f):
            shared.append(f)
    migrations = sorted(f for f in changed if MIGRATION_RE.search(f) and f.endswith(".sql"))
    destructive = []
    for f in migrations:
        diff = git("diff", base, rev, "--", f)
        added = "\n".join(l[1:] for l in diff.splitlines() if l.startswith("+") and not l.startswith("+++"))
        hits = sorted({m.group(0).upper() for m in DESTRUCTIVE_SQL_RE.finditer(added)})
        if hits:
            destructive.append((f, hits))
    old_d, new_d = digests_of(old_doc), digests_of(new_doc)
    image_changes = [(c, old_d.get(c, ""), new_d.get(c, "")) for c in components() if old_d.get(c) != new_d.get(c)]

    # risk
    reasons_high, reasons_med = [], []
    if initial:
        reasons_high.append("first release pinned in this environment (no previous revision to compare)")
    if a.mode == "rollback":
        reasons_high.append("rollback: restores an earlier release")
    if destructive:
        reasons_high.append("migration with a contract step (DROP / RENAME / type change): image rollback is unsafe")
    elif migrations:
        reasons_med.append(f"{len(migrations)} schema migration(s) (expand-only)")
    for rx, why in HIGH_RISK_PATHS:
        if any(rx.search(f) for f in changed):
            reasons_high.append(why)
    if shared:
        reasons_med.append(f"shared build / library change ({len(shared)} files): every component is rebuilt")
    code = [f for f in changed if not DOC_ONLY_RE.search(f)]
    if code and not reasons_high:
        reasons_med.append(f"{len(code)} code / configuration file(s) changed")
    if env == "prod" and not reasons_high and not reasons_med:
        reasons_med.append("production change (minimum risk level for prod)")
    risk = "High" if reasons_high else "Medium" if reasons_med else "Low"

    # evidence
    evidence = json.loads(Path(a.evidence).read_text(encoding="utf-8")) if a.evidence and Path(a.evidence).exists() else None

    short = rev[:12]
    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    record_rel = a.out or f"deploy/change-records/{env}/{today}-{short}{'-rollback' if a.mode == 'rollback' else ''}.md"
    ec = env_conf(env)
    cell_rows = [f"| `{c['cell']}` | {c['region']} | {c['role']} | `{c['namespace']}`, `{c['routerNamespace']}` |" for _, _, c in cells(env)]
    approvals = {
        "dev": "Promotion PR merged under the standard branch protection (CI green; dev is auto-synced by Argo CD).",
        "staging": "Promotion PR: CI + `deploy-check` green and 1 approval (code owners of `deploy/staging/`); the "
                   "`staging` GitHub environment gates the promotion job. Argo CD auto-syncs after merge.",
        "prod": "Promotion PR: **2 approvals** from `@meridian-org/release-managers` (CODEOWNERS `deploy/prod/`, enforced by "
                "the `deploy-check / prod approvals` status check; the author cannot approve), plus the `prod` GitHub "
                "environment's required reviewers on the promotion job. Argo CD **manual sync** per cell inside the "
                "`meridian-prod` sync windows.",
    }[env]

    L = [f"# Change record: {env} {short}{' (rollback)' if a.mode == 'rollback' else ''}", ""]
    L += [f"Generated by `tools/release.py change-record` on {dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()}"
          + (f" by {a.actor}" if a.actor else "") + (f" for ticket {a.ticket}" if a.ticket else "") + ".", ""]
    L += ["| Field | Value |", "|---|---|",
          f"| Environment | `{env}` ({ec['sync']} sync){' - region pair prod-' + ec['region'] if ec.get('region') else ''} |",
          f"| Change type | {'Rollback' if a.mode == 'rollback' else 'Promotion' if ec['from'] != 'ci' else 'Continuous delivery (CI build of main)'} |",
          f"| Release (git revision) | `{rev}` |",
          f"| Previous release | {'none' if initial else f'`{base}`'} |",
          f"| Source | {new_rel.get('source', '')} |",
          f"| Risk level | **{risk}** |",
          f"| Tickets | {', '.join(all_tickets) or 'none referenced'} |",
          f"| Images changed | {len(image_changes)} of {len(components())} |",
          f"| Commits | {len(commits)}{' (latest ' + str(a.max_commits) + ', initial release)' if initial else ''} |", ""]
    L += ["## Cells", "", "| Cell | Region | Role | Namespaces |", "|---|---|---|---|", *cell_rows, ""]
    if env == "prod":
        L += ["Sync order: primary cell first; standby cell right after the primary is healthy (a failover must not "
              "change versions).", ""]
    L += ["## Risk assessment", ""]
    L += [f"- High: {r}" for r in reasons_high] + [f"- Medium: {r}" for r in reasons_med]
    if not reasons_high and not reasons_med:
        L += ["- Low: documentation, tests or tracker only"]
    L += [""]
    L += ["## Schema migrations", ""]
    if not migrations:
        L += ["None: the image rollback below is safe.", ""]
    else:
        L += ["Run by the `<service>-migrate` Jobs (Argo CD PreSync) before any Deployment changes. Flyway is "
              "forward-only (expand / contract, `docs/CONVENTIONS.md` section 3).", ""]
        contract = dict(destructive)
        L += [f"- `{f}`" + (f" - **contract step: {', '.join(contract[f])}**" if f in contract else " (expand)")
              for f in migrations]
        L += [""]
    L += ["## Rollback plan", ""]
    if initial:
        L += ["First release in this environment: there is no earlier release to return to. Fix forward, or revert "
              "the merge of this PR to return the environment to its unpinned state (Argo CD then reports the "
              "Applications as failing to render; nothing running is replaced).", ""]
    else:
        L += [f"1. Restore the previous pins: run the **promote** workflow with `mode=rollback`, `environment={env}` "
              f"and `rollback_to=<merge commit before this change>`, or locally "
              f"`python tools/release.py rollback --env {env} --to <that commit>` and open a PR. Reverting the merge "
              f"commit of this PR (`git revert -m 1 <merge>`) is equivalent. The previous release `{base[:12]}` keeps "
              "its signatures, so it passes the same gates.",
              "2. Argo CD: " + ({"dev": "auto-sync applies the revert.", "staging": "auto-sync applies the revert.",
                                 "prod": "a release manager syncs each cell (primary, then standby); outside a sync "
                                         "window use the break-glass procedure in RUNBOOKS section 1."}[env]),
              "3. Schema: " + ("**do not roll the image back** - the release contains a contract step; the previous "
                               "release may read dropped / renamed columns. Roll forward with a fix instead."
                               if destructive else
                               "expand-only migrations stay applied; the previous images ignore the new objects."
                               if migrations else "no migrations in this change."),
              "4. Verify: readiness of the changed services, router error share and 5xx back to baseline, "
              "`python tools/e2e/journeys.py` against the environment.", ""]
        L += ["Previous digests of the changed images (what a rollback restores):", ""]
        L += ["| Component | Previous digest | New digest |", "|---|---|---|"]
        L += [f"| {c} | `{o[:19] + '...' if o else 'none'}` | `{n[:19]}...` |" for c, o, n in image_changes] or ["| none | | |"]
        L += [""]
    L += ["## Approvals", "", approvals, ""]
    L += ["## Verification gates", ""]
    if evidence:
        res = evidence["results"]
        ok = lambda k: sum(1 for r in res if r.get(k))
        L += [f"Verified {evidence['verifiedAt'][:19]}Z against identity `{evidence['signer']['identity']}` "
              f"(issuer `{evidence['signer']['issuer']}`), built from `{evidence['revision'][:12]}`:", "",
              f"- Signatures (cosign keyless, Rekor): {ok('signature')}/{len(res)}",
              f"- SBOM attestations (CycloneDX): {ok('sbom')}/{len(res)}",
              f"- Trivy (fixable CRITICAL): " + (evidence["trivy"] if evidence["trivy"] == "skipped"
                                                 else f"{ok('trivy')}/{len(res)} clean"),
              f"- Overall: **{'PASSED' if evidence['passed'] else 'FAILED'}**", ""]
    else:
        L += ["No verification evidence attached (dry run). The promote workflow runs `tools/release.py verify` "
              "before opening the PR, and Kyverno verifies signatures again at admission.", ""]
    L += [f"## Affected components ({len(affected)}{' + all, via shared code' if shared else ''})", ""]
    L += [", ".join(f"`{c}`" for c in sorted(affected)) or "No component directories changed.", ""]
    L += ["## Commits", "", "| Commit | Date | Author | Subject | Tickets |", "|---|---|---|---|---|"]
    for c in commits:
        subj = c["subject"].replace("|", "\\|")
        L += [f"| `{c['sha'][:10]}` | {c['date']} | {c['author']} | {subj} | {', '.join(c['tickets']) or '-'} |"]
    if not commits:
        L += ["| - | | | no new commits (same revision) | |"]
    L += [""]
    if untracked:
        L += [f"{len(untracked)} commit(s) without a ticket reference - the approvers confirm they are covered by the "
              "tickets above or are trivial.", ""]
    if len(changed) and a.list_files:
        L += ["<details><summary>Changed files</summary>", "", *[f"- `{f}`" for f in changed], "", "</details>", ""]

    out = ROOT / record_rel
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L), encoding="utf-8", newline="\n")
    if images_path(env).exists() and not a.no_link:
        set_change_record(env, record_rel)
    log = out.parent / "CHANGELOG.md"
    head = f"# {env} change log\n\nOne line per release pinned in `deploy/{env}` (newest first); details in the linked change record.\n\n| Date | Revision | Type | Risk | Tickets | Record |\n|---|---|---|---|---|---|\n"
    body = log.read_text(encoding="utf-8")[len(head):] if log.exists() and log.read_text(encoding="utf-8").startswith(head) else ""
    line = f"| {today} | `{short}` | {a.mode} | {risk} | {', '.join(all_tickets[:8]) + (' ...' if len(all_tickets) > 8 else '') or '-'} | [{out.name}]({out.name}) |\n"
    log.write_text(head + line + body, encoding="utf-8", newline="\n")
    if a.github_output:
        with open(a.github_output, "a", encoding="utf-8") as fh:
            fh.write(f"record={record_rel}\nrisk={risk}\nrevision={rev}\nshort={short}\n")
    print(f"{record_rel}: risk {risk}, {len(commits)} commits, {len(all_tickets)} tickets, {len(image_changes)} images changed")


# --------------------------------------------------------------------------------------------- provenance

def pins(doc: dict) -> tuple:
    return release_of(doc).get("revision"), tuple(sorted(digests_of(doc).items()))


def history(ref: str, path: str, limit: int = 300):
    for sha in git("log", ref, f"-n{limit}", "--format=%H", "--", path).split():
        try:
            yield sha, load_images("", git("show", f"{sha}:{path}"))
        except subprocess.CalledProcessError:
            continue


def cmd_provenance(a) -> None:
    """Immutability gate for pull requests: a changed deploy/<env>/images.yaml must carry pins that already existed,
    verbatim, in the environment before it (promotion) or in its own history (rollback). dev pins are new by nature;
    their provenance is the signature check (`verify`: certificate bound to ci.yml on main and to the commit)."""
    env = a.env
    path = f"deploy/{env}/images.yaml"
    new = load_images(env)
    try:
        old = load_images(env, git("show", f"{a.base}:{path}"))
    except subprocess.CalledProcessError:
        old = {}
    if pins(new) == pins(old):
        print(f"{env}: pins unchanged")
        return
    src = env_conf(env)["from"]
    if src == "ci":
        print(f"{env}: new CI release {release_of(new)['revision']} - provenance by signature verification")
        return
    for sha, doc in history(a.base, f"deploy/{src}/images.yaml"):
        if pins(doc) == pins(new):
            print(f"{env}: promotion of the pins {src} had at {sha[:12]} (same revision, same {len(digests_of(doc))} digests)")
            return
    for sha, doc in history(a.base, path):
        if pins(doc) == pins(new):
            print(f"{env}: rollback to the pins {env} had at {sha[:12]}")
            return
    die(f"{path}: the new pins never existed in deploy/{src} (promotion) or in {env}'s own history (rollback) - "
        f"releases are promoted unchanged, never edited (tools/release.py promote / rollback)")


# ------------------------------------------------------------------------------------------------- check

def cmd_check(_a) -> None:
    conf = pipeline()
    errors = []
    repo, git_url, registry, identity = conf["repository"], conf["gitUrl"], conf["registry"], conf["signer"]["identity"]
    if identity != f"https://github.com/{repo}/.github/workflows/ci.yml@refs/heads/main":
        errors.append("pipeline.yaml signer.identity does not match repository")
    for f in sorted((DEPLOY / "argocd").rglob("*.yaml")):
        for url in set(re.findall(r"https://github\.com/[\w.-]+/[\w.-]+?\.git", f.read_text(encoding="utf-8"))):
            if url != git_url:
                errors.append(f"{f.relative_to(ROOT)}: repo URL {url} != {git_url}")
    for f in sorted((DEPLOY / "policies").glob("*.yaml")):
        text = f.read_text(encoding="utf-8")
        for reg in set(re.findall(r"ghcr\.io/[\w.-]+", text)):
            if reg != registry:
                errors.append(f"{f.relative_to(ROOT)}: registry {reg} != {registry}")
        for subj in set(re.findall(r"https://github\.com/[^\s\"']+ci\.yml@[^\s\"']+", text)):
            if subj != identity:
                errors.append(f"{f.relative_to(ROOT)}: signer {subj} != {identity}")
    for chart in CHARTS:
        base = yaml.safe_load((DEPLOY / "base" / f"{chart}.yaml").read_text(encoding="utf-8"))
        reg = (base.get("global", base) or {}).get("image", {}).get("registry")
        if reg != registry:
            errors.append(f"deploy/base/{chart}.yaml: registry {reg} != {registry}")
    for env in env_names():
        doc = load_images(env)
        rel = release_of(doc)
        missing = [c for c in components() if c not in digests_of(doc)]
        if missing:
            errors.append(f"deploy/{env}/images.yaml: no digest for {', '.join(missing[:5])}{' ...' if len(missing) > 5 else ''}")
        extra = [c for c in digests_of(doc) if c not in components()]
        if extra:
            errors.append(f"deploy/{env}/images.yaml: unknown components {extra}")
        projects = {d["metadata"]["name"]: d for d in yaml.safe_load_all(
            (DEPLOY / "argocd" / "projects.yaml").read_text(encoding="utf-8")) if d}
        project = projects.get(f"meridian-{env}")
        if not project:
            errors.append(f"deploy/argocd/projects.yaml: no AppProject meridian-{env}")
        if not (DEPLOY / "argocd" / "applicationsets" / f"{env}.yaml").exists():
            errors.append(f"deploy/argocd/applicationsets/{env}.yaml missing")
        required = ["cell", "environment", "region", "role", "cluster", "namespace", "routerNamespace", "revision"]
        required += ["syncWave"] if env == "prod" else []
        for name, path, c in cells(env):
            missing_keys = [k for k in required if k not in c]
            if missing_keys:
                errors.append(f"{path.relative_to(ROOT)}: missing {missing_keys} (used by the ApplicationSet template)")
            if c.get("cell") != name:
                errors.append(f"{path.relative_to(ROOT)}: cell {c.get('cell')} != directory {name}")
            for chart in CHARTS:
                if not (path.parent / f"{chart}.yaml").exists():
                    errors.append(f"{path.parent.relative_to(ROOT)}/{chart}.yaml missing (ApplicationSet valueFiles)")
            if project:
                dests = {(d.get("name"), d.get("namespace")) for d in project["spec"]["destinations"]}
                for ns in (c.get("namespace"), c.get("routerNamespace")):
                    if (c.get("cluster"), ns) not in dests:
                        errors.append(f"AppProject meridian-{env} does not allow {c.get('cluster')}/{ns}")
            if c.get("environment") != env:
                errors.append(f"{path.relative_to(ROOT)}: environment {c.get('environment')} != {env}")
            if c.get("revision") != rel.get("revision"):
                errors.append(f"{path.relative_to(ROOT)}: revision {c.get('revision')} != images.yaml {rel.get('revision')}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    print(f"deploy/ consistent: {len(env_names())} environments, {len(components())} components, repository {repo}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("components").set_defaults(fn=cmd_components)
    p = sub.add_parser("current"); p.add_argument("--env", required=True); p.add_argument("--field"); p.set_defaults(fn=cmd_current)
    p = sub.add_parser("pin")
    p.add_argument("--env", required=True); p.add_argument("--revision", required=True); p.add_argument("--source")
    p.add_argument("--digests-dir"); p.add_argument("--digests-json"); p.add_argument("--resolve", action="store_true")
    p.set_defaults(fn=cmd_pin)
    p = sub.add_parser("promote")
    p.add_argument("--from", dest="frm", required=True); p.add_argument("--to", required=True)
    p.add_argument("--allow-skip", action="store_true", help="promote across a skipped environment (break-glass)")
    p.set_defaults(fn=cmd_promote)
    p = sub.add_parser("rollback"); p.add_argument("--env", required=True); p.add_argument("--to", required=True)
    p.set_defaults(fn=cmd_rollback)
    p = sub.add_parser("verify")
    p.add_argument("--env", required=True); p.add_argument("--evidence"); p.add_argument("--skip-trivy", action="store_true")
    p.add_argument("--parallel", type=int, default=6)
    p.set_defaults(fn=cmd_verify)
    p = sub.add_parser("render")
    p.add_argument("--env", required=True, help="environment or 'all'"); p.add_argument("--out")
    p.add_argument("--kubeconform", action="store_true"); p.add_argument("--policies", action="store_true")
    p.add_argument("--schema-location", help="CRD schema template for kubeconform (default: datree CRDs catalog)")
    p.set_defaults(fn=cmd_render)
    p = sub.add_parser("change-record")
    p.add_argument("--env", required=True)
    p.add_argument("--base-ref", default="HEAD", help="git ref holding the previous deploy/<env>/images.yaml (default HEAD)")
    p.add_argument("--base-revision", help="override the previous release revision")
    p.add_argument("--revision", help="override the new release revision (default: deploy/<env>/images.yaml)")
    p.add_argument("--mode", choices=["promote", "rollback"], default="promote")
    p.add_argument("--evidence"); p.add_argument("--ticket"); p.add_argument("--actor"); p.add_argument("--out")
    p.add_argument("--max-commits", type=int, default=50); p.add_argument("--list-files", action="store_true")
    p.add_argument("--no-link", action="store_true", help="do not write the record path into images.yaml")
    p.add_argument("--github-output", default=os.environ.get("GITHUB_OUTPUT"))
    p.set_defaults(fn=cmd_change_record)
    sub.add_parser("check").set_defaults(fn=cmd_check)
    p = sub.add_parser("provenance"); p.add_argument("--env", required=True)
    p.add_argument("--base", default="origin/main", help="git ref the pull request merges into")
    p.set_defaults(fn=cmd_provenance)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
