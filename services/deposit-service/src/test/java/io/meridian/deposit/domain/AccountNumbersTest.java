package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class AccountNumbersTest {

    @Test
    void luhnKnownValues() {
        assertThat(AccountNumbers.luhnCheckDigit("7992739871")).isEqualTo(3);
        assertThat(AccountNumbers.isValidLuhn("79927398713")).isTrue();
        assertThat(AccountNumbers.isValidLuhn("79927398710")).isFalse();
        assertThat(AccountNumbers.isValidLuhn("4539578763621486")).isTrue();
    }

    @Test
    void ibanKnownValuesAndGeneration() {
        assertThat(AccountNumbers.isValidIban("GB82WEST12345698765432")).isTrue();
        assertThat(AccountNumbers.isValidIban("GB82 WEST 1234 5698 7654 32")).isTrue();
        assertThat(AccountNumbers.isValidIban("GB82WEST12345698765433")).isFalse();
        assertThat(AccountNumbers.isValidIban("DE89370400440532013000")).isTrue();
        assertThat(AccountNumbers.iban("GB", "WEST", "123456", "98765432")).contains("GB82WEST12345698765432");
        assertThat(AccountNumbers.iban("DE", "37040044", null, "532013000")).contains("DE89370400440532013000");
        assertThat(AccountNumbers.iban("US", "X", null, "123")).isEmpty();
    }

    @Property(tries = 500)
    void generatedNumbersPassLuhnAndHaveTheRequestedLength(@ForAll @IntRange(min = 2, max = 20) int length,
                                                           @ForAll long seed) {
        String n = AccountNumbers.generate(length, new Random(seed));
        assertThat(n).hasSize(length).doesNotStartWith("0");
        assertThat(AccountNumbers.isValidLuhn(n)).isTrue();
    }

    /** A Luhn check digit catches every single-digit substitution. */
    @Property(tries = 500)
    void luhnDetectsSingleDigitErrors(@ForAll @LongRange(min = 100000000L, max = 999999999L) long payload,
                                      @ForAll @IntRange(min = 0, max = 9) int position,
                                      @ForAll @IntRange(min = 1, max = 9) int delta) {
        String valid = AccountNumbers.withLuhn(Long.toString(payload));
        char[] c = valid.toCharArray();
        c[position] = (char) ('0' + (c[position] - '0' + delta) % 10);
        assertThat(AccountNumbers.isValidLuhn(new String(c))).isFalse();
    }

    @Property(tries = 300)
    void generatedIbansAreValid(@ForAll @LongRange(min = 10000000L, max = 99999999L) long account) {
        String gb = AccountNumbers.iban("GB", "MERI", "000001", Long.toString(account)).orElseThrow();
        assertThat(gb).hasSize(22);
        assertThat(AccountNumbers.isValidIban(gb)).isTrue();
        String nl = AccountNumbers.iban("NL", "MERI", null, Long.toString(account)).orElseThrow();
        assertThat(AccountNumbers.isValidIban(nl)).isTrue();
    }
}
