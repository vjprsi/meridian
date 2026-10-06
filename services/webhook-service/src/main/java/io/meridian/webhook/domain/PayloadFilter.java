package io.meridian.webhook.domain;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * A deliberately small, safe subset of CEL for subscription filters: conjunctions of comparisons
 * {@code <path> == <literal>} / {@code <path> != <literal>} joined by {@code &&}. Paths are dot-separated fields of
 * the event payload (an optional leading {@code data.} is ignored); literals are quoted strings, numbers,
 * {@code true}, {@code false} or {@code null}. Example: {@code data.currency == "USD" && status != "FAILED"}.
 */
public final class PayloadFilter {
    private static final Pattern CLAUSE = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*(==|!=)\\s*"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|-?\\d+(?:\\.\\d+)?|true|false|null)\\s*");

    private record Clause(List<String> path, boolean equals, String literal, boolean literalIsString) {}

    private final List<Clause> clauses;

    private PayloadFilter(List<Clause> clauses) {
        this.clauses = clauses;
    }

    public static PayloadFilter parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return new PayloadFilter(List.of());
        }
        if (expression.length() > 2000) {
            throw ApiException.unprocessable("invalid_filter_expression", "Filter expression is too long");
        }
        List<Clause> out = new ArrayList<>();
        for (String part : expression.split("&&")) {
            Matcher m = CLAUSE.matcher(part);
            if (!m.matches()) {
                throw ApiException.unprocessable("invalid_filter_expression",
                        "Unsupported filter clause '" + part.trim() + "'; use <path> == <literal> joined by &&");
            }
            List<String> path = new ArrayList<>(List.of(m.group(1).split("\\.")));
            if (path.size() > 1 && "data".equals(path.getFirst())) {
                path.removeFirst();
            }
            String lit = m.group(3);
            boolean str = lit.startsWith("\"") || lit.startsWith("'");
            if (str) {
                lit = lit.substring(1, lit.length() - 1).replaceAll("\\\\(.)", "$1");
            }
            out.add(new Clause(List.copyOf(path), "==".equals(m.group(2)), lit, str));
        }
        return new PayloadFilter(out);
    }

    /**
     * @param lookup resolves a path to the payload value rendered as text (null when absent or JSON null)
     */
    public boolean test(Function<List<String>, String> lookup) {
        for (Clause c : clauses) {
            String actual = lookup.apply(c.path());
            String expected = !c.literalIsString() && "null".equals(c.literal()) ? null : c.literal();
            boolean eq = expected == null ? actual == null : expected.equals(actual) || numericEquals(expected, actual);
            if (eq != c.equals()) {
                return false;
            }
        }
        return true;
    }

    private static boolean numericEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
