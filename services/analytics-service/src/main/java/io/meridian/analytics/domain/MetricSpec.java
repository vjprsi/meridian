package io.meridian.analytics.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL-free KPI metric specification:
 * <pre>
 * {"aggregate": "COUNT|SUM|AVG|MIN|MAX|COUNT_DISTINCT", "value": "&lt;row expression&gt;", "filter": "&lt;row condition&gt;",
 *  "scale": 2}
 * {"aggregate": "RATIO", "numerator": {...metric...}, "denominator": {...metric...}, "multiplier": 100, "scale": 2}
 * </pre>
 * evaluated over dataset rows (or rows provided by the caller); expressions may reference only the dataset's fields.
 */
public final class MetricSpec {
    static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "COUNT_DISTINCT", "RATIO");
    static final Set<String> GRANULARITIES = Set.of("DAY", "WEEK", "MONTH");

    private final String aggregate;
    private final Expr value;
    private final Expr filter;
    private final MetricSpec numerator;
    private final MetricSpec denominator;
    private final BigDecimal multiplier;
    private final int scale;

    private MetricSpec(String aggregate, Expr value, Expr filter, MetricSpec numerator, MetricSpec denominator,
                       BigDecimal multiplier, int scale) {
        this.aggregate = aggregate;
        this.value = value;
        this.filter = filter;
        this.numerator = numerator;
        this.denominator = denominator;
        this.multiplier = multiplier;
        this.scale = scale;
    }

    /** Compiles a spec; {@code fields} limits identifiers (null = any). Throws QueryException listing problems. */
    public static MetricSpec compile(Map<String, Object> spec, Set<String> fields) {
        List<String> errors = new ArrayList<>();
        MetricSpec m = compile(spec, fields, "metric", errors, 0);
        if (!errors.isEmpty()) {
            throw new QueryDsl.QueryException(errors);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static MetricSpec compile(Map<String, Object> spec, Set<String> fields, String at, List<String> errors, int depth) {
        if (spec == null) {
            errors.add(at + " is required");
            return null;
        }
        if (depth > 3) {
            errors.add(at + ": ratios are nested too deeply");
            return null;
        }
        String agg = String.valueOf(spec.getOrDefault("aggregate", "")).toUpperCase(Locale.ROOT);
        if (!AGGREGATES.contains(agg)) {
            errors.add(at + ".aggregate must be one of " + AGGREGATES);
            return null;
        }
        int scale = 4;
        if (spec.get("scale") != null) {
            BigDecimal s = Expr.toDecimal(spec.get("scale"));
            if (s == null || s.intValue() < 0 || s.intValue() > 10) {
                errors.add(at + ".scale must be 0-10");
            } else {
                scale = s.intValue();
            }
        }
        BigDecimal multiplier = spec.get("multiplier") == null ? BigDecimal.ONE : Expr.toDecimal(spec.get("multiplier"));
        if (multiplier == null) {
            errors.add(at + ".multiplier must be a number");
            multiplier = BigDecimal.ONE;
        }
        Expr filter = expr(spec.get("filter"), at + ".filter", fields, errors);
        if ("RATIO".equals(agg)) {
            MetricSpec num = spec.get("numerator") instanceof Map<?, ?> n
                    ? compile((Map<String, Object>) n, fields, at + ".numerator", errors, depth + 1) : null;
            MetricSpec den = spec.get("denominator") instanceof Map<?, ?> d
                    ? compile((Map<String, Object>) d, fields, at + ".denominator", errors, depth + 1) : null;
            if (num == null || den == null) {
                errors.add(at + ": RATIO needs numerator and denominator metric specs");
            }
            return new MetricSpec(agg, null, filter, num, den, multiplier, scale);
        }
        Expr value = expr(spec.get("value"), at + ".value", fields, errors);
        if (value == null && !"COUNT".equals(agg) && spec.get("value") == null) {
            errors.add(at + ": " + agg + " needs a value expression");
        }
        return new MetricSpec(agg, value, filter, null, null, multiplier, scale);
    }

    private static Expr expr(Object text, String at, Set<String> fields, List<String> errors) {
        if (text == null) {
            return null;
        }
        try {
            Expr e = Expr.parse(String.valueOf(text));
            if (fields != null) {
                for (String id : e.identifiers()) {
                    if (!fields.contains(id)) {
                        errors.add(at + ": unknown field " + id);
                    }
                }
            }
            return e;
        } catch (Expr.ExprException ex) {
            errors.add(at + ": " + ex.getMessage());
            return null;
        }
    }

    /** Evaluates over rows; null when undefined (e.g. AVG of nothing, ratio with zero denominator). */
    public BigDecimal evaluate(List<Map<String, Object>> rows, Map<String, Object> params) {
        List<Map<String, Object>> in = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (filter == null || filter.test(r::get, params::get)) {
                in.add(r);
            }
        }
        BigDecimal result;
        if ("RATIO".equals(aggregate)) {
            BigDecimal n = numerator.evaluate(in, params);
            BigDecimal d = denominator.evaluate(in, params);
            result = n == null || d == null || d.signum() == 0 ? null : n.divide(d, MathContext.DECIMAL128).multiply(multiplier);
        } else {
            List<Object> values = new ArrayList<>();
            for (Map<String, Object> r : in) {
                Object v = value == null ? Boolean.TRUE : value.eval(r::get, params::get);
                if (v != null) {
                    values.add(v);
                }
            }
            result = switch (aggregate) {
                case "COUNT" -> new BigDecimal(values.size());
                case "COUNT_DISTINCT" -> {
                    Set<String> d = new HashSet<>();
                    values.forEach(v -> d.add(v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v)));
                    yield new BigDecimal(d.size());
                }
                default -> numeric(values);
            };
            if (result != null) {
                result = result.multiply(multiplier);
            }
        }
        return result == null ? null : result.setScale(scale, RoundingMode.HALF_EVEN);
    }

    private BigDecimal numeric(List<Object> values) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal best = null;
        for (Object v : values) {
            BigDecimal d = Expr.toDecimal(v);
            if (d == null) {
                throw new Expr.ExprException(aggregate + " over a non-numeric value '" + v + "'");
            }
            sum = sum.add(d, MathContext.DECIMAL128);
            if (best == null || ("MIN".equals(aggregate) ? d.compareTo(best) < 0 : d.compareTo(best) > 0)) {
                best = d;
            }
        }
        return switch (aggregate) {
            case "SUM" -> sum;
            case "AVG" -> values.isEmpty() ? null : sum.divide(new BigDecimal(values.size()), MathContext.DECIMAL128);
            default -> best;
        };
    }

    /** Start of the bucket containing {@code d}. */
    public static LocalDate bucket(LocalDate d, String granularity) {
        return switch (granularity) {
            case "WEEK" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> d.withDayOfMonth(1);
            default -> d;
        };
    }

    /**
     * Time series: rows bucketed by {@code timeField} (ISO date or timestamp) within [from, to]; every bucket in the
     * range is present (empty buckets evaluate over no rows).
     */
    public List<Map<String, Object>> series(List<Map<String, Object>> rows, String timeField, LocalDate from, LocalDate to,
                                            String granularity, Map<String, Object> params) {
        Map<LocalDate, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (LocalDate b = bucket(from, granularity); !b.isAfter(to); b = next(b, granularity)) {
            buckets.put(b, new ArrayList<>());
        }
        for (Map<String, Object> r : rows) {
            Object t = r.get(timeField);
            if (t == null) {
                continue;
            }
            String s = String.valueOf(t);
            LocalDate d;
            try {
                d = LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
            } catch (java.time.format.DateTimeParseException e) {
                continue;
            }
            if (d.isBefore(from) || d.isAfter(to)) {
                continue;
            }
            buckets.computeIfAbsent(bucket(d, granularity), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.forEach((b, rs) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("period_start", b.toString());
            p.put("value", evaluate(rs, params));
            p.put("rows", rs.size());
            out.add(p);
        });
        return out;
    }

    private static LocalDate next(LocalDate b, String granularity) {
        return switch (granularity) {
            case "WEEK" -> b.plusWeeks(1);
            case "MONTH" -> b.plusMonths(1);
            default -> b.plusDays(1);
        };
    }
}
