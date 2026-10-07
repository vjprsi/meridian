#!/usr/bin/env python3
"""Generates the Kubernetes Helm charts infra/helm/meridian and infra/helm/meridian-router.

infra/helm/meridian deploys a whole Meridian cell: every domain service plus the edge gateway, each with Deployment,
Service, ServiceAccount, HorizontalPodAutoscaler, PodDisruptionBudget, NetworkPolicy and (optionally) a
Prometheus ServiceMonitor, plus one schema-migration Job per service (Helm pre-install / pre-upgrade hook, the only
place the schema-owner credentials are mounted - GAP-22); global.cell.* names the cell (S20). infra/helm/meridian-router deploys the cell router, the
regional entry tier in front of the cells. Infrastructure (PostgreSQL, Kafka, Keycloak, OTel collector) is external and
referenced through values. Secrets are referenced by name, never rendered. Run after tools/generate.py.

GAP-02: the services that store, process or transmit card data (CDE_SERVICES) form the cardholder data environment:
values group `cde` (optional dedicated namespace, default-deny NetworkPolicies admitting only the callers found in the
code, optional default-deny egress, dedicated PostgreSQL host / credentials, card-vault key management via KMS / HSM).
"""
import json
import re
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
    "analytics-service": {"MERIDIAN_ANALYTICS_EMBED_SECRET": "embed-secret"},
    # GAP-07: PII pseudonym key of stored audit states; credentials of the Object Lock bucket for sealed daily batches
    "audit-service": {"MERIDIAN_AUDIT_DOWNLOAD_SIGNING_KEY": "download-signing-key",
                      "MERIDIAN_AUDIT_PSEUDONYM_KEY": "pseudonym-key",
                      "MERIDIAN_AUDIT_WORM_ACCESS_KEY": "worm-access-key",
                      "MERIDIAN_AUDIT_WORM_SECRET_KEY": "worm-secret-key"},
    "card-authorization-service": {"MERIDIAN_CARDS_AUTH_JIT_SECRET": "jit-secret"},
    "card-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret"},
    "card-threeds-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret",
                             "MERIDIAN_CARDS_THREEDS_CAVV_KEY": "cavv-key",
                             "MERIDIAN_CARDS_THREEDS_OTP_KEY": "otp-key"},
    "card-tokenization-service": {"MERIDIAN_CARDS_CALLBACK_SECRET": "callback-secret",
                                  "MERIDIAN_CARDS_TOKENS_OTP_KEY": "otp-key",
                                  "MERIDIAN_CARDS_TOKENS_PAYLOAD_KEY": "payload-key"},
    "card-vault-service": {"MERIDIAN_CARDS_VAULT_MASTER_KEY": "master-key"},
    # GAP-14: identifier hashes, OTP peppering and passkey challenges
    "customer-service": {"MERIDIAN_CUSTOMER_SECRET_KEY": "secret-key"},
    "document-service": {"MERIDIAN_ESIGN_CALLBACK_SECRET": "esign-callback-secret",
                         "MERIDIAN_DOCUMENTS_S3_ACCESS_KEY": "s3-access-key",
                         "MERIDIAN_DOCUMENTS_S3_SECRET_KEY": "s3-secret-key"},
    "file-gateway-service": {"MERIDIAN_FILES_SIGNING_KEY": "signing-key"},
    "fraud-service": {"MERIDIAN_FRAUD_PSEUDONYM_KEY": "pseudonym-key"},
    "identity-service": {"MERIDIAN_IDENTITY_MFA_SEED_KEY": "mfa-seed-key"},
    # RECON-REAL-REFS: counterparty account pseudonyms; the two Secrets must hold the same value
    "ledger-service": {"MERIDIAN_REFS_COUNTERPARTY_KEY": "counterparty-key"},
    "loan-origination-service": {"MERIDIAN_LOAN_ORIGINATION_CALLBACK_SECRET": "callback-secret"},
    "onboarding-service": {"MERIDIAN_ONBOARDING_PUBLIC_OTP_SECRET": "otp-secret",
                           "MERIDIAN_ONBOARDING_RESUME_TOKEN_SECRET": "resume-token-secret"},
    "reconciliation-service": {"MERIDIAN_REFS_COUNTERPARTY_KEY": "counterparty-key"},
    "routing-service": {"MERIDIAN_ROUTING_CALLBACK_SECRET": "callback-secret"},
    "statement-service": {"MERIDIAN_STATEMENT_DOWNLOAD_SECRET": "download-secret"},
    "webhook-service": {"MERIDIAN_WEBHOOKS_KEY": "encryption-key"},
}

# Non-secret per-service environment rendered into values.yaml (services[].env); override per environment.
SERVICE_ENV = {
    # GAP-07: sealed audit batches go to the cell's Object Lock bucket (infra/tofu cell output audit_bucket); set
    # MERIDIAN_AUDIT_WORM_ENDPOINT (https://s3.<region>.amazonaws.com), _BUCKET and _REGION per environment
    "audit-service": {"MERIDIAN_AUDIT_WORM_OBJECT_LOCK_MODE": "COMPLIANCE"},
}

# Secrets that several services MUST share, as {service: {ENV_VAR: (Secret name, key)}}: one Secret referenced by
# every consumer, so the values cannot drift apart (GAP-14: customer-service and search-service compute the same
# contact lookup hash only with the same key).
SHARED_SECRETS = {
    "customer-service": {"MERIDIAN_CONTACT_LOOKUP_KEY": ("meridian-contact-lookup", "key")},
    "search-service": {"MERIDIAN_CONTACT_LOOKUP_KEY": ("meridian-contact-lookup", "key")},
}

# GAP-02: cardholder data environment. Services that store (vault), process (PAN generation, PIN blocks) or transmit
# (authorisation SAD, 3DS / token-provisioning PANs) card data. card-clearing refuses PAN files and card-dispute sees
# card ids only: both stay outside.
CDE_SERVICES = ["card-vault-service", "card-service", "card-authorization-service", "card-tokenization-service",
                "card-threeds-service"]
# Callers that do not appear as a service-name literal in the caller's code: the GAP-08 data-lifecycle API (archive
# jobs, DSAR fan-out from customer-service). The vault is never archived or exported through it.
CDE_LIFECYCLE_CALLERS = ["archive-service", "customer-service"]
CDE_NO_LIFECYCLE = {"card-vault-service"}
# Name mentions that are not calls: the vault's access policy lists its callers, workflow-service maps approval resource
# types to their owning service (it never calls them).
CDE_NOT_CALLERS = {("card-vault-service", "*"), ("workflow-service", "card-service")}


def cde_callers(routes):
    """{CDE service: sorted callers}: every service whose main code or resources name the CDE service (ServiceClient
    targets are string literals; job packs and service lists are resources), the edge gateway when the service has
    routes in generated/routes.json (internal APIs have none, so the gateway never reaches the vault), and the
    data-lifecycle callers. Tested by tools/helm_chart.py itself: a caller missing here is a blocked call in the CDE."""
    found = {c: set() for c in CDE_SERVICES}
    for svc_dir in sorted((ROOT / "services").iterdir()):
        main = svc_dir / "src" / "main"
        if not main.is_dir():
            continue
        for f in main.rglob("*"):
            if f.suffix not in (".java", ".json", ".yml", ".yaml") or not f.is_file():
                continue
            text = f.read_text(encoding="utf-8", errors="ignore")
            if f.suffix != ".java":
                text = "\n".join(line.split("#", 1)[0] for line in text.splitlines())
            for cde in CDE_SERVICES:
                if (cde == svc_dir.name or (svc_dir.name, "*") in CDE_NOT_CALLERS
                        or (svc_dir.name, cde) in CDE_NOT_CALLERS):
                    continue
                pattern = (rf'"{re.escape(cde)}"' if f.suffix == ".java"
                           else rf'(?<![\w-]){re.escape(cde)}(?![\w-])')
                if re.search(pattern, text):
                    found[cde].add(svc_dir.name)
    routed = {r["service"] for r in routes}
    for cde in CDE_SERVICES:
        if cde in routed:
            found[cde].add("edge-gateway")
        if cde not in CDE_NO_LIFECYCLE:
            found[cde].update(CDE_LIFECYCLE_CALLERS)
    return {k: sorted(v) for k, v in found.items()}


def identity_env() -> dict:
    """GAP-16 (merged into services_values, kept apart from SERVICE_ENV): card-vault-service accepts tokens of the staff
    console and of the per-service clients of the services that call it (derived from the code by
    tools/service_access.py). While global.auth.sharedServiceClient is true, add the shared client to this list per
    environment (services[card-vault-service].env)."""
    import service_access
    callers = sorted(service_access.client_id(s["name"]) for s in service_access.manifest()
                     if "card-vault-service" in service_access.called_services(s["name"]))
    return {"card-vault-service": {"MERIDIAN_CARD_VAULT_ALLOWED_CLIENTS": ",".join(["meridian-console"] + callers)}}


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

{{- define "meridian.cdeMembers" -}}
{{- $m := list -}}
{{- range $svc := .Values.services -}}
{{- if and $svc.enabled (eq ($svc.zone | default "") "cde") -}}{{- $m = append $m $svc.name -}}{{- end -}}
{{- end -}}
{{- join ", " $m -}}
{{- end }}

{{- /* PLT-GITOPS: deployed environments pin every image by digest (global.image.digests, written by the promotion
pipeline, tools/release.py); a tag is only the fallback for local installs. requireDigests turns a missing digest into
a render error so a GitOps environment can never fall back to a mutable tag. */ -}}
{{- define "meridian.image" -}}
{{- $g := .root.Values.global.image -}}
{{- $digest := index ($g.digests | default dict) .name | default "" -}}
{{- if $digest -}}
{{ printf "%s/%s@%s" $g.registry .name $digest }}
{{- else if $g.requireDigests -}}
{{ fail (printf "global.image.digests.%s is required (global.image.requireDigests=true): pin the image by digest" .name) }}
{{- else -}}
{{ printf "%s/%s:%s" $g.registry .name ($g.tag | default .root.Chart.AppVersion) }}
{{- end -}}
{{- end }}
'''

SERVICES_TPL = r'''{{- $root := . -}}
{{- $g := .Values.global -}}
{{- range $svc := .Values.services }}
{{- if $svc.enabled }}
{{- $ctx := dict "root" $root "name" $svc.name "domain" $svc.domain }}
{{- $replicas := $svc.replicas | default $g.replicas }}
{{- /* GAP-02: CDE members may live in their own namespace and use the CDE database host and credentials */ -}}
{{- $cde := and $root.Values.cde.enabled (eq ($svc.zone | default "") "cde") }}
{{- $ns := ternary ($root.Values.cde.namespace | default "") "" $cde }}
{{- $pgHost := ternary ($root.Values.cde.postgres.host | default $g.postgres.host) $g.postgres.host $cde }}
{{- $pgPort := ternary ($root.Values.cde.postgres.port | default $g.postgres.port) $g.postgres.port $cde }}
{{- $appSecret := ternary $root.Values.cde.postgres.appSecret $g.postgres.appSecret $cde }}
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
  {{- if and $svc.keyManagement $root.Values.cde.keys.serviceAccountRoleArn }}
  annotations:
    # IRSA: CDE KMS key use. One role per service account: the CDE vault role must also carry the vault's
    # MSK permissions when global.kafka.iam is on (backlog: merge kafka-acls.json grants into the CDE role)
    eks.amazonaws.com/role-arn: {{ $root.Values.cde.keys.serviceAccountRoleArn | quote }}
  {{- else if and $g.kafka.iam.enabled (ne (toString $svc.serviceIdentity) "false") }}
  annotations:
    # GAP-16: the service's own MSK principal (IAM role, IRSA): infra/tofu cell output kafka_service_role_arns
    eks.amazonaws.com/role-arn: {{ replace "{name}" $svc.name $g.kafka.iam.roleArnTemplate | quote }}
  {{- end }}
automountServiceAccountToken: false
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  type: ClusterIP
  selector: {{- include "meridian.selector" $ctx | nindent 4 }}
  ports:
    - name: http
      port: 80
      targetPort: http
    {{- if $svc.managementPort }}
    - name: management
      port: {{ $svc.managementPort }}
      targetPort: management
    {{- end }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
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
      labels:
        {{- include "meridian.labels" $ctx | nindent 8 }}
        {{- if $cde }}
        meridian.io/zone: cde
        {{- end }}
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
            {{- if $svc.managementPort }}
            - name: management
              containerPort: {{ $svc.managementPort }}
            {{- end }}
          envFrom:
            - configMapRef:
                name: meridian-common
          env:
            - name: SERVER_PORT
              value: "8080"
            {{- if $svc.database }}
            - name: SPRING_DATASOURCE_URL
              value: {{ printf "jdbc:postgresql://%s:%v/%s?currentSchema=%s&sslmode=%s" $pgHost $pgPort $svc.database $svc.schema $g.postgres.sslMode | quote }}
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom: {secretKeyRef: {name: {{ $appSecret }}, key: username}}
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom: {secretKeyRef: {name: {{ $appSecret }}, key: password}}
            # GAP-22: the long-lived pod holds the runtime role only; migrations run as the schema owner in the
            # pre-install / pre-upgrade Job (templates/migrations.yaml), never in the Deployment
            - name: SPRING_FLYWAY_ENABLED
              value: "false"
            - name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
              value: {{ $svc.dbPool | default $g.postgres.poolSize | quote }}
            {{- end }}
            {{- if ne (toString $svc.serviceIdentity) "false" }}
            # GAP-16: the service's own client svc-<service> (Secret meridian-<service>-client); the legacy shared client
            # only while global.auth.sharedServiceClient is true (migration)
            {{- $client := $svc.clientSecret | default (ternary $g.auth.serviceClientSecret (printf "meridian-%s-client" $svc.name) $g.auth.sharedServiceClient) }}
            - name: MERIDIAN_SERVICE_AUTH_CLIENT_ID
              valueFrom: {secretKeyRef: {name: {{ $client }}, key: client-id}}
            - name: MERIDIAN_SERVICE_AUTH_CLIENT_SECRET
              valueFrom: {secretKeyRef: {name: {{ $client }}, key: client-secret}}
            {{- end }}
            - name: MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_SERVICE_NAME
              value: {{ $svc.name }}
            {{- if $svc.managementPort }}
            - name: MANAGEMENT_SERVER_PORT          # actuator (probes, /actuator/prometheus) on its own port
              value: {{ $svc.managementPort | quote }}
            {{- end }}
            {{- range $envName, $key := $svc.secretEnv }}
            - name: {{ $envName }}
              valueFrom: {secretKeyRef: {name: {{ $svc.secretName | default (printf "meridian-%s-secrets" $svc.name) }}, key: {{ $key }}}}
            {{- end }}
            {{- range $envName, $ref := $svc.sharedSecretEnv }}
            - name: {{ $envName }}
              valueFrom: {secretKeyRef: {name: {{ $ref.secret }}, key: {{ $ref.key }}}}
            {{- end }}
            {{- range $k, $v := $svc.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            {{- if $svc.keyManagement }}
            {{- $keys := $root.Values.cde.keys }}
            # GAP-01: card key management (software | aws-kms | pkcs11)
            - name: MERIDIAN_CARDS_VAULT_KEY_MANAGEMENT
              value: {{ $keys.mode | quote }}
            {{- if eq $keys.mode "aws-kms" }}
            - name: MERIDIAN_CARDS_VAULT_KMS_KEY_ID
              value: {{ required "cde.keys.kmsKeyId is required with cde.keys.mode=aws-kms" $keys.kmsKeyId | quote }}
            - name: MERIDIAN_CARDS_VAULT_KMS_REGION
              value: {{ $keys.kmsRegion | default $g.cell.region | quote }}
            {{- with $keys.kmsEndpoint }}
            - name: MERIDIAN_CARDS_VAULT_KMS_ENDPOINT
              value: {{ . | quote }}
            {{- end }}
            {{- end }}
            {{- if eq $keys.mode "pkcs11" }}
            - name: MERIDIAN_CARDS_VAULT_PKCS11_CONFIG
              value: /etc/meridian/pkcs11/pkcs11.cfg
            - name: MERIDIAN_CARDS_VAULT_PKCS11_PIN
              valueFrom: {secretKeyRef: {name: {{ $keys.pkcs11.pinSecret }}, key: pin}}
            {{- end }}
            {{- end }}
            {{- if and $g.auth.idpEvents.enabled (eq $svc.name "audit-service") }}
            # GAP-06: read-only Keycloak client meridian-audit-events (view-events) for the IdP events poller
            - name: MERIDIAN_AUDIT_KEYCLOAK_CLIENT_ID
              valueFrom: {secretKeyRef: {name: {{ $g.auth.idpEvents.clientSecret }}, key: client-id}}
            - name: MERIDIAN_AUDIT_KEYCLOAK_CLIENT_SECRET
              valueFrom: {secretKeyRef: {name: {{ $g.auth.idpEvents.clientSecret }}, key: client-secret}}
            {{- end }}
          resources:
            requests: {cpu: {{ $svc.cpu | default $g.resources.cpu | quote }}, memory: {{ $svc.memory | default $g.resources.memory | quote }}}
            limits: {memory: {{ $svc.memory | default $g.resources.memory | quote }}}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: {{ if $svc.managementPort }}management{{ else }}http{{ end }}}
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: {{ if $svc.managementPort }}management{{ else }}http{{ end }}}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: {{ if $svc.managementPort }}management{{ else }}http{{ end }}}
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
            {{- if and $svc.keyManagement (eq $root.Values.cde.keys.mode "pkcs11") }}
            - {name: pkcs11, mountPath: /etc/meridian/pkcs11, readOnly: true}
            {{- end }}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 256Mi}
        {{- if and $svc.keyManagement (eq $root.Values.cde.keys.mode "pkcs11") }}
        - name: pkcs11
          configMap: {name: {{ required "cde.keys.pkcs11.configMap is required with cde.keys.mode=pkcs11" $root.Values.cde.keys.pkcs11.configMap }}}
        {{- end }}
{{- if $g.autoscaling.enabled }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
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
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  minAvailable: 1
  selector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
{{- end }}
{{- if and $g.networkPolicy.enabled $cde }}
---
# GAP-02: CDE member - default deny; only the named callers (found in the code by tools/helm_chart.py) and the
# metrics scraper may connect; egress denied too when cde.egress.enabled (DNS, Meridian pods and allowedCidrs only)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
  policyTypes: [Ingress{{ if $root.Values.cde.egress.enabled }}, Egress{{ end }}]
  ingress:
    {{- if $svc.allowedCallers }}
    - from:
        {{- range $caller := $svc.allowedCallers }}
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: {{ $caller.name }}
              app.kubernetes.io/instance: {{ $root.Release.Name }}
          {{- if $ns }}
          namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ ternary $ns $root.Release.Namespace (eq ($caller.zone | default "") "cde") }}
          {{- end }}
        {{- end }}
      ports:
        - {port: http, protocol: TCP}
    {{- end }}
    {{- with $g.networkPolicy.monitoringNamespace }}
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
      ports:
        - {port: {{ if $svc.managementPort }}management{{ else }}http{{ end }}, protocol: TCP}
    {{- end }}
  {{- if $root.Values.cde.egress.enabled }}
  egress:
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels: {k8s-app: kube-dns}
      ports:
        - {port: 53, protocol: UDP}
        - {port: 53, protocol: TCP}
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              app.kubernetes.io/part-of: meridian
              app.kubernetes.io/instance: {{ $root.Release.Name }}
    {{- with $g.networkPolicy.monitoringNamespace }}
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
    {{- end }}
    {{- with $root.Values.cde.egress.allowedCidrs }}
    - to:
        {{- range . }}
        - ipBlock: {cidr: {{ . }}}
        {{- end }}
    {{- end }}
  {{- end }}
{{- end }}
{{- if and $g.networkPolicy.enabled (not $cde) }}
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
        {{- if and $root.Values.cde.enabled $root.Values.cde.namespace }}
        # GAP-02: CDE members in their own namespace call ledger, fraud, notification ...
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $root.Values.cde.namespace }}
          podSelector:
            matchLabels:
              app.kubernetes.io/part-of: meridian
        {{- end }}
        {{- with $g.networkPolicy.monitoringNamespace }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
        {{- end }}
      ports:
        - {port: http, protocol: TCP}
    {{- if $svc.managementPort }}
    # management port (metrics, probes): only the metrics scraper (kubelet probes are not subject to policies)
    {{- with $g.networkPolicy.monitoringNamespace }}
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
      ports:
        - {port: management, protocol: TCP}
    {{- end }}
    {{- end }}
{{- end }}
{{- if $g.serviceMonitor.enabled }}
---
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ $svc.name }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels: {{- include "meridian.labels" $ctx | nindent 4 }}
spec:
  selector:
    matchLabels: {{- include "meridian.selector" $ctx | nindent 6 }}
  endpoints:
    - port: {{ if $svc.managementPort }}management{{ else }}http{{ end }}
      path: /actuator/prometheus
      interval: 30s
{{- end }}
{{- end }}
{{- end }}
'''

MIGRATIONS_TPL = r'''{{- /*
GAP-22: schema migrations run as the schema owner in one Job per service, before the Deployments are created or
updated (Helm hooks pre-install / pre-upgrade). The Job starts the service image in migrate-only mode
(MERIDIAN_MIGRATE_ONLY=true: Flyway, then exit; no web server, Kafka or schedulers), so the owner credentials
(global.postgres.migrationSecret) are mounted here and nowhere else. A failed Job fails the release before any pod
is replaced. The Job pod carries its own name label, so the service's Service never selects it. A standby cell
never migrates: its database is a read-only replica of the primary, which already ran the same migrations.
*/ -}}
{{- $root := . -}}
{{- $g := .Values.global -}}
{{- if and $g.migrations.enabled (ne $g.cell.role "standby") }}
{{- range $svc := .Values.services }}
{{- if and $svc.enabled $svc.database }}
{{- $ctx := dict "root" $root "name" (printf "%s-migrate" $svc.name) "domain" $svc.domain }}
{{- $cde := and $root.Values.cde.enabled (eq ($svc.zone | default "") "cde") }}
{{- $ns := ternary ($root.Values.cde.namespace | default "") "" $cde }}
{{- $pgHost := ternary ($root.Values.cde.postgres.host | default $g.postgres.host) $g.postgres.host $cde }}
{{- $pgPort := ternary ($root.Values.cde.postgres.port | default $g.postgres.port) $g.postgres.port $cde }}
{{- $ownerSecret := ternary $root.Values.cde.postgres.migrationSecret $g.postgres.migrationSecret $cde }}
---
apiVersion: batch/v1
kind: Job
metadata:
  name: {{ printf "%s-migrate" $svc.name | trunc 63 | trimSuffix "-" }}
  {{- with $ns }}
  namespace: {{ . }}
  {{- end }}
  labels:
    {{- include "meridian.labels" $ctx | nindent 4 }}
    app.kubernetes.io/component: db-migration
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade
    "helm.sh/hook-weight": "-10"
    "helm.sh/hook-delete-policy": before-hook-creation,hook-succeeded
spec:
  backoffLimit: {{ $g.migrations.backoffLimit }}
  activeDeadlineSeconds: {{ $g.migrations.activeDeadlineSeconds }}
  ttlSecondsAfterFinished: {{ $g.migrations.ttlSecondsAfterFinished }}
  template:
    metadata:
      labels:
        {{- include "meridian.labels" $ctx | nindent 8 }}
        app.kubernetes.io/component: db-migration
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile: {type: RuntimeDefault}
      {{- with $g.imagePullSecrets }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: migrate
          image: {{ include "meridian.image" (dict "root" $root "name" $svc.name) }}
          imagePullPolicy: {{ $g.image.pullPolicy }}
          args: ["migrate"]
          env:
            - name: MERIDIAN_MIGRATE_ONLY
              value: "true"
            - name: SPRING_DATASOURCE_URL
              value: {{ printf "jdbc:postgresql://%s:%v/%s?currentSchema=%s&sslmode=%s" $pgHost $pgPort $svc.database $svc.schema $g.postgres.sslMode | quote }}
            - name: SPRING_FLYWAY_USER
              valueFrom: {secretKeyRef: {name: {{ $ownerSecret }}, key: username}}
            - name: SPRING_FLYWAY_PASSWORD
              valueFrom: {secretKeyRef: {name: {{ $ownerSecret }}, key: password}}
          resources:
            requests: {cpu: {{ $g.migrations.resources.cpu | quote }}, memory: {{ $g.migrations.resources.memory | quote }}}
            limits: {memory: {{ $g.migrations.resources.memory | quote }}}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 64Mi}
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
  {{- if $g.kafka.iam.enabled }}
  {{- if not (contains "{name}" $g.kafka.iam.roleArnTemplate) }}
  {{- fail "global.kafka.iam.roleArnTemplate must contain {name} (infra/tofu cell output kafka_service_role_arns)" }}
  {{- end }}
  # GAP-16: every service authenticates to MSK as its own IAM principal (IRSA role of its service account); topic and
  # group permissions come from tools/kafka_acls.py (infra/tofu/modules/cell/kafka_iam.tf)
  SPRING_KAFKA_SECURITY_PROTOCOL: "SASL_SSL"
  SPRING_KAFKA_PROPERTIES_SASL_MECHANISM: "AWS_MSK_IAM"
  SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG: "software.amazon.msk.auth.iam.IAMLoginModule required;"
  SPRING_KAFKA_PROPERTIES_SASL_CLIENT_CALLBACK_HANDLER_CLASS: "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
  {{- end }}
  # GAP-16: shared service clients whose X-Meridian-Service header is still believed (migration only; empty = none)
  MERIDIAN_SECURITY_LEGACY_SERVICE_CLIENTS: {{ join "," $g.auth.legacyServiceClients | quote }}
  # GAP-13: minimum acr of user tokens using meridian:admin / platform:operator
  MERIDIAN_SECURITY_PRIVILEGED_ACR: {{ $g.auth.privilegedAcr | quote }}
  # GAP-06: application logs as ECS JSON lines on stdout (request_id, tenant_id, principal, trace ids; no PII)
  MERIDIAN_LOGGING_FORMAT: {{ $g.logging.format | quote }}
  {{- if $g.auth.idpEvents.enabled }}
  {{- if not $g.auth.platformOperatorTenant }}
  {{- fail "global.auth.idpEvents needs global.auth.platformOperatorTenant (IdP events are audited in that tenant)" }}
  {{- end }}
  # GAP-06: audit-service copies Keycloak sign-in / admin events into the audit trail (client meridian-audit-events)
  MERIDIAN_AUDIT_KEYCLOAK_ENABLED: "true"
  MERIDIAN_AUDIT_KEYCLOAK_URL: {{ regexReplaceAll "/realms/[^/]+/?$" $g.auth.issuerUri "" | quote }}
  MERIDIAN_AUDIT_KEYCLOAK_REALM: {{ regexReplaceAll "^.*/realms/([^/]+)/?$" $g.auth.issuerUri "${1}" | quote }}
  MERIDIAN_AUDIT_KEYCLOAK_TENANT: {{ $g.auth.platformOperatorTenant | quote }}
  {{- end }}
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
  # MR-GAME-DAY: a warm standby (role standby) has a read-only replica database and replicated topics, so its
  # services start with the outbox relay paused and every Kafka listener held (libs/meridian-events StandbyGate,
  # driven by MERIDIAN_CELL_ROLE). Promotion happens in place, without a rolling restart: each service promotes itself
  # when the registry shows this cell ACTIVE (POST /cells/{id}/online), or the operator calls
  # POST /v1/internal/ops/cell-role/promote (tools/gameday/promote_cell.py); it waits for a writable database,
  # re-publishes the last replayOnPromote of relayed events (consumers de-duplicate by ce_id), then resumes.
  MERIDIAN_EVENTS_RETENTION_WINDOW: {{ $g.outbox.retention | quote }}
  MERIDIAN_CELL_PROMOTION_WATCH_REGISTRY: {{ $g.cell.promotion.watchRegistry | quote }}
  MERIDIAN_CELL_PROMOTION_POLL_INTERVAL: {{ $g.cell.promotion.pollInterval | quote }}
  MERIDIAN_CELL_PROMOTION_DB_WAIT: {{ $g.cell.promotion.dbWait | quote }}
  {{- if eq $g.cell.role "standby" }}
  MERIDIAN_EVENTS_RETENTION_REPLAY_ON_PROMOTE: {{ $g.cell.promotion.replayOnPromote | quote }}
  {{- with $g.cell.promotion.registryUrl }}
  MERIDIAN_SERVICES_TENANT_SERVICE_URL: {{ . | quote }}
  {{- end }}
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

# GAP-02: CDE namespace plumbing. With cde.namespace set, the CDE members run there: the namespace gets its own copy of
# the common configuration (service URLs pointing back into the release namespace), a default-deny policy for every
# pod in it, and the release namespace gets ExternalName services so callers keep using http://<service>.
CDE_TPL = r'''{{- $c := .Values.cde -}}
{{- if and $c.enabled $c.namespace }}
{{- if eq $c.namespace .Release.Namespace }}{{ fail "cde.namespace must differ from the release namespace (or be empty)" }}{{ end }}
{{- $cm := include (print $.Template.BasePath "/config.yaml") . | fromYaml }}
{{- $data := $cm.data }}
{{- $_ := set $data "MERIDIAN_SERVICES_URL_TEMPLATE" (printf "http://{name}.%s.svc.cluster.local" .Release.Namespace) }}
{{- $_ := set $data "MERIDIAN_GATEWAY_URL_TEMPLATE" (printf "http://{name}.%s.svc.cluster.local" .Release.Namespace) }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: meridian-common
  namespace: {{ $c.namespace }}
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ .Release.Name }}
    meridian.io/zone: cde
data:
  {{- range $k, $v := $data }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
{{- if .Values.global.networkPolicy.enabled }}
---
# default deny for everything in the CDE namespace; the per-service policies open the named callers only
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: meridian-cde-default-deny
  namespace: {{ $c.namespace }}
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ .Release.Name }}
    meridian.io/zone: cde
spec:
  podSelector: {}
  policyTypes: [Ingress{{ if $c.egress.enabled }}, Egress{{ end }}]
{{- end }}
{{- range $svc := .Values.services }}
{{- if and $svc.enabled (eq ($svc.zone | default "") "cde") }}
---
# callers in the release namespace reach {{ $svc.name }} under its usual name
apiVersion: v1
kind: Service
metadata:
  name: {{ $svc.name }}
  labels:
    app.kubernetes.io/name: {{ $svc.name }}
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ $.Release.Name }}
    meridian.io/zone: cde
spec:
  type: ExternalName
  externalName: {{ printf "%s.%s.svc.cluster.local" $svc.name $c.namespace }}
  ports:
    - name: http
      port: 80
{{- end }}
{{- end }}
{{- end }}
'''

# GAP-10: rules and dashboards from tools/observability.py, copied into the chart's files/observability/
OBSERVABILITY_TPL = r'''{{- $o := .Values.observability -}}
{{- if $o.prometheusRule.enabled }}
{{- range $path, $_ := .Files.Glob "files/observability/rules/*.yml" }}
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: {{ base $path | trimSuffix ".rules.yml" }}
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ $.Release.Name }}
    meridian.io/cell: {{ $.Values.global.cell.id | default "unassigned" | quote }}
    {{- with $o.prometheusRule.labels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
{{- $.Files.Get $path | nindent 2 }}
{{- end }}
{{- end }}
{{- if $o.dashboards.enabled }}
{{- range $path, $_ := .Files.Glob "files/observability/dashboards/*.json" }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ base $path | trimSuffix ".json" }}-dashboard
  labels:
    app.kubernetes.io/part-of: meridian
    app.kubernetes.io/instance: {{ $.Release.Name }}
    {{- with $o.dashboards.labels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  annotations:
    {{- with $o.dashboards.folder }}
    grafana_folder: {{ . | quote }}
    {{- end }}
data:
  {{ base $path }}: |-
{{- $.Files.Get $path | nindent 4 }}
{{- end }}
{{- end }}
'''

NOTES = r'''Meridian {{ .Chart.AppVersion }} installed as "{{ .Release.Name }}" in namespace {{ .Release.Namespace }}.
Cell: {{ .Values.global.cell.id | default "(single-cell install)" }} {{ .Values.global.cell.region }} ({{ .Values.global.cell.role }})

Components: {{ len .Values.services }}
API: {{ if .Values.ingress.enabled }}https://{{ .Values.ingress.host }}{{ else }}kubectl port-forward svc/edge-gateway 18000:80{{ end }}

Required secrets in the namespace (not created by this chart):
  {{ .Values.global.postgres.appSecret }}        username/password of the runtime role (RLS-bound, no DDL)
  {{ .Values.global.postgres.migrationSecret }}  username/password of the schema owner, mounted only by the
      <service>-migrate Jobs (Helm pre-install / pre-upgrade hooks), never by a running Deployment (GAP-22)
{{- if .Values.global.auth.sharedServiceClient }}
  {{ .Values.global.auth.serviceClientSecret }}  client-id/client-secret of the LEGACY shared service client (migration)
{{- else }}
  meridian-<service>-client  client-id/client-secret of each service's own client svc-<service> (GAP-16; infra/tofu
      cell output service_client_secret_arns)
{{- end }}
{{- range $svc := .Values.services }}
{{- if and $svc.enabled $svc.secretEnv }}
  {{ $svc.secretName | default (printf "meridian-%s-secrets" $svc.name) }}  keys: {{ join ", " (values $svc.secretEnv | sortAlpha) }}
{{- end }}
{{- end }}
{{- $shared := dict }}
{{- range $svc := .Values.services }}
{{- if $svc.enabled }}
{{- range $envName, $ref := $svc.sharedSecretEnv }}
{{- $k := printf "%s  key: %s" $ref.secret $ref.key }}
{{- $_ := set $shared $k (append (get $shared $k | default list) $svc.name) }}
{{- end }}
{{- end }}
{{- end }}
{{- range $k, $users := $shared }}
  {{ $k }}  (one Secret shared by {{ join ", " $users }})
{{- end }}
{{- if .Values.global.auth.idpEvents.enabled }}
  {{ .Values.global.auth.idpEvents.clientSecret }}  client-id/client-secret of meridian-audit-events (GAP-06)
{{- end }}
Secret values must be random (>= 32 characters; the card-vault master key 32 random bytes, base64) and come from the
KMS / vault: services refuse to start when one is missing or a committed development default.
{{- if .Values.cde.enabled }}

Cardholder data environment (GAP-02): namespace {{ .Values.cde.namespace | default .Release.Namespace }}
  members: {{ include "meridian.cdeMembers" . }}
  {{ .Values.cde.postgres.appSecret }} / {{ .Values.cde.postgres.migrationSecret }}  CDE database credentials (runtime role /
      schema owner){{ with .Values.cde.postgres.host }} on the dedicated CDE cluster {{ . }}{{ end }}
  Secrets and the service-client Secret must exist in the CDE namespace too when it is separate.
  card key management: {{ .Values.cde.keys.mode }}{{ if eq .Values.cde.keys.mode "software" }} (development only: use aws-kms or pkcs11 for card data, GAP-01){{ end }}
{{- end }}
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

{{- /* PLT-GITOPS: image.digest, else global.image.digests.cell-router (the release file shared with the meridian chart,
deploy/<env>/images.yaml), else the tag; requireDigest fails the render when no digest is set. */ -}}
{{- define "router.image" -}}
{{- $i := .Values.image -}}
{{- $gi := (.Values.global | default dict).image | default dict -}}
{{- $digest := $i.digest | default (index ($gi.digests | default dict) "cell-router") | default "" -}}
{{- if $digest -}}
{{ printf "%s/cell-router@%s" $i.registry $digest }}
{{- else if $i.requireDigest -}}
{{ fail "image.digest (or global.image.digests.cell-router) is required (image.requireDigest=true): pin the image by digest" }}
{{- else -}}
{{ printf "%s/cell-router:%s" $i.registry ($i.tag | default .Chart.AppVersion) }}
{{- end -}}
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
          image: {{ include "router.image" . }}
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

DATA_MOVER_TPL = r'''{{- /* MR-GAME-DAY: data mover for live tenant moves (platform/data-mover). Regional like the router: it reads
the source cell and writes the target cell through the services' internal data-lifecycle move API, so it needs network
reach to both cells' services (private connectivity) and their document buckets. */ -}}
{{- $m := .Values.dataMover -}}
{{- if $m.enabled }}
{{- if not $m.database.url }}{{ fail "dataMover.database.url (the mover's progress database) is required" }}{{ end }}
{{- $gi := (.Values.global | default dict).image | default dict -}}
{{- $digest := $m.image.digest | default (index ($gi.digests | default dict) "data-mover") | default "" -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: data-mover
  labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
automountServiceAccountToken: false
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: data-mover
  labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
data:
  SPRING_KAFKA_BOOTSTRAP_SERVERS: {{ .Values.kafka.bootstrapServers | quote }}
  SPRING_DATASOURCE_URL: {{ $m.database.url | quote }}
  MANAGEMENT_OTLP_METRICS_EXPORT_URL: {{ printf "%s/v1/metrics" .Values.otel.endpoint | quote }}
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_SERVICE_NAME: "data-mover"
  MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_CLOUD_REGION: {{ .Values.region | quote }}
  # meridian.mover.* (cells: services URL template and document bucket per cell code; throttles; lease)
  SPRING_APPLICATION_JSON: {{ dict "meridian" (dict "mover" (dict "registryUrl" ($m.registryUrl | default .Values.router.registryUrl) "operatorTenant" ($m.operatorTenant | default .Values.router.operatorTenant) "cells" $m.cells "rowsPerSecond" $m.rowsPerSecond "objectsPerSecond" $m.objectsPerSecond "pageSize" $m.pageSize "skipServices" $m.skipServices)) | toJson | quote }}
---
apiVersion: v1
kind: Service
metadata:
  name: data-mover
  labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
spec:
  selector: {app.kubernetes.io/name: data-mover, app.kubernetes.io/instance: {{ .Release.Name }}}
  ports:
    - {name: management, port: 9090, targetPort: management}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-mover
  labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
spec:
  replicas: {{ $m.replicas }}
  revisionHistoryLimit: 5
  selector:
    matchLabels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/instance: {{ .Release.Name }}}
  template:
    metadata:
      labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
      annotations:
        checksum/config: {{ toJson $m | sha256sum }}
    spec:
      serviceAccountName: data-mover
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: 60
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile: {type: RuntimeDefault}
      containers:
        - name: app
          {{- if $digest }}
          image: {{ printf "%s/data-mover@%s" .Values.image.registry $digest }}
          {{- else if .Values.image.requireDigest }}
          {{- fail "dataMover.image.digest (or global.image.digests.data-mover) is required (image.requireDigest=true)" }}
          {{- else }}
          image: {{ printf "%s/data-mover:%s" .Values.image.registry (.Values.image.tag | default .Chart.AppVersion) }}
          {{- end }}
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - {name: management, containerPort: 9090}
          envFrom:
            - configMapRef:
                name: data-mover
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: MANAGEMENT_SERVER_PORT
              value: "9090"
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom: {secretKeyRef: {name: {{ $m.database.secret }}, key: username}}
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom: {secretKeyRef: {name: {{ $m.database.secret }}, key: password}}
            - name: MERIDIAN_MOVER_AUTH_TOKEN_URI
              value: {{ $m.auth.tokenUri | quote }}
            - name: MERIDIAN_MOVER_AUTH_CLIENT_ID
              valueFrom: {secretKeyRef: {name: {{ $m.auth.clientSecret }}, key: client-id}}
            - name: MERIDIAN_MOVER_AUTH_CLIENT_SECRET
              valueFrom: {secretKeyRef: {name: {{ $m.auth.clientSecret }}, key: client-secret}}
            - name: MERIDIAN_MOVER_S3_CREDENTIALS_ACCESS_KEY
              valueFrom: {secretKeyRef: {name: {{ $m.s3Secret }}, key: access-key, optional: true}}
            - name: MERIDIAN_MOVER_S3_CREDENTIALS_SECRET_KEY
              valueFrom: {secretKeyRef: {name: {{ $m.s3Secret }}, key: secret-key, optional: true}}
          resources:
            requests: {cpu: {{ $m.resources.cpu | quote }}, memory: {{ $m.resources.memory | quote }}}
            limits: {memory: {{ $m.resources.memory | quote }}}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 5
            failureThreshold: 60
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: management}
            periodSeconds: 10
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 128Mi}
{{- if .Values.networkPolicy.enabled }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: data-mover
  labels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/part-of: meridian, app.kubernetes.io/instance: {{ .Release.Name }}}
spec:
  podSelector:
    matchLabels: {app.kubernetes.io/name: data-mover, app.kubernetes.io/instance: {{ .Release.Name }}}
  policyTypes: [Ingress]
  ingress:
    {{- with .Values.networkPolicy.monitoringNamespace }}
    # no routed API: management (metrics, /actuator/moves) only from monitoring / operators
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ . }}
      ports:
        - {port: management, protocol: TCP}
    {{- end }}
{{- end }}
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
  tag: ""                        # defaults to the chart appVersion (used only without a digest)
  digest: ""                     # sha256:... (PLT-GITOPS; default: global.image.digests.cell-router)
  requireDigest: false           # true in GitOps environments: no digest = render error, never a mutable tag
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

# MR-GAME-DAY: data mover for live tenant moves (consumes platform.tenant_move.copy_requested; docs/MULTI_REGION.md)
dataMover:
  enabled: false
  replicas: 2                    # any instance resumes a run whose lease expired
  image: {digest: ""}            # default: global.image.digests.data-mover
  resources: {cpu: 500m, memory: 768Mi}
  database:
    url: ""                      # its own small progress database, e.g. jdbc:postgresql://<aurora>:5432/mover_db
    secret: "meridian-data-mover-db"     # username / password
  auth:
    tokenUri: ""                 # OIDC token endpoint
    clientSecret: "meridian-data-mover-client"   # client-id / client-secret of svc-data-mover (meridian:internal, platform:operator)
  s3Secret: "meridian-data-mover-s3"    # optional access-key / secret-key used for the cells' document buckets
  registryUrl: ""                # default: router.registryUrl (confirm-copy)
  operatorTenant: ""             # default: router.operatorTenant
  rowsPerSecond: 5000            # throttle per instance (copy and verification reads)
  objectsPerSecond: 50
  pageSize: 1000
  skipServices: [tenant-service]
  cells: {}                      # per cell code, e.g. aws-euw1-c01: {servicesUrlTemplate: "http://{name}.meridian.svc.cluster.local",
                                 #   s3: {endpoint: "https://s3.eu-west-1.amazonaws.com", bucket: "...", region: eu-west-1}}
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
        "templates/data-mover.yaml": DATA_MOVER_TPL,
        "templates/NOTES.txt": ROUTER_NOTES,
        ".helmignore": ".git/\n*.md.bak\n",
    }
    for name, text in files.items():
        (ROUTER_OUT / name).write_text(text, encoding="utf-8", newline="\n")


def services_values(manifest, callers=None):
    callers = callers or {}
    rows = []
    identity = identity_env()
    for s in manifest:
        r = {"name": s["name"], "domain": s["domain"], "enabled": True,
             "database": s.get("database"), "schema": s.get("schema")}
        if s["name"] in HOT:
            rep, cpu, mem = HOT[s["name"]]
            r.update({"replicas": rep, "cpu": cpu, "memory": mem})
        if s["name"] in SERVICE_SECRETS:
            r["secretName"] = f"meridian-{s['name']}-secrets"
            r["secretEnv"] = dict(sorted(SERVICE_SECRETS[s["name"]].items()))
        if s["name"] in SHARED_SECRETS:
            r["sharedSecretEnv"] = dict(sorted(SHARED_SECRETS[s["name"]].items()))
        if s["name"] in SERVICE_ENV:
            r["env"] = dict(sorted(SERVICE_ENV[s["name"]].items()))
        if s["name"] in CDE_SERVICES:
            r["zone"] = "cde"
            r["allowedCallers"] = [{"name": c, "zone": "cde" if c in CDE_SERVICES else ""}
                                   for c in callers.get(s["name"], [])]
        if s["name"] == "card-vault-service":
            r["keyManagement"] = True
        if s["name"] in identity:
            r["env"] = dict(sorted({**r.get("env", {}), **identity[s["name"]]}.items()))
        rows.append(r)
    rows.append({"name": "edge-gateway", "domain": "platform", "enabled": True, "database": None, "schema": None,
                 "replicas": 3, "cpu": "500m", "memory": "512Mi",
                 # SEC-31 / GAP-10: actuator (probes, /actuator/prometheus) never on the port behind the ingress
                 "managementPort": 9090,
                 # GAP-16: proxies with the caller's token, never calls as itself and uses no Kafka
                 "serviceIdentity": False})
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
        '    tag: ""                # defaults to the chart appVersion (used only for images without a digest)',
        "    digests: {}            # PLT-GITOPS: component -> sha256:... (deploy/<env>/images.yaml, tools/release.py)",
        "    requireDigests: false  # true in GitOps environments: a component without a digest fails the render",
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
        '    migrationSecret: "meridian-db-owner"   # schema owner: mounted only by the migration Jobs (GAP-22)',
        "  migrations:               # GAP-22: Flyway per service in a pre-install / pre-upgrade hook Job (schema owner)",
        "    enabled: true           # false: run migrations out of band (e.g. a GitOps PreSync job); pods never migrate",
        "    backoffLimit: 1",
        "    activeDeadlineSeconds: 900",
        "    ttlSecondsAfterFinished: 86400",
        "    resources: {cpu: 250m, memory: 512Mi}",
        "  kafka:",
        '    bootstrapServers: "meridian-kafka:9092"',
        "    iam:                    # GAP-16: MSK IAM, one principal per service (SASL_SSL / AWS_MSK_IAM, IRSA)",
        "      enabled: false",
        '      roleArnTemplate: ""   # e.g. arn:aws:iam::123456789012:role/meridian-eu1a-kafka-{name} (tofu output)',
        "  auth:",
        '    issuerUri: "https://auth.example.com/realms/meridian"',
        '    serviceClientSecret: "meridian-service-client"   # LEGACY shared client (used only with sharedServiceClient)',
        "    sharedServiceClient: false        # GAP-16 migration: true = every service uses serviceClientSecret again",
        "    legacyServiceClients: []          # GAP-16 migration: e.g. [meridian-service] while old pods still call",
        "    privilegedAcr: 2                  # GAP-13: acr needed for meridian:admin / platform:operator user tokens",
        "    idpEvents:                        # GAP-06: audit-service copies Keycloak sign-in / admin events",
        "      enabled: false                  # needs platformOperatorTenant (the tenant the events are audited in)",
        '      clientSecret: "meridian-audit-events-client"   # client-id / client-secret of meridian-audit-events',
        '    audience: "meridian-api"          # SEC-14: required aud of every access token',
        '    platformOperatorTenant: ""        # SEC-15: tenant id of the platform operator (empty: service tokens only)',
        "  cell:                     # S20: identity of this cell in the tenant registry",
        '    id: ""                  # cell code, e.g. aws-euw1-c01 (empty: single-cell install)',
        '    region: ""              # cloud region, e.g. eu-west-1',
        "    role: primary           # primary | standby (warm DR standby: READ_ONLY in the registry until promoted)",
        "    promotion:              # MR-GAME-DAY: standby -> primary in place, no rolling restart",
        "      watchRegistry: true   # each service promotes itself once the registry shows this cell ACTIVE (/online)",
        "      pollInterval: 5s",
        '      registryUrl: ""       # control-plane tenant-service as seen from this cell (empty: the in-cell one)',
        "      replayOnPromote: 60s  # re-publish the last 60s of relayed outbox events on promotion (0s = off)",
        "      dbWait: 10m           # how long promotion waits for the database to accept writes",
        "  outbox:",
        "    retention: 72h          # MR-GAME-DAY: relayed outbox rows kept for replay (/v1/internal/ops/outbox/replay)",
        "  trustedProxies: []        # SEC-16: CIDRs of the load balancer / ingress / cell router in front of the gateway",
        "  callbacks:                # SEC-17: provider callback signatures",
        "    acceptLegacySignatures: true   # deprecation period: set false once every provider signs v2",
        '    maxSkew: "5m"',
        "  otel:",
        '    endpoint: "http://otel-collector.monitoring:4318"',
        "    samplingProbability: 0.1",
        "  logging: {format: json}   # GAP-06: json (ECS lines on stdout, for the node log collector) | text",
        "  env: {}                   # extra environment for every component",
        "",
        "ingress:",
        "  enabled: false",
        '  className: ""',
        '  host: "api.example.com"',
        "  annotations: {}",
        "  tls: []",
        "",
        "# GAP-10: SLO burn-rate / saturation alerts and Grafana dashboards (tools/observability.py). Needs the",
        "# Prometheus Operator CRDs (PrometheusRule) and a Grafana dashboard sidecar; scraping is global.serviceMonitor.",
        "observability:",
        "  prometheusRule:",
        "    enabled: false",
        "    labels: {}              # e.g. {release: kube-prometheus-stack} to match the Prometheus ruleSelector",
        "  dashboards:",
        "    enabled: false",
        '    labels: {grafana_dashboard: "1"}   # label watched by the Grafana sidecar',
        '    folder: "Meridian"',
        "",
        "# GAP-02: cardholder data environment (services with zone: cde). Members get default-deny NetworkPolicies that",
        "# admit only their allowedCallers; optionally their own namespace, default-deny egress, a dedicated PostgreSQL",
        "# cluster / credentials and the CDE KMS key (infra/tofu cell outputs cde_kms_key_arn, cde_db_endpoint).",
        "cde:",
        "  enabled: true",
        '  namespace: ""              # e.g. meridian-cde (must exist, with the Secrets below); empty: release namespace',
        "  egress:",
        "    enabled: false           # true: CDE pods may reach only DNS, Meridian pods, monitoring and allowedCidrs",
        "    allowedCidrs: []         # CDE database, Kafka brokers, IdP, KMS / HSM endpoints",
        "  postgres:",
        '    host: ""                 # dedicated CDE cluster endpoint (empty: global.postgres.host)',
        "    port: 5432",
        '    appSecret: "meridian-cde-db-app"          # CDE runtime role (not the shared meridian-db-app)',
        '    migrationSecret: "meridian-cde-db-owner"  # CDE schema owner, mounted only by the CDE migration Jobs',
        "  keys:                      # GAP-01: card-vault-service key management",
        "    mode: software           # software (development) | aws-kms | pkcs11",
        '    kmsKeyId: ""             # aws-kms: CDE key ARN or alias (tofu cell output cde_kms_key_arn)',
        '    kmsRegion: ""            # default: global.cell.region',
        '    kmsEndpoint: ""          # optional KMS VPC interface endpoint',
        '    serviceAccountRoleArn: "" # IRSA role allowed kms:GenerateDataKey/Decrypt/Encrypt/ReEncrypt* on the key',
        "    pkcs11:",
        '      configMap: ""          # SunPKCS11 configuration (pkcs11.cfg: library, slot, key attribute template)',
        '      pinSecret: "meridian-card-vault-hsm"     # Secret with key pin (HSM crypto-user PIN)',
        '  allowedClients: ""         # card-vault-service accepted azp values (comma separated); empty: service default',
        "",
        "# One entry per component. Per-service overrides: enabled, replicas, maxReplicas, cpu, memory, dbPool, env,",
        "# managementPort (actuator on its own port: probes and the ServiceMonitor use it),",
        "# secretName / secretEnv (ENV_VAR: key of the service's Secret; values come from the KMS / vault, never git),",
        "# sharedSecretEnv (ENV_VAR: {secret, key} of one Secret several services must share),",
        "# clientSecret (Secret of the service's OAuth client, default meridian-<service>-client), serviceIdentity (false:",
        "# no OAuth client and no Kafka principal, e.g. the edge gateway).",
        "services:",
    ]
    for r in rows:
        lines.append(f"  - name: {r['name']}")
        for k in ("domain", "enabled", "database", "schema", "replicas", "cpu", "memory", "managementPort", "zone",
                  "keyManagement", "serviceIdentity"):
            if k in r:
                lines.append(f"    {k}: {yaml_value(r[k])}")
        if "allowedCallers" in r:
            lines.append("    allowedCallers:          # GAP-02: who may connect (from the code; tools/helm_chart.py)")
            for c in r["allowedCallers"]:
                lines.append(f"      - {{name: {yaml_value(c['name'])}, zone: {yaml_value(c['zone'])}}}")
        if "secretEnv" in r:
            lines.append(f"    secretName: {yaml_value(r['secretName'])}")
            lines.append("    secretEnv:")
            for env, key in r["secretEnv"].items():
                lines.append(f"      {env}: {yaml_value(key)}")
        if "env" in r:
            lines.append("    env:")
            for env, val in r["env"].items():
                lines.append(f"      {env}: {yaml_value(val)}")
        if "sharedSecretEnv" in r:
            lines.append("    sharedSecretEnv:")
            for env, (secret, key) in r["sharedSecretEnv"].items():
                lines.append(f"      {env}: {{secret: {yaml_value(secret)}, key: {yaml_value(key)}}}")
    return "\n".join(lines) + "\n"


def copy_observability():
    """GAP-10: package infra/observability (tools/observability.py output) as chart files."""
    src = ROOT / "infra" / "observability"
    dst = OUT / "files" / "observability"
    for sub, pattern in (("rules", "prometheus/rules/*.yml"), ("dashboards", "grafana/dashboards/*.json")):
        (dst / sub).mkdir(parents=True, exist_ok=True)
        for old in (dst / sub).iterdir():
            old.unlink()
        for f in sorted(src.glob(pattern)):
            (dst / sub / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")


def main():
    manifest = json.loads((ROOT / "generated" / "services.json").read_text(encoding="utf-8"))
    routes = json.loads((ROOT / "generated" / "routes.json").read_text(encoding="utf-8"))
    callers = cde_callers(routes)
    rows = services_values(manifest, callers)
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
        "templates/migrations.yaml": MIGRATIONS_TPL,
        "templates/ingress.yaml": INGRESS_TPL,
        "templates/observability.yaml": OBSERVABILITY_TPL,
        "templates/cde.yaml": CDE_TPL,
        "templates/NOTES.txt": NOTES,
        ".helmignore": ".git/\n*.md.bak\n",
    }
    for name, text in files.items():
        (OUT / name).write_text(text, encoding="utf-8", newline="\n")
    copy_observability()
    print(f"helm chart: {len(rows)} components -> {OUT.relative_to(ROOT)}")
    for svc, who in callers.items():
        print(f"  CDE {svc}: callers {', '.join(who) or '(none)'}")
    write_router_chart(app_version)
    print(f"helm chart: cell router -> {ROUTER_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
