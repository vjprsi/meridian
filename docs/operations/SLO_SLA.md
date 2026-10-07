# Service level objectives and agreements

Last reviewed: 2026-10-07 · Owner: TBD (SRE lead), with domain owners per journey · Status: **targets defined, not yet
measured in production** - no dashboards or alert rules exist in the repo (GAP-10)

## 1. Telemetry available today

| Source | What exists | Where |
|---|---|---|
| Spring Boot / Micrometer (every service) | `http.server.requests` (tags `method`, `uri` = route template, `status`, `outcome`, `exception`), `hikaricp.connections.*`, `jvm.*`, `process.*`, Spring Kafka listener / client metrics, Spring Boot health groups (liveness / readiness) | `spring-boot-starter-actuator` + `spring-boot-starter-opentelemetry` (`build-logic/.../meridian.spring-service.gradle.kts`); exported via OTLP to `global.otel.endpoint` (`MANAGEMENT_OTLP_METRICS_EXPORT_URL`) with resource attributes `service.name`, `meridian.cell`, `cloud.region` |
| Traces | OTLP traces, sampling `global.otel.samplingProbability` (0.1 in the chart) | `infra/helm/meridian/templates/config.yaml` |
| Cell router | `meridian.router.requests{cell,outcome}`, `meridian.router.upstream{cell,outcome}` (timer), `meridian.router.failover.requests{from_cell,to_cell}`, `meridian.router.cell.healthy{cell}`, `meridian.router.cell.serving{cell}`, `meridian.router.placement.lookups{result}`, `meridian.router.placement.invalidations` | `platform/cell-router` (Prometheus registry on the management port) |
| business-day-service | `meridian.eod.sweep.runs{trigger,status}`, `meridian.eod.sweep.duration{trigger,status}`, `meridian.eod.sweep.skipped{trigger}`, `meridian.eod.trigger.decisions{decision}` | `EodTriggerService` |
| search-service | `meridian.search.indexer.lag` (timer, event time -> indexed), `meridian.search.indexer.last_lag_ms` (gauge), `meridian.search.indexer.events{outcome,kind}` | `EventIndexer` |
| Domain events (business SLIs) | e.g. `payments.payment.*` (incl. `held`, `fraud_degraded`), `operations.eod_run.completed/failed`, `ledger.recon_eod.completed` (auto-match rate), `platform.audit_chain.broken` | AsyncAPI `generated/asyncapi/meridian.asyncapi.yaml` |
| AWS | Aurora (`DatabaseConnections`, `AuroraGlobalDBReplicationLag`, CPU), MSK (`SumOffsetLag`, `MaxOffsetLag`), MSK Replicator `ReplicationLatency`, S3 `ReplicationLatency`, Route 53 health checks; alarm `global_db_lag` in `infra/tofu/modules/region` | CloudWatch |

Known telemetry gaps (GAP-10):

- Domain services have **no Prometheus registry**; the chart's optional `ServiceMonitor` (`/actuator/prometheus`) only
  works for the router. Use the OTel collector's Prometheus exporter / remote write for service metrics, or add
  `micrometer-registry-prometheus` to the service convention plugin.
- No histogram buckets are configured, so p99 latency cannot be computed from `http.server.requests`. Add per
  environment: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` and
  `management.metrics.distribution.slo.http.server.requests=50ms,100ms,250ms,500ms,1s,2s`.
- No business counters (payments by final status, card auth decisions / stand-in, ledger postings); derive from events
  or add Micrometer counters in payment-service, card-authorization-service and ledger-service.
- After OTLP -> Prometheus translation the latency metric is typically `http_server_requests_milliseconds_*` (Micrometer
  OTLP base unit) or `http_server_requests_seconds_*` (Prometheus registry): check the collector and adapt the queries
  below.

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

## 4. Alert rules (to implement)

Multi-window burn-rate alerts (Google SRE workbook) for every availability SLO, using the metric names above
(Prometheus syntax after OTLP translation; adapt units):

```yaml
# J1 payment initiation: page when burning > 14.4x budget over 1 h (and 5 m), or > 6x over 6 h (and 30 m)
- alert: PaymentInitiationErrorBudgetBurnFast
  expr: |
    (sum(rate(http_server_requests_milliseconds_count{service_name="payment-service",uri="/v1/payments",method="POST",status=~"5.."}[1h]))
      / sum(rate(http_server_requests_milliseconds_count{service_name="payment-service",uri="/v1/payments",method="POST"}[1h]))) > (14.4 * 0.0005)
    and
    (sum(rate(http_server_requests_milliseconds_count{service_name="payment-service",uri="/v1/payments",method="POST",status=~"5.."}[5m]))
      / sum(rate(http_server_requests_milliseconds_count{service_name="payment-service",uri="/v1/payments",method="POST"}[5m]))) > (14.4 * 0.0005)
  labels: {severity: page, journey: J1}
# J2 card auth latency (needs histogram buckets, section 1)
- alert: CardAuthP99High
  expr: histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{service_name="card-authorization-service",uri="/v1/card-authorizations"}[5m]))) > 250
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
| Reporting | Monthly SLO report per tenant from the monitoring stack (GAP-10) |

## Open gaps

| Gap | Summary | Owner | Backlog |
|---|---|---|---|
| GAP-10 | Prometheus registry / collector export, histogram buckets, dashboards, alert rules, paging | TBD (SRE) | `spec/plan.yaml` PLT-OBSERVABILITY |
| - | Business counters (payment outcomes, card auth decisions and stand-in, ledger postings) | TBD (Payments / Cards / Ledger) | Proposed |
| GAP-06 | Logs for SLO investigations (no central logging) | TBD (SecOps) | Proposed |
| - | 10,000 TPS per cell load test (S22 exit) | TBD (Performance) | `spec/plan.yaml` S22; [PERFORMANCE.md](../PERFORMANCE.md) |
| GAP-12 | Connection budget limits horizontal scaling | TBD (Platform) | [SPEC_BACKLOG connection budget](../SPEC_BACKLOG.md#connection-budget) |
| - | IdP (login) SLIs depend on the chosen IdP deployment | TBD (IAM) | Proposed |
