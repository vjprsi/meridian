package io.meridian.productcatalog.domain.eligibility;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates eligibility rules against an applicant profile. Outcome: ELIGIBLE when every rule passes; INELIGIBLE
 * when a hard rule fails; REFER (manual review) when only soft rules fail or required facts are missing.
 *
 * <p>Profile keys: {@code age} or {@code date_of_birth}, {@code residency_country}, {@code nationality},
 * {@code kyc_tier}, {@code segment}, {@code income}, {@code credit_score}, {@code existing_products} (list of
 * product codes), {@code customer_type}. Rule values are JSON objects: {@code {"value": x}},
 * {@code {"values": [..]}} or {@code {"min": a, "max": b}}.
 */
public final class EligibilityEvaluator {
    private EligibilityEvaluator() {}

    public enum Outcome { ELIGIBLE, REFER, INELIGIBLE }

    public enum RuleResult { PASS, FAIL, MISSING_DATA }

    /**
     * @param decisionTableResult for rules delegated to a decision table: the table's verdict (null = unavailable)
     */
    public record Rule(UUID id, String ruleType, String operator, Object value, String expression, boolean hard,
                       String rejectionCode, String rejectionMessage, UUID decisionTableId, Boolean decisionTableResult) {}

    public record RuleEvaluation(Rule rule, Object actual, RuleResult result, String reason) {}

    public record Evaluation(Outcome outcome, List<RuleEvaluation> rules, List<String> reasons) {}

    static final Map<String, String> ATTRIBUTE = Map.of(
            "AGE", "age",
            "RESIDENCY", "residency_country",
            "NATIONALITY", "nationality",
            "KYC_TIER", "kyc_tier",
            "SEGMENT", "segment",
            "INCOME", "income",
            "CREDIT_SCORE", "credit_score",
            "EXISTING_PRODUCT", "existing_products",
            "CUSTOMER_TYPE", "customer_type");

    public static final List<String> OPERATORS = List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN");

    public static Evaluation evaluate(List<Rule> rules, Map<String, Object> profile, LocalDate asOf) {
        List<RuleEvaluation> out = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean hardFail = false;
        boolean refer = false;
        for (Rule r : rules) {
            RuleEvaluation e = evaluateRule(r, profile, asOf);
            out.add(e);
            if (e.result() == RuleResult.PASS) {
                continue;
            }
            String code = r.rejectionCode() != null ? r.rejectionCode()
                    : (e.result() == RuleResult.MISSING_DATA ? "MISSING_" : "FAILED_") + r.ruleType();
            reasons.add(code);
            if (e.result() == RuleResult.FAIL && r.hard()) {
                hardFail = true;
            } else {
                refer = true;
            }
        }
        Outcome o = hardFail ? Outcome.INELIGIBLE : refer ? Outcome.REFER : Outcome.ELIGIBLE;
        return new Evaluation(o, List.copyOf(out), List.copyOf(reasons));
    }

    static RuleEvaluation evaluateRule(Rule r, Map<String, Object> profile, LocalDate asOf) {
        if (r.decisionTableId() != null) {
            if (r.decisionTableResult() == null) {
                return new RuleEvaluation(r, null, RuleResult.MISSING_DATA, "decision table could not be evaluated");
            }
            return new RuleEvaluation(r, r.decisionTableResult(),
                    r.decisionTableResult() ? RuleResult.PASS : RuleResult.FAIL, "decision table verdict");
        }
        if ("CUSTOM_EXPRESSION".equals(r.ruleType())) {
            return expression(r, profile, asOf);
        }
        String attr = ATTRIBUTE.get(r.ruleType());
        Object actual = attr == null ? null : fact(attr, profile, asOf);
        if (actual == null) {
            return new RuleEvaluation(r, null, RuleResult.MISSING_DATA, attr + " is not known");
        }
        boolean ok = compare(actual, r.operator(), r.value());
        return new RuleEvaluation(r, actual, ok ? RuleResult.PASS : RuleResult.FAIL, null);
    }

    /** Reads a fact; {@code age} is derived from {@code date_of_birth} at {@code asOf} when not given. */
    static Object fact(String attr, Map<String, Object> profile, LocalDate asOf) {
        Object v = profile.get(attr);
        if (v == null && attr.equals("age") && profile.get("date_of_birth") != null) {
            LocalDate dob = LocalDate.parse(profile.get("date_of_birth").toString());
            v = BigDecimal.valueOf(Period.between(dob, asOf).getYears());
        }
        return v;
    }

    /** Typed comparison: numbers by value, everything else case-insensitive text; collections for IN semantics. */
    public static boolean compare(Object actual, String operator, Object expected) {
        String op = operator == null ? "EQ" : operator.toUpperCase(Locale.ROOT);
        if (actual instanceof Collection<?> held) {
            // e.g. EXISTING_PRODUCT: the applicant holds a set of product codes
            List<Object> wanted = asList(expected);
            boolean any = held.stream().anyMatch(h -> wanted.stream().anyMatch(w -> eq(h, w)));
            return switch (op) {
                case "NE", "NOT_IN" -> !any;
                default -> any;
            };
        }
        return switch (op) {
            case "EQ" -> eq(actual, single(expected));
            case "NE" -> !eq(actual, single(expected));
            case "GT" -> cmp(actual, single(expected)) > 0;
            case "GTE" -> cmp(actual, single(expected)) >= 0;
            case "LT" -> cmp(actual, single(expected)) < 0;
            case "LTE" -> cmp(actual, single(expected)) <= 0;
            case "IN" -> asList(expected).stream().anyMatch(e -> eq(actual, e));
            case "NOT_IN" -> asList(expected).stream().noneMatch(e -> eq(actual, e));
            case "BETWEEN" -> {
                List<Object> range = asList(expected);
                if (range.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN needs [min, max]");
                }
                yield cmp(actual, range.get(0)) >= 0 && cmp(actual, range.get(1)) <= 0;
            }
            default -> throw new IllegalArgumentException("Unknown operator " + operator);
        };
    }

    /** Normalises a stored rule value ({"value"}, {"values"}, {"min","max"}, scalar or list) for comparison. */
    public static Object normalizeValue(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            if (m.containsKey("values")) {
                return m.get("values");
            }
            if (m.containsKey("min") || m.containsKey("max")) {
                return List.of(m.get("min"), m.get("max"));
            }
            if (m.containsKey("from") || m.containsKey("to")) {
                return List.of(m.get("from"), m.get("to"));
            }
            if (m.containsKey("value")) {
                return m.get("value");
            }
            if (m.size() == 1) {
                return m.values().iterator().next();
            }
        }
        return raw;
    }

    private static final Pattern CLAUSE = Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s*(==|!=|>=|<=|>|<|in)\\s*(.+?)\\s*$");

    /**
     * Minimal CEL subset for CUSTOM_EXPRESSION: clauses {@code fact op literal} joined by {@code &&} or {@code ||}
     * (&& binds tighter). Literals: numbers, 'text' / "text", true/false, ['a','b'] for {@code in}.
     */
    static RuleEvaluation expression(Rule r, Map<String, Object> profile, LocalDate asOf) {
        String expr = r.expression();
        if (expr == null || expr.isBlank()) {
            return new RuleEvaluation(r, null, RuleResult.MISSING_DATA, "expression is empty");
        }
        boolean anyOr = false;
        for (String disjunct : expr.split("\\|\\|")) {
            boolean all = true;
            for (String clause : disjunct.split("&&")) {
                Matcher m = CLAUSE.matcher(clause);
                if (!m.matches()) {
                    throw new IllegalArgumentException("Unsupported expression clause: " + clause.trim());
                }
                Object actual = fact(m.group(1), profile, asOf);
                if (actual == null) {
                    return new RuleEvaluation(r, null, RuleResult.MISSING_DATA, m.group(1) + " is not known");
                }
                String op = switch (m.group(2)) {
                    case "==" -> "EQ";
                    case "!=" -> "NE";
                    case ">=" -> "GTE";
                    case "<=" -> "LTE";
                    case ">" -> "GT";
                    case "<" -> "LT";
                    default -> "IN";
                };
                all &= compare(actual, op, literal(m.group(3)));
            }
            anyOr |= all;
        }
        return new RuleEvaluation(r, null, anyOr ? RuleResult.PASS : RuleResult.FAIL, expr);
    }

    private static Object literal(String s) {
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            List<Object> l = new ArrayList<>();
            for (String p : t.substring(1, t.length() - 1).split(",")) {
                if (!p.isBlank()) {
                    l.add(literal(p));
                }
            }
            return l;
        }
        if (t.length() >= 2 && (t.startsWith("'") && t.endsWith("'") || t.startsWith("\"") && t.endsWith("\""))) {
            return t.substring(1, t.length() - 1);
        }
        if (t.equals("true") || t.equals("false")) {
            return Boolean.valueOf(t);
        }
        return new BigDecimal(t);
    }

    private static Object single(Object expected) {
        if (expected instanceof List<?> l) {
            if (l.size() != 1) {
                throw new IllegalArgumentException("operator needs a single value");
            }
            return l.getFirst();
        }
        return expected;
    }

    private static List<Object> asList(Object expected) {
        if (expected instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        if (expected instanceof String s && s.contains(",")) {
            List<Object> l = new ArrayList<>();
            for (String p : s.split(",")) {
                l.add(p.trim());
            }
            return l;
        }
        List<Object> l = new ArrayList<>();
        l.add(expected);
        return l;
    }

    private static boolean eq(Object a, Object b) {
        BigDecimal x = num(a);
        BigDecimal y = num(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return Objects.equals(String.valueOf(a).toLowerCase(Locale.ROOT), String.valueOf(b).toLowerCase(Locale.ROOT));
        }
        return a != null && b != null && a.toString().trim().equalsIgnoreCase(b.toString().trim());
    }

    private static int cmp(Object a, Object b) {
        BigDecimal x = num(a);
        BigDecimal y = num(b);
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        if (a == null || b == null) {
            throw new IllegalArgumentException("cannot order null");
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    static BigDecimal num(Object o) {
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
