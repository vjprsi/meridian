package io.meridian.regulatoryreporting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validate step of a report run. Rule types:
 * <ul>
 *   <li>REQUIRED - every listed cell has a value</li>
 *   <li>EQUALS - {@code left} and {@code right} expressions over cells are equal within {@code tolerance}</li>
 *   <li>BALANCE - sum of {@code debit} cells equals sum of {@code credit} cells within {@code tolerance}</li>
 *   <li>RANGE - numeric {@code cell} lies within [{@code min}, {@code max}]</li>
 *   <li>EXPRESSION - a condition over cells holds</li>
 * </ul>
 * Only failures produce findings; severity (INFO, WARNING, ERROR; default ERROR) decides whether a run is clean.
 */
public final class ReportValidator {
    static final Set<String> TYPES = Set.of("REQUIRED", "EQUALS", "BALANCE", "RANGE", "EXPRESSION");
    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "ERROR");

    private ReportValidator() {}

    public record Rule(String code, String type, String severity, List<String> cells, Expr left, Expr right,
                       BigDecimal tolerance, List<String> debit, List<String> credit, String cell, BigDecimal min,
                       BigDecimal max, Expr expression, String message) {}

    public record Finding(String ruleCode, String severity, String message, String cellRef, String expected,
                          String actual) {}

    /** Compiles rules against the known cell refs. */
    public static List<Rule> compile(List<Map<String, Object>> raw, Set<String> cellRefs) {
        List<Rule> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<DefinitionException.Problem> problems = new ArrayList<>();
        Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> r = raw.get(i);
            String at = "validation_rules[" + i + "]";
            String code = str(r.get("code"));
            if (code == null || code.isBlank() || code.length() > 64) {
                problems.add(new DefinitionException.Problem(at + ".code", "code is required (max 64)"));
                continue;
            }
            if (!codes.add(code)) {
                problems.add(new DefinitionException.Problem(at + ".code", "duplicate rule code " + code));
            }
            String type = upper(r.get("type"));
            if (type == null || !TYPES.contains(type)) {
                problems.add(new DefinitionException.Problem(at + ".type", "type must be one of " + TYPES));
                continue;
            }
            String severity = r.get("severity") == null ? "ERROR" : upper(r.get("severity"));
            if (!SEVERITIES.contains(severity)) {
                problems.add(new DefinitionException.Problem(at + ".severity", "severity must be INFO, WARNING or ERROR"));
            }
            BigDecimal tolerance = decimal(r.get("tolerance"), at + ".tolerance", problems);
            if (tolerance != null && tolerance.signum() < 0) {
                problems.add(new DefinitionException.Problem(at + ".tolerance", "tolerance cannot be negative"));
            }
            List<String> cells = list(r.get("cells"));
            List<String> debit = list(r.get("debit"));
            List<String> credit = list(r.get("credit"));
            String cell = str(r.get("cell"));
            Expr left = null;
            Expr right = null;
            Expr expression = null;
            switch (type) {
                case "REQUIRED" -> requireCells(cells, at + ".cells", cellRefs, problems);
                case "BALANCE" -> {
                    requireCells(debit, at + ".debit", cellRefs, problems);
                    requireCells(credit, at + ".credit", cellRefs, problems);
                }
                case "RANGE" -> {
                    requireCells(cell == null ? List.of() : List.of(cell), at + ".cell", cellRefs, problems);
                    if (r.get("min") == null && r.get("max") == null) {
                        problems.add(new DefinitionException.Problem(at, "RANGE needs min and/or max"));
                    }
                }
                case "EQUALS" -> {
                    left = cellExpr(r.get("left"), at + ".left", cellRefs, problems);
                    right = cellExpr(r.get("right"), at + ".right", cellRefs, problems);
                }
                default -> expression = cellExpr(r.get("expression"), at + ".expression", cellRefs, problems);
            }
            out.add(new Rule(code, type, severity, cells, left, right, tolerance == null ? BigDecimal.ZERO : tolerance,
                    debit, credit, cell, decimal(r.get("min"), at + ".min", problems),
                    decimal(r.get("max"), at + ".max", problems), expression, str(r.get("message"))));
        }
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return out;
    }

    /** Runs every rule over the cells (cell ref -> typed value: BigDecimal for numbers, String for text). */
    public static List<Finding> validate(List<Rule> rules, Map<String, Object> cells, Map<String, Object> params) {
        List<Finding> out = new ArrayList<>();
        for (Rule r : rules) {
            switch (r.type()) {
                case "REQUIRED" -> {
                    for (String c : r.cells()) {
                        Object v = cells.get(c);
                        if (v == null || String.valueOf(v).isBlank()) {
                            out.add(new Finding(r.code(), r.severity(), msg(r, "Required cell " + c + " has no value"),
                                    c, "a value", null));
                        }
                    }
                }
                case "EQUALS" -> {
                    BigDecimal l = number(r.left(), cells, params);
                    BigDecimal rv = number(r.right(), cells, params);
                    if (l == null || rv == null || l.subtract(rv).abs().compareTo(r.tolerance()) > 0) {
                        out.add(new Finding(r.code(), r.severity(), msg(r, r.left().source() + " must equal "
                                + r.right().source() + " (tolerance " + r.tolerance().toPlainString() + ")"),
                                firstRef(r.left()), plain(rv), plain(l)));
                    }
                }
                case "BALANCE" -> {
                    BigDecimal d = sum(r.debit(), cells);
                    BigDecimal c = sum(r.credit(), cells);
                    if (d.subtract(c).abs().compareTo(r.tolerance()) > 0) {
                        out.add(new Finding(r.code(), r.severity(), msg(r, "Debits " + r.debit() + " (" + d.toPlainString()
                                + ") do not balance credits " + r.credit() + " (" + c.toPlainString() + ")"),
                                r.debit().getFirst(), c.toPlainString(), d.toPlainString()));
                    }
                }
                case "RANGE" -> {
                    BigDecimal v = Expr.toDecimal(cells.get(r.cell()));
                    boolean bad = v == null || (r.min() != null && v.compareTo(r.min()) < 0)
                            || (r.max() != null && v.compareTo(r.max()) > 0);
                    if (bad) {
                        out.add(new Finding(r.code(), r.severity(), msg(r, "Cell " + r.cell() + " must be within ["
                                + plain(r.min()) + ", " + plain(r.max()) + "]"), r.cell(),
                                "[" + plain(r.min()) + ", " + plain(r.max()) + "]", plain(v)));
                    }
                }
                default -> {
                    boolean ok;
                    try {
                        ok = r.expression().test(cells::get, params::get);
                    } catch (Expr.ExprException e) {
                        ok = false;
                    }
                    if (!ok) {
                        out.add(new Finding(r.code(), r.severity(), msg(r, "Condition failed: " + r.expression().source()),
                                firstRef(r.expression()), "true", "false"));
                    }
                }
            }
        }
        return out;
    }

    private static BigDecimal number(Expr e, Map<String, Object> cells, Map<String, Object> params) {
        try {
            return Expr.toDecimal(e.eval(cells::get, params::get));
        } catch (Expr.ExprException ex) {
            return null;
        }
    }

    private static BigDecimal sum(List<String> refs, Map<String, Object> cells) {
        BigDecimal s = BigDecimal.ZERO;
        for (String r : refs) {
            BigDecimal v = Expr.toDecimal(cells.get(r));
            if (v != null) {
                s = s.add(v, MathContext.DECIMAL128);
            }
        }
        return s;
    }

    private static String firstRef(Expr e) {
        return e.identifiers().stream().findFirst().orElse(null);
    }

    private static String msg(Rule r, String generated) {
        String m = r.message() != null ? r.message() + " - " + generated : generated;
        return m.length() > 1000 ? m.substring(0, 1000) : m;
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }

    private static void requireCells(List<String> refs, String field, Set<String> known,
                                     List<DefinitionException.Problem> problems) {
        if (refs.isEmpty()) {
            problems.add(new DefinitionException.Problem(field, "at least one cell is required"));
        }
        for (String c : refs) {
            if (!known.contains(c)) {
                problems.add(new DefinitionException.Problem(field, "unknown cell " + c));
            }
        }
    }

    private static Expr cellExpr(Object text, String field, Set<String> known, List<DefinitionException.Problem> problems) {
        if (text == null) {
            problems.add(new DefinitionException.Problem(field, "expression is required"));
            return null;
        }
        try {
            Expr e = Expr.parse(String.valueOf(text));
            for (String id : e.identifiers()) {
                if (!known.contains(id)) {
                    problems.add(new DefinitionException.Problem(field, "unknown cell " + id));
                }
            }
            return e;
        } catch (Expr.ExprException ex) {
            problems.add(new DefinitionException.Problem(field, ex.getMessage()));
            return null;
        }
    }

    private static BigDecimal decimal(Object o, String field, List<DefinitionException.Problem> problems) {
        if (o == null) {
            return null;
        }
        BigDecimal d = Expr.toDecimal(o);
        if (d == null) {
            problems.add(new DefinitionException.Problem(field, "must be a number"));
        }
        return d;
    }

    private static List<String> list(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String upper(Object o) {
        return o == null ? null : String.valueOf(o).toUpperCase(Locale.ROOT);
    }
}
