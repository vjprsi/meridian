package io.meridian.fraud.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.api.generated.FraudModelScoresApi;
import io.meridian.fraud.api.generated.model.FraudModelScoresPreviewFeaturesRequest;
import io.meridian.fraud.api.generated.model.FraudModelScoresPublishMonitoringRequest;
import io.meridian.fraud.application.FeatureService;
import io.meridian.fraud.application.MlOperationsService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Governed ML models: score log (generic list / get), runtime state, reload, feature catalogue, monitoring feed. */
@RestController
public class FraudModelScoresController implements FraudModelScoresApi {
    private final MlOperationsService ops;
    private final FeatureService features;
    private final Http http;

    public FraudModelScoresController(MlOperationsService ops, FeatureService features, Http http) {
        this.ops = ops;
        this.features = features;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudModelScoresRuntime() {
        TenantContext.require();
        return ResponseEntity.ok(ops.runtime());
    }

    /** Not cached by idempotency: a reload is safe to repeat and must reflect the registry at the time of the call. */
    @Override
    public ResponseEntity<Map<String, Object>> fraudModelScoresReload(String idempotencyKey) {
        TenantContext.require();
        return ResponseEntity.ok(ops.reload());
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudModelScoresRegisterFeatures(String idempotencyKey) {
        return http.onceMap(idempotencyKey, null, ops::registerFeatures);
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudModelScoresPreviewFeatures(String idempotencyKey,
                                                                               FraudModelScoresPreviewFeaturesRequest body) {
        TenantContext.require();
        JsonNode req = http.tree(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", features.keys(req));
        out.put("features", FeatureService.jsonSafe(features.compute(req)));
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudModelScoresPublishMonitoring(String idempotencyKey,
                                                                                 FraudModelScoresPublishMonitoringRequest body) {
        JsonNode req = http.tree(body);
        OffsetDateTime from = ts(req, "window_start");
        OffsetDateTime to = ts(req, "window_end");
        return http.onceMap(idempotencyKey, body, () -> ops.publishMonitoring(from, to));
    }

    static OffsetDateTime ts(JsonNode req, String f) {
        String v = Http.text(req, f);
        if (v == null) {
            throw ApiException.unprocessable(f + "_required", f + " is required");
        }
        return OffsetDateTime.parse(v);
    }
}
