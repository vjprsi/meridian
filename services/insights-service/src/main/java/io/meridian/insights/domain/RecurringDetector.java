package io.meridian.insights.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Cadence detection over a merchant's charge history (one merchant, one currency, one direction).
 *
 * <p>The median interval between consecutive charges is classified into a frequency band (WEEKLY 6-8 days,
 * BIWEEKLY 13-15, MONTHLY 27-33, QUARTERLY 85-95, YEARLY 355-375). A series is recurring when at least 75% of the
 * intervals fall in that band and at least 75% of the amounts are within ±15% of the median amount, with at least
 * three charges (two for quarterly and yearly cadences).
 */
public final class RecurringDetector {
    private RecurringDetector() {}

    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.15");
    /** A known series accepts a new charge within this tolerance of its typical amount (price changes included). */
    public static final BigDecimal LINK_TOLERANCE = new BigDecimal("0.30");
    /** A difference above this share of the last amount is reported as a price change. */
    public static final BigDecimal PRICE_CHANGE_MIN = new BigDecimal("0.02");
    private static final double MIN_SHARE = 0.75;

    public record Charge(LocalDate date, BigDecimal amount) {}

    public record Detection(String frequency, BigDecimal typicalAmount, BigDecimal lastAmount, BigDecimal previousAmount,
                            LocalDate firstSeen, LocalDate lastSeen, LocalDate nextExpected, int occurrences,
                            BigDecimal confidence, boolean priceChanged) {}

    enum Band {
        WEEKLY(6, 8, 3), BIWEEKLY(13, 15, 3), MONTHLY(27, 33, 3), QUARTERLY(85, 95, 2), YEARLY(355, 375, 2);
        final int min;
        final int max;
        final int minOccurrences;

        Band(int min, int max, int minOccurrences) {
            this.min = min;
            this.max = max;
            this.minOccurrences = minOccurrences;
        }

        boolean holds(long days) {
            return days >= min && days <= max;
        }
    }

    public static Optional<Detection> detect(List<Charge> history) {
        List<Charge> charges = new ArrayList<>(history.stream()
                .filter(c -> c.date() != null && c.amount() != null && c.amount().signum() != 0)
                .map(c -> new Charge(c.date(), c.amount().abs()))
                .sorted(Comparator.comparing(Charge::date))
                .toList());
        if (charges.size() < 2) {
            return Optional.empty();
        }
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < charges.size(); i++) {
            intervals.add(ChronoUnit.DAYS.between(charges.get(i - 1).date(), charges.get(i).date()));
        }
        long medianInterval = medianLong(intervals);
        Band band = null;
        for (Band b : Band.values()) {
            if (b.holds(medianInterval)) {
                band = b;
            }
        }
        if (band == null || charges.size() < band.minOccurrences) {
            return Optional.empty();
        }
        final Band f = band;
        double intervalShare = intervals.stream().filter(f::holds).count() / (double) intervals.size();
        if (intervalShare < MIN_SHARE) {
            return Optional.empty();
        }
        List<BigDecimal> amounts = charges.stream().map(Charge::amount).toList();
        BigDecimal median = median(amounts);
        double amountShare = amounts.stream().filter(a -> within(median, a, AMOUNT_TOLERANCE)).count() / (double) amounts.size();
        if (amountShare < MIN_SHARE) {
            return Optional.empty();
        }
        Charge last = charges.getLast();
        BigDecimal previous = charges.get(charges.size() - 2).amount();
        boolean priceChanged = changed(previous, last.amount());
        double volume = Math.min(1.0, charges.size() / 4.0);
        BigDecimal confidence = BigDecimal.valueOf((0.5 * intervalShare + 0.5 * amountShare) * (0.6 + 0.4 * volume))
                .setScale(4, RoundingMode.HALF_EVEN);
        return Optional.of(new Detection(f.name(), median, last.amount(), priceChanged ? previous : null,
                charges.getFirst().date(), last.date(), next(f.name(), last.date()), charges.size(), confidence,
                priceChanged));
    }

    /** The expected date of the charge after {@code last}. */
    public static LocalDate next(String frequency, LocalDate last) {
        return switch (frequency) {
            case "WEEKLY" -> last.plusWeeks(1);
            case "BIWEEKLY" -> last.plusWeeks(2);
            case "QUARTERLY" -> last.plusMonths(3);
            case "YEARLY" -> last.plusYears(1);
            case "IRREGULAR" -> last.plusMonths(1);
            default -> last.plusMonths(1);
        };
    }

    /** Days after the expected date before a missing charge retires the series. */
    public static int graceDays(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> 4;
            case "BIWEEKLY" -> 5;
            case "QUARTERLY" -> 20;
            case "YEARLY" -> 30;
            default -> 10;
        };
    }

    public static boolean within(BigDecimal reference, BigDecimal amount, BigDecimal tolerance) {
        if (reference == null || reference.signum() == 0) {
            return amount != null && amount.signum() == 0;
        }
        BigDecimal diff = amount.abs().subtract(reference.abs()).abs();
        return diff.compareTo(reference.abs().multiply(tolerance)) <= 0;
    }

    /** True when the amount moved by more than {@link #PRICE_CHANGE_MIN} of the previous amount. */
    public static boolean changed(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null || previous.signum() == 0) {
            return false;
        }
        return current.abs().subtract(previous.abs()).abs().compareTo(previous.abs().multiply(PRICE_CHANGE_MIN)) > 0;
    }

    public static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> s = values.stream().sorted().toList();
        int n = s.size();
        if (n == 0) {
            return BigDecimal.ZERO;
        }
        return n % 2 == 1 ? s.get(n / 2)
                : s.get(n / 2 - 1).add(s.get(n / 2)).divide(BigDecimal.TWO, MathContext.DECIMAL128);
    }

    private static long medianLong(List<Long> values) {
        List<Long> s = values.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : Math.round((s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
    }
}
