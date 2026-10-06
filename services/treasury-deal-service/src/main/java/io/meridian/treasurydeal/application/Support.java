package io.meridian.treasurydeal.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.domain.Currencies;
import io.meridian.treasurydeal.domain.Lifecycle;
import io.meridian.treasurydeal.infrastructure.Rows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Shared business checks: transitions, four-eyes, field validation and server-generated references. */
public final class Support {
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Support() {}

    public static void transition(Map<String, Set<String>> machine, JsonNode row, String to, String what) {
        String from = Rows.str(row, "status");
        if (!Lifecycle.allowed(machine, from, to)) {
            throw ApiException.conflict("invalid_transition", what + " cannot move from " + from + " to " + to);
        }
    }

    /** Maker-checker: the checker must differ from the principal that created the row (and the extra makers given). */
    public static void fourEyes(JsonNode row, String... otherMakers) {
        String checker = TenantContext.principal();
        if (checker.equals(Rows.str(row, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker cannot approve or verify their own record");
        }
        for (String m : otherMakers) {
            if (checker.equals(m)) {
                throw ApiException.unprocessable("four_eyes_violation", "The maker cannot approve or verify their own record");
            }
        }
    }

    public static void currency(String code, String field) {
        if (!Currencies.isValid(code)) {
            throw ApiException.unprocessable("invalid_currency", field + " " + code + " is not an ISO 4217 currency");
        }
    }

    public static void positive(BigDecimal v, String field) {
        if (v == null || v.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must be greater than zero");
        }
    }

    public static void minorUnits(BigDecimal v, String currency, String field) {
        if (v != null && v.stripTrailingZeros().scale() > Currencies.digits(currency)) {
            throw ApiException.unprocessable("invalid_amount", field + " has more decimals than " + currency + " allows");
        }
    }

    public static void required(Object v, String field) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw ApiException.unprocessable("missing_field", field + " is required");
        }
    }

    public static void ordered(LocalDate first, LocalDate second, String what) {
        if (first != null && second != null && second.isBefore(first)) {
            throw ApiException.unprocessable("invalid_dates", what);
        }
    }

    /** Deal reference: prefix, trade date and six characters derived from the random part of the row id. */
    public static String dealRef(String prefix, LocalDate tradeDate, UUID id) {
        String suffix = Long.toString(Math.floorMod(id.getLeastSignificantBits(), 2_176_782_336L), 36)
                .toUpperCase(Locale.ROOT);
        return prefix + tradeDate.format(YYMMDD) + "-" + "000000".substring(suffix.length()) + suffix;
    }
}
