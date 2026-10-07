package io.meridian.customerchannel.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Customer channel settings ({@code meridian.channels.*}).
 *
 * @param stepUpMaxAge     money movement and security changes need an authentication (token {@code auth_time}; dev
 *                         mode {@code X-Auth-Time}) no older than this (default 5 minutes)
 * @param stepUpAcr        when not empty, the token's {@code acr} must also be one of these values (e.g. a strong
 *                         authentication level configured in the identity provider)
 * @param debtorNameFromProfile put the customer's display name on outgoing payments as debtor name
 */
@ConfigurationProperties(prefix = "meridian.channels")
public record ChannelProperties(Duration stepUpMaxAge, List<String> stepUpAcr, Boolean debtorNameFromProfile) {
    public ChannelProperties {
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(5) : stepUpMaxAge;
        stepUpAcr = stepUpAcr == null ? List.of() : List.copyOf(stepUpAcr);
        debtorNameFromProfile = debtorNameFromProfile == null || debtorNameFromProfile;
    }
}
