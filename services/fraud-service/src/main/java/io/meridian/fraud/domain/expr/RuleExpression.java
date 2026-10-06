package io.meridian.fraud.domain.expr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A compiled fraud rule expression in a small, safe, side-effect free language (a CEL subset): no loops, no
 * reflection, no script engine, bounded size and depth, so evaluation time is linear in the expression size.
 *
 * <pre>
 * literals     123  12.5  'text'  "text"  true  false  null  [1, 2, 'x']
 * identifiers  amount  device.is_emulator  velocity.count_1h  context.beneficiary_age_days
 * arithmetic   + - * / %   (decimal; division by zero yields null)
 * comparison   == != &lt; &lt;= &gt; &gt;=   (null-safe: comparing with null is false except ==/!=)
 * boolean      &amp;&amp; || !   and or not
 * membership   x in [a, b]   x not in [..]   x in 100..500 (inclusive range)   x between 1 and 9
 * functions    on_list('CODE') size(x) lower(s) upper(s) contains(s, sub) starts_with(s, p) ends_with(s, p)
 *              abs(n) min(a, b) max(a, b) coalesce(a, b) has(x)
 * </pre>
 */
public final class RuleExpression {
    public static final int MAX_LENGTH = 4000;
    static final int MAX_NODES = 600;
    static final int MAX_DEPTH = 48;
    private static final MathContext MC = MathContext.DECIMAL64;

    private final String source;
    private final Node root;
    private final Set<String> identifiers;
    private final Set<String> lists;

    private RuleExpression(String source, Node root, Set<String> identifiers, Set<String> lists) {
        this.source = source;
        this.root = root;
        this.identifiers = identifiers;
        this.lists = lists;
    }

    /** Parses and validates an expression; throws {@link ExpressionException} with the position of the error. */
    public static RuleExpression compile(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException(0, "expression is empty");
        }
        if (source.length() > MAX_LENGTH) {
            throw new ExpressionException(MAX_LENGTH, "expression longer than " + MAX_LENGTH + " characters");
        }
        Parser p = new Parser(Lexer.tokens(source));
        Node root = p.parse();
        return new RuleExpression(source, root, Set.copyOf(p.identifiers), Set.copyOf(p.lists));
    }

    public String source() {
        return source;
    }

    /** Dotted identifiers the expression reads (used to record matched values). */
    public Set<String> identifiers() {
        return identifiers;
    }

    /** List codes referenced through on_list(...). */
    public Set<String> listCodes() {
        return lists;
    }

    /** Evaluates to a boolean: anything other than {@code true} (including null and non-booleans) is false. */
    public boolean test(Bindings b) {
        return Boolean.TRUE.equals(evaluate(b));
    }

    public Object evaluate(Bindings b) {
        return root.eval(b);
    }

    /** Variables visible to an expression and the list-membership oracle for on_list(). */
    public interface Bindings {
        Object resolve(String dottedName);

        boolean onList(String listCode);

        /** Bindings over nested maps (keys are path segments), with a list-membership predicate. */
        static Bindings of(Map<String, ?> root, Predicate<String> onList) {
            return new Bindings() {
                @Override
                public Object resolve(String name) {
                    Object cur = root;
                    for (String seg : name.split("\\.")) {
                        if (cur instanceof Map<?, ?> m) {
                            cur = m.get(seg);
                        } else {
                            return null;
                        }
                    }
                    return normalise(cur);
                }

                @Override
                public boolean onList(String listCode) {
                    return onList.test(listCode);
                }
            };
        }
    }

    /** Converts Java numbers to BigDecimal and leaves other values as they are. */
    static Object normalise(Object v) {
        if (v instanceof BigDecimal) {
            return v;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? new BigDecimal(Double.toString(d)) : null;
        }
        return v;
    }

    // ------------------------------------------------------------------ AST

    interface Node {
        Object eval(Bindings b);
    }

    record Literal(Object value) implements Node {
        public Object eval(Bindings b) {
            return value;
        }
    }

    record Ident(String name) implements Node {
        public Object eval(Bindings b) {
            return b.resolve(name);
        }
    }

    record ListLit(List<Node> items) implements Node {
        public Object eval(Bindings b) {
            List<Object> out = new ArrayList<>(items.size());
            for (Node n : items) {
                out.add(n.eval(b));
            }
            return out;
        }
    }

    record Not(Node inner) implements Node {
        public Object eval(Bindings b) {
            Object v = inner.eval(b);
            return v instanceof Boolean x ? !x : null;
        }
    }

    record Neg(Node inner) implements Node {
        public Object eval(Bindings b) {
            Object v = inner.eval(b);
            BigDecimal d = v instanceof BigDecimal x ? x : v instanceof String s ? Values.parse(s) : null;
            return d == null ? null : d.negate();
        }
    }

    record And(Node l, Node r) implements Node {
        public Object eval(Bindings b) {
            return Boolean.TRUE.equals(l.eval(b)) && Boolean.TRUE.equals(r.eval(b));
        }
    }

    record Or(Node l, Node r) implements Node {
        public Object eval(Bindings b) {
            return Boolean.TRUE.equals(l.eval(b)) || Boolean.TRUE.equals(r.eval(b));
        }
    }

    record Arith(char op, Node l, Node r) implements Node {
        public Object eval(Bindings b) {
            Object a = l.eval(b);
            Object c = r.eval(b);
            BigDecimal x = number(a);
            BigDecimal y = number(c);
            if (op == '+' && a != null && c != null && (x == null || y == null)
                    && (a instanceof String || c instanceof String)) {
                return String.valueOf(a) + c;
            }
            if (x == null || y == null) {
                return null;
            }
            try {
                return switch (op) {
                    case '+' -> x.add(y);
                    case '-' -> x.subtract(y);
                    case '*' -> x.multiply(y, MC);
                    case '/' -> y.signum() == 0 ? null : x.divide(y, MC);
                    case '%' -> y.signum() == 0 ? null : x.remainder(y, MC);
                    default -> null;
                };
            } catch (ArithmeticException e) {
                return null; // e.g. remainder of numbers beyond the working precision
            }
        }

        private static BigDecimal number(Object v) {
            if (v instanceof BigDecimal d) {
                return d;
            }
            return v instanceof String s ? Values.parse(s) : null;
        }
    }

    record Compare(String op, Node l, Node r) implements Node {
        public Object eval(Bindings b) {
            Object a = l.eval(b);
            Object c = r.eval(b);
            return switch (op) {
                case "==" -> Values.equal(a, c);
                case "!=" -> !Values.equal(a, c);
                default -> {
                    Integer cmp = Values.compare(a, c);
                    if (cmp == null) {
                        yield false;
                    }
                    yield switch (op) {
                        case "<" -> cmp < 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> cmp > 0;
                        default -> cmp >= 0;
                    };
                }
            };
        }
    }

    record In(Node value, Node container, boolean negated) implements Node {
        public Object eval(Bindings b) {
            Object v = value.eval(b);
            Object c = container.eval(b);
            boolean found = false;
            if (c instanceof Collection<?> col) {
                for (Object item : col) {
                    if (Values.equal(v, normalise(item))) {
                        found = true;
                        break;
                    }
                }
            } else if (c instanceof String s && v instanceof String x) {
                found = s.contains(x);
            } else if (v == null || c == null) {
                return false;
            }
            return negated != found;
        }
    }

    record Range(Node value, Node low, Node high, boolean negated) implements Node {
        public Object eval(Bindings b) {
            Object v = value.eval(b);
            Integer lo = Values.compare(v, low.eval(b));
            Integer hi = Values.compare(v, high.eval(b));
            if (lo == null || hi == null) {
                return false;
            }
            boolean inside = lo >= 0 && hi <= 0;
            return negated != inside;
        }
    }

    record Call(String fn, List<Node> args) implements Node {
        public Object eval(Bindings b) {
            return Functions.call(fn, args, b);
        }
    }
}
