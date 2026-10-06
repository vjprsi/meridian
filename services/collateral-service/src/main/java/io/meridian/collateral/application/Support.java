package io.meridian.collateral.application;

import io.meridian.collateral.domain.CollateralRules;
import io.meridian.common.error.ApiException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;

/** Small helpers shared by the collateral use cases. */
final class Support {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private Support() {}

    /** COL-2026-7K3QZ8M2 style registry number (unambiguous alphabet). */
    static String collateralNumber(int year) {
        StringBuilder sb = new StringBuilder("COL-").append(year).append('-');
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    static ApiException invalid(String code, String message, List<CollateralRules.Violation> violations) {
        return new ApiException(422, code, message, violations.stream()
                .map(v -> new ApiException.FieldError(v.field(), v.code(), v.message())).toList());
    }

    static void requireTransition(String table, String from, String to) {
        if (!CollateralRules.canTransition(from, to)) {
            throw ApiException.conflict("invalid_transition", table + " cannot move from " + from + " to " + to);
        }
    }

    static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.badRequest("if_match_required", "If-Match header with the row_version is required");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be the row_version");
        }
    }

    static void notAfter(LocalDate value, LocalDate today, String field) {
        if (value != null && value.isAfter(today)) {
            throw ApiException.unprocessable("date_in_future", field + " must not be in the future");
        }
    }
}
