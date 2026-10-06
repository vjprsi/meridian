package io.meridian.loan.domain;

/** Days-past-due buckets and IFRS 9 stage hints. */
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

    /**
     * IFRS 9 stage hint: STAGE_3 when credit-impaired (more than 90 DPD or written off), STAGE_2 on a significant
     * increase in credit risk (more than 30 DPD, or forborne), else STAGE_1.
     */
    public static String eclStage(int dpd, boolean forborne, boolean writtenOff) {
        if (writtenOff || dpd > 90) {
            return "STAGE_3";
        }
        if (dpd > 30 || forborne) {
            return "STAGE_2";
        }
        return "STAGE_1";
    }

    /** Non-performing: interest is accrued into suspense rather than income. */
    public static boolean nonPerforming(int dpd) {
        return dpd > 90;
    }
}
