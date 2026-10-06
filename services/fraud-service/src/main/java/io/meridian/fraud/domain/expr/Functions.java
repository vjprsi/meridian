package io.meridian.fraud.domain.expr;

import io.meridian.fraud.domain.expr.RuleExpression.Bindings;
import io.meridian.fraud.domain.expr.RuleExpression.Node;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The fixed, side-effect free function whitelist of the rule language. */
final class Functions {
    /** Function name to arity. */
    static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("on_list", 1), Map.entry("size", 1), Map.entry("lower", 1), Map.entry("upper", 1),
            Map.entry("contains", 2), Map.entry("starts_with", 2), Map.entry("ends_with", 2), Map.entry("abs", 1),
            Map.entry("min", 2), Map.entry("max", 2), Map.entry("coalesce", 2), Map.entry("has", 1));

    private Functions() {}

    static Object call(String fn, List<Node> args, Bindings b) {
        if (fn.equals("on_list")) {
            return b.onList((String) ((RuleExpression.Literal) args.getFirst()).value());
        }
        if (fn.equals("has")) {
            return b.resolve(((RuleExpression.Ident) args.getFirst()).name()) != null;
        }
        Object a = args.getFirst().eval(b);
        Object c = args.size() > 1 ? args.get(1).eval(b) : null;
        return switch (fn) {
            case "size" -> a instanceof Collection<?> col ? BigDecimal.valueOf(col.size())
                    : a instanceof String s ? BigDecimal.valueOf(s.length()) : null;
            case "lower" -> a instanceof String s ? s.toLowerCase(Locale.ROOT) : null;
            case "upper" -> a instanceof String s ? s.toUpperCase(Locale.ROOT) : null;
            case "contains" -> a instanceof String s && c instanceof String t ? s.contains(t)
                    : a instanceof Collection<?> col && c != null ? col.stream().anyMatch(x -> Values.equal(RuleExpression.normalise(x), c)) : false;
            case "starts_with" -> a instanceof String s && c instanceof String t && s.startsWith(t);
            case "ends_with" -> a instanceof String s && c instanceof String t && s.endsWith(t);
            case "abs" -> a instanceof BigDecimal d ? d.abs() : null;
            case "min" -> a instanceof BigDecimal x && c instanceof BigDecimal y ? x.min(y) : null;
            case "max" -> a instanceof BigDecimal x && c instanceof BigDecimal y ? x.max(y) : null;
            case "coalesce" -> a != null ? a : c;
            default -> null;
        };
    }
}
