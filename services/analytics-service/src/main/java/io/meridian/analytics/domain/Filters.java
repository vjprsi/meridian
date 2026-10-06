package io.meridian.analytics.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Structured filters {@code {field, op, value}} (dashboard global filters, KPI evaluation filters). Ops: = != > >= <
 * <= IN. Values are compared like the query language (numbers numerically, otherwise as text).
 */
public final class Filters {
    static final Set<String> OPS = Set.of("=", "!=", ">", ">=", "<", "<=", "IN");

    private Filters() {}

    public record Filter(String field, String op, Object value) {}

    /** Parses and checks filters; problems are reported with their index. */
    public static List<Filter> parse(List<Map<String, Object>> raw, List<String> errors) {
        List<Filter> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> f = raw.get(i);
            Object field = f.get("field");
            String op = String.valueOf(f.getOrDefault("op", "=")).toUpperCase(java.util.Locale.ROOT);
            if (!(field instanceof String s) || !s.matches("[a-z_][a-z0-9_]*")) {
                errors.add("filters[" + i + "].field must be a field name");
                continue;
            }
            if (!OPS.contains(op)) {
                errors.add("filters[" + i + "].op must be one of " + OPS);
                continue;
            }
            if ("IN".equals(op) && !(f.get("value") instanceof List<?>)) {
                errors.add("filters[" + i + "].value must be a list for IN");
                continue;
            }
            out.add(new Filter(s, op, f.get("value")));
        }
        return out;
    }

    /** Predicate over rows for the filters whose field is in {@code fields} (others do not apply). */
    public static Predicate<Map<String, Object>> predicate(List<Filter> filters, Set<String> fields) {
        List<Filter> applicable = filters.stream().filter(f -> fields.contains(f.field())).toList();
        return row -> {
            for (Filter f : applicable) {
                if (!matches(row.get(f.field()), f)) {
                    return false;
                }
            }
            return true;
        };
    }

    static boolean matches(Object v, Filter f) {
        if (v == null) {
            return f.value() == null && "=".equals(f.op());
        }
        if (f.value() == null) {
            return "!=".equals(f.op());
        }
        return switch (f.op()) {
            case "IN" -> ((List<?>) f.value()).stream().anyMatch(x -> x != null && Expr.equal(v, x));
            case "=" -> Expr.equal(v, f.value());
            case "!=" -> !Expr.equal(v, f.value());
            case ">" -> Expr.compare(v, f.value()) > 0;
            case ">=" -> Expr.compare(v, f.value()) >= 0;
            case "<" -> Expr.compare(v, f.value()) < 0;
            default -> Expr.compare(v, f.value()) <= 0;
        };
    }
}
