package io.meridian.fraud.domain.expr;

import java.math.BigDecimal;

/** Equality and ordering semantics of the rule language. */
final class Values {
    private Values() {}

    static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof String x && b instanceof String y) {
            return x.equals(y);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return a.equals(b);
        }
        // numeric strings compare equal to numbers ('5' == 5), which is what analysts expect from JSON context
        if (a instanceof BigDecimal x && b instanceof String y) {
            BigDecimal d = parse(y);
            return d != null && d.compareTo(x) == 0;
        }
        if (a instanceof String x && b instanceof BigDecimal y) {
            BigDecimal d = parse(x);
            return d != null && d.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    /** Null when the values are not mutually comparable (comparison is then false). */
    static Integer compare(Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y);
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof BigDecimal x && b instanceof String y) {
            BigDecimal d = parse(y);
            return d == null ? null : x.compareTo(d);
        }
        if (a instanceof String x && b instanceof BigDecimal y) {
            BigDecimal d = parse(x);
            return d == null ? null : d.compareTo(y);
        }
        return null;
    }

    static BigDecimal parse(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
