package io.meridian.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SafeRegexTest {

    @Test
    void ordinaryPatternsWork() {
        assertThat(SafeRegex.matches("[0-9]{6,10}", "1234567")).isTrue();
        assertThat(SafeRegex.matches("[0-9]{6,10}", "12a")).isFalse();
        assertThat(SafeRegex.find("acme", Pattern.CASE_INSENSITIVE, "PAYMENT TO ACME LTD")).isTrue();
        assertThat(SafeRegex.problem("^[A-Z]{2}[0-9]{8}$")).isEmpty();
    }

    @Test
    void catastrophicPatternsAreRefusedOnSaveAndBoundedAtRunTime() {
        assertThat(SafeRegex.problem("(a+)+$")).isPresent();
        assertThat(SafeRegex.problem("(\\w*)*x")).isPresent();
        assertThat(SafeRegex.problem("([a-z]+){2,}")).isPresent();
        assertThat(SafeRegex.problem("[")).isPresent();
        long t0 = System.nanoTime();
        assertThat(SafeRegex.matches("(a+)+$", "a".repeat(40) + "!")).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000);
        assertThat(SafeRegex.matches(".*", "x".repeat(SafeRegex.MAX_INPUT + 1))).isFalse();
    }

    @Test
    void extractReturnsGroupOneOrTheMatchWithinLimits() {
        assertThat(SafeRegex.extract("INV-(\\d+)", 0, "paid INV-12 and INV-345", 10)).containsExactly("12", "345");
        assertThat(SafeRegex.extract("\\d{3}", 0, "1234567", 2)).containsExactly("123", "456");
        assertThat(SafeRegex.extract("(", 0, "x", 5)).isEmpty();
        assertThat(SafeRegex.extract("x", 0, "x".repeat(SafeRegex.MAX_INPUT + 1), 5)).isEmpty();
        long t0 = System.nanoTime();
        assertThat(SafeRegex.extract("(a+)+$", 0, "a".repeat(40) + "!", 5)).isEmpty();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000);
    }
}
