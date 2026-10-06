package io.meridian.cardthreeds.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-threeds-service settings ({@code meridian.cards.*}).
 *
 * @param callbackSecret HMAC-SHA256 secret shared with the directory servers for the public AReq / results endpoints
 * @param threeds        ACS settings
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record CardsProperties(String callbackSecret, Threeds threeds) {

    public CardsProperties {
        callbackSecret = callbackSecret == null ? "dev-callback-secret" : callbackSecret;
        threeds = threeds == null ? new Threeds(null, null, null, null, null) : threeds;
    }

    /**
     * @param cavvKey             key for CAVV / AAV generation (an HSM key in production)
     * @param otpKey              HMAC key for challenge OTP hashes
     * @param challengeTtlMinutes challenge validity
     * @param maxOtpAttempts      wrong OTPs before the authentication fails
     * @param threeRiMaxAmount    largest merchant-initiated (3RI) amount authenticated without the cardholder
     */
    public record Threeds(String cavvKey, String otpKey, Integer challengeTtlMinutes, Integer maxOtpAttempts,
                          BigDecimal threeRiMaxAmount) {
        public Threeds {
            cavvKey = cavvKey == null ? "dev-cavv-key" : cavvKey;
            otpKey = otpKey == null ? "dev-3ds-otp-key" : otpKey;
            challengeTtlMinutes = challengeTtlMinutes == null ? 5 : challengeTtlMinutes;
            maxOtpAttempts = maxOtpAttempts == null ? 3 : maxOtpAttempts;
            threeRiMaxAmount = threeRiMaxAmount == null ? new BigDecimal("100") : threeRiMaxAmount;
        }
    }
}
