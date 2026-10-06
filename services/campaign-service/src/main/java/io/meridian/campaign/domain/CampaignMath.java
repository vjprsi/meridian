package io.meridian.campaign.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;

/** Rates, uplift and send-window arithmetic (pure). */
public final class CampaignMath {
    private static final int SCALE = 10;

    private CampaignMath() {}

    /** numerator / denominator with 10 decimals; null when the denominator is zero. */
    public static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Relative conversion uplift of the treated group over the control group: (treated rate - control rate) /
     * control rate. Null when either group is empty or the control group never converted (undefined uplift).
     */
    public static BigDecimal uplift(long treatedConverted, long treated, long controlConverted, long control) {
        BigDecimal t = rate(treatedConverted, treated);
        BigDecimal c = rate(controlConverted, control);
        if (t == null || c == null || c.signum() == 0) {
            return null;
        }
        return t.subtract(c).divide(c, SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Whether {@code now} falls in the daily send window [startHour, endHour) of the time zone; a window whose end
     * is before its start wraps past midnight; equal hours or a missing hour mean any time.
     */
    public static boolean inSendWindow(Integer startHour, Integer endHour, String timeZone, Instant now) {
        if (startHour == null || endHour == null || startHour.equals(endHour)) {
            return true;
        }
        int h = now.atZone(timeZone == null || timeZone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(timeZone)).getHour();
        return startHour < endHour ? h >= startHour && h < endHour : h >= startHour || h < endHour;
    }
}
