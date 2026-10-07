{{/* Common labels for a Meridian component. */}}
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
