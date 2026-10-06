package io.meridian.alm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** An approved behavioural assumption with its validated parameters. */
public record Assumption(UUID id, AssumptionType type, UUID productId, String segment, String currency,
                         Map<String, BigDecimal> parameters, LocalDate validFrom, LocalDate validTo) {

    public enum AssumptionType { PREPAYMENT, DEPOSIT_DECAY, NON_MATURITY_REPRICING, EARLY_WITHDRAWAL, DRAWDOWN }

    public BigDecimal param(String name) {
        return parameters.get(name);
    }

    public BigDecimal param(String name, BigDecimal fallback) {
        BigDecimal v = parameters.get(name);
        return v == null ? fallback : v;
    }

    public boolean validOn(LocalDate date) {
        return !validFrom.isAfter(date) && (validTo == null || !validTo.isBefore(date));
    }

    /**
     * Specificity of this assumption for an item, or -1 when it does not apply: a product match (4) beats a segment
     * match (2) beats a currency match (1) beats a generic assumption (0); attributes the assumption sets must match.
     */
    public int score(UUID itemProduct, String itemSegment, String itemCurrency) {
        int s = 0;
        if (productId != null) {
            if (!productId.equals(itemProduct)) {
                return -1;
            }
            s += 4;
        }
        if (segment != null) {
            if (!segment.equalsIgnoreCase(Objects.toString(itemSegment, ""))) {
                return -1;
            }
            s += 2;
        }
        if (currency != null) {
            if (itemCurrency != null && !currency.equals(itemCurrency)) {
                return -1;
            }
            s += 1;
        }
        return s;
    }

    /** The most specific assumption of a type valid on a date (ties: latest valid_from). */
    public static Optional<Assumption> best(List<Assumption> all, AssumptionType type, UUID product, String segment,
                                            String currency, LocalDate date) {
        return all.stream()
                .filter(a -> a.type() == type && a.validOn(date) && a.score(product, segment, currency) >= 0)
                .max(Comparator.<Assumption>comparingInt(a -> a.score(product, segment, currency))
                        .thenComparing(Assumption::validFrom));
    }
}
