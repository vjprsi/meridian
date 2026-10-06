package io.meridian.config.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;

/**
 * Validates a JSON value against a pragmatic subset of JSON Schema (2020-12): {@code type} (single or list),
 * {@code enum}, {@code const}, {@code required}, {@code properties}, {@code additionalProperties: false},
 * {@code items}, {@code minItems}/{@code maxItems}, {@code minimum}/{@code maximum},
 * {@code exclusiveMinimum}/{@code exclusiveMaximum}, {@code minLength}/{@code maxLength} and {@code pattern}.
 * Unknown keywords are ignored (forward compatible).
 */
public final class JsonSchemaValidator {
    private JsonSchemaValidator() {}

    /** Returns field errors; empty means valid. */
    public static List<ApiException.FieldError> validate(JsonNode schema, JsonNode value) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (schema != null && !schema.isNull()) {
            check(schema, value, "$", errors);
        }
        return errors;
    }

    /** Throws 422 schema_violation with field errors when the value does not satisfy the schema. */
    public static void require(JsonNode schema, JsonNode value) {
        if (schema != null && !schema.isNull() && !schema.isObject()) {
            throw ApiException.unprocessable("invalid_schema", "value_schema must be a JSON Schema object");
        }
        List<ApiException.FieldError> errors = validate(schema, value);
        if (!errors.isEmpty()) {
            throw new ApiException(422, "schema_violation", "value does not satisfy value_schema: "
                    + errors.getFirst().field() + " " + errors.getFirst().message(), errors);
        }
    }

    private static void check(JsonNode s, JsonNode v, String path, List<ApiException.FieldError> errors) {
        if (!s.isObject()) {
            return;
        }
        if (s.has("type") && !typeMatches(s.get("type"), v)) {
            errors.add(err(path, "type", "must be of type " + s.get("type").toString()));
            return;
        }
        if (s.has("const") && !s.get("const").equals(v)) {
            errors.add(err(path, "const", "must equal " + s.get("const")));
        }
        if (s.has("enum") && s.get("enum").isArray()) {
            boolean found = false;
            for (JsonNode e : s.get("enum")) {
                if (e.equals(v) || (e.isNumber() && v.isNumber() && e.decimalValue().compareTo(v.decimalValue()) == 0)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(err(path, "enum", "must be one of " + s.get("enum")));
            }
        }
        if (v.isNumber()) {
            BigDecimal n = v.decimalValue();
            bound(s, "minimum", n, path, errors, c -> c >= 0, "must be >= ");
            bound(s, "maximum", n, path, errors, c -> c <= 0, "must be <= ");
            bound(s, "exclusiveMinimum", n, path, errors, c -> c > 0, "must be > ");
            bound(s, "exclusiveMaximum", n, path, errors, c -> c < 0, "must be < ");
        }
        if (v.isString()) {
            String str = v.stringValue();
            int len = str.codePointCount(0, str.length());
            if (s.has("minLength") && len < s.get("minLength").asInt()) {
                errors.add(err(path, "min_length", "must have at least " + s.get("minLength").asInt() + " characters"));
            }
            if (s.has("maxLength") && len > s.get("maxLength").asInt()) {
                errors.add(err(path, "max_length", "must have at most " + s.get("maxLength").asInt() + " characters"));
            }
            if (s.has("pattern")) {
                try {
                    if (!Pattern.compile(s.get("pattern").asString()).matcher(str).find()) {
                        errors.add(err(path, "pattern", "must match " + s.get("pattern").asString()));
                    }
                } catch (PatternSyntaxException e) {
                    errors.add(err(path, "invalid_schema", "pattern is not a valid regular expression"));
                }
            }
        }
        if (v.isArray()) {
            if (s.has("minItems") && v.size() < s.get("minItems").asInt()) {
                errors.add(err(path, "min_items", "must have at least " + s.get("minItems").asInt() + " items"));
            }
            if (s.has("maxItems") && v.size() > s.get("maxItems").asInt()) {
                errors.add(err(path, "max_items", "must have at most " + s.get("maxItems").asInt() + " items"));
            }
            if (s.has("items") && s.get("items").isObject()) {
                int i = 0;
                for (JsonNode item : v) {
                    check(s.get("items"), item, path + "[" + i++ + "]", errors);
                }
            }
        }
        if (v.isObject()) {
            if (s.has("required") && s.get("required").isArray()) {
                for (JsonNode r : s.get("required")) {
                    if (!v.has(r.asString())) {
                        errors.add(err(path + "." + r.asString(), "required", "is required"));
                    }
                }
            }
            JsonNode props = s.get("properties");
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                String p = path + "." + e.getKey();
                if (props != null && props.has(e.getKey())) {
                    check(props.get(e.getKey()), e.getValue(), p, errors);
                } else if (s.has("additionalProperties")) {
                    JsonNode ap = s.get("additionalProperties");
                    if (ap.isBoolean() && !ap.asBoolean()) {
                        errors.add(err(p, "additional_property", "is not allowed"));
                    } else if (ap.isObject()) {
                        check(ap, e.getValue(), p, errors);
                    }
                }
            }
        }
    }

    private interface Cmp {
        boolean ok(int c);
    }

    private static void bound(JsonNode s, String kw, BigDecimal n, String path, List<ApiException.FieldError> errors,
                              Cmp cmp, String msg) {
        if (s.has(kw) && s.get(kw).isNumber() && !cmp.ok(n.compareTo(s.get(kw).decimalValue()))) {
            errors.add(err(path, snake(kw), msg + s.get(kw).decimalValue().toPlainString()));
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode v) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeMatches(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asString()) {
            case "string" -> v.isString();
            case "number" -> v.isNumber();
            case "integer" -> v.isIntegralNumber()
                    || (v.isNumber() && v.decimalValue().stripTrailingZeros().scale() <= 0);
            case "boolean" -> v.isBoolean();
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "null" -> v.isNull();
            default -> true;
        };
    }

    private static String snake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static ApiException.FieldError err(String path, String code, String message) {
        return new ApiException.FieldError(path, code, message);
    }
}
