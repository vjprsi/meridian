package io.meridian.analytics.api;

import io.meridian.analytics.api.generated.AnalyticsDataSharesApi;
import io.meridian.analytics.api.generated.model.AnalyticsDataShare;
import io.meridian.analytics.api.generated.model.AnalyticsDataShareCreate;
import io.meridian.analytics.api.generated.model.AnalyticsDataShareUpdate;
import io.meridian.analytics.api.generated.model.AnalyticsDataSharesPauseRequest;
import io.meridian.analytics.api.generated.model.AnalyticsDataSharesTargetConfigRequest;
import io.meridian.analytics.application.DataShareService;
import io.meridian.analytics.application.Fields;
import io.meridian.analytics.application.ScheduledJobs;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Data shares: secret destination config (write-only), pause / resume / test, per-tenant delivery job. */
@RestController
public class AnalyticsDataSharesController implements AnalyticsDataSharesApi {
    private final DataShareService shares;
    private final ScheduledJobs jobs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public AnalyticsDataSharesController(DataShareService shares, ScheduledJobs jobs, Idempotency idempotency,
                                         Responses responses, CrudEngine crud, Store store, JsonMapper json) {
        this.shares = shares;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<AnalyticsDataShare> analyticsDataSharesCreate(String idempotencyKey, AnalyticsDataShareCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        ObjectNode values = crud.toColumns("analytics_data_share", body);
        JsonNode config = req.get("target_config");
        if (config == null || config.isNull()) {
            throw Fields.invalid("target_config", "required", "target_config is required");
        }
        shares.prepare(values, null, config);
        values.put("status", "ACTIVE");
        return idempotency.execute(idempotencyKey, req, AnalyticsDataShare.class,
                () -> responses.created(crud.insert("analytics_data_share", values), AnalyticsDataShare.class));
    }

    @Override
    public ResponseEntity<AnalyticsDataShare> analyticsDataSharesUpdate(UUID id, String ifMatch, AnalyticsDataShareUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("analytics_data_share", id);
        ObjectNode values = crud.toColumns("analytics_data_share", body);
        values.remove("target_config");
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), AnalyticsDataShare.class);
        }
        shares.prepare(values, existing, null);
        String row = crud.updateRow("analytics_data_share", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, AnalyticsDataShare.class);
    }

    @Override
    public ResponseEntity<AnalyticsDataShare> analyticsDataSharesPause(UUID id, String idempotencyKey, AnalyticsDataSharesPauseRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("pause", id, req), AnalyticsDataShare.class,
                () -> responses.ok(shares.pause(id, Fields.text(req, "reason")), AnalyticsDataShare.class));
    }

    @Override
    public ResponseEntity<AnalyticsDataShare> analyticsDataSharesResume(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("resume", id, null), AnalyticsDataShare.class,
                () -> responses.ok(shares.resume(id), AnalyticsDataShare.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsDataSharesTest(UUID id, String idempotencyKey) {
        TenantContext.require();
        return responses.object(idempotency.execute(idempotencyKey, responses.action("test", id, null), JsonNode.class,
                () -> ResponseEntity.ok(shares.test(id))));
    }

    @Override
    public ResponseEntity<AnalyticsDataShare> analyticsDataSharesTargetConfig(UUID id, String idempotencyKey,
                                                                             AnalyticsDataSharesTargetConfigRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("target-config", id, req), AnalyticsDataShare.class,
                () -> responses.ok(shares.replaceConfig(id, req.get("target_config")), AnalyticsDataShare.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> analyticsDataSharesDeliver(LocalDate businessDate, String idempotencyKey) {
        TenantContext.require();
        JsonNode fp = json.createObjectNode().put("job", "DATA_SHARE_DELIVERY").put("business_date", String.valueOf(businessDate));
        return responses.object(idempotency.execute(idempotencyKey, fp, JsonNode.class,
                () -> ResponseEntity.ok(jobs.run("DATA_SHARE_DELIVERY", businessDate,
                        (counts, errors) -> shares.deliver(businessDate, counts, errors)))));
    }
}
