package io.meridian.pricingrules.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DMN-style unary tests (a FEEL subset) used in decision-table input entries:
 * <ul>
 *   <li>{@code -} or empty: matches anything (including null)</li>
 *   <li>literal ({@code 5}, {@code "GOLD"}, {@code GOLD}, {@code true}, {@code date("2026-01-01")}, {@code null}):
 *       typed equality</li>
 *   <li>comparisons {@code < 5}, {@code <= 5}, {@code > 5}, {@code >= 5}, {@code = 5}, {@code != 5}</li>
 *   <li>ranges {@code [a..b]} (inclusive), {@code (a..b)} / {@code ]a..b[} (exclusive), mixed ends</li>
 *   <li>lists {@code "A","B",C} (any element matches) and negation {@code not("A","B")}</li>
 * </ul>
 * Comparisons are typed: numbers by value (BigDecimal), dates chronologically, strings exactly (case-sensitive).
 */
public sealed interface UnaryTest {

    boolean matches(Object value);

    /** The original source text (for explanations and export). */
    String source();

    Pattern RANGE = Pattern.compile("^([\\[(\\]])\\s*(.+?)\\s*\\.\\.\\s*(.+?)\\s*([\\])\\[])$", Pattern.DOTALL);
    Pattern NUMBER = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$");
    Pattern DATE_FN = Pattern.compile("^date\\(\\s*\"([^\"]+)\"\\s*\\)$");

    static UnaryTest parse(String text, ValueType type) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty() || s.equals("-")) {
            return new Any(s.isEmpty() ? "-" : s);
        }
        if (s.startsWith("not(") && s.endsWith(")")) {
            List<UnaryTest> inner = parseList(s.substring(4, s.length() - 1), type);
            return new Not(s, inner);
        }
        List<UnaryTest> parts = parseList(s, type);
        return parts.size() == 1 ? parts.getFirst() : new AnyOf(s, parts);
    }

    private static List<UnaryTest> parseList(String s, ValueType type) {
        List<String> parts = splitTopLevel(s);
        List<UnaryTest> tests = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) {
                throw new DecisionTableException("invalid_expression", "Empty element in list '" + s + "'");
            }
            tests.add(parseSingle(p.trim(), type));
        }
        return tests;
    }

    private static UnaryTest parseSingle(String p, ValueType type) {
        Matcher m = RANGE.matcher(p);
        if (m.matches() && !p.startsWith("\"")) {
            Object low = literal(m.group(2), type);
            Object high = literal(m.group(3), type);
            if (!(low instanceof Comparable<?>) || !(high instanceof Comparable<?>) || low.getClass() != high.getClass()) {
                throw new DecisionTableException("invalid_expression", "Range '" + p + "' needs two values of one type");
            }
            if (compare(low, high) > 0) {
                throw new DecisionTableException("invalid_expression", "Range '" + p + "' has its lower end above its upper end");
            }
            return new Range(p, m.group(1).equals("["), low, high, m.group(4).equals("]"));
        }
        for (String op : new String[] {"<=", ">=", "!=", "<", ">", "="}) {
            if (p.startsWith(op)) {
                Object v = literal(p.substring(op.length()).trim(), type);
                if (!op.equals("=") && !op.equals("!=") && !(v instanceof Comparable<?>)) {
                    throw new DecisionTableException("invalid_expression", "Operator " + op + " needs an ordered value in '" + p + "'");
                }
                return new Compare(p, op, v);
            }
        }
        return new Compare(p, "=", literal(p, type));
    }

    /** Parses a literal according to the column type; quoted strings are always strings. */
    static Object literal(String raw, ValueType type) {
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new DecisionTableException("invalid_expression", "Missing value");
        }
        if (s.equals("null")) {
            return null;
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            String str = s.substring(1, s.length() - 1).replace("\\\"", "\"");
            if (type == ValueType.DATE) {
                return parseDate(str);
            }
            return str;
        }
        Matcher d = DATE_FN.matcher(s);
        if (d.matches()) {
            return parseDate(d.group(1));
        }
        switch (type) {
            case NUMBER -> {
                if (!NUMBER.matcher(s).matches()) {
                    throw new DecisionTableException("invalid_expression", "'" + s + "' is not a number");
                }
                return new BigDecimal(s);
            }
            case BOOLEAN -> {
                if (s.equals("true") || s.equals("false")) {
                    return Boolean.valueOf(s);
                }
                throw new DecisionTableException("invalid_expression", "'" + s + "' is not a boolean");
            }
            case DATE -> {
                return parseDate(s);
            }
            case STRING -> {
                return s;
            }
            default -> {
                if (NUMBER.matcher(s).matches()) {
                    return new BigDecimal(s);
                }
                if (s.equals("true") || s.equals("false")) {
                    return Boolean.valueOf(s);
                }
                return s;
            }
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s.trim());
        } catch (RuntimeException e) {
            throw new DecisionTableException("invalid_expression", "'" + s + "' is not an ISO date");
        }
    }

    /** Splits on commas that are outside quotes, brackets and parentheses. */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (c == '[' || c == '(')) {
                depth++;
            } else if (!quoted && (c == ']' || c == ')')) {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && !quoted && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        if (quoted) {
            throw new DecisionTableException("invalid_expression", "Unterminated string in '" + s + "'");
        }
        return out;
    }

    /** Typed comparison; returns null when the values are not comparable. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Integer compare(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return null;
        }
        Object a = align(actual, expected);
        if (a == null || !(a instanceof Comparable) || a.getClass() != expected.getClass()) {
            return null;
        }
        return ((Comparable) a).compareTo(expected);
    }

    /** Converts the actual value to the expected value's type when that is unambiguous. */
    private static Object align(Object actual, Object expected) {
        try {
            if (expected instanceof BigDecimal) {
                return actual instanceof Boolean ? null : ValueType.toNumber(actual);
            }
            if (expected instanceof LocalDate) {
                return ValueType.toDate(actual);
            }
            if (expected instanceof Boolean) {
                return ValueType.toBoolean(actual);
            }
            if (expected instanceof String) {
                return actual instanceof String ? actual : (actual instanceof BigDecimal b ? b.toPlainString() : actual.toString());
            }
        } catch (RuntimeException e) {
            return null;
        }
        return actual;
    }

    record Any(String source) implements UnaryTest {
        @Override
        public boolean matches(Object value) {
            return true;
        }
    }

    record Compare(String source, String op, Object expected) implements UnaryTest {
        @Override
        public boolean matches(Object value) {
            if (expected == null) {
                return op.equals("!=") ? value != null : value == null;
            }
            Integer c = compare(value, expected);
            if (c == null) {
                return op.equals("!=") && value != null;
            }
            return switch (op) {
                case "=" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> false;
            };
        }
    }

    record Range(String source, boolean lowInclusive, Object low, Object high, boolean highInclusive) implements UnaryTest {
        @Override
        public boolean matches(Object value) {
            Integer lo = compare(value, low);
            Integer hi = compare(value, high);
            if (lo == null || hi == null) {
                return false;
            }
            return (lowInclusive ? lo >= 0 : lo > 0) && (highInclusive ? hi <= 0 : hi < 0);
        }
    }

    record AnyOf(String source, List<UnaryTest> tests) implements UnaryTest {
        @Override
        public boolean matches(Object value) {
            return tests.stream().anyMatch(t -> t.matches(value));
        }
    }

    record Not(String source, List<UnaryTest> tests) implements UnaryTest {
        @Override
        public boolean matches(Object value) {
            return tests.stream().noneMatch(t -> t.matches(value));
        }
    }
}
