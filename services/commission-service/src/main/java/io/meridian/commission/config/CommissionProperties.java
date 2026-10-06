package io.meridian.commission.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * commission-service settings ({@code meridian.commission.*}).
 *
 * @param payoutSourceWalletId commission pool wallet funding payouts (FLOAT / ESCROW wallet in wallet-service)
 * @param listenerEnabled      consume wallet transaction events from Kafka to accrue commissions automatically
 */
@ConfigurationProperties(prefix = "meridian.commission")
public record CommissionProperties(UUID payoutSourceWalletId, Boolean listenerEnabled) {}
