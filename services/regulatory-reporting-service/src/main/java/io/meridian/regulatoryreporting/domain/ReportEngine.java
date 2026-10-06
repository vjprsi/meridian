package io.meridian.regulatoryreporting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Transform step of a report run: turns collected source rows into report cells according to the version's field
 * mappings. A mapping either aggregates rows of one source ({@code aggregate} over the {@code value} row expression,
 * rows selected by the {@code filter} condition) or derives a value from other cells ({@code expression}). Derived
 * cells are evaluated in dependency order; cycles and unknown references are rejected when the version is created.
 * Every aggregate cell keeps its lineage (the contributing source records) for drilldown.
 */
public final class ReportEngine {
    public static final int MAX_LINEAGE = 500;
    static final Pattern CELL_REF = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\-]{0,127}");
    static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "AVG", "MIN", "MAX", "FIRST");
    static final Set<String> TYPES = Set.of("NUMBER", "TEXT");
    private static final MathContext MC = MathContext.DECIMAL128;

    private ReportEngine() {}

    /** One data source after collection. */
    public record Source(String name, String idField, List<Map<String, Object>> rows) {}

    /** One compiled field mapping. */
    public record Mapping(String cellRef, String label, String type, String source, String aggregate, Expr value,
                          Expr filter, Expr expression, int scale, Map<String, Object> raw) {
        public boolean derived() {
            return expression != null;
        }
    }

    /** One computed cell. {@code value} is the canonical string form (plain decimal for numbers). */
    public record Cell(String cellRef, String label, String type, String value, String sourceName, int sourceCount,
                       List<Map<String, Object>> sourceRecords) {
        public Object typedValue() {
            if (value == null) {
                return null;
            }
            return "NUMBER".equals(type) ? new BigDecimal(value) : value;
        }
    }

    // ------------------------------------------------------------------ compile / validate

    /** Validates data source declarations; returns the source names. */
    public static Set<String> checkSources(List<Map<String, Object>> sources, Set<String> allowedServices) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        if (sources == null || sources.isEmpty()) {
            problems.add(new DefinitionException.Problem("data_sources", "at least one data source is required"));
        } else {
            for (int i = 0; i < sources.size(); i++) {
                Map<String, Object> s = sources.get(i);
                String at = "data_sources[" + i + "]";
                String name = str(s.get("name"));
                if (name == null || !CELL_REF.matcher(name).matches()) {
                    problems.add(new DefinitionException.Problem(at + ".name", "name is required (letters, digits, _ . -)"));
                } else if (!names.add(name)) {
                    problems.add(new DefinitionException.Problem(at + ".name", "duplicate data source " + name));
                }
                String type = str(s.getOrDefault("type", "SERVICE")).toUpperCase(Locale.ROOT);
                switch (type) {
                    case "SERVICE" -> {
                        String service = str(s.get("service"));
                        String path = str(s.get("path"));
                        if (service == null || !allowedServices.contains(service)) {
                            problems.add(new DefinitionException.Problem(at + ".service",
                                    "service must be one of " + new java.util.TreeSet<>(allowedServices)));
                        }
                        if (path == null || !PathTemplate.isSafe(path)) {
                            problems.add(new DefinitionException.Problem(at + ".path",
                                    "path must be a relative API path starting with /v1/ (placeholders {as_of_date}, "
                                            + "{period_start}, {period_end}, {legal_entity_id}, {param.x})"));
                        } else {
                            try {
                                PathTemplate.check(path);
                            } catch (IllegalArgumentException e) {
                                problems.add(new DefinitionException.Problem(at + ".path", e.getMessage()));
                            }
                        }
                        String pointer = str(s.get("rows_pointer"));
                        if (pointer != null && !pointer.isEmpty() && !pointer.startsWith("/")) {
                            problems.add(new DefinitionException.Problem(at + ".rows_pointer", "rows_pointer must be a JSON pointer, e.g. /data"));
                        }
                    }
                    case "INLINE" -> {
                        if (!(s.get("rows") instanceof List<?> rows) || rows.stream().anyMatch(r -> !(r instanceof Map))) {
                            problems.add(new DefinitionException.Problem(at + ".rows", "INLINE sources need rows (a list of objects)"));
                        }
                    }
                    default -> problems.add(new DefinitionException.Problem(at + ".type", "type must be SERVICE or INLINE"));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return names;
    }

    /** Compiles field mappings: unique cell refs, known sources, valid expressions, acyclic derived cells. */
    public static List<Mapping> compile(List<Map<String, Object>> raw, Set<String> sourceNames) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        List<Mapping> out = new ArrayList<>();
        Set<String> refs = new HashSet<>();
        if (raw == null || raw.isEmpty()) {
            throw new DefinitionException(List.of(new DefinitionException.Problem("field_mappings", "at least one field mapping is required")));
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> m = raw.get(i);
            String at = "field_mappings[" + i + "]";
            String ref = str(m.get("cell_ref"));
            if (ref == null || !CELL_REF.matcher(ref).matches()) {
                problems.add(new DefinitionException.Problem(at + ".cell_ref", "cell_ref is required (letter first; letters, digits, _ . -; max 128)"));
                continue;
            }
            if (!refs.add(ref)) {
                problems.add(new DefinitionException.Problem(at + ".cell_ref", "duplicate cell_ref " + ref));
            }
            String type = str(m.getOrDefault("type", "NUMBER")).toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                problems.add(new DefinitionException.Problem(at + ".type", "type must be NUMBER or TEXT"));
            }
            int scale = 2;
            if (m.get("scale") != null) {
                BigDecimal s = Expr.toDecimal(m.get("scale"));
                if (s == null || s.scale() > 0 || s.intValue() < 0 || s.intValue() > 10) {
                    problems.add(new DefinitionException.Problem(at + ".scale", "scale must be an integer 0-10"));
                } else {
                    scale = s.intValue();
                }
            }
            Expr expression = expr(m.get("expression"), at + ".expression", problems);
            Expr value = expr(m.get("value") != null ? m.get("value") : m.get("field"), at + ".value", problems);
            Expr filter = expr(m.get("filter"), at + ".filter", problems);
            String source = str(m.get("source"));
            String aggregate = null;
            if (expression == null) {
                if (source == null || !sourceNames.contains(source)) {
                    problems.add(new DefinitionException.Problem(at + ".source",
                            "source must name a data source " + sourceNames + " (or give an expression over other cells)"));
                }
                aggregate = str(m.getOrDefault("aggregate", value == null ? "COUNT" : "SUM")).toUpperCase(Locale.ROOT);
                if (!AGGREGATES.contains(aggregate)) {
                    problems.add(new DefinitionException.Problem(at + ".aggregate", "aggregate must be one of " + AGGREGATES));
                } else if (!"COUNT".equals(aggregate) && value == null) {
                    problems.add(new DefinitionException.Problem(at + ".value", aggregate + " needs a value expression"));
                }
            } else if (source != null || value != null || filter != null) {
                problems.add(new DefinitionException.Problem(at, "a derived cell (expression) cannot also declare source, value or filter"));
            }
            out.add(new Mapping(ref, str(m.get("label")), type, source, aggregate, value, filter, expression, scale, m));
        }
        if (problems.isEmpty()) {
            order(out, problems);
        }
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return out;
    }

    /** Derived cells in dependency order (Kahn); reports unknown references and cycles. */
    static List<Mapping> order(List<Mapping> mappings, List<DefinitionException.Problem> problems) {
        Map<String, Mapping> byRef = new LinkedHashMap<>();
        mappings.forEach(m -> byRef.put(m.cellRef(), m));
        Map<String, Set<String>> deps = new HashMap<>();
        for (Mapping m : mappings) {
            if (!m.derived()) {
                continue;
            }
            Set<String> d = new LinkedHashSet<>();
            for (String id : m.expression().identifiers()) {
                if (id.startsWith("param_") || id.startsWith("param.")) {
                    continue;
                }
                if (!byRef.containsKey(id)) {
                    problems.add(new DefinitionException.Problem("field_mappings." + m.cellRef() + ".expression",
                            "unknown cell reference " + id));
                } else if (byRef.get(id).derived()) {
                    d.add(id);
                }
            }
            deps.put(m.cellRef(), d);
        }
        List<Mapping> ordered = new ArrayList<>();
        mappings.stream().filter(m -> !m.derived()).forEach(ordered::add);
        Deque<String> ready = new ArrayDeque<>();
        Map<String, Integer> pending = new HashMap<>();
        deps.forEach((k, v) -> {
            pending.put(k, v.size());
            if (v.isEmpty()) {
                ready.add(k);
            }
        });
        while (!ready.isEmpty()) {
            String r = ready.poll();
            ordered.add(byRef.get(r));
            deps.forEach((k, v) -> {
                if (v.contains(r) && pending.merge(k, -1, Integer::sum) == 0) {
                    ready.add(k);
                }
            });
        }
        if (ordered.size() < mappings.size() && problems.isEmpty()) {
            List<String> cyclic = deps.keySet().stream().filter(k -> pending.get(k) > 0).sorted().toList();
            problems.add(new DefinitionException.Problem("field_mappings", "circular cell references: " + cyclic));
        }
        return ordered;
    }

    // ------------------------------------------------------------------ compute

    /** Computes all cells. Throws {@link Expr.ExprException} (wrapped with the cell) when an expression fails. */
    public static List<Cell> compute(List<Mapping> mappings, Map<String, Source> sources, Map<String, Object> params) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        List<Mapping> ordered = order(mappings, problems);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (Mapping m : ordered) {
            try {
                cells.put(m.cellRef(), m.derived() ? derive(m, cells, params) : aggregate(m, sources.get(m.source()), params));
            } catch (Expr.ExprException e) {
                throw new Expr.ExprException("cell " + m.cellRef() + ": " + e.getMessage());
            }
        }
        List<Cell> out = new ArrayList<>();
        mappings.forEach(m -> out.add(cells.get(m.cellRef())));
        return out;
    }

    /**
     * Re-evaluates derived cells over the current (possibly adjusted) cell values, in dependency order. Cells in
     * {@code pinned} (themselves adjusted) keep their value. Lineage of each cell is preserved.
     */
    public static List<Cell> rederive(List<Mapping> mappings, List<Cell> current, Set<String> pinned,
                                      Map<String, Object> params) {
        List<DefinitionException.Problem> problems = new ArrayList<>();
        List<Mapping> ordered = order(mappings, problems);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        Map<String, Cell> cells = new LinkedHashMap<>();
        current.forEach(c -> cells.put(c.cellRef(), c));
        for (Mapping m : ordered) {
            Cell old = cells.get(m.cellRef());
            if (!m.derived() || old == null || pinned.contains(m.cellRef())) {
                continue;
            }
            Cell d = derive(m, cells, params);
            cells.put(m.cellRef(), new Cell(old.cellRef(), old.label(), old.type(), d.value(), old.sourceName(),
                    old.sourceCount(), old.sourceRecords()));
        }
        List<Cell> out = new ArrayList<>();
        current.forEach(c -> out.add(cells.get(c.cellRef())));
        return out;
    }

    private static Cell derive(Mapping m, Map<String, Cell> cells, Map<String, Object> params) {
        Object v = m.expression().eval(id -> {
            if (id.startsWith("param_")) {
                return params.get(id.substring(6));
            }
            if (id.startsWith("param.")) {
                return params.get(id.substring(6));
            }
            Cell c = cells.get(id);
            return c == null ? null : c.typedValue();
        }, params::get);
        return new Cell(m.cellRef(), m.label(), m.type(), format(m, v), null, 0, List.of());
    }

    private static Cell aggregate(Mapping m, Source src, Map<String, Object> params) {
        List<Map<String, Object>> rows = src == null ? List.of() : src.rows();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal best = null;
        Object first = null;
        int count = 0;
        int numeric = 0;
        List<Map<String, Object>> lineage = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (m.filter() != null && !m.filter().test(id -> rowValue(row, id, params), params::get)) {
                continue;
            }
            Object v = m.value() == null ? null : m.value().eval(id -> rowValue(row, id, params), params::get);
            if (!"COUNT".equals(m.aggregate()) && v == null) {
                continue;
            }
            count++;
            if (first == null) {
                first = v;
            }
            BigDecimal d = Expr.toDecimal(v);
            if (d != null) {
                numeric++;
                sum = sum.add(d, MC);
                if (best == null || ("MIN".equals(m.aggregate()) ? d.compareTo(best) < 0 : d.compareTo(best) > 0)) {
                    best = d;
                }
            } else if (Set.of("SUM", "AVG", "MIN", "MAX").contains(m.aggregate())) {
                throw new Expr.ExprException(m.aggregate() + " over a non-numeric value '" + v + "' (row " + i + ")");
            }
            if (lineage.size() < MAX_LINEAGE) {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("source", src.name());
                rec.put("row", i);
                Object id = src.idField() != null ? lookup(row, src.idField()) : row.containsKey("id") ? row.get("id") : null;
                if (id != null) {
                    rec.put("id", id);
                }
                rec.put("value", v instanceof BigDecimal b ? b.toPlainString() : v);
                if (row.size() <= 20) {
                    rec.put("record", row);
                }
                lineage.add(rec);
            }
        }
        Object result = switch (m.aggregate()) {
            case "COUNT" -> new BigDecimal(count);
            case "SUM" -> sum;
            case "AVG" -> numeric == 0 ? null : sum.divide(new BigDecimal(numeric), MC);
            case "MIN", "MAX" -> best;
            default -> first;
        };
        return new Cell(m.cellRef(), m.label(), m.type(), format(m, result), src == null ? null : src.name(), count,
                lineage);
    }

    static Object rowValue(Map<String, Object> row, String id, Map<String, Object> params) {
        if (id.startsWith("param_") && !row.containsKey(id)) {
            return params.get(id.substring(6));
        }
        if (id.startsWith("param.")) {
            return params.get(id.substring(6));
        }
        return lookup(row, id);
    }

    /** Dotted-path lookup into nested maps. */
    @SuppressWarnings("unchecked")
    public static Object lookup(Map<String, Object> row, String path) {
        if (row.containsKey(path)) {
            return row.get(path);
        }
        Object cur = row;
        for (String part : path.split("\\.")) {
            if (cur instanceof Map<?, ?> map) {
                cur = ((Map<String, Object>) map).get(part);
            } else {
                return null;
            }
        }
        return cur;
    }

    private static String format(Mapping m, Object v) {
        if (v == null) {
            return null;
        }
        if ("NUMBER".equals(m.type())) {
            BigDecimal d = Expr.toDecimal(v);
            if (d == null) {
                throw new Expr.ExprException("value '" + v + "' is not a number");
            }
            int scale = "COUNT".equals(m.aggregate()) ? 0 : m.scale();
            return d.setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
        }
        String s = v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static Expr expr(Object text, String field, List<DefinitionException.Problem> problems) {
        if (text == null) {
            return null;
        }
        try {
            return Expr.parse(String.valueOf(text));
        } catch (Expr.ExprException e) {
            problems.add(new DefinitionException.Problem(field, e.getMessage()));
            return null;
        }
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
