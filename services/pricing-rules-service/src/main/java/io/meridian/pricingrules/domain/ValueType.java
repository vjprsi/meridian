package io.meridian.pricingrules.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Typed values of decision-table columns (DMN typeRef subset). */
public enum ValueType {
    NUMBER, STRING, BOOLEAN, DATE, ANY;

    /** Maps DMN / JSON type names (number, string, boolean, date, integer, decimal...) to a value type. */
    public static ValueType of(String typeRef) {
        if (typeRef == null || typeRef.isBlank()) {
            return ANY;
        }
        return switch (typeRef.trim().toLowerCase(Locale.ROOT)) {
            case "number", "integer", "int", "long", "decimal", "double", "amount", "rate" -> NUMBER;
            case "string", "text" -> STRING;
            case "boolean", "bool" -> BOOLEAN;
            case "date" -> DATE;
            case "any" -> ANY;
            default -> throw new DecisionTableException("unsupported_type", "Unsupported column type '" + typeRef + "'");
        };
    }

    public String typeRef() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Coerces a raw input (from JSON) to this type. Returns null for null input.
     *
     * @throws DecisionTableException with code invalid_input when the value cannot be represented
     */
    public Object coerce(String column, Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (this) {
                case NUMBER -> toNumber(raw);
                case STRING -> raw.toString();
                case BOOLEAN -> toBoolean(raw);
                case DATE -> toDate(raw);
                case ANY -> raw instanceof Number n ? toNumber(n) : raw;
            };
        } catch (RuntimeException e) {
            throw new DecisionTableException("invalid_input",
                    "Input '" + column + "' value '" + raw + "' is not a valid " + typeRef());
        }
    }

    static BigDecimal toNumber(Object raw) {
        if (raw instanceof BigDecimal b) {
            return b;
        }
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(raw.toString().trim());
    }

    static Boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw.toString().trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("not a boolean: " + raw);
        };
    }

    static LocalDate toDate(Object raw) {
        if (raw instanceof LocalDate d) {
            return d;
        }
        String s = raw.toString().trim();
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(s).toLocalDate();
        }
    }
}
