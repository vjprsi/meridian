package io.meridian.merchant.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * merchant-service settings ({@code meridian.merchant.*}).
 *
 * @param paymentLinkBaseUrl     public base URL of payment links (the link code is appended)
 * @param feeIncomeAccountId     ledger account credited with MDR and refund fees; per tenant from config-service
 *                               (namespace {@code ledger}, key {@code merchant-service}, {@code fee_income_account_id}),
 *                               this deployment value otherwise
 * @param dynamicQrMinutes       default validity of a dynamic QR code
 * @param refundBankReview       when a refund approved inside the merchant (merchant app four-eyes) also goes to the
 *                               bank's checkers in central approvals
 */
@ConfigurationProperties(prefix = "meridian.merchant")
public record MerchantProperties(String paymentLinkBaseUrl, UUID feeIncomeAccountId, Integer dynamicQrMinutes,
                                 RefundBankReview refundBankReview) {
    public MerchantProperties {
        paymentLinkBaseUrl = paymentLinkBaseUrl == null ? "https://pay.meridian.example/l" : paymentLinkBaseUrl;
        dynamicQrMinutes = dynamicQrMinutes == null ? 15 : dynamicQrMinutes;
        refundBankReview = refundBankReview == null ? new RefundBankReview(null, null, null, null) : refundBankReview;
    }

    /**
     * Bank-side review of merchant refunds approved in the merchant app: after the merchant's own four-eyes the refund
     * waits in the bank's central approval inbox (status PENDING_BANK_APPROVAL) when it is above the threshold or the
     * merchant's {@code risk_rating} is listed; otherwise it executes right away.
     *
     * @param enabled            bank review at all
     * @param threshold          refunds above this amount are reviewed (any currency without its own threshold)
     * @param currencyThresholds per currency threshold, e.g. {KES: 100000}
     * @param riskRatings        merchant risk ratings whose refunds are always reviewed (default HIGH)
     */
    public record RefundBankReview(Boolean enabled, java.math.BigDecimal threshold,
                                   java.util.Map<String, java.math.BigDecimal> currencyThresholds,
                                   java.util.List<String> riskRatings) {
        public RefundBankReview {
            enabled = enabled == null || enabled;
            threshold = threshold == null ? new java.math.BigDecimal("1000") : threshold;
            currencyThresholds = currencyThresholds == null ? java.util.Map.of() : java.util.Map.copyOf(currencyThresholds);
            riskRatings = riskRatings == null ? java.util.List.of("HIGH") : java.util.List.copyOf(riskRatings);
        }

        /** Why the bank reviews a refund of this amount by this merchant (AMOUNT_THRESHOLD, MERCHANT_RISK), or null. */
        public String reason(java.math.BigDecimal amount, String currency, String merchantRiskRating) {
            if (!enabled) {
                return null;
            }
            if (merchantRiskRating != null && riskRatings.stream().anyMatch(merchantRiskRating::equalsIgnoreCase)) {
                return "MERCHANT_RISK";
            }
            java.math.BigDecimal t = currencyThresholds.getOrDefault(currency, threshold);
            return amount.compareTo(t) > 0 ? "AMOUNT_THRESHOLD" : null;
        }
    }

    /** The tenant's fee income account (ledger accounts are tenant data): config-service first, deployment value second. */
    @Override
    public UUID feeIncomeAccountId() {
        return TenantSettings.uuid("merchant-service", "fee_income_account_id", feeIncomeAccountId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MerchantProperties.class)
    static class Enable {}
}
