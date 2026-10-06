package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.domain.AuthDecisionEngine;
import io.meridian.cardauthorization.domain.AuthRule;
import io.meridian.cardauthorization.domain.Expression;
import io.meridian.cardauthorization.infrastructure.AuthStore;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Authorisation rule validation and the rule test bench. */
@Service
public class RuleService {
    private final AuthStore store;
    private final RuleCache cache;
    private final AuthDecisionEngine engine;

    public RuleService(AuthStore store, RuleCache cache, AuthDecisionEngine engine) {
        this.store = store;
        this.cache = cache;
        this.engine = engine;
    }

    /** Validates a create / patch body: expression syntax, decline reason only with DECLINE, validity window. */
    public void validate(JsonNode body, JsonNode existing) {
        String expr = text(body, "condition_expression");
        if (expr != null) {
            parse(expr);
        }
        String action = text(body, "action") != null ? text(body, "action") : existing == null ? null : text(existing, "action");
        String reason = body.has("decline_reason") ? text(body, "decline_reason")
                : existing == null ? null : text(existing, "decline_reason");
        if (reason != null && !"DECLINE".equals(action)) {
            throw ApiException.unprocessable("decline_reason_not_allowed", "decline_reason applies only to DECLINE rules");
        }
        String from = text(body, "valid_from") != null ? text(body, "valid_from") : existing == null ? null : text(existing, "valid_from");
        String to = text(body, "valid_to") != null ? text(body, "valid_to") : existing == null ? null : text(existing, "valid_to");
        if (from != null && to != null && !java.time.OffsetDateTime.parse(from).isBefore(java.time.OffsetDateTime.parse(to))) {
            throw ApiException.unprocessable("invalid_validity", "valid_from must be before valid_to");
        }
    }

    public void invalidate() {
        cache.invalidate();
    }

    static Expression parse(String expr) {
        try {
            return Expression.parse(expr);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_expression", "condition_expression: " + e.getMessage());
        }
    }

    /** Evaluates one expression, or the active rules (of a program + global), against a sample context. */
    public ObjectNode test(JsonNode sample, UUID programId, String expression) {
        Map<String, Object> vars = new HashMap<>();
        sample.propertyNames().forEach(k -> {
            JsonNode v = sample.get(k);
            vars.put(k, v == null || v.isNull() ? null : v.isNumber() ? new BigDecimal(v.asString())
                    : v.isBoolean() ? (Object) v.asBoolean() : v.isArray() ? toList(v) : v.asString());
        });
        List<AuthRule> rules;
        if (expression != null) {
            rules = List.of(new AuthRule(null, programId, "adhoc", 0, parse(expression), "DECLINE", null, true, null, null));
        } else {
            rules = new ArrayList<>();
            for (JsonNode n : store.query("""
                    select row_to_json(r)::text from card_authorization_rule r where is_active
                      and (card_program_id is null or card_program_id = ?) and (valid_from is null or valid_from <= now())
                      and (valid_to is null or valid_to > now()) order by priority, id""", programId)) {
                try {
                    rules.add(RuleCache.compile(n));
                } catch (IllegalArgumentException e) {
                    // stored before validation existed: reported as non-matching
                }
            }
        }
        List<AuthDecisionEngine.RuleHit> hits = engine.explain(rules, vars);
        ObjectNode out = store.json().createObjectNode();
        AuthDecisionEngine.RuleHit first = hits.stream().filter(AuthDecisionEngine.RuleHit::matched).findFirst().orElse(null);
        out.put("matched", first != null);
        if (first != null) {
            if (first.ruleId() != null) {
                out.put("rule_id", first.ruleId().toString());
            }
            out.put("action", first.action());
            rules.stream().filter(r -> java.util.Objects.equals(r.id(), first.ruleId())).findFirst()
                    .ifPresent(r -> out.put("decline_reason", "DECLINE".equals(r.action())
                            ? (r.declineReason() == null ? "RULE_DECLINED" : r.declineReason()) : null));
        }
        var arr = out.putArray("evaluations");
        for (AuthDecisionEngine.RuleHit h : hits) {
            ObjectNode o = arr.addObject();
            if (h.ruleId() != null) {
                o.put("rule_id", h.ruleId().toString());
            }
            o.put("name", h.name());
            o.put("priority", h.priority());
            o.put("matched", h.matched());
            o.put("action", h.action());
            if (h.error() != null) {
                o.put("error", h.error());
            }
        }
        return out;
    }

    private static List<Object> toList(JsonNode arr) {
        List<Object> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.isNumber() ? new BigDecimal(v.asString()) : v.asString()));
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }
}
