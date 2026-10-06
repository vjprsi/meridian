package io.meridian.productcatalog.domain.fees;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fee calculation (dry run and simulation):
 * <ol>
 *   <li>gross by method: FLAT, PERCENTAGE, FLAT_PLUS_PERCENTAGE, TIERED_FLAT / TIERED_PERCENTAGE (the tier containing
 *       the base amount prices the whole amount), PER_UNIT (base amount = number of units)</li>
 *   <li>min / max per occurrence</li>
 *   <li>free allowance ({@code occurrence_no <= free_count}) waives fully</li>
 *   <li>waiver rules: the best (largest) discount among the rules whose condition holds</li>
 *   <li>period cap: what was already charged in the period limits this charge</li>
 *   <li>round HALF_EVEN to the currency's minor unit, then tax = net x tax rate, rounded the same way</li>
 * </ol>
 */
public final class FeeCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;

    private FeeCalculator() {}

    public record Tier(int tierNo, BigDecimal fromValue, BigDecimal toValue, BigDecimal flatAmount, BigDecimal percentage) {
        boolean contains(BigDecimal v) {
            return v.compareTo(fromValue) >= 0 && (toValue == null || v.compareTo(toValue) < 0);
        }
    }

    public record Waiver(UUID id, String condition, BigDecimal thresholdAmount, Integer thresholdCount, String segment,
                         BigDecimal discountPercentage, String expression, LocalDate validFrom, LocalDate validTo) {}

    public record Fee(UUID id, String code, String calculationMethod, BigDecimal flatAmount, BigDecimal percentage,
                      BigDecimal minAmount, BigDecimal maxAmount, BigDecimal capPerPeriod, int freeCount, String currency,
                      LocalDate effectiveFrom, LocalDate effectiveTo, List<Tier> tiers, List<Waiver> waivers) {
        public Fee {
            tiers = tiers == null ? List.of() : tiers.stream().sorted(Comparator.comparing(Tier::fromValue)).toList();
            waivers = waivers == null ? List.of() : List.copyOf(waivers);
        }
    }

    /** Facts about the customer/account used by waiver rules; every field is optional. */
    public record Context(LocalDate asOf, Integer occurrenceNo, BigDecimal balance, BigDecimal averageBalance,
                          BigDecimal salaryCredit, Integer transactionCount, String segment, Boolean bundleMember,
                          String promotionCode, Boolean manualWaiver, BigDecimal chargedInPeriod) {
        public static Context of(LocalDate asOf) {
            return new Context(asOf, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record Tax(UUID taxRuleId, String code, BigDecimal rate, BigDecimal amount) {}

    public record Result(BigDecimal baseAmount, BigDecimal grossAmount, boolean minApplied, boolean maxApplied,
                         Integer tierNo, boolean freeAllowanceApplied, Waiver waiverApplied, BigDecimal discountAmount,
                         boolean capApplied, BigDecimal netAmount, Tax tax, BigDecimal totalAmount, String currency,
                         List<String> notes) {}

    public static int minorUnits(String currency) {
        return Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
    }

    public static Result calculate(Fee f, BigDecimal baseAmount, Context ctx, Tax taxRule) {
        if (baseAmount == null || baseAmount.signum() < 0) {
            throw ApiException.unprocessable("invalid_base_amount", "base_amount must be zero or positive");
        }
        LocalDate asOf = ctx.asOf() == null ? LocalDate.now() : ctx.asOf();
        if (f.effectiveFrom() != null && asOf.isBefore(f.effectiveFrom())
                || f.effectiveTo() != null && asOf.isAfter(f.effectiveTo())) {
            throw ApiException.unprocessable("fee_not_effective", "Fee " + f.code() + " is not effective on " + asOf);
        }
        int scale = minorUnits(f.currency());
        List<String> notes = new ArrayList<>();
        Integer tierNo = null;
        BigDecimal gross;
        switch (f.calculationMethod()) {
            case "FLAT" -> gross = need(f.flatAmount(), f, "flat_amount");
            case "PERCENTAGE" -> gross = baseAmount.multiply(need(f.percentage(), f, "percentage"), MC);
            case "FLAT_PLUS_PERCENTAGE" -> gross = need(f.flatAmount(), f, "flat_amount")
                    .add(baseAmount.multiply(need(f.percentage(), f, "percentage"), MC), MC);
            case "TIERED_FLAT", "TIERED_PERCENTAGE" -> {
                Tier t = f.tiers().stream().filter(x -> x.contains(baseAmount)).findFirst()
                        .orElseThrow(() -> ApiException.unprocessable("no_matching_tier",
                                "No tier of fee " + f.code() + " covers " + baseAmount.toPlainString()));
                tierNo = t.tierNo();
                BigDecimal flat = t.flatAmount() == null ? BigDecimal.ZERO : t.flatAmount();
                BigDecimal pct = t.percentage() == null ? BigDecimal.ZERO : t.percentage();
                gross = "TIERED_FLAT".equals(f.calculationMethod()) && t.percentage() == null
                        ? flat : flat.add(baseAmount.multiply(pct, MC), MC);
            }
            case "PER_UNIT" -> gross = need(f.flatAmount(), f, "flat_amount").multiply(baseAmount, MC);
            default -> throw ApiException.unprocessable("unsupported_calculation_method",
                    "Unsupported calculation method " + f.calculationMethod());
        }
        boolean minApplied = false;
        boolean maxApplied = false;
        if (f.minAmount() != null && gross.signum() > 0 && gross.compareTo(f.minAmount()) < 0) {
            gross = f.minAmount();
            minApplied = true;
        }
        if (f.maxAmount() != null && gross.compareTo(f.maxAmount()) > 0) {
            gross = f.maxAmount();
            maxApplied = true;
        }
        gross = gross.setScale(scale, RoundingMode.HALF_EVEN);

        BigDecimal net = gross;
        boolean free = ctx.occurrenceNo() != null && ctx.occurrenceNo() >= 1 && ctx.occurrenceNo() <= f.freeCount();
        Waiver applied = null;
        BigDecimal discount = BigDecimal.ZERO.setScale(scale);
        if (free) {
            discount = gross;
            net = BigDecimal.ZERO.setScale(scale);
            notes.add("occurrence " + ctx.occurrenceNo() + " is within the free allowance of " + f.freeCount());
        } else {
            for (Waiver w : f.waivers()) {
                if (applies(w, ctx, asOf) && (applied == null || pct(w).compareTo(pct(applied)) > 0)) {
                    applied = w;
                }
            }
            if (applied != null) {
                discount = gross.multiply(pct(applied), MC).setScale(scale, RoundingMode.HALF_EVEN);
                net = gross.subtract(discount);
                notes.add("waiver " + applied.condition() + " applied (" + pct(applied).toPlainString() + ")");
            }
        }
        boolean capApplied = false;
        if (f.capPerPeriod() != null && ctx.chargedInPeriod() != null) {
            BigDecimal room = f.capPerPeriod().subtract(ctx.chargedInPeriod()).max(BigDecimal.ZERO);
            if (net.compareTo(room) > 0) {
                net = room.setScale(scale, RoundingMode.HALF_EVEN);
                capApplied = true;
                notes.add("period cap " + f.capPerPeriod().toPlainString() + " reached");
            }
        }
        Tax tax = null;
        BigDecimal total = net;
        if (taxRule != null) {
            BigDecimal taxAmount = net.multiply(taxRule.rate(), MC).setScale(scale, RoundingMode.HALF_EVEN);
            tax = new Tax(taxRule.taxRuleId(), taxRule.code(), taxRule.rate(), taxAmount);
            total = net.add(taxAmount);
        }
        return new Result(baseAmount, gross, minApplied, maxApplied, tierNo, free, applied, discount, capApplied,
                net, tax, total, f.currency(), List.copyOf(notes));
    }

    /** Whether a waiver rule's condition holds for the context. Unknown facts never satisfy a condition. */
    public static boolean applies(Waiver w, Context ctx, LocalDate asOf) {
        if (w.validFrom() != null && asOf.isBefore(w.validFrom()) || w.validTo() != null && asOf.isAfter(w.validTo())) {
            return false;
        }
        return switch (w.condition()) {
            case "MIN_BALANCE" -> ge(ctx.balance(), w.thresholdAmount());
            case "AVERAGE_BALANCE" -> ge(ctx.averageBalance(), w.thresholdAmount());
            case "SALARY_CREDIT" -> ge(ctx.salaryCredit(), w.thresholdAmount() == null ? BigDecimal.ONE : w.thresholdAmount());
            case "TRANSACTION_COUNT" -> ctx.transactionCount() != null && w.thresholdCount() != null
                    && ctx.transactionCount() >= w.thresholdCount();
            case "FIRST_N_FREE" -> ctx.occurrenceNo() != null && w.thresholdCount() != null
                    && ctx.occurrenceNo() >= 1 && ctx.occurrenceNo() <= w.thresholdCount();
            case "BUNDLE_MEMBER" -> Boolean.TRUE.equals(ctx.bundleMember());
            case "SEGMENT" -> w.segment() != null && w.segment().equalsIgnoreCase(String.valueOf(ctx.segment()));
            case "PROMOTION" -> ctx.promotionCode() != null && (w.expression() == null || w.expression().isBlank()
                    || codes(w.expression()).contains(ctx.promotionCode().toUpperCase(Locale.ROOT)));
            case "MANUAL" -> Boolean.TRUE.equals(ctx.manualWaiver());
            default -> false;
        };
    }

    private static Set<String> codes(String expression) {
        return Set.of(expression.toUpperCase(Locale.ROOT).replace("\"", "").replace("'", "").split("\\s*,\\s*"));
    }

    private static BigDecimal pct(Waiver w) {
        BigDecimal p = w.discountPercentage() == null ? BigDecimal.ONE : w.discountPercentage();
        return p.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static boolean ge(BigDecimal actual, BigDecimal threshold) {
        return actual != null && threshold != null && actual.compareTo(threshold) >= 0;
    }

    private static BigDecimal need(BigDecimal v, Fee f, String field) {
        if (v == null) {
            throw ApiException.unprocessable("incomplete_fee_definition",
                    "Fee " + f.code() + " (" + f.calculationMethod() + ") needs " + field);
        }
        return v;
    }
}
