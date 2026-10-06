package io.meridian.loanorigination.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Lending terms of one product version as read from product-catalog-service: nominal rate (DEBIT_INTEREST), the
 * origination fee definition (LOAN_ORIGINATION) and the lending parameters (amount/term bounds, offer validity).
 * Bounds are null when the catalog does not define them.
 */
public record ProductTerms(UUID productId, UUID productVersionId, UUID legalEntityId, String currency, String family,
                           BigDecimal baseRate, String dayCount, BigDecimal originationFlat,
                           BigDecimal originationPercentage, BigDecimal originationMin, BigDecimal originationMax,
                           BigDecimal minAmount, BigDecimal maxAmount, Integer minTermMonths, Integer maxTermMonths,
                           Integer offerValidityDays) {

    /** Origination fee for a principal: flat + percentage, clamped to [min, max], rounded to 2 decimals. */
    public BigDecimal originationFee(BigDecimal principal, int scale) {
        BigDecimal fee = BigDecimal.ZERO;
        if (originationFlat != null) {
            fee = fee.add(originationFlat);
        }
        if (originationPercentage != null) {
            fee = fee.add(principal.multiply(originationPercentage));
        }
        if (originationMin != null && fee.signum() > 0) {
            fee = fee.max(originationMin);
        }
        if (originationMax != null) {
            fee = fee.min(originationMax);
        }
        return fee.setScale(scale, RoundingMode.HALF_EVEN);
    }
}
