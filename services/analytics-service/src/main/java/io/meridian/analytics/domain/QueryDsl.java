package io.meridian.analytics.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Meridian query DSL: a small, read-only, SQL-shaped language over one whitelisted dataset.
 *
 * <pre>
 * SELECT &lt;item&gt;[, ...] FROM &lt;dataset&gt;
 *   [WHERE &lt;condition&gt;] [GROUP BY &lt;field&gt;[, ...]] [ORDER BY &lt;column&gt; [ASC|DESC][, ...]] [LIMIT &lt;n&gt;]
 * item := * | &lt;expression&gt; [AS alias] | COUNT(*) | COUNT|SUM|AVG|MIN|MAX|COUNT_DISTINCT(&lt;expression&gt;) [AS alias]
 * </pre>
 *
 * Queries are parsed and interpreted in memory over the dataset's rows; they are never translated to SQL. Only the
 * dataset's declared fields can be referenced, there are no joins, sub-queries, comments or statement separators, and
 * values come in only as {@code :parameters}.
 */
public final class QueryDsl {
    public static final int MAX_LIMIT = 10_000;
    public static final int MAX_LENGTH = 8_000;
    private static final Pattern CLAUSE = Pattern.compile("\\b(SELECT|FROM|WHERE|GROUP\\s+BY|ORDER\\s+BY|LIMIT)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS = Pattern.compile("\\s+AS\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AGG = Pattern.compile("^\\s*(COUNT_DISTINCT|COUNT|SUM|AVG|MIN|MAX)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DATASET = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final List<String> ORDER = List.of("SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "LIMIT");

    private QueryDsl() {}

    public enum Agg { COUNT, SUM, AVG, MIN, MAX, COUNT_DISTINCT }

    /** One output column. {@code expr} is null for COUNT(*). */
    public record Item(String alias, Expr expr, Agg agg, String source) {
        boolean plainField() {
            return agg == null && expr != null && IDENT.matcher(expr.source().trim()).matches();
        }
    }

    public record OrderBy(String column, boolean desc) {}

    public record Query(String dataset, List<Item> items, boolean selectAll, Expr where, List<String> groupBy,
                        List<OrderBy> orderBy, Integer limit, Set<String> parameters) {
        public boolean aggregated() {
            return !groupBy.isEmpty() || items.stream().anyMatch(i -> i.agg() != null);
        }

        public List<String> columns(Map<String, String> fields) {
            if (selectAll) {
                return new ArrayList<>(fields.keySet());
            }
            return items.stream().map(Item::alias).toList();
        }
    }

    public record Result(List<String> columns, List<Map<String, Object>> rows, int matched, boolean truncated) {}

    /** All problems found in a query. */
    public static final class QueryException extends RuntimeException {
        private final List<String> errors;

        public QueryException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    // ------------------------------------------------------------------ parse

    /** Parses and validates a query against the dataset catalog ({@code fieldsOf} returns the dataset's fields). */
    public static Query parse(String text, Function<String, Optional<Map<String, String>>> fieldsOf) {
        List<String> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            throw new QueryException(List.of("query is empty"));
        }
        if (text.length() > MAX_LENGTH) {
            throw new QueryException(List.of("query is longer than " + MAX_LENGTH + " characters"));
        }
        String masked = mask(text);
        if (masked.contains(";") || masked.contains("--") || masked.contains("/*")) {
            throw new QueryException(List.of("statement separators and comments are not allowed"));
        }
        Map<String, int[]> clauses = new LinkedHashMap<>();
        Matcher m = CLAUSE.matcher(masked);
        int last = -1;
        String lastName = null;
        while (m.find()) {
            String name = m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (clauses.containsKey(name)) {
                throw new QueryException(List.of(name + " appears more than once"));
            }
            if (lastName != null && ORDER.indexOf(name) <= ORDER.indexOf(lastName)) {
                throw new QueryException(List.of(name + " is out of order; expected SELECT ... FROM ... [WHERE] [GROUP BY] [ORDER BY] [LIMIT]"));
            }
            if (lastName != null) {
                clauses.get(lastName)[1] = m.start();
            }
            clauses.put(name, new int[] {m.end(), masked.length()});
            last = m.start();
            lastName = name;
        }
        if (!clauses.containsKey("SELECT") || !masked.substring(0, masked.toUpperCase(Locale.ROOT).indexOf("SELECT")).isBlank()) {
            throw new QueryException(List.of("query must start with SELECT"));
        }
        if (!clauses.containsKey("FROM")) {
            throw new QueryException(List.of("FROM <dataset> is required"));
        }
        String dataset = part(text, clauses.get("FROM")).trim();
        if (!DATASET.matcher(dataset).matches()) {
            throw new QueryException(List.of("FROM must name one dataset (no joins or sub-queries), got '" + dataset + "'"));
        }
        Map<String, String> fields = fieldsOf.apply(dataset).orElseThrow(() ->
                new QueryException(List.of("unknown dataset " + dataset)));

        String selectText = part(text, clauses.get("SELECT"));
        String selectMasked = part(masked, clauses.get("SELECT"));
        boolean selectAll = selectText.trim().equals("*");
        List<Item> items = new ArrayList<>();
        Set<String> parameters = new LinkedHashSet<>();
        if (!selectAll) {
            int n = 0;
            for (int[] span : splitTopLevel(selectMasked)) {
                n++;
                String itemText = selectText.substring(span[0], span[1]);
                Item item = item(itemText, selectMasked.substring(span[0], span[1]), n, fields, errors);
                if (item != null) {
                    items.add(item);
                    if (item.expr() != null) {
                        parameters.addAll(item.expr().parameters());
                    }
                }
            }
            if (items.isEmpty() && errors.isEmpty()) {
                errors.add("SELECT needs at least one item");
            }
            Set<String> aliases = new HashSet<>();
            for (Item i : items) {
                if (!aliases.add(i.alias())) {
                    errors.add("duplicate column name " + i.alias());
                }
            }
        }
        Expr where = null;
        if (clauses.containsKey("WHERE")) {
            where = expr(part(text, clauses.get("WHERE")), "WHERE", fields, errors);
            if (where != null) {
                parameters.addAll(where.parameters());
            }
        }
        List<String> groupBy = new ArrayList<>();
        if (clauses.containsKey("GROUP BY")) {
            for (String g : part(text, clauses.get("GROUP BY")).split(",")) {
                String f = g.trim();
                if (!fields.containsKey(f)) {
                    errors.add("GROUP BY field " + f + " is not a field of " + dataset);
                } else {
                    groupBy.add(f);
                }
            }
        }
        List<OrderBy> orderBy = new ArrayList<>();
        if (clauses.containsKey("ORDER BY")) {
            for (String o : part(text, clauses.get("ORDER BY")).split(",")) {
                String[] t = o.trim().split("\\s+");
                boolean desc = t.length > 1 && t[1].equalsIgnoreCase("DESC");
                if (t.length > 2 || (t.length == 2 && !t[1].equalsIgnoreCase("ASC") && !desc) || t[0].isEmpty()) {
                    errors.add("ORDER BY item must be '<column> [ASC|DESC]', got '" + o.trim() + "'");
                    continue;
                }
                orderBy.add(new OrderBy(t[0], desc));
            }
        }
        Integer limit = null;
        if (clauses.containsKey("LIMIT")) {
            String l = part(text, clauses.get("LIMIT")).trim();
            try {
                limit = Integer.parseInt(l);
                if (limit < 1 || limit > MAX_LIMIT) {
                    errors.add("LIMIT must be 1-" + MAX_LIMIT);
                }
            } catch (NumberFormatException e) {
                errors.add("LIMIT must be an integer, got '" + l + "'");
            }
        }
        Query q = new Query(dataset, items, selectAll, where, groupBy, orderBy, limit, parameters);
        if (errors.isEmpty()) {
            checkShape(q, fields, errors);
        }
        if (!errors.isEmpty()) {
            throw new QueryException(errors);
        }
        return q;
    }

    private static void checkShape(Query q, Map<String, String> fields, List<String> errors) {
        if (q.selectAll() && q.aggregated()) {
            errors.add("SELECT * cannot be combined with GROUP BY");
        }
        if (q.aggregated()) {
            for (Item i : q.items()) {
                if (i.agg() == null && !(i.plainField() && q.groupBy().contains(i.expr().source().trim()))) {
                    errors.add("column " + i.alias() + " must be aggregated or be a GROUP BY field");
                }
            }
        }
        List<String> columns = q.columns(fields);
        for (OrderBy o : q.orderBy()) {
            if (!columns.contains(o.column())) {
                errors.add("ORDER BY " + o.column() + " is not an output column " + columns);
            }
        }
    }

    private static Item item(String text, String masked, int n, Map<String, String> fields, List<String> errors) {
        String body = text;
        String alias = null;
        Matcher am = ALIAS.matcher(masked);
        if (am.find()) {
            alias = text.substring(am.start(1), am.end(1));
            body = text.substring(0, am.start());
        }
        if (body.isBlank()) {
            errors.add("empty SELECT item " + n);
            return null;
        }
        Matcher agg = AGG.matcher(body);
        if (agg.find()) {
            int open = agg.end() - 1;
            int close = matching(body, open);
            if (close < 0 || !body.substring(close + 1).isBlank()) {
                errors.add("item " + n + ": aggregates cannot be combined with other operators ('" + body.trim() + "')");
                return null;
            }
            Agg a = Agg.valueOf(agg.group(1).toUpperCase(Locale.ROOT));
            String inner = body.substring(open + 1, close).trim();
            if (inner.equals("*")) {
                if (a != Agg.COUNT) {
                    errors.add("item " + n + ": only COUNT accepts *");
                    return null;
                }
                return new Item(alias == null ? "count" : alias, null, a, body.trim());
            }
            Expr e = expr(inner, "item " + n, fields, errors);
            if (e == null) {
                return null;
            }
            String dflt = a.name().toLowerCase(Locale.ROOT) + (IDENT.matcher(inner).matches() ? "_" + inner : "_" + n);
            return new Item(alias == null ? dflt : alias, e, a, body.trim());
        }
        Expr e = expr(body, "item " + n, fields, errors);
        if (e == null) {
            return null;
        }
        String trimmed = body.trim();
        return new Item(alias != null ? alias : IDENT.matcher(trimmed).matches() ? trimmed : "expr_" + n, e, null, trimmed);
    }

    private static Expr expr(String text, String where, Map<String, String> fields, List<String> errors) {
        try {
            Expr e = Expr.parse(text);
            for (String id : e.identifiers()) {
                if (!fields.containsKey(id)) {
                    errors.add(where + ": unknown field " + id);
                }
            }
            return e;
        } catch (Expr.ExprException ex) {
            errors.add(where + ": " + ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ execute

    /**
     * Executes over the dataset rows. {@code extraFilter} (dashboard filters) applies before aggregation;
     * {@code maxRows} caps the output on top of the query's LIMIT.
     */
    public static Result execute(Query q, Map<String, String> fields, List<Map<String, Object>> rows,
                                 Map<String, Object> params, Predicate<Map<String, Object>> extraFilter, int maxRows) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if ((q.where() == null || q.where().test(r::get, params::get)) && (extraFilter == null || extraFilter.test(r))) {
                matched.add(r);
            }
        }
        List<String> columns = q.columns(fields);
        List<Map<String, Object>> out = new ArrayList<>();
        if (q.aggregated()) {
            Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> r : matched) {
                List<Object> key = new ArrayList<>();
                q.groupBy().forEach(g -> key.add(r.get(g)));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            if (groups.isEmpty() && q.groupBy().isEmpty()) {
                groups.put(List.of(), List.of());
            }
            for (var g : groups.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Item i : q.items()) {
                    if (i.agg() == null) {
                        row.put(i.alias(), g.getKey().get(q.groupBy().indexOf(i.expr().source().trim())));
                    } else {
                        row.put(i.alias(), aggregate(i, g.getValue(), params));
                    }
                }
                out.add(row);
            }
        } else {
            for (Map<String, Object> r : matched) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (q.selectAll()) {
                    fields.keySet().forEach(f -> row.put(f, r.get(f)));
                } else {
                    for (Item i : q.items()) {
                        row.put(i.alias(), i.expr().eval(r::get, params::get));
                    }
                }
                out.add(row);
            }
        }
        if (!q.orderBy().isEmpty()) {
            Comparator<Map<String, Object>> cmp = null;
            for (OrderBy o : q.orderBy()) {
                Comparator<Map<String, Object>> c = (a, b) -> compareNullsLast(a.get(o.column()), b.get(o.column()));
                if (o.desc()) {
                    Comparator<Map<String, Object>> asc = c;
                    c = (a, b) -> {
                        Object x = a.get(o.column());
                        Object y = b.get(o.column());
                        if (x == null || y == null) {
                            return asc.compare(a, b);
                        }
                        return -asc.compare(a, b);
                    };
                }
                cmp = cmp == null ? c : cmp.thenComparing(c);
            }
            out.sort(cmp);
        }
        int cap = Math.min(q.limit() == null ? MAX_LIMIT : q.limit(), maxRows);
        boolean truncated = out.size() > cap;
        return new Result(columns, truncated ? new ArrayList<>(out.subList(0, cap)) : out, matched.size(), truncated);
    }

    private static Object aggregate(Item i, List<Map<String, Object>> rows, Map<String, Object> params) {
        if (i.expr() == null) {
            return new BigDecimal(rows.size());
        }
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object v = i.expr().eval(r::get, params::get);
            if (v != null) {
                values.add(v);
            }
        }
        switch (i.agg()) {
            case COUNT -> {
                return new BigDecimal(values.size());
            }
            case COUNT_DISTINCT -> {
                Set<String> distinct = new HashSet<>();
                values.forEach(v -> distinct.add(v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v)));
                return new BigDecimal(distinct.size());
            }
            case MIN, MAX -> {
                Object best = null;
                for (Object v : values) {
                    if (best == null || (i.agg() == Agg.MIN ? Expr.compare(v, best) < 0 : Expr.compare(v, best) > 0)) {
                        best = v;
                    }
                }
                return best;
            }
            default -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (Object v : values) {
                    BigDecimal d = Expr.toDecimal(v);
                    if (d == null) {
                        throw new Expr.ExprException(i.agg() + " over a non-numeric value '" + v + "'");
                    }
                    sum = sum.add(d, MathContext.DECIMAL128);
                }
                if (i.agg() == Agg.AVG) {
                    return values.isEmpty() ? null : sum.divide(new BigDecimal(values.size()), MathContext.DECIMAL64);
                }
                return sum;
            }
        }
    }

    static int compareNullsLast(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Expr.compare(a, b);
    }

    // ------------------------------------------------------------------ text helpers

    /** Same-length copy with quoted text and parenthesised content blanked, so keywords and commas inside are ignored. */
    static String mask(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        char quote = 0;
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                sb.append(c == quote ? c : ' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(c);
            } else if (c == '(') {
                depth++;
                sb.append(depth == 1 ? '(' : ' ');
            } else if (c == ')') {
                sb.append(depth == 1 ? ')' : ' ');
                depth = Math.max(0, depth - 1);
            } else {
                sb.append(depth > 0 ? ' ' : c);
            }
        }
        return sb.toString();
    }

    private static String part(String s, int[] span) {
        return s.substring(span[0], span[1]);
    }

    private static List<int[]> splitTopLevel(String masked) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < masked.length(); i++) {
            if (masked.charAt(i) == ',') {
                out.add(new int[] {start, i});
                start = i + 1;
            }
        }
        out.add(new int[] {start, masked.length()});
        return out;
    }

    private static int matching(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
