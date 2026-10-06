package io.meridian.liquidity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maturity ladder (liquidity gap) over the standard buckets. Items with a contractual maturity fall in the bucket of
 * their residual days; non-maturity items are spread by a behavioural profile (bucket shares summing to 1) or, when
 * behaviour is not applied, treated as repayable overnight.
 */
public final class MaturityLadder {
    /** Standard buckets; end_day null = open-ended. */
    public enum Bucket {
        ON("O/N", 0, 1), D7("2-7D", 2, 7), D30("8-30D", 8, 30), M3("1-3M", 31, 90), M6("3-6M", 91, 180),
        M12("6-12M", 181, 365), Y5("1-5Y", 366, 1825), OVER5Y(">5Y", 1826, null);

        public final String code;
        public final int startDay;
        public final Integer endDay;

        Bucket(String code, int startDay, Integer endDay) {
            this.code = code;
            this.startDay = startDay;
            this.endDay = endDay;
        }

        public int order() {
            return ordinal() + 1;
        }

        /** Bucket of a residual maturity in days (overdue and same-day items are overnight). */
        public static Bucket forDays(long days) {
            for (Bucket b : values()) {
                if (b.endDay == null || days <= b.endDay) {
                    return b;
                }
            }
            return OVER5Y;
        }

        public static Bucket ofCode(String code) {
            for (Bucket b : values()) {
                if (b.code.equalsIgnoreCase(code)) {
                    return b;
                }
            }
            return null;
        }
    }

    /** Default run-off profile of non-maturity deposits when behaviour applies (volatile part early, core part late). */
    public static final Map<Bucket, BigDecimal> DEFAULT_DEPOSIT_PROFILE = defaultProfile();

    public record Share(Bucket bucket, BigDecimal share) {}

    /** {@code days} null = no contractual maturity; {@code profile} empty = default behaviour. */
    public record Item(String category, boolean inflow, BigDecimal amount, Long days, List<Share> profile) {}

    public record Row(Bucket bucket, BigDecimal inflows, BigDecimal outflows, BigDecimal netGap, BigDecimal cumulativeGap,
                      Map<String, BigDecimal[]> breakdown) {}

    private MaturityLadder() {}

    public static List<Row> build(List<Item> items, boolean behavioural) {
        Map<Bucket, BigDecimal> in = new LinkedHashMap<>();
        Map<Bucket, BigDecimal> out = new LinkedHashMap<>();
        Map<Bucket, Map<String, BigDecimal[]>> breakdown = new LinkedHashMap<>();
        for (Bucket b : Bucket.values()) {
            in.put(b, BigDecimal.ZERO);
            out.put(b, BigDecimal.ZERO);
            breakdown.put(b, new TreeMap<>());
        }
        for (Item item : items) {
            if (item.amount() == null || item.amount().signum() < 0) {
                throw new DomainException("invalid_amount", "item amounts must be zero or positive");
            }
            for (Map.Entry<Bucket, BigDecimal> e : allocate(item, behavioural).entrySet()) {
                Map<Bucket, BigDecimal> target = item.inflow() ? in : out;
                target.merge(e.getKey(), e.getValue(), BigDecimal::add);
                BigDecimal[] cell = breakdown.get(e.getKey()).computeIfAbsent(item.category(),
                        k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                cell[item.inflow() ? 0 : 1] = cell[item.inflow() ? 0 : 1].add(e.getValue());
            }
        }
        List<Row> rows = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Bucket b : Bucket.values()) {
            BigDecimal i = Amounts.money(in.get(b));
            BigDecimal o = Amounts.money(out.get(b));
            BigDecimal net = i.subtract(o);
            cumulative = cumulative.add(net);
            rows.add(new Row(b, i, o, net, cumulative, breakdown.get(b)));
        }
        return rows;
    }

    /** Amount per bucket for one item; shares are rounded to cents with the remainder on the last bucket. */
    static Map<Bucket, BigDecimal> allocate(Item item, boolean behavioural) {
        Map<Bucket, BigDecimal> result = new LinkedHashMap<>();
        if (item.days() != null) {
            result.put(Bucket.forDays(item.days()), item.amount());
            return result;
        }
        Map<Bucket, BigDecimal> profile = new LinkedHashMap<>();
        if (!behavioural) {
            profile.put(Bucket.ON, BigDecimal.ONE);
        } else if (item.profile() != null && !item.profile().isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Share s : item.profile()) {
                if (s.bucket() == null || !Amounts.isFactor(s.share())) {
                    throw new DomainException("invalid_behavioural_profile",
                            "profile shares need a known bucket_code and a share between 0 and 1");
                }
                profile.merge(s.bucket(), s.share(), BigDecimal::add);
                sum = sum.add(s.share());
            }
            if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) > 0) {
                throw new DomainException("invalid_behavioural_profile", "profile shares must sum to 1 (got " + sum + ")");
            }
        } else if ("DEPOSITS".equals(item.category()) && !item.inflow()) {
            profile.putAll(DEFAULT_DEPOSIT_PROFILE);
        } else {
            profile.put(Bucket.ON, BigDecimal.ONE);
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<Map.Entry<Bucket, BigDecimal>> entries = new ArrayList<>(profile.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            BigDecimal part = i == entries.size() - 1 ? item.amount().subtract(allocated)
                    : Amounts.money(item.amount().multiply(entries.get(i).getValue(), Amounts.MC));
            allocated = allocated.add(part);
            result.merge(entries.get(i).getKey(), part, BigDecimal::add);
        }
        return result;
    }

    /** First bucket whose cumulative gap is negative, or null. */
    public static Bucket firstNegative(List<Row> rows) {
        for (Row r : rows) {
            if (r.cumulativeGap().signum() < 0) {
                return r.bucket();
            }
        }
        return null;
    }

    private static Map<Bucket, BigDecimal> defaultProfile() {
        Map<Bucket, BigDecimal> m = new LinkedHashMap<>();
        m.put(Bucket.ON, new BigDecimal("0.05"));
        m.put(Bucket.D7, new BigDecimal("0.05"));
        m.put(Bucket.D30, new BigDecimal("0.10"));
        m.put(Bucket.M3, new BigDecimal("0.10"));
        m.put(Bucket.M6, new BigDecimal("0.10"));
        m.put(Bucket.M12, new BigDecimal("0.10"));
        m.put(Bucket.Y5, new BigDecimal("0.50"));
        return java.util.Collections.unmodifiableMap(m);
    }
}
