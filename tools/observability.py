#!/usr/bin/env python3
"""Generates the Meridian monitoring pack (GAP-10): Grafana dashboards and Prometheus alert / recording rules.

    python tools/observability.py           # write infra/observability/{grafana/dashboards,prometheus/rules}
    python tools/observability.py --check   # validate what is on disk (JSON / YAML syntax, PromQL sanity,
                                            # unique uids and alert names, generated files up to date;
                                            # runs `promtool check rules` when promtool is on the PATH)

Sources of truth: the SLIs / SLOs and alert table of docs/operations/SLO_SLA.md and the metric names the services
export (Micrometer Prometheus registry; OTLP export uses the same names because MetricsDefaults sets
base-time-unit=seconds). The `job` label is the service name in both pipelines (ServiceMonitor: the Kubernetes
Service; OTLP: service.name). tools/helm_chart.py packages the same files as PrometheusRule / dashboard ConfigMaps;
infra/local/compose.yaml mounts them into the otel-lgtm container.
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "infra" / "observability"
DASHBOARDS = OUT / "grafana" / "dashboards"
RULES = OUT / "prometheus" / "rules"
RUNBOOKS = "docs/operations/RUNBOOKS.md"

# ---------------------------------------------------------------------------------------------------- SLO catalogue
# Availability: bad = 5xx, valid = everything except 4xx (SLO_SLA.md section 2: client errors are not counted).
# Latency: bad = valid non-5xx requests slower than the threshold (needs the SLO buckets of MetricsDefaults).
HTTP = "http_server_requests_seconds"
WINDOWS = ["5m", "30m", "1h", "2h", "6h", "1d", "3d"]
BURN = [  # (alert suffix, long window, short window, factor, severity) - Google SRE workbook, multiwindow
    ("BurnFast", "1h", "5m", 14.4, "page"),
    ("BurnSlow", "6h", "30m", 6, "page"),
    ("BurnDrift", "1d", "2h", 3, "ticket"),
    ("BurnDriftSlow", "3d", "6h", 1, "ticket"),
]

JOURNEYS = [
    {"id": "J1", "name": "PaymentInitiation", "title": "Payment initiation",
     "selector": 'job=~"payment-service|customer-channel-service|business-channel-service",'
                 'uri=~"/v1/payments|/v1/me/payments|/v1/business/payments/.+/submit",method="POST"',
     "availability": 99.95, "latency": (1.0, 99.0), "runbook": "#5-stuck-payments"},
    {"id": "J2", "name": "CardAuthorization", "title": "Card authorisation",
     "selector": 'job="card-authorization-service",uri="/v1/card-authorizations",method="POST"',
     "availability": 99.99, "latency": (0.25, 99.0), "runbook": "#11-card-authorisation-degradation"},
    {"id": "J3", "name": "LedgerPosting", "title": "Ledger posting",
     "selector": 'job="ledger-service",uri="/v1/journal-entries",method="POST"',
     "availability": 99.99, "latency": (0.25, 99.0), "runbook": "#8-database-connection-exhaustion"},
]
# J6: router outcomes that are the platform's fault (not tenant freezes or client errors)
ROUTER_BAD = 'outcome=~"upstream_timeout|upstream_unreachable"'


def le(seconds):
    """le matcher for a bucket bound: the Prometheus registry writes "1.0", OTLP translation writes "1"."""
    f = float(seconds)
    return f'le=~"{int(f)}|{f!r}"' if f.is_integer() else f'le="{f!r}"'


def sli_exprs(j, sli, w):
    s = j["selector"]
    if sli == "availability":
        return (f'sum by (cell) (rate({HTTP}_count{{{s},status=~"5.."}}[{w}]))\n'
                f'/\nsum by (cell) (rate({HTTP}_count{{{s},status!~"4.."}}[{w}]))')
    threshold = le(j["latency"][0])
    return (f'1 - (\n  sum by (cell) (rate({HTTP}_bucket{{{s},status!~"[45]..",{threshold}}}[{w}]))\n'
            f'  /\n  sum by (cell) (rate({HTTP}_count{{{s},status!~"[45].."}}[{w}]))\n)')


def router_expr(w):
    return (f'sum by (cell) (rate(meridian_router_requests_total{{{ROUTER_BAD}}}[{w}]))\n'
            f'/\nsum by (cell) (rate(meridian_router_requests_total[{w}]))')


def slis():
    """(journey id, journey name, title, sli, budget, runbook, expr(window))"""
    out = []
    for j in JOURNEYS:
        out.append((j["id"], j["name"], j["title"], "availability", round(1 - j["availability"] / 100, 6), j["runbook"],
                    lambda w, j=j: sli_exprs(j, "availability", w)))
        out.append((j["id"], j["name"], j["title"], "latency", round(1 - j["latency"][1] / 100, 6), j["runbook"],
                    lambda w, j=j: sli_exprs(j, "latency", w)))
    out.append(("J6", "ApiFrontDoor", "API front door (cell router)", "availability", 0.0005, "#3-deployment-rollback",
                router_expr))
    return out


def slo_rules():
    recording = []
    for jid, _, _, sli, _, _, expr in slis():
        for w in WINDOWS:
            recording.append({"record": f"meridian:slo_errors:ratio_rate{w}", "expr": expr(w),
                              "labels": {"journey": jid, "sli": sli}})
    alerts = []
    for jid, name, title, sli, budget, runbook, _ in slis():
        sel = f'{{journey="{jid}",sli="{sli}"}}'
        for suffix, long_w, short_w, factor, severity in BURN:
            threshold = f"({factor} * {budget})"
            alerts.append({
                "alert": f"{jid}{name}{sli.capitalize()}{suffix}",
                "expr": (f"meridian:slo_errors:ratio_rate{long_w}{sel} > {threshold}\n"
                         f"and\nmeridian:slo_errors:ratio_rate{short_w}{sel} > {threshold}"),
                "for": "2m" if severity == "page" else "15m",
                "labels": {"severity": severity, "journey": jid, "sli": sli},
                "annotations": {
                    "summary": f"{title} {sli} error budget burning {factor}x ({long_w}/{short_w}) in cell {{{{ $labels.cell }}}}",
                    "description": f"{jid} {sli} SLO ({'budget ' + str(budget)}): error ratio over {long_w} is "
                                   f"{{{{ $value | humanizePercentage }}}}. See docs/operations/SLO_SLA.md.",
                    "runbook": RUNBOOKS + runbook}})
    return {"groups": [
        {"name": "meridian-slo-recording", "interval": "30s", "rules": recording},
        {"name": "meridian-slo-burn-rate", "rules": alerts},
    ]}


def alert(name, expr, severity, summary, runbook=None, for_="5m", **labels):
    a = {"alert": name, "expr": expr, "for": for_, "labels": {"severity": severity, **labels},
         "annotations": {"summary": summary}}
    if runbook:
        a["annotations"]["runbook"] = RUNBOOKS + runbook if runbook.startswith("#") else runbook
    return a


def journey_rules():
    card = 'job="card-authorization-service",uri="/v1/card-authorizations"'
    return {"groups": [{"name": "meridian-journeys", "rules": [
        alert("J2CardAuthP99High",
              f'histogram_quantile(0.99, sum by (le, cell) (rate({HTTP}_bucket{{{card}}}[5m]))) > 0.25',
              "page", "Card authorisation p99 above 250 ms in cell {{ $labels.cell }}",
              "#11-card-authorisation-degradation", journey="J2"),
        alert("J2CardAuthStandInHigh",
              'sum by (cell) (rate(meridian_cards_authorization_duration_seconds_count{stand_in="true"}[10m]))\n'
              '/\nsum by (cell) (rate(meridian_cards_authorization_duration_seconds_count[10m])) > 0.05',
              "ticket", "More than 5 % of card authorisations decided in stand-in (ledger unavailable?)",
              "#11-card-authorisation-degradation", for_="10m", journey="J2"),
        alert("J1PaymentInitiationErrors",
              'sum by (cell) (rate(meridian_payments_initiations_total{outcome="error"}[10m]))\n'
              '/\nsum by (cell) (rate(meridian_payments_initiations_total[10m])) > 0.01',
              "ticket", "More than 1 % of payment initiations end in a server error", "#5-stuck-payments",
              for_="10m", journey="J1"),
        alert("J5EodSweepFailed", 'sum by (cell) (increase(meridian_eod_sweep_runs_total{status!="SUCCEEDED"}[30m])) > 0',
              "page", "An EOD sweep finished {{ $labels.status }} in cell {{ $labels.cell }}",
              "#4-end-of-day-eod-failure-handling", for_="0m", journey="J5"),
        alert("J5EodLate",
              'time() - max by (cell) (max_over_time(meridian_eod_sweep_last_success_timestamp_seconds[3d])) > 26 * 3600',
              "page", "No successful EOD sweep for more than 26 h in cell {{ $labels.cell }}",
              "#4-end-of-day-eod-failure-handling", for_="15m", journey="J5"),
        alert("J4StepUpChallengeSpike",
              'sum by (channel) (rate(meridian_auth_step_up_challenges_total[10m])) > 0.2\n'
              'and\nsum by (channel) (rate(meridian_auth_step_up_challenges_total[10m]))\n'
              '  > 5 * sum by (channel) (rate(meridian_auth_step_up_challenges_total[1d] offset 1d))',
              "ticket", "Step-up challenges on the {{ $labels.channel }} channel at 5x yesterday's rate",
              "../compliance/THREAT_MODEL.md", for_="15m", journey="J4"),
        alert("J4MfaVerificationFailures",
              'sum(rate(meridian_identity_mfa_verifications_total{outcome=~"invalid_code|factor_locked"}[10m])) > 0.5',
              "ticket", "MFA verification failures above 0.5/s (code guessing or a broken factor)",
              "../compliance/THREAT_MODEL.md", for_="10m", journey="J4"),
        alert("J4LoginFailureRatioHigh",
              'sum(rate(keycloak_user_events_total{event="login",error!=""}[10m]))\n'
              '/\nsum(rate(keycloak_user_events_total{event="login"}[10m])) > 0.3\n'
              'and\nsum(rate(keycloak_user_events_total{event="login"}[10m])) > 0.1',
              "ticket", "More than 30 % of IdP logins fail (credential stuffing or IdP fault)",
              "../compliance/THREAT_MODEL.md", for_="10m", journey="J4"),
        alert("J6CellUnhealthy", "meridian_router_cell_healthy == 0", "page",
              "Cell {{ $labels.cell }} is unhealthy for the router", "../MULTI_REGION.md#5-failover-runbook-game-day",
              for_="30s", journey="J6"),
        alert("J7SearchIndexerLag", "max by (cell) (meridian_search_indexer_last_lag_ms) > 60000", "ticket",
              "Search indexer more than 60 s behind", None, for_="10m", journey="J7"),
        alert("WorkflowApprovalQueueStale", "max by (cell) (meridian_workflow_approvals_oldest_age_seconds) > 86400",
              "ticket", "An approval request has been PENDING for more than 24 h", None, for_="30m"),
    ]}]}


def saturation_rules():
    return {"groups": [{"name": "meridian-saturation", "rules": [
        alert("MeridianTargetDown", 'up{job=~".+-service|edge-gateway|cell-router|data-mover"} == 0', "page",
              "{{ $labels.job }} {{ $labels.instance }} cannot be scraped", "#3-deployment-rollback"),
        alert("MeridianHttp5xxHigh",
              f'sum by (job, cell) (rate({HTTP}_count{{status=~"5.."}}[5m]))\n'
              f'/\nsum by (job, cell) (rate({HTTP}_count[5m])) > 0.05',
              "ticket", "{{ $labels.job }} answers more than 5 % 5xx", None, for_="10m"),
        alert("MeridianDbPoolPending", "max by (job, instance) (hikaricp_connections_pending) > 0", "page",
              "{{ $labels.job }} threads are waiting for a database connection", "#8-database-connection-exhaustion"),
        alert("MeridianDbPoolTimeouts", "sum by (job) (increase(hikaricp_connections_timeout_total[5m])) > 0", "page",
              "{{ $labels.job }} timed out acquiring database connections", "#8-database-connection-exhaustion",
              for_="0m"),
        alert("MeridianDbPoolNearlyExhausted",
              "max by (job, instance) (hikaricp_connections_active / hikaricp_connections_max) > 0.9", "ticket",
              "{{ $labels.job }} uses more than 90 % of its connection pool", "#8-database-connection-exhaustion",
              for_="15m"),
        alert("MeridianKafkaConsumerLagHigh",
              "max by (job, client_id) (kafka_consumer_fetch_manager_records_lag_max) > 10000", "ticket",
              "{{ $labels.job }} consumer is more than 10k records behind", "#7-kafka-consumer-lag-audit-catch-up",
              for_="10m"),
        alert("MeridianAuditConsumerLagCritical",
              'max(kafka_consumer_fetch_manager_records_lag_max{job="audit-service"}) > 50000', "page",
              "audit-service consumer more than 50k records behind for 1 h", "#7-kafka-consumer-lag-audit-catch-up",
              for_="1h"),
        alert("MeridianOutboxBacklog", "max by (job) (meridian_outbox_oldest_age_seconds) > 60", "ticket",
              "{{ $labels.job }} has unpublished outbox events older than 60 s",
              "#7-kafka-consumer-lag-audit-catch-up", for_="10m"),
        alert("MeridianOutboxStuck", "max by (job) (meridian_outbox_oldest_age_seconds) > 300", "page",
              "{{ $labels.job }} outbox relay stuck (events older than 5 min)", "#7-kafka-consumer-lag-audit-catch-up"),
        alert("MeridianOutboxRelayFailing", "sum by (job) (increase(meridian_outbox_relay_failures_total[5m])) > 0",
              "ticket", "{{ $labels.job }} cannot publish outbox events to Kafka",
              "#7-kafka-consumer-lag-audit-catch-up", for_="10m"),
        # MR-GAME-DAY: tenant moves and standby promotion
        alert("MeridianDataMoverRunFailed",
              'sum(increase(meridian_mover_runs_total{outcome="failed"}[15m])) > 0', "ticket",
              "A tenant move copy FAILED in the data mover (schema drift, target not empty or verification mismatch)",
              "#9-tenant-move-and-cell-failover", for_="0m"),
        alert("MeridianOutboxRelayPausedLong", "max by (job, cell) (meridian_outbox_relay_paused) == 1", "ticket",
              "{{ $labels.job }} relay paused for 30 min (expected only in a warm standby cell; after a promotion "
              "it means the promotion is stuck)", "#9-tenant-move-and-cell-failover", for_="30m"),
        alert("MeridianJvmHeapHigh",
              'sum by (job, instance) (jvm_memory_used_bytes{area="heap"})\n'
              '/\nsum by (job, instance) (jvm_memory_max_bytes{area="heap"} > 0) > 0.9',
              "ticket", "{{ $labels.job }} {{ $labels.instance }} heap above 90 %", None, for_="15m"),
    ]}]}


# ---------------------------------------------------------------------------------------------------- dashboards
DS = {"type": "prometheus", "uid": "${datasource}"}
CELL = 'cell=~"$cell"'


class Dashboard:
    def __init__(self, uid, title, description, variables=("cell",)):
        self.uid, self.title, self.description = uid, title, description
        self.panels, self.x, self.y, self.row_h, self.next_id = [], 0, 0, 0, 1
        self.variables = variables

    def _place(self, w, h):
        if self.x + w > 24:
            self.x, self.y = 0, self.y + self.row_h
            self.row_h = 0
        pos = {"h": h, "w": w, "x": self.x, "y": self.y}
        self.x += w
        self.row_h = max(self.row_h, h)
        return pos

    def row(self, title):
        if self.x:
            self.x, self.y, self.row_h = 0, self.y + self.row_h, 0
        self.panels.append({"id": self.next_id, "type": "row", "title": title, "collapsed": False,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.next_id += 1
        self.y += 1
        return self

    def panel(self, kind, title, targets, unit="short", w=12, h=8, description="", thresholds=None, **opts):
        p = {"id": self.next_id, "type": kind, "title": title, "description": description, "datasource": DS,
             "gridPos": self._place(w, h),
             "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
             "targets": [{"refId": chr(65 + i), "datasource": DS, "expr": e, "legendFormat": lf}
                         for i, (e, lf) in enumerate(targets)]}
        if thresholds:
            p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [
                {"color": c, "value": v} for v, c in thresholds]}
        if kind == "timeseries":
            p["options"] = {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                            "tooltip": {"mode": "multi", "sort": "desc"}}
            p["fieldConfig"]["defaults"]["custom"] = {"lineWidth": 1, "fillOpacity": 10, "showPoints": "never"}
        elif kind == "stat":
            p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                            "colorMode": "value", "graphMode": "area", "textMode": "auto"}
        p["options"] = {**p.get("options", {}), **opts}
        self.panels.append(p)
        self.next_id += 1
        return self

    def ts(self, title, targets, unit="short", **kw):
        return self.panel("timeseries", title, targets, unit, **kw)

    def stat(self, title, targets, unit="short", w=6, h=5, **kw):
        return self.panel("stat", title, targets, unit, w=w, h=h, **kw)

    def json(self):
        variables = [{"name": "datasource", "label": "Data source", "type": "datasource", "query": "prometheus",
                      "current": {}, "hide": 0, "refresh": 1, "options": []}]
        if "cell" in self.variables:
            variables.append({"name": "cell", "label": "Cell", "type": "query", "datasource": DS,
                              "query": {"query": f"label_values({HTTP}_count, cell)", "refId": "cell"},
                              "definition": f"label_values({HTTP}_count, cell)", "refresh": 2, "includeAll": True,
                              "multi": True, "allValue": ".*", "current": {}, "hide": 0, "sort": 1, "options": []})
        if "job" in self.variables:
            variables.append({"name": "job", "label": "Service", "type": "query", "datasource": DS,
                              "query": {"query": f"label_values({HTTP}_count, job)", "refId": "job"},
                              "definition": f"label_values({HTTP}_count, job)", "refresh": 2, "includeAll": True,
                              "multi": True, "allValue": ".*", "current": {}, "hide": 0, "sort": 1, "options": []})
        return {"uid": self.uid, "title": self.title, "description": self.description,
                "tags": ["meridian", "generated"], "timezone": "utc", "schemaVersion": 39, "version": 1,
                "editable": True, "graphTooltip": 1, "refresh": "30s", "time": {"from": "now-6h", "to": "now"},
                "annotations": {"list": []}, "links": [{"title": "Meridian", "type": "dashboards", "tags": ["meridian"],
                                                        "asDropdown": True}],
                "templating": {"list": variables}, "panels": self.panels}


def q(quantile, metric, sel, by="le"):
    return f"histogram_quantile({quantile}, sum by ({by}) (rate({metric}_bucket{{{sel}}}[$__rate_interval])))"


def overview():
    d = Dashboard("meridian-overview", "Meridian / Platform overview",
                  "RED metrics of every service and the edge, JVM and scrape health (GAP-10)", ("cell", "job"))
    sel = f'{CELL},job=~"$job"'
    d.row("Traffic and errors")
    d.stat("Requests / s", [(f"sum(rate({HTTP}_count{{{sel}}}[$__rate_interval]))", "")], "reqps")
    d.stat("5xx ratio", [(f'sum(rate({HTTP}_count{{{sel},status=~"5.."}}[$__rate_interval])) / '
                          f'sum(rate({HTTP}_count{{{sel}}}[$__rate_interval]))', "")], "percentunit",
           thresholds=[(None, "green"), (0.001, "orange"), (0.01, "red")])
    d.stat("p99 latency (all)", [(q(0.99, HTTP, sel), "")], "s")
    d.stat("Targets down", [('count(up{job=~".+-service|edge-gateway|cell-router|data-mover"} == 0) or vector(0)', "")],
           thresholds=[(None, "green"), (1, "red")])
    d.ts("Requests / s by service", [(f"sum by (job) (rate({HTTP}_count{{{sel}}}[$__rate_interval]))", "{{job}}")],
         "reqps")
    d.ts("5xx / s by service", [(f'sum by (job) (rate({HTTP}_count{{{sel},status=~"5.."}}[$__rate_interval]))',
                                 "{{job}}")], "reqps")
    d.ts("p99 latency by service", [(q(0.99, HTTP, sel, "le, job"), "{{job}}")], "s")
    d.ts("p50 latency by service", [(q(0.5, HTTP, sel, "le, job"), "{{job}}")], "s")
    d.row("JVM")
    d.ts("Heap used / max", [(f'sum by (job, instance) (jvm_memory_used_bytes{{{sel},area="heap"}}) / '
                              f'sum by (job, instance) (jvm_memory_max_bytes{{{sel},area="heap"}} > 0)',
                              "{{job}} {{instance}}")], "percentunit")
    d.ts("Live threads", [(f'sum by (job) (jvm_threads_live_threads{{{sel}}})', "{{job}}")])
    d.ts("GC pause / s", [(f"sum by (job) (rate(jvm_gc_pause_seconds_sum{{{sel}}}[$__rate_interval]))", "{{job}}")],
         "s")
    d.ts("Process CPU", [(f"avg by (job) (process_cpu_usage{{{sel}}})", "{{job}}")], "percentunit")
    return d


def slo_dashboard():
    d = Dashboard("meridian-slo", "Meridian / Journey SLOs",
                  "SLIs, error budgets and burn rates per critical journey (docs/operations/SLO_SLA.md section 2)")
    for jid, _, title, sli, budget, _, _ in slis():
        sel = f'journey="{jid}",sli="{sli}",{CELL}'
        d.row(f"{jid} {title} - {sli} (budget {budget * 100:g} %)")
        d.stat("SLI (30 d, approx.)", [(f"1 - avg(avg_over_time(meridian:slo_errors:ratio_rate5m{{{sel}}}[30d]))", "")],
               "percentunit", decimals=3)
        d.stat("Error budget left (30 d)", [(f"1 - avg(avg_over_time(meridian:slo_errors:ratio_rate5m{{{sel}}}[30d])) "
                                             f"/ {budget}", "")], "percentunit",
               thresholds=[(None, "red"), (0.25, "orange"), (0.5, "green")])
        d.stat("Burn rate 1 h", [(f"max(meridian:slo_errors:ratio_rate1h{{{sel}}}) / {budget}", "")], "x",
               thresholds=[(None, "green"), (6, "orange"), (14.4, "red")])
        d.stat("Burn rate 6 h", [(f"max(meridian:slo_errors:ratio_rate6h{{{sel}}}) / {budget}", "")], "x",
               thresholds=[(None, "green"), (1, "orange"), (6, "red")])
        d.ts("Error ratio (5 m / 1 h / 6 h)", [(f"max by (cell) (meridian:slo_errors:ratio_rate{w}{{{sel}}})",
                                                f"{w} {{{{cell}}}}") for w in ("5m", "1h", "6h")], "percentunit",
             w=24, h=7)
    d.row("J4 Login and step-up (IdP + channels)")
    d.ts("IdP login failures / s", [('sum by (error) (rate(keycloak_user_events_total{event="login",error!=""}'
                                     '[$__rate_interval]))', "{{error}}")], "ops",
         description="Keycloak with --metrics-enabled --event-metrics-user-enabled and the realm's metrics-listener")
    d.ts("Step-up challenges / s", [("sum by (channel, reason) (rate(meridian_auth_step_up_challenges_total"
                                     "[$__rate_interval]))", "{{channel}} {{reason}}")], "ops")
    d.ts("MFA verifications / s", [("sum by (outcome) (rate(meridian_identity_mfa_verifications_total"
                                    "[$__rate_interval]))", "{{outcome}}")], "ops")
    d.row("J5 EOD and J7 freshness")
    d.stat("Hours since last successful EOD sweep", [
        ("(time() - max(max_over_time(meridian_eod_sweep_last_success_timestamp_seconds[3d]))) / 3600", "")], "h",
        thresholds=[(None, "green"), (24, "orange"), (26, "red")])
    d.stat("Failed EOD sweeps (7 d)", [('sum(increase(meridian_eod_sweep_runs_total{status!="SUCCEEDED"}[7d]))',
                                        "")], thresholds=[(None, "green"), (1, "red")])
    d.stat("Search indexer lag", [("max(meridian_search_indexer_last_lag_ms) / 1000", "")], "s",
           thresholds=[(None, "green"), (30, "orange"), (60, "red")])
    d.stat("Oldest outbox event", [("max(meridian_outbox_oldest_age_seconds)", "")], "s",
           thresholds=[(None, "green"), (60, "orange"), (300, "red")])
    return d


def payments():
    d = Dashboard("meridian-payments", "Meridian / Payments", "J1 payment initiation: outcomes, latency, rejections")
    m = "meridian_payments_initiations_total"
    d.row("Initiation outcomes")
    d.stat("Initiations / s", [(f"sum(rate({m}{{{CELL}}}[$__rate_interval]))", "")], "ops")
    d.stat("Accepted", [(f'sum(rate({m}{{{CELL},outcome="accepted"}}[$__rate_interval])) / '
                         f'sum(rate({m}{{{CELL}}}[$__rate_interval]))', "")], "percentunit")
    d.stat("Server errors", [(f'sum(rate({m}{{{CELL},outcome="error"}}[$__rate_interval])) / '
                              f'sum(rate({m}{{{CELL}}}[$__rate_interval]))', "")], "percentunit",
           thresholds=[(None, "green"), (0.001, "orange"), (0.01, "red")])
    d.stat("p99 initiation", [(q(0.99, "meridian_payments_initiation_duration_seconds", CELL), "")], "s",
           thresholds=[(None, "green"), (0.5, "orange"), (1, "red")])
    d.ts("Initiations by outcome", [(f"sum by (outcome) (rate({m}{{{CELL}}}[$__rate_interval]))", "{{outcome}}")],
         "ops")
    d.ts("Status after synchronous processing", [(f"sum by (status) (rate({m}{{{CELL},outcome!=\"rejected\"}}"
                                                  f"[$__rate_interval]))", "{{status}}")], "ops")
    d.ts("Rejections by reason (top 10)", [(f'topk(10, sum by (reason) (rate({m}{{{CELL},outcome="rejected"}}'
                                            f'[$__rate_interval])))', "{{reason}}")], "ops")
    d.ts("Initiation latency", [(q(p, "meridian_payments_initiation_duration_seconds", CELL), f"p{int(p * 100)}")
                                for p in (0.5, 0.9, 0.99)], "s")
    d.row("HTTP (payment-service and channel BFFs)")
    sel = f'{CELL},{JOURNEYS[0]["selector"]}'
    d.ts("Requests by endpoint", [(f"sum by (job, uri, status) (rate({HTTP}_count{{{sel}}}[$__rate_interval]))",
                                   "{{job}} {{uri}} {{status}}")], "reqps")
    d.ts("p99 by endpoint", [(q(0.99, HTTP, sel, "le, job, uri"), "{{job}} {{uri}}")], "s")
    return d


def ledger():
    d = Dashboard("meridian-ledger", "Meridian / Ledger", "J3 ledger posting latency and outcomes")
    m = "meridian_ledger_posting_duration_seconds"
    d.row("Postings")
    d.stat("Postings / s", [(f'sum(rate({m}_count{{{CELL},outcome="posted"}}[$__rate_interval]))', "")], "ops")
    d.stat("p99 posting", [(q(0.99, m, CELL), "")], "s", thresholds=[(None, "green"), (0.15, "orange"), (0.25, "red")])
    d.stat("Under 250 ms", [(f'sum(rate({m}_bucket{{{CELL},le="0.25"}}[$__rate_interval])) / '
                             f'sum(rate({m}_count{{{CELL}}}[$__rate_interval]))', "")], "percentunit")
    d.stat("Rejected", [(f'sum(rate({m}_count{{{CELL},outcome="rejected"}}[$__rate_interval])) / '
                         f'sum(rate({m}_count{{{CELL}}}[$__rate_interval]))', "")], "percentunit")
    d.ts("Postings by outcome and path", [(f"sum by (outcome, path) (rate({m}_count{{{CELL}}}[$__rate_interval]))",
                                           "{{outcome}} {{path}}")], "ops")
    d.ts("Posting latency", [(q(p, m, CELL), f"p{int(p * 100)}") for p in (0.5, 0.9, 0.99)], "s")
    d.row("Database")
    sel = f'{CELL},job="ledger-service"'
    d.ts("Connections", [(f"sum(hikaricp_connections_active{{{sel}}})", "active"),
                         (f"sum(hikaricp_connections_pending{{{sel}}})", "pending"),
                         (f"sum(hikaricp_connections_max{{{sel}}})", "max")])
    d.ts("Connection acquire p99", [(q(0.99, "hikaricp_connections_acquire_seconds", sel), "p99")], "s")
    return d


def cards():
    d = Dashboard("meridian-cards", "Meridian / Card authorisation", "J2 decision latency, outcomes and stand-in")
    m = "meridian_cards_authorization_duration_seconds"
    d.row("Decisions")
    d.stat("Authorisations / s", [(f"sum(rate({m}_count{{{CELL}}}[$__rate_interval]))", "")], "ops")
    d.stat("p99 decision", [(q(0.99, m, CELL), "")], "s", thresholds=[(None, "green"), (0.15, "orange"), (0.25, "red")])
    d.stat("Approval rate", [(f'sum(rate({m}_count{{{CELL},decision=~"APPROVED|PARTIAL"}}[$__rate_interval])) / '
                              f'sum(rate({m}_count{{{CELL}}}[$__rate_interval]))', "")], "percentunit")
    d.stat("Stand-in", [(f'sum(rate({m}_count{{{CELL},stand_in="true"}}[$__rate_interval])) / '
                         f'sum(rate({m}_count{{{CELL}}}[$__rate_interval]))', "")], "percentunit",
           thresholds=[(None, "green"), (0.01, "orange"), (0.05, "red")])
    d.ts("Decisions", [(f"sum by (decision, message_type) (rate({m}_count{{{CELL}}}[$__rate_interval]))",
                        "{{message_type}} {{decision}}")], "ops")
    d.ts("Decision latency", [(q(p, m, CELL), f"p{int(p * 100)}") for p in (0.5, 0.9, 0.99, 0.999)], "s")
    d.ts("HTTP p99 (service edge)", [(q(0.99, HTTP, f'{CELL},job="card-authorization-service",'
                                                    f'uri="/v1/card-authorizations"'), "p99")], "s")
    d.ts("HTTP status", [(f'sum by (status) (rate({HTTP}_count{{{CELL},job="card-authorization-service",'
                          f'uri="/v1/card-authorizations"}}[$__rate_interval]))', "{{status}}")], "reqps")
    return d


def eod():
    d = Dashboard("meridian-eod", "Meridian / EOD and operations",
                  "J5 end-of-day sweeps and the maker-checker approval queue")
    m = "meridian_eod_sweep"
    d.row("EOD sweeps")
    d.stat("Hours since last success", [(f"(time() - max(max_over_time({m}_last_success_timestamp_seconds[3d]))) / 3600",
                                         "")], "h", thresholds=[(None, "green"), (24, "orange"), (26, "red")])
    # Prometheus registry: <name>_seconds_max; OTLP translation: <name>_max_seconds
    d.stat("Longest recent sweep", [(f"max({m}_duration_seconds_max{{{CELL}}} or {m}_duration_max_seconds{{{CELL}}})",
                                     "")], "s")
    d.stat("Failed / partial (7 d)", [(f'sum(increase({m}_runs_total{{{CELL},status!="SUCCEEDED"}}[7d]))', "")],
           thresholds=[(None, "green"), (1, "red")])
    d.stat("Skipped (slot already claimed, 7 d)", [(f"sum(increase({m}_skipped_total{{{CELL}}}[7d]))", "")])
    d.ts("Sweeps by status", [(f"sum by (trigger, status) (increase({m}_runs_total{{{CELL}}}[1h]))",
                               "{{trigger}} {{status}}")], description="per hour")
    d.ts("Sweep duration p50 / p99", [(q(p, f"{m}_duration_seconds", CELL, "le"), f"p{int(p * 100)}")
                                      for p in (0.5, 0.99)], "s")
    d.ts("Trigger decisions", [("sum by (decision) (increase(meridian_eod_trigger_decisions_total[1h]))",
                                "{{decision}}")])
    d.row("Approval queue (workflow-service)")
    d.stat("Pending approvals", [(f"max(meridian_workflow_approvals_pending{{{CELL}}})", "")])
    d.stat("Oldest pending", [(f"max(meridian_workflow_approvals_oldest_age_seconds{{{CELL}}})", "")], "s",
           thresholds=[(None, "green"), (14400, "orange"), (86400, "red")])
    d.ts("Approval queue", [(f"max(meridian_workflow_approvals_pending{{{CELL}}})", "pending"),
                            (f"max(meridian_workflow_approvals_oldest_age_seconds{{{CELL}}}) / 3600", "oldest (h)")])
    return d


def kafka():
    d = Dashboard("meridian-kafka", "Meridian / Kafka and outbox",
                  "Consumer lag, outbox backlog and relay throughput (event freshness, J7)", ("cell", "job"))
    sel = f'{CELL},job=~"$job"'
    d.row("Consumers")
    d.ts("Max records lag by service", [(f"max by (job) (kafka_consumer_fetch_manager_records_lag_max{{{sel}}})",
                                         "{{job}}")], w=24)
    d.ts("Records consumed / s", [(f"sum by (job) (rate(kafka_consumer_fetch_manager_records_consumed_total{{{sel}}}"
                                   f"[$__rate_interval]))", "{{job}}")], "ops")
    d.ts("Listener mean time", [(f"sum by (job) (rate(spring_kafka_listener_seconds_sum{{{sel}}}[$__rate_interval])) / "
                                 f"sum by (job) (rate(spring_kafka_listener_seconds_count{{{sel}}}[$__rate_interval]))",
                                 "{{job}}")], "s")
    d.row("Outbox")
    d.ts("Backlog (unpublished events)", [(f"max by (job) (meridian_outbox_backlog{{{sel}}})", "{{job}}")])
    d.ts("Oldest unpublished event", [(f"max by (job) (meridian_outbox_oldest_age_seconds{{{sel}}})", "{{job}}")], "s")
    d.ts("Relayed / s", [(f"sum by (job) (rate(meridian_outbox_relayed_total{{{sel}}}[$__rate_interval]))", "{{job}}")],
         "ops")
    d.ts("Relay failures", [(f"sum by (job) (increase(meridian_outbox_relay_failures_total{{{sel}}}[5m]))", "{{job}}")])
    d.row("Search indexer")
    d.ts("Indexer lag", [("max(meridian_search_indexer_last_lag_ms) / 1000", "last lag"),
                         (q(0.99, "meridian_search_indexer_lag_seconds", CELL), "p99")], "s", w=24)
    return d


def db_pool():
    d = Dashboard("meridian-db-pool", "Meridian / Database pools",
                  "HikariCP saturation per service (connection budget, GAP-12)", ("cell", "job"))
    sel = f'{CELL},job=~"$job"'
    d.row("Saturation")
    d.stat("Pending threads", [(f"sum(hikaricp_connections_pending{{{sel}}})", "")],
           thresholds=[(None, "green"), (1, "red")])
    d.stat("Timeouts (1 h)", [(f"sum(increase(hikaricp_connections_timeout_total{{{sel}}}[1h]))", "")],
           thresholds=[(None, "green"), (1, "red")])
    d.stat("Connections in use", [(f"sum(hikaricp_connections_active{{{sel}}})", "")])
    d.stat("Connections configured", [(f"sum(hikaricp_connections_max{{{sel}}})", "")],
           description="Sum of pool maxima: compare with the Aurora max_connections (GAP-12)")
    d.ts("Pool usage by service", [(f"max by (job) (hikaricp_connections_active{{{sel}}} / "
                                    f"hikaricp_connections_max{{{sel}}})", "{{job}}")], "percentunit")
    d.ts("Pending by service", [(f"max by (job) (hikaricp_connections_pending{{{sel}}})", "{{job}}")])
    d.ts("Acquire p99", [(q(0.99, "hikaricp_connections_acquire_seconds", sel, "le, job"), "{{job}}")], "s")
    d.ts("Usage time p99", [(q(0.99, "hikaricp_connections_usage_seconds", sel, "le, job"), "{{job}}")], "s",
         description="How long a request holds a connection (long transactions starve the pool)")
    return d


def gameday():
    """MR-GAME-DAY: the RTO timeline of a regional failover on one screen (detection, reads moved, writes back),
    standby promotion state and tenant-move copies. Annotate T0 by hand; the RPO number comes from
    tools/gameday/rpo_probe.py check."""
    d = Dashboard("meridian-gameday", "Meridian / Game day (failover timeline)",
                  "Region failover and tenant moves: router view of the cells, failover traffic, front-door errors, "
                  "standby relay state, outbox backlog, data mover progress (docs/MULTI_REGION.md section 5)",
                  variables=())
    d.row("Detection and routing (router)")
    d.ts("Cell healthy (router health checks)", [("max by (cell) (meridian_router_cell_healthy)", "{{cell}}")],
         description="0 after 3 failed probes (~15 s): detection")
    d.ts("Cell serving (registry status / overrides)", [("max by (cell) (meridian_router_cell_serving)", "{{cell}}")])
    d.ts("Failover requests / s", [("sum by (from_cell, to_cell) (rate(meridian_router_failover_requests_total"
                                    "[$__rate_interval]))", "{{from_cell}} -> {{to_cell}}")], "reqps",
         description="reads moved: the standby serves the home cell's tenants")
    d.ts("Front door by outcome / s", [("sum by (outcome) (rate(meridian_router_requests_total"
                                        "[$__rate_interval]))", "{{outcome}}")], "reqps",
         description="writes back when cell_read_only / cell_unavailable answers stop")
    d.row("Standby promotion and events")
    d.ts("Outbox relay paused (1 = standby hold)", [("max by (cell, job) (meridian_outbox_relay_paused)",
                                                     "{{cell}} {{job}}")], w=24,
         description="drops to 0 per service when its promotion completes (no restart)")
    d.ts("Outbox backlog", [("sum by (cell) (meridian_outbox_backlog)", "{{cell}}")])
    d.ts("Replayed events / s", [("sum by (cell, job) (rate(meridian_outbox_replayed_total[$__rate_interval]))",
                                  "{{cell}} {{job}}")], "ops",
         description="replay-on-promote of the last window, or an operator replay")
    d.row("Tenant moves (data mover)")
    d.ts("Rows copied / s", [("sum by (service) (rate(meridian_mover_rows_copied_total[$__rate_interval]))",
                              "{{service}}")], "ops")
    d.stat("Copies failed (24 h)", [('sum(increase(meridian_mover_runs_total{outcome="failed"}[24h])) or vector(0)', "")],
           thresholds=[(None, "green"), (1, "red")])
    d.stat("Copies completed (24 h)", [('sum(increase(meridian_mover_runs_total{outcome="completed"}[24h])) '
                                        'or vector(0)', "")])
    return d


def dashboards():
    return [overview(), slo_dashboard(), payments(), ledger(), cards(), eod(), kafka(), db_pool(), gameday()]


# ---------------------------------------------------------------------------------------------------- output
HEADER = ("# Generated by tools/observability.py from docs/operations/SLO_SLA.md; edit the generator, not this file.\n"
          "# Prometheus rule file (also packaged as a PrometheusRule by infra/helm/meridian, values observability.*).\n")


def to_yaml(obj, indent=0):
    """Small deterministic YAML emitter (block scalars for multi-line PromQL, JSON-quoted strings otherwise)."""
    pad = "  " * indent
    lines = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if isinstance(v, (dict, list)) and v:
                lines.append(f"{pad}{k}:")
                lines.append(to_yaml(v, indent + 1))
            elif isinstance(v, str) and "\n" in v:
                lines.append(f"{pad}{k}: |-")
                lines.extend(f"{pad}  {line}" for line in v.split("\n"))
            else:
                lines.append(f"{pad}{k}: {scalar(v)}")
    elif isinstance(obj, list):
        for item in obj:
            sub = to_yaml(item, indent + 1).split("\n")
            first = sub[0].lstrip()
            lines.append(f"{pad}- {first}")
            lines.extend(sub[1:])
    return "\n".join(lines)


def scalar(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if v == {} or v == []:
        return json.dumps(v)
    return json.dumps(v)


def rendered():
    files = {}
    for name, rules in (("meridian-slo.rules.yml", slo_rules()), ("meridian-journeys.rules.yml", journey_rules()),
                        ("meridian-saturation.rules.yml", saturation_rules())):
        files[RULES / name] = HEADER + to_yaml(rules) + "\n"
    for d in dashboards():
        files[DASHBOARDS / f"{d.uid}.json"] = json.dumps(d.json(), indent=2) + "\n"
    return files


def promql_sane(expr):
    depth = 0
    for ch in re.sub(r'"(?:[^"\\]|\\.)*"', '""', expr):
        depth += {"(": 1, ")": -1, "{": 1, "}": -1, "[": 1, "]": -1}.get(ch, 0)
        if depth < 0:
            return False
    return depth == 0


def check():
    import yaml
    errors = []
    expected = rendered()
    for path, text in expected.items():
        if not path.exists():
            errors.append(f"missing {path.relative_to(ROOT)}")
        elif path.read_text(encoding="utf-8") != text:
            errors.append(f"stale {path.relative_to(ROOT)} (run python tools/observability.py)")
    uids, alerts, records = set(), set(), 0
    for p in sorted(DASHBOARDS.glob("*.json")):
        d = json.loads(p.read_text(encoding="utf-8"))
        if d["uid"] in uids:
            errors.append(f"duplicate dashboard uid {d['uid']}")
        uids.add(d["uid"])
        for panel in d["panels"]:
            for t in panel.get("targets", []):
                expr = t["expr"].replace("$__rate_interval", "5m")
                if not promql_sane(expr):
                    errors.append(f"{p.name} '{panel['title']}': unbalanced PromQL")
    for p in sorted(RULES.glob("*.yml")):
        doc = yaml.safe_load(p.read_text(encoding="utf-8"))
        for g in doc["groups"]:
            for r in g["rules"]:
                if not promql_sane(r["expr"]):
                    errors.append(f"{p.name} {r.get('alert') or r.get('record')}: unbalanced PromQL")
                if "alert" in r:
                    if r["alert"] in alerts:
                        errors.append(f"duplicate alert {r['alert']}")
                    alerts.add(r["alert"])
                    if r["labels"].get("severity") not in ("page", "ticket"):
                        errors.append(f"{r['alert']}: severity must be page or ticket")
                else:
                    records += 1
    promtool = shutil.which("promtool")
    if promtool:
        res = subprocess.run([promtool, "check", "rules", *map(str, sorted(RULES.glob("*.yml")))],
                             capture_output=True, text=True)
        if res.returncode:
            errors.append(res.stdout + res.stderr)
    for e in errors:
        print("ERROR:", e)
    print(f"observability: {len(uids)} dashboards, {len(alerts)} alerts, {records} recording rules"
          f"{', promtool OK' if promtool and not errors else ''}")
    return not errors


def main():
    if "--check" in sys.argv:
        sys.exit(0 if check() else 1)
    for path, text in rendered().items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
    print(f"observability: {len(dashboards())} dashboards, 3 rule files -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
