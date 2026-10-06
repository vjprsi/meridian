package io.meridian.limits.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.limits.domain.Appetite;
import io.meridian.limits.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Limit rule lifecycle (validation, maker-checker activation), temporary overrides (four-eyes approval, never on
 * regulatory limits), operational usage resets and risk appetite measurements.
 */
@Service
public class LimitAdminService {
    static final Set<String> COUNTER_PERIODS = Set.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY", "LIFETIME",
            "ROLLING_24H", "ROLLING_7D", "ROLLING_30D");
    static final Duration MAX_OVERRIDE = Duration.ofDays(90);

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final LimitRuleCache cache;
    private final JsonMapper json;

    public LimitAdminService(Db db, CrudEngine crud, OutboxWriter outbox, LimitRuleCache cache, JsonMapper json) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.cache = cache;
        this.json = json;
    }

    // ------------------------------------------------------------------ rules

    /** Validates a rule definition (create, or the merged result of an update). */
    public void validateRule(JsonNode r) {
        String scope = Db.str(r, "scope");
        String ref = Db.str(r, "scope_ref");
        if (("GLOBAL".equals(scope) || "TENANT".equals(scope)) && ref != null && !ref.isBlank()) {
            throw ApiException.unprocessable("scope_ref_not_allowed", scope + " limits have no scope_ref");
        }
        if (!"GLOBAL".equals(scope) && !"TENANT".equals(scope) && (ref == null || ref.isBlank())) {
            throw ApiException.unprocessable("scope_ref_required", scope + " limits need a scope_ref");
        }
        String measure = Db.str(r, "measure");
        String period = Db.str(r, "period");
        BigDecimal maxAmount = Db.dec(r, "max_amount");
        Integer maxCount = Db.integer(r, "max_count");
        switch (measure) {
            case "AMOUNT", "BALANCE" -> {
                if (maxAmount == null || Db.str(r, "currency") == null) {
                    throw ApiException.unprocessable("max_amount_required", measure + " limits need max_amount and currency");
                }
            }
            case "COUNT" -> {
                if (maxCount == null) {
                    throw ApiException.unprocessable("max_count_required", "COUNT limits need max_count");
                }
                if ("PER_TRANSACTION".equals(period)) {
                    throw ApiException.unprocessable("invalid_period", "A COUNT limit needs a counting period");
                }
            }
            default -> throw ApiException.unprocessable("invalid_measure", "unknown measure " + measure);
        }
        if ((maxAmount != null && maxAmount.signum() < 0) || (maxCount != null && maxCount < 0)) {
            throw ApiException.unprocessable("invalid_limit", "Limits must not be negative");
        }
        if ("BALANCE".equals(measure) && COUNTER_PERIODS.contains(period) && !"LIFETIME".equals(period)) {
            // a balance cap is a point-in-time check, not a counter
            throw ApiException.unprocessable("invalid_period", "BALANCE limits use PER_TRANSACTION or LIFETIME");
        }
        if (r.hasNonNull("valid_from") && r.hasNonNull("valid_to")
                && !OffsetDateTime.parse(r.get("valid_to").asString()).isAfter(OffsetDateTime.parse(r.get("valid_from").asString()))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must be after valid_from");
        }
    }

    @Transactional
    public String updateRule(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode rule = db.require("limit_rule", id, true);
        if ("ACTIVE".equals(rule.get("status").asString())) {
            throw ApiException.conflict("rule_active", "Deactivate limit " + rule.get("code").asString() + " before changing it");
        }
        if (rule.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode values = crud.toColumns("limit_rule", changes);
        for (String f : new String[] {"status", "approved_by", "deactivation_reason"}) {
            values.remove(f);
        }
        ObjectNode merged = ((ObjectNode) rule.deepCopy()).setAll(values);
        validateRule(merged);
        String row = crud.updateRow("limit_rule", Map.of("id", id), values, expectedVersion).orElseThrow();
        outbox.publish("risk.limit_rule.changed", "limit_rule", id, json.readTree(row));
        cache.invalidate();
        return row;
    }

    /** Maker-checker: the activating user must differ from the last author of the rule. */
    @Transactional
    public String activate(UUID id) {
        JsonNode rule = db.require("limit_rule", id, true);
        if ("ACTIVE".equals(rule.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Limit is already active");
        }
        String maker = Db.str(rule, "updated_by") != null ? Db.str(rule, "updated_by") : Db.str(rule, "created_by");
        if (TenantContext.principal().equals(maker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The limit was last changed by " + maker + "; another user must activate it");
        }
        validateRule(rule);
        db.update("update limit_rule set status = 'ACTIVE', approved_by = ?, deactivation_reason = null where id = ?",
                TenantContext.principal(), id);
        return publishRule(id, "risk.limit_rule.activated");
    }

    @Transactional
    public String deactivate(UUID id, String reason) {
        JsonNode rule = db.require("limit_rule", id, true);
        if (!"ACTIVE".equals(rule.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Limit is not active");
        }
        db.update("update limit_rule set status = 'INACTIVE', deactivation_reason = ? where id = ?", reason, id);
        return publishRule(id, "risk.limit_rule.deactivated");
    }

    private String publishRule(UUID id, String event) {
        JsonNode row = db.require("limit_rule", id, false);
        outbox.publish(event, "limit_rule", id, row);
        cache.invalidate();
        return db.text(row);
    }

    // ------------------------------------------------------------------ overrides

    @Transactional
    public String requestOverride(JsonNode body) {
        UUID ruleId = Db.uuid(body, "limit_rule_id");
        JsonNode rule = db.require("limit_rule", ruleId, false);
        if (rule.get("is_regulatory").asBoolean()) {
            throw ApiException.unprocessable("regulatory_limit", "Regulatory limits cannot be overridden");
        }
        if (!body.hasNonNull("max_amount") && !body.hasNonNull("max_count")) {
            throw ApiException.unprocessable("override_value_required", "Give max_amount and/or max_count");
        }
        OffsetDateTime from = OffsetDateTime.parse(body.get("valid_from").asString());
        OffsetDateTime to = OffsetDateTime.parse(body.get("valid_to").asString());
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must be after valid_from");
        }
        if (Duration.between(from, to).compareTo(MAX_OVERRIDE) > 0) {
            throw ApiException.unprocessable("override_too_long", "Overrides are temporary: at most " + MAX_OVERRIDE.toDays() + " days");
        }
        if (!to.isAfter(OffsetDateTime.now())) {
            throw ApiException.unprocessable("invalid_validity", "valid_to is in the past");
        }
        ObjectNode v = crud.toColumns("limit_override", body);
        v.put("status", "PENDING_APPROVAL");
        v.put("requested_by", TenantContext.principal());
        String row = crud.insert("limit_override", v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.limit_override.requested", "limit_override", UUID.fromString(n.get("id").asString()), n);
        return row;
    }

    @Transactional
    public String approveOverride(UUID id, String comment) {
        JsonNode o = db.require("limit_override", id, true);
        requireStatus(o, "PENDING_APPROVAL", "approve");
        if (TenantContext.principal().equals(Db.str(o, "requested_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The requester cannot approve their own override");
        }
        if (db.one("select 1::text where cast(? as timestamptz) <= now()", o.get("valid_to").asString()).isPresent()) {
            throw ApiException.conflict("override_expired", "The override window has already ended");
        }
        db.update("update limit_override set status = 'ACTIVE', approved_by = ?, decision_reason = ? where id = ?",
                TenantContext.principal(), comment, id);
        return publishOverride(id, "risk.limit_override.approved");
    }

    @Transactional
    public String rejectOverride(UUID id, String reason) {
        JsonNode o = db.require("limit_override", id, true);
        requireStatus(o, "PENDING_APPROVAL", "reject");
        db.update("update limit_override set status = 'REJECTED', decision_reason = ? where id = ?", reason, id);
        return publishOverride(id, "risk.limit_override.rejected");
    }

    @Transactional
    public String revokeOverride(UUID id, String reason) {
        JsonNode o = db.require("limit_override", id, true);
        requireStatus(o, "ACTIVE", "revoke");
        db.update("update limit_override set status = 'REVOKED', decision_reason = ? where id = ?", reason, id);
        return publishOverride(id, "risk.limit_override.expired");
    }

    private String publishOverride(UUID id, String event) {
        JsonNode row = db.require("limit_override", id, false);
        outbox.publish(event, "limit_override", id, row);
        return db.text(row);
    }

    private static void requireStatus(JsonNode n, String expected, String what) {
        String s = n.get("status").asString();
        if (!expected.equals(s)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + what + " an override that is " + s);
        }
    }

    // ------------------------------------------------------------------ usage

    /** Operations correction: clears committed usage (reservations in flight are kept), audited by event. */
    @Transactional
    public String resetUsage(UUID id, String reason) {
        db.require("limit_usage", id, true);
        db.update("update limit_usage set used_amount = 0, used_count = 0, last_reset_reason = ?, updated_by = ? where id = ?",
                reason, TenantContext.principal(), id);
        JsonNode row = db.require("limit_usage", id, false);
        outbox.publish("risk.limit_usage.reset", "limit_usage", id, row);
        return db.text(row);
    }

    // ------------------------------------------------------------------ risk appetite

    public void validateMetric(JsonNode current, JsonNode changes) {
        BigDecimal amber = Db.dec(changes, "threshold_amber") != null ? Db.dec(changes, "threshold_amber") : Db.dec(current, "threshold_amber");
        BigDecimal red = Db.dec(changes, "threshold_red") != null ? Db.dec(changes, "threshold_red") : Db.dec(current, "threshold_red");
        if (amber != null && red != null && amber.compareTo(red) == 0) {
            throw ApiException.unprocessable("invalid_thresholds", "Amber and red thresholds must differ");
        }
    }

    /**
     * Records a measurement (history is append-only). The metric's current value and status move only forward in
     * time; a worsening status (GREEN to AMBER / RED, AMBER to RED) publishes risk.appetite_metric.breached.
     */
    @Transactional
    public String measure(UUID id, BigDecimal value, String measuredAt, String source) {
        JsonNode m = db.require("risk_appetite_metric", id, true);
        if (db.one("select 1::text where cast(? as timestamptz) > now() + interval '5 minutes'", measuredAt).isPresent()) {
            throw ApiException.unprocessable("measured_in_future", "measured_at cannot be in the future");
        }
        String status = Appetite.status(value, Db.dec(m, "threshold_amber"), Db.dec(m, "threshold_red"));
        ObjectNode h = json.createObjectNode();
        h.put("risk_appetite_metric_id", id.toString());
        h.put("value", value.toPlainString());
        h.put("status", status);
        h.put("measured_at", measuredAt);
        if (source != null) {
            h.put("source", source);
        }
        crud.insert("risk_appetite_measurement", h);
        int changed = db.update("""
                update risk_appetite_metric set current_value = ?, status = ?, measured_at = cast(? as timestamptz)
                where id = ? and (measured_at is null or measured_at <= cast(? as timestamptz))""",
                value, status, measuredAt, id, measuredAt);
        JsonNode row = db.require("risk_appetite_metric", id, false);
        if (changed > 0 && Appetite.worsened(m.get("status").asString(), status)) {
            outbox.publish("risk.appetite_metric.breached", "risk_appetite_metric", id, row);
        }
        return db.text(row);
    }
}
