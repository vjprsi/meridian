package io.meridian.collections.application;

import io.meridian.collections.domain.StrategyMatcher;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Strategy, step and queue configuration with validation beyond the generic CRUD. */
@Service
public class StrategyAdmin {
    private final Rows rows;

    public StrategyAdmin(Rows rows) {
        this.rows = rows;
    }

    /** Validates a strategy create/update after merging the change into the current values. */
    public void validateStrategy(JsonNode merged) {
        int min = merged.path("min_dpd").asInt(1);
        JsonNode max = merged.get("max_dpd");
        if (min < 0) {
            throw ApiException.unprocessable("invalid_dpd_range", "min_dpd must not be negative");
        }
        if (max != null && !max.isNull() && max.asInt() < min) {
            throw ApiException.unprocessable("invalid_dpd_range", "max_dpd must not be lower than min_dpd");
        }
        if (merged.has("priority") && merged.get("priority").asInt() < 0) {
            throw ApiException.unprocessable("invalid_priority", "priority must not be negative");
        }
        if (merged.has("max_contacts_per_week") && merged.get("max_contacts_per_week").asInt() < 0) {
            throw ApiException.unprocessable("invalid_contact_cap", "max_contacts_per_week must not be negative");
        }
        StrategyMatcher.SegmentRules.parse(merged.get("segment_rules"));
        quietHours(merged.get("quiet_hours"));
    }

    /** Validates a step against its strategy: unique step number, trigger DPD inside the strategy's range, queue. */
    @Transactional(readOnly = true)
    public void validateStep(UUID strategyId, UUID stepId, JsonNode merged) {
        ObjectNode s = rows.find("collection_strategy", strategyId)
                .orElseThrow(() -> ApiException.notFound("collection strategy", strategyId));
        if (merged.hasNonNull("step_no")) {
            int no = merged.get("step_no").asInt();
            if (no < 1) {
                throw ApiException.unprocessable("invalid_step_no", "step_no must be 1 or more");
            }
            int dup = rows.scalar("""
                    select count(*) from collection_strategy_step where collection_strategy_id = ? and step_no = ?
                      and (?::uuid is null or id <> ?::uuid)""", Integer.class, strategyId, no, stepId, stepId).orElse(0);
            if (dup > 0) {
                throw ApiException.conflict("duplicate_step_no", "Step " + no + " already exists in this strategy");
            }
        }
        if (merged.hasNonNull("trigger_dpd")) {
            int dpd = merged.get("trigger_dpd").asInt();
            int min = Rows.integer(s, "min_dpd");
            Integer max = Rows.integer(s, "max_dpd");
            if (dpd < min || (max != null && dpd > max)) {
                throw ApiException.unprocessable("trigger_dpd_out_of_range", "trigger_dpd " + dpd
                        + " is outside the strategy range " + min + ".." + (max == null ? "" : max));
            }
        }
        if (merged.hasNonNull("queue_id")) {
            UUID q = UUID.fromString(merged.get("queue_id").asString());
            if (rows.find("collection_queue", q).isEmpty()) {
                throw ApiException.unprocessable("queue_not_found", "queue_id " + q + " does not reference an existing queue");
            }
        }
    }

    public void validateQueue(JsonNode merged) {
        JsonNode agents = merged.get("agent_user_ids");
        if (agents != null && agents.isArray()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonNode a : agents) {
                if (a.asString().isBlank() || !seen.add(a.asString())) {
                    throw ApiException.unprocessable("invalid_agents", "agent_user_ids must be distinct, non-blank ids");
                }
            }
        }
        if (merged.hasNonNull("max_cases_per_agent") && merged.get("max_cases_per_agent").asInt() < 1) {
            throw ApiException.unprocessable("invalid_capacity", "max_cases_per_agent must be 1 or more");
        }
    }

    /** Parses {@code quiet_hours} ({start: "HH:mm", end: "HH:mm"}); 422 invalid_quiet_hours otherwise. */
    public static LocalTime[] quietHours(JsonNode q) {
        if (q == null || q.isNull()) {
            return null;
        }
        if (!q.isObject() || !q.hasNonNull("start") || !q.hasNonNull("end")) {
            throw ApiException.unprocessable("invalid_quiet_hours", "quiet_hours must be {start: 'HH:mm', end: 'HH:mm'}");
        }
        try {
            return new LocalTime[] {LocalTime.parse(q.get("start").asString()), LocalTime.parse(q.get("end").asString())};
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("invalid_quiet_hours", "quiet_hours times must be HH:mm");
        }
    }

    public static StrategyMatcher.Strategy toStrategy(JsonNode r) {
        return new StrategyMatcher.Strategy(Rows.uuid(r, "id"), Rows.str(r, "code"), Rows.str(r, "exposure_type"),
                Rows.integer(r, "min_dpd"), Rows.integer(r, "max_dpd"), Rows.integer(r, "priority"),
                Rows.bool(r, "is_active"), StrategyMatcher.SegmentRules.parse(r.get("segment_rules")));
    }
}
