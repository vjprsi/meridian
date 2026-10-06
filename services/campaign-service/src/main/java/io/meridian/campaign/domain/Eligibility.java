package io.meridian.campaign.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Targeting and offer eligibility checks (pure). */
public final class Eligibility {
    private Eligibility() {}

    /** Offer eligibility rules: target / excluded segments and a score band (all optional). */
    public record Rules(List<UUID> segmentIds, List<UUID> exclusionSegmentIds, BigDecimal minScore, BigDecimal maxScore) {
        public boolean needsSegments() {
            return !segmentIds.isEmpty() || !exclusionSegmentIds.isEmpty();
        }
    }

    /**
     * Segment targeting: the customer must be in at least one target segment (when any are given) and in none of
     * the excluded ones. Returns the exclusion reason, or null when the customer is in the audience.
     */
    public static String segments(Set<UUID> customerSegments, Collection<UUID> targets, Collection<UUID> exclusions) {
        if (targets != null && !targets.isEmpty() && targets.stream().noneMatch(customerSegments::contains)) {
            return "not_in_segment";
        }
        if (exclusions != null && exclusions.stream().anyMatch(customerSegments::contains)) {
            return "excluded_segment";
        }
        return null;
    }

    /** Offer eligibility; null when eligible, otherwise the reason. */
    public static String offer(Rules rules, Set<UUID> customerSegments, BigDecimal score) {
        if (rules.minScore() != null || rules.maxScore() != null) {
            if (score == null) {
                return "score_required";
            }
            if (rules.minScore() != null && score.compareTo(rules.minScore()) < 0
                    || rules.maxScore() != null && score.compareTo(rules.maxScore()) > 0) {
                return "score_out_of_range";
            }
        }
        if (rules.needsSegments()) {
            return segments(customerSegments, rules.segmentIds(), rules.exclusionSegmentIds());
        }
        return null;
    }
}
