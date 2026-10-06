package io.meridian.cardtokenization.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-tokenization-service settings ({@code meridian.cards.*}).
 *
 * @param callbackSecret HMAC-SHA256 secret shared with the scheme token services for the public callbacks
 * @param tokens         provisioning and step-up settings
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record CardsProperties(String callbackSecret, Tokens tokens) {

    public CardsProperties {
        callbackSecret = callbackSecret == null ? "dev-callback-secret" : callbackSecret;
        tokens = tokens == null ? new Tokens(null, null, null, null, null) : tokens;
    }

    /**
     * @param maxPerCard         live (active, suspended, pending) tokens allowed per card
     * @param otpKey             HMAC key for step-up OTP hashes
     * @param payloadKey         key protecting the opaque push-provisioning payload
     * @param otpTtlMinutes      step-up OTP validity
     * @param maxStepUpAttempts  wrong OTPs before the provisioning is declined
     */
    public record Tokens(Integer maxPerCard, String otpKey, String payloadKey, Integer otpTtlMinutes,
                         Integer maxStepUpAttempts) {
        public Tokens {
            maxPerCard = maxPerCard == null ? 10 : maxPerCard;
            otpKey = otpKey == null ? "dev-token-otp-key" : otpKey;
            payloadKey = payloadKey == null ? "dev-token-payload-key" : payloadKey;
            otpTtlMinutes = otpTtlMinutes == null ? 10 : otpTtlMinutes;
            maxStepUpAttempts = maxStepUpAttempts == null ? 3 : maxStepUpAttempts;
        }
    }
}
