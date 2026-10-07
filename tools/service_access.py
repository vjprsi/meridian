#!/usr/bin/env python3
"""What each Meridian service may call and which Kafka topics it may use (GAP-16), derived from the code and the spec.

Used by tools/keycloak_realm.py (scopes of the per-service OAuth clients svc-<service>), tools/helm_chart.py (per-service
client Secrets, card-vault allow-list) and tools/kafka_acls.py (MSK IAM policies). Run it directly to print the table:

    python tools/service_access.py            # service -> HTTP scopes, produce / consume topics
    python tools/service_access.py --json

HTTP scopes of svc-<service> (client credentials; used only when the service calls without a user token to forward:
scheduled jobs, Kafka consumers, provider callbacks, customer / agent / business / merchant relays, asService calls):
  * meridian:internal, always (act for the tenant named in X-Tenant-Id);
  * <domain>:write for the domain of every service it calls. A called service is any Meridian service name that appears
    as a string literal in the caller's main sources or application.yml (ServiceClient calls name the target service),
    plus the services every service calls through the libraries (workflow-service for central approvals,
    config-service for tenant settings, business-day-service for the business date). <domain>:write also grants reads;
    the granularity is the domain, not the operation, because many paths are built at run time;
  * special scopes (platform:operator, channels:admin, risk:* ...) only when one of the caller's "/v1/..." path
    literals matches an operation of a called service that requires it, plus EXTRA_SCOPES below (data-lifecycle callers);
  * never a channel scope (channels:customer / agent / business / merchant), never meridian:admin.

Kafka (MSK IAM, tools/kafka_acls.py): a service produces only to its own domain topic meridian.<domain>.v1 (the outbox
relay; every event it declares in the spec is in that domain) and consumes the topics of its @KafkaListener
annotations (literal topics, ${property:default} defaults, topicPattern meridian\\..*\\.v1 = every domain topic) plus
meridian.platform.v1 for central approval decisions (libs/meridian-approvals ApprovalEventsDispatcher). Consumer group
ids all start with the service name.
"""
from __future__ import annotations

import json
import re
import sys
from functools import lru_cache
from pathlib import Path



ROOT = Path(__file__).resolve().parent.parent
CLIENT_PREFIX = "svc-"
INTERNAL_SCOPE = "meridian:internal"
ROUTING_SCOPE = "platform:routing-read"
CHANNEL_SCOPES = {"channels:customer", "channels:agent", "channels:business", "channels:merchant"}
# Services every service calls through libs/: central approvals, tenant settings, business date.
LIBRARY_CALLS = ["workflow-service", "config-service", "business-day-service"]
# Calls the path matching cannot see: the generic data-lifecycle API (libs/meridian-web DataLifecycleController,
# platform:operator) is called by archive-service (archive jobs, sources from its policies) and customer-service
# (DSAR fan-out); see meridian.data-lifecycle.callers.
EXTRA_SCOPES = {"archive-service": ["platform:operator"], "customer-service": ["platform:operator"]}
APPROVALS_TOPIC = "meridian.platform.v1"
ALL_TOPICS_PATTERN = r"meridian\\..*\\.v1"


@lru_cache(maxsize=1)
def manifest() -> list[dict]:
    return json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))


def client_id(service: str) -> str:
    return CLIENT_PREFIX + service


def domain_topic(domain: str) -> str:
    return f"meridian.{domain}.v1"


@lru_cache(maxsize=None)
def _sources(service: str) -> str:
    """Main Java sources and application.yml of a service (comments included: harmless over-approximation)."""
    base = ROOT / "services" / service / "src" / "main"
    parts = [p.read_text(encoding="utf-8") for p in sorted(base.rglob("*.java"))] if base.exists() else []
    yml = base / "resources" / "application.yml"
    if yml.exists():
        parts.append(yml.read_text(encoding="utf-8"))
    return "\n".join(parts)


def called_services(service: str) -> list[str]:
    names = {s["name"] for s in manifest()}
    text = _sources(service)
    found = {n for n in names if n != service and re.search(r'(?<![a-z0-9-])' + re.escape(n) + r'(?![a-z0-9-])', text)}
    found.update(n for n in LIBRARY_CALLS if n != service)
    return sorted(found)


@lru_cache(maxsize=None)
def operations(service: str) -> list[tuple[list[str], list[str]]]:
    """(path segments, accepted scopes) of every operation of a service's contract."""
    f = ROOT / "generated" / "openapi" / "services" / f"{service}.openapi.yaml"
    if not f.exists():
        return []
    import yaml   # only the scope derivation needs PyYAML (helm_chart.py / kafka_acls.py run without it)
    doc = yaml.load(f.read_text(encoding="utf-8"), Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader))
    ops = []
    for path, item in (doc.get("paths") or {}).items():
        for method, op in item.items():
            if not isinstance(op, dict) or "x-required-scope" not in op:
                continue
            scopes = [op["x-required-scope"]] + list(op.get("x-accepted-scopes") or [])
            ops.append(([s for s in path.split("/") if s], scopes))
    return ops


def _path_literals(service: str) -> list[list[str]]:
    out = []
    for lit in set(re.findall(r'"(/v1/[^"?\s]*)', _sources(service))):
        out.append([s for s in lit.split("/") if s])
    return out


def _matches(literal: list[str], template: list[str]) -> bool:
    if len(literal) > len(template):
        return False
    for a, b in zip(literal, template):
        if b.startswith("{") or a in ("%s", "%d", "*") or a == b:
            continue
        return False
    return True


def http_scopes(service: str, realm_scopes: set[str] | None = None) -> list[str]:
    """Scopes of the svc-<service> client (see the module docstring)."""
    by_name = {s["name"]: s for s in manifest()}
    scopes = {INTERNAL_SCOPE}
    literals = _path_literals(service)
    for target in called_services(service):
        scopes.add(f"{by_name[target]['domain']}:write")
        for template, accepted in operations(target):
            if not any(_matches(lit, template) for lit in literals):
                continue
            for s in accepted:
                if not (s.endswith(":read") or s.endswith(":write")):
                    scopes.add(s)
    scopes.update(EXTRA_SCOPES.get(service, []))
    # MR-GAME-DAY: every service reads its own cell's status from the registry (libs/meridian-web
    # CellPromotionWatcher: a standby service promotes itself when its cell turns ACTIVE)
    scopes.add(ROUTING_SCOPE)
    scopes -= CHANNEL_SCOPES
    scopes.discard("meridian:admin")
    if realm_scopes is not None:
        scopes &= realm_scopes | {INTERNAL_SCOPE}
    return sorted(scopes)


def _listener_topics(service: str) -> set[str]:
    """Topics of the service's @KafkaListener annotations; '*' for the all-domains pattern."""
    topics: set[str] = set()
    text = _sources(service)
    for m in re.finditer(r"@KafkaListener\((.*?)\)\s*\n\s*(?:public|void|private|protected|[A-Z])", text, re.S):
        args = m.group(1)
        if "topicPattern" in args:
            topics.add("*")
            continue
        t = re.search(r'topics\s*=\s*"(.*?)"\s*(?:,|$)', args, re.S)
        if not t:
            continue
        value = t.group(1)
        value = re.sub(r"\$\{[^:}]+:([^}]*)}", r"\1", value)        # ${prop:default} -> default
        topics.update(re.findall(r"meridian\.[a-z]+\.v1", value))
    return topics


def kafka_access(service: str) -> dict:
    by_name = {s["name"]: s for s in manifest()}
    all_topics = sorted({domain_topic(s["domain"]) for s in manifest()})
    consume = _listener_topics(service) | {APPROVALS_TOPIC}
    if "*" in consume:
        consume = set(all_topics)
    return {"produce": [domain_topic(by_name[service]["domain"])],
            "consume": sorted(consume & set(all_topics)),
            "group_prefix": service}


def table(realm_scopes: set[str] | None = None) -> dict:
    return {s["name"]: {"client_id": client_id(s["name"]), "scopes": http_scopes(s["name"], realm_scopes),
                        "calls": called_services(s["name"]), **kafka_access(s["name"])}
            for s in manifest()}


def main():
    t = table()
    if "--json" in sys.argv:
        print(json.dumps(t, indent=2))
        return
    for name, row in t.items():
        print(f"{row['client_id']:40} {' '.join(row['scopes'])}")
        print(f"{'':40} produce {', '.join(row['produce'])}; consume {', '.join(row['consume'])}")


if __name__ == "__main__":
    main()
