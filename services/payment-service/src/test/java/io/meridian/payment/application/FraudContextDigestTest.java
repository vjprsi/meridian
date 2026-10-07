package io.meridian.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * payment-service sends the beneficiary account to fraud-service only as fraud-service's canonical digest; the vector
 * is shared with fraud-service's PseudonymsTest so both sides normalise and hash identically.
 */
class FraudContextDigestTest {
    static final String VECTOR = "f7b0e9c9cc3e4af959b5ff7a40db8f9495d5f0ebf236fffeb551c105d2574047";

    @Test
    void digestMatchesFraudServicesCanonicalForm() {
        assertThat(PaymentProcessor.accountDigest("GB33BUKB20201555555555")).isEqualTo(VECTOR);
        assertThat(PaymentProcessor.accountDigest(" gb33 bukb-2020 1555 5555 55 ")).isEqualTo(VECTOR);
        assertThat(PaymentProcessor.accountDigest(null)).isNull();
        assertThat(PaymentProcessor.accountDigest(" ")).isNull();
    }
}
