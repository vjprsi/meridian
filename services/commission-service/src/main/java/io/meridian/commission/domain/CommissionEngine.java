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

    public record Rule(UUID id, int priority, String txnType, String channel, Integer kycTier, String agentLevel,
                       UUID territoryId, UUID billerId, BigDecimal minAmount, BigDecimal maxAmount, String basis,
                       BigDecimal flatAmount, BigDecimal percentage, List<Tier> tiers, BigDecimal capAmount, String base,
                       String beneficiary, UUID beneficiaryWalletId, BigDecimal share, BigDecimal taxRate,
                       boolean active) {}

    /** Attributes of the transaction the rules are matched against. */
    public record Txn(String txnType, String channel, Integer kycTier, String agentLevel, UUID territoryId,
                      UUID billerId, BigDecimal amount, BigDecimal fee) {}

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
                && (r.maxAmount() == null || t.amount().compareTo(r.maxAmount()) <= 0);
    }

    public static int specificity(Rule r) {
        int s = 0;
        s += r.channel() != null ? 1 : 0;
        s += r.kycTier() != null ? 1 : 0;
        s += r.agentLevel() != null ? 1 : 0;
        s += r.territoryId() != null ? 1 : 0;
        s += r.billerId() != null ? 1 : 0;
        s += r.minAmount() != null || r.maxAmount() != null ? 1 : 0;
        return s;
    }

    public static Amounts compute(Rule r, Txn t) {
        BigDecimal base = "FEE".equals(r.base()) ? nz(t.fee()) : t.amount();
        BigDecimal c = switch (r.basis()) {
            case "FLAT" -> nz(r.flatAmount());
            case "PERCENTAGE" -> base.multiply(nz(r.percentage()));
            case "FLAT_PLUS_PERCENTAGE" -> nz(r.flatAmount()).add(base.multiply(nz(r.percentage())));
            case "TIERED" -> {
                Tier tier = tierFor(r.tiers(), base);
                yield tier == null ? BigDecimal.ZERO : nz(tier.flat()).add(base.multiply(nz(tier.percentage())));
            }
            default -> throw ApiException.unprocessable("invalid_rule", "Unknown basis " + r.basis());
        };
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
