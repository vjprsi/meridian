package io.meridian.fraud.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.expr.ExpressionException;
import io.meridian.fraud.domain.expr.RuleExpression;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fraud rules (validation, versioning, maker-checker activation, backtests) and decision policies. */
@Service
public class FraudRuleService {
    private static final Set<String> EDITABLE = Set.of("DRAFT", "DISABLED");
    private static final int MAX_BACKTEST = 50_000;
    /** Approval resource type for rule activation submissions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "fraud_rule";
    private static final String CLEAR_SUBMISSION = "submitted_mode = null, submitted_by = null, submitted_at = null";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final FraudConfigCache cache;
    private final JsonMapper json;
    private final Approvals approvals;

    public FraudRuleService(Db db, CrudEngine crud, OutboxWriter outbox, FraudConfigCache cache, JsonMapper json,
                            Approvals approvals) {
        this.approvals = approvals;
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.cache = cache;
        this.json = json;
    }

    /** Validates a new rule (expression must compile) before the generic idempotent insert. */
    public void validateCreate(JsonNode body) {
        compile(body.path("expression").asString(null));
        if ("ALLOW".equals(Db.str(body, "action")) && body.path("score_adjustment").asInt(0) > 0) {
            throw ApiException.unprocessable("invalid_score_adjustment", "An ALLOW rule cannot add risk points");
        }
        validWindow(body);
    }

    /** Edits are allowed only while the rule is not live; every edit bumps rule_version and needs re-activation. */
    @Transactional
    public String update(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode rule = db.require("fraud_rule", id, true);
        if (!EDITABLE.contains(rule.get("status").asString())) {
            throw ApiException.conflict("rule_live", "Rule " + rule.get("code").asString() + " is "
                    + rule.get("status").asString() + "; deactivate it before editing");
        }
        if (rule.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode values = crud.toColumns("fraud_rule", changes);
        for (String f : List.of("status", "rule_version", "approved_by", "deactivation_reason", "submitted_mode",
                "submitted_by", "submitted_at", "rejected_by", "rejection_reason", "approval_request_id")) {
            values.remove(f);
        }
        if (values.has("expression")) {
            compile(values.get("expression").asString());
        }
        if (values.isEmpty()) {
            return db.text(rule);
        }
        values.put("rule_version", rule.get("rule_version").asInt() + 1);
        values.putNull("approved_by");
        boolean pending = rule.hasNonNull("submitted_at");
        if (pending) { // an edit withdraws the pending activation submission
            values.putNull("submitted_mode");
            values.putNull("submitted_by");
            values.putNull("submitted_at");
        }
        String row = crud.updateRow("fraud_rule", Map.of("id", id), values, expectedVersion).orElseThrow();
        if (pending) {
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rule changed after submission");
        }
        outbox.publish("risk.fraud_rule.changed", "fraud_rule", id, json.readTree(row));
        cache.invalidate();
        return row;
    }

    /**
     * Maker step: asks for activation in a mode. The rule keeps its status until a checker activates it (directly or
     * in the workflow inbox, {@link FraudRuleApprovalHandler}) or rejects the submission. A new submission replaces
     * a pending one; an edit withdraws it.
     */
    @Transactional
    public String submit(UUID id, String mode, String comment) {
        requireMode(mode);
        JsonNode rule = db.require("fraud_rule", id, true);
        if (rule.get("status").asString().equals(mode)) {
            throw ApiException.conflict("invalid_transition", "Rule is already " + mode);
        }
        compile(rule.get("expression").asString());
        OffsetDateTime at = Clocks.now();
        db.update("update fraud_rule set submitted_mode = ?, submitted_by = ?, submitted_at = ?, rejected_by = null,"
                + " rejection_reason = null where id = ?", mode, TenantContext.principal(), at, id);
        int ruleVersion = rule.get("rule_version").asInt();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", Db.str(rule, "code"));
        payload.put("name", Db.str(rule, "name"));
        payload.put("subject_type", Db.str(rule, "subject_type"));
        payload.put("action", Db.str(rule, "action"));
        payload.put("score_adjustment", rule.path("score_adjustment").asInt());
        payload.put("current_status", Db.str(rule, "status"));
        payload.put("mode", mode);
        payload.put("rule_version", ruleVersion);
        payload.put("submitted_at", at.toString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "activate",
                "Fraud rule " + Db.str(rule, "code") + " v" + ruleVersion + " -> " + mode, payload),
                "fraud-rule:" + id + ":" + ruleVersion + ":" + at);
        UUID requestId = Approvals.idOf(request);
        if (requestId != null) {
            db.update("update fraud_rule set approval_request_id = ? where id = ?", requestId, id);
            approvals.cancelOpenExcept(APPROVAL_RESOURCE, id, null, requestId, "superseded");
        }
        String row = db.text(db.require("fraud_rule", id, false));
        outbox.publish("risk.fraud_rule.submitted", "fraud_rule", id, json.readTree(row));
        return row;
    }

    /** True while the rule waits on the activation submission opened as {@code approvalRequestId}. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, UUID approvalRequestId) {
        return pending(id)
                .filter(r -> approvalRequestId != null && approvalRequestId.toString().equals(Db.str(r, "approval_request_id")))
                .isPresent();
    }

    /** The rule while an activation submission is pending. */
    @Transactional(readOnly = true)
    public java.util.Optional<JsonNode> pending(UUID id) {
        return db.one("select row_to_json(t)::text from fraud_rule t where id = ?", id)
                .filter(r -> r.hasNonNull("submitted_at"));
    }

    /** Maker-checker: the activating user must differ from the author of the rule version and from the submitter. */
    @Transactional
    public String activate(UUID id, String mode) {
        requireMode(mode);
        JsonNode rule = db.require("fraud_rule", id, true);
        String status = rule.get("status").asString();
        if (status.equals(mode)) {
            throw ApiException.conflict("invalid_transition", "Rule is already " + mode);
        }
        String maker = Db.str(rule, "updated_by") != null ? Db.str(rule, "updated_by") : Db.str(rule, "created_by");
        String checker = TenantContext.principal();
        if (checker.equals(maker) || checker.equals(Db.str(rule, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The rule version was authored or submitted by " + checker + "; another user must activate it");
        }
        compile(rule.get("expression").asString());
        // updated_by keeps naming the author of the rule version (the maker); the checker is approved_by
        db.update("update fraud_rule set status = ?, approved_by = ?, deactivation_reason = null, " + CLEAR_SUBMISSION
                + " where id = ?", mode, checker, id);
        String row = db.text(db.require("fraud_rule", id, false));
        outbox.publish("risk.fraud_rule.activated", "fraud_rule", id, json.readTree(row));
        cache.invalidate();
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "activated directly");
        return row;
    }

    /** Checker step: rejects the pending activation submission; the rule keeps its status and can be reworked. */
    @Transactional
    public String reject(UUID id, String reason) {
        JsonNode rule = db.require("fraud_rule", id, true);
        if (!rule.hasNonNull("submitted_at")) {
            throw ApiException.conflict("no_pending_submission", "Rule " + Db.str(rule, "code") + " has no pending activation");
        }
        String checker = TenantContext.principal();
        if (checker.equals(Db.str(rule, "submitted_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The submitter cannot reject their own submission");
        }
        db.update("update fraud_rule set rejected_by = ?, rejection_reason = ?, " + CLEAR_SUBMISSION + " where id = ?",
                checker, reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason, id);
        String row = db.text(db.require("fraud_rule", id, false));
        outbox.publish("risk.fraud_rule.rejected", "fraud_rule", id, json.readTree(row));
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return row;
    }

    private static void requireMode(String mode) {
        if (!"ACTIVE".equals(mode) && !"SHADOW".equals(mode)) {
            throw ApiException.unprocessable("invalid_mode", "mode must be ACTIVE or SHADOW");
        }
    }

    @Transactional
    public String deactivate(UUID id, String reason) {
        JsonNode rule = db.require("fraud_rule", id, true);
        if ("DISABLED".equals(rule.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Rule is already disabled");
        }
        db.update("update fraud_rule set status = 'DISABLED', deactivation_reason = ?, " + CLEAR_SUBMISSION
                + " where id = ?", reason, id);
        String row = db.text(db.require("fraud_rule", id, false));
        outbox.publish("risk.fraud_rule.deactivated", "fraud_rule", id, json.readTree(row));
        cache.invalidate();
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rule deactivated");
        return row;
    }

    /** Compiles and evaluates an expression against a sample event without touching any data. */
    public Map<String, Object> testExpression(String expression, JsonNode sample) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            RuleExpression e = RuleExpression.compile(expression);
            @SuppressWarnings("unchecked")
            Map<String, Object> facts = sample != null && sample.isObject() ? json.convertValue(sample, LinkedHashMap.class) : Map.of();
            Set<String> lists = new java.util.HashSet<>();
            Object hits = facts.get("lists") instanceof Map<?, ?> m ? m.get("hits") : null;
            if (hits instanceof List<?> l) {
                l.forEach(x -> lists.add(String.valueOf(x)));
            }
            Object result = e.evaluate(RuleExpression.Bindings.of(facts, lists::contains));
            out.put("valid", true);
            out.put("result", result instanceof java.math.BigDecimal d ? d.toPlainString() : result);
            out.put("matched", Boolean.TRUE.equals(result));
            out.put("identifiers", List.copyOf(e.identifiers()));
            out.put("list_codes", List.copyOf(e.listCodes()));
        } catch (ExpressionException ex) {
            out.put("valid", false);
            out.put("error", ex.getMessage());
            out.put("error_position", ex.position());
        }
        return out;
    }

    /**
     * Replays the rule over stored evaluations of its subject type: hit rate, decisions it would change, and
     * precision / recall against outcome labels from fraud_feedback.
     */
    public Map<String, Object> backtest(UUID id, OffsetDateTime from, OffsetDateTime to, Integer maxEvents) {
        if (!to.isAfter(from)) {
            throw ApiException.unprocessable("invalid_period", "to_ts must be after from_ts");
        }
        int max = maxEvents == null ? 10_000 : Math.max(1, Math.min(MAX_BACKTEST, maxEvents));
        JsonNode rule = db.require("fraud_rule", id, false);
        RuleExpression expr = compile(rule.get("expression").asString());
        String action = rule.get("action").asString();
        int adjustment = rule.get("score_adjustment").asInt();
        String subjectType = rule.get("subject_type").asString();
        DecisionEngine.Policy policy = cache.current().policy(subjectType);
        List<JsonNode> rows = db.list("""
                select json_build_object('id', s.id, 'score', s.score, 'decision', s.decision, 'features', s.features,
                       'label', (select f.label from fraud_feedback f where f.fraud_score_id = s.id
                                 order by f.reported_at desc limit 1))::text
                from fraud_score s
                where s.subject_type = ? and s.created_at >= ? and s.created_at < ?
                order by s.created_at desc limit ?""", subjectType, from, to, max);
        int hits = 0;
        int changes = 0;
        int errors = 0;
        int labelledFraud = 0;
        int labelledGenuine = 0;
        int tp = 0;
        int fp = 0;
        List<String> sample = new ArrayList<>();
        for (JsonNode r : rows) {
            String label = Db.str(r, "label");
            if ("FRAUD".equals(label)) {
                labelledFraud++;
            } else if ("NOT_FRAUD".equals(label)) {
                labelledGenuine++;
            }
            boolean fired;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> facts = r.get("features").isObject()
                        ? json.convertValue(r.get("features"), LinkedHashMap.class) : Map.of();
                Set<String> lists = new java.util.HashSet<>();
                if (facts.get("lists") instanceof Map<?, ?> m && m.get("hits") instanceof List<?> l) {
                    l.forEach(x -> lists.add(String.valueOf(x)));
                }
                fired = expr.test(RuleExpression.Bindings.of(facts, lists::contains));
            } catch (RuntimeException e) {
                errors++;
                continue;
            }
            if (!fired) {
                continue;
            }
            hits++;
            if (sample.size() < 20) {
                sample.add(r.get("id").asString());
            }
            if ("FRAUD".equals(label)) {
                tp++;
            } else if ("NOT_FRAUD".equals(label)) {
                fp++;
            }
            DecisionEngine.Decision before = DecisionEngine.Decision.valueOf(r.get("decision").asString());
            if (wouldChange(action, adjustment, before, r.get("score").asInt(), policy)) {
                changes++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule_id", id.toString());
        out.put("rule_code", rule.get("code").asString());
        out.put("rule_version", rule.get("rule_version").asInt());
        out.put("from_ts", from.toString());
        out.put("to_ts", to.toString());
        out.put("evaluated", rows.size());
        out.put("truncated", rows.size() == max);
        out.put("hits", hits);
        out.put("hit_rate", rows.isEmpty() ? 0.0 : round4((double) hits / rows.size()));
        out.put("decisions_changed", changes);
        out.put("labelled_fraud", labelledFraud);
        out.put("labelled_not_fraud", labelledGenuine);
        out.put("true_positives", tp);
        out.put("false_positives", fp);
        out.put("precision", tp + fp == 0 ? null : round4((double) tp / (tp + fp)));
        out.put("recall", labelledFraud == 0 ? null : round4((double) tp / labelledFraud));
        out.put("evaluation_errors", errors);
        out.put("sample_hit_score_ids", sample);
        return out;
    }

    static boolean wouldChange(String action, int adjustment, DecisionEngine.Decision before, int score,
                               DecisionEngine.Policy policy) {
        return switch (action) {
            case "BLOCK" -> before != DecisionEngine.Decision.BLOCK;
            case "ALLOW" -> before != DecisionEngine.Decision.ALLOW;
            case "REVIEW" -> before == DecisionEngine.Decision.ALLOW || before == DecisionEngine.Decision.STEP_UP;
            case "STEP_UP" -> before == DecisionEngine.Decision.ALLOW;
            case "SCORE_ADJUST" -> policy.forScore(Math.max(0, Math.min(1000, score + adjustment))) != before;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ decision policies

    public void validatePolicy(JsonNode current, JsonNode changes) {
        int stepUp = pick(changes, current, "step_up_threshold");
        int review = pick(changes, current, "review_threshold");
        int block = pick(changes, current, "block_threshold");
        if (stepUp < 0 || block > 1000 || !(stepUp <= review && review <= block)) {
            throw ApiException.unprocessable("invalid_thresholds",
                    "Thresholds must satisfy 0 <= step_up <= review <= block <= 1000");
        }
        int timeout = changes.hasNonNull("timeout_ms") ? changes.get("timeout_ms").asInt()
                : current != null && current.hasNonNull("timeout_ms") ? current.get("timeout_ms").asInt() : 50;
        if (timeout < 5 || timeout > 2000) {
            throw ApiException.unprocessable("invalid_timeout", "timeout_ms must be between 5 and 2000");
        }
        if ("BLOCK".equals(Db.str(changes, "fallback_decision"))) {
            // a blocking fallback turns a scoring outage into a payments outage
            throw ApiException.unprocessable("invalid_fallback", "fallback_decision cannot be BLOCK");
        }
    }

    public void policyChanged() {
        cache.invalidate();
    }

    private static int pick(JsonNode changes, JsonNode current, String f) {
        if (changes.hasNonNull(f)) {
            return changes.get(f).asInt();
        }
        if (current != null && current.hasNonNull(f)) {
            return current.get(f).asInt();
        }
        throw ApiException.unprocessable(f + "_required", f + " is required");
    }

    private static void validWindow(JsonNode body) {
        if (body.hasNonNull("valid_from") && body.hasNonNull("valid_to")
                && !OffsetDateTime.parse(body.get("valid_to").asString()).isAfter(OffsetDateTime.parse(body.get("valid_from").asString()))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must be after valid_from");
        }
    }

    static RuleExpression compile(String expression) {
        try {
            return RuleExpression.compile(expression);
        } catch (ExpressionException e) {
            throw ApiException.unprocessable("invalid_expression", e.getMessage());
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
