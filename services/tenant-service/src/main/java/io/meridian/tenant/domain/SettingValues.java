package io.meridian.tenant.domain;

import java.math.BigDecimal;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Validates a tenant setting value against its declared {@code SettingValueType}. */
public final class SettingValues {
    private SettingValues() {}

    /**
     * Contract note: {@code value} is a JSON column exposed as an object, so scalar types (STRING, NUMBER, BOOLEAN,
     * DURATION) are carried wrapped as {@code {"value": <scalar>}}; AMOUNT is {@code {"amount","currency"}} and
     * JSON is any object.
     *
     * @return an error message when the value does not match the type, empty when valid
     */
    public static Optional<String> validate(String valueType, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.of("value is required");
        }
        if (isScalarType(valueType)) {
            if (!value.isObject() || !value.has("value")) {
                return Optional.of(valueType + " setting needs {\"value\": <scalar>}");
            }
            value = value.get("value");
        }
        return switch (valueType) {
            case "STRING" -> value.isString() ? Optional.empty() : Optional.of("STRING setting needs a JSON string");
            case "BOOLEAN" -> value.isBoolean() ? Optional.empty() : Optional.of("BOOLEAN setting needs true or false");
            case "NUMBER" -> isNumber(value) ? Optional.empty() : Optional.of("NUMBER setting needs a number");
            case "JSON" -> value.isObject() || value.isArray() ? Optional.empty()
                    : Optional.of("JSON setting needs an object or array");
            case "DURATION" -> value.isString() && Validators.isIsoDuration(value.asString()) ? Optional.empty()
                    : Optional.of("DURATION setting needs an ISO 8601 duration string, e.g. PT30M");
            case "AMOUNT" -> amount(value);
            default -> Optional.of("unknown value_type " + valueType);
        };
    }

    public static boolean isScalarType(String valueType) {
        return "STRING".equals(valueType) || "NUMBER".equals(valueType) || "BOOLEAN".equals(valueType)
                || "DURATION".equals(valueType);
    }

    private static boolean isNumber(JsonNode v) {
        if (v.isNumber()) {
            return true;
        }
        if (v.isString()) {
            try {
                new BigDecimal(v.asString());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static Optional<String> amount(JsonNode v) {
        if (!v.isObject() || !v.hasNonNull("amount") || !v.hasNonNull("currency")) {
            return Optional.of("AMOUNT setting needs {\"amount\":\"<decimal>\",\"currency\":\"<ISO 4217>\"}");
        }
        try {
            new BigDecimal(v.get("amount").asString());
        } catch (NumberFormatException e) {
            return Optional.of("AMOUNT amount must be a decimal string");
        }
        if (!Validators.isCurrency(v.get("currency").asString())) {
            return Optional.of("AMOUNT currency must be an ISO 4217 code");
        }
        return Optional.empty();
    }
}
