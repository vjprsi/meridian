package io.meridian.insights.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rules producing insight cards. Each candidate carries a business {@code dedupeKey}: the same key never produces a
 * second card for the customer (a spike is reported once per category and month, a price change once per new
 * amount, a new subscription once per series, a low balance once per account and date).
 */
public final class InsightRules {
    private InsightRules() {}

    public static final String VERSION = "insight-rules-1";
    public static final BigDecimal SPIKE_FACTOR = new BigDecimal("1.5");

    public record Candidate(String type, String severity, String title, String body, Map<String, Object> data,
                            String relatedType, UUID relatedId, String dedupeKey, String actionLabel, String deeplink) {}

    /**
     * Spend in the last 30 days of a category vs the average of the previous 30-day windows (those with history).
     * A spike needs at least one previous window with spend, current > 1.5 x average and an excess of at least
     * {@code minExcess}.
     */
    public static Optional<Candidate> spendSpike(UUID categoryId, String categoryName, String currency, BigDecimal current,
                                                 List<BigDecimal> previousWindows, BigDecimal minExcess, LocalDate today) {
        List<BigDecimal> prev = previousWindows.stream().filter(v -> v != null && v.signum() > 0).toList();
        if (prev.isEmpty() || current == null || current.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal avg = prev.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(prev.size()), MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal excess = current.subtract(avg);
        if (current.compareTo(avg.multiply(SPIKE_FACTOR)) <= 0 || excess.compareTo(minExcess) < 0) {
            return Optional.empty();
        }
        BigDecimal pct = excess.multiply(BigDecimal.valueOf(100)).divide(avg, 0, RoundingMode.HALF_EVEN);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spend_category_id", categoryId == null ? null : categoryId.toString());
        data.put("category", categoryName);
        data.put("currency", currency);
        data.put("last_30_days", money(current));
        data.put("trailing_average", money(avg));
        data.put("increase_pct", pct.toPlainString());
        String name = categoryName == null ? "uncategorised spending" : categoryName;
        return Optional.of(new Candidate("SPEND_SPIKE", "WARNING", "Spending up on " + name,
                "You spent " + money(current) + " " + currency + " on " + name + " in the last 30 days, " + pct
                        + "% more than your usual " + money(avg) + " " + currency + ".",
                data, "SPEND_CATEGORY", categoryId,
                "SPEND_SPIKE:" + categoryId + ":" + currency + ":" + today.getYear() + "-" + today.getMonthValue(),
                "See spending", "meridian://insights/spending"));
    }

    public static Candidate priceChange(UUID recurringId, String merchant, String currency, BigDecimal previous,
                                        BigDecimal current) {
        boolean up = current.abs().compareTo(previous.abs()) > 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant", merchant);
        data.put("currency", currency);
        data.put("previous_amount", money(previous));
        data.put("new_amount", money(current));
        return new Candidate("SUBSCRIPTION_PRICE_CHANGE", up ? "WARNING" : "POSITIVE",
                merchant + " price " + (up ? "increase" : "decrease"),
                "Your " + merchant + " payment changed from " + money(previous) + " to " + money(current) + " " + currency + ".",
                data, "RECURRING_PAYMENT", recurringId, "SUB_PRICE:" + recurringId + ":" + money(current),
                "Review subscription", "meridian://insights/subscriptions/" + recurringId);
    }

    public static Candidate newSeries(UUID recurringId, String merchant, String frequency, String currency,
                                      BigDecimal amount, boolean income, LocalDate lastSeen) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant", merchant);
        data.put("frequency", frequency);
        data.put("currency", currency);
        data.put("amount", money(amount));
        if (income) {
            return new Candidate("SALARY_RECEIVED", "POSITIVE", "Regular income from " + merchant,
                    "We spotted a " + frequency.toLowerCase() + " payment of " + money(amount) + " " + currency + " from "
                            + merchant + ".", data, "RECURRING_PAYMENT", recurringId,
                    "SALARY:" + recurringId + ":" + lastSeen, null, null);
        }
        return new Candidate("NEW_SUBSCRIPTION", "INFO", "New subscription: " + merchant,
                "We spotted a " + frequency.toLowerCase() + " payment of " + money(amount) + " " + currency + " to "
                        + merchant + ".", data, "RECURRING_PAYMENT", recurringId, "NEW_SUB:" + recurringId,
                "Review subscription", "meridian://insights/subscriptions/" + recurringId);
    }

    public static Candidate lowBalance(UUID forecastId, UUID accountId, String currency, BigDecimal lowest,
                                       LocalDate lowestDate, BigDecimal threshold) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deposit_account_id", accountId.toString());
        data.put("cash_flow_forecast_id", forecastId.toString());
        data.put("currency", currency);
        data.put("lowest_balance", money(lowest));
        data.put("lowest_balance_date", lowestDate.toString());
        data.put("threshold", money(threshold));
        return new Candidate("LOW_BALANCE_FORECAST", lowest.signum() < 0 ? "CRITICAL" : "WARNING", "Low balance ahead",
                "Your balance may fall to " + money(lowest) + " " + currency + " around " + lowestDate + ".",
                data, "ACCOUNT", accountId, "LOW_BAL:" + accountId + ":" + lowestDate,
                "Move money", "meridian://accounts/" + accountId);
    }

    public static Candidate budget(boolean exceeded, UUID periodId, UUID budgetId, String budgetName, String currency,
                                   BigDecimal spent, BigDecimal budgetAmount, LocalDate periodStart) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("budget_id", budgetId.toString());
        data.put("budget_period_id", periodId.toString());
        data.put("currency", currency);
        data.put("spent", money(spent));
        data.put("budget", money(budgetAmount));
        data.put("period_start", periodStart.toString());
        return exceeded
                ? new Candidate("BUDGET_EXCEEDED", "CRITICAL", budgetName + " budget exceeded",
                        "You have spent " + money(spent) + " of your " + money(budgetAmount) + " " + currency + " budget.",
                        data, "BUDGET_PERIOD", periodId, "BUDGET_EXCEEDED:" + periodId, "View budget",
                        "meridian://insights/budgets/" + budgetId)
                : new Candidate("BUDGET_NEAR_LIMIT", "WARNING", budgetName + " budget nearly used",
                        "You have spent " + money(spent) + " of your " + money(budgetAmount) + " " + currency + " budget.",
                        data, "BUDGET_PERIOD", periodId, "BUDGET_NEAR_LIMIT:" + periodId, "View budget",
                        "meridian://insights/budgets/" + budgetId);
    }

    public static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
