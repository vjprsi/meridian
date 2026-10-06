package io.meridian.customer.domain;

import io.meridian.common.error.ApiException.FieldError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Segment criteria: a small, safe rule language evaluated against a customer.
 * <pre>
 * {"all": [ {"field": "customer_type", "op": "eq", "value": "INDIVIDUAL"},
 *           {"any": [ {"field": "annual_income", "op": "gte", "value": 100000},
 *                     {"field": "kyc_tier", "op": "gte", "value": "TIER_3"} ]} ]}
 * </pre>
 * Ordinal fields (kyc_tier, risk_level) compare by rank; numeric fields as decimals; {@code age} is derived.
 */
public final class SegmentRule {
    private SegmentRule() {}

    private static final Set<String> TEXT_FIELDS = Set.of("customer_type", "status", "residence_country", "nationality",
            "employment_status", "onboarding_channel", "industry_code", "preferred_language", "gender", "marital_status",
            "tax_residence_country", "legal_form", "registration_country");
    private static final Set<String> BOOL_FIELDS = Set.of("is_staff", "is_pep", "us_person", "is_minor");
    private static final Set<String> NUMBER_FIELDS = Set.of("annual_income", "annual_turnover", "number_of_employees", "age");
    private static final Map<String, List<String>> ORDINAL_FIELDS = Map.of(
            "kyc_tier", KycTierPolicy.TIERS,
            "risk_level", List.of("LOW", "MEDIUM", "HIGH", "PROHIBITED"));
    private static final Set<String> OPS = Set.of("eq", "ne", "in", "not_in", "gt", "gte", "lt", "lte", "exists");
    private static final int MAX_DEPTH = 5;

    public static List<FieldError> validate(JsonNode criteria) {
        List<FieldError> errors = new ArrayList<>();
        if (criteria == null || criteria.isNull() || !criteria.isObject() || criteria.isEmpty()) {
            errors.add(new FieldError("criteria", "required", "Automatic segments need a criteria rule"));
            return errors;
        }
        check(criteria, "criteria", 0, errors);
        return errors;
    }

    private static void check(JsonNode n, String path, int depth, List<FieldError> errors) {
        if (depth > MAX_DEPTH) {
            errors.add(new FieldError(path, "too_deep", "Rules may nest at most " + MAX_DEPTH + " levels"));
            return;
        }
        for (String group : List.of("all", "any")) {
            if (n.has(group)) {
                JsonNode list = n.get(group);
                if (!list.isArray() || list.isEmpty()) {
                    errors.add(new FieldError(path + "." + group, "invalid", group + " must be a non-empty array"));
                    return;
                }
                for (int i = 0; i < list.size(); i++) {
                    check(list.get(i), path + "." + group + "[" + i + "]", depth + 1, errors);
                }
                return;
            }
        }
        if (n.has("not")) {
            check(n.get("not"), path + ".not", depth + 1, errors);
            return;
        }
        String field = n.path("field").asString("");
        String op = n.path("op").asString("");
        if (!known(field)) {
            errors.add(new FieldError(path + ".field", "unknown_field", "Unsupported field '" + field + "'"));
        }
        if (!OPS.contains(op)) {
            errors.add(new FieldError(path + ".op", "unknown_op", "Unsupported operator '" + op + "'"));
            return;
        }
        if (!"exists".equals(op) && !n.has("value")) {
            errors.add(new FieldError(path + ".value", "required", "value is required for " + op));
        }
        if (("in".equals(op) || "not_in".equals(op)) && !n.path("value").isArray()) {
            errors.add(new FieldError(path + ".value", "invalid", op + " needs an array value"));
        }
        boolean ordered = op.startsWith("g") || op.startsWith("l");
        if (ordered && !NUMBER_FIELDS.contains(field) && !ORDINAL_FIELDS.containsKey(field)) {
            errors.add(new FieldError(path + ".op", "invalid", op + " is only allowed on numeric or ordinal fields"));
        }
        if (ordered && NUMBER_FIELDS.contains(field) && n.has("value") && decimal(n.get("value")) == null) {
            errors.add(new FieldError(path + ".value", "invalid", "value must be numeric"));
        }
        if (ORDINAL_FIELDS.containsKey(field) && n.has("value") && n.get("value").isString()
                && !ORDINAL_FIELDS.get(field).contains(n.get("value").asString())) {
            errors.add(new FieldError(path + ".value", "invalid", "unknown " + field + " value"));
        }
    }

    private static boolean known(String f) {
        return TEXT_FIELDS.contains(f) || BOOL_FIELDS.contains(f) || NUMBER_FIELDS.contains(f) || ORDINAL_FIELDS.containsKey(f);
    }

    /** Evaluates validated criteria against a customer row (snake_case JSON). */
    public static boolean matches(JsonNode n, JsonNode customer, LocalDate today) {
        if (n.has("all")) {
            for (JsonNode c : n.get("all")) {
                if (!matches(c, customer, today)) {
                    return false;
                }
            }
            return true;
        }
        if (n.has("any")) {
            for (JsonNode c : n.get("any")) {
                if (matches(c, customer, today)) {
                    return true;
                }
            }
            return false;
        }
        if (n.has("not")) {
            return !matches(n.get("not"), customer, today);
        }
        String field = n.get("field").asString();
        String op = n.get("op").asString();
        JsonNode actual = value(field, customer, today);
        boolean present = actual != null && !actual.isNull();
        if ("exists".equals(op)) {
            boolean want = !n.has("value") || n.get("value").asBoolean(true);
            return present == want;
        }
        JsonNode expected = n.get("value");
        if ("in".equals(op) || "not_in".equals(op)) {
            boolean in = present && contains(field, expected, actual);
            return "in".equals(op) == in;
        }
        int c = present ? compare(field, actual, expected) : Integer.MIN_VALUE;
        if (c == Integer.MIN_VALUE) {
            return "ne".equals(op);
        }
        return switch (op) {
            case "eq" -> c == 0;
            case "ne" -> c != 0;
            case "gt" -> c > 0;
            case "gte" -> c >= 0;
            case "lt" -> c < 0;
            case "lte" -> c <= 0;
            default -> false;
        };
    }

    private static boolean contains(String field, JsonNode list, JsonNode actual) {
        for (JsonNode v : list) {
            if (compare(field, actual, v) == 0) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode value(String field, JsonNode customer, LocalDate today) {
        if ("age".equals(field)) {
            JsonNode dob = customer.get("date_of_birth");
            if (dob == null || dob.isNull()) {
                return null;
            }
            int age = Period.between(LocalDate.parse(dob.asString()), today).getYears();
            return tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(age);
        }
        return customer.get(field);
    }

    private static int compare(String field, JsonNode actual, JsonNode expected) {
        if (NUMBER_FIELDS.contains(field)) {
            BigDecimal a = decimal(actual);
            BigDecimal e = decimal(expected);
            if (a == null || e == null) {
                return Integer.MIN_VALUE;
            }
            return a.compareTo(e);
        }
        if (ORDINAL_FIELDS.containsKey(field)) {
            List<String> order = ORDINAL_FIELDS.get(field);
            return Integer.compare(order.indexOf(actual.asString()), order.indexOf(expected.asString()));
        }
        if (BOOL_FIELDS.contains(field)) {
            return actual.asBoolean() == expected.asBoolean() ? 0 : 1;
        }
        return actual.asString().trim().equalsIgnoreCase(expected.asString().trim()) ? 0 : 1;
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
