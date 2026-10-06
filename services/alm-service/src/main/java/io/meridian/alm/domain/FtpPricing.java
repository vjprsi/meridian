package io.meridian.alm.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Funds transfer pricing: rule resolution, pricing tenor per method, FTP rate from curves, margin and the period
 * charge / credit.
 *
 * <ul>
 *   <li>MATCHED_MATURITY: base(term) + liquidity premium(term)</li>
 *   <li>FIXED_SPREAD: base(term) + liquidity premium(term) + fixed_spread</li>
 *   <li>POOLED: base(pool tenor, default 30 days) + liquidity premium(pool tenor)</li>
 *   <li>BEHAVIOURAL: as matched maturity at the behavioural average life (DEPOSIT_DECAY), else the contractual term</li>
 * </ul>
 */
public final class FtpPricing {
    static final MathContext MC = MathContext.DECIMAL128;

    private FtpPricing() {}

    public enum Method { MATCHED_MATURITY, POOLED, BEHAVIOURAL, FIXED_SPREAD }

    public enum ExposureType {
        DEPOSIT_ACCOUNT, TERM_DEPOSIT, LOAN, CREDIT_LINE;

        public boolean isAsset() {
            return this == LOAN || this == CREDIT_LINE;
        }
    }

    public record Rule(UUID id, UUID productId, String segment, String currency, Method method, String baseCurveCode,
                       String liquidityCurveCode, BigDecimal fixedSpread, Integer poolTenorDays, int priority,
                       LocalDate validFrom, LocalDate validTo) {

        public boolean validOn(LocalDate d) {
            return !validFrom.isAfter(d) && (validTo == null || !validTo.isBefore(d));
        }

        /** Specificity (product 4, segment 2, currency 1) or -1 when a set attribute does not match. */
        public int score(UUID product, String seg, String ccy) {
            int s = 0;
            if (productId != null) {
                if (!productId.equals(product)) {
                    return -1;
                }
                s += 4;
            }
            if (segment != null) {
                if (!segment.equalsIgnoreCase(Objects.toString(seg, ""))) {
                    return -1;
                }
                s += 2;
            }
            if (currency != null) {
                if (!currency.equals(ccy)) {
                    return -1;
                }
                s += 1;
            }
            return s;
        }
    }

    public record Pricing(int termDays, BigDecimal baseRate, BigDecimal liquidityPremium, BigDecimal spread,
                          BigDecimal ftpRate) {}

    /** Most specific valid rule; among equally specific rules the lowest priority wins, then the latest valid_from. */
    public static Optional<Rule> resolve(List<Rule> rules, UUID product, String segment, String currency, LocalDate date) {
        return rules.stream()
                .filter(r -> r.validOn(date) && r.score(product, segment, currency) >= 0)
                .min(Comparator.<Rule>comparingInt(r -> -r.score(product, segment, currency))
                        .thenComparingInt(Rule::priority)
                        .thenComparing(Rule::validFrom, Comparator.reverseOrder()));
    }

    /** Pricing tenor in days for a method. */
    public static int pricingTerm(Rule rule, Integer termDays, Integer behaviouralDays) {
        return switch (rule.method()) {
            case POOLED -> rule.poolTenorDays() == null ? 30 : rule.poolTenorDays();
            case BEHAVIOURAL -> behaviouralDays != null ? behaviouralDays : required(termDays);
            case MATCHED_MATURITY, FIXED_SPREAD -> required(termDays);
        };
    }

    private static int required(Integer termDays) {
        if (termDays == null || termDays <= 0) {
            throw new IllegalArgumentException("term_days must be a positive number of days");
        }
        return termDays;
    }

    public static Pricing price(Rule rule, Curve base, Curve liquidity, int termDays) {
        BigDecimal b = base.rate(termDays);
        BigDecimal l = liquidity == null ? BigDecimal.ZERO : liquidity.rate(termDays);
        BigDecimal spread = rule.method() == Method.FIXED_SPREAD && rule.fixedSpread() != null
                ? rule.fixedSpread() : BigDecimal.ZERO;
        BigDecimal ftp = b.add(l, MC).add(spread, MC);
        return new Pricing(termDays, rate(b), rate(l), rate(spread), rate(ftp));
    }

    /** Customer rate minus FTP for assets; FTP minus customer rate for liabilities. */
    public static BigDecimal margin(ExposureType type, BigDecimal customerRate, BigDecimal ftpRate) {
        if (customerRate == null) {
            return null;
        }
        return rate(type.isAsset() ? customerRate.subtract(ftpRate) : ftpRate.subtract(customerRate));
    }

    /** FTP charge (assets) or credit (liabilities) for an inclusive period on ACT/365, rounded to cents. */
    public static BigDecimal charge(BigDecimal averageBalance, BigDecimal ftpRate, LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return averageBalance.multiply(ftpRate, MC).multiply(BigDecimal.valueOf(days), MC)
                .divide(BigDecimal.valueOf(365), MC).setScale(2, RoundingMode.HALF_EVEN);
    }

    static BigDecimal rate(BigDecimal r) {
        return r.setScale(10, RoundingMode.HALF_EVEN);
    }
}
