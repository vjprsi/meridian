package io.meridian.insights.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Budget period arithmetic. Periods are anchored on the budget's {@code starts_on}: period k starts at
 * {@code starts_on + k * period} (weeks or months; month arithmetic clamps to the month end) and ends the day before
 * period k + 1 starts.
 */
public final class BudgetPeriods {
    private BudgetPeriods() {}

    public record Period(LocalDate start, LocalDate end) {
        public boolean contains(LocalDate d) {
            return !d.isBefore(start) && !d.isAfter(end);
        }
    }

    public static LocalDate startOf(LocalDate startsOn, String type, long k) {
        return switch (type) {
            case "WEEKLY" -> startsOn.plusWeeks(k);
            case "QUARTERLY" -> startsOn.plusMonths(3 * k);
            case "YEARLY" -> startsOn.plusYears(k);
            default -> startsOn.plusMonths(k);
        };
    }

    /** The period containing {@code date}; empty before the first period. */
    public static Optional<Period> containing(LocalDate startsOn, String type, LocalDate date) {
        if (date.isBefore(startsOn)) {
            return Optional.empty();
        }
        long k = switch (type) {
            case "WEEKLY" -> ChronoUnit.WEEKS.between(startsOn, date);
            case "QUARTERLY" -> ChronoUnit.MONTHS.between(startsOn, date) / 3;
            case "YEARLY" -> ChronoUnit.YEARS.between(startsOn, date);
            default -> ChronoUnit.MONTHS.between(startsOn, date);
        };
        k = Math.max(0, k - 1);
        while (!startOf(startsOn, type, k + 1).isAfter(date)) {
            k++;
        }
        return Optional.of(new Period(startOf(startsOn, type, k), startOf(startsOn, type, k + 1).minusDays(1)));
    }

    /** The period that follows {@code p}. */
    public static Period next(LocalDate startsOn, String type, Period p) {
        return containing(startsOn, type, p.end().plusDays(1)).orElseThrow();
    }

    /** Budget amount of a period: the base amount plus the unspent remainder carried over (never negative). */
    public static BigDecimal withRollover(BigDecimal amount, boolean rollover, BigDecimal previousRemaining) {
        if (!rollover || previousRemaining == null || previousRemaining.signum() <= 0) {
            return amount;
        }
        return amount.add(previousRemaining);
    }

    public enum Alert { NONE, THRESHOLD, EXCEEDED }

    /** Highest alert level reached by the spend: EXCEEDED above the budget, THRESHOLD at or above the share. */
    public static Alert level(BigDecimal spent, BigDecimal budget, BigDecimal thresholdPct) {
        if (budget == null || budget.signum() <= 0) {
            return spent.signum() > 0 ? Alert.EXCEEDED : Alert.NONE;
        }
        if (spent.compareTo(budget) > 0) {
            return Alert.EXCEEDED;
        }
        if (spent.compareTo(budget.multiply(thresholdPct)) >= 0 && spent.signum() > 0) {
            return Alert.THRESHOLD;
        }
        return Alert.NONE;
    }
}
