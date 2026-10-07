package io.meridian.fraud.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.expr.ExpressionException;
import io.meridian.fraud.domain.expr.RuleExpression;
import io.meridian.fraud.domain.ml.ScoreCombination;
import io.meridian.fraud.infrastructure.Db;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Per-tenant snapshot of the scoring configuration (compiled rules, decision policies, list metadata) so the
 * evaluate hot path does not query or compile it. Writes in this instance invalidate the tenant immediately; other
 * instances pick changes up within the TTL.
 */
@Component
public class FraudConfigCache {
    private static final Logger log = LoggerFactory.getLogger(FraudConfigCache.class);

    public record ListMeta(UUID id, String code, String listType, String entityType) {}

    public record Snapshot(long loadedAtNanos, Map<String, List<DecisionEngine.Rule>> rulesBySubject,
                           Map<String, DecisionEngine.Policy> policies, List<ListMeta> lists) {
        public List<DecisionEngine.Rule> rules(String subjectType) {
            return rulesBySubject.getOrDefault(subjectType, List.of());
        }

        public DecisionEngine.Policy policy(String subjectType) {
            return policies.getOrDefault(subjectType, DecisionEngine.Policy.defaults());
        }
    }

    private final Db db;
    private final long ttlNanos;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    public FraudConfigCache(Db db, @Value("${meridian.fraud.config-ttl-ms:5000}") long ttlMs) {
        this.db = db;
        this.ttlNanos = ttlMs * 1_000_000L;
    }

    public Snapshot current() {
        UUID tenant = TenantContext.require();
        Snapshot s = snapshots.get(tenant);
        if (s == null || System.nanoTime() - s.loadedAtNanos() > ttlNanos) {
            s = load();
            snapshots.put(tenant, s);
        }
        return s;
    }

    public void invalidate() {
        TenantContext.current().ifPresent(snapshots::remove);
    }

    private Snapshot load() {
        Map<String, List<DecisionEngine.Rule>> rules = new HashMap<>();
        for (JsonNode r : db.list("""
                select row_to_json(r)::text from fraud_rule r
                where status in ('ACTIVE', 'SHADOW') and (valid_from is null or valid_from <= now())
                  and (valid_to is null or valid_to > now())""")) {
            try {
                DecisionEngine.Rule rule = new DecisionEngine.Rule(UUID.fromString(r.get("id").asString()),
                        r.get("code").asString(), r.get("rule_version").asInt(), r.get("action").asString(),
                        r.get("score_adjustment").asInt(), r.get("priority").asInt(),
                        "SHADOW".equals(r.get("status").asString()), RuleExpression.compile(r.get("expression").asString()));
                rules.computeIfAbsent(r.get("subject_type").asString(), k -> new ArrayList<>()).add(rule);
            } catch (ExpressionException e) {
                log.warn("fraud rule {} does not compile and is skipped: {}", r.path("code").asString(), e.getMessage());
            }
        }
        rules.values().forEach(l -> l.sort(Comparator.comparingInt(DecisionEngine.Rule::priority)
                .thenComparing(DecisionEngine.Rule::code)));
        Map<String, DecisionEngine.Policy> policies = new HashMap<>();
        for (JsonNode p : db.list("select row_to_json(p)::text from fraud_decision_policy p where is_active")) {
            Map<String, DecisionEngine.Thresholds> channels = new HashMap<>();
            p.path("channel_thresholds").properties().forEach(e -> channels.put(e.getKey(), new DecisionEngine.Thresholds(
                    e.getValue().path("step_up").asInt(), e.getValue().path("review").asInt(), e.getValue().path("block").asInt())));
            policies.put(p.get("subject_type").asString(), new DecisionEngine.Policy(
                    p.get("step_up_threshold").asInt(), p.get("review_threshold").asInt(), p.get("block_threshold").asInt(),
                    Db.uuid(p, "model_id"), Db.uuid(p, "shadow_model_id"), p.get("timeout_ms").asInt(),
                    DecisionEngine.Decision.valueOf(p.get("fallback_decision").asString()),
                    ScoreCombination.valueOf(p.path("combination").asString("ADDITIVE")),
                    p.path("model_weight").asDouble(0.5), p.path("model_confidence").asDouble(0.8),
                    p.path("model_timeout_ms").asInt(10), Map.copyOf(channels), p.path("use_model_thresholds").asBoolean(false)));
        }
        List<ListMeta> lists = db.list("select row_to_json(l)::text from fraud_list l where is_active").stream()
                .map(l -> new ListMeta(UUID.fromString(l.get("id").asString()), l.get("code").asString(),
                        l.get("list_type").asString(), l.get("entity_type").asString()))
                .toList();
        return new Snapshot(System.nanoTime(), Map.copyOf(rules), Map.copyOf(policies), lists);
    }
}
