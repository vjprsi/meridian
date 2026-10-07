package io.meridian.commission.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * commission-service settings ({@code meridian.commission.*}).
 *
 * @param payoutSourceWalletId commission pool wallet funding payouts (FLOAT / ESCROW wallet in wallet-service); per
 *                             tenant from config-service (namespace {@code ledger}, key {@code commission-service},
 *                             {@code payout_source_wallet_id}), this deployment value otherwise
 * @param listenerEnabled      consume wallet transaction events from Kafka to accrue commissions automatically
 */
@ConfigurationProperties(prefix = "meridian.commission")
public record CommissionProperties(UUID payoutSourceWalletId, Boolean listenerEnabled) {

    /** The tenant's commission pool wallet (wallets are tenant data): config-service setting first, deployment value second. */
    @Override
    public UUID payoutSourceWalletId() {
        return TenantSettings.uuid("commission-service", "payout_source_wallet_id", payoutSourceWalletId);
    }
}
