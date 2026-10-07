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
