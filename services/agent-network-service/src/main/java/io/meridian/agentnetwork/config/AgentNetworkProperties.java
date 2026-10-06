package io.meridian.agentnetwork.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-network-service settings ({@code meridian.agent-network.*}).
 *
 * @param treasuryWalletId     platform treasury wallet funding bank-funded float top-ups and receiving withdrawals
 * @param lowFloatAlertMinutes minimum minutes between two low-float alerts of the same float
 */
@ConfigurationProperties(prefix = "meridian.agent-network")
public record AgentNetworkProperties(UUID treasuryWalletId, Integer lowFloatAlertMinutes) {
    public AgentNetworkProperties {
        lowFloatAlertMinutes = lowFloatAlertMinutes == null ? 60 : lowFloatAlertMinutes;
    }
}
