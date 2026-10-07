package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.bool;
import static io.meridian.routing.infrastructure.Rows.date;
import static io.meridian.routing.infrastructure.Rows.dec;
import static io.meridian.routing.infrastructure.Rows.integer;
import static io.meridian.routing.infrastructure.Rows.str;
import static io.meridian.routing.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.routing.domain.RoutingEngine;
import io.meridian.routing.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Routing rules: validation, simulation (explained decision) and the route decision used by payment-service. */
@Service
public class RoutingService {
    static final String RULE = "payment_routing_rule";
    private static final Set<String> CONDITION_KEYS = Set.of("payment_types", "payment_type", "currencies", "currency",
            "countries", "country", "min_amount", "max_amount", "priorities", "priority", "directions", "direction");
    private static final Set<String> WEIGHT_KEYS = Set.of("cost", "speed", "success");

    private final Rows rows;
    private final RailService rails;
    private final Clock clock;

    public RoutingService(Rows rows, RailService rails, Clock clock) {
        this.rows = rows;
        this.rails = rails;
        this.clock = clock;
    }

    /** Validates a rule create / update body (fields present only). */
    @Transactional(readOnly = true)
    public void validate(JsonNode body) {
        JsonNode cond = body.get("conditions");
        if (cond != null && !cond.isNull()) {
            for (String k : cond.propertyNames()) {
                if (!CONDITION_KEYS.contains(k)) {
                    throw ApiException.unprocessable("invalid_condition", "unknown condition " + k + "; allowed: "
                            + CONDITION_KEYS);
                }
            }
            BigDecimal min = dec(cond, "min_amount");
            BigDecimal max = dec(cond, "max_amount");
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw ApiException.unprocessable("invalid_amount_band", "min_amount must not exceed max_amount");
            }
        }
        JsonNode cands = body.get("candidate_rail_ids");
        if (cands != null && !cands.isNull()) {
            if (!cands.isArray() || cands.isEmpty()) {
                throw ApiException.unprocessable("candidates_required", "candidate_rail_ids must list at least one rail");
            }
            for (JsonNode c : cands) {
                UUID id;
                try {
                    id = UUID.fromString(c.asString());
                } catch (IllegalArgumentException e) {
                    throw ApiException.unprocessable("invalid_candidate", "candidate_rail_ids must be rail ids");
                }
                if (rows.find(RailService.RAIL, id).isEmpty()) {
                    throw ApiException.unprocessable("unknown_rail", "candidate rail " + id + " does not exist");
                }
            }
        }
        JsonNode w = body.get("weights");
        if (w != null && !w.isNull()) {
            for (String k : w.propertyNames()) {
                BigDecimal v = dec(w, k);
                if (!WEIGHT_KEYS.contains(k) || v == null || v.signum() < 0) {
                    throw ApiException.unprocessable("invalid_weights", "weights are non-negative cost / speed / success");
                }
            }
        }
        if (date(body, "valid_from") != null && date(body, "valid_to") != null
                && date(body, "valid_from").isAfter(date(body, "valid_to"))) {
            throw ApiException.unprocessable("invalid_validity", "valid_from must not be after valid_to");
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode simulate(JsonNode payment) {
        RoutingEngine.Decision d = decide(payment);
        ObjectNode out = rows.object();
        out.put("routable", d.routable());
        if (d.routable()) {
            out.put("payment_rail_id", d.rail().id().toString());
            out.put("rail_code", d.rail().code());
            out.put("rule_id", d.rule() == null ? null : d.rule().id().toString());
            out.put("strategy", d.strategy());
        } else {
            out.put("reason_code", "NO_ROUTE");
            out.put("reason", d.reason());
        }
        ArrayNode evals = out.putArray("evaluations");
        for (RoutingEngine.RuleEvaluation e : d.evaluations()) {
            ObjectNode n = evals.addObject();
            n.put("rule_id", e.ruleId() == null ? null : e.ruleId().toString());
            n.put("name", e.name());
            n.put("priority", e.priority() == Integer.MAX_VALUE ? null : e.priority());
            n.put("matched", e.matched());
            n.put("strategy", e.strategy());
            ArrayNode reasons = n.putArray("reasons");
            e.reasons().forEach(reasons::add);
            ArrayNode cs = n.putArray("candidates");
            for (RoutingEngine.Candidate c : e.candidates()) {
                ObjectNode cn = cs.addObject();
                cn.put("payment_rail_id", c.railId().toString());
                cn.put("rail_code", c.code());
                cn.put("eligible", c.eligible());
                cn.put("score", c.score());
                cn.put("degraded", c.degraded());
                ArrayNode r = cn.putArray("reasons");
                c.reasons().forEach(r::add);
            }
            n.put("chosen_rail_id", e.chosen() == null ? null : e.chosen().toString());
        }
        if (d.routable()) {
            out.set("execution", rails.nextExecution(rows.get(RailService.RAIL, d.rail().id()),
                    str(payment, "payment_type"), str(payment, "currency"), null, date(payment, "requested_execution_date")));
        }
        return out;
    }

    /** The routing decision consumed by payment-service. Always 200: {routable:false, reason_code:NO_ROUTE} when none. */
    @Transactional(readOnly = true)
    public ObjectNode route(JsonNode payment) {
        RoutingEngine.Decision d = decide(payment);
        ObjectNode out = rows.object();
        if (!d.routable()) {
            out.put("routable", false);
            out.put("reason_code", "NO_ROUTE");
            out.put("reason", d.reason());
            return out;
        }
        ObjectNode rail = rows.get(RailService.RAIL, d.rail().id());
        ObjectNode exec = rails.nextExecution(rail, str(payment, "payment_type"), str(payment, "currency"), null,
                date(payment, "requested_execution_date"));
        out.put("routable", true);
        out.put("payment_rail_id", d.rail().id().toString());
        out.put("rail_code", d.rail().code());
        out.put("rail_type", d.rail().railType());
        out.put("is_instant", d.rail().instant());
        out.put("rail_status", d.rail().status());
        out.put("settlement_account_id", str(rail, "settlement_account_id"));
        out.put("participant_bic", str(rail, "participant_bic"));
        out.put("cost", (d.rail().cost() == null ? BigDecimal.ZERO : d.rail().cost()).toPlainString());
        out.put("currency", str(payment, "currency"));
        out.put("rule_id", d.rule() == null ? null : d.rule().id().toString());
        out.put("strategy", d.strategy());
        out.put("execution_date", exec.get("execution_date").asString());
        out.set("cutoff_at", exec.get("cutoff_at"));
        out.put("after_cutoff", exec.get("after_cutoff").asBoolean());
        out.put("settlement_minutes", integer(rail, "settlement_minutes"));
        return out;
    }

    private RoutingEngine.Decision decide(JsonNode payment) {
        if (payment == null || !payment.isObject()) {
            throw ApiException.badRequest("payment_required", "payment must be an object");
        }
        String currency = str(payment, "currency");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw ApiException.unprocessable("invalid_currency", "payment.currency must be an ISO 4217 code");
        }
        BigDecimal amount;
        try {
            amount = dec(payment, "amount");
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("invalid_amount", "payment.amount must be a decimal");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "payment.amount must be positive");
        }
        LocalDate today = rows.today(() -> LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        LocalDate onDate = date(payment, "requested_execution_date");
        RoutingEngine.Facts facts = new RoutingEngine.Facts(str(payment, "payment_type"), str(payment, "direction"),
                amount, currency, str(payment, "creditor_country"), str(payment, "priority"),
                onDate != null && onDate.isAfter(today) ? onDate : today);
        Map<UUID, RoutingEngine.Rail> railMap = new LinkedHashMap<>();
        for (ObjectNode r : rows.query("select row_to_json(t)::text from payment_rail t order by code")) {
            railMap.put(uuid(r, "id"), RailService.toEngine(r));
        }
        List<RoutingEngine.Rule> rules = new ArrayList<>();
        for (ObjectNode r : rows.query("select row_to_json(t)::text from payment_routing_rule t")) {
            rules.add(toRule(r));
        }
        return RoutingEngine.route(facts, rules, railMap);
    }

    @SuppressWarnings("unchecked")
    private RoutingEngine.Rule toRule(ObjectNode r) {
        List<UUID> cands = new ArrayList<>();
        JsonNode c = r.get("candidate_rail_ids");
        if (c != null && c.isArray()) {
            c.forEach(x -> {
                try {
                    cands.add(UUID.fromString(x.asString()));
                } catch (IllegalArgumentException ignored) {
                    // invalid ids are ignored (validated on write)
                }
            });
        }
        Map<String, Object> conditions = r.hasNonNull("conditions")
                ? rows.json().treeToValue(r.get("conditions"), Map.class) : Map.of();
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        JsonNode w = r.get("weights");
        if (w != null && w.isObject()) {
            for (String k : w.propertyNames()) {
                BigDecimal v = dec(w, k);
                if (v != null) {
                    weights.put(k, v);
                }
            }
        }
        Integer prio = integer(r, "priority");
        return new RoutingEngine.Rule(uuid(r, "id"), str(r, "name"), prio == null ? 100 : prio, conditions,
                str(r, "strategy"), cands, uuid(r, "fallback_rail_id"), weights, bool(r, "is_active"),
                date(r, "valid_from"), date(r, "valid_to"));
    }
}
