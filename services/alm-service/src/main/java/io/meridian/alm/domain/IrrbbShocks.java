package io.meridian.alm.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Basel IRRBB (BCBS d368) standardised interest rate shock scenarios as a rate change by tenor t (years):
 * <ul>
 *   <li>parallel: +/- P (default 200bp)</li>
 *   <li>short rate: +/- S * e^(-t/4) (S = 250bp)</li>
 *   <li>long rate component: L * (1 - e^(-t/4)) (L = 100bp)</li>
 *   <li>steepener: -0.65 |short(t)| + 0.9 |long(t)|; flattener: +0.8 |short(t)| - 0.6 |long(t)|</li>
 *   <li>custom: tenor-by-tenor shocks in bp, linearly interpolated</li>
 * </ul>
 * Shocked rates are floored at the post-shock floor: max(base + shock, min(base, -100bp + 5bp * t, capped at 0)).
 */
public final class IrrbbShocks {
    static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal BP = new BigDecimal("0.0001");

    public enum ShockType { PARALLEL_UP, PARALLEL_DOWN, STEEPENER, FLATTENER, SHORT_UP, SHORT_DOWN, CUSTOM }

    /** Standard six shocks (CUSTOM excluded) in Basel order. */
    public static final List<ShockType> STANDARD = List.of(ShockType.PARALLEL_UP, ShockType.PARALLEL_DOWN,
            ShockType.STEEPENER, ShockType.FLATTENER, ShockType.SHORT_UP, ShockType.SHORT_DOWN);

    /** Shock sizes in basis points; custom: points (tenor_days, bp). */
    public record Params(int parallelBps, int shortBps, int longBps, Curve custom) {
        public static Params standard(Integer parallelBps, Curve custom) {
            return new Params(parallelBps == null ? 200 : parallelBps, 250, 100, custom);
        }
    }

    private IrrbbShocks() {}

    /** Rate change (decimal, e.g. 0.02) at tenor t years. */
    public static BigDecimal delta(ShockType type, double t, Params p) {
        double decay = Math.exp(-t / 4.0);
        double shortT = p.shortBps() * decay;
        double longT = p.longBps() * (1 - decay);
        double bps = switch (type) {
            case PARALLEL_UP -> p.parallelBps();
            case PARALLEL_DOWN -> -p.parallelBps();
            case SHORT_UP -> shortT;
            case SHORT_DOWN -> -shortT;
            case STEEPENER -> -0.65 * Math.abs(shortT) + 0.9 * Math.abs(longT);
            case FLATTENER -> 0.8 * Math.abs(shortT) - 0.6 * Math.abs(longT);
            case CUSTOM -> {
                if (p.custom() == null) {
                    throw new IllegalArgumentException("custom_shock is required for CUSTOM");
                }
                yield p.custom().rate(BigDecimal.valueOf(t * 365.0)).doubleValue();
            }
        };
        return BigDecimal.valueOf(bps).multiply(BP, MC);
    }

    /** Post-shock floor at tenor t: -100bp rising 5bp a year, never above zero. */
    public static BigDecimal floor(double t) {
        return BigDecimal.valueOf(Math.min(0.0, -0.01 + 0.0005 * t));
    }

    /** Shocked rate with the Basel floor applied. */
    public static BigDecimal shocked(BigDecimal base, BigDecimal delta, double t) {
        BigDecimal raw = base.add(delta, MC);
        BigDecimal lower = base.min(floor(t));
        return raw.max(lower);
    }
}
