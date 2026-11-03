package io.meridian.limits.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.domain.LimitEngine;
import io.meridian.limits.domain.LimitModel;
import io.meridian.limits.domain.LimitModel.Applicable;
import io.meridian.limits.domain.LimitModel.CheckRequest;
import io.meridian.limits.domain.LimitModel.RuleResult;
import io.meridian.limits.domain.LimitModel.Usage;
import io.meridian.limits.infrastructure.Db;
import io.meridian.limits.infrastructure.LimitStore;
import io.meridian.web.time.BusinessDates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Limit checks: dry-run evaluation (one read), check-and-reserve (counters locked, reservation and check written in
 * one statement), commit / release of reservations and expiry of abandoned ones.
 */
@Service
public class LimitCheckService {
    private final LimitStore store;
    private final LimitRuleCache rules;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;

    private final BusinessDates businessDates;

    public LimitCheckService(LimitStore store, LimitRuleCache rules, TransactionTemplate tx, Db db, JsonMapper json,
                             BusinessDates businessDates) {
        this.businessDates = businessDates;
        this.store = store;
        this.rules = rules;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    /** Result of evaluating all applicable rules for one transaction. */
    record Evaluation(LimitModel.Selection selection, List<RuleResult> results, Map<UUID, UUID> usageIds, String action,
                      boolean allowed) {}

    public record CheckResult(String row, boolean replayed) {}

    // ------------------------------------------------------------------ evaluate / simulate (no reservation)

    public Map<String, Object> evaluate(JsonNode body) {
        long started = System.nanoTime();
        CheckRequest req = request(body);
        CheckRequest payee = payeeRequest(body, req);
        Evaluation e = tx.execute(s -> run(req, payee, false));
        Map<String, Object> out = summary(e, req);
        out.put("dry_run", true);
        out.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
        return out;
    }

    public Map<String, Object> simulate(JsonNode body) {
        CheckRequest req = request(body);
        CheckRequest payee = payeeRequest(body, req);
        Evaluation e = tx.execute(s -> run(req, payee, false));
        Map<String, Object> out = summary(e, req);
        List<Map<String, Object>> applied = new ArrayList<>();
        for (Applicable a : e.selection().effective()) {
            Map<String, Object> m = ruleView(a.rule());
            m.put("counted_against", Map.of("subject_type", a.countingSubjectType(), "subject_id", a.countingSubjectId().toString()));
            applied.add(m);
        }
        List<Map<String, Object>> superseded = new ArrayList<>();
        e.selection().superseded().forEach(s -> {
            Map<String, Object> m = ruleView(s.rule());
            m.put("superseded_by", s.by().code());
            superseded.add(m);
        });
        List<Map<String, Object>> mismatch = new ArrayList<>();
        e.selection().currencyMismatch().forEach(r -> {
            Map<String, Object> m = ruleView(r);
            m.put("reason", "currency_mismatch");
            mismatch.add(m);
        });
        out.put("applicable_rules", applied);
        out.put("superseded_rules", superseded);
        out.put("not_applicable_rules", mismatch);
        out.put("would_breach", !e.results().stream().filter(RuleResult::breached).toList().isEmpty());
        return out;
    }

    // ------------------------------------------------------------------ check and reserve

    public CheckResult check(JsonNode body, String idempotencyKey) {
        long started = System.nanoTime();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "Idempotency-Key header (1-128 chars) is required");
        }
        CheckRequest req = request(body);
        if (req.subjectId() == null) {
            throw ApiException.unprocessable("subject_id_required", "subject_id is required");
        }
        if (body.hasNonNull("expires_at")) {
            java.time.OffsetDateTime exp = java.time.OffsetDateTime.parse(body.get("expires_at").asString());
            java.time.OffsetDateTime now = io.meridian.common.time.Clocks.now();
            if (!exp.isAfter(now) || exp.isAfter(now.plusHours(24))) {
                throw ApiException.unprocessable("invalid_expiry", "expires_at must be within the next 24 hours");
            }
        }
        return tx.execute(s -> {
            var prior = db.one("select row_to_json(c)::text from limit_check c where idempotency_key = ?", idempotencyKey);
            if (prior.isPresent()) {
                return replay(prior.get(), req);
            }
            CheckRequest payee = payeeRequest(body, req);
            Evaluation e = run(req, payee, true);
            boolean reserve = !"REJECT".equals(e.action());
            ArrayNode deltas = json.createArrayNode();
            ArrayNode reservations = json.createArrayNode();
            if (reserve) {
                for (RuleResult r : e.results()) {
                    UUID usageId = e.usageIds().get(r.rule().id());
                    if (usageId != null) {
                        ObjectNode d = deltas.addObject();
                        d.put("limit_usage_id", usageId.toString());
                        d.put("amount", req.amount().toPlainString());
                        d.put("count", 1);
                        reservations.add(d.deepCopy());
                    }
                }
            }
            ObjectNode c = json.createObjectNode();
            c.put("id", Ids.newId().toString());
            c.put("subject_type", req.subjectType());
            c.put("subject_id", req.subjectId().toString());
            putIf(c, "customer_id", req.customerId());
            putIf(c, "kyc_tier", req.kycTier());
            putIf(c, "customer_segment", req.customerSegment());
            putIf(c, "product_id", req.productId());
            c.put("transaction_type", req.transactionType());
            putIf(c, "channel", req.channel());
            c.put("amount", req.amount().toPlainString());
            c.put("currency", req.currency());
            putIf(c, "current_balance", req.currentBalance() == null ? null : req.currentBalance().toPlainString());
            // WE-LIMIT-1 payer TCP context
            putIf(c, "direction", req.direction());
            putIf(c, "regulatory_profile_code", req.regulatoryProfileCode());
            putIf(c, "domain_code", req.domainCode());
            putIf(c, "category_code", req.categoryCode());
            putIf(c, "grade_code", req.gradeCode());
            putIf(c, "payment_instrument", req.instrument());
            // WE-LIMIT-1 credit block (echoed for audit; the credit-side counters are already in the reservations)
            if (payee != null) {
                c.put("payee_subject_type", payee.subjectType());
                putIf(c, "payee_subject_id", payee.subjectId());
                putIf(c, "payee_customer_id", payee.customerId());
                putIf(c, "payee_regulatory_profile_code", payee.regulatoryProfileCode());
                putIf(c, "payee_grade_code", payee.gradeCode());
                putIf(c, "payee_payment_instrument", payee.instrument());
                putIf(c, "payee_current_balance", payee.currentBalance() == null ? null : payee.currentBalance().toPlainString());
            }
            c.put("allowed", e.allowed());
            c.put("status", reserve ? "RESERVED" : "REJECTED");
            putIf(c, "action", e.action());
            c.set("evaluated_rule_ids", json.valueToTree(e.results().stream().map(r -> r.rule().id().toString()).toList()));
            c.set("breached_rule_ids", json.valueToTree(e.results().stream().filter(RuleResult::breached)
                    .map(r -> r.rule().id().toString()).toList()));
            c.set("remaining", json.valueToTree(remaining(e.results())));
            c.set("reservations", reservations);
            c.put("idempotency_key", idempotencyKey);
            putIf(c, "correlation_id", Db.str(body, "correlation_id"));
            putIf(c, "expires_at", Db.str(body, "expires_at"));
            c.put("latency_ms", (System.nanoTime() - started) / 1_000_000);
            boolean breached = e.results().stream().anyMatch(RuleResult::breached);
            JsonNode row = store.persist(c, deltas, breached, TenantContext.principal());
            if (row == null) {
                s.setRollbackOnly();
                return null;
            }
            return new CheckResult(db.text(row), false);
        }) instanceof CheckResult r ? r : replayAfterRace(idempotencyKey, req);
    }

    private CheckResult replayAfterRace(String key, CheckRequest req) {
        JsonNode prior = db.one("select row_to_json(c)::text from limit_check c where idempotency_key = ?", key)
                .orElseThrow(() -> ApiException.conflict("request_in_progress", "A request with this Idempotency-Key is in progress"));
        return replay(prior, req);
    }

    private CheckResult replay(JsonNode prior, CheckRequest req) {
        if (!prior.get("subject_id").asString().equals(req.subjectId().toString())
                || new BigDecimal(prior.get("amount").asString()).compareTo(req.amount()) != 0) {
            throw ApiException.unprocessable("idempotency_key_reused", "Idempotency-Key was already used for a different check");
        }
        return new CheckResult(db.text(prior), true);
    }

    /** Commits the reservation (all of it, or a smaller final amount; the rest is released). */
    public String commit(UUID id, BigDecimal amount) {
        return tx.execute(s -> {
            JsonNode c = db.require("limit_check", id, true);
            requireReserved(c, "commit");
            BigDecimal reserved = new BigDecimal(c.get("amount").asString());
            BigDecimal commitAmount = amount == null ? reserved : amount;
            if (commitAmount.signum() <= 0 || commitAmount.compareTo(reserved) > 0) {
                throw ApiException.unprocessable("invalid_commit_amount",
                        "Commit amount must be positive and at most the reserved " + reserved.toPlainString());
            }
            store.settle(deltas(c, commitAmount, true));
            db.update("update limit_check set status = 'COMMITTED', committed_amount = ?, settled_at = now() where id = ?",
                    commitAmount, id);
            return db.text(db.require("limit_check", id, false));
        });
    }

    public String release(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode c = db.require("limit_check", id, true);
            requireReserved(c, "release");
            store.settle(deltas(c, BigDecimal.ZERO, false));
            db.update("update limit_check set status = 'RELEASED', release_reason = ?, settled_at = now() where id = ?",
                    reason, id);
            return db.text(db.require("limit_check", id, false));
        });
    }

    /** Releases reservations whose expires_at has passed (called by the scheduler for each tenant). */
    public Map<String, Object> expire(int max) {
        int limit = Math.max(1, Math.min(max, 5000));
        return tx.execute(s -> {
            List<JsonNode> due = db.list("""
                    select row_to_json(c)::text from limit_check c
                    where status = 'RESERVED' and expires_at < now() order by expires_at limit ? for update skip locked""", limit);
            List<String> ids = new ArrayList<>();
            for (JsonNode c : due) {
                store.settle(deltas(c, BigDecimal.ZERO, false));
                db.update("""
                        update limit_check set status = 'EXPIRED', release_reason = 'reservation expired', settled_at = now()
                        where id = ?""", UUID.fromString(c.get("id").asString()));
                ids.add(c.get("id").asString());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("expired", ids.size());
            out.put("check_ids", ids);
            return out;
        });
    }

    private ArrayNode deltas(JsonNode check, BigDecimal commitAmount, boolean commit) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode r : check.path("reservations")) {
            ObjectNode d = out.addObject();
            d.put("limit_usage_id", r.get("limit_usage_id").asString());
            d.put("amount", r.get("amount").asString());
            d.put("count", r.get("count").asInt());
            d.put("commit_amount", commit ? commitAmount.toPlainString() : "0");
            d.put("commit_count", commit ? r.get("count").asInt() : 0);
        }
        return out;
    }

    private static void requireReserved(JsonNode c, String what) {
        String status = c.get("status").asString();
        if (!"RESERVED".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + what + " a limit check that is " + status);
        }
    }

    // ------------------------------------------------------------------ core

    Evaluation run(CheckRequest req, boolean lock) {
        return run(req, null, lock);
    }

    /** One applicable rule together with the request side (payer or payee) whose amount/balance it is evaluated with. */
    private record Sided(Applicable applicable, CheckRequest side) {}

    /**
     * WE-LIMIT-1: evaluates the payer (debit) side and, when a credit block is supplied, the payee (credit) side in
     * one pass. Both sides' counters are created and locked in the same single round-trip, so the credit-side
     * reservation adds no extra hot-path query.
     */
    Evaluation run(CheckRequest req, CheckRequest payee, boolean lock) {
        LimitModel.Selection sel = LimitEngine.select(rules.rules(), req);
        List<Sided> sided = new ArrayList<>();
        sel.effective().forEach(a -> sided.add(new Sided(a, req)));
        if (payee != null) {
            LimitEngine.select(rules.rules(), payee).effective().forEach(a -> sided.add(new Sided(a, payee)));
        }
        Instant now = Instant.now();
        // calendar periods follow the tenant's business date (UTC calendar date when business-day-service has none)
        java.time.LocalDate today = businessDates.today(() -> now.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        ArrayNode keys = json.createArrayNode();
        ArrayNode rolling = json.createArrayNode();
        Set<UUID> subjects = new LinkedHashSet<>();
        Set<UUID> ruleIds = new LinkedHashSet<>();
        for (Sided s : sided) {
            Applicable a = s.applicable();
            subjects.add(a.countingSubjectId());
            ruleIds.add(a.rule().id());
            if (!a.rule().counted()) {
                continue;
            }
            LimitModel.Window w = LimitEngine.window(a.rule().period(), now, today);
            ObjectNode k = keys.addObject();
            k.put("limit_rule_id", a.rule().id().toString());
            k.put("subject_type", a.countingSubjectType());
            k.put("subject_id", a.countingSubjectId().toString());
            k.put("period_start", w.start().toString());
            k.put("period_end", w.end().toString());
            k.put("currency", a.rule().currency() != null ? a.rule().currency() : s.side().currency());
            if (a.rule().direction() != null) {
                k.put("direction", a.rule().direction());
            }
            if (w.rolling()) {
                ObjectNode r = rolling.addObject();
                r.put("limit_rule_id", a.rule().id().toString());
                r.put("subject_id", a.countingSubjectId().toString());
                r.put("window_start", w.lookbackStart().toString());
                r.put("bucket_start", w.start().toString());
            }
        }
        Map<String, JsonNode> usage = new HashMap<>();
        Map<String, JsonNode> prior = new HashMap<>();
        Map<String, JsonNode> overrides = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            JsonNode loaded = store.load(keys, rolling, subjects, ruleIds, lock, TenantContext.principal());
            loaded.get("usage").forEach(u -> usage.put(key(u), u));
            loaded.get("prior").forEach(p -> prior.put(key(p), p));
            loaded.get("overrides").forEach(o -> {
                String k = key(o);
                JsonNode existing = overrides.get(k);
                if (existing == null || o.get("created_at").asString().compareTo(existing.get("created_at").asString()) > 0) {
                    overrides.put(k, o); // the latest approved override wins
                }
            });
        }
        List<RuleResult> results = new ArrayList<>();
        Map<UUID, UUID> usageIds = new HashMap<>();
        for (Sided s : sided) {
            Applicable a = s.applicable();
            String k = a.rule().id() + "|" + a.countingSubjectId();
            Usage u = Usage.ZERO;
            JsonNode row = usage.get(k);
            if (row != null) {
                u = usage(row);
                usageIds.put(a.rule().id(), UUID.fromString(row.get("id").asString()));
            }
            JsonNode p = prior.get(k);
            if (p != null) {
                u = u.plus(usage(p));
            }
            JsonNode ov = overrides.get(k);
            LimitModel.Limit limit = LimitEngine.limit(a.rule(), ov == null ? null : Db.dec(ov, "max_amount"),
                    ov == null ? null : Db.integer(ov, "max_count"), ov == null ? null : UUID.fromString(ov.get("id").asString()));
            results.add(LimitEngine.evaluate(a.rule(), limit, u, s.side().amount(), s.side().currentBalance()));
        }
        String action = LimitEngine.strictest(results);
        return new Evaluation(sel, results, usageIds, action, LimitEngine.allowed(action));
    }

    private static String key(JsonNode n) {
        return n.get("limit_rule_id").asString() + "|" + n.get("subject_id").asString();
    }

    private static Usage usage(JsonNode n) {
        return new Usage(dec(n, "used_amount"), dec(n, "reserved_amount"), n.path("used_count").asLong(0),
                n.path("reserved_count").asLong(0));
    }

    private static BigDecimal dec(JsonNode n, String f) {
        BigDecimal d = Db.dec(n, f);
        return d == null ? BigDecimal.ZERO : d;
    }

    private Map<String, Object> summary(Evaluation e, CheckRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", e.allowed());
        out.put("action", e.action());
        out.put("subject_type", req.subjectType());
        out.put("subject_id", req.subjectId().toString());
        out.put("amount", req.amount().toPlainString());
        out.put("currency", req.currency());
        out.put("evaluated_rule_ids", e.results().stream().map(r -> r.rule().id().toString()).toList());
        out.put("breached_rule_ids", e.results().stream().filter(RuleResult::breached).map(r -> r.rule().id().toString()).toList());
        out.put("remaining", remaining(e.results()));
        return out;
    }

    static List<Map<String, Object>> remaining(List<RuleResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("limit_rule_id", r.rule().id().toString());
            m.put("code", r.rule().code());
            m.put("scope", r.rule().scope());
            m.put("period", r.rule().period());
            m.put("measure", r.rule().measure());
            m.put("limit_amount", r.limitAmount() == null ? null : r.limitAmount().toPlainString());
            m.put("limit_count", r.limitCount());
            m.put("used_amount", r.usage().usedAmount().toPlainString());
            m.put("reserved_amount", r.usage().reservedAmount().toPlainString());
            m.put("used_count", r.usage().usedCount());
            m.put("reserved_count", r.usage().reservedCount());
            m.put("remaining_amount", r.remainingAmount() == null ? null : r.remainingAmount().toPlainString());
            m.put("remaining_count", r.remainingCount());
            m.put("breached", r.breached());
            m.put("breach_action", r.rule().breachAction());
            m.put("override_id", r.overrideId() == null ? null : r.overrideId().toString());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> ruleView(LimitModel.Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit_rule_id", r.id().toString());
        m.put("code", r.code());
        m.put("scope", r.scope());
        m.put("scope_ref", r.scopeRef());
        m.put("transaction_type", r.transactionType());
        m.put("channel", r.channel());
        m.put("period", r.period());
        m.put("measure", r.measure());
        m.put("currency", r.currency());
        m.put("max_amount", r.maxAmount() == null ? null : r.maxAmount().toPlainString());
        m.put("max_count", r.maxCount());
        m.put("priority", r.priority());
        m.put("is_regulatory", r.regulatory());
        m.put("specificity", r.specificity());
        return m;
    }

    static CheckRequest request(JsonNode b) {
        TenantContext.require();
        BigDecimal amount = Db.dec(b, "amount");
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        String subjectType = Db.str(b, "subject_type");
        if (subjectType == null || subjectType.isBlank()) {
            throw ApiException.unprocessable("subject_type_required", "subject_type is required");
        }
        String direction = Db.str(b, "direction");
        String regProfile = Db.str(b, "regulatory_profile_code");
        return new CheckRequest(subjectType.toUpperCase(java.util.Locale.ROOT), Db.uuid(b, "subject_id"),
                Db.uuid(b, "customer_id"), Db.str(b, "kyc_tier"), Db.str(b, "customer_segment"), Db.uuid(b, "product_id"),
                Db.str(b, "transaction_type"), Db.str(b, "channel"), amount, Db.str(b, "currency"),
                Db.dec(b, "current_balance"), direction == null ? "DEBIT" : direction.toUpperCase(java.util.Locale.ROOT),
                regProfile, regProfileSubject(regProfile), Db.str(b, "domain_code"), Db.str(b, "category_code"),
                Db.str(b, "grade_code"), Db.str(b, "payment_instrument"));
    }

    /**
     * WE-LIMIT-1 credit block: the payee side of a transfer, evaluated as a CREDIT check against the payee subject.
     * Returns null when no payee is supplied (classic single-sided behaviour).
     */
    static CheckRequest payeeRequest(JsonNode b, CheckRequest payer) {
        java.util.UUID payeeId = Db.uuid(b, "payee_subject_id");
        if (payeeId == null) {
            return null;
        }
        String type = Db.str(b, "payee_subject_type");
        String regProfile = Db.str(b, "payee_regulatory_profile_code");
        return new CheckRequest(type == null ? "WALLET" : type.toUpperCase(java.util.Locale.ROOT), payeeId,
                Db.uuid(b, "payee_customer_id"), payer.kycTier(), payer.customerSegment(), payer.productId(),
                payer.transactionType(), payer.channel(), payer.amount(), payer.currency(),
                Db.dec(b, "payee_current_balance"), "CREDIT", regProfile, regProfileSubject(regProfile),
                payer.domainCode(), payer.categoryCode(), Db.str(b, "payee_grade_code"),
                Db.str(b, "payee_payment_instrument"));
    }

    /** Deterministic counting subject of a regulatory-profile code (cross-account-group aggregation key). */
    private static java.util.UUID regProfileSubject(String code) {
        return code == null || code.isBlank() ? null : io.meridian.common.id.Ids.deterministic("regprofile:" + code);
    }

    private static void putIf(ObjectNode n, String f, Object v) {
        if (v != null) {
            n.put(f, v.toString());
        }
    }
}
