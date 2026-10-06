package io.meridian.collections.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an arrears amount into plan instalments: equal amounts rounded down to the currency's minor unit, the
 * rounding remainder added to the last instalment so the instalments always sum exactly to the total.
 */
public final class PaymentPlanCalculator {
    private PaymentPlanCalculator() {}

    /** Policy: longer plans or plans covering less than the arrears need a second approver. */
    public static final int MAX_INSTALMENTS_IN_POLICY = 12;
    public static final int MAX_INSTALMENTS = 60;

    public record Instalment(int no, LocalDate dueDate, BigDecimal amount) {}

    public static List<Instalment> instalments(BigDecimal total, String currency, int count, String frequency,
                                               LocalDate start) {
        if (count < 1 || count > MAX_INSTALMENTS) {
            throw ApiException.unprocessable("invalid_installment_count",
                    "installment_count must be between 1 and " + MAX_INSTALMENTS);
        }
        if (total == null || total.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "The plan total must be positive");
        }
        int digits = Currencies.digits(currency);
        BigDecimal rounded = total.setScale(digits, RoundingMode.HALF_EVEN);
        BigDecimal each = rounded.divide(BigDecimal.valueOf(count), digits, RoundingMode.DOWN);
        if (each.signum() == 0) {
            throw ApiException.unprocessable("invalid_installment_count", "Instalments would be zero; use fewer instalments");
        }
        List<Instalment> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) {
            BigDecimal amount = i == count ? rounded.subtract(sum) : each;
            sum = sum.add(amount);
            out.add(new Instalment(i, dueDate(start, frequency, i - 1), amount));
        }
        return out;
    }

    public static LocalDate dueDate(LocalDate start, String frequency, int periods) {
        return switch (frequency) {
            case "WEEKLY" -> start.plusWeeks(periods);
            case "FORTNIGHTLY" -> start.plusWeeks(2L * periods);
            case "MONTHLY" -> start.plusMonths(periods);
            case "QUARTERLY" -> start.plusMonths(3L * periods);
            default -> throw ApiException.unprocessable("invalid_frequency",
                    "Payment plans support WEEKLY, FORTNIGHTLY, MONTHLY or QUARTERLY instalments");
        };
    }

    /** True when the plan is outside policy (too long, or not covering the full arrears). */
    public static boolean outsidePolicy(int count, BigDecimal total, BigDecimal overdue) {
        return count > MAX_INSTALMENTS_IN_POLICY || (overdue != null && total.compareTo(overdue) < 0);
    }
}
