package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsExportsApi;
import io.meridian.analytics.api.generated.model.AnalyticsExportJob;
import io.meridian.analytics.api.generated.model.AnalyticsExportJobCreate;
import io.meridian.analytics.api.generated.model.AnalyticsExportsCancelRequest;
import io.meridian.analytics.application.ExportService;
import io.meridian.analytics.application.Fields;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Export jobs: asynchronous creation, cancel, downloads of finished files. */
@RestController
public class AnalyticsExportsController implements AnalyticsExportsApi {
    private final ExportService exports;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public AnalyticsExportsController(ExportService exports, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.exports = exports;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<AnalyticsExportJob> analyticsExportsCreate(String idempotencyKey, AnalyticsExportJobCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        UUID query = Fields.requireUuid(req, "analytics_saved_query_id");
        String format = Fields.requireText(req, "format");
        return idempotency.execute(idempotencyKey, body, AnalyticsExportJob.class,
                () -> responses.created(exports.create(query, format, req.get("parameters")), AnalyticsExportJob.class));
    }

    @Override
    public ResponseEntity<AnalyticsExportJob> analyticsExportsCancel(UUID id, String idempotencyKey, AnalyticsExportsCancelRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("cancel", id, req), AnalyticsExportJob.class,
                () -> responses.ok(exports.cancel(id, Fields.text(req, "reason")), AnalyticsExportJob.class));
    }

    @Override
    public ResponseEntity<String> analyticsExportsCsv(UUID id) {
        return download(id, "CSV", "text/csv", ".csv");
    }

    @Override
    public ResponseEntity<String> analyticsExportsJson(UUID id) {
        return download(id, "JSON", "application/json", ".json");
    }

    private ResponseEntity<String> download(UUID id, String format, String type, String ext) {
        TenantContext.require();
        JsonNode job = exports.downloadable(id, format);
        ResponseEntity<String> r = responses.file(job.get("content").asString(), type, "export-" + id + ext);
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .header("X-Content-SHA256", job.get("sha256").asString()).body(r.getBody());
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsExportsProcessQueued(String idempotencyKey) {
        TenantContext.require();
        return responses.object(idempotency.execute(idempotencyKey, json.createObjectNode().put("action", "process-queued"),
                JsonNode.class, () -> ResponseEntity.ok(exports.processQueued())));
    }
}
