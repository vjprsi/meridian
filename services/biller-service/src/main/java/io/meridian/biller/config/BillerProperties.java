package io.meridian.biller.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * biller-service settings ({@code meridian.biller.*}).
 *
 * @param settlementClearingAccountId ledger account (bank clearing / nostro) credited when a biller settlement is paid
 *                                    out of the biller's collection wallet
 */
@ConfigurationProperties(prefix = "meridian.biller")
public record BillerProperties(UUID settlementClearingAccountId) {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BillerProperties.class)
    static class Config {}
}
