package io.meridian.alm.application;

import io.meridian.alm.domain.Assumption;
import io.meridian.alm.domain.Assumption.AssumptionType;
import io.meridian.alm.domain.Behaviour;
import io.meridian.alm.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** Approval resource type for behavioural assumptions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "alm_behavioural_assumption";

    private final Rows rows;
    private final Events events;
    private final Approvals approvals;

    public AssumptionService(Rows rows, Events events, Approvals approvals) {
        this.rows = rows;
        this.events = events;
        this.approvals = approvals;
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
        ObjectNode row = rows.insert(TABLE, values);
        requestApproval(row);
        return row;
    }

    /**
     * After a PATCH of an assumption still awaiting approval: the reworked content is submitted again (the earlier
     * workflow request carries the old row_version and is ignored if decided).
     */
    @Transactional
    public void resubmit(UUID id) {
        if (rows.find(TABLE, id).filter(r -> Rows.str(r, "approved_by") == null).isEmpty()) {
            return;
        }
        // the rework answers a rejection: clear it and submit the new content
        rows.execute("""
                update alm_behavioural_assumption set rejected_by = null, rejected_at = null, rejection_reason = null
                where id = ? and rejected_by is not null""", id);
        requestApproval(rows.get(TABLE, id));
    }

    /** Central maker-checker: the checker decides in the workflow inbox; AssumptionApprovalHandler applies it. */
    private void requestApproval(ObjectNode row) {
        UUID id = Rows.uuid(row, "id");
        int version = Rows.integer(row, "row_version");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assumption_type", Rows.str(row, "assumption_type"));
        payload.put("product_id", Rows.str(row, "product_id"));
        payload.put("segment", Rows.str(row, "segment"));
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("parameters", toMap(row.get("parameters")));
        payload.put("valid_from", Rows.str(row, "valid_from"));
        payload.put("valid_to", Rows.str(row, "valid_to"));
        payload.put("model_version", Rows.str(row, "model_version"));
        payload.put("row_version", version);
        String scope = Rows.str(row, "segment") != null ? Rows.str(row, "segment")
                : Rows.str(row, "product_id") != null ? "product " + Rows.str(row, "product_id") : "all";
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "Behavioural assumption " + Rows.str(row, "assumption_type") + " " + Rows.str(row, "model_version")
                        + " (" + scope + ")", payload), "submit:" + id + ":" + version);
    }

    /** True while the assumption is unapproved with the submitted content (row_version) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, Integer rowVersion) {
        return rows.find(TABLE, id)
                .filter(r -> Rows.str(r, "approved_by") == null && Rows.str(r, "rejected_by") == null)
                .filter(r -> rowVersion == null || rowVersion.equals(Rows.integer(r, "row_version")))
                .isPresent();
    }

    /**
     * Checker step: rejects an unapproved assumption (it is never used by gap, FTP or sensitivity runs). A PATCH
     * reworks it, clears the rejection and submits it again.
     */
    @Transactional
    public JsonNode reject(UUID id, String reason) {
        ObjectNode row = rows.lock(TABLE, id);
        if (Rows.str(row, "approved_by") != null) {
            throw ApiException.conflict("already_approved", "The assumption was already approved by "
                    + Rows.str(row, "approved_by"));
        }
        if (Rows.str(row, "rejected_by") != null) {
            throw ApiException.conflict("already_rejected", "The assumption was already rejected by "
                    + Rows.str(row, "rejected_by") + "; rework it to submit it again");
        }
        String checker = TenantContext.principal();
        if (checker.equals(Rows.str(row, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of an assumption cannot reject it");
        }
        ObjectNode rejected = rows.queryOne("""
                update alm_behavioural_assumption t set rejected_by = ?, rejected_at = ?, rejection_reason = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""", checker, Clocks.now(), reason, checker, id).orElseThrow();
        events.publish("treasury.alm_assumption.rejected", TABLE, rejected);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return rejected;
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
        if (Rows.str(row, "rejected_by") != null) {
            throw ApiException.conflict("assumption_rejected", "The assumption was rejected by "
                    + Rows.str(row, "rejected_by") + "; rework it before approval");
        }
        String approver = TenantContext.principal();
        if (approver.equals(Rows.str(row, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of an assumption cannot approve it");
        }
        ObjectNode approved = rows.queryOne("""
                update alm_behavioural_assumption t set approved_by = ?, approved_at = ?, updated_by = ?
                where id = ? returning row_to_json(t)::text""", approver, Clocks.now(), approver, id).orElseThrow();
        events.publish("treasury.alm_assumption.approved", TABLE, approved);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
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
