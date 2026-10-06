package io.meridian.cardauthorization.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A compiled authorisation rule (program-specific when {@code programId} is set, else tenant-wide). */
public record AuthRule(UUID id, UUID programId, String name, int priority, Expression condition, String action,
                       String declineReason, boolean active, OffsetDateTime validFrom, OffsetDateTime validTo) {

    public boolean effectiveAt(OffsetDateTime now) {
        return active && (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || now.isBefore(validTo));
    }

    /** Rules that look at the fraud score run after fraud scoring; the others before it. */
    public boolean needsFraudScore() {
        return condition.identifiers().contains("fraud_score");
    }
}
