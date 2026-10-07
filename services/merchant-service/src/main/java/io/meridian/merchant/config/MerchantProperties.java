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
 */
@ConfigurationProperties(prefix = "meridian.merchant")
public record MerchantProperties(String paymentLinkBaseUrl, UUID feeIncomeAccountId, Integer dynamicQrMinutes) {
    public MerchantProperties {
        paymentLinkBaseUrl = paymentLinkBaseUrl == null ? "https://pay.meridian.example/l" : paymentLinkBaseUrl;
        dynamicQrMinutes = dynamicQrMinutes == null ? 15 : dynamicQrMinutes;
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
