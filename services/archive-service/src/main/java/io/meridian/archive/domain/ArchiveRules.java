package io.meridian.archive.domain;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.util.Set;

/** Lifecycle rules for archive jobs, legal holds and purge requests, and retention arithmetic. */
public final class ArchiveRules {
    private ArchiveRules() {}

    private static ApiException transition(String what, String from, String action) {
        return ApiException.conflict("invalid_transition", what + " in status " + from + " cannot " + action);
    }

    // ------------------------------------------------------------------ retention

    /** Records created before this date have left the online period and may be archived. */
    public static LocalDate archiveCutoff(LocalDate today, int onlineDays) {
        return today.minusDays(onlineDays);
    }

    /** Records created before this date reached the end of retention and may be purged. */
    public static LocalDate purgeCutoff(LocalDate today, int retentionYears) {
        return today.minusYears(retentionYears);
    }

    /** WORM retention date of an archived batch: its newest record plus the retention period. */
    public static LocalDate wormRetainUntil(LocalDate newestRecord, int retentionYears) {
        return newestRecord.plusYears(retentionYears);
    }

    public static void requirePolicyShape(int onlineDays, int retentionYears) {
        if (onlineDays < 0) {
            throw ApiException.unprocessable("invalid_online_days", "online_days must not be negative");
        }
        if (retentionYears < 1 || retentionYears > 100) {
            throw ApiException.unprocessable("invalid_retention_years", "retention_years must be between 1 and 100");
        }
        if ((long) onlineDays > retentionYears * 366L) {
            throw ApiException.unprocessable("online_exceeds_retention", "online_days cannot exceed the total retention");
        }
    }

    // ------------------------------------------------------------------ archive jobs

    public static void requireJobCancel(String status) {
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw transition("Archive job", status, "be cancelled");
        }
    }

    // ------------------------------------------------------------------ legal holds

    public static void requireHoldRelease(String status) {
        if (!"ACTIVE".equals(status)) {
            throw transition("Legal hold", status, "be released");
        }
    }

    // ------------------------------------------------------------------ purge requests

    public static void requireSubmit(String status) {
        if (!"DRAFT".equals(status)) {
            throw transition("Purge request", status, "be submitted");
        }
    }

    public static void requireDecision(String status) {
        if (!"PENDING_APPROVAL".equals(status)) {
            throw transition("Purge request", status, "be approved or rejected");
        }
    }

    public static void requireExecute(String status) {
        if ("BLOCKED_BY_HOLD".equals(status)) {
            throw ApiException.conflict("blocked_by_legal_hold", "The purge is blocked by an active legal hold");
        }
        if (!"APPROVED".equals(status) && !"FAILED".equals(status)) {
            throw transition("Purge request", status, "be executed");
        }
    }

    private static final Set<String> CANCELLABLE = Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "BLOCKED_BY_HOLD", "FAILED");

    public static void requireCancel(String status) {
        if (!CANCELLABLE.contains(status)) {
            throw transition("Purge request", status, "be cancelled");
        }
    }

    public static void requirePurgeAction(String action) {
        if (!"PURGE".equals(action) && !"ANONYMISE".equals(action)) {
            throw ApiException.unprocessable("invalid_purge_action", "A purge request action must be PURGE or ANONYMISE");
        }
    }
}
