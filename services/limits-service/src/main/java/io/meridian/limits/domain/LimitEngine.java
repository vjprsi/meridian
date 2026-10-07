package io.meridian.limits.domain;

import io.meridian.limits.domain.LimitModel.Applicable;
import io.meridian.limits.domain.LimitModel.CheckRequest;
import io.meridian.limits.domain.LimitModel.Limit;
import io.meridian.limits.domain.LimitModel.Rule;
import io.meridian.limits.domain.LimitModel.RuleResult;
import io.meridian.limits.domain.LimitModel.Selection;
import io.meridian.limits.domain.LimitModel.Superseded;
import io.meridian.limits.domain.LimitModel.Usage;
import io.meridian.limits.domain.LimitModel.Window;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Limit selection and arithmetic.
 *
 * <p>Selection: a rule matches when its scope, transaction type, channel and currency fit the check. Matching rules
 * compete within a group (counting subject kind, period, measure, transaction type, channel): the most specific
 * scope wins, then the lowest priority number. Regulatory rules always apply and are never superseded.
 *
 * <p>Arithmetic: a counted rule is breached when used + reserved + this transaction exceeds the limit; a
 * PER_TRANSACTION rule when the amount alone exceeds it; a BALANCE rule when the balance after the transaction would.
 */
public final class LimitEngine {
    private LimitEngine() {}

    public static Selection select(List<Rule> rules, CheckRequest req) {
        Map<String, List<Applicable>> groups = new LinkedHashMap<>();
        List<Applicable> regulatory = new ArrayList<>();
        List<Rule> mismatch = new ArrayList<>();
        for (Rule r : rules) {
            if (!matches(r, req)) {
                continue;
            }
            if (!"COUNT".equals(r.measure()) && r.currency() != null && !r.currency().equals(req.currency())) {
                mismatch.add(r);
                continue;
            }
            boolean instrument = LimitModel.INSTRUMENT_SCOPES.contains(r.scope());
            String countingType = instrument ? r.scope() : "CUSTOMER";
            UUID countingId = instrument ? req.subjectId()
                    : req.customerId() != null ? req.customerId() : req.subjectId();
            if (!instrument && req.customerId() == null) {
                countingType = req.subjectType();
            }
            Applicable a = new Applicable(r, countingType, countingId);
            if (r.regulatory()) {
                regulatory.add(a);
                continue;
            }
            String key = (instrument ? r.scope() : "PARTY") + "|" + r.period() + "|" + r.measure() + "|"
                    + r.transactionType() + "|" + r.channel();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        List<Applicable> effective = new ArrayList<>(regulatory);
        List<Superseded> superseded = new ArrayList<>();
        Comparator<Applicable> order = Comparator.<Applicable>comparingInt(a -> -a.rule().specificity())
                .thenComparingInt(a -> a.rule().priority())
                .thenComparing(a -> a.rule().code());
        for (List<Applicable> g : groups.values()) {
            g.sort(order);
            Applicable winner = g.getFirst();
            effective.add(winner);
            for (int i = 1; i < g.size(); i++) {
                superseded.add(new Superseded(g.get(i).rule(), winner.rule()));
            }
        }
        effective.sort(Comparator.comparing(a -> a.rule().code()));
        return new Selection(List.copyOf(effective), List.copyOf(superseded), List.copyOf(mismatch));
    }

    static boolean matches(Rule r, CheckRequest req) {
        if (r.transactionType() != null && !r.transactionType().equals(req.transactionType())) {
            return false;
        }
        if (r.channel() != null && !r.channel().equals(req.channel())) {
            return false;
        }
        String ref = r.scopeRef();
        return switch (r.scope()) {
            case "GLOBAL", "TENANT" -> true;
            case "CUSTOMER" -> req.customerId() != null && req.customerId().toString().equalsIgnoreCase(ref);
            case "KYC_TIER" -> req.kycTier() != null && req.kycTier().equalsIgnoreCase(ref);
            case "CUSTOMER_SEGMENT" -> req.customerSegment() != null && req.customerSegment().equalsIgnoreCase(ref);
            case "PRODUCT" -> req.productId() != null && req.productId().toString().equalsIgnoreCase(ref);
            default -> r.scope().equals(req.subjectType()) && req.subjectId().toString().equalsIgnoreCase(ref);
        };
    }

    /** The period window containing {@code now} (UTC calendar periods; rolling periods use hourly buckets). */
    public static Window window(String period, Instant now) {
        return window(period, now, now.atZone(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * The period window for a transaction at {@code now} on business date {@code today}: calendar periods (daily,
     * weekly, monthly, yearly) follow the business date, so a transaction booked while the previous business day is
     * still open counts towards that day; rolling periods use hourly buckets of the instant.
     */
    public static Window window(String period, Instant now, LocalDate today) {
        ZonedDateTime t = now.atZone(ZoneOffset.UTC);
        return switch (period) {
            case "DAILY" -> {
                Instant s = today.atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Window(s, s.plus(Duration.ofDays(1)), false, s);
            }
            case "WEEKLY" -> {
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                Instant s = monday.atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Window(s, s.plus(Duration.ofDays(7)), false, s);
            }
            case "MONTHLY" -> {
                LocalDate first = today.withDayOfMonth(1);
                Instant s = first.atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Window(s, first.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant(), false, s);
            }
            case "YEARLY" -> {
                LocalDate first = today.withDayOfYear(1);
                Instant s = first.atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Window(s, first.plusYears(1).atStartOfDay(ZoneOffset.UTC).toInstant(), false, s);
            }
            case "LIFETIME" -> {
                Instant s = Instant.parse("2000-01-01T00:00:00Z");
                yield new Window(s, Instant.parse("9999-01-01T00:00:00Z"), false, s);
            }
            case "ROLLING_24H", "ROLLING_7D", "ROLLING_30D" -> {
                Instant bucket = t.truncatedTo(ChronoUnit.HOURS).toInstant();
                long hours = switch (period) {
                    case "ROLLING_24H" -> 24;
                    case "ROLLING_7D" -> 24 * 7;
                    default -> 24 * 30;
                };
                // the current bucket plus the previous (hours - 1) full buckets
                yield new Window(bucket, bucket.plus(Duration.ofHours(1)), true, bucket.minus(Duration.ofHours(hours - 1)));
            }
            default -> new Window(now, now, false, now); // PER_TRANSACTION: no counter
        };
    }

    /**
     * Evaluates one rule. {@code usage} is everything already counted in the period (committed + reserved) and
     * excludes this transaction.
     */
    public static RuleResult evaluate(Rule r, Limit limit, Usage usage, BigDecimal amount, BigDecimal currentBalance) {
        BigDecimal maxAmount = limit.maxAmount();
        Integer maxCount = limit.maxCount();
        boolean breached = false;
        BigDecimal remainingAmount = null;
        Long remainingCount = null;
        if ("BALANCE".equals(r.measure())) {
            BigDecimal after = (currentBalance == null ? BigDecimal.ZERO : currentBalance).add(amount);
            if (maxAmount != null) {
                breached = after.compareTo(maxAmount) > 0;
                remainingAmount = nonNegative(maxAmount.subtract(after));
            }
        } else if ("PER_TRANSACTION".equals(r.period())) {
            if (maxAmount != null) {
                breached = amount.compareTo(maxAmount) > 0;
                remainingAmount = nonNegative(maxAmount.subtract(amount));
            }
        } else {
            BigDecimal consumed = usage.usedAmount().add(usage.reservedAmount());
            long consumedCount = usage.usedCount() + usage.reservedCount();
            if (maxAmount != null && !"COUNT".equals(r.measure())) {
                BigDecimal after = consumed.add(amount);
                breached = after.compareTo(maxAmount) > 0;
                remainingAmount = nonNegative(maxAmount.subtract(after));
            }
            if (maxCount != null) {
                long after = consumedCount + 1;
                breached = breached || after > maxCount;
                remainingCount = Math.max(0, maxCount - after);
            }
        }
        return new RuleResult(r, breached, remainingAmount, remainingCount, maxAmount, maxCount, usage, limit.overrideId());
    }

    /** Strictest breach action: REJECT > STEP_UP > REVIEW > ALLOW_AND_ALERT; null when nothing is breached. */
    public static String strictest(List<RuleResult> results) {
        List<String> order = List.of("ALLOW_AND_ALERT", "REVIEW", "STEP_UP", "REJECT");
        String worst = null;
        for (RuleResult r : results) {
            if (r.breached() && (worst == null || order.indexOf(r.rule().breachAction()) > order.indexOf(worst))) {
                worst = r.rule().breachAction();
            }
        }
        return worst;
    }

    /** Allowed when nothing is breached or every breach only alerts. */
    public static boolean allowed(String strictest) {
        return strictest == null || "ALLOW_AND_ALERT".equals(strictest);
    }

    public static Limit limit(Rule r, BigDecimal overrideAmount, Integer overrideCount, UUID overrideId) {
        if (overrideId == null || r.regulatory()) {
            return new Limit(r.maxAmount(), r.maxCount(), null);
        }
        return new Limit(overrideAmount != null ? overrideAmount : r.maxAmount(),
                overrideCount != null ? overrideCount : r.maxCount(), overrideId);
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
