package io.meridian.merchantchannel.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Merchant channel settings ({@code meridian.merchant.*}).
 *
 * @param stepUpMaxAge        refund approvals, payout account changes, terminal deactivation and administration need an
 *                            authentication (token {@code auth_time}; dev mode {@code X-Auth-Time}) no older than this
 *                            (default 5 minutes)
 * @param stepUpAcr           when not empty, the token's {@code acr} must also be one of these values
 * @param bootstrapFirstOwner the first app user who signs in for a merchant that has no app users yet becomes its OWNER,
 *                            provided the merchant exists and is ACTIVE (the identity provider's admin-only attributes
 *                            decide who that can be; default true). When false, the bank inserts the first owner.
 * @param payoutApproverRoles roles that may approve another user's payout account change (default OWNER and MANAGER;
 *                            never the requester)
 * @param maxTransactionPages pages of 200 wallet transactions read per history request (default 10)
 * @param apiClientScopes     scopes an API client created for a merchant may hold (default {@code channels:merchant})
 * @param webhookEventTypes   event types (or {@code <prefix>.*} wildcards) a merchant endpoint may subscribe to; every
 *                            subscription is filtered to the merchant's own events ({@code merchant_id} of the payload)
 */
@ConfigurationProperties(prefix = "meridian.merchant")
public record MerchantChannelProperties(Duration stepUpMaxAge, List<String> stepUpAcr, Boolean bootstrapFirstOwner,
                                        List<String> payoutApproverRoles, Integer maxTransactionPages,
                                        List<String> apiClientScopes, List<String> webhookEventTypes) {
    public MerchantChannelProperties {
        stepUpMaxAge = stepUpMaxAge == null ? Duration.ofMinutes(5) : stepUpMaxAge;
        stepUpAcr = stepUpAcr == null ? List.of() : List.copyOf(stepUpAcr);
        bootstrapFirstOwner = bootstrapFirstOwner == null || bootstrapFirstOwner;
        payoutApproverRoles = payoutApproverRoles == null || payoutApproverRoles.isEmpty() ? List.of("OWNER", "MANAGER")
                : List.copyOf(payoutApproverRoles);
        maxTransactionPages = maxTransactionPages == null ? 10 : Math.max(1, maxTransactionPages);
        apiClientScopes = apiClientScopes == null || apiClientScopes.isEmpty() ? List.of("channels:merchant")
                : List.copyOf(apiClientScopes);
        webhookEventTypes = webhookEventTypes == null || webhookEventTypes.isEmpty()
                ? List.of("wallets.merchant_qr.*", "wallets.merchant_payment_link.*", "wallets.merchant_refund.*",
                        "wallets.merchant_settlement.*")
                : List.copyOf(webhookEventTypes);
    }
}
