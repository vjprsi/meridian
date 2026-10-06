package io.meridian.routing.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Smart routing: picks a rail for a payment. Rules are evaluated by ascending priority; the first rule whose
 * conditions match and that yields an eligible rail wins (candidates ranked by its strategy, then its fallback rail).
 * Without a matching rule every eligible rail competes on cost. ACTIVE rails always rank before DEGRADED ones;
 * SUSPENDED / RETIRED rails, unsupported currencies and amounts above the scheme maximum are ineligible.
 * Pure: no I/O, every decision is explained.
 */
public final class RoutingEngine {

    /** Payment attributes routing looks at. */
    public record Facts(String paymentType, String direction, BigDecimal amount, String currency,
                        String creditorCountry, String priority, LocalDate onDate) {}

    public record Rail(UUID id, String code, String railType, String status, Set<String> currencies, String country,
                       BigDecimal maxAmount, BigDecimal cost, Integer settlementMinutes, boolean instant,
                       BigDecimal successRate, Integer p95LatencyMs) {}

    /** conditions: keys payment_types, currencies, countries, min_amount, max_amount, priorities, directions. */
    public record Rule(UUID id, String name, int priority, Map<String, Object> conditions, String strategy,
                       List<UUID> candidates, UUID fallback, Map<String, BigDecimal> weights, boolean active,
                       LocalDate validFrom, LocalDate validTo) {}

    public record Candidate(UUID railId, String code, boolean eligible, List<String> reasons, double score,
                            boolean degraded) {}

    public record RuleEvaluation(UUID ruleId, String name, int priority, boolean matched, List<String> reasons,
                                 String strategy, List<Candidate> candidates, UUID chosen) {}

    public record Decision(Rail rail, Rule rule, String strategy, List<RuleEvaluation> evaluations, String reason) {
        public boolean routable() {
            return rail != null;
        }
    }

    private static final BigDecimal DEFAULT_SUCCESS = new BigDecimal("0.95");

    private RoutingEngine() {}

    public static Decision route(Facts f, List<Rule> rules, Map<UUID, Rail> rails) {
        List<RuleEvaluation> evals = new ArrayList<>();
        List<Rule> ordered = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority).thenComparing(r -> r.id().toString())).toList();
        for (Rule r : ordered) {
            List<String> why = matchReasons(r, f);
            if (!why.isEmpty()) {
                evals.add(new RuleEvaluation(r.id(), r.name(), r.priority(), false, why, r.strategy(), List.of(), null));
                continue;
            }
            List<Candidate> ranked = rank(f, r.candidates(), rails, r.strategy(), r.weights());
            Candidate best = ranked.stream().filter(Candidate::eligible).findFirst().orElse(null);
            List<String> notes = new ArrayList<>();
            if (best == null && r.fallback() != null) {
                Candidate fb = evaluate(f, r.fallback(), rails, 0, 0);
                notes.add("no eligible candidate; fallback rail " + (fb.code() == null ? r.fallback() : fb.code())
                        + (fb.eligible() ? " used" : " ineligible: " + String.join(", ", fb.reasons())));
                if (fb.eligible()) {
                    best = fb;
                }
            }
            evals.add(new RuleEvaluation(r.id(), r.name(), r.priority(), true, notes, r.strategy(), ranked,
                    best == null ? null : best.railId()));
            if (best != null) {
                return new Decision(rails.get(best.railId()), r, r.strategy(), evals, null);
            }
        }
        // default route: every eligible rail, cheapest first
        List<Candidate> ranked = rank(f, new ArrayList<>(rails.keySet()), rails, "CHEAPEST", Map.of());
        Candidate best = ranked.stream().filter(Candidate::eligible).findFirst().orElse(null);
        evals.add(new RuleEvaluation(null, "default (no matching rule)", Integer.MAX_VALUE, true, List.of(),
                "CHEAPEST", ranked, best == null ? null : best.railId()));
        if (best == null) {
            return new Decision(null, null, null, evals, "No active rail supports " + f.currency()
                    + (f.amount() == null ? "" : " for " + f.amount().toPlainString())
                    + (f.paymentType() == null ? "" : " (" + f.paymentType() + ")"));
        }
        return new Decision(rails.get(best.railId()), null, "CHEAPEST", evals, null);
    }

    /** Empty when the rule matches; otherwise why it does not. */
    static List<String> matchReasons(Rule r, Facts f) {
        List<String> why = new ArrayList<>();
        if (!r.active()) {
            why.add("rule inactive");
        }
        if (f.onDate() != null) {
            if (r.validFrom() != null && f.onDate().isBefore(r.validFrom())) {
                why.add("not yet effective (valid_from " + r.validFrom() + ")");
            }
            if (r.validTo() != null && f.onDate().isAfter(r.validTo())) {
                why.add("expired (valid_to " + r.validTo() + ")");
            }
        }
        Map<String, Object> c = r.conditions() == null ? Map.of() : r.conditions();
        check(why, c, "payment_types", "payment_type", f.paymentType());
        check(why, c, "currencies", "currency", f.currency());
        check(why, c, "countries", "country", f.creditorCountry());
        check(why, c, "priorities", "priority", f.priority());
        check(why, c, "directions", "direction", f.direction());
        BigDecimal min = decimal(c.get("min_amount"));
        BigDecimal max = decimal(c.get("max_amount"));
        if (f.amount() != null && min != null && f.amount().compareTo(min) < 0) {
            why.add("amount below min_amount " + min.toPlainString());
        }
        if (f.amount() != null && max != null && f.amount().compareTo(max) > 0) {
            why.add("amount above max_amount " + max.toPlainString());
        }
        return why;
    }

    private static void check(List<String> why, Map<String, Object> c, String plural, String singular, String value) {
        Object cond = c.containsKey(plural) ? c.get(plural) : c.get(singular);
        if (cond == null) {
            return;
        }
        List<String> allowed = new ArrayList<>();
        if (cond instanceof List<?> l) {
            l.forEach(o -> allowed.add(String.valueOf(o).toUpperCase(Locale.ROOT)));
        } else {
            allowed.add(String.valueOf(cond).toUpperCase(Locale.ROOT));
        }
        if (allowed.isEmpty() || allowed.contains("*")) {
            return;
        }
        if (value == null || !allowed.contains(value.toUpperCase(Locale.ROOT))) {
            why.add(singular + " " + (value == null ? "(none)" : value) + " not in " + allowed);
        }
    }

    /** Candidates in rank order: eligible before ineligible, ACTIVE before DEGRADED, then strategy score. */
    static List<Candidate> rank(Facts f, List<UUID> ids, Map<UUID, Rail> rails, String strategy,
                                Map<String, BigDecimal> weights) {
        List<Rail> known = ids.stream().map(rails::get).filter(java.util.Objects::nonNull).toList();
        double minCost = known.stream().mapToDouble(r -> cost(r)).min().orElse(0);
        double maxCost = known.stream().mapToDouble(r -> cost(r)).max().orElse(0);
        double minLat = known.stream().mapToDouble(RoutingEngine::latency).min().orElse(0);
        double maxLat = known.stream().mapToDouble(RoutingEngine::latency).max().orElse(0);
        List<Candidate> out = new ArrayList<>();
        int index = 0;
        for (UUID id : ids) {
            Rail r = rails.get(id);
            double score = 0;
            if (r != null) {
                String s = strategy == null ? "FIXED" : strategy;
                score = switch (s) {
                    case "CHEAPEST" -> -cost(r);
                    case "FASTEST" -> -latency(r);
                    case "MOST_RELIABLE" -> success(r);
                    case "BALANCED" -> {
                        double wc = weight(weights, "cost", 0.4);
                        double ws = weight(weights, "speed", 0.3);
                        double wr = weight(weights, "success", 0.3);
                        double sum = wc + ws + wr;
                        double c = maxCost == minCost ? 1 : (maxCost - cost(r)) / (maxCost - minCost);
                        double sp = maxLat == minLat ? 1 : (maxLat - latency(r)) / (maxLat - minLat);
                        yield sum == 0 ? 0 : (wc * c + ws * sp + wr * success(r)) / sum;
                    }
                    default -> -index;
                };
            }
            out.add(evaluate(f, id, rails, score, index));
            index++;
        }
        List<Candidate> sorted = new ArrayList<>(out);
        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            order.putIfAbsent(ids.get(i), i);
        }
        sorted.sort(Comparator.comparing((Candidate c) -> !c.eligible())
                .thenComparing(Candidate::degraded)
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())
                .thenComparing(c -> order.getOrDefault(c.railId(), Integer.MAX_VALUE)));
        return sorted;
    }

    static Candidate evaluate(Facts f, UUID id, Map<UUID, Rail> rails, double score, int index) {
        Rail r = rails.get(id);
        if (r == null) {
            return new Candidate(id, null, false, List.of("rail not found"), score, false);
        }
        List<String> reasons = new ArrayList<>();
        if (!"ACTIVE".equals(r.status()) && !"DEGRADED".equals(r.status())) {
            reasons.add("rail " + r.status());
        }
        if (f.currency() != null && !r.currencies().contains(f.currency())) {
            reasons.add("currency " + f.currency() + " not supported");
        }
        if (f.amount() != null && r.maxAmount() != null && f.amount().compareTo(r.maxAmount()) > 0) {
            reasons.add("amount above scheme maximum " + r.maxAmount().toPlainString());
        }
        if (f.creditorCountry() != null && r.country() != null && !"CROSS_BORDER".equals(r.railType())
                && !r.country().equals(f.creditorCountry())) {
            reasons.add("creditor country " + f.creditorCountry() + " not served (rail country " + r.country() + ")");
        }
        boolean degraded = "DEGRADED".equals(r.status());
        if (degraded && reasons.isEmpty()) {
            reasons.add("degraded: ranked after active rails");
        }
        boolean eligible = reasons.isEmpty() || degraded && reasons.size() == 1;
        return new Candidate(id, r.code(), eligible, List.copyOf(reasons), round(score), degraded);
    }

    private static double cost(Rail r) {
        return r.cost() == null ? 0 : r.cost().doubleValue();
    }

    /** Expected latency in ms: measured p95, else typical settlement time, else instant 5s / batch 1 day. */
    static double latency(Rail r) {
        if (r.p95LatencyMs() != null) {
            return r.p95LatencyMs();
        }
        if (r.settlementMinutes() != null) {
            return r.settlementMinutes() * 60_000.0;
        }
        return r.instant() ? 5_000 : 86_400_000;
    }

    private static double success(Rail r) {
        return (r.successRate() == null ? DEFAULT_SUCCESS : r.successRate()).doubleValue();
    }

    private static double weight(Map<String, BigDecimal> w, String k, double dflt) {
        return w == null || w.get(k) == null ? dflt : w.get(k).doubleValue();
    }

    private static double round(double d) {
        return new BigDecimal(d).round(new MathContext(8)).doubleValue();
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
