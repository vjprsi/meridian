package io.meridian.fraud.domain.features;

import java.time.Instant;

/**
 * Long-run behaviour of an entity: exponentially weighted amount mean / variance and hour-of-day vector, with weight
 * {@code a = max(1 / (n + 1), ALPHA_MIN)} (a plain running mean for the first events, then a memory of about
 * {@code 1 / ALPHA_MIN} events). Update rule (West / Finch): {@code d = x - mean; mean += a*d;
 * var = (1 - a) * (var + a*d*d)}. The database applies the same rule in SQL ({@code FeatureStore}).
 */
public record Profile(long count, double mean, double var, double hourSin, double hourCos, Instant firstSeen,
                      Instant lastSeen, int fraudCount, int labelCount) {
    public static final double ALPHA_MIN = 0.02;
    /** Events needed before the amount z-score and the hour deviation are trusted (else 0). */
    public static final int MIN_HISTORY = 5;

    public static final Profile EMPTY = new Profile(0, 0, 0, 0, 0, null, null, 0, 0);

    public static double alpha(long countBefore) {
        return Math.max(1.0 / (countBefore + 1), ALPHA_MIN);
    }

    /** Profile after one more event with amount {@code x} (null = no amount) at {@code t}. */
    public Profile update(Double x, Instant t) {
        double a = alpha(count);
        double m = mean;
        double v = var;
        if (x != null) {
            double d = x - mean;
            m = mean + a * d;
            v = (1 - a) * (var + a * d * d);
        }
        double theta = angle(t);
        return new Profile(count + 1, m, v, hourSin + a * (Math.sin(theta) - hourSin),
                hourCos + a * (Math.cos(theta) - hourCos), firstSeen == null || t.isBefore(firstSeen) ? t : firstSeen,
                lastSeen == null || t.isAfter(lastSeen) ? t : lastSeen, fraudCount, labelCount);
    }

    /** {@code (x - mean) / max(sqrt(var), 1% of |mean|, 1e-9)}, clamped to [-10, 10]; 0 with too little history. */
    public double zScore(double x) {
        if (count < MIN_HISTORY) {
            return 0.0;
        }
        double sd = Math.max(Math.sqrt(Math.max(var, 0.0)), Math.max(0.01 * Math.abs(mean), 1e-9));
        return Math.max(-10.0, Math.min(10.0, (x - mean) / sd));
    }

    /**
     * Distance of the hour of {@code t} from the entity's usual hours: {@code R * (1 - cos(theta - mu)) / 2} where
     * mu / R are the direction / length of the mean hour vector (R near 1 = always at the same time). 0..1; 0 with too
     * little history.
     */
    public double hourDeviation(Instant t) {
        if (count < MIN_HISTORY) {
            return 0.0;
        }
        double r = Math.min(1.0, Math.hypot(hourSin, hourCos));
        if (r == 0.0) {
            return 0.0;
        }
        double mu = Math.atan2(hourSin, hourCos);
        return r * (1 - Math.cos(angle(t) - mu)) / 2.0;
    }

    /** Hour of day (UTC, fractional) as an angle. */
    public static double angle(Instant t) {
        long secOfDay = Math.floorMod(t.getEpochSecond(), 86_400L);
        return 2 * Math.PI * secOfDay / 86_400.0;
    }
}
