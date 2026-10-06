package io.meridian.merchant.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * merchant-service settings ({@code meridian.merchant.*}).
 *
 * @param paymentLinkBaseUrl     public base URL of payment links (the link code is appended)
 * @param feeIncomeAccountId     ledger account credited with MDR and refund fees
 * @param dynamicQrMinutes       default validity of a dynamic QR code
 */
@ConfigurationProperties(prefix = "meridian.merchant")
public record MerchantProperties(String paymentLinkBaseUrl, UUID feeIncomeAccountId, Integer dynamicQrMinutes) {
    public MerchantProperties {
        paymentLinkBaseUrl = paymentLinkBaseUrl == null ? "https://pay.meridian.example/l" : paymentLinkBaseUrl;
        dynamicQrMinutes = dynamicQrMinutes == null ? 15 : dynamicQrMinutes;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MerchantProperties.class)
    static class Enable {}
}
