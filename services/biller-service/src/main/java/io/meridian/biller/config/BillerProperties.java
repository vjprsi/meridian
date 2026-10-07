package io.meridian.biller.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * biller-service settings ({@code meridian.biller.*}).
 *
 * @param settlementClearingAccountId ledger account (bank clearing / nostro) credited when a biller settlement is paid
 *                                    out of the biller's collection wallet; per tenant from config-service
 *                                    (namespace {@code ledger}, key {@code biller-service},
 *                                    {@code settlement_clearing_account_id}), this deployment value otherwise
 */
@ConfigurationProperties(prefix = "meridian.biller")
public record BillerProperties(UUID settlementClearingAccountId) {

    /** The tenant's clearing account (ledger accounts are tenant data): config-service setting first, deployment value second. */
    @Override
    public UUID settlementClearingAccountId() {
        return TenantSettings.uuid("biller-service", "settlement_clearing_account_id", settlementClearingAccountId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BillerProperties.class)
    static class Config {}
}
