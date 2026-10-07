package io.meridian.agentnetwork.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-network-service settings ({@code meridian.agent-network.*}).
 *
 * @param treasuryWalletId     platform treasury wallet funding bank-funded float top-ups and receiving withdrawals;
 *                             per tenant from config-service (namespace {@code ledger}, key {@code agent-network-service},
 *                             {@code treasury_wallet_id}), this deployment value otherwise
 * @param lowFloatAlertMinutes minimum minutes between two low-float alerts of the same float
 */
@ConfigurationProperties(prefix = "meridian.agent-network")
public record AgentNetworkProperties(UUID treasuryWalletId, Integer lowFloatAlertMinutes) {
    public AgentNetworkProperties {
        lowFloatAlertMinutes = lowFloatAlertMinutes == null ? 60 : lowFloatAlertMinutes;
    }

    /** The tenant's treasury wallet (wallets are tenant data): config-service setting first, deployment value second. */
    @Override
    public UUID treasuryWalletId() {
        return io.meridian.web.config.TenantSettings.uuid("agent-network-service", "treasury_wallet_id", treasuryWalletId);
    }
}
