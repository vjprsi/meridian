package io.meridian.commission.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Commission rule engine (pure). For every beneficiary type the most specific active rule matching the transaction
 * wins: each of channel, KYC tier, agent level, territory, biller and amount band counts one; ties go to the lowest
 * priority, then the lowest rule id. The commission is computed on the transaction AMOUNT or its FEE as FLAT,
 * PERCENTAGE, FLAT_PLUS_PERCENTAGE or TIERED (the band containing the base), rounded to 2 decimals half-even, capped,
 * multiplied by the beneficiary share; withholding tax is commission x tax rate and net = commission - tax.
 */
public final class CommissionEngine {
    public static final Set<String> BASES = Set.of("AMOUNT", "FEE");
    public static final Set<String> BASIS = Set.of("FLAT", "PERCENTAGE", "FLAT_PLUS_PERCENTAGE", "TIERED");

    private CommissionEngine() {}

    public record Tier(BigDecimal from, BigDecimal to, BigDecimal flat, BigDecimal percentage) {}

    /**
     * A commission rule. WE-PRICE-1 added the trailing fields (tier_mode..shareEnabled); all are optional and default
     * to today's behaviour, so the legacy constructor below produces identical results.
     */
    public record Rule(UUID id, int priority, String txnType, String channel, Integer kycTier, String agentLevel,
                       UUID territoryId, UUID billerId, BigDecimal minAmount, BigDecimal maxAmount, String basis,
                       BigDecimal flatAmount, BigDecimal percentage, List<Tier> tiers, BigDecimal capAmount, String base,
                       String beneficiary, UUID beneficiaryWalletId, BigDecimal share, BigDecimal taxRate,
                       boolean active, String tierMode, String chargeScope, String accumulationPeriod,
                       String chargeDirection, Integer milestoneFromNth, Integer milestoneToNth, String milestonePeriod,
                       String milestoneCountScope, String remoteTxnType, boolean inactiveCustomerOnly, Integer inactiveDays,
                       String rewardTrigger, Integer rewardDelayDays, boolean shareEnabled) {

        /** Legacy constructor (pre-WE-PRICE-1): band / instrument charging, no milestone / remote / inactive gate. */
        public Rule(UUID id, int priority, String txnType, String channel, Integer kycTier, String agentLevel,
                    UUID territoryId, UUID billerId, BigDecimal minAmount, BigDecimal maxAmount, String basis,
                    BigDecimal flatAmount, BigDecimal percentage, List<Tier> tiers, BigDecimal capAmount, String base,
                    String beneficiary, UUID beneficiaryWalletId, BigDecimal share, BigDecimal taxRate, boolean active) {
            this(id, priority, txnType, channel, kycTier, agentLevel, territoryId, billerId, minAmount, maxAmount, basis,
                    flatAmount, percentage, tiers, capAmount, base, beneficiary, beneficiaryWalletId, share, taxRate, active,
                    "BAND", "INSTRUMENT", null, null, null, null, null, null, null, false, null, null, null, false);
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
     * Attributes of the transaction the rules are matched and charged against. WE-PRICE-1 added the trailing fields;
     * the legacy constructor defaults them (no remote gate, zero cumulative-before, counts ignored, customer not
     * flagged inactive).
     */
    public record Txn(String txnType, String channel, Integer kycTier, String agentLevel, UUID territoryId,
                      UUID billerId, BigDecimal amount, BigDecimal fee, String remoteTxnType, BigDecimal cumulativeBefore,
                      Integer countDaily, Integer countWeekly, Integer countMonthly, Integer countLifetime,
                      Integer customerInactiveDays) {

        /** Legacy constructor (pre-WE-PRICE-1). */
        public Txn(String txnType, String channel, Integer kycTier, String agentLevel, UUID territoryId, UUID billerId,
                   BigDecimal amount, BigDecimal fee) {
            this(txnType, channel, kycTier, agentLevel, territoryId, billerId, amount, fee, null, BigDecimal.ZERO,
                    null, null, null, null, null);
        }

        BigDecimal before() {
            return cumulativeBefore == null ? BigDecimal.ZERO : cumulativeBefore;
        }

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

    public record Amounts(BigDecimal base, BigDecimal commission, BigDecimal tax, BigDecimal net) {}

    public record Selected(Rule rule, Amounts amounts) {}

    /** The winning rule per beneficiary type, in a stable order (beneficiary name). */
    public static Map<String, Rule> select(List<Rule> rules, Txn t) {
        Map<String, Rule> out = new LinkedHashMap<>();
        rules.stream().filter(r -> matches(r, t)).map(Rule::beneficiary).distinct().sorted().forEach(b ->
                best(rules.stream().filter(r -> r.beneficiary().equals(b) && matches(r, t)).toList())
                        .ifPresent(r -> out.put(b, r)));
        return out;
    }

    public static List<Selected> evaluate(List<Rule> rules, Txn t) {
        List<Selected> out = new ArrayList<>();
        for (Rule r : select(rules, t).values()) {
            Amounts a = compute(r, t);
            if (a.commission().signum() != 0) {
                out.add(new Selected(r, a));
            }
        }
        return out;
    }

    static Optional<Rule> best(List<Rule> candidates) {
        return candidates.stream().min(Comparator.comparingInt((Rule r) -> -specificity(r))
                .thenComparingInt(Rule::priority).thenComparing(Rule::id));
    }

    public static boolean matches(Rule r, Txn t) {
        return r.active() && r.txnType().equals(t.txnType())
                && (r.channel() == null || r.channel().equals(t.channel()))
                && (r.kycTier() == null || r.kycTier().equals(t.kycTier()))
                && (r.agentLevel() == null || r.agentLevel().equals(t.agentLevel()))
                && (r.territoryId() == null || r.territoryId().equals(t.territoryId()))
                && (r.billerId() == null || r.billerId().equals(t.billerId()))
                && (r.minAmount() == null || t.amount().compareTo(r.minAmount()) >= 0)
                && (r.maxAmount() == null || t.amount().compareTo(r.maxAmount()) <= 0)
                && (r.remoteTxnType() == null || r.remoteTxnType().equals(t.remoteTxnType()))
                && milestoneMatches(r, t)
                && inactiveMatches(r, t);
    }

    /** WE-PRICE-1 (PE-18): a milestone rule applies only when this txn's ordinal falls in [from_nth, to_nth]. */
    static boolean milestoneMatches(Rule r, Txn t) {
        if (!r.hasMilestone()) {
            return true;
        }
        int n = t.nth(r.milestonePeriod());
        return (r.milestoneFromNth() == null || n >= r.milestoneFromNth())
                && (r.milestoneToNth() == null || n <= r.milestoneToNth());
    }

    /** WE-PRICE-1 (PE-13): an inactive-customer rule fires only when the credited customer is inactive enough. */
    static boolean inactiveMatches(Rule r, Txn t) {
        if (!r.inactiveCustomerOnly()) {
            return true;
        }
        return t.customerInactiveDays() != null
                && (r.inactiveDays() == null || t.customerInactiveDays() >= r.inactiveDays());
    }

    public static int specificity(Rule r) {
        int s = 0;
        s += r.channel() != null ? 1 : 0;
        s += r.kycTier() != null ? 1 : 0;
        s += r.agentLevel() != null ? 1 : 0;
        s += r.territoryId() != null ? 1 : 0;
        s += r.billerId() != null ? 1 : 0;
        s += r.minAmount() != null || r.maxAmount() != null ? 1 : 0;
        s += r.remoteTxnType() != null ? 1 : 0;
        s += r.hasMilestone() ? 1 : 0;
        s += r.inactiveCustomerOnly() ? 1 : 0;
        return s;
    }

    public static Amounts compute(Rule r, Txn t) {
        BigDecimal base = "FEE".equals(r.base()) ? nz(t.fee()) : t.amount();
        BigDecimal c = "CUMULATIVE".equals(r.scope())
                ? rawCharge(r, t.before().add(base)).subtract(rawCharge(r, t.before()))
                : rawCharge(r, base);
        c = c.setScale(2, RoundingMode.HALF_EVEN);
        if (r.capAmount() != null && c.compareTo(r.capAmount()) > 0) {
            c = r.capAmount().setScale(2, RoundingMode.HALF_EVEN);
        }
        BigDecimal share = r.share() == null ? BigDecimal.ONE : r.share();
        c = c.multiply(share).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal tax = r.taxRate() == null ? BigDecimal.ZERO.setScale(2)
                : c.multiply(r.taxRate()).setScale(2, RoundingMode.HALF_EVEN);
        return new Amounts(base.setScale(2, RoundingMode.HALF_EVEN), c, tax, c.subtract(tax));
    }

    /** The un-rounded commission of a rule on a base, honouring the tier_mode for TIERED rules (WE-PRICE-1 PE-7). */
    static BigDecimal rawCharge(Rule r, BigDecimal base) {
        return switch (r.basis()) {
            case "FLAT" -> nz(r.flatAmount());
            case "PERCENTAGE" -> base.multiply(nz(r.percentage()));
            case "FLAT_PLUS_PERCENTAGE" -> nz(r.flatAmount()).add(base.multiply(nz(r.percentage())));
            case "TIERED" -> "TELESCOPIC".equals(r.slabMode()) ? telescopic(r.tiers(), base) : band(r.tiers(), base);
            default -> throw ApiException.unprocessable("invalid_rule", "Unknown basis " + r.basis());
        };
    }

    /** BAND: the single band containing the base charges the whole base (flat + base * percentage). */
    static BigDecimal band(List<Tier> tiers, BigDecimal base) {
        Tier tier = tierFor(tiers, base);
        return tier == null ? BigDecimal.ZERO : nz(tier.flat()).add(base.multiply(nz(tier.percentage())));
    }

    /**
     * TELESCOPIC (PE-7): the sum over every tier the base reaches into of that tier's flat plus its percentage applied
     * to the portion of the base lying within the tier's [from, to) slice.
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

    public static Tier tierFor(List<Tier> tiers, BigDecimal base) {
        if (tiers == null) {
            return null;
        }
        for (Tier t : tiers) {
            if ((t.from() == null || base.compareTo(t.from()) >= 0) && (t.to() == null || base.compareTo(t.to()) <= 0)) {
                return t;
            }
        }
        return null;
    }

    /** Validates a rule definition; throws 422 {@code invalid_rule}. */
    public static void validate(Rule r) {
        require(BASIS.contains(r.basis()), "basis must be one of " + BASIS);
        require(BASES.contains(r.base()), "base must be AMOUNT or FEE");
        switch (r.basis()) {
            case "FLAT" -> require(r.flatAmount() != null, "flat_amount is required for FLAT");
            case "PERCENTAGE" -> require(r.percentage() != null, "percentage is required for PERCENTAGE");
            case "FLAT_PLUS_PERCENTAGE" -> require(r.flatAmount() != null && r.percentage() != null,
                    "flat_amount and percentage are required for FLAT_PLUS_PERCENTAGE");
            default -> {
                require(r.tiers() != null && !r.tiers().isEmpty(), "tiers are required for TIERED");
                BigDecimal prevTo = null;
                boolean open = false;
                for (Tier t : r.tiers()) {
                    require(!open, "only the last tier may be open-ended");
                    require(t.from() != null && t.from().signum() >= 0, "every tier needs a non-negative from");
                    require(t.to() == null || t.to().compareTo(t.from()) >= 0, "tier to must be >= from");
                    require(prevTo == null || t.from().compareTo(prevTo) > 0, "tiers must be ascending and not overlap");
                    require(t.flat() != null || t.percentage() != null, "every tier needs flat or percentage");
                    require((t.flat() == null || t.flat().signum() >= 0) && (t.percentage() == null || t.percentage().signum() >= 0),
                            "tier values must not be negative");
                    open = t.to() == null;
                    prevTo = t.to();
                }
            }
        }
        for (BigDecimal v : new BigDecimal[] {r.flatAmount(), r.percentage(), r.capAmount(), r.minAmount(), r.maxAmount()}) {
            require(v == null || v.signum() >= 0, "amounts and rates must not be negative");
        }
        require(r.percentage() == null || r.percentage().compareTo(BigDecimal.ONE) <= 0, "percentage is a fraction (0.005 = 0.5%)");
        require(r.share() != null && r.share().signum() >= 0 && r.share().compareTo(BigDecimal.ONE) <= 0,
                "beneficiary_share must be between 0 and 1");
        require(r.taxRate() == null || (r.taxRate().signum() >= 0 && r.taxRate().compareTo(BigDecimal.ONE) < 0),
                "tax_withholding_rate must be between 0 and 1");
        require(r.minAmount() == null || r.maxAmount() == null || r.minAmount().compareTo(r.maxAmount()) <= 0,
                "min_amount must be <= max_amount");
        require(!Set.of("PLATFORM", "BILLER", "CUSTOMER", "MERCHANT").contains(r.beneficiary()) || r.beneficiaryWalletId() != null,
                r.beneficiary() + " rules need beneficiary_wallet_id");
    }

    /** Payout date of an earning: REALTIME / DAILY the earned date, WEEKLY next Monday, MONTHLY 1st of next month. */
    public static LocalDate payoutDueOn(String frequency, LocalDate earned) {
        return switch (frequency) {
            case "WEEKLY" -> earned.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            case "MONTHLY" -> earned.with(TemporalAdjusters.firstDayOfNextMonth());
            default -> earned;
        };
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw ApiException.unprocessable("invalid_rule", message);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
