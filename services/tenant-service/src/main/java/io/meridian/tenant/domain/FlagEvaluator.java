package io.meridian.tenant.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic feature-flag evaluation.
 *
 * <p>Order: master switch off -> {@code DISABLED}; first matching targeting rule -> {@code RULE_MATCH} (serves the
 * rule's {@code serve} value, default true, and optional {@code variant}); otherwise percentage rollout using a
 * stable SHA-256 bucket of {@code flag_key:subject_type:subject_id} -> {@code ROLLOUT} / {@code DEFAULT}.
 *
 * <p>Targeting rules JSON: an ordered array; each rule is
 * {@code {"attribute":"legal_entity_id","operator":"in","values":["..."],"serve":true,"variant":"beta"}}.
 * Attributes resolve from {@code subject_type}, {@code subject_id} or the request context (dotted paths allowed).
 * Operators: eq, neq, in, not_in, gt, gte, lt, lte, starts_with, ends_with, contains, exists, semver_gte, semver_lt.
 *
 * <p>Variants JSON: {@code {"control":50,"treatment":50}} (weights) or {@code ["a","b"]} (equal weights).
 */
public final class FlagEvaluator {
    private FlagEvaluator() {}

    public record Flag(String key, boolean enabled, int rolloutPercentage, JsonNode targetingRules, JsonNode variants,
                       Instant expiresAt) {}

    public record Result(boolean enabled, String variant, String reason, boolean expired) {}

    public static Result evaluate(Flag flag, String subjectType, String subjectId, JsonNode context, Instant now) {
        boolean expired = flag.expiresAt() != null && !flag.expiresAt().isAfter(now);
        if (!flag.enabled()) {
            return new Result(false, null, "DISABLED", expired);
        }
        List<JsonNode> rules = rules(flag.targetingRules());
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            if (matches(rule, subjectType, subjectId, context)) {
                boolean serve = !rule.has("serve") || rule.get("serve").asBoolean();
                String variant = serve
                        ? (rule.hasNonNull("variant") ? rule.get("variant").asString() : pickVariant(flag, subjectType, subjectId))
                        : null;
                return new Result(serve, variant, "RULE_MATCH:" + i, expired);
            }
        }
        int pct = Math.max(0, Math.min(100, flag.rolloutPercentage()));
        if (bucket(flag.key() + ":" + subjectType + ":" + subjectId) < pct) {
            return new Result(true, pickVariant(flag, subjectType, subjectId), "ROLLOUT", expired);
        }
        return new Result(false, null, "DEFAULT", expired);
    }

    /** Validates the structure of targeting rules; returns an error message if malformed. */
    public static Optional<String> validateRules(JsonNode rules) {
        if (rules == null || rules.isNull() || rules.isMissingNode()) {
            return Optional.empty();
        }
        if (!rules.isArray()) {
            return Optional.of("targeting_rules must be an array of rules");
        }
        for (JsonNode r : rules(rules)) {
            if (!r.isObject() || !r.hasNonNull("attribute") || !r.hasNonNull("operator")) {
                return Optional.of("each targeting rule needs attribute and operator");
            }
            if (!OPERATORS.contains(r.get("operator").asString())) {
                return Optional.of("unsupported operator " + r.get("operator").asString());
            }
        }
        return Optional.empty();
    }

    private static final List<String> OPERATORS = List.of("eq", "neq", "in", "not_in", "gt", "gte", "lt", "lte",
            "starts_with", "ends_with", "contains", "exists", "semver_gte", "semver_lt");

    private static List<JsonNode> rules(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    static boolean matches(JsonNode rule, String subjectType, String subjectId, JsonNode context) {
        String attribute = rule.path("attribute").asString();
        String op = rule.path("operator").asString();
        JsonNode actual = attribute(attribute, subjectType, subjectId, context);
        if ("exists".equals(op)) {
            return actual != null;
        }
        if (actual == null) {
            return "neq".equals(op) || "not_in".equals(op);
        }
        String a = actual.isString() ? actual.asString() : actual.toString();
        List<String> values = new ArrayList<>();
        if (rule.has("values") && rule.get("values").isArray()) {
            rule.get("values").forEach(v -> values.add(v.isString() ? v.asString() : v.toString()));
        } else if (rule.has("value")) {
            JsonNode v = rule.get("value");
            values.add(v.isString() ? v.asString() : v.toString());
        }
        String first = values.isEmpty() ? null : values.getFirst();
        return switch (op) {
            case "eq" -> a.equals(first);
            case "neq" -> !a.equals(first);
            case "in" -> values.contains(a);
            case "not_in" -> !values.contains(a);
            case "gt" -> first != null && compare(a, first) > 0;
            case "gte" -> first != null && compare(a, first) >= 0;
            case "lt" -> first != null && compare(a, first) < 0;
            case "lte" -> first != null && compare(a, first) <= 0;
            case "starts_with" -> first != null && a.startsWith(first);
            case "ends_with" -> first != null && a.endsWith(first);
            case "contains" -> first != null && a.contains(first);
            case "semver_gte" -> first != null && semver(a, first) >= 0;
            case "semver_lt" -> first != null && semver(a, first) < 0;
            default -> false;
        };
    }

    private static JsonNode attribute(String name, String subjectType, String subjectId, JsonNode context) {
        if ("subject_type".equals(name)) {
            return tools.jackson.databind.node.StringNode.valueOf(subjectType);
        }
        if ("subject_id".equals(name)) {
            return tools.jackson.databind.node.StringNode.valueOf(subjectId);
        }
        if (context == null || context.isNull()) {
            return null;
        }
        JsonNode cur = context;
        for (String part : name.split("\\.")) {
            cur = cur.get(part);
            if (cur == null || cur.isNull()) {
                return null;
            }
        }
        return cur;
    }

    static int compare(String a, String b) {
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    static int semver(String a, String b) {
        String[] pa = a.split("[.+-]");
        String[] pb = b.split("[.+-]");
        for (int i = 0; i < 3; i++) {
            int x = i < pa.length ? parseInt(pa[i]) : 0;
            int y = i < pb.length ? parseInt(pb[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String pickVariant(Flag flag, String subjectType, String subjectId) {
        JsonNode v = flag.variants();
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (v.isArray()) {
            v.forEach(n -> weights.put(n.isString() ? n.asString() : n.toString(), 1));
        } else if (v.isObject()) {
            v.properties().forEach(e -> weights.put(e.getKey(), Math.max(0, e.getValue().asInt(0))));
        }
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            return null;
        }
        int b = (int) (bucketLong(flag.key() + ":variant:" + subjectType + ":" + subjectId) % total);
        int acc = 0;
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            acc += e.getValue();
            if (b < acc) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Stable bucket 0..99. */
    public static int bucket(String seed) {
        return (int) (bucketLong(seed) % 100);
    }

    private static long bucketLong(String seed) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return ((d[0] & 0xFFL) << 24) | ((d[1] & 0xFFL) << 16) | ((d[2] & 0xFFL) << 8) | (d[3] & 0xFFL);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
