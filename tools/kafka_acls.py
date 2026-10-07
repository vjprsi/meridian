#!/usr/bin/env python3
"""Generates the per-service Kafka authorisation of a cell (GAP-16): infra/tofu/modules/cell/kafka-acls.json.

Each Meridian service is its own Kafka principal on MSK (SASL AWS_MSK_IAM with the IAM role of its Kubernetes service
account, IRSA). What it may do is derived from the spec and the code (tools/service_access.py):
  produce  only its own domain topic meridian.<domain>.v1 (outbox relay; every event it declares is in that domain;
           cross-checked against generated/asyncapi/meridian.asyncapi.yaml: the topic must be a declared channel)
  consume  the topics of its @KafkaListener annotations (topicPattern meridian\\..*\\.v1 = all domain topics) plus
           meridian.platform.v1 (central approval decisions, libs/meridian-approvals)
  groups   consumer groups whose id starts with the service name (<service>, <service>-approvals, <service>-<purpose>)
Topics are provisioned by the platform (MSK auto-create off); a service may create only its own domain topic.

The JSON is read by infra/tofu/modules/cell/kafka_iam.tf (one IAM role + inline policy per service, trusted by the
cell's EKS OIDC provider for system:serviceaccount:<namespace>:<service>) and lists, for review, the IAM policy
document of each service with ${cluster_arn} / ${topic_arn_prefix} / ${group_arn_prefix} placeholders. Local
development is unchanged (PLAINTEXT Kafka without authorisation).

    python tools/kafka_acls.py            # write the file
    python tools/kafka_acls.py --check    # fail when the committed file is stale (CI)
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import service_access  # noqa: E402

ROOT = service_access.ROOT
OUT = ROOT / "infra" / "tofu" / "modules" / "cell" / "kafka-acls.json"
ASYNCAPI = ROOT / "generated" / "asyncapi" / "meridian.asyncapi.yaml"


def declared_topics() -> set[str]:
    text = ASYNCAPI.read_text(encoding="utf-8")
    return set(re.findall(r"^\s+address:\s*(meridian\.[a-z_]+\.v1)\s*$", text, re.M))


def policy(acl: dict) -> dict:
    """IAM policy document of one service (placeholders filled by tofu or by hand)."""
    statements = [
        {"Sid": "Connect", "Effect": "Allow",
         "Action": ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster", "kafka-cluster:WriteDataIdempotently"],
         "Resource": ["${cluster_arn}"]},
        {"Sid": "ProduceOwnDomainTopic", "Effect": "Allow",
         "Action": ["kafka-cluster:DescribeTopic", "kafka-cluster:WriteData", "kafka-cluster:CreateTopic"],
         "Resource": [f"${{topic_arn_prefix}}/{t}" for t in acl["produce"]]},
    ]
    if acl["consume"]:
        statements.append({"Sid": "ConsumeSubscribedTopics", "Effect": "Allow",
                           "Action": ["kafka-cluster:DescribeTopic", "kafka-cluster:ReadData"],
                           "Resource": [f"${{topic_arn_prefix}}/{t}" for t in acl["consume"]]})
        statements.append({"Sid": "OwnConsumerGroups", "Effect": "Allow",
                           "Action": ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"],
                           "Resource": [f"${{group_arn_prefix}}/{acl['group_prefix']}*"]})
    return {"Version": "2012-10-17", "Statement": statements}


def build() -> dict:
    declared = declared_topics()
    services = {}
    for s in service_access.manifest():
        acl = service_access.kafka_access(s["name"])
        unknown = [t for t in acl["produce"] + acl["consume"] if t not in declared]
        if unknown:
            raise SystemExit(f"{s['name']}: topics {unknown} are not channels of {ASYNCAPI.relative_to(ROOT)}")
        services[s["name"]] = {"produce": acl["produce"], "consume": acl["consume"],
                               "group_prefix": acl["group_prefix"], "policy": policy(acl)}
    return {
        "_generated_by": "tools/kafka_acls.py from generated/services.json, generated/asyncapi and the services' "
                         "@KafkaListener annotations; do not edit",
        "topics": sorted(declared),
        "services": services,
    }


def main():
    text = json.dumps(build(), indent=2) + "\n"
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            raise SystemExit(f"{OUT.relative_to(ROOT)} is stale: run python tools/kafka_acls.py")
        print("kafka acls up to date")
        return
    OUT.write_text(text, encoding="utf-8", newline="\n")
    data = json.loads(text)
    consumers = sum(1 for v in data["services"].values() if len(v["consume"]) > 1)
    print(f"kafka acls: {len(data['services'])} principals, {len(data['topics'])} topics, "
          f"{consumers} with domain-event subscriptions -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
