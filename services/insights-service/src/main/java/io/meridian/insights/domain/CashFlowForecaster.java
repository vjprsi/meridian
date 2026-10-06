package io.meridian.insights.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic cash-flow forecast: the starting balance rolled forward day by day with (1) the expected charges of
 * known recurring series (projected from their next expected date at their cadence) and (2) the trailing daily
 * average of non-recurring net flows. The same inputs always give the same projection.
 */
public final class CashFlowForecaster {
    private CashFlowForecaster() {}

    public static final String MODEL_VERSION = "cff-deterministic-1";

    /** A recurring series; {@code amount} is signed (negative = outflow). */
    public record Series(String name, String frequency, BigDecimal amount, LocalDate nextExpected) {}

    public record Item(String name, BigDecimal amount) {}

    public record Point(LocalDate date, BigDecimal balance, BigDecimal inflows, BigDecimal outflows, List<Item> items) {}

    public record Forecast(BigDecimal startingBalance, BigDecimal lowestBalance, LocalDate lowestBalanceDate,
                           BigDecimal expectedIncome, BigDecimal expectedBills, BigDecimal expectedDiscretionary,
                           List<Point> points) {}

    /** Average daily non-recurring net flow over a trailing window (signed, 2 decimals). */
    public static BigDecimal dailyAverage(BigDecimal netOverWindow, int windowDays) {
        if (netOverWindow == null || windowDays <= 0) {
            return BigDecimal.ZERO;
        }
        return netOverWindow.divide(BigDecimal.valueOf(windowDays), MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Forecast project(LocalDate asOf, int horizonDays, BigDecimal startingBalance, List<Series> series,
                                   BigDecimal dailyDiscretionary) {
        BigDecimal daily = dailyDiscretionary == null ? BigDecimal.ZERO : dailyDiscretionary;
        BigDecimal balance = startingBalance;
        BigDecimal lowest = startingBalance;
        LocalDate lowestDate = asOf;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal bills = BigDecimal.ZERO;
        LocalDate end = asOf.plusDays(horizonDays);
        List<List<Item>> perDay = new ArrayList<>();
        for (int d = 0; d < horizonDays; d++) {
            perDay.add(new ArrayList<>());
        }
        for (Series s : series) {
            if (s.nextExpected() == null || s.amount() == null || s.amount().signum() == 0) {
                continue;
            }
            LocalDate due = s.nextExpected();
            int guard = 0;
            while (!due.isAfter(asOf) && guard++ < 1000) {
                due = RecurringDetector.next(s.frequency(), due);
            }
            while (!due.isAfter(end) && guard++ < 2000) {
                int idx = (int) java.time.temporal.ChronoUnit.DAYS.between(asOf, due) - 1;
                perDay.get(idx).add(new Item(s.name(), s.amount()));
                due = RecurringDetector.next(s.frequency(), due);
            }
        }
        List<Point> points = new ArrayList<>();
        for (int d = 0; d < horizonDays; d++) {
            LocalDate date = asOf.plusDays(d + 1);
            BigDecimal in = BigDecimal.ZERO;
            BigDecimal out = BigDecimal.ZERO;
            for (Item it : perDay.get(d)) {
                if (it.amount().signum() > 0) {
                    in = in.add(it.amount());
                    income = income.add(it.amount());
                } else {
                    out = out.add(it.amount().negate());
                    bills = bills.add(it.amount().negate());
                }
            }
            if (daily.signum() > 0) {
                in = in.add(daily);
            } else {
                out = out.add(daily.negate());
            }
            balance = balance.add(in).subtract(out);
            if (balance.compareTo(lowest) < 0) {
                lowest = balance;
                lowestDate = date;
            }
            points.add(new Point(date, scale(balance), scale(in), scale(out), List.copyOf(perDay.get(d))));
        }
        return new Forecast(scale(startingBalance), scale(lowest), lowestDate, scale(income), scale(bills),
                scale(daily.negate().multiply(BigDecimal.valueOf(horizonDays))), points);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }
}
