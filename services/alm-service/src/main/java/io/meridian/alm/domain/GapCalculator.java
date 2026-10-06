package io.meridian.alm.domain;

import io.meridian.alm.domain.Assumption.AssumptionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Repricing gap: rate-sensitive assets minus liabilities per time bucket (0-1M ... >5Y) by repricing date, with optional
 * behavioural treatment (CPR prepayment of fixed-rate assets, decay / repricing of non-maturity items).
 */
public final class GapCalculator {
    static final MathContext MC = MathContext.DECIMAL128;

    private GapCalculator() {}

    public record BucketDef(String code, int order, int startMonths, Integer endMonths) {}

    public static final List<BucketDef> BUCKETS = List.of(
            new BucketDef("0-1M", 1, 0, 1), new BucketDef("1-3M", 2, 1, 3), new BucketDef("3-6M", 3, 3, 6),
            new BucketDef("6-12M", 4, 6, 12), new BucketDef("1-2Y", 5, 12, 24), new BucketDef("2-5Y", 6, 24, 60),
            new BucketDef(">5Y", 7, 60, null));

    public record BucketResult(BucketDef def, BigDecimal assets, BigDecimal liabilities, BigDecimal offBalance,
                               BigDecimal gap, BigDecimal cumulativeGap, Map<String, BigDecimal> breakdown) {}

    public record Result(List<BucketResult> buckets, BigDecimal totalAssets, BigDecimal totalLiabilities,
                         Set<UUID> assumptionsUsed) {}

    /** A position that cannot be placed in a bucket (no repricing information): the run fails with this message. */
    public static final class UnbucketablePosition extends RuntimeException {
        public UnbucketablePosition(String message) {
            super(message);
        }
    }

    /** Index into {@link #BUCKETS} for a repricing date (dates on or before as-of fall in the first bucket). */
    public static int bucketIndex(LocalDate asOf, LocalDate date) {
        for (int i = 0; i < BUCKETS.size(); i++) {
            Integer end = BUCKETS.get(i).endMonths();
            if (end == null || !date.isAfter(asOf.plusMonths(end))) {
                return i;
            }
        }
        return BUCKETS.size() - 1;
    }

    public static Result compute(LocalDate asOf, List<Position> positions, boolean behavioural, List<Assumption> assumptions) {
        int n = BUCKETS.size();
        BigDecimal[] assets = zeros(n);
        BigDecimal[] liabilities = zeros(n);
        BigDecimal[] off = zeros(n);
        List<Map<String, BigDecimal>> breakdown = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            breakdown.add(new TreeMap<>());
        }
        Set<UUID> used = new LinkedHashSet<>();
        BigDecimal totalA = BigDecimal.ZERO;
        BigDecimal totalL = BigDecimal.ZERO;
        for (Position p : positions) {
            List<Behaviour.Flow> flows = flows(asOf, p, behavioural, assumptions, used);
            for (Behaviour.Flow f : flows) {
                int b = bucketIndex(asOf, f.date());
                switch (p.side()) {
                    case ASSET -> assets[b] = assets[b].add(f.amount(), MC);
                    case LIABILITY -> liabilities[b] = liabilities[b].add(f.amount(), MC);
                    case OFF_BALANCE -> off[b] = off[b].add(f.amount(), MC);
                }
                BigDecimal signed = p.side() == Position.Side.LIABILITY ? f.amount().negate() : f.amount();
                breakdown.get(b).merge(p.code(), signed, (x, y) -> x.add(y, MC));
            }
            if (p.side() == Position.Side.ASSET) {
                totalA = totalA.add(p.amount(), MC);
            } else if (p.side() == Position.Side.LIABILITY) {
                totalL = totalL.add(p.amount(), MC);
            }
        }
        List<BucketResult> out = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal a = money(assets[i]);
            BigDecimal l = money(liabilities[i]);
            BigDecimal o = money(off[i]);
            BigDecimal gap = a.subtract(l).add(o);
            cumulative = cumulative.add(gap);
            Map<String, BigDecimal> bd = new TreeMap<>();
            breakdown.get(i).forEach((k, v) -> bd.put(k, money(v)));
            out.add(new BucketResult(BUCKETS.get(i), a, l, o, gap, cumulative, bd));
        }
        return new Result(out, money(totalA), money(totalL), used);
    }

    static List<Behaviour.Flow> flows(LocalDate asOf, Position p, boolean behavioural, List<Assumption> assumptions,
                                      Set<UUID> used) {
        if (behavioural) {
            if (p.side() == Position.Side.ASSET && p.rateType() == Position.RateType.FIXED && p.maturityDate() != null
                    && !p.nonMaturity()) {
                Optional<Assumption> a = find(assumptions, AssumptionType.PREPAYMENT, p, asOf);
                if (a.isPresent()) {
                    used.add(a.get().id());
                    return Behaviour.prepayment(asOf, p.maturityDate(), p.amount(), a.get().param("cpr"));
                }
            }
            if (p.nonMaturity()) {
                if (p.side() == Position.Side.LIABILITY) {
                    Optional<Assumption> d = find(assumptions, AssumptionType.DEPOSIT_DECAY, p, asOf);
                    if (d.isPresent()) {
                        used.add(d.get().id());
                        return Behaviour.decay(asOf, p.amount(), d.get().parameters());
                    }
                }
                Optional<Assumption> r = find(assumptions, AssumptionType.NON_MATURITY_REPRICING, p, asOf);
                if (r.isPresent()) {
                    used.add(r.get().id());
                    int months = r.get().param("repricing_months").intValue();
                    return List.of(new Behaviour.Flow(months == 0 ? asOf : asOf.plusMonths(months), p.amount()));
                }
            }
        }
        LocalDate d = p.repricesOn();
        if (d != null) {
            return List.of(new Behaviour.Flow(d, p.amount()));
        }
        if (p.nonMaturity()) {
            return List.of(new Behaviour.Flow(asOf, p.amount()));
        }
        throw new UnbucketablePosition("position " + p.code()
                + " has neither repricing_date nor maturity_date and is not flagged non_maturity");
    }

    static Optional<Assumption> find(List<Assumption> all, AssumptionType type, Position p, LocalDate asOf) {
        return Assumption.best(all, type, p.productId(), p.segment(), p.currency(), asOf);
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
