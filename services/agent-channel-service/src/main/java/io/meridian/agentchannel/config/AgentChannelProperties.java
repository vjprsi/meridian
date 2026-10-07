package io.meridian.agentchannel.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent channel settings ({@code meridian.agent-channel.*}).
 *
 * @param stepUpMaxAge        money movement needs an agent login (token {@code auth_time}; dev mode {@code X-Auth-Time})
 *                            no older than this, unless the request carries a valid device proof (default 5 minutes)
 * @param deviceProofMaxAge   a device proof ({@code X-Agent-Device-Proof}) is accepted when its timestamp is within this
 *                            much of the server clock (default 5 minutes)
 * @param lookupsPerHour      customer wallet lookups an agent may make per rolling hour (default 60)
 * @param hierarchyRead       super-agents and agents may read their direct sub-agents and their KPIs (default true)
 * @param reconcileWindow     how far back {@code idempotency_key} searches look for transactions, bill payments and
 *                            top-ups that downstream cannot filter by key (default 7 days)
 */
@ConfigurationProperties(prefix = "meridian.agent-channel")
public record AgentChannelProperties(Duration stepUpMaxAge, Duration deviceProofMaxAge, Integer lookupsPerHour,
                                     Boolean hierarchyRead, Duration reconcileWindow) {
    public AgentChannelProperties {
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(5) : stepUpMaxAge;
        deviceProofMaxAge = deviceProofMaxAge == null ? Duration.ofMinutes(5) : deviceProofMaxAge;
        lookupsPerHour = lookupsPerHour == null ? 60 : lookupsPerHour;
        hierarchyRead = hierarchyRead == null || hierarchyRead;
        reconcileWindow = reconcileWindow == null ? Duration.ofDays(7) : reconcileWindow;
    }
}
