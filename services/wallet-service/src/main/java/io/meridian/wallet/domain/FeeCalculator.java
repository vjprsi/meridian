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

    /**
     * A fee rule. WE-PRICE-1 added the trailing fields (tier_mode..remoteTxnType); all are optional and default to
     * today's behaviour (BAND / INSTRUMENT / no milestone or remote gate), so the legacy constructor below produces
     * identical results.
     */
    public record Rule(UUID id, String code, String txnType, String channel, String walletType, Integer kycTier,
                       BigDecimal minAmount, BigDecimal maxAmount, String basis, BigDecimal flatFee,
                       BigDecimal percentage, List<Tier> tiers, BigDecimal minFee, BigDecimal maxFee,
                       BigDecimal taxRate, int priority, String tierMode, String chargeScope, String accumulationPeriod,
                       String chargeDirection, Integer milestoneFromNth, Integer milestoneToNth, String milestonePeriod,
                       String milestoneCountScope, String remoteTxnType) {

        /** Legacy constructor (pre-WE-PRICE-1): band / instrument charging, no milestone or remote gate. */
        public Rule(UUID id, String code, String txnType, String channel, String walletType, Integer kycTier,
                    BigDecimal minAmount, BigDecimal maxAmount, String basis, BigDecimal flatFee, BigDecimal percentage,
                    List<Tier> tiers, BigDecimal minFee, BigDecimal maxFee, BigDecimal taxRate, int priority) {
            this(id, code, txnType, channel, walletType, kycTier, minAmount, maxAmount, basis, flatFee, percentage, tiers,
                    minFee, maxFee, taxRate, priority, "BAND", "INSTRUMENT", null, null, null, null, null, null, null);
        }

        public String slabMode() {
            return tierMode == null ? "BAND" : tierMode;
        }

        public String scope() {
            return chargeScope == null ? "INSTRUMENT" : chargeScope;
        }

        public boolean hasMilestone() {
            return milestoneFromNth != null || milestoneToNth != null;
        }
    }

    /**
     * Transaction attributes rules are matched and charged against. WE-PRICE-1 added the trailing fields; the legacy
     * constructor defaults them so matching and charging are unchanged (no remote gate, zero cumulative-before, counts
     * ignored).
     *
     * @param cumulativeBefore cumulative base already charged in the accumulation window before this transaction
     * @param countDaily/Weekly/Monthly/Lifetime matching transactions BEFORE this one in each window (this txn is the next)
     */
    public record Context(String txnType, String channel, String walletType, Integer kycTier, BigDecimal amount,
                          String remoteTxnType, BigDecimal cumulativeBefore, Integer countDaily, Integer countWeekly,
                          Integer countMonthly, Integer countLifetime) {

        /** Legacy constructor (pre-WE-PRICE-1). */
        public Context(String txnType, String channel, String walletType, Integer kycTier, BigDecimal amount) {
            this(txnType, channel, walletType, kycTier, amount, null, BigDecimal.ZERO, null, null, null, null);
        }

        BigDecimal before() {
            return cumulativeBefore == null ? BigDecimal.ZERO : cumulativeBefore;
        }

        /** The ordinal of this transaction (count-before + 1) in the window a milestone rule measures. */
        int nth(String period) {
            Integer c = switch (period == null ? "LIFETIME" : period) {
                case "DAILY" -> countDaily;
                case "WEEKLY" -> countWeekly;
                case "MONTHLY" -> countMonthly;
                default -> countLifetime;
            };
            return (c == null ? 0 : c) + 1;
        }
    }

    public record Fee(UUID ruleId, BigDecimal fee, BigDecimal tax) {
        public static final Fee NONE = new Fee(null, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        public BigDecimal total() {
            return fee.add(tax);
        }
    }

    public static Fee quote(List<Rule> rules, Context ctx) {
        return select(rules, ctx).map(r -> {
            BigDecimal fee = compute(r, ctx.amount(), ctx.before());
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
                && (r.maxAmount() == null || c.amount().compareTo(r.maxAmount()) <= 0)
                && (r.remoteTxnType() == null || r.remoteTxnType().equals(c.remoteTxnType()))
                && milestoneMatches(r, c);
    }

    /** WE-PRICE-1 (PE-18): a milestone rule applies only when this txn's ordinal falls in [from_nth, to_nth]. */
    static boolean milestoneMatches(Rule r, Context c) {
        if (!r.hasMilestone()) {
            return true;
        }
        int n = c.nth(r.milestonePeriod());
        return (r.milestoneFromNth() == null || n >= r.milestoneFromNth())
                && (r.milestoneToNth() == null || n <= r.milestoneToNth());
    }

    static int specificity(Rule r) {
        int s = 0;
        s += r.channel() != null ? 1 : 0;
        s += r.walletType() != null ? 1 : 0;
        s += r.kycTier() != null ? 1 : 0;
        s += r.minAmount() != null || r.maxAmount() != null ? 1 : 0;
        s += r.remoteTxnType() != null ? 1 : 0;
        s += r.hasMilestone() ? 1 : 0;
        return s;
    }

    /** Fee of one rule for an amount (before tax), band / instrument charging. Kept for callers and parity tests. */
    public static BigDecimal compute(Rule r, BigDecimal amount) {
        return compute(r, amount, BigDecimal.ZERO);
    }

    /**
     * Fee of one rule for an amount (before tax), 2 decimals half-even, clamped to the rule's min/max. WE-PRICE-1:
     * TELESCOPIC sums per-slice charges across the tier table; CUMULATIVE charges f(before + amount) - f(before).
     * With the defaults (BAND / INSTRUMENT / before = 0) this is identical to the pre-WE-PRICE-1 band computation.
     */
    public static BigDecimal compute(Rule r, BigDecimal amount, BigDecimal cumulativeBefore) {
        BigDecimal before = cumulativeBefore == null ? BigDecimal.ZERO : cumulativeBefore;
        BigDecimal fee = "CUMULATIVE".equals(r.scope())
                ? rawCharge(r, before.add(amount)).subtract(rawCharge(r, before))
                : rawCharge(r, amount);
        fee = fee.setScale(2, RoundingMode.HALF_EVEN);
        if (r.minFee() != null && fee.compareTo(r.minFee()) < 0) {
            fee = r.minFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        if (r.maxFee() != null && fee.compareTo(r.maxFee()) > 0) {
            fee = r.maxFee().setScale(2, RoundingMode.HALF_EVEN);
        }
        return fee;
    }

    /** The un-rounded, un-clamped charge of a rule on a base, honouring the tier_mode for TIERED rules. */
    static BigDecimal rawCharge(Rule r, BigDecimal base) {
        return switch (r.basis()) {
            case "FLAT" -> nz(r.flatFee());
            case "PERCENTAGE" -> base.multiply(nz(r.percentage()));
            case "FLAT_PLUS_PERCENTAGE" -> nz(r.flatFee()).add(base.multiply(nz(r.percentage())));
            case "TIERED" -> "TELESCOPIC".equals(r.slabMode()) ? telescopic(r.tiers(), base) : band(r.tiers(), base);
            default -> throw ApiException.unprocessable("invalid_fee_rule", "Unknown fee basis " + r.basis());
        };
    }

    /** BAND: the single band containing the base charges the whole base (flat + base * percentage). */
    static BigDecimal band(List<Tier> tiers, BigDecimal base) {
        Tier t = tierFor(tiers, base);
        return t == null ? BigDecimal.ZERO : nz(t.flat()).add(base.multiply(nz(t.percentage())));
    }

    /**
     * TELESCOPIC (PE-7): the charge is the sum over every tier the base reaches into of that tier's flat plus its
     * percentage applied to the portion of the base lying within the tier's [from, to) slice.
     */
    static BigDecimal telescopic(List<Tier> tiers, BigDecimal base) {
        if (tiers == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Tier t : tiers) {
            BigDecimal lower = nz(t.from());
            if (base.compareTo(lower) <= 0) {
                continue;
            }
            BigDecimal upper = t.to() == null ? base : base.min(t.to());
            BigDecimal width = upper.subtract(lower);
            if (width.signum() <= 0) {
                continue;
            }
            total = total.add(nz(t.flat())).add(width.multiply(nz(t.percentage())));
        }
        return total;
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
