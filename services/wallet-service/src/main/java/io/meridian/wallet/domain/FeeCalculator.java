package io.meridian.wallet.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer fee engine. Among the active rules matching the transaction (type, channel, payer wallet type, KYC tier,
 * amount band) the most specific wins (each of channel, wallet type, tier and amount band counts one), then the lowest
 * priority, then the code. Fees are rounded to 2 decimals half-even and clamped to [min_fee, max_fee]; tax is
 * fee x tax_rate.
 */
public final class FeeCalculator {
    private FeeCalculator() {}

    public record Tier(BigDecimal from, BigDecimal to, BigDecimal flat, BigDecimal percentage) {}

    public record Rule(UUID id, String code, String txnType, String channel, String walletType, Integer kycTier,
                       BigDecimal minAmount, BigDecimal maxAmount, String basis, BigDecimal flatFee,
                       BigDecimal percentage, List<Tier> tiers, BigDecimal minFee, BigDecimal maxFee,
                       BigDecimal taxRate, int priority) {}

    public record Context(String txnType, String channel, String walletType, Integer kycTier, BigDecimal amount) {}

    public record Fee(UUID ruleId, BigDecimal fee, BigDecimal tax) {
        public static final Fee NONE = new Fee(null, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        public BigDecimal total() {
            return fee.add(tax);
        }
    }

    public static Fee quote(List<Rule> rules, Context ctx) {
        return select(rules, ctx).map(r -> {
            BigDecimal fee = compute(r, ctx.amount());
            BigDecimal tax = r.taxRate() == null ? BigDecimal.ZERO
                    : fee.multiply(r.taxRate()).setScale(2, RoundingMode.HALF_EVEN);
            return new Fee(r.id(), fee, tax.setScale(2, RoundingMode.HALF_EVEN));
        }).orElse(Fee.NONE);
    }

    public static Optional<Rule> select(List<Rule> rules, Context ctx) {
        return rules.stream()
                .filter(r -> matches(r, ctx))
                .min(Comparator.comparingInt((Rule r) -> -specificity(r))
                        .thenComparingInt(Rule::priority)
                        .thenComparing(Rule::code));
    }

    static boolean matches(Rule r, Context c) {
        return r.txnType().equals(c.txnType())
                && (r.channel() == null || r.channel().equals(c.channel()))
                && (r.walletType() == null || r.walletType().equals(c.walletType()))
                && (r.kycTier() == null || r.kycTier().equals(c.kycTier()))
                && (r.minAmount() == null || c.amount().compareTo(r.minAmount()) >= 0)
                && (r.maxAmount() == null || c.amount().compareTo(r.maxAmount()) <= 0);
    }

    static int specificity(Rule r) {
        int s = 0;
        s += r.channel() != null ? 1 : 0;
        s += r.walletType() != null ? 1 : 0;
        s += r.kycTier() != null ? 1 : 0;
        s += r.minAmount() != null || r.maxAmount() != null ? 1 : 0;
        return s;
    }

    /** Fee of one rule for an amount (before tax), 2 decimals half-even, clamped to the rule's min/max. */
    public static BigDecimal compute(Rule r, BigDecimal amount) {
        BigDecimal fee = switch (r.basis()) {
            case "FLAT" -> nz(r.flatFee());
            case "PERCENTAGE" -> amount.multiply(nz(r.percentage()));
            case "FLAT_PLUS_PERCENTAGE" -> nz(r.flatFee()).add(amount.multiply(nz(r.percentage())));
            case "TIERED" -> {
                Tier t = tierFor(r.tiers(), amount);
                yield t == null ? BigDecimal.ZERO : nz(t.flat()).add(amount.multiply(nz(t.percentage())));
            }
            default -> throw ApiException.unprocessable("invalid_fee_rule", "Unknown fee basis " + r.basis());
        };
        fee = fee.setScale(2, RoundingMode.HALF_EVEN);
        if (r.minFee() != null && fee.compareTo(r.minFee()) < 0) {
            fee = r.minFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        if (r.maxFee() != null && fee.compareTo(r.maxFee()) > 0) {
            fee = r.maxFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        return fee;
    }

    /** The band containing the amount: from <= amount and (to empty or amount <= to). */
    public static Tier tierFor(List<Tier> tiers, BigDecimal amount) {
        if (tiers == null) {
            return null;
        }
        for (Tier t : tiers) {
            if ((t.from() == null || amount.compareTo(t.from()) >= 0) && (t.to() == null || amount.compareTo(t.to()) <= 0)) {
                return t;
            }
        }
        return null;
    }

    /** Validates a rule definition: basis fields present, non-negative values, sane band and tier table. */
    public static void validate(Rule r) {
        switch (r.basis()) {
            case "FLAT" -> require(r.flatFee() != null, "flat_fee is required for FLAT");
            case "PERCENTAGE" -> require(r.percentage() != null, "percentage is required for PERCENTAGE");
            case "FLAT_PLUS_PERCENTAGE" -> require(r.flatFee() != null && r.percentage() != null,
                    "flat_fee and percentage are required for FLAT_PLUS_PERCENTAGE");
            case "TIERED" -> {
                require(r.tiers() != null && !r.tiers().isEmpty(), "tiers are required for TIERED");
                BigDecimal prevTo = null;
                for (Tier t : r.tiers()) {
                    require(t.from() != null, "every tier needs from");
                    require(t.to() == null || t.to().compareTo(t.from()) >= 0, "tier to must be >= from");
                    require(prevTo == null || t.from().compareTo(prevTo) > 0, "tiers must be ascending and not overlap");
                    require(t.flat() != null || t.percentage() != null, "every tier needs flat or percentage");
                    prevTo = t.to() == null ? t.from() : t.to();
                }
            }
            default -> throw ApiException.unprocessable("invalid_fee_rule", "Unknown fee basis " + r.basis());
        }
        for (BigDecimal v : new BigDecimal[] {r.flatFee(), r.percentage(), r.minFee(), r.maxFee(), r.taxRate()}) {
            require(v == null || v.signum() >= 0, "fee values must not be negative");
        }
        require(r.minAmount() == null || r.maxAmount() == null || r.minAmount().compareTo(r.maxAmount()) <= 0,
                "min_amount must be <= max_amount");
        require(r.minFee() == null || r.maxFee() == null || r.minFee().compareTo(r.maxFee()) <= 0,
                "min_fee must be <= max_fee");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw ApiException.unprocessable("invalid_fee_rule", message);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
