package io.meridian.gl.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * IFRS 9 expected credit loss, simplified and deterministic: ECL = EAD x PD x LGD where the PD depends on the stage:
 * stage 1 uses the 12-month PD, stage 2 and POCI the lifetime PD, stage 3 (credit-impaired) a PD of 1.
 * The result is rounded to 2 decimals, half-even.
 */
public final class EclCalculator {
    public static final String MODEL_VERSION = "IFRS9-EADxPDxLGD-1.0";

    public enum Stage { STAGE_1, STAGE_2, STAGE_3, POCI }

    public record Input(String exposureType, java.util.UUID exposureId, Stage stage, BigDecimal ead, BigDecimal pd12m,
                        BigDecimal pdLifetime, BigDecimal lgd, String currency) {}

    public record Result(Input input, BigDecimal appliedPd, BigDecimal ecl) {}

    private EclCalculator() {}

    public static Result calculate(Input in) {
        if (in.ead() == null || in.ead().signum() < 0) {
            throw ApiException.unprocessable("invalid_ead", "EAD must be zero or positive for exposure " + in.exposureId());
        }
        requireRate(in.lgd(), "lgd", in.exposureId());
        BigDecimal pd = switch (in.stage()) {
            case STAGE_1 -> requireRate(in.pd12m(), "pd_12m", in.exposureId());
            case STAGE_2, POCI -> requireRate(in.pdLifetime(), "pd_lifetime", in.exposureId());
            case STAGE_3 -> BigDecimal.ONE;
        };
        BigDecimal ecl = in.ead().multiply(pd).multiply(in.lgd()).setScale(2, RoundingMode.HALF_EVEN);
        return new Result(in, pd, ecl);
    }

    private static BigDecimal requireRate(BigDecimal r, String name, UUID exposure) {
        if (r == null || r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid_" + name, name + " must be between 0 and 1 for exposure " + exposure);
        }
        return r;
    }
}
