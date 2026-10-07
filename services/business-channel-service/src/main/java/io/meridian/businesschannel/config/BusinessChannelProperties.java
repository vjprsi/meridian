package io.meridian.businesschannel.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business channel settings ({@code meridian.business.*}).
 *
 * @param stepUpMaxAge             approvals, money movement and administration need an authentication (token
 *                                 {@code auth_time}; dev mode {@code X-Auth-Time}) no older than this (default 5 minutes)
 * @param stepUpAcr                when not empty, the token's {@code acr} must also be one of these values
 * @param bootstrapFirstAdmin      the first portal user who signs in for a business that has no portal users yet becomes
 *                                 its ADMIN (the identity provider's admin-only attributes decide who that can be;
 *                                 default true). When false, the first admin must be inserted by the bank.
 * @param defaultRequiredApprovals approvals required when no rule of the business's matrix matches (default 1; at
 *                                 least 1: a payment is never released without an approver other than its initiator)
 * @param maxBatchLines            largest bulk / payroll batch (lines; default 5000)
 * @param maxBatchBytes            largest bulk / payroll CSV (characters; default 2,000,000)
 * @param apiClientScopes          scopes an API client created for a business may hold (default {@code channels:business})
 * @param webhookEventTypes        event types (or {@code <prefix>.*} wildcards) a business endpoint may subscribe to;
 *                                 every subscription is filtered to the business's own events ({@code customer_id}
 *                                 of the payload), so only event types whose payload carries {@code customer_id} make
 *                                 sense here (others never match and are never delivered)
 */
@ConfigurationProperties(prefix = "meridian.business")
public record BusinessChannelProperties(Duration stepUpMaxAge, List<String> stepUpAcr, Boolean bootstrapFirstAdmin,
                                        Integer defaultRequiredApprovals, Integer maxBatchLines, Integer maxBatchBytes,
                                        List<String> apiClientScopes, List<String> webhookEventTypes) {
    public BusinessChannelProperties {
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(5) : stepUpMaxAge;
        stepUpAcr = stepUpAcr == null ? List.of() : List.copyOf(stepUpAcr);
        bootstrapFirstAdmin = bootstrapFirstAdmin == null || bootstrapFirstAdmin;
        defaultRequiredApprovals = defaultRequiredApprovals == null ? 1 : Math.max(1, defaultRequiredApprovals);
        maxBatchLines = maxBatchLines == null ? 5000 : maxBatchLines;
        maxBatchBytes = maxBatchBytes == null ? 2_000_000 : maxBatchBytes;
        apiClientScopes = apiClientScopes == null || apiClientScopes.isEmpty() ? List.of("channels:business")
                : List.copyOf(apiClientScopes);
        webhookEventTypes = webhookEventTypes == null || webhookEventTypes.isEmpty()
                ? List.of("payments.payment.*", "payments.bulk_payment.*", "payments.payment_mandate.*")
                : List.copyOf(webhookEventTypes);
    }
}
