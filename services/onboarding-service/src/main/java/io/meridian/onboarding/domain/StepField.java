package io.meridian.onboarding.domain;

import io.meridian.common.error.ApiException.FieldError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A typed input field of a journey step, rendered by the client and validated by the server. In the definition a
 * field is either a plain name (legacy: required, any type) or an object
 * <pre>{"name": "date_of_birth", "type": "DATE", "label_key": "onb.dob", "required": true, "pattern": "...",
 *  "enum": ["A", "B"], "min_length": 1, "max_length": 80, "min": 0, "max": 100}</pre>
 *
 * @param type null for a legacy name-only field (presence is checked, not the type)
 */
public record StepField(String name, String type, String labelKey, boolean required, String pattern, List<String> enumValues,
                        Integer minLength, Integer maxLength, BigDecimal min, BigDecimal max) {
    public static final Set<String> TYPES = Set.of("STRING", "TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "EMAIL",
            "PHONE", "COUNTRY", "ENUM", "DOCUMENT");
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    public static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");
    public static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}");
    static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");

    static StepField parse(JsonNode f) {
        if (f.isString()) {
            return new StepField(f.asString(), null, null, true, null, List.of(), null, null, null, null);
        }
        List<String> values = new ArrayList<>();
        f.path("enum").forEach(v -> values.add(v.asString()));
        return new StepField(f.path("name").asString(""), f.path("type").asString(null),
                f.path("label_key").asString(null), !f.has("required") || f.get("required").asBoolean(true),
                f.path("pattern").asString(null), values, intOrNull(f, "min_length"), intOrNull(f, "max_length"),
                decOrNull(f, "min"), decOrNull(f, "max"));
    }

    /** Definition errors of one field at {@code path}. */
    static void validateDefinition(JsonNode f, String path, List<FieldError> errors) {
        if (f.isString()) {
            return; // legacy name-only field
        }
        if (!f.isObject()) {
            errors.add(new FieldError(path, "invalid_field", "a field is a name or an object"));
            return;
        }
        if (!NAME.matcher(f.path("name").asString("")).matches()) {
            errors.add(new FieldError(path + ".name", "invalid_name", "field name must match [a-z][a-z0-9_]{0,63}"));
        }
        String type = f.path("type").asString("");
        if (!TYPES.contains(type)) {
            errors.add(new FieldError(path + ".type", "invalid_type", "type must be one of " + TYPES));
        }
        if ("ENUM".equals(type) && (!f.path("enum").isArray() || f.path("enum").isEmpty())) {
            errors.add(new FieldError(path + ".enum", "required", "an ENUM field needs a non-empty enum list"));
        }
        if (f.hasNonNull("pattern")) {
            try {
                Pattern.compile(f.get("pattern").asString());
            } catch (PatternSyntaxException e) {
                errors.add(new FieldError(path + ".pattern", "invalid_pattern", "pattern is not a valid regular expression"));
            }
        }
    }

    /** The client-facing definition (null members omitted). */
    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("name", name);
        o.put("type", type == null ? "STRING" : type);
        o.put("label_key", labelKey == null ? "onboarding.field." + name : labelKey);
        o.put("required", required);
        if (pattern != null) {
            o.put("pattern", pattern);
        }
        if (!enumValues.isEmpty()) {
            enumValues.forEach(o.putArray("enum")::add);
        }
        if (minLength != null) {
            o.put("min_length", minLength);
        }
        if (maxLength != null) {
            o.put("max_length", maxLength);
        }
        if (min != null) {
            o.put("min", min);
        }
        if (max != null) {
            o.put("max", max);
        }
        return o;
    }

    /** Validates a value of this field; errors are reported at {@code input.<name>}. */
    public void validate(JsonNode v, List<FieldError> errors) {
        String at = "input." + name;
        if (JsonValues.blank(v)) {
            if (required) {
                errors.add(new FieldError(at, "required", name + " is required for this step"));
            }
            return;
        }
        if (type == null) {
            return;
        }
        String text = v.isString() ? v.asString() : null;
        switch (type) {
            case "STRING", "TEXT" -> {
                if (text == null) {
                    errors.add(new FieldError(at, "invalid_type", name + " must be a string"));
                    return;
                }
            }
            case "INTEGER" -> {
                if (!v.isIntegralNumber()) {
                    errors.add(new FieldError(at, "invalid_type", name + " must be an integer"));
                    return;
                }
                range(v.decimalValue(), at, errors);
            }
            case "DECIMAL" -> {
                BigDecimal d = decimal(v);
                if (d == null) {
                    errors.add(new FieldError(at, "invalid_type", name + " must be a decimal number"));
                    return;
                }
                range(d, at, errors);
            }
            case "BOOLEAN" -> {
                if (!v.isBoolean()) {
                    errors.add(new FieldError(at, "invalid_type", name + " must be true or false"));
                }
                return;
            }
            case "DATE" -> {
                try {
                    LocalDate.parse(text == null ? "" : text);
                } catch (DateTimeParseException e) {
                    errors.add(new FieldError(at, "invalid_date", name + " must be a date (YYYY-MM-DD)"));
                    return;
                }
            }
            case "EMAIL" -> {
                if (text == null || !EMAIL.matcher(text).matches()) {
                    errors.add(new FieldError(at, "invalid_email", name + " must be an email address"));
                    return;
                }
            }
            case "PHONE" -> {
                if (text == null || !E164.matcher(text).matches()) {
                    errors.add(new FieldError(at, "invalid_phone", name + " must be an E.164 phone number"));
                    return;
                }
            }
            case "COUNTRY" -> {
                if (text == null || !COUNTRY.matcher(text).matches()) {
                    errors.add(new FieldError(at, "invalid_country", name + " must be an ISO 3166-1 alpha-2 code"));
                    return;
                }
            }
            case "ENUM" -> {
                if (text == null || !enumValues.contains(text)) {
                    errors.add(new FieldError(at, "invalid_value", name + " must be one of " + enumValues));
                    return;
                }
            }
            case "DOCUMENT" -> {
                try {
                    UUID.fromString(text == null ? "" : text);
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldError(at, "invalid_document", name + " must be a document id"));
                    return;
                }
            }
            default -> {
                return;
            }
        }
        if (text != null) {
            if (minLength != null && text.length() < minLength) {
                errors.add(new FieldError(at, "too_short", name + " must have at least " + minLength + " characters"));
            }
            if (maxLength != null && text.length() > maxLength) {
                errors.add(new FieldError(at, "too_long", name + " must have at most " + maxLength + " characters"));
            }
            if (pattern != null && !Pattern.compile(pattern).matcher(text).matches()) {
                errors.add(new FieldError(at, "pattern_mismatch", name + " has an invalid format"));
            }
        }
    }

    private void range(BigDecimal d, String at, List<FieldError> errors) {
        if (min != null && d.compareTo(min) < 0) {
            errors.add(new FieldError(at, "below_min", name + " must be at least " + min.toPlainString()));
        }
        if (max != null && d.compareTo(max) > 0) {
            errors.add(new FieldError(at, "above_max", name + " must be at most " + max.toPlainString()));
        }
    }

    private static BigDecimal decimal(JsonNode v) {
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            return v.isString() ? new BigDecimal(v.asString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode f, String k) {
        return f.hasNonNull(k) ? f.get(k).asInt() : null;
    }

    private static BigDecimal decOrNull(JsonNode f, String k) {
        return f.hasNonNull(k) ? f.get(k).decimalValue() : null;
    }
}
