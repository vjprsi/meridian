package io.meridian.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import org.junit.jupiter.api.Test;

class PinPolicyTest {

    @Test
    void acceptsFourToSixDigitsThatAreNotTrivial() {
        PinPolicy.validateNew("4826", "+254700000001");
        PinPolicy.validateNew("739154", null);
    }

    @Test
    void rejectsBadFormats() {
        for (String pin : new String[] {null, "", "123", "1234567", "12a4", " 4826"}) {
            assertThatThrownBy(() -> PinPolicy.validateNew(pin, null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_pin_format");
        }
    }

    @Test
    void rejectsGuessablePins() {
        assertThat(PinPolicy.isWeak("1111", null)).isTrue();
        assertThat(PinPolicy.isWeak("1234", null)).isTrue();
        assertThat(PinPolicy.isWeak("987654", null)).isTrue();
        assertThat(PinPolicy.isWeak("0001", "+254700000001")).isTrue(); // tail of the msisdn
        assertThat(PinPolicy.isWeak("1357", "+254700000001")).isFalse();
        assertThatThrownBy(() -> PinPolicy.validateNew("4444", null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("weak_pin");
    }

    @Test
    void hashesAreSaltedAndVerifyInConstantTimeFormat() {
        String h1 = PinPolicy.hash("4826", 1000);
        String h2 = PinPolicy.hash("4826", 1000);
        assertThat(h1).startsWith("pbkdf2$1000$").isNotEqualTo(h2).doesNotContain("4826");
        assertThat(PinPolicy.verify("4826", h1)).isTrue();
        assertThat(PinPolicy.verify("4827", h1)).isFalse();
        assertThat(PinPolicy.verify("4826", "garbage")).isFalse();
        assertThat(PinPolicy.verify(null, h1)).isFalse();
    }

    @Test
    void lockoutCountsConsecutiveFailures() {
        PinPolicy.Failure first = PinPolicy.Failure.after(0, 3);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.locked()).isFalse();
        assertThat(first.remaining()).isEqualTo(2);
        PinPolicy.Failure third = PinPolicy.Failure.after(2, 3);
        assertThat(third.locked()).isTrue();
        assertThat(third.remaining()).isZero();
    }

    @Test
    void otpsAreNumericAndHashed() {
        String otp = PinPolicy.otp(6);
        assertThat(otp).matches("\\d{6}");
        String h = PinPolicy.hashOtp(otp);
        assertThat(h).doesNotContain(otp);
        assertThat(PinPolicy.verifyOtp(otp, h)).isTrue();
        assertThat(PinPolicy.verifyOtp("x" + otp, h)).isFalse();
    }
}
