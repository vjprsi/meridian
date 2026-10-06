package io.meridian.campaign.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic audience allocation. A customer's bucket is derived from SHA-256 of the campaign and customer ids,
 * so the same customer always lands in the same group of a campaign (rebuilds and retries are stable) while
 * allocations of different campaigns are independent. Control-group selection and variant selection use two
 * independent hashes so that the control share does not bias the variant split.
 */
public final class AbTesting {
    private static final BigDecimal TWO_64 = new BigDecimal("18446744073709551616");

    private AbTesting() {}

    /** Result of an allocation: control members carry no variant code. */
    public record Allocation(boolean control, String variantCode, double bucket) {}

    /** Uniform value in [0, 1) from SHA-256(campaign:customer[:salt]). */
    public static double bucket(UUID campaignId, UUID customerId, String salt) {
        String key = campaignId + ":" + customerId + (salt == null ? "" : ":" + salt);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long first = ByteBuffer.wrap(h, 0, 8).getLong();
            BigDecimal unsigned = new BigDecimal(Long.toUnsignedString(first));
            return unsigned.divide(TWO_64, MathContext.DECIMAL64).doubleValue();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Allocates a customer: control when the bucket falls below {@code controlShare}; otherwise a variant code chosen
     * by a second hash over the cumulative, normalised traffic shares of the codes (ordered by code).
     */
    public static Allocation allocate(UUID campaignId, UUID customerId, BigDecimal controlShare,
                                      Map<String, BigDecimal> shares) {
        double b = bucket(campaignId, customerId, null);
        double control = controlShare == null ? 0 : controlShare.doubleValue();
        if (b < control) {
            return new Allocation(true, null, b);
        }
        return new Allocation(false, pickVariant(bucket(campaignId, customerId, "variant"), shares), b);
    }

    /** The code whose cumulative normalised share first exceeds {@code u}; null when there are no codes. */
    public static String pickVariant(double u, Map<String, BigDecimal> shares) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        SortedMap<String, BigDecimal> sorted = new TreeMap<>(shares);
        double total = sorted.values().stream().mapToDouble(v -> Math.max(0, v.doubleValue())).sum();
        if (total <= 0) {
            return sorted.firstKey();
        }
        double cumulative = 0;
        for (Map.Entry<String, BigDecimal> e : sorted.entrySet()) {
            cumulative += Math.max(0, e.getValue().doubleValue()) / total;
            if (u < cumulative) {
                return e.getKey();
            }
        }
        return sorted.lastKey();
    }
}
