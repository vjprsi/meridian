package io.meridian.productcatalog.domain;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.productcatalog.domain.eligibility.EligibilityEvaluator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Business validation of product configuration rows (plain maps of column values) and of a whole version before
 * submission. Returns field errors; {@link #throwIfAny} turns them into a 422 problem.
 */
public final class ConfigValidator {
    private static final BigDecimal MAX_RATE = new BigDecimal("10");

    private ConfigValidator() {}

    public static void throwIfAny(String code, String message, List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(422, code, message, errors);
        }
    }

    // ------------------------------------------------------------------ parameters

    public static List<FieldError> parameter(Map<String, Object> p, String prefix) {
        List<FieldError> e = new ArrayList<>();
        String type = str(p.get("param_type"));
        String key = str(p.get("param_key"));
        if (key != null && !key.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")) {
            e.add(err(prefix + "param_key", "invalid_param_key", "param_key must be a dotted lower_snake path"));
        }
        String text = str(p.get("value_text"));
        Object json = p.get("value_json");
        if (text == null && json == null) {
            e.add(err(prefix + "value_text", "value_required", "value_text or value_json is required"));
            return e;
        }
        if (type == null || text == null) {
            return e;
        }
        try {
            switch (type) {
                case "INTEGER" -> {
                    BigDecimal v = new BigDecimal(text);
                    if (v.stripTrailingZeros().scale() > 0) {
                        throw new NumberFormatException();
                    }
                    bounds(v, p, prefix, e);
                }
                case "DECIMAL", "AMOUNT", "RATE" -> bounds(new BigDecimal(text), p, prefix, e);
                case "BOOLEAN" -> {
                    if (!text.equals("true") && !text.equals("false")) {
                        e.add(err(prefix + "value_text", "invalid_value", "BOOLEAN values are true or false"));
                    }
                }
                case "DATE" -> LocalDate.parse(text);
                case "DURATION" -> java.time.Period.parse(text);
                case "ENUM" -> {
                    Object allowed = p.get("allowed_values");
                    List<Object> values = allowed instanceof List<?> l ? new ArrayList<>(l) : null;
                    if (values == null || values.isEmpty()) {
                        e.add(err(prefix + "allowed_values", "allowed_values_required",
                                "ENUM parameters need allowed_values [...]"));
                    } else if (values.stream().noneMatch(v -> String.valueOf(v).equals(text))) {
                        e.add(err(prefix + "value_text", "value_not_allowed", text + " is not one of " + values));
                    }
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            e.add(err(prefix + "value_text", "invalid_value", "'" + text + "' is not a valid " + type));
        }
        return e;
    }

    private static void bounds(BigDecimal v, Map<String, Object> p, String prefix, List<FieldError> e) {
        BigDecimal min = num(p.get("min_value"));
        BigDecimal max = num(p.get("max_value"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            e.add(err(prefix + "min_value", "invalid_bounds", "min_value is above max_value"));
        }
        if (min != null && v.compareTo(min) < 0 || max != null && v.compareTo(max) > 0) {
            e.add(err(prefix + "value_text", "out_of_bounds", v.toPlainString() + " is outside ["
                    + (min == null ? "" : min.toPlainString()) + ", " + (max == null ? "" : max.toPlainString()) + "]"));
        }
    }

    // ------------------------------------------------------------------ interest

    public static List<FieldError> rateConfig(Map<String, Object> c, String productCurrency, String prefix) {
        List<FieldError> e = new ArrayList<>();
        String type = str(c.get("rate_type"));
        currency(c, productCurrency, prefix, e);
        rate(c, "fixed_rate", prefix, e);
        rate(c, "spread", prefix, e);
        rate(c, "floor_rate", prefix, e);
        rate(c, "cap_rate", prefix, e);
        if ("FIXED".equals(type) && c.get("fixed_rate") == null) {
            e.add(err(prefix + "fixed_rate", "required", "FIXED rates need fixed_rate"));
        }
        if ("FLOATING".equals(type) && c.get("rate_index_id") == null) {
            e.add(err(prefix + "rate_index_id", "required", "FLOATING rates need rate_index_id"));
        }
        BigDecimal floor = num(c.get("floor_rate"));
        BigDecimal cap = num(c.get("cap_rate"));
        if (floor != null && cap != null && floor.compareTo(cap) > 0) {
            e.add(err(prefix + "floor_rate", "invalid_bounds", "floor_rate is above cap_rate"));
        }
        Integer scale = integer(c.get("rounding_scale"));
        if (scale != null && (scale < 0 || scale > 8)) {
            e.add(err(prefix + "rounding_scale", "out_of_range", "rounding_scale must be 0..8"));
        }
        Integer day = integer(c.get("capitalisation_day"));
        if (day != null && (day < 1 || day > 31)) {
            e.add(err(prefix + "capitalisation_day", "out_of_range", "capitalisation_day must be 1..31"));
        }
        BigDecimal minBal = num(c.get("min_balance_for_interest"));
        if (minBal != null && minBal.signum() < 0) {
            e.add(err(prefix + "min_balance_for_interest", "must_not_be_negative", "min_balance_for_interest cannot be negative"));
        }
        dates(c, "effective_from", "effective_to", prefix, e);
        return e;
    }

    public static List<FieldError> rateTier(Map<String, Object> t, String prefix) {
        List<FieldError> e = new ArrayList<>();
        rate(t, "rate", prefix, e);
        rate(t, "spread", prefix, e);
        BigDecimal from = num(t.get("from_amount"));
        BigDecimal to = num(t.get("to_amount"));
        if (from != null && from.signum() < 0) {
            e.add(err(prefix + "from_amount", "must_not_be_negative", "from_amount cannot be negative"));
        }
        if (from != null && to != null && to.compareTo(from) <= 0) {
            e.add(err(prefix + "to_amount", "invalid_bounds", "to_amount must be above from_amount"));
        }
        Integer fd = integer(t.get("from_day"));
        Integer td = integer(t.get("to_day"));
        if (fd != null && fd < 1 || fd != null && td != null && td < fd) {
            e.add(err(prefix + "from_day", "invalid_bounds", "steps need 1 <= from_day <= to_day"));
        }
        return e;
    }

    // ------------------------------------------------------------------ fees

    public static List<FieldError> fee(Map<String, Object> f, String productCurrency, String prefix) {
        List<FieldError> e = new ArrayList<>();
        currency(f, productCurrency, prefix, e);
        String method = str(f.get("calculation_method"));
        for (String a : List.of("flat_amount", "min_amount", "max_amount", "cap_per_period")) {
            BigDecimal v = num(f.get(a));
            if (v != null && v.signum() < 0) {
                e.add(err(prefix + a, "must_not_be_negative", a + " cannot be negative"));
            }
        }
        BigDecimal pct = num(f.get("percentage"));
        if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.ONE) > 0)) {
            e.add(err(prefix + "percentage", "out_of_range", "percentage is a fraction 0..1"));
        }
        if (method != null) {
            boolean needsFlat = method.equals("FLAT") || method.equals("FLAT_PLUS_PERCENTAGE") || method.equals("PER_UNIT");
            boolean needsPct = method.equals("PERCENTAGE") || method.equals("FLAT_PLUS_PERCENTAGE");
            if (needsFlat && f.get("flat_amount") == null) {
                e.add(err(prefix + "flat_amount", "required", method + " fees need flat_amount"));
            }
            if (needsPct && pct == null) {
                e.add(err(prefix + "percentage", "required", method + " fees need percentage"));
            }
        }
        BigDecimal min = num(f.get("min_amount"));
        BigDecimal max = num(f.get("max_amount"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            e.add(err(prefix + "min_amount", "invalid_bounds", "min_amount is above max_amount"));
        }
        if (f.get("cap_per_period") != null && f.get("cap_period") == null) {
            e.add(err(prefix + "cap_period", "required", "cap_per_period needs cap_period"));
        }
        Integer free = integer(f.get("free_count"));
        if (free != null && free < 0) {
            e.add(err(prefix + "free_count", "must_not_be_negative", "free_count cannot be negative"));
        }
        String code = str(f.get("code"));
        if (code != null && !code.matches("[A-Z0-9_\\-]+")) {
            e.add(err(prefix + "code", "invalid_code", "fee code must be UPPER_SNAKE"));
        }
        dates(f, "effective_from", "effective_to", prefix, e);
        return e;
    }

    public static List<FieldError> feeTier(Map<String, Object> t, String prefix) {
        List<FieldError> e = new ArrayList<>();
        BigDecimal from = num(t.get("from_value"));
        BigDecimal to = num(t.get("to_value"));
        if (from != null && from.signum() < 0) {
            e.add(err(prefix + "from_value", "must_not_be_negative", "from_value cannot be negative"));
        }
        if (from != null && to != null && to.compareTo(from) <= 0) {
            e.add(err(prefix + "to_value", "invalid_bounds", "to_value must be above from_value"));
        }
        if (t.get("flat_amount") == null && t.get("percentage") == null) {
            e.add(err(prefix + "flat_amount", "required", "a fee tier needs flat_amount or percentage"));
        }
        BigDecimal pct = num(t.get("percentage"));
        if (pct != null && (pct.signum() < 0 || pct.compareTo(BigDecimal.ONE) > 0)) {
            e.add(err(prefix + "percentage", "out_of_range", "percentage is a fraction 0..1"));
        }
        return e;
    }

    public static List<FieldError> waiver(Map<String, Object> w, String prefix) {
        List<FieldError> e = new ArrayList<>();
        String c = str(w.get("condition"));
        BigDecimal d = num(w.get("discount_percentage"));
        if (d != null && (d.signum() <= 0 || d.compareTo(BigDecimal.ONE) > 0)) {
            e.add(err(prefix + "discount_percentage", "out_of_range", "discount_percentage is a fraction in (0, 1]"));
        }
        if (c != null) {
            switch (c) {
                case "MIN_BALANCE", "AVERAGE_BALANCE" -> {
                    if (w.get("threshold_amount") == null) {
                        e.add(err(prefix + "threshold_amount", "required", c + " waivers need threshold_amount"));
                    }
                }
                case "TRANSACTION_COUNT", "FIRST_N_FREE" -> {
                    if (w.get("threshold_count") == null) {
                        e.add(err(prefix + "threshold_count", "required", c + " waivers need threshold_count"));
                    }
                }
                case "SEGMENT" -> {
                    if (w.get("segment") == null) {
                        e.add(err(prefix + "segment", "required", "SEGMENT waivers need segment"));
                    }
                }
                default -> { }
            }
        }
        dates(w, "valid_from", "valid_to", prefix, e);
        return e;
    }

    // ------------------------------------------------------------------ limits and eligibility

    public static List<FieldError> limit(Map<String, Object> l, String productCurrency, String prefix) {
        List<FieldError> e = new ArrayList<>();
        BigDecimal max = num(l.get("max_value"));
        if (max != null && max.signum() < 0) {
            e.add(err(prefix + "max_value", "must_not_be_negative", "max_value cannot be negative"));
        }
        String metric = str(l.get("metric"));
        if (("AMOUNT".equals(metric) || "BALANCE".equals(metric) || metric == null) && l.get("currency") != null
                && productCurrency != null && !productCurrency.equals(str(l.get("currency")).trim())) {
            e.add(err(prefix + "currency", "currency_mismatch", "limit currency must equal the product currency " + productCurrency));
        }
        if ("COUNT".equals(metric) && max != null && max.stripTrailingZeros().scale() > 0) {
            e.add(err(prefix + "max_value", "invalid_value", "COUNT limits must be whole numbers"));
        }
        Integer tier = integer(l.get("kyc_tier"));
        if (tier != null && (tier < 0 || tier > 9)) {
            e.add(err(prefix + "kyc_tier", "out_of_range", "kyc_tier must be 0..9"));
        }
        return e;
    }

    public static List<FieldError> eligibility(Map<String, Object> r, String prefix) {
        List<FieldError> e = new ArrayList<>();
        String type = str(r.get("rule_type"));
        String op = str(r.get("operator"));
        if (op != null && !EligibilityEvaluator.OPERATORS.contains(op.toUpperCase(Locale.ROOT))) {
            e.add(err(prefix + "operator", "invalid_operator", "operator must be one of " + EligibilityEvaluator.OPERATORS));
            return e;
        }
        if ("CUSTOM_EXPRESSION".equals(type)) {
            if (r.get("expression") == null && r.get("decision_table_id") == null) {
                e.add(err(prefix + "expression", "required", "CUSTOM_EXPRESSION rules need expression or decision_table_id"));
            }
            return e;
        }
        if (r.get("decision_table_id") != null) {
            return e;
        }
        Object value = EligibilityEvaluator.normalizeValue(r.get("value"));
        if (value == null) {
            e.add(err(prefix + "value", "required", "value is required, e.g. {\"value\": 18}"));
            return e;
        }
        String o = op == null ? "EQ" : op.toUpperCase(Locale.ROOT);
        if (o.equals("BETWEEN") && !(value instanceof List<?> l && l.size() == 2 && l.get(0) != null && l.get(1) != null)) {
            e.add(err(prefix + "value", "invalid_value", "BETWEEN needs {\"min\": a, \"max\": b}"));
        }
        if ((o.equals("IN") || o.equals("NOT_IN")) && !(value instanceof List<?>) && !(value instanceof String)) {
            e.add(err(prefix + "value", "invalid_value", o + " needs {\"values\": [...]}"));
        }
        if (List.of("GT", "GTE", "LT", "LTE").contains(o) && num(value instanceof List<?> l && l.size() == 1 ? l.getFirst() : value) == null
                && !"RESIDENCY".equals(type) && !"NATIONALITY".equals(type)) {
            e.add(err(prefix + "value", "invalid_value", o + " needs a numeric value"));
        }
        return e;
    }

    // ------------------------------------------------------------------ whole version

    /**
     * Cross-row checks of a resolved definition before submission: tier ranges contiguous and non-overlapping,
     * tiered structures have tiers, fee tiers present for tiered methods, unique rate kinds per effective date.
     */
    @SuppressWarnings("unchecked")
    public static List<FieldError> version(Map<String, Object> def, String productCurrency) {
        List<FieldError> e = new ArrayList<>();
        List<Object> rates = list(def.get("interest_rates"));
        for (int i = 0; i < rates.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) rates.get(i);
            String p = "interest_rates[" + i + "].";
            e.addAll(rateConfig(c, productCurrency, p));
            String type = str(c.get("rate_type"));
            List<Object> tiers = list(c.get("tiers"));
            for (int k = 0; k < tiers.size(); k++) {
                e.addAll(rateTier((Map<String, Object>) tiers.get(k), p + "tiers[" + k + "]."));
            }
            if (List.of("TIERED", "BANDED", "STEPPED").contains(type) && tiers.isEmpty()) {
                e.add(err(p + "tiers", "tiers_required", type + " rates need tiers"));
            }
            if (List.of("TIERED", "BANDED").contains(type) || "FLOATING".equals(type) && !tiers.isEmpty()) {
                contiguous(tiers, "from_amount", "to_amount", p + "tiers", e);
            }
            if ("STEPPED".equals(type)) {
                List<Map<String, Object>> steps = tiers.stream().map(t -> (Map<String, Object>) t)
                        .sorted(Comparator.comparing(t -> integer(t.get("from_day")) == null ? 1 : integer(t.get("from_day")))).toList();
                Integer expected = 1;
                for (Map<String, Object> s : steps) {
                    Integer fd = integer(s.get("from_day")) == null ? 1 : integer(s.get("from_day"));
                    if (expected == null || !fd.equals(expected)) {
                        e.add(err(p + "tiers", "steps_not_contiguous", "rate steps must start at day 1 and not overlap or gap"));
                        break;
                    }
                    Integer td = integer(s.get("to_day"));
                    expected = td == null ? null : td + 1;
                }
            }
        }
        List<Object> fees = list(def.get("fees"));
        for (int i = 0; i < fees.size(); i++) {
            Map<String, Object> f = (Map<String, Object>) fees.get(i);
            String p = "fees[" + i + "].";
            e.addAll(fee(f, productCurrency, p));
            List<Object> tiers = list(f.get("tiers"));
            String method = str(f.get("calculation_method"));
            if (method != null && method.startsWith("TIERED")) {
                if (tiers.isEmpty()) {
                    e.add(err(p + "tiers", "tiers_required", method + " fees need tiers"));
                } else {
                    contiguous(tiers, "from_value", "to_value", p + "tiers", e);
                }
            }
            for (int k = 0; k < tiers.size(); k++) {
                e.addAll(feeTier((Map<String, Object>) tiers.get(k), p + "tiers[" + k + "]."));
            }
            List<Object> waivers = list(f.get("waiver_rules"));
            for (int k = 0; k < waivers.size(); k++) {
                e.addAll(waiver((Map<String, Object>) waivers.get(k), p + "waiver_rules[" + k + "]."));
            }
        }
        List<Object> params = list(def.get("parameters"));
        for (int i = 0; i < params.size(); i++) {
            e.addAll(parameter((Map<String, Object>) params.get(i), "parameters[" + i + "]."));
        }
        List<Object> limits = list(def.get("limits"));
        for (int i = 0; i < limits.size(); i++) {
            e.addAll(limit((Map<String, Object>) limits.get(i), productCurrency, "limits[" + i + "]."));
        }
        List<Object> rules = list(def.get("eligibility_rules"));
        for (int i = 0; i < rules.size(); i++) {
            e.addAll(eligibility((Map<String, Object>) rules.get(i), "eligibility_rules[" + i + "]."));
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private static void contiguous(List<Object> tiers, String fromKey, String toKey, String field, List<FieldError> e) {
        List<Map<String, Object>> sorted = tiers.stream().map(t -> (Map<String, Object>) t)
                .sorted(Comparator.comparing(t -> num(t.get(fromKey)) == null ? BigDecimal.ZERO : num(t.get(fromKey))))
                .toList();
        BigDecimal expected = null;
        for (int i = 0; i < sorted.size(); i++) {
            BigDecimal from = num(sorted.get(i).get(fromKey));
            from = from == null ? BigDecimal.ZERO : from;
            if (expected != null && from.compareTo(expected) != 0) {
                e.add(err(field, "tiers_not_contiguous", "tier ranges must be contiguous and non-overlapping (gap or overlap at "
                        + from.toPlainString() + ")"));
                return;
            }
            BigDecimal to = num(sorted.get(i).get(toKey));
            if (to == null && i < sorted.size() - 1) {
                e.add(err(field, "tiers_not_contiguous", "only the last tier may be unbounded"));
                return;
            }
            expected = to;
        }
    }

    private static void currency(Map<String, Object> m, String productCurrency, String prefix, List<FieldError> e) {
        String c = str(m.get("currency"));
        if (c != null && productCurrency != null && !c.trim().equals(productCurrency.trim())) {
            e.add(err(prefix + "currency", "currency_mismatch", "currency must equal the product currency " + productCurrency));
        }
    }

    private static void rate(Map<String, Object> m, String field, String prefix, List<FieldError> e) {
        BigDecimal r = num(m.get(field));
        if (r != null && r.abs().compareTo(MAX_RATE) > 0) {
            e.add(err(prefix + field, "out_of_range", field + " is an annual fraction (0.045 = 4.5%)"));
        }
    }

    private static void dates(Map<String, Object> m, String fromKey, String toKey, String prefix, List<FieldError> e) {
        String from = str(m.get(fromKey));
        String to = str(m.get(toKey));
        if (from != null && to != null && LocalDate.parse(to.substring(0, 10)).isBefore(LocalDate.parse(from.substring(0, 10)))) {
            e.add(err(prefix + toKey, "invalid_dates", toKey + " is before " + fromKey));
        }
    }

    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private static FieldError err(String field, String code, String message) {
        return new FieldError(field, code, message);
    }

    public static BigDecimal num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(o.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Integer integer(Object o) {
        BigDecimal b = num(o);
        return b == null ? null : b.intValue();
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
