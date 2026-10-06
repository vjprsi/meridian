package io.meridian.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the lines and totals of a tenant invoice for one billing period. Pure: all inputs are resolved by the
 * caller (plan, negotiated overrides, usage aggregates). Amounts are rounded to 2 dp HALF_EVEN per line.
 */
public final class InvoiceCalculator {
    private InvoiceCalculator() {}

    public record Line(String metric, String description, long quantity, BigDecimal unitPrice, BigDecimal amount,
                       String tier) {}

    public record Adjustment(String description, BigDecimal amount) {}

    public record Input(
            String planCode,
            int periodMonths,
            BigDecimal platformFee,
            BigDecimal platformFeeCoverage,
            long includedActiveAccounts,
            BigDecimal pricePerActiveAccount,
            long peakActiveAccounts,
            Map<String, Long> usage,
            Map<String, TieredPrice> prices,
            BigDecimal discountRate,
            List<Adjustment> adjustments,
            BigDecimal committedMonthlySpend,
            BigDecimal taxRate) {}

    public record Result(List<Line> lines, BigDecimal subtotal, BigDecimal taxAmount, BigDecimal total) {}

    public static Result calculate(Input in) {
        List<Line> lines = new ArrayList<>();
        BigDecimal chargeable = BigDecimal.ZERO;

        if (in.platformFee().signum() > 0 && in.platformFeeCoverage().signum() > 0) {
            BigDecimal fee = in.platformFee().multiply(in.platformFeeCoverage()).setScale(2, RoundingMode.HALF_EVEN);
            String desc = "Platform fee (" + in.planCode() + ")"
                    + (in.platformFeeCoverage().compareTo(BigDecimal.ONE) < 0 ? ", prorated" : "");
            lines.add(new Line(null, desc, 1, fee, fee, null));
            chargeable = chargeable.add(fee);
        }

        long extraAccounts = Math.max(0, in.peakActiveAccounts() - in.includedActiveAccounts());
        if (extraAccounts > 0 && in.pricePerActiveAccount().signum() > 0) {
            BigDecimal amt = in.pricePerActiveAccount().multiply(BigDecimal.valueOf(extraAccounts))
                    .setScale(2, RoundingMode.HALF_EVEN);
            lines.add(new Line("ACTIVE_ACCOUNT", "Active accounts above " + in.includedActiveAccounts() + " included (peak "
                    + in.peakActiveAccounts() + ")", extraAccounts, in.pricePerActiveAccount(), amt, null));
            chargeable = chargeable.add(amt);
        }

        for (String metric : UsageMetrics.ALL) {
            Long qty = in.usage().get(metric);
            TieredPrice price = in.prices().get(metric);
            if (qty == null || qty <= 0 || price == null || "ACTIVE_ACCOUNT".equals(metric)) {
                continue;
            }
            for (TieredPrice.TierCharge c : price.charge(qty)) {
                lines.add(new Line(metric, metric + " usage, " + c.label(), c.quantity(), c.unitPrice(), c.amount(),
                        c.label()));
                chargeable = chargeable.add(c.amount());
            }
        }

        BigDecimal subtotal = chargeable;
        if (in.discountRate() != null && in.discountRate().signum() > 0 && chargeable.signum() > 0) {
            BigDecimal discount = chargeable.multiply(in.discountRate()).setScale(2, RoundingMode.HALF_EVEN).negate();
            lines.add(new Line(null, "Negotiated discount " + in.discountRate().stripTrailingZeros().toPlainString(),
                    1, discount, discount, null));
            subtotal = subtotal.add(discount);
        }

        for (Adjustment a : in.adjustments()) {
            BigDecimal amt = a.amount().setScale(2, RoundingMode.HALF_EVEN);
            lines.add(new Line(null, a.description(), 1, amt, amt, null));
            subtotal = subtotal.add(amt);
        }

        if (in.committedMonthlySpend() != null && in.committedMonthlySpend().signum() > 0) {
            BigDecimal commitment = in.committedMonthlySpend().multiply(BigDecimal.valueOf(in.periodMonths()))
                    .multiply(in.platformFeeCoverage()).setScale(2, RoundingMode.HALF_EVEN);
            if (subtotal.compareTo(commitment) < 0) {
                BigDecimal topUp = commitment.subtract(subtotal);
                lines.add(new Line(null, "Minimum commitment top-up to " + commitment.toPlainString(), 1, topUp, topUp,
                        null));
                subtotal = commitment;
            }
        }

        BigDecimal tax = subtotal.signum() > 0 && in.taxRate() != null
                ? subtotal.multiply(in.taxRate()).setScale(2, RoundingMode.HALF_EVEN)
                : BigDecimal.ZERO.setScale(2);
        return new Result(List.copyOf(lines), subtotal.setScale(2, RoundingMode.HALF_EVEN), tax,
                subtotal.add(tax).setScale(2, RoundingMode.HALF_EVEN));
    }
}
