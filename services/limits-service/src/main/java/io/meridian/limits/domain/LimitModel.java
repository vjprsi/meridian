package io.meridian.limits.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Value types of the limits engine (no Spring, no SQL). */
public final class LimitModel {
    private LimitModel() {}

    /** Instrument scopes are counted per instrument; all other scopes are counted per customer ("party"). */
    public static final Set<String> INSTRUMENT_SCOPES = Set.of("ACCOUNT", "CARD", "WALLET", "AGENT", "MERCHANT");

    public record Rule(UUID id, String code, String scope, String scopeRef, String transactionType, String channel,
                       String period, String measure, String currency, BigDecimal maxAmount, Integer maxCount,
                       String breachAction, int priority, boolean regulatory) {
        public boolean counted() {
            return !"PER_TRANSACTION".equals(period) && !"BALANCE".equals(measure);
        }

        /** Higher = more specific. Scope dominates; a transaction type or channel qualifier refines it. */
        public int specificity() {
            int base = switch (scope) {
                case "ACCOUNT", "CARD", "WALLET", "AGENT", "MERCHANT" -> 6;
                case "CUSTOMER" -> 5;
                case "CUSTOMER_SEGMENT" -> 4;
                case "KYC_TIER", "PRODUCT" -> 3;
                case "TENANT" -> 1;
                default -> 0;
            };
            return base * 4 + (transactionType != null ? 2 : 0) + (channel != null ? 1 : 0);
        }
    }

    public record CheckRequest(String subjectType, UUID subjectId, UUID customerId, String kycTier, String customerSegment,
                               UUID productId, String transactionType, String channel, BigDecimal amount, String currency,
                               BigDecimal currentBalance) {}

    /** A rule that applies to a check, with the subject its usage is counted against. */
    public record Applicable(Rule rule, String countingSubjectType, UUID countingSubjectId) {}

    public record Superseded(Rule rule, Rule by) {}

    public record Selection(List<Applicable> effective, List<Superseded> superseded, List<Rule> currencyMismatch) {}

    /** Period window: [start, end). Rolling periods count hourly buckets from lookbackStart up to now. */
    public record Window(Instant start, Instant end, boolean rolling, Instant lookbackStart) {}

    /** Effective limit after any approved override. */
    public record Limit(BigDecimal maxAmount, Integer maxCount, UUID overrideId) {}

    public record Usage(BigDecimal usedAmount, BigDecimal reservedAmount, long usedCount, long reservedCount) {
        public static final Usage ZERO = new Usage(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(usedAmount.add(o.usedAmount), reservedAmount.add(o.reservedAmount),
                    usedCount + o.usedCount, reservedCount + o.reservedCount);
        }
    }

    /** Outcome for one rule. Remaining values are what is left after this transaction (never negative). */
    public record RuleResult(Rule rule, boolean breached, BigDecimal remainingAmount, Long remainingCount,
                             BigDecimal limitAmount, Integer limitCount, Usage usage, UUID overrideId) {}
}
