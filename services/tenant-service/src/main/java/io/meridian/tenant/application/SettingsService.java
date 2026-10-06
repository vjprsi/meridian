package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.tenant.domain.FlagEvaluator;
import io.meridian.tenant.domain.SettingValues;
import io.meridian.tenant.domain.Validators;
import io.meridian.tenant.infrastructure.TenantStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Typed tenant settings and feature flags (with deterministic evaluation for a subject). */
@Service
public class SettingsService {
    private static final String MASK = "***";

    private final TenantStore store;
    private final CrudEngine crud;
    private final Events events;
    private final JsonMapper json;

    public SettingsService(TenantStore store, CrudEngine crud, Events events, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.events = events;
        this.json = json;
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public JsonNode createSetting(Object body) {
        ObjectNode v = crud.toColumns("tenant_setting", body);
        if (!Validators.isSettingKey(v.path("setting_key").asString(null))) {
            throw ApiException.unprocessable("invalid_setting_key",
                    "setting_key must be dotted lower_snake_case, e.g. payments.default_rail");
        }
        validateValue(v.path("value_type").asString(), v.get("value"));
        ObjectNode row = store.insert("tenant_setting", v);
        events.publish("platform.tenant_setting.changed", "tenant_setting", id(row), masked(row));
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateSetting(UUID id, Object body, String ifMatch, Class<T> type) {
        ObjectNode v = crud.toColumns("tenant_setting", body);
        ObjectNode current = store.lock("tenant_setting", id);
        if (v.hasNonNull("setting_key") && !v.get("setting_key").asString().equals(current.get("setting_key").asString())) {
            throw ApiException.unprocessable("immutable_field", "setting_key cannot be changed; create a new setting");
        }
        if (v.has("value") || v.has("value_type")) {
            String t = v.hasNonNull("value_type") ? v.get("value_type").asString() : current.get("value_type").asString();
            JsonNode value = v.has("value") ? v.get("value") : current.get("value");
            validateValue(t, value);
        }
        ResponseEntity<T> r = crud.update("tenant_setting", type, body, ifMatch, Crud.params().with("id", id));
        events.publish("platform.tenant_setting.changed", "tenant_setting", id,
                masked((ObjectNode) json.valueToTree(r.getBody())));
        return r;
    }

    @Transactional
    public ResponseEntity<Void> deleteSetting(UUID id) {
        ObjectNode current = store.lock("tenant_setting", id);
        ResponseEntity<Void> r = crud.delete("tenant_setting", Crud.params().with("id", id));
        events.publish("platform.tenant_setting.deleted", "tenant_setting", id, masked(current));
        return r;
    }

    private void validateValue(String valueType, JsonNode value) {
        SettingValues.validate(valueType, value).ifPresent(msg -> {
            throw ApiException.unprocessable("invalid_setting_value", msg);
        });
    }

    /** Event payload: the setting with its value masked when sensitive. */
    private static ObjectNode masked(ObjectNode row) {
        ObjectNode p = row.deepCopy();
        if (p.path("is_sensitive").asBoolean()) {
            p.put("value", MASK);
        }
        return p;
    }

    // ------------------------------------------------------------------ feature flags

    @Transactional
    public JsonNode createFlag(Object body) {
        ObjectNode v = crud.toColumns("feature_flag", body);
        if (!Validators.isFlagKey(v.path("flag_key").asString(null))) {
            throw ApiException.unprocessable("invalid_flag_key", "flag_key must be lower case letters, digits, . _ -");
        }
        validateFlag(v);
        ObjectNode row = store.insert("feature_flag", v);
        events.publish("platform.feature_flag.changed", "feature_flag", id(row), row);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateFlag(UUID id, Object body, String ifMatch, Class<T> type) {
        ObjectNode v = crud.toColumns("feature_flag", body);
        ObjectNode current = store.lock("feature_flag", id);
        if (v.hasNonNull("flag_key") && !v.get("flag_key").asString().equals(current.get("flag_key").asString())) {
            throw ApiException.unprocessable("immutable_field", "flag_key cannot be changed");
        }
        validateFlag(v);
        ResponseEntity<T> r = crud.update("feature_flag", type, body, ifMatch, Crud.params().with("id", id));
        events.publish("platform.feature_flag.changed", "feature_flag", id,
                json.valueToTree(r.getBody()));
        return r;
    }

    @Transactional
    public ResponseEntity<Void> deleteFlag(UUID id) {
        ObjectNode current = store.lock("feature_flag", id);
        ResponseEntity<Void> r = crud.delete("feature_flag", Crud.params().with("id", id));
        events.publish("platform.feature_flag.deleted", "feature_flag", id, current);
        return r;
    }

    private void validateFlag(ObjectNode v) {
        if (v.has("rollout_percentage")) {
            int p = v.get("rollout_percentage").asInt();
            if (p < 0 || p > 100) {
                throw ApiException.unprocessable("invalid_rollout_percentage", "rollout_percentage must be 0-100");
            }
        }
        FlagEvaluator.validateRules(v.get("targeting_rules")).ifPresent(msg -> {
            throw ApiException.unprocessable("invalid_targeting_rules", msg);
        });
        JsonNode variants = v.get("variants");
        if (variants != null && !variants.isNull() && !variants.isArray() && !variants.isObject()) {
            throw ApiException.unprocessable("invalid_variants", "variants must be an array of names or {name: weight}");
        }
    }

    /** Evaluates every flag of the tenant for one subject. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(String subjectType, String subjectId, JsonNode context) {
        Instant now = Instant.now();
        ObjectNode out = json.createObjectNode();
        out.put("subject_type", subjectType);
        out.put("subject_id", subjectId);
        out.put("evaluated_at", now.toString());
        ObjectNode flags = out.putObject("flags");
        for (ObjectNode f : store.query("select row_to_json(f)::text from feature_flag f order by flag_key")) {
            FlagEvaluator.Flag flag = new FlagEvaluator.Flag(f.get("flag_key").asString(),
                    f.get("is_enabled").asBoolean(), f.path("rollout_percentage").asInt(0), f.get("targeting_rules"),
                    f.get("variants"),
                    f.hasNonNull("expires_at") ? OffsetDateTime.parse(f.get("expires_at").asString()).toInstant() : null);
            FlagEvaluator.Result r = FlagEvaluator.evaluate(flag, subjectType, subjectId, context, now);
            ObjectNode e = flags.putObject(flag.key());
            e.put("enabled", r.enabled());
            if (r.variant() != null) {
                e.put("variant", r.variant());
            }
            e.put("reason", r.reason());
            if (r.expired()) {
                e.put("expired", true);
            }
        }
        return json.convertValue(out, Map.class);
    }

    private static UUID id(JsonNode row) {
        return UUID.fromString(row.get("id").asString());
    }
}
