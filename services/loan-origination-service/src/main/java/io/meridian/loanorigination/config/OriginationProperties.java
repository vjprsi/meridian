package io.meridian.loanorigination.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * loan-origination-service settings ({@code meridian.loan-origination.*}).
 *
 * @param callbackSecret          HMAC-SHA256 secret verifying the public e-signature and bureau callbacks
 * @param defaultBureauProvider   bureau adapter used when a pull names none (SANDBOX, SANDBOX_ASYNC, ...)
 * @param defaultEsignProvider    e-signature provider used when an acceptance names none
 * @param bureauValidityDays      days a received bureau report may be reused
 * @param offerValidityDays       default offer validity when the product has no lending.offer_validity_days
 * @param envelopeValidityDays    days an e-signature envelope stays open
 * @param applicationValidityDays default application expiry after creation
 * @param defaultRate             nominal rate when the product version has no DEBIT_INTEREST rate
 * @param counterOfferStep        counter-offer amounts are rounded down to a multiple of this
 */
@ConfigurationProperties(prefix = "meridian.loan-origination")
public record OriginationProperties(String callbackSecret, String defaultBureauProvider, String defaultEsignProvider,
                                    Integer bureauValidityDays, Integer offerValidityDays, Integer envelopeValidityDays,
                                    Integer applicationValidityDays, BigDecimal defaultRate,
                                    BigDecimal counterOfferStep) {
    public OriginationProperties {
        callbackSecret = callbackSecret == null ? "dev-lending-callback-secret" : callbackSecret;
        defaultBureauProvider = defaultBureauProvider == null ? "SANDBOX" : defaultBureauProvider;
        defaultEsignProvider = defaultEsignProvider == null ? "SANDBOX" : defaultEsignProvider;
        bureauValidityDays = bureauValidityDays == null ? 30 : bureauValidityDays;
        offerValidityDays = offerValidityDays == null ? 30 : offerValidityDays;
        envelopeValidityDays = envelopeValidityDays == null ? 14 : envelopeValidityDays;
        applicationValidityDays = applicationValidityDays == null ? 30 : applicationValidityDays;
        defaultRate = defaultRate == null ? new BigDecimal("0.12") : defaultRate;
        counterOfferStep = counterOfferStep == null ? BigDecimal.valueOf(100) : counterOfferStep;
    }
}
