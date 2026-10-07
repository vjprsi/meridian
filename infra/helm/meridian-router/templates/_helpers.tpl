{{- define "router.labels" -}}
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
