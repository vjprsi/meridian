# Logging and IdP event pipeline (GAP-06)

Last reviewed: 2026-10-07. Covers application logs of the services, Keycloak (IdP) events and how both reach
long-term storage / a SIEM. Audit events (the hash-chained business audit trail) are described in
[`../compliance/README.md`](../compliance/README.md) GAP-07.

## 1. Application logs

| Aspect | Implementation | Evidence |
|---|---|---|
| Format | One JSON object per line on stdout, Elastic Common Schema (Spring Boot structured logging, `logging.structured.format.console=ecs`). Default for every service outside dev security mode; `meridian.logging.format=text` (or `json`) overrides, an explicit `logging.structured.format.console` wins. Helm: `global.logging.format` -> `MERIDIAN_LOGGING_FORMAT`. | `libs/meridian-web` `StructuredLoggingDefaults` (+ test), `META-INF/spring.factories` |
| Service / cell | `service.name` = `spring.application.name`, `service.node.name` = `MERIDIAN_CELL_ID` | same |
| Correlation | `request_id` (`X-Request-Id`, `RequestIdFilter`), `traceId` / `spanId` (Micrometer tracing, OTLP traces), `tenant_id`, `principal`, `calling_service` (`TenantFilter`) in the MDC of every request; cleared after it | `TenantFilterTest.bindsTenantPrincipalAndCallingServiceInTheLogMdcForTheRequestOnly` |
| No PII | In jwt mode `principal` is an opaque id (token subject, `customer:<uuid>`, `svc:<service>`); request / response bodies are never logged by the platform code; names, e-mail addresses, phone numbers, PAN and secrets must never be passed to a logger (code review rule; `pii` columns are listed in `generated/docs/data-dictionary.md`). Dev mode (local, tests) keeps plain text and may show `X-User` values. | CONVENTIONS / review |
| Local | `tools/dev.py` runs in dev mode: readable text logs in `.run/logs/<service>.log` | `tools/dev.py` |

## 2. Shipping (cluster)

```
pod stdout (ECS JSON) -> container runtime log file on the node
   -> node collector (OpenTelemetry Collector filelog receiver, or Promtail / Grafana Alloy) as a DaemonSet
      -> Loki (search, dashboards, LogQL alerts; same Grafana as the GAP-10 metrics)
      -> SIEM export (OTLP / HTTP) for security correlation
```

* The collector parses the JSON line (`json_parser` operator / Promtail `json` stage), keeps `service.name`,
  `log.level`, `tenant_id`, `calling_service`, `request_id` and the trace ids as labels / attributes (only low-cardinality
  ones as Loki labels: service, cell, level), and adds Kubernetes metadata (namespace, pod).
* The collector is the only component that needs cluster permissions; services keep `automountServiceAccountToken: false`.
* Not deployed by this repository yet: the DaemonSet, Loki and the SIEM are platform components outside the chart
  (same status as the Prometheus / Alertmanager stack of GAP-10). `infra/local` ships the `otel-lgtm` container
  (Loki included) for local experiments.

Retention (proposal, to be confirmed by the SecOps owner): application logs 90 days hot in Loki, 1 year in object
storage; security-relevant streams (IdP events, `platform.audit_event.recorded`) per the audit retention policy
(audit-service retention policies + WORM seals, GAP-07).

## 3. Keycloak (IdP) events

| Aspect | Implementation | Evidence |
|---|---|---|
| What is recorded | User events `LOGIN`, `LOGIN_ERROR`, `LOGOUT`, `CLIENT_LOGIN(_ERROR)`, `CODE_TO_TOKEN_ERROR`, `REFRESH_TOKEN_ERROR`, password / credential / TOTP changes, resets, lockouts (`USER_DISABLED_BY_*`), impersonation, token exchange, consents; admin events with representation (`adminEventsDetailsEnabled`) | `tools/keycloak_realm.py` `USER_EVENT_TYPES`, `realm_events()` |
| Listeners | `jboss-logging` (Keycloak's own log, shipped like any container log) and `metrics-listener` (`keycloak_user_events_total`, J4 login SLI; Keycloak started with `--metrics-enabled --event-metrics-user-enabled`) | realm, `infra/local/compose.yaml` |
| Storage in Keycloak | 90 days (`eventsExpiration`) - a buffer, not the archive | realm |
| Into the audit trail | audit-service `KeycloakEventsPoller` polls `GET /admin/realms/{realm}/events` and `/admin-events` with the read-only client `meridian-audit-events` (realm-management `view-events` only: it cannot read users or change the realm). Actions `idp.<type>` / `idp.admin.<operation>.<resource>`, outcome FAILURE for errors, source event ids `kc:<id>` / `kc-admin:<id>` (recorded once), events recorded in the platform-operator tenant, credentials / tokens / login names removed, keys snake_cased for the PII masker. Enabled with Helm `global.auth.idpEvents.enabled` (needs `platformOperatorTenant`). | `KeycloakEventsPollerTest` (fake server + real Keycloak 26.4), `tools/keycloak_mfa_check.py` events checks |
| Why polling | Keycloak 26 ships no Kafka / HTTP event listener; a custom SPI jar would have to be built and installed in every Keycloak image and loses events while the target is down. The realm stores events itself, so polling with an overlapping window and de-duplication is lossless for outages shorter than the 90-day expiry. Latency: one poll interval (default 60 s). | |

From the audit trail the events reach the SIEM with the rest of the audit stream (`platform.audit_event.recorded`).

## 4. Open items

* Deploy the node log collector, Loki and the SIEM connector per cell (platform, outside the chart); log-based alerts
  (e.g. login failure bursts, `insufficient_user_authentication` spikes) once Loki runs.
* Edge gateway and cell router (`platform/`) do not use `libs/meridian-web` and still log plain text; give them the
  same `StructuredLoggingDefaults` when they are next touched.
* A production Keycloak (or SaaS IdP) must be configured with the same event settings; the realm export documents them.
