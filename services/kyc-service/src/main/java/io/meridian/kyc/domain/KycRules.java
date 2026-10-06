package io.meridian.kyc.domain;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** State machines and static rules of the KYC domain. Pure: no Spring, no SQL. */
public final class KycRules {
    private KycRules() {}

    public static final Set<String> CAPABILITIES =
            Set.of("DOCUMENT", "LIVENESS", "FACE_MATCH", "DATABASE", "ADDRESS", "REGISTRY", "SCREENING", "PHONE", "EMAIL");
    public static final Set<String> TERMINAL_CHECK = Set.of("PASSED", "FAILED", "INCONCLUSIVE", "ERROR", "EXPIRED");
    public static final Set<String> RETRYABLE_CHECK = Set.of("FAILED", "ERROR");
    public static final Set<String> CHECK_STATUSES =
            Set.of("PENDING", "IN_PROGRESS", "PASSED", "FAILED", "INCONCLUSIVE", "ERROR", "EXPIRED");
    public static final Set<String> TERMINAL_REVIEW = Set.of("COMPLETED", "CANCELLED");
    public static final Set<String> REVIEW_OUTCOMES = Set.of("NO_CHANGE", "UPDATED", "EDD_REQUIRED", "EXIT_RELATIONSHIP");
    public static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "PROHIBITED");
    public static final Set<String> OPEN_MATCH = Set.of("POTENTIAL_MATCH", "CONFIRMED_MATCH");

    /** Capability a provider needs to run a check type. */
    private static final Map<String, String> CHECK_CAPABILITY = Map.ofEntries(
            Map.entry("DOCUMENT_OCR", "DOCUMENT"), Map.entry("DOCUMENT_AUTHENTICITY", "DOCUMENT"),
            Map.entry("LIVENESS", "LIVENESS"), Map.entry("FACE_MATCH", "FACE_MATCH"),
            Map.entry("DATABASE_VERIFICATION", "DATABASE"), Map.entry("ADDRESS_VERIFICATION", "ADDRESS"),
            Map.entry("PHONE_VERIFICATION", "PHONE"), Map.entry("EMAIL_VERIFICATION", "EMAIL"),
            Map.entry("REGISTRY_LOOKUP", "REGISTRY"), Map.entry("UBO_DISCOVERY", "REGISTRY"),
            Map.entry("TAX_ID_VERIFICATION", "DATABASE"), Map.entry("BANK_ACCOUNT_VERIFICATION", "DATABASE"));

    public static String capabilityFor(String checkType) {
        String c = CHECK_CAPABILITY.get(checkType);
        if (c == null) {
            throw ApiException.unprocessable("invalid_check_type", "Unknown check type " + checkType);
        }
        return c;
    }

    // ------------------------------------------------------------------ documents

    public static void requireDocumentReviewable(String status) {
        if (!"UPLOADED".equals(status) && !"PROCESSING".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Document is " + status + " and can no longer be reviewed");
        }
    }

    // ------------------------------------------------------------------ checks

    public static void requireRetryable(String status) {
        if (!RETRYABLE_CHECK.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Only FAILED or ERROR checks can be retried; check is " + status);
        }
    }

    // ------------------------------------------------------------------ screenings

    public static void requireDisposable(String current, String target) {
        if (!"FALSE_POSITIVE".equals(target) && !"CONFIRMED_MATCH".equals(target)) {
            throw ApiException.unprocessable("invalid_disposition", "Disposition must be FALSE_POSITIVE or CONFIRMED_MATCH");
        }
        if (!"POTENTIAL_MATCH".equals(current)) {
            throw ApiException.conflict("invalid_transition", "Only POTENTIAL_MATCH screenings can be dispositioned; screening is " + current);
        }
    }

    // ------------------------------------------------------------------ reviews

    public static void requireOpenReview(String status) {
        if (TERMINAL_REVIEW.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Review is " + status);
        }
    }

    public static String initialReviewStatus(LocalDate dueDate, LocalDate today) {
        return dueDate.isAfter(today) ? "SCHEDULED" : "DUE";
    }

    /** Periodic review interval by risk level: HIGH 1 year, MEDIUM 2 years, LOW 3 years; none for PROHIBITED. */
    public static LocalDate nextReviewDate(String riskLevel, LocalDate from) {
        if (riskLevel == null) {
            return from.plusYears(2);
        }
        return switch (riskLevel) {
            case "HIGH" -> from.plusYears(1);
            case "LOW" -> from.plusYears(3);
            case "PROHIBITED" -> null;
            default -> from.plusYears(2);
        };
    }

    // ------------------------------------------------------------------ four-eyes

    public static void requireFourEyes(String maker, String checker, String what) {
        if (maker != null && maker.equals(checker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The " + what + " must be reviewed by a different user than the one who created it");
        }
    }
}
