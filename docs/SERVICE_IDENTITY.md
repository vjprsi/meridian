# Service identities and Kafka authorisation (GAP-16)

Last reviewed: 2026-10-07. How a Meridian service proves who it is to other services (OAuth) and to Kafka (MSK IAM),
and how the permissions are derived. Staff MFA / access reviews (GAP-13) are in
[SERVICE_IMPLEMENTATION_GUIDE.md](SERVICE_IMPLEMENTATION_GUIDE.md#service-identity-jwt-mode).

## 1. OAuth: one client per service

| | Before | Now |
|---|---|---|
| Client | one shared `meridian-service` (every staff scope, `meridian:internal`, `platform:operator`) | `svc-<service>` per service, confidential, client credentials only |
| Scopes | everything | `meridian:internal` + `<domain>:write` of every service it calls + special scopes its call paths need (`platform:operator` for e.g. data-lifecycle callers and tenant registry readers); never `meridian:admin` or a channel scope |
| Calling service seen by the receiver | `X-Meridian-Service` header, asserted by the caller | the token's `azp` (`svc-deposit-service` -> `deposit-service`); the header only marks "acting as myself" (`ServiceClient.asService`) |
| Secret | one Secret `meridian-service-client` in every pod | Secret `meridian-<service>-client` per service (Secrets Manager entry per service, `infra/tofu` cell output `service_client_secret_arns`, synced by External Secrets) |

Derivation (`tools/service_access.py`, used by `tools/keycloak_realm.py`): a called service is any Meridian service
name that appears as a literal in the caller's main sources or `application.yml` (ServiceClient calls name their
target; constants like `VAULT = "card-vault-service"` count), plus the services every service calls through the
libraries (workflow-service, config-service, business-day-service). The granularity is the domain, not the operation,
because many paths are built at run time; special (non read/write) scopes are granted only when one of the caller's
`/v1/...` path literals matches an operation of a called service that requires it. `python tools/service_access.py`
prints the table.

Receivers (`libs/meridian-web` `TenantFilter`):
* internal token of `svc-<name>`: `TenantContext.via()` = `<name>`; with `X-Meridian-Service` present
  `callingService()` = `<name>` whatever the header says (a mismatch is logged); principal = `X-User` or `svc:<name>`;
* internal token of a legacy shared client listed in `meridian.security.legacy-service-clients`: the header's value
  (migration only); any other client: no calling service;
* dev mode: the header, as before.

So workflow-service's approval owner registry, ledger-service's account-owner check, card-vault's caller in the access
log, payment-service's trusted callers and the data-lifecycle caller allow-list all rest on the token
(`ApprovalOwnersServiceIdentityTest`, `TenantFilterTest`). card-vault-service additionally accepts only the console
and the `svc-*` clients of its callers (`MERIDIAN_CARD_VAULT_ALLOWED_CLIENTS`, rendered by `tools/helm_chart.py`).

### Migration (one release window)

1. Provision the `svc-*` clients (realm export or IdP automation) and the per-service Secrets.
2. Deploy with `global.auth.sharedServiceClient=true` and `global.auth.legacyServiceClients=[meridian-service]`
   (old behaviour, new code) and add `meridian-service` to card-vault's allow-list for the window.
3. Switch `sharedServiceClient=false` (each pod uses its own client) while `legacyServiceClients` still lists the
   shared client (pods of the previous release keep working during the rollout).
4. Set `legacyServiceClients=[]`, remove `meridian-service` from card-vault's list, then delete the client in the IdP.

## 2. Kafka: one MSK principal per service

`tools/kafka_acls.py` writes `infra/tofu/modules/cell/kafka-acls.json` (checked in CI with `--check`):

| Permission | Rule | Source |
|---|---|---|
| Produce | only `meridian.<domain>.v1` of the service's own domain (outbox relay; every event it declares is in that domain) | `generated/services.json`, AsyncAPI channels |
| Create topic | only its own domain topic (MSK auto-create stays off; normally provisioned beforehand) | |
| Consume | topics of its `@KafkaListener` annotations (`topicPattern meridian\..*\.v1` = all domain topics: audit, notification, search, webhook) + `meridian.platform.v1` (approval decisions) | service code, `libs/meridian-approvals` |
| Consumer groups | ids starting with the service name | listener `groupId`s |

`infra/tofu/modules/cell/kafka_iam.tf` turns each entry into an IAM role trusted by the cell's EKS OIDC provider for
`system:serviceaccount:<k8s_namespace>:<service>` with an inline policy on the MSK cluster, topic and group ARNs
(`kafka_iam_enabled`, default true; output `kafka_service_role_arns`). The Helm chart (`global.kafka.iam.enabled`,
`roleArnTemplate` with `{name}`) annotates each service account with its role and sets `SASL_SSL` /
`AWS_MSK_IAM` (`aws-msk-iam-auth` is on every service's runtime classpath, inactive otherwise). Result: a compromised
service can neither publish another domain's events (forging, e.g., `ledger.*` postings) nor read topics it does not
subscribe to.

Local development is unchanged: PLAINTEXT Kafka without authorisation, dev security mode (or, with
`MERIDIAN_SECURITY_MODE=jwt`, `tools/dev.py` uses the local `svc-<name>` clients).

## 3. Residuals

* Domain-level scope granularity; operation-level scopes would need call-site metadata.
* Topics that several services of one domain share (e.g. all `cards` services produce `meridian.cards.v1`) cannot be
  split further without per-service topics; event-type checks on the consumer side remain (`ce_source`).
* The production IdP configuration (clients, secrets rotation) is an operational process outside the repository.
