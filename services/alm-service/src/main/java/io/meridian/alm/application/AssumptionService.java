package io.meridian.alm.application;

import io.meridian.alm.domain.Assumption;
import io.meridian.alm.domain.Assumption.AssumptionType;
import io.meridian.alm.domain.Behaviour;
import io.meridian.alm.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Behavioural assumptions (model risk): parameters validated per type, maker-checker approval, frozen once approved
 * (only valid_to may change, to retire a model; a new model_version is captured as a new assumption).
 */
@Service
public class AssumptionService {
    static final String TABLE = "alm_behavioural_assumption";
    /** Fields that may still change after approval. */
    private static final Set<String> AFTER_APPROVAL = Set.of("valid_to", "custom_field_1", "custom_field_2",
            "custom_field_3", "custom_field_4", "custom_field_5");

    private final Rows rows;
    private final Events events;

    public AssumptionService(Rows rows, Events events) {
        this.rows = rows;
        this.events = events;
    }

    /** Validates a create body and returns the column values to insert (parameters normalised). */
    public ObjectNode prepareCreate(Object body) {
        ObjectNode values = rows.columns(TABLE, body);
        AssumptionType type = type(Rows.str(values, "assumption_type"));
        values.set("parameters", normalised(type, values.get("parameters")));
        checkValidity(Rows.date(values, "valid_from"), Rows.date(values, "valid_to"));
        return values;
    }

    @Transactional
    public JsonNode create(ObjectNode values) {
        return rows.insert(TABLE, values);
    }

    /** Validates a PATCH against the stored row; throws when the assumption is approved and frozen. */
    @Transactional(readOnly = true)
    public void checkUpdate(java.util.UUID id, Object body) {
        ObjectNode existing = rows.get(TABLE, id);
        ObjectNode changes = rows.columns(TABLE, body);
        if (Rows.str(existing, "approved_by") != null) {
            for (String f : iterable(changes)) {
                if (!AFTER_APPROVAL.contains(f)) {
                    throw ApiException.conflict("assumption_approved", "Approved assumptions are frozen (" + f
                            + " cannot change); capture a new model_version instead");
                }
            }
        }
        AssumptionType type = type(changes.has("assumption_type") ? Rows.str(changes, "assumption_type")
                : Rows.str(existing, "assumption_type"));
        if (changes.has("parameters") || changes.has("assumption_type")) {
            normalised(type, changes.has("parameters") ? changes.get("parameters") : existing.get("parameters"));
        }
        LocalDate from = changes.has("valid_from") ? Rows.date(changes, "valid_from") : Rows.date(existing, "valid_from");
        LocalDate to = changes.has("valid_to") ? Rows.date(changes, "valid_to") : Rows.date(existing, "valid_to");
        checkValidity(from, to);
    }

    @Transactional
    public JsonNode approve(java.util.UUID id, String comment) {
        ObjectNode row = rows.lock(TABLE, id);
        if (Rows.str(row, "approved_by") != null) {
            throw ApiException.conflict("already_approved", "The assumption was already approved by "
                    + Rows.str(row, "approved_by"));
        }
        String approver = TenantContext.principal();
        if (approver.equals(Rows.str(row, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of an assumption cannot approve it");
        }
        ObjectNode approved = rows.queryOne("""
                update alm_behavioural_assumption t set approved_by = ?, approved_at = now(), updated_by = ?
                where id = ? returning row_to_json(t)::text""", approver, approver, id).orElseThrow();
        events.publish("treasury.alm_assumption.approved", TABLE, approved);
        return approved;
    }

    /** Approved assumptions (all types) as domain objects. */
    @Transactional(readOnly = true)
    public List<Assumption> approved() {
        List<Assumption> out = new ArrayList<>();
        for (ObjectNode r : rows.query("select row_to_json(t)::text from alm_behavioural_assumption t "
                + "where approved_by is not null order by valid_from")) {
            AssumptionType type = AssumptionType.valueOf(Rows.str(r, "assumption_type"));
            Map<String, BigDecimal> params;
            try {
                params = Behaviour.validate(type, toMap(r.get("parameters")));
            } catch (IllegalArgumentException e) {
                continue; // stored before validation tightened: never used
            }
            out.add(new Assumption(Rows.uuid(r, "id"), type, Rows.uuid(r, "product_id"), Rows.str(r, "segment"),
                    Rows.str(r, "currency"), params, Rows.date(r, "valid_from"), Rows.date(r, "valid_to")));
        }
        return out;
    }

    private JsonNode normalised(AssumptionType type, JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            throw ApiException.unprocessable("invalid_parameters", "parameters must be an object");
        }
        Map<String, BigDecimal> p;
        try {
            p = Behaviour.validate(type, toMap(parameters));
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_parameters", type + ": " + e.getMessage());
        }
        ObjectNode out = rows.object();
        p.forEach(out::put);
        return out;
    }

    static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.propertyNames().forEach(k -> {
                JsonNode v = node.get(k);
                m.put(k, v == null || v.isNull() ? null : v.isNumber() ? v.decimalValue() : v.asString());
            });
        }
        return m;
    }

    private static AssumptionType type(String s) {
        try {
            return AssumptionType.valueOf(s);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_assumption_type", "unknown assumption_type " + s);
        }
    }

    private static void checkValidity(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
    }

    private static Iterable<String> iterable(ObjectNode n) {
        List<String> names = new ArrayList<>();
        n.propertyNames().forEach(names::add);
        return names;
    }
}
