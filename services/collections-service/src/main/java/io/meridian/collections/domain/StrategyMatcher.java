package io.meridian.collections.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Chooses the collection strategy for a case: active strategies whose exposure type, DPD range and segment rules
 * match; the lowest priority wins (ties broken by code, so the choice is deterministic).
 */
public final class StrategyMatcher {
    private StrategyMatcher() {}

    static final Set<String> RULE_KEYS = Set.of("risk_grades", "min_balance", "max_balance", "product_ids", "vulnerable",
            "exposure_types");

    /** Parsed segment criteria; empty lists / nulls mean "any". */
    public record SegmentRules(List<String> riskGrades, BigDecimal minBalance, BigDecimal maxBalance,
                               List<UUID> productIds, Boolean vulnerable, List<String> exposureTypes) {
        public static final SegmentRules ANY = new SegmentRules(List.of(), null, null, List.of(), null, List.of());

        /** Validates and parses {@code segment_rules}; unknown keys or wrong shapes are 422 invalid_segment_rules. */
        public static SegmentRules parse(JsonNode n) {
            if (n == null || n.isNull()) {
                return ANY;
            }
            if (!n.isObject()) {
                throw invalid("segment_rules must be an object");
            }
            for (String k : n.propertyNames()) {
                if (!RULE_KEYS.contains(k)) {
                    throw invalid("unknown segment rule '" + k + "'; allowed: " + RULE_KEYS);
                }
            }
            List<String> grades = strings(n, "risk_grades");
            List<String> types = strings(n, "exposure_types");
            List<UUID> products = new ArrayList<>();
            for (String s : strings(n, "product_ids")) {
                try {
                    products.add(UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    throw invalid("product_ids must contain uuids");
                }
            }
            BigDecimal min = decimal(n, "min_balance");
            BigDecimal max = decimal(n, "max_balance");
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw invalid("min_balance must not exceed max_balance");
            }
            Boolean vulnerable = null;
            if (n.hasNonNull("vulnerable")) {
                if (!n.get("vulnerable").isBoolean()) {
                    throw invalid("vulnerable must be a boolean");
                }
                vulnerable = n.get("vulnerable").asBoolean();
            }
            return new SegmentRules(grades, min, max, products, vulnerable, types);
        }

        boolean matches(CaseFacts f) {
            if (!riskGrades.isEmpty() && (f.riskGrade() == null || !riskGrades.contains(f.riskGrade()))) {
                return false;
            }
            if (!exposureTypes.isEmpty() && !exposureTypes.contains(f.exposureType())) {
                return false;
            }
            if (minBalance != null && f.totalOutstanding().compareTo(minBalance) < 0) {
                return false;
            }
            if (maxBalance != null && f.totalOutstanding().compareTo(maxBalance) > 0) {
                return false;
            }
            if (!productIds.isEmpty() && (f.productId() == null || !productIds.contains(f.productId()))) {
                return false;
            }
            return vulnerable == null || vulnerable == f.vulnerable();
        }

        private static List<String> strings(JsonNode n, String f) {
            JsonNode v = n.get(f);
            if (v == null || v.isNull()) {
                return List.of();
            }
            if (!v.isArray()) {
                throw invalid(f + " must be an array");
            }
            List<String> out = new ArrayList<>();
            for (JsonNode x : v) {
                if (!x.isString()) {
                    throw invalid(f + " must contain strings");
                }
                out.add(x.asString());
            }
            return out;
        }

        private static BigDecimal decimal(JsonNode n, String f) {
            JsonNode v = n.get(f);
            if (v == null || v.isNull()) {
                return null;
            }
            try {
                return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString());
            } catch (NumberFormatException e) {
                throw invalid(f + " must be a decimal");
            }
        }

        private static ApiException invalid(String msg) {
            return ApiException.unprocessable("invalid_segment_rules", msg);
        }
    }

    public record Strategy(UUID id, String code, String exposureType, int minDpd, Integer maxDpd, int priority,
                           boolean active, SegmentRules rules) {}

    public record CaseFacts(String exposureType, int dpd, BigDecimal totalOutstanding, String riskGrade, UUID productId,
                            boolean vulnerable) {}

    public static Optional<Strategy> select(List<Strategy> strategies, CaseFacts facts) {
        return strategies.stream()
                .filter(Strategy::active)
                .filter(s -> s.exposureType() == null || s.exposureType().equals(facts.exposureType()))
                .filter(s -> facts.dpd() >= s.minDpd() && (s.maxDpd() == null || facts.dpd() <= s.maxDpd()))
                .filter(s -> s.rules().matches(facts))
                .min(Comparator.comparingInt(Strategy::priority).thenComparing(Strategy::code));
    }
}
