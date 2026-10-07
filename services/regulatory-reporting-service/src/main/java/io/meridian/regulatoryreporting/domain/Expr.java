package io.meridian.regulatoryreporting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * A small, safe expression language used by report mappings and validation rules. It is parsed into an AST and
 * interpreted; nothing is ever compiled to SQL or code. Supported:
 * <ul>
 *   <li>literals: decimal numbers, 'strings' / "strings", TRUE, FALSE, NULL</li>
 *   <li>identifiers (dotted paths such as {@code balance.amount}, cell refs such as {@code C010}) and parameters
 *       {@code :name}</li>
 *   <li>arithmetic {@code + - * / %}, comparison {@code = == != <> < <= > >=}, {@code IN (...)}, {@code NOT IN},
 *       {@code IS [NOT] NULL}, boolean {@code AND OR NOT} (also {@code && || !})</li>
 *   <li>functions ABS, ROUND, FLOOR, CEIL, MIN, MAX, COALESCE, IF, UPPER, LOWER, CONCAT, LEN, YEAR, MONTH, DIV (NULL
 *       instead of a division by zero),
 *       DAYS_BETWEEN, STARTS_WITH, CONTAINS</li>
 * </ul>
 * Arithmetic is exact ({@link BigDecimal}, DECIMAL128); NULL propagates through arithmetic and comparisons
 * evaluate to FALSE when an operand is NULL. Inputs are bounded (length, nesting) so hostile input cannot exhaust
 * the stack.
 */
public final class Expr {
    public static final int MAX_LENGTH = 4000;
    private static final int MAX_DEPTH = 64;
    private static final MathContext MC = MathContext.DECIMAL128;

    private final String source;
    private final Node root;

    private Expr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /** Parses an expression; throws {@link ExprException} with a position-bearing message when invalid. */
    public static Expr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ExprException("expression is empty");
        }
        if (text.length() > MAX_LENGTH) {
            throw new ExprException("expression is longer than " + MAX_LENGTH + " characters");
        }
        Parser p = new Parser(tokenize(text));
        Node n = p.parseExpr(0);
        if (!p.atEnd()) {
            throw new ExprException("unexpected '" + p.peek().text + "' at position " + p.peek().pos);
        }
        return new Expr(text, n);
    }

    public String source() {
        return source;
    }

    /** Identifiers referenced (variables, not functions or parameters). */
    public Set<String> identifiers() {
        Set<String> out = new LinkedHashSet<>();
        root.collect(out, false);
        return out;
    }

    /** Parameters referenced ({@code :name}). */
    public Set<String> parameters() {
        Set<String> out = new LinkedHashSet<>();
        root.collect(out, true);
        return out;
    }

    /** Evaluates with identifiers resolved by {@code vars} and parameters by {@code params} (both may return null). */
    public Object eval(Function<String, Object> vars, Function<String, Object> params) {
        return root.eval(new Env(vars, params == null ? k -> null : params));
    }

    public Object eval(Function<String, Object> vars) {
        return eval(vars, null);
    }

    /** Evaluates as a condition: TRUE only for boolean true. */
    public boolean test(Function<String, Object> vars, Function<String, Object> params) {
        return Boolean.TRUE.equals(eval(vars, params));
    }

    // ------------------------------------------------------------------ value helpers

    /** Converts a value to a decimal; numeric strings are accepted, anything else is null. */
    public static BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
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

    public static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v);
    }

    static int compare(Object a, Object b) {
        BigDecimal da = toDecimal(a);
        BigDecimal db = toDecimal(b);
        if (da != null && db != null) {
            return da.compareTo(db);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return Boolean.compare(ba, bb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    static boolean equal(Object a, Object b) {
        BigDecimal da = toDecimal(a);
        BigDecimal db = toDecimal(b);
        if (da != null && db != null) {
            return da.compareTo(db) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    // ------------------------------------------------------------------ AST

    private record Env(Function<String, Object> vars, Function<String, Object> params) {}

    private interface Node {
        Object eval(Env env);

        default void collect(Set<String> out, boolean params) {}
    }

    private record Lit(Object value) implements Node {
        public Object eval(Env env) {
            return value;
        }
    }

    private record Var(String name) implements Node {
        public Object eval(Env env) {
            return normalise(env.vars().apply(name));
        }

        public void collect(Set<String> out, boolean params) {
            if (!params) {
                out.add(name);
            }
        }
    }

    private record Param(String name) implements Node {
        public Object eval(Env env) {
            return normalise(env.params().apply(name));
        }

        public void collect(Set<String> out, boolean params) {
            if (params) {
                out.add(name);
            }
        }
    }

    private static Object normalise(Object v) {
        if (v instanceof Number n && !(v instanceof BigDecimal)) {
            return new BigDecimal(n.toString());
        }
        return v;
    }

    private record Unary(String op, Node arg) implements Node {
        public Object eval(Env env) {
            Object v = arg.eval(env);
            if ("NOT".equals(op)) {
                return v == null ? null : !truthy(v);
            }
            BigDecimal d = toDecimal(v);
            return d == null ? null : d.negate();
        }

        public void collect(Set<String> out, boolean params) {
            arg.collect(out, params);
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        public Object eval(Env env) {
            switch (op) {
                case "AND" -> {
                    Object l = left.eval(env);
                    if (Boolean.FALSE.equals(l)) {
                        return false;
                    }
                    Object r = right.eval(env);
                    return truthy(l) && truthy(r);
                }
                case "OR" -> {
                    Object l = left.eval(env);
                    if (truthy(l)) {
                        return true;
                    }
                    return truthy(right.eval(env));
                }
                default -> {
                    Object l = left.eval(env);
                    Object r = right.eval(env);
                    return apply(l, r);
                }
            }
        }

        private Object apply(Object l, Object r) {
            switch (op) {
                case "=", "!=" -> {
                    if (l == null || r == null) {
                        return false;
                    }
                    boolean eq = equal(l, r);
                    return "=".equals(op) == eq;
                }
                case "<", "<=", ">", ">=" -> {
                    if (l == null || r == null) {
                        return false;
                    }
                    int c = compare(l, r);
                    return switch (op) {
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        default -> c >= 0;
                    };
                }
                default -> {
                    if (l == null || r == null) {
                        return null;
                    }
                    if ("+".equals(op) && (toDecimal(l) == null || toDecimal(r) == null)) {
                        return String.valueOf(l) + r;
                    }
                    BigDecimal a = toDecimal(l);
                    BigDecimal b = toDecimal(r);
                    if (a == null || b == null) {
                        throw new ExprException("operator " + op + " needs numbers, got '" + l + "' and '" + r + "'");
                    }
                    return switch (op) {
                        case "+" -> a.add(b, MC);
                        case "-" -> a.subtract(b, MC);
                        case "*" -> a.multiply(b, MC);
                        case "/" -> {
                            if (b.signum() == 0) {
                                throw new ExprException("division by zero");
                            }
                            yield a.divide(b, MC);
                        }
                        case "%" -> {
                            if (b.signum() == 0) {
                                throw new ExprException("division by zero");
                            }
                            yield a.remainder(b, MC);
                        }
                        default -> throw new ExprException("unknown operator " + op);
                    };
                }
            }
        }

        public void collect(Set<String> out, boolean params) {
            left.collect(out, params);
            right.collect(out, params);
        }
    }

    private record In(Node value, List<Node> options, boolean negate) implements Node {
        public Object eval(Env env) {
            Object v = value.eval(env);
            if (v == null) {
                return false;
            }
            boolean found = false;
            for (Node o : options) {
                Object ov = o.eval(env);
                if (ov instanceof List<?> list) {
                    for (Object x : list) {
                        if (x != null && equal(v, normalise(x))) {
                            found = true;
                        }
                    }
                } else if (ov != null && equal(v, ov)) {
                    found = true;
                }
            }
            return found != negate;
        }

        public void collect(Set<String> out, boolean params) {
            value.collect(out, params);
            options.forEach(o -> o.collect(out, params));
        }
    }

    private record IsNull(Node value, boolean negate) implements Node {
        public Object eval(Env env) {
            Object v = value.eval(env);
            boolean isNull = v == null || (v instanceof String s && s.isEmpty());
            return isNull != negate;
        }

        public void collect(Set<String> out, boolean params) {
            value.collect(out, params);
        }
    }

    private record Call(String fn, List<Node> args) implements Node {
        public Object eval(Env env) {
            if ("IF".equals(fn)) {
                return truthy(args.get(0).eval(env)) ? args.get(1).eval(env) : args.get(2).eval(env);
            }
            if ("COALESCE".equals(fn)) {
                for (Node a : args) {
                    Object v = a.eval(env);
                    if (v != null) {
                        return v;
                    }
                }
                return null;
            }
            List<Object> v = new ArrayList<>();
            for (Node a : args) {
                v.add(a.eval(env));
            }
            return Functions.apply(fn, v);
        }

        public void collect(Set<String> out, boolean params) {
            args.forEach(a -> a.collect(out, params));
        }
    }

    // ------------------------------------------------------------------ functions

    private static final class Functions {
        static final Set<String> NAMES = Set.of("ABS", "ROUND", "FLOOR", "CEIL", "MIN", "MAX", "COALESCE", "IF", "UPPER",
                "LOWER", "CONCAT", "LEN", "YEAR", "MONTH", "DAYS_BETWEEN", "STARTS_WITH", "CONTAINS", "DIV");

        static void checkArity(String fn, int n) {
            boolean ok = switch (fn) {
                case "ABS", "FLOOR", "CEIL", "UPPER", "LOWER", "LEN", "YEAR", "MONTH" -> n == 1;
                case "ROUND" -> n == 1 || n == 2;
                case "IF" -> n == 3;
                case "DAYS_BETWEEN", "STARTS_WITH", "CONTAINS", "DIV" -> n == 2;
                default -> n >= 1;
            };
            if (!ok) {
                throw new ExprException("wrong number of arguments for " + fn);
            }
        }

        static Object apply(String fn, List<Object> a) {
            switch (fn) {
                case "ABS" -> {
                    BigDecimal d = num(fn, a.get(0));
                    return d == null ? null : d.abs();
                }
                case "ROUND" -> {
                    BigDecimal d = num(fn, a.get(0));
                    int scale = a.size() > 1 && a.get(1) != null ? num(fn, a.get(1)).intValueExact() : 0;
                    return d == null ? null : d.setScale(scale, RoundingMode.HALF_EVEN);
                }
                case "FLOOR" -> {
                    BigDecimal d = num(fn, a.get(0));
                    return d == null ? null : d.setScale(0, RoundingMode.FLOOR);
                }
                case "CEIL" -> {
                    BigDecimal d = num(fn, a.get(0));
                    return d == null ? null : d.setScale(0, RoundingMode.CEILING);
                }
                case "MIN", "MAX" -> {
                    Object best = null;
                    for (Object o : a) {
                        if (o != null && (best == null || ("MIN".equals(fn) ? compare(o, best) < 0 : compare(o, best) > 0))) {
                            best = o;
                        }
                    }
                    return best;
                }
                case "UPPER" -> {
                    return a.get(0) == null ? null : String.valueOf(a.get(0)).toUpperCase(Locale.ROOT);
                }
                case "LOWER" -> {
                    return a.get(0) == null ? null : String.valueOf(a.get(0)).toLowerCase(Locale.ROOT);
                }
                case "CONCAT" -> {
                    StringBuilder sb = new StringBuilder();
                    a.forEach(o -> sb.append(o == null ? "" : plain(o)));
                    return sb.toString();
                }
                case "LEN" -> {
                    return a.get(0) == null ? null : new BigDecimal(String.valueOf(a.get(0)).length());
                }
                case "YEAR" -> {
                    LocalDate d = date(fn, a.get(0));
                    return d == null ? null : new BigDecimal(d.getYear());
                }
                case "MONTH" -> {
                    LocalDate d = date(fn, a.get(0));
                    return d == null ? null : new BigDecimal(d.getMonthValue());
                }
                case "DAYS_BETWEEN" -> {
                    LocalDate x = date(fn, a.get(0));
                    LocalDate y = date(fn, a.get(1));
                    return x == null || y == null ? null : new BigDecimal(ChronoUnit.DAYS.between(x, y));
                }
                case "STARTS_WITH" -> {
                    return a.get(0) != null && a.get(1) != null && String.valueOf(a.get(0)).startsWith(String.valueOf(a.get(1)));
                }
                case "CONTAINS" -> {
                    return a.get(0) != null && a.get(1) != null && String.valueOf(a.get(0)).contains(String.valueOf(a.get(1)));
                }
                case "DIV" -> {
                    BigDecimal x = num(fn, a.get(0));
                    BigDecimal y = num(fn, a.get(1));
                    return x == null || y == null || y.signum() == 0 ? null : x.divide(y, MC);
                }
                default -> throw new ExprException("unknown function " + fn);
            }
        }

        private static String plain(Object o) {
            return o instanceof BigDecimal b ? b.toPlainString() : String.valueOf(o);
        }

        private static BigDecimal num(String fn, Object o) {
            if (o == null) {
                return null;
            }
            BigDecimal d = toDecimal(o);
            if (d == null) {
                throw new ExprException(fn + " needs a number, got '" + o + "'");
            }
            return d;
        }

        private static LocalDate date(String fn, Object o) {
            if (o == null) {
                return null;
            }
            String s = String.valueOf(o);
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            } catch (DateTimeParseException e) {
                throw new ExprException(fn + " needs an ISO date, got '" + o + "'");
            }
        }
    }

    // ------------------------------------------------------------------ tokenizer

    private enum T { NUM, STR, ID, PARAM, OP, LP, RP, COMMA, END }

    private record Tok(T type, String text, int pos) {}

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int st = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String num = s.substring(st, i);
                try {
                    new BigDecimal(num);
                } catch (NumberFormatException e) {
                    throw new ExprException("invalid number '" + num + "' at position " + st);
                }
                out.add(new Tok(T.NUM, num, st));
            } else if (c == '\'' || c == '"') {
                int st = i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char d = s.charAt(i);
                    if (d == c) {
                        if (i + 1 < n && s.charAt(i + 1) == c) {
                            sb.append(c);
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    throw new ExprException("unterminated string at position " + st);
                }
                out.add(new Tok(T.STR, sb.toString(), st));
            } else if (Character.isLetter(c) || c == '_') {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Tok(T.ID, s.substring(st, i), st));
            } else if (c == ':') {
                int st = i++;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                if (i == st + 1) {
                    throw new ExprException("parameter name expected after ':' at position " + st);
                }
                out.add(new Tok(T.PARAM, s.substring(st + 1, i), st));
            } else if (c == '(') {
                out.add(new Tok(T.LP, "(", i++));
            } else if (c == ')') {
                out.add(new Tok(T.RP, ")", i++));
            } else if (c == ',') {
                out.add(new Tok(T.COMMA, ",", i++));
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (List.of("<=", ">=", "!=", "<>", "==", "&&", "||").contains(two)) {
                    out.add(new Tok(T.OP, two, i));
                    i += 2;
                } else if ("+-*/%<>=!".indexOf(c) >= 0) {
                    out.add(new Tok(T.OP, String.valueOf(c), i++));
                } else {
                    throw new ExprException("unexpected character '" + c + "' at position " + i);
                }
            }
        }
        out.add(new Tok(T.END, "<end>", n));
        return out;
    }

    // ------------------------------------------------------------------ parser (precedence climbing)

    private static final class Parser {
        private final List<Tok> toks;
        private int i;

        Parser(List<Tok> toks) {
            this.toks = toks;
        }

        Tok peek() {
            return toks.get(i);
        }

        boolean atEnd() {
            return peek().type == T.END;
        }

        private boolean kw(String word) {
            Tok t = peek();
            return t.type == T.ID && t.text.equalsIgnoreCase(word);
        }

        private boolean op(String... ops) {
            Tok t = peek();
            if (t.type != T.OP) {
                return false;
            }
            for (String o : ops) {
                if (t.text.equals(o)) {
                    return true;
                }
            }
            return false;
        }

        private void expect(T type, String what) {
            if (peek().type != type) {
                throw new ExprException(what + " expected at position " + peek().pos + " but found '" + peek().text + "'");
            }
            i++;
        }

        Node parseExpr(int depth) {
            if (depth > MAX_DEPTH) {
                throw new ExprException("expression is nested too deeply");
            }
            return or(depth);
        }

        private Node or(int d) {
            Node l = and(d);
            while (kw("OR") || op("||")) {
                i++;
                l = new Binary("OR", l, and(d));
            }
            return l;
        }

        private Node and(int d) {
            Node l = not(d);
            while (kw("AND") || op("&&")) {
                i++;
                l = new Binary("AND", l, not(d));
            }
            return l;
        }

        private Node not(int d) {
            if (kw("NOT") || op("!")) {
                i++;
                return new Unary("NOT", not(d + 1));
            }
            return cmp(d);
        }

        private Node cmp(int d) {
            Node l = add(d);
            if (op("=", "==", "!=", "<>", "<", "<=", ">", ">=")) {
                String o = peek().text;
                i++;
                o = switch (o) {
                    case "==" -> "=";
                    case "<>" -> "!=";
                    default -> o;
                };
                return new Binary(o, l, add(d));
            }
            boolean negate = false;
            if (kw("NOT") && i + 1 < toks.size() && toks.get(i + 1).type == T.ID
                    && toks.get(i + 1).text.equalsIgnoreCase("IN")) {
                negate = true;
                i++;
            }
            if (kw("IN")) {
                i++;
                expect(T.LP, "'('");
                List<Node> opts = new ArrayList<>();
                opts.add(parseExpr(d + 1));
                while (peek().type == T.COMMA) {
                    i++;
                    opts.add(parseExpr(d + 1));
                }
                expect(T.RP, "')'");
                return new In(l, opts, negate);
            }
            if (kw("IS")) {
                i++;
                boolean not = false;
                if (kw("NOT")) {
                    not = true;
                    i++;
                }
                if (!kw("NULL")) {
                    throw new ExprException("NULL expected after IS at position " + peek().pos);
                }
                i++;
                return new IsNull(l, not);
            }
            return l;
        }

        private Node add(int d) {
            Node l = mul(d);
            while (op("+", "-")) {
                String o = peek().text;
                i++;
                l = new Binary(o, l, mul(d));
            }
            return l;
        }

        private Node mul(int d) {
            Node l = unary(d);
            while (op("*", "/", "%")) {
                String o = peek().text;
                i++;
                l = new Binary(o, l, unary(d));
            }
            return l;
        }

        private Node unary(int d) {
            if (op("-")) {
                i++;
                return new Unary("-", unary(d + 1));
            }
            if (op("+")) {
                i++;
                return unary(d + 1);
            }
            return primary(d);
        }

        private Node primary(int d) {
            Tok t = peek();
            switch (t.type) {
                case NUM -> {
                    i++;
                    return new Lit(new BigDecimal(t.text));
                }
                case STR -> {
                    i++;
                    return new Lit(t.text);
                }
                case PARAM -> {
                    i++;
                    return new Param(t.text);
                }
                case LP -> {
                    i++;
                    Node n = parseExpr(d + 1);
                    expect(T.RP, "')'");
                    return n;
                }
                case ID -> {
                    i++;
                    String up = t.text.toUpperCase(Locale.ROOT);
                    switch (up) {
                        case "TRUE" -> {
                            return new Lit(Boolean.TRUE);
                        }
                        case "FALSE" -> {
                            return new Lit(Boolean.FALSE);
                        }
                        case "NULL" -> {
                            return new Lit(null);
                        }
                        default -> {
                        }
                    }
                    if (peek().type == T.LP) {
                        if (!Functions.NAMES.contains(up)) {
                            throw new ExprException("unknown function " + t.text + " at position " + t.pos);
                        }
                        i++;
                        List<Node> args = new ArrayList<>();
                        if (peek().type != T.RP) {
                            args.add(parseExpr(d + 1));
                            while (peek().type == T.COMMA) {
                                i++;
                                args.add(parseExpr(d + 1));
                            }
                        }
                        expect(T.RP, "')'");
                        Functions.checkArity(up, args.size());
                        return new Call(up, args);
                    }
                    if (Set.of("AND", "OR", "NOT", "IN", "IS").contains(up)) {
                        throw new ExprException("unexpected keyword " + t.text + " at position " + t.pos);
                    }
                    return new Var(t.text);
                }
                default -> throw new ExprException("unexpected '" + t.text + "' at position " + t.pos);
            }
        }
    }

    /** Invalid expression or evaluation failure (bad operand types, division by zero). */
    public static final class ExprException extends RuntimeException {
        public ExprException(String message) {
            super(message);
        }
    }
}
