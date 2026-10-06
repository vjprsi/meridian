package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.rewards.api.generated.model.RewardRule;
import io.meridian.rewards.api.generated.model.RewardsProgram;
import io.meridian.rewards.domain.Lifecycles;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Program set-up: programs (maker-checker activation), tiers, earn rules and catalogue items. */
@Service
public class ProgramService {
    static final String PROGRAM = "rewards_program";
    static final String TIER = "reward_tier";
    static final String RULE = "reward_rule";
    static final String ITEM = "reward_catalog_item";
    private static final Set<String> CASH_PROGRAMS = Set.of("CASHBACK", "HYBRID");

    private final Rows rows;
    private final Patcher patcher;
    private final Events events;

    public ProgramService(Rows rows, Patcher patcher, Events events) {
        this.rows = rows;
        this.patcher = patcher;
        this.events = events;
    }

    // ------------------------------------------------------------------ programs

    @Transactional
    public ObjectNode createProgram(Object body) {
        ObjectNode v = patcher.columns(PROGRAM, body);
        if (text(v, "code") != null) {
            v.put("code", text(v, "code").trim().toUpperCase());
        }
        validateProgram(v);
        if (rows.count("select count(*) from rewards_program where code = ?", text(v, "code")) > 0) {
            throw ApiException.conflict("duplicate_code", "A rewards program with code " + text(v, "code") + " exists");
        }
        v.put("status", "DRAFT");
        return rows.insert(PROGRAM, v);
    }

    @Transactional
    public ObjectNode updateProgram(UUID id, Object body, String ifMatch) {
        return patcher.patch(PROGRAM, id, body, ifMatch, (cur, merged) -> {
            if (!Objects.equals(text(cur, "code"), text(merged, "code"))) {
                throw ApiException.unprocessable("immutable_field", "code cannot change");
            }
            if (!"DRAFT".equals(text(cur, "status")) && !Objects.equals(text(cur, "program_type"), text(merged, "program_type"))) {
                throw ApiException.unprocessable("immutable_field", "program_type can only change in DRAFT");
            }
            if ("ENDED".equals(text(cur, "status"))) {
                throw ApiException.conflict("invalid_transition", "An ended program cannot be changed");
            }
            validateProgram(merged);
        });
    }

    private static void validateProgram(ObjectNode v) {
        Problems p = new Problems("invalid_program");
        String code = text(v, "code");
        p.check(code != null && Problems.CODE.matcher(code).matches(), "code", "invalid_code", "code must be A-Z, 0-9, _ or -");
        p.positive(Json.decimal(v, "point_value"), "point_value");
        Integer expiry = v.hasNonNull("points_expiry_months") ? v.get("points_expiry_months").asInt() : null;
        p.check(expiry == null || (expiry >= 1 && expiry <= 120), "points_expiry_months", "out_of_range", "points_expiry_months must be 1-120");
        int period = Json.integer(v, "tier_period_months", 12);
        p.check(period >= 1 && period <= 60, "tier_period_months", "out_of_range", "tier_period_months must be 1-60");
        if (CASH_PROGRAMS.contains(text(v, "program_type"))) {
            p.check(text(v, "value_currency") != null, "value_currency", "required", "value_currency is required for cash rewards");
        }
        LocalDate start = Json.date(v, "starts_on");
        LocalDate end = Json.date(v, "ends_on");
        p.check(start == null || end == null || !end.isBefore(start), "ends_on", "before_start", "ends_on is before starts_on");
        p.throwIfAny();
    }

    @Transactional
    public ObjectNode activateProgram(UUID id, String comment) {
        ObjectNode r = rows.lock(PROGRAM, id);
        String from = text(r, "status");
        String to = Lifecycles.PROGRAM.apply("activate", from);
        ObjectNode v = rows.object();
        v.put("status", to);
        if ("DRAFT".equals(from)) {
            String checker = TenantContext.principal();
            if (checker.equals(text(r, "created_by"))) {
                throw ApiException.unprocessable("four_eyes_violation", "A program must be activated by a different user than its maker");
            }
            v.put("approved_by", Json.truncate(checker, 64));
            v.put("approved_at", rows.now().toString());
            if (comment != null) {
                v.put("approval_comment", Json.truncate(comment, 500));
            }
        }
        LocalDate ends = Json.date(r, "ends_on");
        if (ends != null && ends.isBefore(rows.now().toLocalDate())) {
            throw ApiException.unprocessable("program_ended", "The program ended on " + ends);
        }
        v.putNull("status_reason");
        ObjectNode row = rows.update(PROGRAM, id, v);
        events.publish("engagement.rewards_program.activated", PROGRAM, row, RewardsProgram.class);
        return row;
    }

    @Transactional
    public ObjectNode pauseProgram(UUID id, String reason) {
        ObjectNode r = rows.lock(PROGRAM, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.PROGRAM.apply("pause", text(r, "status")));
        v.put("status_reason", Json.truncate(reason, 255));
        ObjectNode row = rows.update(PROGRAM, id, v);
        events.publish("engagement.rewards_program.paused", PROGRAM, row, RewardsProgram.class);
        return row;
    }

    @Transactional
    public ObjectNode endProgram(UUID id, LocalDate endsOn, String reason) {
        ObjectNode r = rows.lock(PROGRAM, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.PROGRAM.apply("end", text(r, "status")));
        LocalDate start = Json.date(r, "starts_on");
        if (endsOn.isBefore(start)) {
            throw ApiException.unprocessable("invalid_end_date", "ends_on is before the program start " + start);
        }
        v.put("ends_on", endsOn.toString());
        if (reason != null) {
            v.put("status_reason", Json.truncate(reason, 255));
        }
        ObjectNode row = rows.update(PROGRAM, id, v);
        events.publish("engagement.rewards_program.ended", PROGRAM, row, RewardsProgram.class);
        return row;
    }

    ObjectNode program(UUID id) {
        return rows.find(PROGRAM, id).orElseThrow(() ->
                ApiException.unprocessable("program_not_found", "Rewards program " + id + " does not exist"));
    }

    // ------------------------------------------------------------------ tiers

    @Transactional
    public ObjectNode createTier(Object body) {
        ObjectNode v = patcher.columns(TIER, body);
        ObjectNode program = program(Json.uuid(v, "rewards_program_id"));
        if ("ENDED".equals(text(program, "status"))) {
            throw ApiException.conflict("program_ended", "The program has ended");
        }
        validateTier(v);
        if (rows.count("select count(*) from reward_tier where rewards_program_id = ? and (code = ? or rank = ?)",
                Json.uuid(v, "rewards_program_id"), text(v, "code"), v.path("rank").asInt()) > 0) {
            throw ApiException.conflict("duplicate_tier", "The program already has a tier with this code or rank");
        }
        return rows.insert(TIER, v);
    }

    @Transactional
    public ObjectNode updateTier(UUID id, Object body, String ifMatch) {
        return patcher.patch(TIER, id, body, ifMatch, (cur, merged) -> {
            if (!Objects.equals(text(cur, "rewards_program_id"), text(merged, "rewards_program_id"))) {
                throw ApiException.unprocessable("immutable_field", "rewards_program_id cannot change");
            }
            validateTier(merged);
        });
    }

    @Transactional
    public void deleteTier(UUID id) {
        rows.lock(TIER, id);
        if (rows.count("select count(*) from points_account where reward_tier_id = ?", id) > 0) {
            throw ApiException.conflict("tier_in_use", "Members hold this tier; move them before deleting it");
        }
        rows.execute("delete from reward_tier where id = ?", id);
    }

    private static void validateTier(ObjectNode v) {
        Problems p = new Problems("invalid_tier");
        p.check(v.path("rank").asInt(0) >= 1, "rank", "out_of_range", "rank must be >= 1");
        p.positive(Json.decimal(v, "earn_multiplier"), "earn_multiplier");
        p.nonNegative(Json.decimal(v, "qualify_spend"), "qualify_spend");
        p.check(!v.hasNonNull("qualify_points") || v.get("qualify_points").asLong() >= 0, "qualify_points",
                "must_not_be_negative", "qualify_points must be >= 0");
        p.throwIfAny();
    }

    // ------------------------------------------------------------------ rules

    @Transactional
    public ObjectNode createRule(Object body) {
        ObjectNode v = patcher.columns(RULE, body);
        ObjectNode program = program(Json.uuid(v, "rewards_program_id"));
        if ("ENDED".equals(text(program, "status"))) {
            throw ApiException.conflict("program_ended", "The program has ended");
        }
        validateRule(v, text(program, "program_type"));
        v.put("status", "DRAFT");
        return rows.insert(RULE, v);
    }

    @Transactional
    public ObjectNode updateRule(UUID id, Object body, String ifMatch) {
        return patcher.patch(RULE, id, body, ifMatch, (cur, merged) -> {
            if ("ACTIVE".equals(text(cur, "status"))) {
                throw ApiException.conflict("rule_active", "Deactivate the rule before changing it");
            }
            if (!Objects.equals(text(cur, "rewards_program_id"), text(merged, "rewards_program_id"))) {
                throw ApiException.unprocessable("immutable_field", "rewards_program_id cannot change");
            }
            validateRule(merged, text(program(Json.uuid(cur, "rewards_program_id")), "program_type"));
        });
    }

    static void validateRule(ObjectNode v, String programType) {
        Problems p = new Problems("invalid_rule");
        BigDecimal ppu = Json.decimal(v, "points_per_unit");
        BigDecimal rate = Json.decimal(v, "cashback_rate");
        BigDecimal fixed = Json.decimal(v, "fixed_award");
        p.check(ppu != null || rate != null || fixed != null, "points_per_unit", "no_award",
                "A rule needs points_per_unit, cashback_rate or fixed_award");
        p.positive(ppu, "points_per_unit").positive(rate, "cashback_rate").positive(fixed, "fixed_award");
        p.check(rate == null || rate.compareTo(BigDecimal.ONE) <= 0, "cashback_rate", "out_of_range", "cashback_rate is a fraction <= 1");
        p.positive(Json.decimal(v, "multiplier"), "multiplier").nonNegative(Json.decimal(v, "min_amount"), "min_amount");
        if ("CASHBACK".equals(programType)) {
            p.check(ppu == null, "points_per_unit", "not_applicable", "A cashback program awards no points");
        }
        if ("POINTS".equals(programType) || "MILES".equals(programType)) {
            p.check(rate == null, "cashback_rate", "not_applicable", "A " + programType + " program awards no cashback");
        }
        BigDecimal cap = Json.decimal(v, "cap_amount");
        p.positive(cap, "cap_amount");
        p.check((cap == null) == (text(v, "cap_period") == null), "cap_period", "cap_incomplete",
                "cap_amount and cap_period go together");
        String from = text(v, "valid_time_from");
        String to = text(v, "valid_time_to");
        p.hhmm(from, "valid_time_from").hhmm(to, "valid_time_to");
        p.check((from == null) == (to == null), "valid_time_to", "window_incomplete", "valid_time_from and valid_time_to go together");
        p.zone(text(v, "time_zone"), "time_zone");
        for (JsonNode d : iter(v.get("valid_days_of_week"))) {
            p.check(d.isInt() && d.asInt() >= 1 && d.asInt() <= 7, "valid_days_of_week", "out_of_range", "days are 1 (Monday) to 7");
        }
        for (String f : new String[] {"mcc_include", "mcc_exclude"}) {
            for (JsonNode m : iter(v.get(f))) {
                p.check(Problems.MCC.matcher(m.asString()).matches(), f, "invalid_mcc", "MCC codes have 4 digits");
            }
        }
        p.check(Json.integer(v, "settle_after_days", 0) >= 0 && Json.integer(v, "settle_after_days", 0) <= 365,
                "settle_after_days", "out_of_range", "settle_after_days must be 0-365");
        if (v.hasNonNull("valid_from") && v.hasNonNull("valid_to")) {
            p.check(Json.ts(v, "valid_to").isAfter(Json.ts(v, "valid_from")), "valid_to", "before_start", "valid_to must be after valid_from");
        }
        p.throwIfAny();
    }

    private static Iterable<JsonNode> iter(JsonNode n) {
        return n != null && n.isArray() ? n : java.util.List.of();
    }

    @Transactional
    public ObjectNode activateRule(UUID id) {
        ObjectNode r = rows.lock(RULE, id);
        String to = Lifecycles.RULE.apply("activate", text(r, "status"));
        ObjectNode program = program(Json.uuid(r, "rewards_program_id"));
        if ("ENDED".equals(text(program, "status"))) {
            throw ApiException.conflict("program_ended", "The program has ended");
        }
        ObjectNode v = rows.object();
        v.put("status", to);
        v.putNull("status_reason");
        ObjectNode row = rows.update(RULE, id, v);
        events.publish("engagement.reward_rule.activated", RULE, row, RewardRule.class);
        return row;
    }

    @Transactional
    public ObjectNode deactivateRule(UUID id, String reason) {
        ObjectNode r = rows.lock(RULE, id);
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.RULE.apply("deactivate", text(r, "status")));
        if (reason != null) {
            v.put("status_reason", Json.truncate(reason, 255));
        }
        ObjectNode row = rows.update(RULE, id, v);
        events.publish("engagement.reward_rule.deactivated", RULE, row, RewardRule.class);
        return row;
    }

    // ------------------------------------------------------------------ catalogue

    @Transactional
    public ObjectNode createItem(Object body) {
        ObjectNode v = patcher.columns(ITEM, body);
        program(Json.uuid(v, "rewards_program_id"));
        validateItem(v);
        if (rows.count("select count(*) from reward_catalog_item where rewards_program_id = ? and sku = ?",
                Json.uuid(v, "rewards_program_id"), text(v, "sku")) > 0) {
            throw ApiException.conflict("duplicate_sku", "The program already has an item with SKU " + text(v, "sku"));
        }
        return rows.insert(ITEM, v);
    }

    @Transactional
    public ObjectNode updateItem(UUID id, Object body, String ifMatch) {
        return patcher.patch(ITEM, id, body, ifMatch, (cur, merged) -> {
            if (!Objects.equals(text(cur, "rewards_program_id"), text(merged, "rewards_program_id"))) {
                throw ApiException.unprocessable("immutable_field", "rewards_program_id cannot change");
            }
            validateItem(merged);
        });
    }

    @Transactional
    public void deleteItem(UUID id) {
        rows.lock(ITEM, id);
        if (rows.count("select count(*) from redemption where reward_catalog_item_id = ?", id) > 0) {
            throw ApiException.conflict("item_in_use", "The item has redemptions; deactivate it instead");
        }
        rows.execute("delete from reward_catalog_item where id = ?", id);
    }

    private static void validateItem(ObjectNode v) {
        Problems p = new Problems("invalid_catalog_item");
        p.check(v.path("points_cost").asLong(0) > 0, "points_cost", "must_be_positive", "points_cost must be > 0");
        p.check(!v.hasNonNull("stock") || v.get("stock").asInt() >= 0, "stock", "must_not_be_negative", "stock must be >= 0");
        String type = text(v, "item_type");
        if ("STATEMENT_CREDIT".equals(type) || "CASH_TRANSFER".equals(type)) {
            BigDecimal value = Json.decimal(v, "cash_value");
            p.check(value != null && value.signum() > 0, "cash_value", "required", type + " needs a positive cash_value");
            p.check(text(v, "currency") != null, "currency", "required", type + " needs a currency");
        }
        p.throwIfAny();
    }
}
