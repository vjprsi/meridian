package io.meridian.audit.domain;

import java.util.Map;
import java.util.Set;

/** Data export lifecycle and request rules. */
public final class ExportRules {
    private ExportRules() {}

    public static final Set<String> SUBJECT_TYPES_REQUIRED = Set.of("DATA_SUBJECT_ACCESS", "DATA_PORTABILITY");
    /** Statutory response window for data-subject access requests (GDPR Art. 12(3): one month). */
    public static final int DSAR_DUE_DAYS = 30;
    public static final int DOWNLOAD_VALIDITY_DAYS = 7;

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "QUEUED", Set.of("RUNNING", "CANCELLED"),
            "RUNNING", Set.of("SUCCEEDED", "FAILED", "PARTIALLY_SUCCEEDED", "CANCELLED"),
            "SUCCEEDED", Set.of(),
            "FAILED", Set.of(),
            "PARTIALLY_SUCCEEDED", Set.of(),
            "CANCELLED", Set.of());

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** Null when valid, else an error code. */
    public static String validateRetention(String resourceType, int retentionYears, int archiveAfterDays) {
        if (resourceType == null || !(resourceType.equals("*") || resourceType.matches("[a-z][a-z0-9_.]{0,63}"))) {
            return "invalid_resource_type";
        }
        if (retentionYears < 1 || retentionYears > 100) {
            return "invalid_retention_years";
        }
        if (archiveAfterDays < 0 || archiveAfterDays >= retentionYears * 365) {
            return "invalid_archive_after_days";
        }
        return null;
    }
}
