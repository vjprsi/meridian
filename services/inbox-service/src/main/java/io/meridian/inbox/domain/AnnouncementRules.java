package io.meridian.inbox.domain;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Announcement lifecycle, schedule and targeting rules. Pure (no Spring, no SQL). */
public final class AnnouncementRules {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "publish", Set.of("DRAFT"),
            "withdraw", Set.of("SCHEDULED", "LIVE"));

    private AnnouncementRules() {}

    /** True when {@code action} is allowed from {@code status}. */
    public static boolean allowed(String action, String status) {
        return TRANSITIONS.getOrDefault(action, Set.of()).contains(status);
    }

    /** Status right after publishing, by the database clock: ended windows cannot be published. */
    public static String statusOnPublish(OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime now) {
        if (endsAt != null && !endsAt.isAfter(now)) {
            return null;
        }
        return startsAt.isAfter(now) ? "SCHEDULED" : "LIVE";
    }

    /** The status a published announcement should have now (SCHEDULED, LIVE or EXPIRED); others unchanged. */
    public static String scheduledStatus(String status, OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime now) {
        if (!"SCHEDULED".equals(status) && !"LIVE".equals(status)) {
            return status;
        }
        if (endsAt != null && !endsAt.isAfter(now)) {
            return "EXPIRED";
        }
        return startsAt.isAfter(now) ? "SCHEDULED" : "LIVE";
    }

    /** Whether an announcement is shown now to a customer in a given app. */
    public static boolean visible(String status, OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime now,
                                  List<String> apps, String app, List<String> segmentIds, Collection<String> customerSegments) {
        if (!"LIVE".equals(scheduledStatus(status, startsAt, endsAt, now))) {
            return false;
        }
        if (apps != null && !apps.isEmpty() && !apps.contains(app)) {
            return false;
        }
        if (segmentIds == null || segmentIds.isEmpty()) {
            return true;
        }
        if (customerSegments == null) {
            return false;   // targeting unknown (lookup failed): fail closed
        }
        return segmentIds.stream().anyMatch(customerSegments::contains);
    }
}
