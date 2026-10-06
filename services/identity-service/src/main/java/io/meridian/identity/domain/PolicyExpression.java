package io.meridian.identity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A safe, side-effect-free subset of CEL used for ABAC conditions. No script engine, no method calls.
 * <p>Supported:
 * <ul>
 *   <li>literals: numbers ({@code 10000}, {@code 0.5}), strings ({@code 'x'} or {@code "x"}), {@code true},
 *       {@code false}, {@code null}, lists {@code ['A', 'B']}</li>
 *   <li>paths into the evaluation context: {@code subject.branch_id}, {@code resource.amount},
 *       {@code context.hour}, {@code action}</li>
 *   <li>comparison: {@code == != < <= > >=} (numbers compared numerically, strings lexicographically, so ISO
 *       dates/times work), membership {@code x in [..]} / {@code x in resource.list}</li>
 *   <li>boolean: {@code &&}, {@code ||}, {@code !}, parentheses; {@code and}/{@code or}/{@code not} keywords</li>
 * </ul>
 * Missing paths evaluate to {@code null}; ordering comparisons with {@code null} are false.
 */
public final class PolicyExpression {
    private final Node root;
    private final String source;

    private PolicyExpression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /** Parses an expression; throws {@link IllegalArgumentException} with a readable message if malformed. */
    public static PolicyExpression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("condition expression is empty");
        }
        Parser p = new Parser(tokenize(source));
        Node n = p.or();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("unexpected token '" + p.peek().text + "'");
        }
        return new PolicyExpression(source, n);
    }

    public String source() {
        return source;
    }

    /** Evaluates against a context of nested maps/lists; the result must be boolean. */
    public boolean evaluate(Map<String, Object> context) {
        Object v = root.eval(context);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("condition does not evaluate to a boolean");
    }

    // ------------------------------------------------------------------ AST

    private interface Node {
        Object eval(Map<String, Object> ctx);
    }

    private record Literal(Object value) implements Node {
        public Object eval(Map<String, Object> ctx) {
            return value;
        }
    }

    private record ListNode(List<Node> items) implements Node {
        public Object eval(Map<String, Object> ctx) {
            List<Object> out = new ArrayList<>();
            items.forEach(i -> out.add(i.eval(ctx)));
            return out;
        }
    }

    private record Path(String[] parts) implements Node {
        public Object eval(Map<String, Object> ctx) {
            Object cur = ctx;
            for (String p : parts) {
                if (cur instanceof Map<?, ?> m) {
                    cur = m.get(p);
                } else {
                    return null;
                }
            }
            return normalize(cur);
        }
    }

    private record Not(Node inner) implements Node {
        public Object eval(Map<String, Object> ctx) {
            return !bool(inner.eval(ctx));
        }
    }

    private record And(Node l, Node r) implements Node {
        public Object eval(Map<String, Object> ctx) {
            return bool(l.eval(ctx)) && bool(r.eval(ctx));
        }
    }

    private record Or(Node l, Node r) implements Node {
        public Object eval(Map<String, Object> ctx) {
            return bool(l.eval(ctx)) || bool(r.eval(ctx));
        }
    }

    private record Compare(String op, Node l, Node r) implements Node {
        public Object eval(Map<String, Object> ctx) {
            Object a = l.eval(ctx);
            Object b = r.eval(ctx);
            return switch (op) {
                case "==" -> equal(a, b);
                case "!=" -> !equal(a, b);
                case "in" -> b instanceof Collection<?> c && c.stream().anyMatch(x -> equal(a, normalize(x)));
                default -> {
                    Integer cmp = compare(a, b);
                    if (cmp == null) {
                        yield false;
                    }
                    yield switch (op) {
                        case "<" -> cmp < 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> cmp > 0;
                        case ">=" -> cmp >= 0;
                        default -> throw new IllegalArgumentException("unknown operator " + op);
                    };
                }
            };
        }
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("expected a boolean operand but got " + o);
    }

    private static Object normalize(Object o) {
        if (o instanceof Number n && !(o instanceof BigDecimal)) {
            return new BigDecimal(n.toString());
        }
        return o;
    }

    private static BigDecimal number(Object o) {
        if (o instanceof BigDecimal d) {
            return d;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (o instanceof String s && s.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(s);
        }
        return null;
    }

    private static boolean equal(Object a, Object b) {
        a = normalize(a);
        b = normalize(b);
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            BigDecimal x = number(a);
            BigDecimal y = number(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.toString(), b.toString());
    }

    private static Integer compare(Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        BigDecimal x = number(normalize(a));
        BigDecimal y = number(normalize(b));
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        if (a instanceof String s && b instanceof String t) {
            return s.compareTo(t);
        }
        return null;
    }

    // ------------------------------------------------------------------ lexer / parser

    private record Token(String kind, String text) {}

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))
                    && (out.isEmpty() || out.getLast().kind.equals("op") || out.getLast().text.equals("(")
                    || out.getLast().text.equals("[") || out.getLast().text.equals(",")))) {
                int j = i + 1;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Token("num", s.substring(i, j)));
                i = j;
            } else if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) {
                    throw new IllegalArgumentException("unterminated string literal");
                }
                out.add(new Token("str", s.substring(i + 1, j)));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) {
                    j++;
                }
                String w = s.substring(i, j);
                switch (w) {
                    case "and" -> out.add(new Token("op", "&&"));
                    case "or" -> out.add(new Token("op", "||"));
                    case "not" -> out.add(new Token("op", "!"));
                    case "in" -> out.add(new Token("op", "in"));
                    default -> out.add(new Token("id", w));
                }
                i = j;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("&&", "||", "==", "!=", "<=", ">=").contains(two)) {
                    out.add(new Token("op", two));
                    i += 2;
                } else if ("<>!".indexOf(c) >= 0) {
                    out.add(new Token("op", String.valueOf(c)));
                    i++;
                } else if ("()[],".indexOf(c) >= 0) {
                    out.add(new Token("punct", String.valueOf(c)));
                    i++;
                } else {
                    throw new IllegalArgumentException("unexpected character '" + c + "'");
                }
            }
        }
        return out;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return pos >= tokens.size();
        }

        Token peek() {
            return atEnd() ? new Token("eof", "<end>") : tokens.get(pos);
        }

        boolean accept(String text) {
            if (!atEnd() && tokens.get(pos).text.equals(text) && !tokens.get(pos).kind.equals("str")) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(String text) {
            if (!accept(text)) {
                throw new IllegalArgumentException("expected '" + text + "' but found '" + peek().text + "'");
            }
        }

        Node or() {
            Node n = and();
            while (accept("||")) {
                n = new Or(n, and());
            }
            return n;
        }

        Node and() {
            Node n = not();
            while (accept("&&")) {
                n = new And(n, not());
            }
            return n;
        }

        Node not() {
            if (accept("!")) {
                return new Not(not());
            }
            return comparison();
        }

        Node comparison() {
            Node l = primary();
            for (String op : List.of("==", "!=", "<=", ">=", "<", ">", "in")) {
                if (accept(op)) {
                    return new Compare(op, l, primary());
                }
            }
            return l;
        }

        Node primary() {
            Token t = peek();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of expression");
            }
            if (accept("(")) {
                Node n = or();
                expect(")");
                return n;
            }
            if (accept("[")) {
                List<Node> items = new ArrayList<>();
                if (!accept("]")) {
                    do {
                        items.add(primary());
                    } while (accept(","));
                    expect("]");
                }
                return new ListNode(items);
            }
            pos++;
            return switch (t.kind) {
                case "num" -> new Literal(new BigDecimal(t.text));
                case "str" -> new Literal(t.text);
                case "id" -> switch (t.text) {
                    case "true" -> new Literal(Boolean.TRUE);
                    case "false" -> new Literal(Boolean.FALSE);
                    case "null" -> new Literal(null);
                    default -> {
                        if (t.text.startsWith(".") || t.text.endsWith(".") || t.text.contains("..")) {
                            throw new IllegalArgumentException("malformed path '" + t.text + "'");
                        }
                        yield new Path(t.text.split("\\."));
                    }
                };
                default -> throw new IllegalArgumentException("unexpected token '" + t.text + "'");
            };
        }
    }
}
