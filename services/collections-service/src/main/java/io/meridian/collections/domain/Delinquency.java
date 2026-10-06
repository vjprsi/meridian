package io.meridian.collections.domain;

/** Delinquency buckets by days past due (DelinquencyBucket). */
public final class Delinquency {
    private Delinquency() {}

    public static String bucket(int dpd) {
        if (dpd <= 0) {
            return "CURRENT";
        }
        if (dpd < 30) {
            return "DPD_1_29";
        }
        if (dpd < 60) {
            return "DPD_30_59";
        }
        if (dpd < 90) {
            return "DPD_60_89";
        }
        if (dpd < 120) {
            return "DPD_90_119";
        }
        if (dpd < 180) {
            return "DPD_120_179";
        }
        return "DPD_180_PLUS";
    }
}
