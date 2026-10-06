package io.meridian.card.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-service settings ({@code meridian.cards.*}).
 *
 * @param callbackSecret     HMAC-SHA256 secret bureaus sign their status callbacks with (X-Signature)
 * @param replacementFee     fee charged for a replacement card (0 = none); not charged for EXPIRING or FRAUD
 * @param statusSyncEnabled  push card status changes to card-tokenization-service (best effort, after commit)
 * @param maxPanAttempts     PAN generation attempts when the vault reports a collision
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record CardProperties(String callbackSecret, BigDecimal replacementFee, Boolean statusSyncEnabled,
                             Integer maxPanAttempts) {
    public CardProperties {
        callbackSecret = callbackSecret == null ? "dev-callback-secret" : callbackSecret;
        replacementFee = replacementFee == null ? BigDecimal.ZERO : replacementFee;
        statusSyncEnabled = statusSyncEnabled == null || statusSyncEnabled;
        maxPanAttempts = maxPanAttempts == null ? 5 : maxPanAttempts;
    }
}
