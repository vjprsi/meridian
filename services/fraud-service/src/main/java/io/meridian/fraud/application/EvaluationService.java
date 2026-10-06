package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.FeatureEngineering;
import io.meridian.fraud.domain.ListValues;
import io.meridian.fraud.domain.model.FraudModel;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.fraud.infrastructure.EvaluationStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real-time fraud evaluation: feature extraction (request + device + lists + velocity + session signals), rules,
 * champion and shadow models, decision policy, persistence of score / rule hits / alert and events. Two database
 * round trips inside one transaction; configuration comes from {@link FraudConfigCache}.
 */
@Service
public class EvaluationService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final EvaluationStore store;
    private final FraudConfigCache config;
    private final ModelRegistry models;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;

    public EvaluationService(EvaluationStore store, FraudConfigCache config, ModelRegistry models, TransactionTemplate tx,
                             Db db, JsonMapper json) {
        this.store = store;
        this.config = config;
        this.models = models;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    public Map<String, Object> evaluate(JsonNode req, String idempotencyKey) {
        long started = System.nanoTime();
        TenantContext.require();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header (1-128 chars) is required");
        }
        String subjectType = req.get("subject_type").asString();
        UUID subjectId = UUID.fromString(req.get("subject_id").asString());
        BigDecimal amount = Db.dec(req, "amount");
        String currency = Db.str(req, "currency");
        if (amount != null && amount.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must not be negative");
        }
        if (amount != null && currency == null) {
            throw ApiException.unprocessable("currency_required", "currency is required with amount");
        }
        FraudConfigCache.Snapshot cfg = config.current();
        DecisionEngine.Policy policy = cfg.policy(subjectType);
        UUID customerId = Db.uuid(req, "customer_id");
        JsonNode context = req.hasNonNull("context") ? req.get("context") : json.createObjectNode();

        // values of this event that may be on a list, hashed per list entity type
        Map<String, List<String>> candidates = listCandidates(req, context);
        Set<String> hashes = new LinkedHashSet<>();
        List<UUID> listIds = new ArrayList<>();
        for (FraudConfigCache.ListMeta l : cfg.lists()) {
            List<String> values = candidates.getOrDefault(l.entityType(), List.of());
            if (!values.isEmpty()) {
                listIds.add(l.id());
                values.forEach(v -> hashes.add(ListValues.hash(l.entityType(), v)));
            }
        }
        EvaluationStore.DeviceInput device = deviceInput(req);
        try {
            return tx.execute(s -> {
                JsonNode loaded = store.loadFacts(idempotencyKey, device, customerId, currency, Db.str(req, "session_id"),
                        hashes, listIds, TenantContext.principal());
                if (loaded.hasNonNull("replay")) {
                    return replay(loaded.get("replay"), subjectId);
                }
                return score(req, idempotencyKey, cfg, policy, loaded, context, started);
            });
        } catch (TransientDataAccessException | DataAccessResourceFailureException e) {
            // the store is unavailable: answer with the policy's fallback decision rather than failing the payment
            log.error("fraud evaluation failed, applying fallback decision {}", policy.fallback(), e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fraud_score_id", null);
            out.put("decision", policy.fallback().name());
            out.put("score", null);
            out.put("reason_codes", List.of("SCORING_UNAVAILABLE"));
            out.put("fallback", true);
            out.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
            return out;
        }
    }

    private Map<String, Object> score(JsonNode req, String key, FraudConfigCache.Snapshot cfg, DecisionEngine.Policy policy,
                                      JsonNode loaded, JsonNode context, long started) {
        String subjectType = req.get("subject_type").asString();
        Set<String> listHits = new LinkedHashSet<>();
        Set<String> blockHits = new LinkedHashSet<>();
        List<String> watchHits = new ArrayList<>();
        List<String> allowHits = new ArrayList<>();
        Set<String> hitIds = new LinkedHashSet<>();
        loaded.get("list_ids").forEach(n -> hitIds.add(n.asString()));
        for (FraudConfigCache.ListMeta l : cfg.lists()) {
            if (hitIds.contains(l.id().toString())) {
                listHits.add(l.code());
                switch (l.listType()) {
                    case "BLOCK" -> blockHits.add(l.code());
                    case "WATCH" -> watchHits.add(l.code());
                    default -> allowHits.add(l.code());
                }
            }
        }
        Map<String, Object> facts = facts(req, loaded, context);
        facts.put("lists", Map.of("hits", List.copyOf(listHits), "block_hits", List.copyOf(blockHits),
                "watch_hits", watchHits, "allow_hits", allowHits));
        Map<String, Double> engineered = FeatureEngineering.engineer(facts);
        FraudModel champion = models.champion(policy.modelId());
        FraudModel shadow = models.shadow(policy.shadowModelId());
        FraudModel.Output out = champion.score(engineered);
        FraudModel.Output shadowOut = shadow.score(engineered);
        facts.put("model_score", out.score());
        facts.put("engineered", engineered);
        DecisionEngine.Outcome o = DecisionEngine.decide(cfg.rules(subjectType), facts, listHits, blockHits, out,
                shadowOut, policy);
        if (!o.ruleErrors().isEmpty()) {
            log.warn("fraud rules failed at runtime: {}", o.ruleErrors());
        }

        UUID scoreId = Ids.newId();
        UUID alertId = null;
        ObjectNode alert = null;
        if (o.decision() == DecisionEngine.Decision.REVIEW || o.decision() == DecisionEngine.Decision.BLOCK) {
            alertId = Ids.newId();
            alert = alert(alertId, scoreId, req, o);
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        ObjectNode score = json.createObjectNode();
        score.put("id", scoreId.toString());
        score.put("subject_type", subjectType);
        score.put("subject_id", req.get("subject_id").asString());
        copy(req, score, "customer_id", "account_id", "card_id", "amount", "currency", "channel", "merchant_category_code",
                "counterparty_country", "ip_address", "correlation_id", "session_id");
        JsonNode dev = loaded.get("device");
        if (dev != null && !dev.isNull()) {
            score.put("device_fingerprint_id", dev.get("id").asString());
        }
        score.put("score", o.score());
        score.put("model_score", o.modelScore());
        score.put("rule_score", o.ruleScore());
        score.put("model_id", champion.id().toString());
        score.put("model_version", champion.name() + "@" + champion.version());
        score.put("decision", o.decision().name());
        score.set("reason_codes", json.valueToTree(o.reasonCodes()));
        score.set("features", json.valueToTree(facts));
        if (o.shadowDecision() != null) {
            score.put("shadow_decision", o.shadowDecision().name());
        }
        score.put("latency_ms", latency);
        if (alertId != null) {
            score.put("fraud_alert_id", alertId.toString());
        }
        score.put("idempotency_key", key);
        ArrayNode hits = json.createArrayNode();
        List<Map<String, Object>> hitView = new ArrayList<>();
        for (DecisionEngine.Hit h : o.hits()) {
            ObjectNode hn = hits.addObject();
            hn.put("fraud_score_id", scoreId.toString());
            hn.put("fraud_rule_id", h.rule().id().toString());
            hn.put("rule_version", h.rule().version());
            hn.put("action", h.rule().action());
            hn.put("score_adjustment", h.shadow() ? 0 : h.rule().scoreAdjustment());
            hn.put("shadow", h.shadow());
            hn.set("matched_values", json.valueToTree(h.matchedValues()));
            hitView.add(hitMap(h.rule().id(), h.rule().code(), h.rule().action(),
                    h.shadow() ? 0 : h.rule().scoreAdjustment(), h.shadow()));
        }
        JsonNode stored = store.persist(score, hits, alert, TenantContext.principal());
        if (stored == null) {
            // a concurrent request with the same key committed first
            JsonNode prior = db.one("select row_to_json(s)::text from fraud_score s where idempotency_key = ?", key)
                    .orElseThrow(() -> ApiException.conflict("request_in_progress", "A request with this Idempotency-Key is in progress"));
            return replay(prior, UUID.fromString(req.get("subject_id").asString()));
        }
        return response(stored, hitView, false);
    }

    private Map<String, Object> replay(JsonNode row, UUID subjectId) {
        if (!subjectId.toString().equals(row.get("subject_id").asString())) {
            throw ApiException.unprocessable("idempotency_key_reused",
                    "Idempotency-Key was already used to evaluate a different subject");
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (JsonNode h : db.list("""
                select json_build_object('rule_id', h.fraud_rule_id, 'code', r.code, 'action', h.action,
                       'score_adjustment', h.score_adjustment, 'shadow', h.shadow)::text
                from fraud_rule_hit h join fraud_rule r on r.id = h.fraud_rule_id
                where h.fraud_score_id = ? order by h.id""", UUID.fromString(row.get("id").asString()))) {
            hits.add(hitMap(UUID.fromString(h.get("rule_id").asString()), h.get("code").asString(),
                    h.get("action").asString(), h.get("score_adjustment").asInt(), h.get("shadow").asBoolean()));
        }
        return response(row, hits, true);
    }

    private Map<String, Object> response(JsonNode row, List<Map<String, Object>> hits, boolean replayed) {
        Map<String, Object> out = new LinkedHashMap<>();
        String decision = row.get("decision").asString();
        out.put("fraud_score_id", row.get("id").asString());
        out.put("subject_type", row.get("subject_type").asString());
        out.put("subject_id", row.get("subject_id").asString());
        out.put("decision", decision);
        out.put("step_up_required", "STEP_UP".equals(decision));
        out.put("score", row.get("score").asInt());
        out.put("model_score", Db.integer(row, "model_score"));
        out.put("rule_score", Db.integer(row, "rule_score"));
        out.put("model_id", Db.str(row, "model_id"));
        out.put("model_version", Db.str(row, "model_version"));
        out.put("shadow_decision", Db.str(row, "shadow_decision"));
        List<String> reasons = new ArrayList<>();
        row.path("reason_codes").forEach(n -> reasons.add(n.asString()));
        out.put("reason_codes", reasons);
        out.put("rule_hits", hits);
        out.put("fraud_alert_id", Db.str(row, "fraud_alert_id"));
        out.put("device_fingerprint_id", Db.str(row, "device_fingerprint_id"));
        out.put("latency_ms", row.get("latency_ms").asInt());
        out.put("replayed", replayed);
        out.put("created_at", Db.str(row, "created_at"));
        return out;
    }

    private static Map<String, Object> hitMap(UUID id, String code, String action, int adj, boolean shadow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_id", id.toString());
        m.put("code", code);
        m.put("action", action);
        m.put("score_adjustment", adj);
        m.put("shadow", shadow);
        return m;
    }

    private Map<String, Object> facts(JsonNode req, JsonNode loaded, JsonNode context) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (String k : List.of("subject_type", "subject_id", "customer_id", "account_id", "card_id", "currency",
                "channel", "merchant_category_code", "counterparty_country", "ip_address", "ip_country", "session_id")) {
            if (req.hasNonNull(k)) {
                f.put(k, req.get(k).asString());
            }
        }
        if (req.hasNonNull("amount")) {
            f.put("amount", new BigDecimal(req.get("amount").asString()));
        }
        f.put("hour_utc", loaded.path("hour_utc").asInt());
        JsonNode dev = loaded.get("device");
        Map<String, Object> device = new LinkedHashMap<>();
        if (dev != null && !dev.isNull()) {
            device.put("id", dev.get("id").asString());
            device.put("known", true);
            for (String k : List.of("is_new", "is_emulator", "is_rooted", "remote_access_tool", "trusted", "blocked")) {
                device.put(k, dev.path(k).asBoolean(false));
            }
            device.put("attestation_passed", dev.hasNonNull("attestation_passed") ? dev.get("attestation_passed").asBoolean() : null);
            device.put("customers_seen", dev.path("customers_seen").asInt(1));
            device.put("risk_score", dev.path("risk_score").asInt(0));
            device.put("platform", Db.str(dev, "platform"));
            device.put("last_country", Db.str(dev, "last_country"));
        } else {
            device.put("known", false);
        }
        f.put("device", device);
        f.put("velocity", toMap(loaded.get("velocity")));
        f.put("session", toMap(loaded.get("session")));
        f.put("context", toMap(context));
        return f;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode n) {
        if (n == null || n.isNull() || !n.isObject()) {
            return new LinkedHashMap<>();
        }
        return json.convertValue(n, LinkedHashMap.class);
    }

    private ObjectNode alert(UUID alertId, UUID scoreId, JsonNode req, DecisionEngine.Outcome o) {
        String severity = o.score() >= 900 || o.reasonCodes().stream().anyMatch(r -> r.startsWith("BLOCK_LIST:"))
                ? "CRITICAL" : o.score() >= 750 ? "HIGH" : o.score() >= 550 ? "MEDIUM" : "LOW";
        int sla = switch (severity) {
            case "CRITICAL" -> 60;
            case "HIGH" -> 240;
            case "MEDIUM" -> 1440;
            default -> 4320;
        };
        ObjectNode a = json.createObjectNode();
        a.put("id", alertId.toString());
        a.put("fraud_score_id", scoreId.toString());
        copy(req, a, "customer_id", "amount", "currency");
        a.put("subject_type", req.get("subject_type").asString());
        a.put("subject_id", req.get("subject_id").asString());
        a.put("severity", severity);
        String typology = typology(req, o.reasonCodes());
        if (typology != null) {
            a.put("typology", typology);
        }
        a.set("reason_codes", json.valueToTree(o.reasonCodes()));
        a.put("sla_minutes", sla);
        return a;
    }

    /** First guess of the typology from the strongest signals; analysts confirm it on resolution. */
    static String typology(JsonNode req, List<String> reasons) {
        if (reasons.contains("REMOTE_ACCESS_TOOL") || reasons.contains("ON_ACTIVE_CALL")) {
            return "AUTHORISED_PUSH_PAYMENT_SCAM";
        }
        if (reasons.contains("SHARED_DEVICE")) {
            return "MONEY_MULE";
        }
        if ("CARD_AUTHORIZATION".equals(req.path("subject_type").asString())
                && "ECOMMERCE".equals(req.path("channel").asString(""))) {
            return "CARD_NOT_PRESENT";
        }
        if (reasons.contains("NEW_DEVICE") || reasons.contains("EMULATOR")) {
            return "ACCOUNT_TAKEOVER";
        }
        return null;
    }

    private static EvaluationStore.DeviceInput deviceInput(JsonNode req) {
        String fp = Db.str(req, "device_fingerprint");
        if (fp == null || fp.isBlank()) {
            return null;
        }
        JsonNode d = req.path("device");
        return new EvaluationStore.DeviceInput(fp.trim(), text(d, "platform", 16), text(d, "os_version", 32),
                text(d, "app_version", 32), text(d, "device_model", 64), d.path("is_emulator").asBoolean(false),
                d.path("is_rooted").asBoolean(false),
                d.hasNonNull("attestation_passed") ? d.get("attestation_passed").asBoolean() : null,
                d.path("remote_access_tool").asBoolean(false), Db.str(req, "ip_address"), Db.str(req, "ip_country"));
    }

    private static String text(JsonNode n, String f, int max) {
        String v = n.hasNonNull(f) ? n.get(f).asString() : null;
        return v == null ? null : v.length() > max ? v.substring(0, max) : v;
    }

    /** Values of the event per list entity type (the list lookup hashes them with the list's entity type). */
    static Map<String, List<String>> listCandidates(JsonNode req, JsonNode ctx) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        add(m, "DEVICE", Db.str(req, "device_fingerprint"));
        add(m, "IP_ADDRESS", Db.str(req, "ip_address"));
        add(m, "ACCOUNT", Db.str(req, "account_id"));
        add(m, "CARD", Db.str(req, "card_id"));
        add(m, "COUNTRY", Db.str(req, "counterparty_country"));
        add(m, "COUNTRY", Db.str(req, "ip_country"));
        add(m, "CARD_BIN", Db.str(ctx, "card_bin"));
        add(m, "EMAIL", Db.str(ctx, "email"));
        add(m, "PHONE", Db.str(ctx, "phone"));
        add(m, "MERCHANT", Db.str(ctx, "merchant_id"));
        add(m, "BENEFICIARY_ACCOUNT", Db.str(ctx, "beneficiary_account"));
        add(m, "NATIONAL_ID", Db.str(ctx, "national_id"));
        return m;
    }

    private static void add(Map<String, List<String>> m, String type, String v) {
        if (v != null && !v.isBlank()) {
            m.computeIfAbsent(type, k -> new ArrayList<>()).add(v);
        }
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String f : fields) {
            if (from.hasNonNull(f)) {
                to.set(f, from.get(f));
            }
        }
    }
}
