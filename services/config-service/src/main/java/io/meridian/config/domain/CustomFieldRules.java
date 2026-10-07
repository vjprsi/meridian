package io.meridian.config.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rules for tenant-defined custom fields (custom_field_1..5 slots): validation of a definition itself and of
 * values stored in a slot. Values are stored as strings (max 500 chars) and typed by the definition.
 */
public final class CustomFieldRules {
    public static final int MAX_LENGTH = 500;
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern INTEGER = Pattern.compile("^[+-]?\\d+$");

    public enum DataType { TEXT, NUMBER, DECIMAL, BOOLEAN, DATE, DATETIME, EMAIL, PHONE, URL, ENUM, REFERENCE }

    /** A definition as far as validation is concerned. */
    public record Definition(String tableName, int slot, String fieldKey, DataType dataType, boolean required,
                             String regex, List<String> allowedValues, String minValue, String maxValue,
                             String defaultValue) {}

    private CustomFieldRules() {}

    /** Validates a definition; throws 422 with field errors. */
    public static void requireValidDefinition(Definition d) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (d.tableName() == null || !IDENT.matcher(d.tableName()).matches()) {
            errors.add(new ApiException.FieldError("table_name", "invalid_format", "must be a snake_case table name"));
        }
        if (d.fieldKey() == null || !IDENT.matcher(d.fieldKey()).matches()) {
            errors.add(new ApiException.FieldError("field_key", "invalid_format", "must be a snake_case key"));
        }
        if (d.slot() < 1 || d.slot() > 5) {
            errors.add(new ApiException.FieldError("slot", "invalid_slot", "must be between 1 and 5"));
        }
        if (d.dataType() == DataType.ENUM && (d.allowedValues() == null || d.allowedValues().isEmpty())) {
            errors.add(new ApiException.FieldError("allowed_values", "required", "ENUM fields need allowed_values"));
        }
        if (d.regex() != null) {
            io.meridian.common.text.SafeRegex.problem(d.regex()).ifPresent(why ->
                    errors.add(new ApiException.FieldError("validation_regex", "invalid_regex", why)));
        }
        if (d.minValue() != null || d.maxValue() != null) {
            if (!isOrdered(d.dataType())) {
                errors.add(new ApiException.FieldError("min_value", "not_applicable",
                        "min/max apply only to NUMBER, DECIMAL, DATE and DATETIME"));
            } else {
                Comparable<Object> min = d.minValue() == null ? null : parseOrdered(d.dataType(), d.minValue());
                Comparable<Object> max = d.maxValue() == null ? null : parseOrdered(d.dataType(), d.maxValue());
                if (d.minValue() != null && min == null) {
                    errors.add(new ApiException.FieldError("min_value", "invalid_type", "is not a valid " + d.dataType()));
                }
                if (d.maxValue() != null && max == null) {
                    errors.add(new ApiException.FieldError("max_value", "invalid_type", "is not a valid " + d.dataType()));
                }
                if (min != null && max != null && min.compareTo(max) > 0) {
                    errors.add(new ApiException.FieldError("min_value", "invalid_range", "must not exceed max_value"));
                }
            }
        }
        if (errors.isEmpty() && d.defaultValue() != null) {
            ApiException.FieldError e = validateValue(d, d.defaultValue());
            if (e != null) {
                errors.add(new ApiException.FieldError("default_value", e.code(), e.message()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_custom_field_definition",
                    errors.getFirst().field() + ": " + errors.getFirst().message(), errors);
        }
    }

    /** Validates one value against its definition; returns null when valid. Field = custom_field_&lt;slot&gt;. */
    public static ApiException.FieldError validateValue(Definition d, String value) {
        String field = d.fieldKey();
        if (value == null || value.isBlank()) {
            return d.required() ? new ApiException.FieldError(field, "required", "is required") : null;
        }
        if (value.length() > MAX_LENGTH) {
            return new ApiException.FieldError(field, "max_length", "must be at most " + MAX_LENGTH + " characters");
        }
        String typeError = switch (d.dataType()) {
            case TEXT, REFERENCE -> null;
            case NUMBER -> INTEGER.matcher(value).matches() ? null : "must be an integer";
            case DECIMAL -> parseOrdered(DataType.DECIMAL, value) != null ? null : "must be a decimal number";
            case BOOLEAN -> "true".equals(value) || "false".equals(value) ? null : "must be true or false";
            case DATE -> parseOrdered(DataType.DATE, value) != null ? null : "must be a date (YYYY-MM-DD)";
            case DATETIME -> parseOrdered(DataType.DATETIME, value) != null ? null : "must be an ISO 8601 date-time with offset";
            case EMAIL -> EMAIL.matcher(value).matches() ? null : "must be an email address";
            case PHONE -> PHONE.matcher(value).matches() ? null : "must be an E.164 phone number";
            case URL -> isUrl(value) ? null : "must be an http(s) URL";
            case ENUM -> d.allowedValues() != null && d.allowedValues().contains(value) ? null
                    : "must be one of " + d.allowedValues();
        };
        if (typeError != null) {
            return new ApiException.FieldError(field, d.dataType() == DataType.ENUM ? "not_allowed" : "invalid_type", typeError);
        }
        if (d.regex() != null && !io.meridian.common.text.SafeRegex.matches(d.regex(), value)) {
            return new ApiException.FieldError(field, "pattern", "must match " + d.regex());
        }
        if (isOrdered(d.dataType())) {
            Comparable<Object> v = parseOrdered(d.dataType(), value);
            if (d.minValue() != null && v.compareTo(parseOrdered(d.dataType(), d.minValue())) < 0) {
                return new ApiException.FieldError(field, "below_minimum", "must be >= " + d.minValue());
            }
            if (d.maxValue() != null && v.compareTo(parseOrdered(d.dataType(), d.maxValue())) > 0) {
                return new ApiException.FieldError(field, "above_maximum", "must be <= " + d.maxValue());
            }
        }
        return null;
    }

    private static boolean isOrdered(DataType t) {
        return t == DataType.NUMBER || t == DataType.DECIMAL || t == DataType.DATE || t == DataType.DATETIME;
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> parseOrdered(DataType t, String s) {
        try {
            Object v = switch (t) {
                case NUMBER, DECIMAL -> new BigDecimal(s.trim());
                case DATE -> LocalDate.parse(s.trim());
                case DATETIME -> OffsetDateTime.parse(s.trim()).toInstant();
                default -> null;
            };
            if (t == DataType.NUMBER && v != null && !INTEGER.matcher(s.trim()).matches()) {
                return null;
            }
            return (Comparable<Object>) v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isUrl(String s) {
        try {
            URI u = new URI(s);
            return ("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())) && u.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
