package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.config.domain.CustomFieldRules;
import io.meridian.config.domain.CustomFieldRules.DataType;
import io.meridian.config.domain.CustomFieldRules.Definition;
import io.meridian.config.infrastructure.ConfigStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Custom field definitions (custom_field_1..5 per table) and validation of values against them. */
@Service
public class CustomFieldService {
    static final String TABLE = "custom_field_definition";
    static final String EVENT = "platform.custom_field_definition.changed";
    private final EventedCrud evented;
    private final ConfigStore store;
    private final JsonMapper json;

    public CustomFieldService(EventedCrud evented, ConfigStore store, JsonMapper json) {
        this.evented = evented;
        this.store = store;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        return evented.create(TABLE, body, v -> validate(v, null), EVENT);
    }

    @Transactional
    public String update(UUID id, Object body, String ifMatch) {
        return evented.update(TABLE, id, body, EventedCrud.parseIfMatch(ifMatch), v -> validate(v, id), EVENT);
    }

    @Transactional
    public void delete(UUID id) {
        evented.delete(TABLE, id, r -> { }, EVENT);
    }

    private void validate(JsonNode v, UUID self) {
        Definition d = definition(v);
        CustomFieldRules.requireValidDefinition(d);
        boolean active = !v.has("is_active") || v.get("is_active").asBoolean();
        if (active && store.fieldKeyTaken(d.tableName(), d.fieldKey(), self)) {
            throw ApiException.conflict("duplicate_field_key",
                    "field_key " + d.fieldKey() + " is already used on table " + d.tableName());
        }
    }

    /**
     * Validates custom field values of a table. Keys may be field keys or slot columns (custom_field_N). Returns
     * {@code valid}, field {@code errors} and the {@code normalized} slot values with defaults applied.
     */
    @Transactional(readOnly = true)
    public JsonNode validateValues(String tableName, JsonNode values) {
        if (tableName == null || tableName.isBlank()) {
            throw ApiException.badRequest("validation_failed", "table_name is required");
        }
        if (values == null || !values.isObject()) {
            throw ApiException.badRequest("validation_failed", "values must be an object");
        }
        Map<String, Definition> byKey = new LinkedHashMap<>();
        for (String r : store.activeCustomFields(tableName)) {
            Definition d = definition(json.readTree(r));
            byKey.put(d.fieldKey(), d);
        }
        Map<String, Definition> bySlot = new LinkedHashMap<>();
        byKey.values().forEach(d -> bySlot.put("custom_field_" + d.slot(), d));

        List<ApiException.FieldError> errors = new ArrayList<>();
        Map<String, String> provided = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : values.properties()) {
            Definition d = byKey.containsKey(e.getKey()) ? byKey.get(e.getKey()) : bySlot.get(e.getKey());
            if (d == null) {
                errors.add(new ApiException.FieldError(e.getKey(), "unknown_field",
                        "no active custom field definition on " + tableName));
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isContainer()) {
                errors.add(new ApiException.FieldError(d.fieldKey(), "invalid_type", "must be a scalar value"));
                continue;
            }
            provided.put(d.fieldKey(), v.isNull() ? null : v.asString());
        }
        ObjectNode normalized = json.createObjectNode();
        for (Definition d : byKey.values()) {
            String value = provided.containsKey(d.fieldKey()) ? provided.get(d.fieldKey()) : null;
            if ((value == null || value.isBlank()) && d.defaultValue() != null) {
                value = d.defaultValue();
            }
            ApiException.FieldError err = CustomFieldRules.validateValue(d, value);
            if (err != null) {
                errors.add(err);
            } else if (value != null && !value.isBlank()) {
                normalized.put("custom_field_" + d.slot(), value);
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("table_name", tableName);
        out.put("valid", errors.isEmpty());
        ArrayNode errs = out.putArray("errors");
        for (ApiException.FieldError e : errors) {
            ObjectNode o = errs.addObject();
            o.put("field", e.field());
            o.put("code", e.code());
            o.put("message", e.message());
        }
        out.set("normalized", normalized);
        return out;
    }

    private static Definition definition(JsonNode v) {
        List<String> allowed = null;
        if (v.hasNonNull("allowed_values")) {
            allowed = new ArrayList<>();
            JsonNode a = v.get("allowed_values");
            if (a.isArray()) {
                for (JsonNode x : a) {
                    allowed.add(x.asString());
                }
            } else if (a.isObject() && a.get("values") != null && a.get("values").isArray()) {
                for (JsonNode x : a.get("values")) {
                    allowed.add(x.asString());
                }
            } else if (a.isObject()) {
                allowed.addAll(a.propertyNames());
            }
        }
        DataType type;
        try {
            type = DataType.valueOf(v.path("data_type").asString("TEXT"));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_value", "unknown data_type " + v.path("data_type").asString(""));
        }
        return new Definition(text(v, "table_name"), v.path("slot").asInt(0), text(v, "field_key"), type,
                v.path("is_required").asBoolean(false), text(v, "validation_regex"), allowed,
                text(v, "min_value"), text(v, "max_value"), text(v, "default_value"));
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
