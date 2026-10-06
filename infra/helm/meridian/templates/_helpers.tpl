{{/* Common labels for a Meridian component. */}}
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
