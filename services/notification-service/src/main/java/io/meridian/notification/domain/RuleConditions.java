package io.meridian.notification.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A safe subset of CEL for notification rule conditions: comparisons of a payload path with a literal, joined by
 * {@code &&}. Examples: {@code amount >= 1000}, {@code status == 'POSTED' && channel != "ATM"}, {@code is_card == true}.
 * Paths are dotted ({@code account.type}); literals are quoted strings, numbers, true/false or null.
 */
public final class RuleConditions {
    private static final Pattern CLAUSE = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(==|!=|>=|<=|>|<)\\s*('([^']*)'|\"([^\"]*)\"|-?\\d+(\\.\\d+)?|true|false|null)\\s*$");

    private RuleConditions() {}

    public static final class InvalidConditionException extends RuntimeException {
        public InvalidConditionException(String message) {
            super(message);
        }
    }

    /** Throws {@link InvalidConditionException} if the expression is not in the supported subset. */
    public static void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        for (String clause : expression.split("&&")) {
            if (!CLAUSE.matcher(clause).matches()) {
                throw new InvalidConditionException("Unsupported condition clause: " + clause.trim());
            }
        }
    }

    public static boolean matches(String expression, Map<String, Object> payload) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        validate(expression);
        for (String clause : expression.split("&&")) {
            Matcher m = CLAUSE.matcher(clause);
            if (!m.matches() || !clause(m, payload)) {
                return false;
            }
        }
        return true;
    }

    private static boolean clause(Matcher m, Map<String, Object> payload) {
        Object actual = lookup(payload, m.group(1));
        String op = m.group(2);
        String literal = m.group(3);
        Object expected;
        if (m.group(4) != null) {
            expected = m.group(4);
        } else if (m.group(5) != null) {
            expected = m.group(5);
        } else if ("true".equals(literal) || "false".equals(literal)) {
            expected = Boolean.valueOf(literal);
        } else if ("null".equals(literal)) {
            expected = null;
        } else {
            expected = new BigDecimal(literal);
        }
        if (expected instanceof BigDecimal num) {
            BigDecimal a = number(actual);
            if (a == null) {
                return "!=".equals(op);
            }
            int c = a.compareTo(num);
            return switch (op) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                default -> c <= 0;
            };
        }
        boolean eq = expected == null ? actual == null
                : actual != null && String.valueOf(actual).equals(String.valueOf(expected));
        return switch (op) {
            case "==" -> eq;
            case "!=" -> !eq;
            default -> false; // ordering only for numbers
        };
    }

    private static BigDecimal number(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Object lookup(Map<String, Object> payload, String path) {
        Object current = payload;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && part.matches("\\d+") && Integer.parseInt(part) < list.size()) {
                current = list.get(Integer.parseInt(part));
            } else {
                return null;
            }
        }
        return current;
    }

    /** Glob match of an event type against a rule pattern: exact, {@code *}, or prefix wildcard {@code payments.*}. */
    public static boolean eventMatches(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }
        if ("*".equals(pattern) || pattern.equals(eventType)) {
            return true;
        }
        if (pattern.endsWith(".*")) {
            return eventType.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }
}
