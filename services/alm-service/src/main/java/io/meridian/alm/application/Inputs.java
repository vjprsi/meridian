package io.meridian.alm.application;

import io.meridian.alm.domain.Curve;
import io.meridian.alm.domain.Position;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Parses and validates list-valued request inputs (positions, curves); errors become 422 with the item index. */
final class Inputs {
    private static final Pattern CCY = Pattern.compile("[A-Z]{3}");

    private Inputs() {}

    static List<Position> positions(JsonNode items, String field) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw ApiException.unprocessable("positions_required", field + " must be a non-empty list");
        }
        List<Position> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : items) {
            try {
                if (!n.isObject()) {
                    throw new IllegalArgumentException("must be an object");
                }
                String side = text(n, "side");
                if (side == null) {
                    throw new IllegalArgumentException("side is required (ASSET, LIABILITY or OFF_BALANCE)");
                }
                String rateType = text(n, "rate_type");
                String ccy = text(n, "currency");
                if (ccy != null && !CCY.matcher(ccy).matches()) {
                    throw new IllegalArgumentException("currency must be an ISO 4217 code");
                }
                Integer freq = n.hasNonNull("payment_frequency_months") ? n.get("payment_frequency_months").asInt() : 0;
                out.add(new Position(text(n, "code"), uuid(n, "product_id"), text(n, "segment"),
                        Position.Side.valueOf(side.toUpperCase(Locale.ROOT)), decimal(n, "amount"), ccy,
                        decimal(n, "rate"),
                        rateType == null ? null : Position.RateType.valueOf(rateType.toUpperCase(Locale.ROOT)),
                        date(n, "repricing_date"), date(n, "maturity_date"),
                        n.hasNonNull("non_maturity") && n.get("non_maturity").asBoolean(), freq));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw ApiException.unprocessable("invalid_position", field + "[" + i + "]: " + message(e));
            }
            i++;
        }
        return out;
    }

    /** Curve points [{tenor_days, rate}] (rate decimal); empty / absent = null. */
    static Curve curve(JsonNode items, String field, String rateField) {
        if (items == null || items.isNull() || (items.isArray() && items.isEmpty())) {
            return null;
        }
        if (!items.isArray()) {
            throw ApiException.unprocessable("invalid_curve", field + " must be a list");
        }
        List<Curve.Point> pts = new ArrayList<>();
        int i = 0;
        for (JsonNode n : items) {
            try {
                if (!n.hasNonNull("tenor_days")) {
                    throw new IllegalArgumentException("tenor_days is required");
                }
                BigDecimal r = decimal(n, rateField);
                if (r == null) {
                    throw new IllegalArgumentException(rateField + " is required");
                }
                pts.add(new Curve.Point(n.get("tenor_days").asInt(), r));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_curve", field + "[" + i + "]: " + message(e));
            }
            i++;
        }
        try {
            return new Curve(pts);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_curve", field + ": " + e.getMessage());
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    static BigDecimal decimal(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(f + " must be a decimal number");
        }
    }

    static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(f + " must be a UUID");
        }
    }

    static LocalDate date(JsonNode n, String f) {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(f + " must be a date (YYYY-MM-DD)");
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof IllegalArgumentException && m != null && m.startsWith("No enum constant")) {
            return "unknown value " + m.substring(m.lastIndexOf('.') + 1);
        }
        return m;
    }
}
