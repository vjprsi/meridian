package io.meridian.config.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tax calculation over applicable rules. A segment-specific rule replaces the generic (no segment) rule of the
 * same tax type; legal-entity-specific rules replace tenant-wide ones of the same type. Below the threshold
 * (same currency) no tax applies. Tax per rule = taxable amount x rate, rounded HALF_EVEN to the currency's minor
 * units.
 */
public final class TaxCalculator {
    private TaxCalculator() {}

    public record Rule(UUID id, String taxType, String name, BigDecimal rate, String customerSegment, UUID legalEntityId,
                       BigDecimal threshold, String thresholdCurrency, String glCode, LocalDate validFrom, LocalDate validTo) {}

    public record Line(Rule rule, BigDecimal taxableAmount, BigDecimal tax, boolean exempt, String reason) {}

    public record Result(List<Line> lines, BigDecimal totalTax, BigDecimal withholding, int scale) {}

    public static int minorUnits(String currency) {
        try {
            int d = Currency.getInstance(currency).getDefaultFractionDigits();
            return d < 0 ? 2 : d;
        } catch (IllegalArgumentException e) {
            return 2;
        }
    }

    /** Validates a rule's own fields (rate 0..1, validity range, threshold). */
    public static void requireValidRule(BigDecimal rate, LocalDate validFrom, LocalDate validTo, BigDecimal threshold,
                                        String thresholdCurrency) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_rate", "rate must be a fraction between 0 and 1, e.g. 0.15");
        }
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
        if (threshold != null) {
            if (threshold.signum() < 0) {
                throw ApiException.unprocessable("invalid_threshold", "threshold_amount must not be negative");
            }
            if (thresholdCurrency == null) {
                throw ApiException.unprocessable("currency_required", "threshold_amount needs a currency");
            }
        }
    }

    public static Result calculate(List<Rule> candidates, BigDecimal amount, String currency, String segment,
                                   UUID legalEntityId, LocalDate on) {
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive");
        }
        int scale = minorUnits(currency);
        // pick the most specific applicable rule per tax type: legal entity > tenant-wide, segment > generic
        Map<String, List<Rule>> byType = new LinkedHashMap<>();
        for (Rule r : candidates) {
            if (r.validFrom().isAfter(on) || (r.validTo() != null && r.validTo().isBefore(on))) {
                continue;
            }
            if (r.customerSegment() != null && !r.customerSegment().equals(segment)) {
                continue;
            }
            if (r.legalEntityId() != null && !r.legalEntityId().equals(legalEntityId)) {
                continue;
            }
            List<Rule> cur = byType.computeIfAbsent(r.taxType(), k -> new ArrayList<>());
            if (cur.isEmpty() || specificity(r) == specificity(cur.getFirst())) {
                cur.add(r);
            } else if (specificity(r) > specificity(cur.getFirst())) {
                cur.clear();
                cur.add(r);
            }
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
        BigDecimal withholding = total;
        for (Rule r : byType.values().stream().flatMap(List::stream).toList()) {
            if (r.threshold() != null && currency.equals(r.thresholdCurrency()) && amount.compareTo(r.threshold()) < 0) {
                lines.add(new Line(r, amount, BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN), true,
                        "below_threshold"));
                continue;
            }
            String reason = r.threshold() != null && !currency.equals(r.thresholdCurrency()) ? "threshold_currency_differs" : null;
            BigDecimal tax = amount.multiply(r.rate()).setScale(scale, RoundingMode.HALF_EVEN);
            lines.add(new Line(r, amount, tax, false, reason));
            total = total.add(tax);
            if ("WITHHOLDING".equals(r.taxType())) {
                withholding = withholding.add(tax);
            }
        }
        return new Result(lines, total, withholding, scale);
    }

    private static int specificity(Rule r) {
        return (r.legalEntityId() != null ? 2 : 0) + (r.customerSegment() != null ? 1 : 0);
    }
}
