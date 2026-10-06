package io.meridian.cardthreeds.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.cardthreeds.domain.RiskScorer.Input;
import io.meridian.cardthreeds.domain.RiskScorer.Outcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

class ThreedsDomainTest {
    static final BigDecimal THREE_RI_MAX = new BigDecimal("100");

    static Input in(String amount, String mcc, String country, String channel, String status) {
        return new Input(amount == null ? null : new BigDecimal(amount), mcc, country, channel, status);
    }

    // ------------------------------------------------------------------ risk scoring

    @Test
    void scoresAndDecisions() {
        assertThat(RiskScorer.assess(in("25", "5411", "GB", "BROWSER", "ACTIVE"), THREE_RI_MAX))
                .extracting(RiskScorer.Assessment::score, RiskScorer.Assessment::outcome).containsExactly(0, Outcome.FRICTIONLESS);
        assertThat(RiskScorer.assess(in("499.99", "5411", "GB", "APP", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.FRICTIONLESS);
        assertThat(RiskScorer.assess(in("500", "5411", null, "APP", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.CHALLENGE);
        RiskScorer.Assessment mcc = RiskScorer.assess(in("600", "7995", "MT", "APP", "ACTIVE"), THREE_RI_MAX);
        assertThat(mcc.score()).isEqualTo(500);
        assertThat(mcc.reasons()).containsExactly("ELEVATED_AMOUNT", "HIGH_RISK_MCC");
        assertThat(RiskScorer.assess(in("2000", "5411", "GB", "APP", "ACTIVE"), THREE_RI_MAX))
                .extracting(RiskScorer.Assessment::score, RiskScorer.Assessment::outcome).containsExactly(650, Outcome.CHALLENGE);
        assertThat(RiskScorer.assess(in("2000", "6051", null, "APP", "ACTIVE"), THREE_RI_MAX))
                .extracting(RiskScorer.Assessment::score, RiskScorer.Assessment::outcome).containsExactly(950, Outcome.REJECT);
        assertThat(RiskScorer.assess(in("5", "5411", "GB", "APP", "FROZEN"), THREE_RI_MAX))
                .extracting(RiskScorer.Assessment::score, RiskScorer.Assessment::outcome).containsExactly(1000, Outcome.REJECT);
        assertThat(RiskScorer.assess(in(null, null, "GB", "APP", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.FRICTIONLESS);
    }

    @Test
    void merchantInitiatedOnlyWhenSmallAndLowRisk() {
        assertThat(RiskScorer.assess(in("100", "5411", "GB", "3RI", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.FRICTIONLESS);
        assertThat(RiskScorer.assess(in("100.01", "5411", "GB", "3RI", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.REJECT);
        assertThat(RiskScorer.assess(in("50", "7995", null, "3RI", "ACTIVE"), THREE_RI_MAX).reasons())
                .contains("MERCHANT_INITIATED_NOT_LOW_RISK");
        assertThat(RiskScorer.assess(in(null, "5411", "GB", "3RI", "ACTIVE"), THREE_RI_MAX).outcome())
                .isEqualTo(Outcome.REJECT);
    }

    @Test
    void lowValueExemptionAndChallengeTypesAndEci() {
        assertThat(RiskScorer.lowValueExempt(in("29.99", "5411", "GB", "APP", "ACTIVE"))).isTrue();
        assertThat(RiskScorer.lowValueExempt(in("30", "5411", "GB", "APP", "ACTIVE"))).isFalse();
        assertThat(RiskScorer.lowValueExempt(in("10", "7995", null, "APP", "ACTIVE"))).isFalse();
        assertThat(RiskScorer.challengeType(null)).isEqualTo("OTP_SMS");
        assertThat(RiskScorer.challengeType("FRICTIONLESS")).isEqualTo("OTP_SMS");
        assertThat(RiskScorer.challengeType("BIOMETRIC")).isEqualTo("BIOMETRIC");
        assertThat(RiskScorer.eci("VISA", "AUTHENTICATED")).isEqualTo("05");
        assertThat(RiskScorer.eci("MASTERCARD", "AUTHENTICATED")).isEqualTo("02");
        assertThat(RiskScorer.eci("MASTERCARD", "ATTEMPTED")).isEqualTo("01");
        assertThat(RiskScorer.eci(null, "ATTEMPTED")).isEqualTo("06");
        assertThat(RiskScorer.eci("VISA", "FAILED")).isNull();
    }

    @Property
    void scoreStaysWithinBounds(@ForAll @BigRange(min = "0", max = "1000000") BigDecimal amount,
                                @ForAll("mccs") String mcc, @ForAll("countries") String country,
                                @ForAll("statuses") String status) {
        int s = RiskScorer.score(new Input(amount, mcc, country, "BROWSER", status)).score();
        assertThat(s).isBetween(0, 1000);
    }

    @Property
    void scoreIsMonotoneInAmount(@ForAll @BigRange(min = "0", max = "100000") BigDecimal a,
                                 @ForAll @BigRange(min = "0", max = "100000") BigDecimal b,
                                 @ForAll("mccs") String mcc, @ForAll("countries") String country) {
        BigDecimal lo = a.min(b);
        BigDecimal hi = a.max(b);
        assertThat(RiskScorer.score(new Input(lo, mcc, country, "APP", "ACTIVE")).score())
                .isLessThanOrEqualTo(RiskScorer.score(new Input(hi, mcc, country, "APP", "ACTIVE")).score());
    }

    @Provide
    Arbitrary<String> mccs() {
        return Arbitraries.of("5411", "7995", "4829", "6051", "5812", "5999");
    }

    @Provide
    Arbitrary<String> countries() {
        return Arbitraries.of("GB", "US", "MT").injectNull(0.3);
    }

    @Provide
    Arbitrary<String> statuses() {
        return Arbitraries.of("ACTIVE", "FROZEN", "BLOCKED", "CREATED");
    }

    // ------------------------------------------------------------------ crypto

    @Test
    void cavvIsDeterministicAnd28Characters() {
        UUID card = UUID.fromString("0199a000-0000-7000-8000-000000000001");
        String a = Crypto.cavv("k", "acs-1", card, new BigDecimal("10.50"), "EUR");
        assertThat(a).hasSize(28).isEqualTo(Crypto.cavv("k", "acs-1", card, new BigDecimal("10.5000"), "EUR"));
        assertThat(a).isNotEqualTo(Crypto.cavv("k", "acs-2", card, new BigDecimal("10.50"), "EUR"));
        assertThat(a).isNotEqualTo(Crypto.cavv("other", "acs-1", card, new BigDecimal("10.50"), "EUR"));
        assertThat(Crypto.cavv("k", "acs-1", card, null, null)).hasSize(28);
    }

    @Test
    void otpHashingAndSignatures() {
        String otp = Crypto.newOtp(6);
        assertThat(otp).matches("\\d{6}");
        String h = Crypto.otpHash("k", "auth-1", otp);
        assertThat(Crypto.otpMatches("k", "auth-1", otp, h)).isTrue();
        assertThat(Crypto.otpMatches("k", "auth-2", otp, h)).isFalse();
        assertThat(Crypto.otpMatches("k", "auth-1", "x" + otp, h)).isFalse();
        byte[] ds = "ds-1".getBytes(StandardCharsets.UTF_8);
        assertThat(Crypto.verifySignature("s", ds, Crypto.hmacHex("s", ds))).isTrue();
        assertThat(Crypto.verifySignature("s", ds, Crypto.hmacHex("t", ds))).isFalse();
        assertThat(Crypto.verifySignature("s", ds, "")).isFalse();
    }
}
