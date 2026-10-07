#!/usr/bin/env python3
"""Generates the Kubernetes Helm chart infra/helm/meridian from generated/services.json.

One chart deploys a whole Meridian cell: every domain service plus the edge gateway, each with Deployment,
Service, ServiceAccount, HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy and (optionally) a
Prometheus ServiceMonitor. Infrastructure (PostgreSQL, Kafka, Keycloak, OTel collector) is external and
referenced through values. Secrets are referenced by name, never rendered. Run after tools/generate.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "infra" / "helm" / "meridian"
CHART_VERSION = "0.1.0"

# Services on the money path get more headroom by default; everything else starts small and autoscales.
HOT = {"ledger-service": (3, "1", "1Gi"), "payment-service": (3, "1", "1Gi"),
       "card-authorization-service": (3, "1", "1Gi"), "wallet-service": (3, "1", "1Gi"),
       "fraud-service": (2, "500m", "768Mi"), "limits-service": (2, "500m", "768Mi")}

# SEC-01 / SEC-02: secrets each service needs, as {ENV_VAR: key in the service's Secret}. The Secret (default name
# meridian-<service>-secrets, override per service with secretName) is created by the operator from the KMS / vault;
# the chart only references it. The services refuse to start outside security-mode=dev when one is missing or a
# committed development default (libs/meridian-web RequiredSecrets, card-vault MasterKeyProviders).
SERVICE_SECRETS = {
    "audit-service": {"MERIDIAN_AUDIT_DOWNLOAD_SIGNING_KEY": "download-signing-key"},
    "card-authorization-service": {"MERIDIAN_CARDS_AUTH_JIT_SECRET": "jit-secret"},
    "card-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret"},
    "card-threeds-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret",
                             "MERIDIAN_CARDS_THREEDS_CAVV_KEY": "cavv-key",
                             "MERIDIAN_CARDS_THREEDS_OTP_KEY": "otp-key"},
    "card-tokenization-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret",
                                  "MERIDIAN_CARDS_TOKENS_OTP_KEY": "otp-key",
                                  "MERIDIAN_CARDS_TOKENS_PAYLOAD_KEY": "payload-key"},
    "card-vault-service": {"MERIDIAN_CARDS_VAULT_MASTER_KEY": "master-key"},
    "document-service": {"MERIDIAN_ESIGN_CALLBACK_SECRET": "esign-callback-secret",
                         "MERIDIAN_DOCUMENTS_S3_ACCESS_KEY": "s3-access-key",
                         "MERIDIAN_DOCUMENTS_S3_SECRET_KEY": "s3-secret-key"},
    "file-gateway-service": {"MERIDIAN_FILES_SIGNING_KEY": "signing-key"},
    "loan-origination-service": {"MERIDIAN_LOAN_ORIGINATION_CALLBACK_SECRET": "callback-secret"},
    "onboarding-service": {"MERIDIAN_ONBOARDING_PUBLIC_OTP_SECRET": "otp-secret",
                           "MERIDIAN_ONBOARDING_RESUME_TOKEN_SECRET": "resume-token-secret"},
    "routing-service": {"MERIDIAN_ROUTING_CALLBACK_SECRET": "callback-secret"},
    "search-service": {"MERIDIAN_CONTACT_LOOKUP_KEY": "contact-lookup-key"},
    "statement-service": {"MERIDIAN_STATEMENT_DOWNLOAD_SECRET": "download-secret"},
    "webhook-service": {"MERIDIAN_WEBHOOKS_KEY": "encryption-key"},
}

HELPERS = r'''{{/* Common labels for a Meridian component. */}}
{{- define "meridian.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: meridian
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ .root.Values.global.image.tag | default .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
meridian.io/domain: {{ .domain | default "platform" }}
{{- end }}

{{- define "meridian.selector" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{- define "meridian.image" -}}
{{- $g := .root.Values.global.image -}}
{{ printf "%s/%s:%s" $g.registry .name ($g.tag | default .root.Chart.AppVersion) }}
{{- end }}
'''

SERVICES_TPL = r'''{{- $root := . -}}
{{- $g := .Values.global -}}
{{- range $svc := .Values.services }}
{{- if $svc.enabled }}
{{- $ctx := dict "root" $root "name" $svc.name "domain" $svc.domain }}
{{- $replicas := $svc.replicas | default $g.replicas }}
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
automountServiceAccountToken: false
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  type: ClusterIP
  selector: {{- include "meridian.selector" $ctx | nindent 4 }}
  ports:
    - name: http
      port: 80
      targetPort: http
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  {{- if not $g.autoscaling.enabled }}
  replicas: {{ $replicas }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxUnavailable: 0, maxSurge: 1}
  selector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
  template:
    metadata:
      labels: {{- include "meridian.labels" $ctx | nindent 8 }}
      annotations:
        checksum/config: {{ include (print $root.Template.BasePath "/config.yaml") $root | sha256sum }}
    spec:
      serviceAccountName: {{ $svc.name }}
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: 45
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile: {type: RuntimeDefault}
      {{- with $g.imagePullSecrets }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- include "meridian.selector" $ctx | nindent 14 }}
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- include "meridian.selector" $ctx | nindent 14 }}
      containers:
        - name: app
          image: {{ include "meridian.image" $ctx }}
          imagePullPolicy: {{ $g.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
          envFrom:
            - configMapRef:
                name: meridian-common
          env:
            - name: SERVER_PORT
              value: "8080"
            {{- if $svc.database }}
            - name: SPRING_DATASOURCE_URL
              value: {{ printf "jdbc:postgresql://%s:%v/%s?currentSchema=%s&sslmode=%s" $g.postgres.host $g.postgres.port $svc.database $svc.schema $g.postgres.sslMode | quote }}
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom: {secretKeyRef: {name: {{ $g.postgres.appSecret }}, key: username}}
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom: {secretKeyRef: {name: {{ $g.postgres.appSecret }}, key: password}}
            - name: SPRING_FLYWAY_USER
              valueFrom: {secretKeyRef: {name: {{ $g.postgres.migrationSecret }}, key: username}}
            - name: SPRING_FLYWAY_PASSWORD
              valueFrom: {secretKeyRef: {name: {{ $g.postgres.migrationSecret }}, key: password}}
            - name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
              value: {{ $svc.dbPool | default $g.postgres.poolSize | quote }}
            {{- end }}
            - name: MERIDIAN_SERVICE_AUTH_CLIENT_ID
              valueFrom: {secretKeyRef: {name: {{ $g.auth.serviceClientSecret }}, key: client-id}}
            - name: MERIDIAN_SERVICE_AUTH_CLIENT_SECRET
              valueFrom: {secretKeyRef: {name: {{ $g.auth.serviceClientSecret }}, key: client-secret}}
            - name: MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_SERVICE_NAME
              value: {{ $svc.name }}
            {{- range $envName, $key := $svc.secretEnv }}
            - name: {{ $envName }}
              valueFrom: {secretKeyRef: {name: {{ $svc.secretName | default (printf "meridian-%s-secrets" $svc.name) }}, key: {{ $key }}}}
            {{- end }}
            {{- range $k, $v := $svc.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          resources:
            requests: {cpu: {{ $svc.cpu | default $g.resources.cpu | quote }}, memory: {{ $svc.memory | default $g.resources.memory | quote }}}
            limits: {memory: {{ $svc.memory | default $g.resources.memory | quote }}}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: http}
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: http}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: http}
            periodSeconds: 5
          lifecycle:
            preStop:
              sleep: {seconds: 5}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 256Mi}
{{- if $g.autoscaling.enabled }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  scaleTargetRef: {apiVersion: apps/v1, kind: Deployment, name: {{ $svc.name }}}
  minReplicas: {{ $replicas }}
  maxReplicas: {{ $svc.maxReplicas | default $g.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {type: Utilization, averageUtilization: {{ $g.autoscaling.targetCpu }}}
{{- end }}
{{- if gt (int $replicas) 1 }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  minAvailable: 1
  selector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
{{- end }}
{{- if $g.networkPolicy.enabled }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    # only other Meridian components (gateway, services, jobs) and the metrics scraper
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: meridian
        {{- with $g.networkPolicy.monitoringNamespace }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
        {{- end }}
      ports:
        - {port: http, protocol: TCP}
{{- end }}
{{- if $g.serviceMonitor.enabled }}
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ $svc.name }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  selector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 30s
{{- end }}
{{- end }}
{{- end }}
'''

CONFIG_TPL = r'''{{- $g := .Values.global -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: meridian-common
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ .Release.Name }}
data:
  # every component: JWT security, cluster DNS for service-to-service calls, central approvals
  MERIDIAN_SECURITY_MODE: "jwt"
  MERIDIAN_SERVICES_URL_TEMPLATE: "http://{name}"
  MERIDIAN_GATEWAY_URL_TEMPLATE: "http://{name}"
  MERIDIAN_APPROVALS_MODE: {{ $g.approvalsMode | quote }}
  SPRING_KAFKA_BOOTSTRAP_SERVERS: {{ $g.kafka.bootstrapServers | quote }}
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: {{ $g.auth.issuerUri | quote }}
  MERIDIAN_SERVICE_AUTH_TOKEN_URI: {{ printf "%s/protocol/openid-connect/token" $g.auth.issuerUri | quote }}
  MANAGEMENT_OTLP_METRICS_EXPORT_URL: {{ printf "%s/v1/metrics" $g.otel.endpoint | quote }}
  MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT: {{ printf "%s/v1/traces" $g.otel.endpoint | quote }}
  MANAGEMENT_TRACING_SAMPLING_PROBABILITY: {{ $g.otel.samplingProbability | quote }}
  {{- range $k, $v := $g.env }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
'''

INGRESS_TPL = r'''{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: meridian-api
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ .Release.Name }}
  {{- with .Values.ingress.annotations }}
  annotations: {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- with .Values.ingress.className }}
  ingressClassName: {{ . }}
  {{- end }}
  {{- with .Values.ingress.tls }}
  tls: {{- toYaml . | nindent 4 }}
  {{- end }}
  rules:
    - host: {{ .Values.ingress.host | quote }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service: {name: edge-gateway, port: {name: http}}
{{- end }}
'''

NOTES = r'''Meridian {{ .Chart.AppVersion }} installed as "{{ .Release.Name }}" in namespace {{ .Release.Namespace }}.

Components: {{ len .Values.services }}
API: {{ if .Values.ingress.enabled }}https://{{ .Values.ingress.host }}{{ else }}kubectl port-forward svc/edge-gateway 18000:80{{ end }}

Required secrets in the namespace (not created by this chart):
  {{ .Values.global.postgres.appSecret }}        username/password of the runtime role (RLS-bound, no DDL)
  {{ .Values.global.postgres.migrationSecret }}  username/password of the schema owner used by Flyway
  {{ .Values.global.auth.serviceClientSecret }}  client-id/client-secret of the service account (scope meridian:internal)
{{- range $svc := .Values.services }}
{{- if and $svc.enabled $svc.secretEnv }}
  {{ $svc.secretName | default (printf "meridian-%s-secrets" $svc.name) }}  keys: {{ join ", " (values $svc.secretEnv | sortAlpha) }}
{{- end }}
{{- end }}
Secret values must be random (>= 32 characters; the card-vault master key 32 random bytes, base64) and come from the
KMS / vault: services refuse to start when one is missing or a committed development default.
'''


def services_values(manifest):
    rows = []
    for s in manifest:
        r = {"name": s["name"], "domain": s["domain"], "enabled": True,
             "database": s.get("database"), "schema": s.get("schema")}
        if s["name"] in HOT:
            rep, cpu, mem = HOT[s["name"]]
            r.update({"replicas": rep, "cpu": cpu, "memory": mem})
        if s["name"] in SERVICE_SECRETS:
            r["secretName"] = f"meridian-{s['name']}-secrets"
            r["secretEnv"] = dict(sorted(SERVICE_SECRETS[s["name"]].items()))
        rows.append(r)
    rows.append({"name": "edge-gateway", "domain": "platform", "enabled": True, "database": None, "schema": None,
                 "replicas": 3, "cpu": "500m", "memory": "512Mi"})
    return rows


def yaml_value(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    return json.dumps(v)


def values_yaml(rows, app_version):
    lines = [
        "# Generated by tools/helm_chart.py from generated/services.json; edit the generator, not this file.",
        "# Override per environment with -f values-<env>.yaml.",
        "global:",
        "  image:",
        '    registry: "ghcr.io/meridian"',
        '    tag: ""                # defaults to the chart appVersion',
        "    pullPolicy: IfNotPresent",
        "  imagePullSecrets: []",
        "  replicas: 2",
        "  resources: {cpu: 250m, memory: 640Mi}",
        "  autoscaling: {enabled: true, maxReplicas: 10, targetCpu: 70}",
        "  networkPolicy: {enabled: true, monitoringNamespace: monitoring}",
        "  serviceMonitor: {enabled: false}",
        '  approvalsMode: workflow   # central maker-checker through workflow-service',
        "  postgres:",
        '    host: "meridian-postgres"',
        "    port: 5432",
        '    sslMode: "require"',
        "    poolSize: 20",
        '    appSecret: "meridian-db-app"',
        '    migrationSecret: "meridian-db-owner"',
        "  kafka:",
        '    bootstrapServers: "meridian-kafka:9092"',
        "  auth:",
        '    issuerUri: "https://auth.example.com/realms/meridian"',
        '    serviceClientSecret: "meridian-service-client"',
        "  otel:",
        '    endpoint: "http://otel-collector.monitoring:4318"',
        "    samplingProbability: 0.1",
        "  env: {}                   # extra environment for every component",
        "",
        "ingress:",
        "  enabled: false",
        '  className: ""',
        '  host: "api.example.com"',
        "  annotations: {}",
        "  tls: []",
        "",
        "# One entry per component. Per-service overrides: enabled, replicas, maxReplicas, cpu, memory, dbPool, env,",
        "# secretName / secretEnv (ENV_VAR: key of the service's Secret; values come from the KMS / vault, never git).",
        "services:",
    ]
    for r in rows:
        lines.append(f"  - name: {r['name']}")
        for k in ("domain", "enabled", "database", "schema", "replicas", "cpu", "memory"):
            if k in r:
                lines.append(f"    {k}: {yaml_value(r[k])}")
        if "secretEnv" in r:
            lines.append(f"    secretName: {yaml_value(r['secretName'])}")
            lines.append("    secretEnv:")
            for env, key in r["secretEnv"].items():
                lines.append(f"      {env}: {yaml_value(key)}")
    return "\n".join(lines) + "\n"


def main():
    manifest = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
    rows = services_values(manifest)
    app_version = "0.1.0"
    (OUT / "templates").mkdir(parents=True, exist_ok=True)
    files = {
        "Chart.yaml": f"""apiVersion: v2
name: meridian
description: Meridian banking core - one cell (all domain services and the edge gateway)
type: application
version: {CHART_VERSION}
appVersion: "{app_version}"
kubeVersion: ">=1.30.0-0"
""",
        "values.yaml": values_yaml(rows, app_version),
        "templates/_helpers.tpl": HELPERS,
        "templates/services.yaml": SERVICES_TPL,
        "templates/config.yaml": CONFIG_TPL,
        "templates/ingress.yaml": INGRESS_TPL,
        "templates/NOTES.txt": NOTES,
        ".helmignore": ".git/\n*.md.bak\n",
    }
    for name, text in files.items():
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
    print(f"helm chart: {len(rows)} components -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
