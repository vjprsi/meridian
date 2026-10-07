package io.meridian.regulatoryreporting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Row shaping applied while a run collects its sources, declared on the data source:
 * <ul>
 *   <li>{@code gl_mapping} - maps each trial-balance row to a report line with a GL mapping table: the first rule
 *       whose matcher fits the row's account code wins ({@code code} exact, {@code prefix}, or {@code from}..{@code to}
 *       inclusive; optionally restricted to an {@code account_class}); the row gets {@code gl_map.line},
 *       {@code gl_map.sign} (rule sign, else +1 for ASSET / EXPENSE / CONTINGENT and -1 for LIABILITY / EQUITY /
 *       INCOME, so credit balances report as positive amounts) and the rule's {@code attributes} as
 *       {@code gl_map.<name>} (e.g. {@code risk_weight}); unmatched rows get {@code gl_map.line} NULL;</li>
 *   <li>{@code COMBINE} sources - group rows of earlier sources by a key expression ({@code from[].key}), summing
 *       {@code sum} expressions and keeping the first non-null {@code first} expressions per key, so exposures can be
 *       aggregated per counterparty across loans, collateral and treasury deals.</li>
 * </ul>
 * Everything is interpreted with {@link Expr}; nothing is executed as code.
 */
public final class SourceTransforms {
    public static final String GL_MAP = "gl_map";
    public static final int MAX_RULES = 2000;
    public static final int MAX_MEMBERS = 20;
    private static final MathContext MC = MathContext.DECIMAL128;
    static final Set<String> CLASSES = Set.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE", "CONTINGENT");

    private SourceTransforms() {}

    // ------------------------------------------------------------------ GL mapping

    /** One rule of a GL mapping table. */
    public record GlRule(String line, String code, String prefix, String from, String to, String accountClass,
                         BigDecimal sign, Map<String, Object> attributes, String origin) {
        boolean matches(String account, String cls) {
            if (accountClass != null && !accountClass.equalsIgnoreCase(cls == null ? "" : cls)) {
                return false;
            }
            if (account == null) {
                return code == null && prefix == null && from == null && accountClass != null;
            }
            if (code != null) {
                return code.equals(account);
            }
            if (prefix != null) {
                return account.startsWith(prefix);
            }
            if (from != null) {
                return account.compareTo(from) >= 0 && account.compareTo(to) <= 0;
            }
            return accountClass != null;
        }
    }

    /** A parsed GL mapping declaration of a data source. */
    public record GlMapping(String table, String accountField, String classField, List<GlRule> rules) {
        /** The same mapping with tenant rules ahead of the pack rules (or instead of them when {@code replace}). */
        public GlMapping withOverride(List<GlRule> tenantRules, boolean replace) {
            List<GlRule> all = new ArrayList<>(tenantRules);
            if (!replace) {
                all.addAll(rules);
            }
            return new GlMapping(table, accountField, classField, List.copyOf(all));
        }
    }

    /** Parses (and validates) a {@code gl_mapping} declaration; problems are reported under {@code at}. */
    public static GlMapping glMapping(Object raw, String at, List<DefinitionException.Problem> problems) {
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(new DefinitionException.Problem(at, "gl_mapping must be an object {table, account_field, rules}"));
            return null;
        }
        String table = str(m.get("table"));
        if (table == null || !table.matches("[A-Z][A-Z0-9_]{1,63}")) {
            problems.add(new DefinitionException.Problem(at + ".table", "table is required (upper-case code, e.g. KE_CBK_GL)"));
        }
        List<GlRule> rules = glRules(m.get("rules"), at + ".rules", "pack", problems);
        String accountField = str(m.get("account_field"));
        String classField = str(m.get("class_field"));
        return new GlMapping(table, accountField == null ? "gl_account_code" : accountField,
                classField == null ? "account_class" : classField, rules);
    }

    /** Parses mapping rules (pack tables and tenant overrides from config-service). */
    public static List<GlRule> glRules(Object raw, String at, String origin, List<DefinitionException.Problem> problems) {
        List<GlRule> out = new ArrayList<>();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            problems.add(new DefinitionException.Problem(at, "rules must be a non-empty list"));
            return out;
        }
        if (list.size() > MAX_RULES) {
            problems.add(new DefinitionException.Problem(at, "at most " + MAX_RULES + " rules"));
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            String ra = at + "[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> r)) {
                problems.add(new DefinitionException.Problem(ra, "rule must be an object"));
                continue;
            }
            String line = str(r.get("line"));
            if (line == null || !ReportEngine.CELL_REF.matcher(line).matches()) {
                problems.add(new DefinitionException.Problem(ra + ".line", "line is required (letters, digits, _ . -)"));
            }
            String code = str(r.get("code"));
            String prefix = str(r.get("prefix"));
            String from = str(r.get("from"));
            String to = str(r.get("to"));
            String cls = r.get("account_class") == null ? null : str(r.get("account_class")).toUpperCase(Locale.ROOT);
            int matchers = (code != null ? 1 : 0) + (prefix != null ? 1 : 0) + (from != null || to != null ? 1 : 0);
            if (matchers > 1) {
                problems.add(new DefinitionException.Problem(ra, "use one of code, prefix or from/to"));
            }
            if (matchers == 0 && cls == null) {
                problems.add(new DefinitionException.Problem(ra, "a rule needs code, prefix, from/to or account_class"));
            }
            if ((from == null) != (to == null) || (from != null && from.compareTo(to) > 0)) {
                problems.add(new DefinitionException.Problem(ra, "from and to go together, from <= to"));
            }
            if (cls != null && !CLASSES.contains(cls)) {
                problems.add(new DefinitionException.Problem(ra + ".account_class", "account_class must be one of " + CLASSES));
            }
            BigDecimal sign = null;
            if (r.get("sign") != null) {
                sign = Expr.toDecimal(r.get("sign"));
                if (sign == null || sign.abs().compareTo(BigDecimal.ONE) != 0) {
                    problems.add(new DefinitionException.Problem(ra + ".sign", "sign must be 1 or -1"));
                    sign = null;
                }
            }
            Map<String, Object> attributes = new TreeMap<>();
            if (r.get("attributes") instanceof Map<?, ?> a) {
                a.forEach((k, v) -> {
                    String name = String.valueOf(k);
                    if (!name.matches("[a-z][a-z0-9_]{0,39}") || Set.of("line", "sign", "table", "origin").contains(name)) {
                        problems.add(new DefinitionException.Problem(ra + ".attributes", "invalid attribute name " + name));
                    } else if (v != null && !(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                        problems.add(new DefinitionException.Problem(ra + ".attributes." + name, "attribute values are scalars"));
                    } else {
                        attributes.put(name, v instanceof Number n ? new BigDecimal(n.toString()) : v);
                    }
                });
            } else if (r.get("attributes") != null) {
                problems.add(new DefinitionException.Problem(ra + ".attributes", "attributes must be an object"));
            }
            out.add(new GlRule(line, code, prefix, from, to, cls, sign, Map.copyOf(attributes), origin));
        }
        return out;
    }

    /** Adds {@code gl_map} to every row (copies; the input rows are left unchanged). */
    public static List<Map<String, Object>> applyGlMapping(GlMapping mapping, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object acc = ReportEngine.lookup(row, mapping.accountField());
            Object cls = ReportEngine.lookup(row, mapping.classField());
            String account = acc == null ? null : String.valueOf(acc).trim();
            String accountClass = cls == null ? null : String.valueOf(cls).trim().toUpperCase(Locale.ROOT);
            GlRule hit = null;
            for (GlRule r : mapping.rules()) {
                if (r.matches(account, accountClass)) {
                    hit = r;
                    break;
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("table", mapping.table());
            map.put("line", hit == null ? null : hit.line());
            map.put("sign", hit != null && hit.sign() != null ? hit.sign() : defaultSign(accountClass));
            map.put("origin", hit == null ? null : hit.origin());
            if (hit != null) {
                map.putAll(hit.attributes());
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(GL_MAP, map);
            out.add(copy);
        }
        return out;
    }

    static BigDecimal defaultSign(String accountClass) {
        return accountClass != null && Set.of("LIABILITY", "EQUITY", "INCOME").contains(accountClass)
                ? BigDecimal.ONE.negate() : BigDecimal.ONE;
    }

    // ------------------------------------------------------------------ COMBINE

    /** One input of a COMBINE source. */
    public record Part(String source, Expr key, Expr filter, Map<String, Expr> sum, Map<String, Expr> first) {}

    /** A parsed COMBINE declaration. */
    public record Combine(String keyField, List<Part> parts) {
        /** Output fields besides the key and member bookkeeping. */
        public Set<String> fields() {
            Set<String> f = new java.util.LinkedHashSet<>();
            parts.forEach(p -> {
                f.addAll(p.sum().keySet());
                f.addAll(p.first().keySet());
            });
            return f;
        }
    }

    /** Parses a COMBINE declaration; {@code earlier} are the sources declared before it. */
    public static Combine combine(Map<String, Object> s, Set<String> earlier, String at,
                                  List<DefinitionException.Problem> problems) {
        String keyField = s.get("key_field") == null ? "key" : str(s.get("key_field"));
        if (!keyField.matches("[a-z][a-z0-9_]{0,39}")) {
            problems.add(new DefinitionException.Problem(at + ".key_field", "key_field must be a snake_case name"));
        }
        List<Part> parts = new ArrayList<>();
        if (!(s.get("from") instanceof List<?> from) || from.isEmpty()) {
            problems.add(new DefinitionException.Problem(at + ".from", "COMBINE needs from: a list of {source, key, sum, first}"));
            return new Combine(keyField, parts);
        }
        for (int i = 0; i < from.size(); i++) {
            String pa = at + ".from[" + i + "]";
            if (!(from.get(i) instanceof Map<?, ?> p)) {
                problems.add(new DefinitionException.Problem(pa, "must be an object"));
                continue;
            }
            String source = str(p.get("source"));
            if (source == null || !earlier.contains(source)) {
                problems.add(new DefinitionException.Problem(pa + ".source", "source must name a data source declared before " + earlier));
            }
            Expr key = expr(p.get("key"), pa + ".key", problems, true);
            Expr filter = expr(p.get("filter"), pa + ".filter", problems, false);
            Map<String, Expr> sum = exprs(p.get("sum"), pa + ".sum", problems);
            Map<String, Expr> first = exprs(p.get("first"), pa + ".first", problems);
            if (sum.isEmpty() && first.isEmpty()) {
                problems.add(new DefinitionException.Problem(pa, "declare sum and/or first fields"));
            }
            for (String f : sum.keySet()) {
                if (f.equals(keyField) || "member_count".equals(f) || "members".equals(f)) {
                    problems.add(new DefinitionException.Problem(pa + ".sum." + f, "reserved field name"));
                }
            }
            parts.add(new Part(source, key, filter, sum, first));
        }
        return new Combine(keyField, parts);
    }

    /**
     * Groups the rows of the parts' sources by key. Rows whose key is null are skipped. Every sum field starts at
     * zero for every key, so a counterparty with loans but no collateral still has {@code collateral = 0}.
     */
    public static List<Map<String, Object>> applyCombine(Combine c, Map<String, ReportEngine.Source> sources,
                                                         Map<String, Object> params) {
        Set<String> sumFields = new java.util.LinkedHashSet<>();
        c.parts().forEach(p -> sumFields.addAll(p.sum().keySet()));
        Map<String, Map<String, Object>> groups = new TreeMap<>();
        for (Part p : c.parts()) {
            ReportEngine.Source src = sources.get(p.source());
            if (src == null) {
                continue;
            }
            List<Map<String, Object>> rows = src.rows();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                if (p.filter() != null && !p.filter().test(id -> ReportEngine.rowValue(row, id, params), params::get)) {
                    continue;
                }
                Object k = p.key().eval(id -> ReportEngine.rowValue(row, id, params), params::get);
                if (k == null || String.valueOf(k).isBlank()) {
                    continue;
                }
                String key = String.valueOf(k);
                Map<String, Object> g = groups.computeIfAbsent(key, x -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put(c.keyField(), x);
                    sumFields.forEach(f -> n.put(f, BigDecimal.ZERO));
                    n.put("member_count", BigDecimal.ZERO);
                    n.put("members", new ArrayList<Map<String, Object>>());
                    return n;
                });
                for (Map.Entry<String, Expr> e : p.sum().entrySet()) {
                    Object v = e.getValue().eval(id -> ReportEngine.rowValue(row, id, params), params::get);
                    if (v == null) {
                        continue;
                    }
                    BigDecimal d = Expr.toDecimal(v);
                    if (d == null) {
                        throw new Expr.ExprException("COMBINE sum " + e.getKey() + " over a non-numeric value '" + v
                                + "' (" + p.source() + " row " + i + ")");
                    }
                    g.put(e.getKey(), ((BigDecimal) g.get(e.getKey())).add(d, MC));
                }
                for (Map.Entry<String, Expr> e : p.first().entrySet()) {
                    if (g.get(e.getKey()) == null) {
                        Object v = e.getValue().eval(id -> ReportEngine.rowValue(row, id, params), params::get);
                        g.put(e.getKey(), v instanceof BigDecimal b ? b : v);
                    }
                }
                g.put("member_count", ((BigDecimal) g.get("member_count")).add(BigDecimal.ONE));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> members = (List<Map<String, Object>>) g.get("members");
                if (members.size() < MAX_MEMBERS) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", p.source());
                    Object id = src.idField() != null ? ReportEngine.lookup(row, src.idField()) : row.get("id");
                    m.put("id", id == null ? "row " + i : id);
                    members.add(m);
                }
            }
        }
        return new ArrayList<>(groups.values());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Expr> exprs(Object raw, String at, List<DefinitionException.Problem> problems) {
        Map<String, Expr> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add(new DefinitionException.Problem(at, "must be an object {field: expression}"));
            return out;
        }
        m.forEach((k, v) -> {
            String f = String.valueOf(k);
            if (!f.matches("[a-z][a-z0-9_]{0,39}")) {
                problems.add(new DefinitionException.Problem(at + "." + f, "field names are snake_case"));
                return;
            }
            Expr e = expr(v, at + "." + f, problems, true);
            if (e != null) {
                out.put(f, e);
            }
        });
        return out;
    }

    private static Expr expr(Object text, String at, List<DefinitionException.Problem> problems, boolean required) {
        if (text == null) {
            if (required) {
                problems.add(new DefinitionException.Problem(at, "expression is required"));
            }
            return null;
        }
        try {
            return Expr.parse(String.valueOf(text));
        } catch (Expr.ExprException e) {
            problems.add(new DefinitionException.Problem(at, e.getMessage()));
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
