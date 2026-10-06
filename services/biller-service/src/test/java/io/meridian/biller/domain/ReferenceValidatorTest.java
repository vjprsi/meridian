package io.meridian.biller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReferenceValidatorTest {

    @Test
    void luhnAcceptsValidNumbersAndRejectsSingleDigitErrors() {
        assertThat(ReferenceValidator.luhn("79927398713")).isTrue();
        assertThat(ReferenceValidator.luhn("79927398710")).isFalse();
        assertThat(ReferenceValidator.validate("79927398713", "\\d{11}", "LUHN")).isEmpty();
        assertThat(ReferenceValidator.validate("79927398714", "\\d{11}", "LUHN")).isPresent();
    }

    @Test
    void mod97FollowsIso7064() {
        // GB82 WEST 1234 5698 7654 32 rearranged and converted to digits (IBAN check) gives remainder 1
        assertThat(ReferenceValidator.mod97("3214282912345698765432161182")).isTrue();
        assertThat(ReferenceValidator.mod97("3214282912345698765432161183")).isFalse();
        assertThat(ReferenceValidator.validate("97", null, "MOD97")).isPresent(); // 97 mod 97 = 0
        assertThat(ReferenceValidator.validate("98", null, "MOD97")).isEmpty(); // 98 mod 97 = 1
    }

    @Test
    void mod11WeightsTwoToSevenFromTheRight() {
        // payload 12345: 5*2 + 4*3 + 3*4 + 2*5 + 1*6 = 50; 50 mod 11 = 6; 11 - 6 = 5
        assertThat(ReferenceValidator.mod11CheckDigit("12345")).isEqualTo(5);
        assertThat(ReferenceValidator.mod11("123455")).isTrue();
        assertThat(ReferenceValidator.mod11("123454")).isFalse();
        // payload 0: sum 0 -> 11 -> check digit 0
        assertThat(ReferenceValidator.mod11CheckDigit("0")).isZero();
        // payload 5: 10 mod 11 = 10 -> 1 ; payload 1: 2 -> 9; a result of 10 is never issued
        assertThat(ReferenceValidator.mod11CheckDigit("1")).isEqualTo(9);
        assertThat(ReferenceValidator.mod11CheckDigit("6")).isNull(); // 12 mod 11 = 1 -> 10
        assertThat(ReferenceValidator.mod11("60")).isFalse();
    }

    @Test
    void patternMustMatchTheWholeReferenceAndChecksumsNeedDigits() {
        assertThat(ReferenceValidator.validate("ACC-12345", "ACC-\\d{5}", "NONE")).isEmpty();
        assertThat(ReferenceValidator.validate("XACC-12345", "ACC-\\d{5}", "NONE")).isPresent();
        assertThat(ReferenceValidator.validate("ACC-12345", null, "LUHN")).get().asString().contains("numeric");
        assertThat(ReferenceValidator.validate(" ", null, "NONE")).isPresent();
        assertThat(ReferenceValidator.compiles("[0-9")).isFalse();
        assertThat(ReferenceValidator.compiles("\\d{6,12}")).isTrue();
    }

    @Test
    void amountAgainstDueRespectsPartialAndOverpaymentFlags() {
        BigDecimal due = new BigDecimal("100.00");
        Lifecycle.checkAgainstDue(new BigDecimal("100"), due, false, false);
        assertThatThrownBy(() -> Lifecycle.checkAgainstDue(new BigDecimal("99.99"), due, false, true))
                .isInstanceOf(ApiException.class).hasMessageContaining("full amount");
        assertThatThrownBy(() -> Lifecycle.checkAgainstDue(new BigDecimal("100.01"), due, true, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("more than");
        Lifecycle.checkAgainstDue(new BigDecimal("50"), due, true, false);
        Lifecycle.checkAgainstDue(new BigDecimal("150"), due, false, true);
    }

    @Test
    void paymentStateMachine() {
        Lifecycle.payment("PROCESSING", "PENDING_PROVIDER");
        Lifecycle.payment("PENDING_PROVIDER", "COMPLETED");
        Lifecycle.payment("COMPLETED", "REVERSED");
        assertThatThrownBy(() -> Lifecycle.payment("FAILED", "COMPLETED")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Lifecycle.settlement("CALCULATED", "PAID")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Lifecycle.biller("ACTIVE", "ACTIVE")).isInstanceOf(ApiException.class);
    }
}
