package io.meridian.onboarding.domain;

import io.meridian.common.error.ApiException.FieldError;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Condition language used by journey branching and decision rules:
 * {@code {"field": "a.b", "op": "eq|ne|gt|gte|lt|lte|in|exists", "value": ...}}, combined with
 * {@code {"all": [...]}}, {@code {"any": [...]}} and {@code {"not": {...}}}. Fields are dotted paths into the data.
 */
public final class Conditions {
    public static final Set<String> OPS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "exists");

    private Conditions() {}

    public static boolean eval(JsonNode c, JsonNode data) {
        if (c == null || c.isNull() || c.isMissingNode()) {
            return true;
        }
        if (c.has("all")) {
            for (JsonNode x : c.get("all")) {
                if (!eval(x, data)) {
                    return false;
                }
            }
            return true;
        }
        if (c.has("any")) {
            for (JsonNode x : c.get("any")) {
                if (eval(x, data)) {
                    return true;
                }
            }
            return false;
        }
        if (c.has("not")) {
            return !eval(c.get("not"), data);
        }
        JsonNode actual = resolve(data, c.path("field").asString(""));
        JsonNode expected = c.get("value");
        boolean present = actual != null && !actual.isNull() && !actual.isMissingNode();
        String op = c.path("op").asString("eq");
        return switch (op) {
            case "exists" -> (expected == null || expected.asBoolean(true)) == present;
            case "eq" -> present && equal(actual, expected);
            case "ne" -> !(present && equal(actual, expected));
            case "in" -> {
                if (!present || expected == null || !expected.isArray()) {
                    yield false;
                }
                for (JsonNode e : expected) {
                    if (equal(actual, e)) {
                        yield true;
                    }
                }
                yield false;
            }
            case "gt" -> present && compare(actual, expected) > 0;
            case "gte" -> present && compare(actual, expected) >= 0;
            case "lt" -> present && compare(actual, expected) < 0;
            case "lte" -> present && compare(actual, expected) <= 0;
            default -> false;
        };
    }

    public static void validate(JsonNode c, String path, List<FieldError> errors) {
        if (c == null || !c.isObject()) {
            errors.add(new FieldError(path, "invalid_condition", "condition must be an object"));
            return;
        }
        for (String k : List.of("all", "any")) {
            if (c.has(k)) {
                if (!c.get(k).isArray() || c.get(k).isEmpty()) {
                    errors.add(new FieldError(path + "." + k, "invalid_condition", k + " must be a non-empty array"));
                    return;
                }
                int i = 0;
                for (JsonNode x : c.get(k)) {
                    validate(x, path + "." + k + "[" + i++ + "]", errors);
                }
                return;
            }
        }
        if (c.has("not")) {
            validate(c.get("not"), path + ".not", errors);
            return;
        }
        if (JsonValues.blank(c.get("field"))) {
            errors.add(new FieldError(path + ".field", "required", "condition field is required"));
        }
        String op = c.path("op").asString("eq");
        if (!OPS.contains(op)) {
            errors.add(new FieldError(path + ".op", "invalid_operator", "op must be one of " + OPS));
        } else if ("in".equals(op) && (c.get("value") == null || !c.get("value").isArray())) {
            errors.add(new FieldError(path + ".value", "invalid_condition", "in requires an array value"));
        } else if (!"exists".equals(op) && !c.has("value")) {
            errors.add(new FieldError(path + ".value", "required", "condition value is required"));
        }
    }

    static JsonNode resolve(JsonNode data, String field) {
        JsonNode n = data;
        if (n == null || field.isBlank()) {
            return null;
        }
        for (String part : field.split("\\.")) {
            n = n.path(part);
        }
        return n;
    }

    private static boolean equal(JsonNode a, JsonNode b) {
        if (b == null || b.isNull()) {
            return a == null || a.isNull();
        }
        BigDecimal x = decimal(a);
        BigDecimal y = decimal(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        if (a.isBoolean() || b.isBoolean()) {
            return a.asString().equalsIgnoreCase(b.asString());
        }
        return a.asString().equals(b.asString());
    }

    private static int compare(JsonNode a, JsonNode b) {
        if (b == null || b.isNull()) {
            return 0;
        }
        BigDecimal x = decimal(a);
        BigDecimal y = decimal(b);
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        return a.asString().compareTo(b.asString());
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isContainer()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDecimal();
        }
        if (n.isString()) {
            try {
                return new BigDecimal(n.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
