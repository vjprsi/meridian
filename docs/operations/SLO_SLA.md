# Service level objectives and agreements

Last reviewed: 2026-10-07 · Owner: TBD (SRE lead), with domain owners per journey · Status: **targets defined and
instrumented; dashboards and alert rules in the repo (`infra/observability`); not yet measured in production** - no
Prometheus / Alertmanager is deployed and paging is not wired (GAP-10 residual)

## 1. Telemetry available today

| Source | What exists | Where |
|---|---|---|
| Spring Boot / Micrometer (every service, edge gateway, cell router) | `http.server.requests` (tags `method`, `uri` = route template, `status`, `outcome`, `exception`, `cell`) **with percentile histograms and SLO buckets** 25 ms ... 5 s, `hikaricp.connections.*` (acquire / usage histograms), `jvm.*`, `process.*`, Spring Kafka listener / client metrics (`kafka_consumer_fetch_manager_records_lag_max`), health groups | `micrometer-registry-prometheus` in the build-logic conventions (`meridian.spring-service`, `meridian.boot-app`): `GET /actuator/prometheus` on the service port (no token; NetworkPolicy limits callers), on the **management port** for the edge gateway (18001 locally, 9090 in Helm) and the router (18101). Defaults in `libs/meridian-web/.../metrics/MetricsDefaults.java` (lowest precedence). Also exported via OTLP (`MANAGEMENT_OTLP_METRICS_EXPORT_URL`) with **base unit seconds**, so both pipelines produce the same names (`http_server_requests_seconds_*`; verified against the otel-lgtm Prometheus); `job` = service name in both |
| Traces | OTLP traces, sampling `global.otel.samplingProbability` (0.1 in the chart) | `infra/helm/meridian/templates/config.yaml` |
| Cell router | `meridian.router.requests{cell,outcome}`, `meridian.router.upstream{cell,outcome}` (timer), `meridian.router.failover.requests{from_cell,to_cell}`, `meridian.router.cell.healthy{cell}`, `meridian.router.cell.serving{cell}`, `meridian.router.placement.lookups{result}`, `meridian.router.placement.invalidations` | `platform/cell-router` (Prometheus registry on the management port) |
| business-day-service | `meridian.eod.sweep.runs{trigger,status}`, `meridian.eod.sweep.duration{trigger,status}`, `meridian.eod.sweep.skipped{trigger}`, `meridian.eod.trigger.decisions{decision}` | `EodTriggerService` |
| search-service | `meridian.search.indexer.lag` (timer, event time -> indexed), `meridian.search.indexer.last_lag_ms` (gauge), `meridian.search.indexer.events{outcome,kind}` | `EventIndexer` |
| payment-service (J1) | `meridian.payments.initiations{outcome=accepted\|failed\|rejected\|error, status, reason, path}` (counter), `meridian.payments.initiation.duration{outcome}` (timer, histogram) | `PaymentProcessor.initiate` |
| ledger-service (J3) | `meridian.ledger.posting.duration{path=single\|batch, outcome=posted\|replayed\|rejected\|error}` (timer incl. commit, histogram) | `JournalEntriesController` |
| card-authorization-service (J2) | `meridian.cards.authorization.duration{message_type, decision, stand_in}` (timer, histogram) | `CardAuthorizationsController` |
| business-day-service (J5) | `meridian.eod.sweep.last.success.timestamp` (gauge, epoch seconds); sweep timer now with histogram and SLO buckets 1 min ... 8 h | `EodTriggerService` |
| channels (J4) | `meridian.auth.step_up.challenges{channel, reason=stale_auth\|weak_auth\|device_proof_invalid}` | `StepUp` in the four channel BFFs |
| identity-service (J4) | `meridian.identity.mfa.verifications{outcome=verified\|invalid_code\|factor_locked\|...}` | `MfaService.verify` |
| workflow-service | `meridian.workflow.approvals.pending`, `meridian.workflow.approvals.oldest.age` (seconds; all tenants, refreshed by the sweep) | `SweepService.Job` |
| every service with an outbox | `meridian.outbox.backlog`, `meridian.outbox.oldest.age` (read at scrape time), `meridian.outbox.relayed`, `meridian.outbox.relay.failures` | `libs/meridian-events` `OutboxRelay` |
| Keycloak (J4, local stack) | `keycloak_user_events_total{event, error}` with `--metrics-enabled --event-metrics-user-enabled` (management port 9000) | `infra/local/compose.yaml`; the realm also needs the `metrics-listener` event listener (not yet in `tools/keycloak_realm.py`) |
| Domain events (business SLIs) | e.g. `payments.payment.*` (incl. `held`, `fraud_degraded`), `operations.eod_run.completed/failed`, `ledger.recon_eod.completed` (auto-match rate), `platform.audit_chain.broken` | AsyncAPI `generated/asyncapi/meridian.asyncapi.yaml` |
| AWS | Aurora (`DatabaseConnections`, `AuroraGlobalDBReplicationLag`, CPU), MSK (`SumOffsetLag`, `MaxOffsetLag`), MSK Replicator `ReplicationLatency`, S3 `ReplicationLatency`, Route 53 health checks; alarm `global_db_lag` in `infra/tofu/modules/region` | CloudWatch |

Remaining telemetry gaps (GAP-10 residual):

- Nothing scrapes in a real environment yet: Prometheus (or Amazon Managed Prometheus), Alertmanager and the OTel
  collector are not deployed by `infra/tofu` or the chart; the chart only ships `ServiceMonitor`s
  (`global.serviceMonitor.enabled`), `PrometheusRule`s and dashboard ConfigMaps (`observability.*`), all off by default.
- J1b (payment completion within 60 s) and J8 (reconciliation auto-match) are still event-derived; no metric yet.
- The `cell` label is the metric tag `cell` (`meridian.cell.id`, `local` when unset); "deliberate 503" answers
  (`tenant_read_only`, `cell_read_only`) cannot be told apart from other 503s by the HTTP metric and count as bad.
- Bucket bounds differ in text form between the pipelines (`le="1.0"` from the Prometheus registry, `le="1"` from OTLP
  translation); the generated rules match both. `_max` gauges differ too (`_seconds_max` vs `_max_seconds`).

## 2. Critical journeys, SLIs and SLOs

Window: rolling 30 days, per cell and per tenant tier. "Good" excludes 4xx (client errors) and 503 answers that are
deliberate (`tenant_read_only`, `cell_read_only` during announced maintenance).

| # | Journey | SLI (good / valid) | SLO | Error budget (30 d) | Measurement |
|---|---|---|---|---|---|
| J1 | **Payment initiation** (`POST /v1/payments`, `/v1/me/payments`, `/v1/business/payments/{id}/submit`) | Availability: non-5xx / all; Latency: requests < 1 s (synchronous acceptance incl. sanctions + fraud scoring budget 2 s) | 99.95 % available; 99 % < 1 s | 21.6 min of 5xx-equivalent | `http.server.requests{service.name="payment-service",uri="/v1/payments",method="POST"}` and the channel equivalents |
| J1b | Payment completion (internal / on-us) | Payments reaching a final status within 60 s of acceptance (excluding approvals, scheduled dates, fraud holds) | 99.5 % | 0.5 % | Event pairs `payments.payment.*` (accepted -> completed) per payment id |
| J2 | **Card authorisation** (`POST /v1/card-authorizations`, card-authorization-service) | Latency: p99 at the service; Availability: decision returned (approve / decline incl. stand-in) / requests | p99 < 250 ms; 99.99 % answered | 4.3 min | `http.server.requests{service.name="card-authorization-service",uri="/v1/card-authorizations"}`; stand-in rate from `card_stand_in_record` (no metric yet) |
| J3 | **Ledger posting** (`POST /v1/journal-entries`) | Availability; latency < 250 ms | 99.99 %; 99 % < 250 ms (benchmark p99 117.9 ms at 1,792 postings/s, `docs/PERFORMANCE.md`) | 4.3 min | `http.server.requests{service.name="ledger-service",uri="/v1/journal-entries",method="POST"}` |
| J4 | **Login** (staff, customer, agent, business, merchant via the IdP) | Successful token issuance / login attempts with valid credentials; IdP availability | 99.95 % | 21.6 min | IdP metrics (Keycloak metrics endpoint / SaaS IdP) - not in this repo; `platform.app_user.logged_in` events for staff sessions |
| J5 | **EOD completion** | EOD runs (per legal entity) completed successfully before the deadline (cut-off + 4 h, tenant-configurable) | 99 % of business days per tenant | 1 late / failed EOD per ~100 days | `operations.eod_run.completed` vs `.failed`, `meridian.eod.sweep.runs{status}`, `meridian.eod.sweep.duration` |
| J6 | API front door (router + gateway) | Router outcomes not upstream-error / all | 99.95 % | 21.6 min | `meridian.router.requests{outcome}`; `meridian.router.cell.serving` |
| J7 | Event freshness (supporting) | audit consumer lag < 5 min; search indexer lag < 60 s | 99 % of minutes | - | MSK `MaxOffsetLag` for group `audit-service`; `meridian.search.indexer.last_lag_ms` |
| J8 | Reconciliation quality (supporting) | Daily auto-match rate per source | >= 98 % | - | `ledger.recon_eod.completed`, `GET /v1/reconciliation/runs/metrics` |

## 3. Error budget policy

- Budget remaining > 50 %: normal release cadence.
- 25-50 %: releases of the affected services need SRE sign-off; prioritise reliability items.
- < 25 %: feature freeze for the affected services (security fixes excepted) until the budget recovers or a
  post-incident review accepts the risk.
- A single SEV1 consuming > 50 % of a budget triggers a mandatory review ([RUNBOOKS.md section 12](RUNBOOKS.md#12-post-incident-review)).

## 4. Alert rules and dashboards (implemented as code)

Generated by `python tools/observability.py` from this document's SLOs into `infra/observability` (checked in CI with
`--check` and `promtool check rules`; edit the generator, not the output):

| Artefact | Content |
|---|---|
| `prometheus/rules/meridian-slo.rules.yml` | Recording rules `meridian:slo_errors:ratio_rate{5m,30m,1h,2h,6h,1d,3d}{journey,sli,cell}` for J1 / J2 / J3 availability and latency (valid = non-4xx) and J6 (router `upstream_timeout` / `upstream_unreachable`); per SLI four multi-window burn-rate alerts: 14.4x 1h/5m and 6x 6h/30m (page), 3x 1d/2h and 1x 3d/6h (ticket) |
| `prometheus/rules/meridian-journeys.rules.yml` | J2 p99 > 250 ms (page), stand-in > 5 %, J1 initiation server errors > 1 %, J5 sweep failed (page) / no successful sweep for 26 h (page), J4 step-up spike (5x yesterday), MFA failures, IdP login failure ratio, J6 cell unhealthy (page), J7 search lag, approval older than 24 h |
| `prometheus/rules/meridian-saturation.rules.yml` | Target down, 5xx ratio per service, DB pool pending (page) / timeouts (page) / > 90 % used, Kafka consumer lag > 10k, audit consumer lag > 50k for 1 h (page), outbox events older than 60 s (ticket) / 300 s (page), relay failures, JVM heap > 90 % |
| `grafana/dashboards/*.json` | Platform overview (RED + JVM, `cell` / `job` variables), Journey SLOs (SLI, budget left, burn rates; J4 login / step-up / MFA; J5 / J7 freshness), Payments, Ledger, Card authorisation, EOD and operations (incl. approval queue), Kafka and outbox, Database pools |

Wiring: locally `infra/local/compose.yaml` mounts the dashboards (Grafana folder "Meridian", http://localhost:13000)
and rules (Prometheus UI http://localhost:19090) into the otel-lgtm container (`infra/observability/local/`); in
Kubernetes set `observability.prometheusRule.enabled` (plus `observability.prometheusRule.labels` matching the
Prometheus `ruleSelector`) and `observability.dashboards.enabled` (Grafana sidecar label `grafana_dashboard: "1"`).
Severity `page` / `ticket` labels and a `runbook` annotation are on every alert; Alertmanager routes are still to be
defined (GAP-10 residual).

The burn-rate pattern used for every SLI (example J1 availability, budget 0.05 %):

```yaml
- record: meridian:slo_errors:ratio_rate1h
  labels: {journey: J1, sli: availability}
  expr: |
    sum by (cell) (rate(http_server_requests_seconds_count{job=~"payment-service|customer-channel-service|business-channel-service",
      uri=~"/v1/payments|/v1/me/payments|/v1/business/payments/.+/submit",method="POST",status=~"5.."}[1h]))
    / sum by (cell) (rate(http_server_requests_seconds_count{...same selector...,status!~"4.."}[1h]))
- alert: J1PaymentInitiationAvailabilityBurnFast
  expr: |
    meridian:slo_errors:ratio_rate1h{journey="J1",sli="availability"} > (14.4 * 0.0005)
    and meridian:slo_errors:ratio_rate5m{journey="J1",sli="availability"} > (14.4 * 0.0005)
  for: 2m
  labels: {severity: page, journey: J1, sli: availability}
- alert: J2CardAuthP99High
  expr: histogram_quantile(0.99, sum by (le, cell) (rate(http_server_requests_seconds_bucket{job="card-authorization-service",uri="/v1/card-authorizations"}[5m]))) > 0.25
  for: 5m
  labels: {severity: page, journey: J2}
```

| Alert | Condition | Severity | Runbook |
|---|---|---|---|
| Budget burn fast (J1, J2, J3, J6) | 14.4x over 1 h and 5 m | page (SEV2, SEV1 if J2 / J3) | service runbook |
| Budget burn slow | 6x over 6 h and 30 m | page | service runbook |
| Budget burn drift | 1x over 3 d | ticket | - |
| Card auth p99 > 250 ms for 5 m | histogram | page | [RUNBOOKS section 11](RUNBOOKS.md#11-card-authorisation-degradation) |
| Fraud degraded payments | rate of `payments.payment.fraud_degraded` events > 0 for 5 m | page | [RUNBOOKS section 5](RUNBOOKS.md#5-stuck-payments) |
| EOD failed / late | `operations.eod_run.failed` or no `.completed` by deadline; `meridian.eod.sweep.runs{status!="COMPLETED"}` | page (SEV2) | [RUNBOOKS section 4](RUNBOOKS.md#4-end-of-day-eod-failure-handling) |
| Audit consumer lag | `MaxOffsetLag{group="audit-service"}` > 50k or > 15 min | ticket, page if > 1 h | [RUNBOOKS section 7](RUNBOOKS.md#7-kafka-consumer-lag-audit-catch-up) |
| Audit chain broken / ledger hash mismatch | `platform.audit_chain.broken` event; `ledger_verification.hash_mismatches > 0` | page (SEV1) | [RUNBOOKS section 10](RUNBOOKS.md#10-audit-chain-broken--ledger-integrity-mismatch) |
| DB pool saturation | `hikaricp_connections_pending > 0` for 5 m, or `hikaricp_connections_timeout_total` increasing | page | [RUNBOOKS section 8](RUNBOOKS.md#8-database-connection-exhaustion) |
| Cell unhealthy | `meridian_router_cell_healthy == 0` for 30 s | page (SEV1) | [MULTI_REGION section 5](../MULTI_REGION.md#5-failover-runbook-game-day) |
| Replication lag | `AuroraGlobalDBReplicationLag` > 1 s (tofu alarm exists), MSK Replicator latency > 5 s | page | [BCP_DR.md](BCP_DR.md) |
| Search lag | `meridian_search_indexer_last_lag_ms` > 60000 for 10 m | ticket | - |
| Recon auto-match | daily rate < 98 % for a source | ticket (finance) | [RUNBOOKS section 6](RUNBOOKS.md#6-reconciliation-breaks) |
| Denied object access spike | `channels.*_action.denied` events per tenant > baseline x 10 | security ticket (possible BOLA probing) | [THREAT_MODEL B2](../compliance/THREAT_MODEL.md#b2-channel-bffs-customer-agent-business-merchant) |

## 5. SLA (contractual, tenant banks) - draft

| Item | Target (placeholder) |
|---|---|
| Monthly availability of core APIs (J1, J3, J6) per cell | 99.95 % (SHARED), 99.99 % (DEDICATED with region pair) |
| Card authorisation availability | 99.99 % (stand-in counts as available) |
| EOD completion by deadline | 99 % of business days |
| Support response | SEV1 15 min, SEV2 1 h, SEV3 next business day |
| RTO / RPO | See [BCP_DR.md](BCP_DR.md) |
| Exclusions | Announced maintenance (READ_ONLY windows, tenant moves), tenant-side IdP / network, third-party rails and schemes, force majeure |
| Service credits | TBD (commercial) |
| Reporting | Monthly SLO report per tenant from the monitoring stack (dashboards exist; stack not deployed, GAP-10 residual) |

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-10 | Residual: deploy Prometheus / Alertmanager / collector per cell, Alertmanager routes and paging, on-call; J1b / J8 metrics; Keycloak realm `metrics-listener` | TBD (SRE) | `spec/plan.yaml` PLT-OBSERVABILITY (In progress) |
| GAP-06 | Logs for SLO investigations (no central logging) | TBD (SecOps) | Proposed |
| - | 10,000 TPS per cell load test (S22 exit) | TBD (Performance) | `spec/plan.yaml` S22; [PERFORMANCE.md](../PERFORMANCE.md) |
| GAP-12 | Connection budget limits horizontal scaling | TBD (Platform) | [SPEC_BACKLOG connection budget](../SPEC_BACKLOG.md#connection-budget) |
| - | IdP (login) SLIs depend on the chosen IdP deployment | TBD (IAM) | Proposed |
