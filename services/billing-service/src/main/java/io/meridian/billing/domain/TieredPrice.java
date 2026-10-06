package io.meridian.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Graduated (tiered) unit pricing for one usage metric. Each tier prices the units that fall inside it:
 * with tiers [0..1000] at 0 and (1000..] at 0.001, 1500 units cost 1000 x 0 + 500 x 0.001.
 * Tier bounds ({@code up_to}) are inclusive cumulative quantities, strictly ascending; the last tier is open-ended.
 */
public record TieredPrice(List<Tier> tiers) {

    /** One tier; {@code upTo == null} means unbounded. */
    public record Tier(Long upTo, BigDecimal unitPrice) {}

    /** Units charged in one tier. Amount is rounded to the minor unit (2 dp, HALF_EVEN). */
    public record TierCharge(int tierNo, String label, long quantity, BigDecimal unitPrice, BigDecimal amount) {}

    public TieredPrice {
        if (tiers == null || tiers.isEmpty()) {
            throw new PricingException("at least one tier is required");
        }
        long previous = 0;
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (t.unitPrice() == null || t.unitPrice().signum() < 0) {
                throw new PricingException("tier " + (i + 1) + " unit_price must be zero or positive");
            }
            boolean last = i == tiers.size() - 1;
            if (t.upTo() == null && !last) {
                throw new PricingException("only the last tier may be open-ended (up_to null)");
            }
            if (last && t.upTo() != null) {
                throw new PricingException("the last tier must be open-ended (up_to null)");
            }
            if (t.upTo() != null) {
                if (t.upTo() <= previous) {
                    throw new PricingException("tier up_to values must be positive and strictly ascending");
                }
                previous = t.upTo();
            }
        }
        tiers = List.copyOf(tiers);
    }

    /** Flat price: a single open-ended tier. */
    public static TieredPrice flat(BigDecimal unitPrice) {
        return new TieredPrice(List.of(new Tier(null, unitPrice)));
    }

    /**
     * Parses {@code {"tiers":[{"up_to":1000,"unit_price":"0"},{"up_to":null,"unit_price":"0.001"}]}} or the
     * flat shorthand {@code {"unit_price":"0.01"}}.
     */
    public static TieredPrice parse(Object spec) {
        if (!(spec instanceof Map<?, ?> m)) {
            throw new PricingException("price must be an object with tiers or unit_price");
        }
        if (m.get("tiers") instanceof List<?> list) {
            List<Tier> tiers = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> t)) {
                    throw new PricingException("each tier must be an object");
                }
                tiers.add(new Tier(toLong(t.get("up_to")), toDecimal(t.get("unit_price"))));
            }
            return new TieredPrice(tiers);
        }
        if (m.containsKey("unit_price")) {
            return flat(toDecimal(m.get("unit_price")));
        }
        throw new PricingException("price must define tiers or unit_price");
    }

    /** Splits a quantity across tiers; tiers with no units are omitted. */
    public List<TierCharge> charge(long quantity) {
        if (quantity < 0) {
            throw new PricingException("quantity must not be negative");
        }
        List<TierCharge> out = new ArrayList<>();
        long lower = 0;
        for (int i = 0; i < tiers.size() && quantity > lower; i++) {
            Tier t = tiers.get(i);
            long upper = t.upTo() == null ? Long.MAX_VALUE : t.upTo();
            long units = Math.min(quantity, upper) - lower;
            if (units > 0) {
                BigDecimal amount = t.unitPrice().multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_EVEN);
                String label = "tier " + (i + 1) + " (" + (lower + 1) + (t.upTo() == null ? "+" : "-" + t.upTo()) + ")";
                out.add(new TierCharge(i + 1, label, units, t.unitPrice(), amount));
            }
            lower = upper;
        }
        return out;
    }

    public BigDecimal total(long quantity) {
        return charge(quantity).stream().map(TierCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(o.toString());
            return d.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new PricingException("up_to must be a whole number: " + o);
        }
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            throw new PricingException("unit_price is required");
        }
        try {
            BigDecimal d = new BigDecimal(o.toString());
            if (d.stripTrailingZeros().scale() > 8) {
                throw new PricingException("unit_price has more than 8 decimals: " + o);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new PricingException("unit_price must be a decimal: " + o);
        }
    }
}
