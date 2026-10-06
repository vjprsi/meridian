package io.meridian.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class LuhnAndPanTest {

    @Test
    void knownNumbers() {
        assertThat(Luhn.isValid("4111111111111111")).isTrue();
        assertThat(Luhn.isValid("5555555555554444")).isTrue();
        assertThat(Luhn.isValid("378282246310005")).isTrue();
        assertThat(Luhn.isValid("4111111111111112")).isFalse();
        assertThat(Luhn.isValid("79927398713")).isTrue();
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo(3);
        assertThat(Luhn.isValid("41111111111111a1")).isFalse();
        assertThat(Luhn.isValid("4")).isFalse();
        assertThat(Luhn.isValid(null)).isFalse();
    }

    @Property(tries = 300)
    void anySingleDigitChangeIsDetected(@ForAll("payloads") String payload, @ForAll("positions") int pos) {
        String pan = payload + Luhn.checkDigit(payload);
        int i = pos % pan.length();
        char c = pan.charAt(i);
        String mutated = pan.substring(0, i) + (char) ('0' + (c - '0' + 1) % 10) + pan.substring(i + 1);
        assertThat(Luhn.isValid(pan)).isTrue();
        assertThat(Luhn.isValid(mutated)).isFalse();
    }

    record Range(String start, String end, int panLength) {}

    @Provide
    Arbitrary<String> payloads() {
        return Arbitraries.strings().numeric().ofMinLength(12).ofMaxLength(18);
    }

    @Provide
    Arbitrary<Integer> positions() {
        return Arbitraries.integers().between(0, 100);
    }

    @Provide
    Arbitrary<Range> ranges() {
        Arbitrary<String> bin = Arbitraries.strings().numeric().ofLength(6).filter(s -> s.charAt(0) != '0');
        Arbitrary<Integer> extra = Arbitraries.integers().between(0, 4);
        Arbitrary<Integer> length = Arbitraries.integers().between(13, 19);
        Arbitrary<Long> seed = Arbitraries.longs();
        return Combinators.combine(bin, extra, length, seed).as((b, e, len, s) -> {
            Random r = new Random(s);
            String a = b + digits(r, e);
            String z = b + digits(r, e);
            if (a.compareTo(z) > 0) {
                String t = a;
                a = z;
                z = t;
            }
            return new Range(a, z, Math.max(len, a.length() + 2));
        });
    }

    private static String digits(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(r.nextInt(10));
        }
        return sb.toString();
    }

    @Property(tries = 500)
    void generatedPansAreLuhnValidInRangeWithBinAndLength(@ForAll("ranges") Range range, @ForAll long seed) {
        String pan = new PanGenerator(new Random(seed)).generate(range.start(), range.end(), range.panLength());
        assertThat(pan).hasSize(range.panLength()).containsOnlyDigits();
        assertThat(Luhn.isValid(pan)).isTrue();
        assertThat(pan).startsWith(range.start().substring(0, 6));
        BigInteger prefix = new BigInteger(pan.substring(0, range.start().length()));
        assertThat(prefix).isBetween(new BigInteger(range.start()), new BigInteger(range.end()));
    }

    @Test
    void generatorSpreadsOverTheRange() {
        PanGenerator g = new PanGenerator(new Random(7));
        Set<String> prefixes = new HashSet<>();
        Set<String> pans = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String pan = g.generate("45391000", "45391009", 16);
            prefixes.add(pan.substring(0, 8));
            pans.add(pan);
        }
        assertThat(prefixes).hasSize(10);
        assertThat(pans).hasSizeGreaterThan(1990);
    }

    @Test
    void rangeValidation() {
        ProgramRules.validateBin("453910", "4539100", "4539109", 16);
        assertThatThrownBy(() -> ProgramRules.validateBin("45391", "4539100", "4539109", 16))
                .isInstanceOf(ApiException.class).hasMessageContaining("6 or 8");
        assertThatThrownBy(() -> ProgramRules.validateBin("453910", "5539100", "5539109", 16))
                .hasMessageContaining("start with the BIN");
        assertThatThrownBy(() -> ProgramRules.validateBin("453910", "4539109", "4539100", 16))
                .hasMessageContaining("must not exceed");
        assertThatThrownBy(() -> ProgramRules.validateBin("453910", "45391000", "4539109", 16))
                .hasMessageContaining("same length");
        assertThatThrownBy(() -> ProgramRules.validateBin("453910", "4539100", "4539109", 12))
                .hasMessageContaining("pan_length");
        assertThatThrownBy(() -> ProgramRules.validateBin("453910", "4539100000000000", "4539100000000000", 16))
                .hasMessageContaining("shorter than pan_length");
    }

    @Test
    void fundingModelAccountTypes() {
        ProgramRules.validateFunding("DEBIT", "DEPOSIT_ACCOUNT");
        ProgramRules.validateFunding("CREDIT", "CREDIT_LINE");
        ProgramRules.validateFunding("JIT", "JIT_PROGRAM");
        assertThatThrownBy(() -> ProgramRules.validateFunding("CREDIT", "DEPOSIT_ACCOUNT"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("linked_account_type_mismatch");
    }
}
