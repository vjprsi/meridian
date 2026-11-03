package io.meridian.limits.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.limits.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WE-LIMIT-1: maker-checker lifecycle of the two TCP authoring tiers and the transfer-rule matrix. The lifecycle
 * mirrors {@link LimitAdminService} for {@code limit_rule} (submit by a maker, activate/reject by a different checker,
 * deactivate), so the same four-eyes rules and workflow-service integration apply. Activation of an instrument TCP
 * additionally enforces that its Rank-2 caps are a subset of the matching Rank-1 cumulative profile (MQ-TCP-3).
 * Activation / deactivation invalidate the rule cache so the {@link TcpCompiler} recompiles.
 */
@Service
public class TcpAdminService {
    public static final String TCP = "transfer_control_profile";
    public static final String INSTRUMENT_TCP = "instrument_tcp";
    public static final String TRANSFER_RULE = "transfer_rule";
    private static final String CLEAR = "submitted_by = null, submitted_at = null";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final LimitRuleCache cache;
    private final JsonMapper json;
    private final Approvals approvals;

    public TcpAdminService(Db db, CrudEngine crud, OutboxWriter outbox, LimitRuleCache cache, JsonMapper json,
                           Approvals approvals) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.cache = cache;
        this.json = json;
        this.approvals = approvals;
    }

    // ------------------------------------------------------------------ validation (create / update)

    public void validate(String table, JsonNode r) {
        switch (table) {
            case TCP -> {
                requireCurrencyForAmounts(r, "daily_amount", "weekly_amount", "monthly_amount", "yearly_amount", "max_balance");
                if (noneSet(r, "daily_amount", "daily_count", "weekly_amount", "weekly_count", "monthly_amount",
                        "monthly_count", "yearly_amount", "yearly_count", "max_balance")) {
                    throw ApiException.unprocessable("empty_profile", "A cumulative TCP needs at least one cap");
                }
            }
            case INSTRUMENT_TCP -> {
                if (Db.str(r, "currency") == null) {
                    throw ApiException.unprocessable("currency_required", "An instrument TCP needs a currency");
                }
                BigDecimal pct = Db.dec(r, "max_transfer_pct");
                if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.ONE) > 0)) {
                    throw ApiException.unprocessable("invalid_pct", "max_transfer_pct is a fraction between 0 and 1");
                }
            }
            case TRANSFER_RULE -> {
                BigDecimal min = Db.dec(r, "min_amount");
                BigDecimal max = Db.dec(r, "max_amount");
                if (min != null && max != null && min.compareTo(max) > 0) {
                    throw ApiException.unprocessable("invalid_bounds", "min_amount must not exceed max_amount");
                }
            }
            default -> throw new IllegalArgumentException(table);
        }
        if (r.hasNonNull("valid_from") && r.hasNonNull("valid_to")
                && !OffsetDateTime.parse(r.get("valid_to").asString()).isAfter(OffsetDateTime.parse(r.get("valid_from").asString()))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must be after valid_from");
        }
    }

    // ------------------------------------------------------------------ update (only while not ACTIVE)

    @Transactional
    public String update(String table, UUID id, JsonNode changes, int expectedVersion) {
        JsonNode row = db.require(table, id, true);
        if ("ACTIVE".equals(Db.str(row, "status"))) {
            throw ApiException.conflict("row_active", "Deactivate " + Db.str(row, "code") + " before changing it");
        }
        if (row.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict",
                    "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        var values = crud.toColumns(table, changes);
        for (String f : new String[] {"status", "approved_by", "deactivation_reason", "submitted_by", "submitted_at",
                "rejected_by", "rejection_reason", "approval_request_id"}) {
            values.remove(f);
        }
        JsonNode merged = ((tools.jackson.databind.node.ObjectNode) row.deepCopy()).setAll(values);
        validate(table, merged);
        boolean pending = row.hasNonNull("submitted_at");
        if (pending) {
            values.putNull("submitted_by");
            values.putNull("submitted_at");
        }
        String out = crud.updateRow(table, Map.of("id", id), values, expectedVersion).orElseThrow();
        if (pending) {
            approvals.cancelOpen(table, id, null, "changed after submission");
        }
        cache.invalidate();
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    @Transactional
    public String submit(String table, String resourceType, UUID id, String comment) {
        JsonNode r = db.require(table, id, true);
        if ("ACTIVE".equals(Db.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Already active");
        }
        validate(table, r);
        OffsetDateTime at = io.meridian.common.time.Clocks.now();
        db.update("update " + table + " set submitted_by = ?, submitted_at = ?, rejected_by = null,"
                + " rejection_reason = null where id = ?", TenantContext.principal(), at, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Db.str(r, "code"));
        payload.put("name", Db.str(r, "name"));
        payload.put("row_version", r.get("row_version").asInt());
        payload.put("submitted_at", at.toString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        ApprovalSpec spec = ApprovalSpec.of(resourceType, id, "activate", label(table) + " " + Db.str(r, "code"), payload);
        UUID requestId = Approvals.idOf(approvals.request(spec,
                resourceType + ":" + id + ":" + r.get("row_version").asInt() + ":" + at));
        if (requestId != null) {
            db.update("update " + table + " set approval_request_id = ? where id = ?", requestId, id);
            approvals.cancelOpenExcept(resourceType, id, null, requestId, "superseded");
        }
        return publish(table, id, event(table, "submitted"));
    }

    @Transactional(readOnly = true)
    public boolean awaitsApproval(String table, UUID id, UUID approvalRequestId) {
        return db.one("select row_to_json(t)::text from " + table + " t where id = ?", id)
                .filter(r -> r.hasNonNull("submitted_at"))
                .filter(r -> approvalRequestId != null
                        && approvalRequestId.toString().equals(Db.str(r, "approval_request_id")))
                .isPresent();
    }

    @Transactional
    public String activate(String table, String resourceType, UUID id) {
        JsonNode r = db.require(table, id, true);
        if ("ACTIVE".equals(Db.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Already active");
        }
        String maker = Db.str(r, "updated_by") != null ? Db.str(r, "updated_by") : Db.str(r, "created_by");
        String checker = TenantContext.principal();
        if (checker.equals(maker) || checker.equals(Db.str(r, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The row was last changed or submitted by " + checker + "; another user must activate it");
        }
        validate(table, r);
        if (INSTRUMENT_TCP.equals(table)) {
            validateRankSubset(r);
        }
        db.update("update " + table + " set status = 'ACTIVE', approved_by = ?, deactivation_reason = null, "
                + CLEAR + " where id = ?", checker, id);
        approvals.cancelOpen(resourceType, id, null, "activated directly");
        cache.invalidate();
        return publish(table, id, event(table, "activated"));
    }

    @Transactional
    public String reject(String table, String resourceType, UUID id, String reason) {
        JsonNode r = db.require(table, id, true);
        if (!r.hasNonNull("submitted_at")) {
            throw ApiException.conflict("no_pending_submission", "No pending activation");
        }
        if (TenantContext.principal().equals(Db.str(r, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The submitter cannot reject their own submission");
        }
        db.update("update " + table + " set rejected_by = ?, rejection_reason = ?, " + CLEAR + " where id = ?",
                TenantContext.principal(), reason, id);
        approvals.cancelOpen(resourceType, id, null, "rejected directly");
        return publish(table, id, event(table, "rejected"));
    }

    @Transactional
    public String deactivate(String table, UUID id, String reason) {
        JsonNode r = db.require(table, id, true);
        if (!"ACTIVE".equals(Db.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "Not active");
        }
        db.update("update " + table + " set status = 'INACTIVE', deactivation_reason = ?, " + CLEAR + " where id = ?",
                reason, id);
        cache.invalidate();
        return publish(table, id, event(table, "deactivated"));
    }

    // ------------------------------------------------------------------ Rank-2 subset of Rank-1 (MQ-TCP-3)

    /**
     * An instrument (Rank-2) TCP may not grant more head-room than the cumulative (Rank-1) profile it sits under: for
     * each window where both define an amount cap, the instrument cap must be {@code <=} the cumulative cap. The
     * cumulative profiles considered are the ACTIVE ones for the same currency and (when the instrument fixes a
     * direction) the same direction, whose domain/category are unset or equal to the instrument's.
     */
    void validateRankSubset(JsonNode itc) {
        String currency = Db.str(itc, "currency");
        String direction = Db.str(itc, "direction");
        List<JsonNode> cumulative = db.list("""
                select row_to_json(t)::text from transfer_control_profile t
                where status = 'ACTIVE' and currency = ?""", currency);
        for (JsonNode cum : cumulative) {
            if (direction != null && !direction.equals(Db.str(cum, "direction"))) {
                continue;
            }
            subsetViolation(itc, cum).ifPresent(msg -> {
                throw ApiException.unprocessable("rank2_exceeds_rank1", msg);
            });
        }
    }

    /** Pure Rank-2 ⊆ Rank-1 check for one (instrument, cumulative) pair; empty when the instrument is a subset. */
    static java.util.Optional<String> subsetViolation(JsonNode itc, JsonNode cum) {
        if (!compatible(Db.str(cum, "domain_code"), Db.str(itc, "domain_code"))
                || !compatible(Db.str(cum, "category_code"), Db.str(itc, "category_code"))) {
            return java.util.Optional.empty();
        }
        for (String field : new String[] {"daily_amount", "monthly_amount", "max_balance"}) {
            BigDecimal rank2 = Db.dec(itc, field);
            BigDecimal rank1 = Db.dec(cum, field);
            if (rank2 != null && rank1 != null && rank2.compareTo(rank1) > 0) {
                return java.util.Optional.of("Instrument TCP " + field + " " + rank2.toPlainString()
                        + " exceeds the cumulative profile " + Db.str(cum, "code") + " cap " + rank1.toPlainString());
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean compatible(String cumulative, String instrument) {
        return cumulative == null || cumulative.equalsIgnoreCase(instrument);
    }

    // ------------------------------------------------------------------ transfer-rule matrix evaluation (MQ-TR-1/2/3)

    @Transactional(readOnly = true)
    public Map<String, Object> evaluateTransferRules(JsonNode b) {
        TenantContext.require();
        List<JsonNode> rules = db.list("""
                select row_to_json(t)::text from transfer_rule t where status = 'ACTIVE'
                  and (valid_from is null or valid_from <= now()) and (valid_to is null or valid_to > now())
                order by priority, code""");
        JsonNode decider = null;
        for (JsonNode r : rules) {
            if (transferRuleMatches(r, b)) {
                decider = r;
                break; // lowest priority (highest precedence) wins
            }
        }
        boolean allowed = decider == null || Db.bool(decider, "allow");
        String reason = null;
        if (decider != null) {
            BigDecimal amount = Db.dec(b, "amount");
            reason = amountReason(decider, amount);
            if (reason != null) {
                allowed = false;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", allowed);
        out.put("matched_rule_code", decider == null ? null : Db.str(decider, "code"));
        out.put("effect", decider == null ? "DEFAULT_ALLOW" : Db.bool(decider, "allow") ? "ALLOW" : "DENY");
        if (reason != null) {
            out.put("reason", reason);
        }
        return out;
    }

    static boolean transferRuleMatches(JsonNode r, JsonNode b) {
        String scope = Db.str(r, "hierarchy_scope");
        if (scope != null && !"ANY".equals(scope) && !scope.equalsIgnoreCase(Db.str(b, "hierarchy_scope"))) {
            return false;
        }
        return dim(r, b, "service_type", "service_type")
                && dim(r, b, "sender_domain_code", "sender_domain_code")
                && dim(r, b, "sender_category_code", "sender_category_code")
                && dim(r, b, "sender_grade_code", "sender_grade_code")
                && dim(r, b, "sender_instrument", "sender_instrument")
                && dim(r, b, "sender_bank_code", "sender_bank_code")
                && dim(r, b, "receiver_domain_code", "receiver_domain_code")
                && dim(r, b, "receiver_category_code", "receiver_category_code")
                && dim(r, b, "receiver_grade_code", "receiver_grade_code")
                && dim(r, b, "receiver_instrument", "receiver_instrument")
                && dim(r, b, "receiver_bank_code", "receiver_bank_code");
    }

    private static boolean dim(JsonNode rule, JsonNode req, String ruleField, String reqField) {
        String v = Db.str(rule, ruleField);
        return v == null || v.equalsIgnoreCase(Db.str(req, reqField));
    }

    static String amountReason(JsonNode r, BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal min = Db.dec(r, "min_amount");
        BigDecimal max = Db.dec(r, "max_amount");
        BigDecimal multiple = Db.dec(r, "amount_multiple");
        if (min != null && amount.compareTo(min) < 0) {
            return "amount below the rule minimum " + min.toPlainString();
        }
        if (max != null && amount.compareTo(max) > 0) {
            return "amount above the rule maximum " + max.toPlainString();
        }
        if (multiple != null && multiple.signum() > 0 && amount.remainder(multiple).signum() != 0) {
            return "amount is not a multiple of " + multiple.toPlainString();
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private String publish(String table, UUID id, String event) {
        JsonNode row = db.require(table, id, false);
        outbox.publish(event, table, id, row);
        return db.text(row);
    }

    private static String event(String table, String verb) {
        return "risk." + table + "." + verb;
    }

    private static String label(String table) {
        return switch (table) {
            case TCP -> "Cumulative TCP";
            case INSTRUMENT_TCP -> "Instrument TCP";
            default -> "Transfer rule";
        };
    }

    private static void requireCurrencyForAmounts(JsonNode r, String... amountFields) {
        for (String f : amountFields) {
            if (Db.dec(r, f) != null && Db.str(r, "currency") == null) {
                throw ApiException.unprocessable("currency_required", "Amount caps need a currency");
            }
        }
    }

    private static boolean noneSet(JsonNode r, String... fields) {
        for (String f : fields) {
            if (r.hasNonNull(f)) {
                return false;
            }
        }
        return true;
    }
}
