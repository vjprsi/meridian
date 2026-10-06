package io.meridian.alm.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A zero / transfer-pricing curve: rates at tenor points (days), linearly interpolated between points and flat beyond
 * the first and last point.
 */
public final class Curve {
    public static final MathContext MC = MathContext.DECIMAL128;

    public record Point(int tenorDays, BigDecimal rate) {
        public Point {
            if (tenorDays < 0) {
                throw new IllegalArgumentException("tenor_days must not be negative");
            }
            if (rate == null) {
                throw new IllegalArgumentException("rate is required");
            }
        }
    }

    /** Interpolation outcome with the bracketing points (equal when exact or extrapolated flat). */
    public record Interpolation(int tenorDays, BigDecimal rate, String method, Point lower, Point upper) {}

    private final List<Point> points;

    public Curve(List<Point> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("a curve needs at least one point");
        }
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingInt(Point::tenorDays));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).tenorDays() == sorted.get(i - 1).tenorDays()) {
                throw new IllegalArgumentException("duplicate tenor " + sorted.get(i).tenorDays());
            }
        }
        this.points = List.copyOf(sorted);
    }

    public List<Point> points() {
        return points;
    }

    public BigDecimal rate(int tenorDays) {
        return interpolate(tenorDays).rate();
    }

    /** Rate at a fractional tenor in days (used for cash-flow discounting). */
    public BigDecimal rate(BigDecimal tenorDays) {
        Point first = points.getFirst();
        Point last = points.getLast();
        if (tenorDays.compareTo(BigDecimal.valueOf(first.tenorDays())) <= 0) {
            return first.rate();
        }
        if (tenorDays.compareTo(BigDecimal.valueOf(last.tenorDays())) >= 0) {
            return last.rate();
        }
        for (int i = 1; i < points.size(); i++) {
            Point hi = points.get(i);
            if (tenorDays.compareTo(BigDecimal.valueOf(hi.tenorDays())) <= 0) {
                return between(points.get(i - 1), hi, tenorDays);
            }
        }
        return last.rate();
    }

    public Interpolation interpolate(int tenorDays) {
        Point first = points.getFirst();
        Point last = points.getLast();
        if (tenorDays <= first.tenorDays()) {
            return new Interpolation(tenorDays, first.rate(), tenorDays == first.tenorDays() ? "EXACT" : "FLAT_EXTRAPOLATION",
                    first, first);
        }
        if (tenorDays >= last.tenorDays()) {
            return new Interpolation(tenorDays, last.rate(), tenorDays == last.tenorDays() ? "EXACT" : "FLAT_EXTRAPOLATION",
                    last, last);
        }
        for (int i = 1; i < points.size(); i++) {
            Point hi = points.get(i);
            if (tenorDays == hi.tenorDays()) {
                return new Interpolation(tenorDays, hi.rate(), "EXACT", hi, hi);
            }
            if (tenorDays < hi.tenorDays()) {
                Point lo = points.get(i - 1);
                return new Interpolation(tenorDays, between(lo, hi, BigDecimal.valueOf(tenorDays)), "LINEAR", lo, hi);
            }
        }
        return new Interpolation(tenorDays, last.rate(), "FLAT_EXTRAPOLATION", last, last);
    }

    private static BigDecimal between(Point lo, Point hi, BigDecimal t) {
        BigDecimal span = BigDecimal.valueOf(hi.tenorDays() - lo.tenorDays());
        BigDecimal w = t.subtract(BigDecimal.valueOf(lo.tenorDays())).divide(span, MC);
        return lo.rate().add(hi.rate().subtract(lo.rate()).multiply(w, MC), MC);
    }
}
