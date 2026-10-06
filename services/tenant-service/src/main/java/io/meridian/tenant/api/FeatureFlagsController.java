package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.FeatureFlagsApi;
import io.meridian.tenant.api.generated.model.FeatureFlag;
import io.meridian.tenant.api.generated.model.FeatureFlagCreate;
import io.meridian.tenant.api.generated.model.FeatureFlagUpdate;
import io.meridian.tenant.api.generated.model.FeatureFlagsEvaluateRequest;
import io.meridian.tenant.application.SettingsService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
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

    /** Returns {subject_type, subject_id, evaluated_at, flags: {key: {enabled, variant, reason}}}. */
    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> featureFlagsEvaluate(String idempotencyKey, FeatureFlagsEvaluateRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, (Class<Map<String, Object>>) (Class<?>) Map.class,
                () -> ResponseEntity.ok(settings.evaluate(body.getSubjectType(), body.getSubjectId(),
                        body.getContext() == null ? null : json.valueToTree(body.getContext()))));
    }
}
