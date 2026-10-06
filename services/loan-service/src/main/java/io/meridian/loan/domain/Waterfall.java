package io.meridian.loan.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Repayment allocation waterfall (pure). Money is applied to "buckets" (instalments, or unlinked fee charges) in
 * due-date order, component by component in the configured order:
 * <ul>
 *   <li>oldest-first: each bucket is cleared in component order before the next bucket is touched;</li>
 *   <li>component-first: a component is cleared across all buckets before the next component.</li>
 * </ul>
 * Whatever is left is the excess (handled by the caller per the rule's excess handling).
 */
public final class Waterfall {

    public enum Component {
        PENALTY, FEE, INTEREST, PRINCIPAL, INSURANCE, TAX;

        public static Component parse(String v) {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** Default order when no allocation rule applies: fees, penalties, interest, principal. */
    public static final List<Component> DEFAULT_ORDER = List.of(Component.FEE, Component.PENALTY, Component.INTEREST,
            Component.PRINCIPAL);

    /** Amounts still owed in one bucket. {@code ref} identifies the instalment or fee charge. */
    public record Bucket(String ref, LocalDate dueDate, Map<Component, BigDecimal> due) {
        public Bucket {
            due = due == null ? Map.of() : Map.copyOf(due);
        }

        public BigDecimal owed(Component c) {
            BigDecimal v = due.get(c);
            return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
        }
    }

    public record Allocation(String ref, Component component, BigDecimal amount) {}

    public record Result(List<Allocation> allocations, Map<Component, BigDecimal> totals, BigDecimal excess) {
        public BigDecimal total(Component c) {
            return totals.getOrDefault(c, BigDecimal.ZERO);
        }

        public BigDecimal allocated() {
            return totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private Waterfall() {}

    public static Result allocate(List<Bucket> buckets, List<Component> order, boolean oldestFirst, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        List<Component> seq = order == null || order.isEmpty() ? DEFAULT_ORDER : order;
        List<Bucket> sorted = new ArrayList<>(buckets);
        sorted.sort(Comparator.comparing(Bucket::dueDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, Map<Component, BigDecimal>> remaining = new java.util.HashMap<>();
        for (Bucket b : sorted) {
            Map<Component, BigDecimal> m = new EnumMap<>(Component.class);
            for (Component c : Component.values()) {
                m.put(c, b.owed(c));
            }
            remaining.put(b.ref(), m);
        }
        List<Allocation> out = new ArrayList<>();
        Map<Component, BigDecimal> totals = new EnumMap<>(Component.class);
        BigDecimal left = amount;
        if (oldestFirst) {
            for (Bucket b : sorted) {
                for (Component c : seq) {
                    left = take(b.ref(), c, remaining, left, out, totals);
                }
            }
        } else {
            for (Component c : seq) {
                for (Bucket b : sorted) {
                    left = take(b.ref(), c, remaining, left, out, totals);
                }
            }
        }
        return new Result(List.copyOf(out), Map.copyOf(totals), left);
    }

    private static BigDecimal take(String ref, Component c, Map<String, Map<Component, BigDecimal>> remaining,
                                   BigDecimal left, List<Allocation> out, Map<Component, BigDecimal> totals) {
        if (left.signum() <= 0) {
            return left;
        }
        BigDecimal owed = remaining.get(ref).get(c);
        if (owed.signum() <= 0) {
            return left;
        }
        BigDecimal x = owed.min(left);
        remaining.get(ref).put(c, owed.subtract(x));
        out.add(new Allocation(ref, c, x));
        totals.merge(c, x, BigDecimal::add);
        return left.subtract(x);
    }
}
