package io.meridian.limits.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Risk appetite traffic lights. When red is above amber, higher values are worse (e.g. fraud loss bps); when red is
 * below amber, lower values are worse (e.g. a capital or liquidity buffer).
 */
public final class Appetite {
    private static final List<String> ORDER = List.of("GREEN", "AMBER", "RED");

    private Appetite() {}

    public static String status(BigDecimal value, BigDecimal amber, BigDecimal red) {
        boolean higherIsWorse = red.compareTo(amber) >= 0;
        if (higherIsWorse) {
            return value.compareTo(red) >= 0 ? "RED" : value.compareTo(amber) >= 0 ? "AMBER" : "GREEN";
        }
        return value.compareTo(red) <= 0 ? "RED" : value.compareTo(amber) <= 0 ? "AMBER" : "GREEN";
    }

    public static boolean worsened(String before, String after) {
        return ORDER.indexOf(after) > ORDER.indexOf(before);
    }
}
