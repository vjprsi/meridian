package io.meridian.cardauthorization.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A compiled authorisation-rule condition in a CEL subset:
 * <pre>
 *   expr    := or
 *   or      := and ('||' and)*
 *   and     := unary ('&&' unary)*
 *   unary   := '!' unary | cmp
 *   cmp     := primary (('==' | '!=' | '<' | '<=' | '>' | '>=') primary | 'in' list)?
 *   primary := number | 'string' | "string" | true | false | null | identifier | '(' expr ')' | list
 *   list    := '[' (primary (',' primary)*)? ']'
 * </pre>
 * Numbers are BigDecimal; a number compared with a numeric string compares numerically; any comparison with null
 * (unknown variable) is false except {@code == null} / {@code != null}. Evaluation never throws on missing variables.
 */
public final class Expression {
    private final String source;
    private final Node root;
    private final Set<String> identifiers;

    private Expression(String source, Node root, Set<String> identifiers) {
        this.source = source;
        this.root = root;
        this.identifiers = Collections.unmodifiableSet(identifiers);
    }

    /** Parses an expression; throws {@link IllegalArgumentException} with position on syntax errors. */
    public static Expression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("expression is empty");
        }
        Parser p = new Parser(source);
        Node n = p.or();
        if (p.peek().kind != Kind.EOF) {
            throw p.error("unexpected '" + p.peek().text + "'");
        }
        return new Expression(source, n, p.ids);
    }

    public String source() {
        return source;
    }

    /** Variables referenced by the expression. */
    public Set<String> identifiers() {
        return identifiers;
    }

    public boolean evaluate(Map<String, ?> vars) {
        return truthy(root.eval(vars));
    }

    // ------------------------------------------------------------------ AST

    private interface Node {
        Object eval(Map<String, ?> vars);
    }

    private record Literal(Object value) implements Node {
        public Object eval(Map<String, ?> vars) {
            return value;
        }
    }

    private record Ident(String name) implements Node {
        public Object eval(Map<String, ?> vars) {
            return normalise(vars.get(name));
        }
    }

    private record ListNode(List<Node> items) implements Node {
        public Object eval(Map<String, ?> vars) {
            List<Object> out = new ArrayList<>(items.size());
            for (Node n : items) {
                out.add(n.eval(vars));
            }
            return out;
        }
    }

    private record Not(Node inner) implements Node {
        public Object eval(Map<String, ?> vars) {
            return !truthy(inner.eval(vars));
        }
    }

    private record And(Node l, Node r) implements Node {
        public Object eval(Map<String, ?> vars) {
            return truthy(l.eval(vars)) && truthy(r.eval(vars));
        }
    }

    private record Or(Node l, Node r) implements Node {
        public Object eval(Map<String, ?> vars) {
            return truthy(l.eval(vars)) || truthy(r.eval(vars));
        }
    }

    private record In(Node l, Node r) implements Node {
        public Object eval(Map<String, ?> vars) {
            Object v = l.eval(vars);
            Object list = r.eval(vars);
            if (v == null || !(list instanceof List<?> items)) {
                return false;
            }
            for (Object item : items) {
                if (equal(v, item)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Cmp(String op, Node l, Node r) implements Node {
        public Object eval(Map<String, ?> vars) {
            Object a = l.eval(vars);
            Object b = r.eval(vars);
            return switch (op) {
                case "==" -> equal(a, b);
                case "!=" -> !equal(a, b);
                default -> {
                    Integer c = compare(a, b);
                    if (c == null) {
                        yield false;
                    }
                    yield switch (op) {
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        default -> c >= 0;
                    };
                }
            };
        }
    }

    // ------------------------------------------------------------------ semantics

    private static Object normalise(Object v) {
        if (v instanceof BigDecimal || v == null || v instanceof String || v instanceof Boolean || v instanceof List<?>) {
            return v;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        return v.toString();
    }

    static boolean truthy(Object v) {
        return v instanceof Boolean b && b;
    }

    private static BigDecimal number(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            BigDecimal x = number(a);
            BigDecimal y = number(b);
            return x != null && y != null && x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Integer compare(Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            BigDecimal x = number(a);
            BigDecimal y = number(b);
            return x == null || y == null ? null : x.compareTo(y);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        return null;
    }

    // ------------------------------------------------------------------ lexer + parser

    private enum Kind { NUMBER, STRING, IDENT, OP, EOF }

    private record Token(Kind kind, String text, int pos) {}

    private static final class Parser {
        private final String src;
        private final List<Token> tokens = new ArrayList<>();
        private final Set<String> ids = new LinkedHashSet<>();
        private int i;

        Parser(String src) {
            this.src = src;
            lex();
        }

        private void lex() {
            int p = 0;
            while (p < src.length()) {
                char c = src.charAt(p);
                if (Character.isWhitespace(c)) {
                    p++;
                } else if (Character.isDigit(c) || (c == '-' && p + 1 < src.length() && Character.isDigit(src.charAt(p + 1))
                        && (tokens.isEmpty() || tokens.getLast().kind == Kind.OP && !")]".contains(tokens.getLast().text)))) {
                    int s = p++;
                    while (p < src.length() && (Character.isDigit(src.charAt(p)) || src.charAt(p) == '.')) {
                        p++;
                    }
                    tokens.add(new Token(Kind.NUMBER, src.substring(s, p), s));
                } else if (c == '\'' || c == '"') {
                    int s = p++;
                    StringBuilder b = new StringBuilder();
                    while (p < src.length() && src.charAt(p) != c) {
                        if (src.charAt(p) == '\\' && p + 1 < src.length()) {
                            p++;
                        }
                        b.append(src.charAt(p++));
                    }
                    if (p >= src.length()) {
                        throw new IllegalArgumentException("unterminated string at " + s);
                    }
                    p++;
                    tokens.add(new Token(Kind.STRING, b.toString(), s));
                } else if (Character.isLetter(c) || c == '_') {
                    int s = p;
                    while (p < src.length() && (Character.isLetterOrDigit(src.charAt(p)) || src.charAt(p) == '_' || src.charAt(p) == '.')) {
                        p++;
                    }
                    tokens.add(new Token(Kind.IDENT, src.substring(s, p), s));
                } else {
                    String two = p + 1 < src.length() ? src.substring(p, p + 2) : "";
                    if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                        tokens.add(new Token(Kind.OP, two, p));
                        p += 2;
                    } else if ("<>!()[],".indexOf(c) >= 0) {
                        tokens.add(new Token(Kind.OP, String.valueOf(c), p));
                        p++;
                    } else {
                        throw new IllegalArgumentException("unexpected character '" + c + "' at " + p);
                    }
                }
            }
            tokens.add(new Token(Kind.EOF, "<end>", src.length()));
        }

        Token peek() {
            return tokens.get(i);
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at " + peek().pos);
        }

        private boolean accept(String op) {
            if (peek().kind == Kind.OP && peek().text.equals(op) || peek().kind == Kind.IDENT && peek().text.equals(op)) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(String op) {
            if (!accept(op)) {
                throw error("expected '" + op + "' but found '" + peek().text + "'");
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
            Node n = unary();
            while (accept("&&")) {
                n = new And(n, unary());
            }
            return n;
        }

        Node unary() {
            if (accept("!")) {
                return new Not(unary());
            }
            return cmp();
        }

        Node cmp() {
            Node l = primary();
            Token t = peek();
            if (t.kind == Kind.OP && List.of("==", "!=", "<", "<=", ">", ">=").contains(t.text)) {
                i++;
                return new Cmp(t.text, l, primary());
            }
            if (accept("in")) {
                if (!(peek().kind == Kind.OP && peek().text.equals("[")) && peek().kind != Kind.IDENT) {
                    throw error("expected a list after 'in'");
                }
                return new In(l, primary());
            }
            return l;
        }

        Node primary() {
            Token t = peek();
            switch (t.kind) {
                case NUMBER -> {
                    i++;
                    try {
                        return new Literal(new BigDecimal(t.text));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("bad number '" + t.text + "' at " + t.pos);
                    }
                }
                case STRING -> {
                    i++;
                    return new Literal(t.text);
                }
                case IDENT -> {
                    i++;
                    return switch (t.text) {
                        case "true" -> new Literal(Boolean.TRUE);
                        case "false" -> new Literal(Boolean.FALSE);
                        case "null" -> new Literal(null);
                        case "in" -> throw new IllegalArgumentException("unexpected 'in' at " + t.pos);
                        default -> {
                            ids.add(t.text);
                            yield new Ident(t.text);
                        }
                    };
                }
                case OP -> {
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
                    throw error("unexpected '" + t.text + "'");
                }
                default -> throw error("unexpected end of expression");
            }
        }
    }
}
