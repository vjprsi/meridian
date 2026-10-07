#!/usr/bin/env python3
"""Generates the Kubernetes Helm charts infra/helm/meridian and infra/helm/meridian-router.

infra/helm/meridian deploys a whole Meridian cell: every domain service plus the edge gateway, each with Deployment,
Service, ServiceAccount, HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy and (optionally) a
Prometheus ServiceMonitor; global.cell.* names the cell (S20). infra/helm/meridian-router deploys the cell router, the
regional entry tier in front of the cells. Infrastructure (PostgreSQL, Kafka, Keycloak, OTel collector) is external and
referenced through values. Secrets are referenced by name, never rendered. Run after tools/generate.py.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "infra" / "helm" / "meridian"
ROUTER_OUT = ROOT / "infra" / "helm" / "meridian-router"
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
    "fraud-service": {"MERIDIAN_FRAUD_PSEUDONYM_KEY": "pseudonym-key"},
    "identity-service": {"MERIDIAN_IDENTITY_MFA_SEED_KEY": "mfa-seed-key"},
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
meridian.io/cell: {{ .root.Values.global.cell.id | default "unassigned" | quote }}
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
{{- if and $g.cell.id (not (regexMatch "^[a-z0-9]([-a-z0-9]{0,30}[a-z0-9])?$" $g.cell.id)) }}
{{- fail "global.cell.id must be the cell code of the tenant registry (lowercase letters, digits, dashes)" }}
{{- end }}
{{- if not (has $g.cell.role (list "primary" "standby")) }}
{{- fail "global.cell.role must be primary or standby" }}
{{- end }}
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
  # SEC-14: tokens must carry this audience; SEC-15: only this tenant's users may use platform:operator
  MERIDIAN_SECURITY_AUDIENCES: {{ $g.auth.audience | quote }}
  MERIDIAN_SECURITY_PLATFORM_OPERATOR_TENANT: {{ $g.auth.platformOperatorTenant | quote }}
  # SEC-16: load balancer / ingress addresses whose X-Forwarded-* the edge gateway believes (comma separated CIDRs)
  MERIDIAN_GATEWAY_TRUSTED_PROXIES: {{ join "," $g.trustedProxies | quote }}
  # SEC-17: provider callbacks must use v2 signatures (timestamp + nonce) once this is false
  MERIDIAN_CALLBACKS_ACCEPT_LEGACY_SIGNATURES: {{ $g.callbacks.acceptLegacySignatures | quote }}
  MERIDIAN_CALLBACKS_MAX_SKEW: {{ $g.callbacks.maxSkew | quote }}
  MERIDIAN_SERVICE_AUTH_TOKEN_URI: {{ printf "%s/protocol/openid-connect/token" $g.auth.issuerUri | quote }}
  # S20: which cell this is (the cell router maps tenants to cells; telemetry is tagged per cell and region)
  MERIDIAN_CELL_ID: {{ $g.cell.id | quote }}
  MERIDIAN_CELL_REGION: {{ $g.cell.region | quote }}
  MERIDIAN_CELL_ROLE: {{ $g.cell.role | quote }}
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_MERIDIAN_CELL: {{ $g.cell.id | quote }}
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_CLOUD_REGION: {{ $g.cell.region | quote }}
  {{- if eq $g.cell.role "standby" }}
  # warm standby: its database is a read-only replica and its topics are replicated, so it neither relays outboxes
  # nor consumes events (that would apply them twice); promotion = role primary (helm upgrade, rolling restart)
  MERIDIAN_EVENTS_RELAY_ENABLED: "false"
  SPRING_KAFKA_LISTENER_AUTO_STARTUP: "false"
  {{- end }}
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
Cell: {{ .Values.global.cell.id | default "(single-cell install)" }} {{ .Values.global.cell.region }} ({{ .Values.global.cell.role }})

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


ROUTER_HELPERS = r'''{{- define "router.labels" -}}
app.kubernetes.io/name: cell-router
app.kubernetes.io/part-of: meridian
app.kubernetes.io/component: cell-router
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
meridian.io/region: {{ .Values.region | default "unassigned" | quote }}
{{- end }}

{{- define "router.selector" -}}
app.kubernetes.io/name: cell-router
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
'''

ROUTER_TPL = r'''{{- $r := .Values.router -}}
{{- if not $r.registryUrl }}{{ fail "router.registryUrl (tenant-service of the control plane) is required" }}{{ end }}
{{- if not $r.jwt.jwkSetUri }}{{ fail "router.jwt.jwkSetUri is required (jwt mode)" }}{{ end }}
{{- range $cell, $state := $r.cellOverrides }}
{{- if not (has $state (list "ACTIVE" "READ_ONLY" "OFFLINE")) }}{{ fail (printf "router.cellOverrides.%s must be ACTIVE, READ_ONLY or OFFLINE" $cell) }}{{ end }}
{{- end }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
automountServiceAccountToken: false
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
data:
  MERIDIAN_SECURITY_MODE: "jwt"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: {{ .Values.kafka.bootstrapServers | quote }}
  MANAGEMENT_OTLP_METRICS_EXPORT_URL: {{ printf "%s/v1/metrics" .Values.otel.endpoint | quote }}
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_SERVICE_NAME: "cell-router"
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_CLOUD_REGION: {{ .Values.region | quote }}
  # meridian.router.* (registry, JWT verification, cache, health checks with hysteresis, break-glass overrides)
  SPRING_APPLICATION_JSON: {{ dict "meridian" (dict "router" (omit $r "auth")) | toJson | quote }}
---
apiVersion: v1
kind: Service
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  selector: {{- include "router.selector" . | nindent 4 }}
  ports:
    - {name: http, port: 80, targetPort: http}
    - {name: management, port: 9090, targetPort: management}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicas }}
  {{- end }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxUnavailable: 0, maxSurge: 1}
  selector:
    matchLabels: {{- include "router.selector" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "router.labels" . | nindent 8 }}
      annotations:
        checksum/config: {{ toJson .Values | sha256sum }}
    spec:
      serviceAccountName: cell-router
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: 45
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile: {type: RuntimeDefault}
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels: {{- include "router.selector" . | nindent 14 }}
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- include "router.selector" . | nindent 14 }}
      containers:
        - name: app
          image: {{ printf "%s/cell-router:%s" .Values.image.registry (.Values.image.tag | default .Chart.AppVersion) }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - {name: http, containerPort: 8080}
            - {name: management, containerPort: 9090}
          envFrom:
            - configMapRef:
                name: cell-router
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: MANAGEMENT_SERVER_PORT
              value: "9090"
            - name: MERIDIAN_ROUTER_AUTH_TOKEN_URI
              value: {{ $r.auth.tokenUri | quote }}
            - name: MERIDIAN_ROUTER_AUTH_SCOPE
              value: {{ $r.auth.scope | quote }}
            - name: MERIDIAN_ROUTER_AUTH_CLIENT_ID
              valueFrom: {secretKeyRef: {name: {{ $r.auth.clientSecret }}, key: client-id}}
            - name: MERIDIAN_ROUTER_AUTH_CLIENT_SECRET
              valueFrom: {secretKeyRef: {name: {{ $r.auth.clientSecret }}, key: client-secret}}
          resources:
            requests: {cpu: {{ .Values.resources.cpu | quote }}, memory: {{ .Values.resources.memory | quote }}}
            limits: {memory: {{ .Values.resources.memory | quote }}}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: /healthz, port: http}
            periodSeconds: 5
          lifecycle:
            preStop:
              sleep: {seconds: 10}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 128Mi}
{{- if .Values.autoscaling.enabled }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  scaleTargetRef: {apiVersion: apps/v1, kind: Deployment, name: cell-router}
  minReplicas: {{ .Values.replicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {type: Utilization, averageUtilization: {{ .Values.autoscaling.targetCpu }}}
{{- end }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels: {{- include "router.selector" . | nindent 6 }}
{{- if .Values.networkPolicy.enabled }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "router.selector" . | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    # the routed port from the load balancer / ingress; management (metrics, probes) only from monitoring
    - ports:
        - {port: http, protocol: TCP}
    {{- with .Values.networkPolicy.monitoringNamespace }}
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
      ports:
        - {port: management, protocol: TCP}
    {{- end }}
{{- end }}
{{- if .Values.serviceMonitor.enabled }}
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels: {{- include "router.selector" . | nindent 6 }}
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 15s
{{- end }}
{{- if .Values.ingress.enabled }}
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: cell-router
  labels: {{- include "router.labels" . | nindent 4 }}
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
              service: {name: cell-router, port: {name: http}}
{{- end }}
'''

ROUTER_NOTES = r'''Meridian cell router {{ .Chart.AppVersion }} installed as "{{ .Release.Name }}" ({{ .Values.region | default "no region set" }}).

Tenant registry: {{ .Values.router.registryUrl }}
Health check for the load balancer / Route 53: GET /healthz on the http port.
Required secret (not created by this chart):
  {{ .Values.router.auth.clientSecret }}  client-id/client-secret of the router's own client (meridian-cell-router:
                                scope platform:routing-read only, tokens of the platform-operator tenant; SEC-15)
Cell gateways must list the router's addresses in global.trustedProxies (SEC-16) to keep the client address.
{{- if .Values.router.cellOverrides }}
WARNING: break-glass cell overrides in force: {{ toJson .Values.router.cellOverrides }}
{{- end }}
'''

ROUTER_VALUES = """# Generated by tools/helm_chart.py; edit the generator, not this file. Override per region with -f values-<region>.yaml.
# The cell router is the regional entry tier: it resolves the tenant (JWT tenant_id claim; public callbacks:
# tenant_id parameter), looks up the tenant's cell in the tenant registry and proxies to that cell's edge gateway.
region: ""                       # cloud region of this router deployment, e.g. eu-west-1
image:
  registry: "ghcr.io/meridian"
  tag: ""                        # defaults to the chart appVersion
  pullPolicy: IfNotPresent
imagePullSecrets: []
replicas: 3                      # one per AZ at least: the router is the front door of every cell in the region
resources: {cpu: 500m, memory: 512Mi}
autoscaling: {enabled: true, maxReplicas: 12, targetCpu: 60}
pdb: {minAvailable: 2}
networkPolicy: {enabled: true, monitoringNamespace: monitoring}
serviceMonitor: {enabled: false}
service:
  type: ClusterIP                # LoadBalancer (NLB) when Route 53 points straight at the router
kafka:
  bootstrapServers: "meridian-kafka:9092"   # tenant-service events (placement / cell invalidations)
otel:
  endpoint: "http://otel-collector.monitoring:4318"
ingress:
  enabled: false
  className: ""
  host: "api.example.com"
  annotations: {}
  tls: []

router:
  registryUrl: ""                # tenant-service of the control plane, e.g. http://tenant-service.meridian-control.svc
  operatorTenant: ""             # SEC-15: platform-operator tenant id (registry reads are operator-only)
  auth:
    tokenUri: ""                 # OIDC token endpoint for the router's client credentials
    clientSecret: "meridian-cell-router-client"   # Secret with client-id / client-secret of the router's own client (realm client meridian-cell-router), never the shared meridian-service client
    scope: "platform:routing-read"   # the only scope the router needs: tenant routing and cell reads
  jwt:
    jwkSetUri: ""                # IdP JWKS used to verify callers' tokens
    issuer: ""
    audiences: ["meridian-api"]  # SEC-14
    tenantClaim: tenant_id
  cacheTtl: 30s                  # placement cache (events invalidate sooner)
  negativeTtl: 5s
  maxStale: 15m                  # keep routing from cache while the registry is unreachable
  cellsRefresh: 15s
  timeout: 30s
  retryAfter: 30s                # Retry-After of 503s (frozen tenants, cells down)
  maxBodySize: 64MB
  trustedProxies: []             # SEC-16: load balancer CIDRs in front of the router
  health:
    enabled: true
    interval: 5s
    probeTimeout: 2s
    path: /actuator/health/readiness
    failureThreshold: 3          # hysteresis: 3 failures to mark a cell unhealthy,
    successThreshold: 5          # 5 successes to trust it again
    autoFailover: false          # true only where standby cells are READ_ONLY warm standbys
  events:
    enabled: true
    topic: meridian.platform.v1
  cellOverrides: {}              # break-glass when the control plane is unreachable, e.g. {aws-euw1-c01: OFFLINE}
"""


def write_router_chart(app_version):
    (ROUTER_OUT / "templates").mkdir(parents=True, exist_ok=True)
    files = {
        "Chart.yaml": f"""apiVersion: v2
name: meridian-router
description: Meridian cell router - regional entry tier that routes each tenant to its cell (S20)
type: application
version: {CHART_VERSION}
appVersion: "{app_version}"
kubeVersion: ">=1.30.0-0"
""",
        "values.yaml": ROUTER_VALUES,
        "templates/_helpers.tpl": ROUTER_HELPERS,
        "templates/router.yaml": ROUTER_TPL,
        "templates/NOTES.txt": ROUTER_NOTES,
        ".helmignore": ".git/\n*.md.bak\n",
    }
    for name, text in files.items():
        (ROUTER_OUT / name).write_text(text, encoding="utf-8", newline="\n")


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
        '    audience: "meridian-api"          # SEC-14: required aud of every access token',
        '    platformOperatorTenant: ""        # SEC-15: tenant id of the platform operator (empty: service tokens only)',
        "  cell:                     # S20: identity of this cell in the tenant registry",
        '    id: ""                  # cell code, e.g. aws-euw1-c01 (empty: single-cell install)',
        '    region: ""              # cloud region, e.g. eu-west-1',
        "    role: primary           # primary | standby (warm DR standby: READ_ONLY in the registry until promoted)",
        "  trustedProxies: []        # SEC-16: CIDRs of the load balancer / ingress / cell router in front of the gateway",
        "  callbacks:                # SEC-17: provider callback signatures",
        "    acceptLegacySignatures: true   # deprecation period: set false once every provider signs v2",
        '    maxSkew: "5m"',
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
    write_router_chart(app_version)
    print(f"helm chart: cell router -> {ROUTER_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
