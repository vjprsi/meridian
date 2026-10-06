package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.FeatureFlagsApi;
import io.meridian.tenant.api.generated.model.FeatureFlag;
import io.meridian.tenant.api.generated.model.FeatureFlagCreate;
import io.meridian.tenant.api.generated.model.FeatureFlagUpdate;
import io.meridian.tenant.api.generated.model.FeatureFlagsEvaluateRequest;
import io.meridian.tenant.application.SettingsService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Feature flags with targeting rules and percentage rollout; evaluation for a subject. */
@RestController
public class FeatureFlagsController implements FeatureFlagsApi {
    private final SettingsService settings;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FeatureFlagsController(SettingsService settings, Idempotency idempotency, JsonMapper json) {
        this.settings = settings;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FeatureFlag> featureFlagsCreate(String idempotencyKey, FeatureFlagCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, FeatureFlag.class,
                () -> Responses.of(HttpStatus.CREATED, settings.createFlag(body), FeatureFlag.class, json));
    }

    @Override
    public ResponseEntity<FeatureFlag> featureFlagsUpdate(UUID id, String ifMatch, FeatureFlagUpdate body) {
        TenantContext.require();
        return settings.updateFlag(id, body, ifMatch, FeatureFlag.class);
    }

    @Override
    public ResponseEntity<Void> featureFlagsDelete(UUID id) {
        TenantContext.require();
        return settings.deleteFlag(id);
    }

    /** Contract declares no response schema; returns {subject_type, subject_id, flags: {key: {enabled, variant, reason}}}. */
    @Override
    public ResponseEntity<Void> featureFlagsEvaluate(String idempotencyKey, FeatureFlagsEvaluateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return Responses.untyped(idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(settings.evaluate(req))));
    }
}
