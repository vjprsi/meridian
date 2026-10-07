package io.meridian.reconciliation.application;

import io.meridian.common.text.SafeRegex;
import io.meridian.reconciliation.domain.matching.MatchRule;
import io.meridian.reconciliation.domain.matching.RuleSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stored form of a rule set ({@code recon_rule_set.rules}: an ordered JSON array) to and from the domain
 * {@link RuleSet}, with validation so that every stored version is executable and bounded: known types and keys,
 * tenant regexes accepted by SafeRegex (SEC-30), tolerances, windows, thresholds and subset-sum budgets in range.
 *
 * <pre>
 * {"code":"exact_e2e","type":"EXACT_KEY","keys":["end_to_end_id","instruction_id"],"fee_tolerance":"50",
 *  "fee_tolerance_pct":"1","fx_tolerance_pct":"2","date_window_days":5}
 * {"code":"remittance","type":"REFERENCE_EXTRACT","patterns":["(?i)INV[- ]?(\\d{4}-\\d{5})"]}
 * {"code":"fuzzy","type":"FUZZY","date_window_days":3,"name_threshold":0.8,"auto_threshold":0.92}
 * {"code":"batch","type":"ONE_TO_MANY","keys":["batch_ref","remittance"],"fee_tolerance_pct":"3",
 *  "max_group_size":500,"max_candidates":40,"max_search_nodes":500000}
 * </pre>
 */
final class RuleSetCodec {
    static final int MAX_RULES = 30;
    static final int MAX_PATTERNS = 20;
    private static final Set<String> EXACT_KEYS = Set.of("uetr", "end_to_end_id", "instruction_id", "bank_ref", "batch_ref");
    private static final BigDecimal MAX_PCT = BigDecimal.TEN;

    private RuleSetCodec() {}

    static RuleSet decode(JsonNode row) {
        UUID id = row.hasNonNull("id") ? UUID.fromString(row.get("id").asString()) : null;
        int version = row.path("version").asInt(0);
        double auto = row.hasNonNull("auto_match_threshold") ? row.get("auto_match_threshold").asDouble() : 0.9;
        double suggest = row.hasNonNull("suggest_threshold") ? row.get("suggest_threshold").asDouble() : 0.5;
        return new RuleSet(id, version, auto, suggest, rules(row.path("rules")));
    }

    static List<MatchRule> rules(JsonNode arr) {
        if (!arr.isArray() || arr.isEmpty()) {
            throw Fields.invalid("rules", "required", "rules must be a non-empty array of rule definitions");
        }
        if (arr.size() > MAX_RULES) {
            throw Fields.invalid("rules", "max", "A rule set has at most " + MAX_RULES + " rules");
        }
        List<MatchRule> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        int n = 0;
        for (JsonNode r : arr) {
            n++;
            String at = "rules[" + (n - 1) + "]";
            if (!r.isObject()) {
                throw Fields.invalid("rules", "type", at + " must be an object");
            }
            String code = Fields.text(r, "code");
            if (code == null || !code.matches("[a-z][a-z0-9_]{1,63}")) {
                throw Fields.invalid("rules", "code", at + ".code must match [a-z][a-z0-9_]{1,63}");
            }
            if (!codes.add(code)) {
                throw Fields.invalid("rules", "duplicate", "Rule code " + code + " is used twice");
            }
            MatchRule.Type type;
            try {
                type = MatchRule.Type.valueOf(Fields.text(r, "type") == null ? "" : r.get("type").asString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw Fields.invalid("rules", "invalid_enum", at + ".type must be one of " + List.of(MatchRule.Type.values()));
            }
            List<String> keys = strings(r.path("keys"), at + ".keys");
            for (String k : keys) {
                boolean ok = type == MatchRule.Type.EXACT_KEY || type == MatchRule.Type.REVERSAL_PAIR
                        ? EXACT_KEYS.contains(k) : MatchRule.KEYS.contains(k);
                if (!ok) {
                    throw Fields.invalid("rules", "invalid_key", at + ": key " + k + " is not allowed for " + type
                            + " (allowed: " + (type == MatchRule.Type.EXACT_KEY || type == MatchRule.Type.REVERSAL_PAIR
                            ? EXACT_KEYS : MatchRule.KEYS) + ")");
                }
            }
            if ((type == MatchRule.Type.EXACT_KEY || type == MatchRule.Type.REVERSAL_PAIR) && keys.isEmpty()) {
                throw Fields.invalid("rules", "required", at + ": " + type + " needs keys");
            }
            if (type == MatchRule.Type.MANY_TO_ONE && keys.isEmpty()) {
                throw Fields.invalid("rules", "required", at + ": MANY_TO_ONE groups lines by a shared key; set keys");
            }
            List<String> patterns = strings(r.path("patterns"), at + ".patterns");
            if (patterns.size() > MAX_PATTERNS) {
                throw Fields.invalid("rules", "max", at + " has more than " + MAX_PATTERNS + " patterns");
            }
            for (String p : patterns) {
                SafeRegex.problem(p).ifPresent(problem -> {
                    throw Fields.invalid("rules", "unsafe_pattern", at + ": pattern " + p + " rejected: " + problem);
                });
            }
            MatchRule.Builder b = MatchRule.builder(code, type).keys(keys).patterns(patterns)
                    .amountTolerance(amount(r, "amount_tolerance", at), pct(r, "amount_tolerance_pct", at))
                    .fee(amount(r, "fee_tolerance", at), pct(r, "fee_tolerance_pct", at))
                    .fxPct(pct(r, "fx_tolerance_pct", at))
                    .window(intIn(r, "date_window_days", 3, 0, MatchRule.MAX_DATE_WINDOW, at))
                    .nameThreshold(rate(r, "name_threshold", 0.8, at))
                    .autoThreshold(r.hasNonNull("auto_threshold") ? rate(r, "auto_threshold", 0.9, at) : null)
                    .minMargin(rate(r, "min_margin", 0.05, at))
                    .group(intIn(r, "max_group_size", 50, 2, MatchRule.MAX_GROUP, at),
                            intIn(r, "max_candidates", 25, 2, MatchRule.MAX_CANDIDATES, at),
                            longIn(r, "max_search_nodes", 200_000, 1, MatchRule.MAX_NODES, at))
                    .autoWithoutKey(r.path("auto_without_key").asBoolean(false))
                    .requireReversalFlag(r.path("require_reversal_flag").asBoolean(true))
                    .enabled(r.path("enabled").asBoolean(true));
            out.add(b.build());
        }
        return out;
    }

    /** Validates the stored row (rules plus thresholds). */
    static void validate(JsonNode row) {
        rules(row.path("rules"));
        double auto = row.hasNonNull("auto_match_threshold") ? row.get("auto_match_threshold").asDouble() : 0.9;
        double suggest = row.hasNonNull("suggest_threshold") ? row.get("suggest_threshold").asDouble() : 0.5;
        if (auto < 0.5 || auto > 1) {
            throw Fields.invalid("auto_match_threshold", "range", "auto_match_threshold must be 0.5..1");
        }
        if (suggest < 0 || suggest > auto) {
            throw Fields.invalid("suggest_threshold", "range", "suggest_threshold must be 0..auto_match_threshold");
        }
    }

    static ArrayNode encode(List<MatchRule> rules, JsonMapper json) {
        ArrayNode arr = json.createArrayNode();
        for (MatchRule r : rules) {
            ObjectNode o = arr.addObject();
            o.put("code", r.code());
            o.put("type", r.type().name());
            if (!r.keys().isEmpty()) {
                ArrayNode k = o.putArray("keys");
                r.keys().forEach(k::add);
            }
            if (!r.patterns().isEmpty()) {
                ArrayNode p = o.putArray("patterns");
                r.patterns().forEach(p::add);
            }
            putDecimal(o, "amount_tolerance", r.amountTolerance());
            putDecimal(o, "amount_tolerance_pct", r.amountTolerancePct());
            putDecimal(o, "fee_tolerance", r.feeTolerance());
            putDecimal(o, "fee_tolerance_pct", r.feeTolerancePct());
            putDecimal(o, "fx_tolerance_pct", r.fxTolerancePct());
            o.put("date_window_days", r.dateWindowDays());
            o.put("name_threshold", r.nameThreshold());
            if (r.autoThreshold() != null) {
                o.put("auto_threshold", r.autoThreshold());
            }
            o.put("min_margin", r.minMargin());
            o.put("max_group_size", r.maxGroupSize());
            o.put("max_candidates", r.maxCandidates());
            o.put("max_search_nodes", r.maxSearchNodes());
            o.put("auto_without_key", r.autoWithoutKey());
            o.put("require_reversal_flag", r.requireReversalFlag());
            o.put("enabled", r.enabled());
        }
        return arr;
    }

    private static void putDecimal(ObjectNode o, String k, BigDecimal v) {
        if (v.signum() != 0) {
            o.put(k, v.toPlainString());
        }
    }

    private static List<String> strings(JsonNode arr, String at) {
        List<String> out = new ArrayList<>();
        if (arr.isMissingNode() || arr.isNull()) {
            return out;
        }
        if (!arr.isArray()) {
            throw Fields.invalid("rules", "type", at + " must be an array of strings");
        }
        arr.forEach(v -> out.add(v.asString()));
        return out;
    }

    private static BigDecimal amount(JsonNode r, String f, String at) {
        if (!r.hasNonNull(f)) {
            return BigDecimal.ZERO;
        }
        BigDecimal v;
        try {
            v = new BigDecimal(r.get(f).asString());
        } catch (NumberFormatException e) {
            throw Fields.invalid("rules", "number", at + "." + f + " must be a decimal");
        }
        if (v.signum() < 0) {
            throw Fields.invalid("rules", "min", at + "." + f + " must not be negative");
        }
        return v;
    }

    private static BigDecimal pct(JsonNode r, String f, String at) {
        BigDecimal v = amount(r, f, at);
        if (v.compareTo(MAX_PCT) > 0) {
            throw Fields.invalid("rules", "max", at + "." + f + " must be at most " + MAX_PCT + " (percent)");
        }
        return v;
    }

    private static double rate(JsonNode r, String f, double dflt, String at) {
        if (!r.hasNonNull(f)) {
            return dflt;
        }
        double v = r.get(f).asDouble();
        if (v < 0 || v > 1) {
            throw Fields.invalid("rules", "range", at + "." + f + " must be 0..1");
        }
        return v;
    }

    private static int intIn(JsonNode r, String f, int dflt, int min, int max, String at) {
        return (int) longIn(r, f, dflt, min, max, at);
    }

    private static long longIn(JsonNode r, String f, long dflt, long min, long max, String at) {
        if (!r.hasNonNull(f)) {
            return dflt;
        }
        long v = r.get(f).asLong();
        if (v < min || v > max) {
            throw Fields.invalid("rules", "range", at + "." + f + " must be " + min + ".." + max);
        }
        return v;
    }
}
