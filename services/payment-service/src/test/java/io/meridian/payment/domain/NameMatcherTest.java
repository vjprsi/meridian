package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class NameMatcherTest {
    static final BigDecimal MATCH = new BigDecimal("0.98");
    static final BigDecimal CLOSE = new BigDecimal("0.80");

    private static NameMatcher.Outcome outcome(String provided, String actual) {
        return NameMatcher.match(provided, actual, MATCH, CLOSE).outcome();
    }

    @Test
    void exactAfterNormalisation() {
        assertThat(outcome("John Smith", "JOHN SMITH")).isEqualTo(NameMatcher.Outcome.MATCH);
        assertThat(outcome("Mr. John  Smith", "John Smith")).isEqualTo(NameMatcher.Outcome.MATCH);
        assertThat(outcome("José Álvarez", "Jose Alvarez")).isEqualTo(NameMatcher.Outcome.MATCH);
        assertThat(outcome("Acme Widgets Ltd", "ACME WIDGETS LIMITED")).isEqualTo(NameMatcher.Outcome.MATCH);
        assertThat(outcome("Smith John", "John Smith")).isEqualTo(NameMatcher.Outcome.MATCH);
        assertThat(NameMatcher.match("John Smith", "John Smith", MATCH, CLOSE).score()).isEqualByComparingTo("1");
    }

    @Test
    void closeMatchesForTyposAndInitials() {
        assertThat(outcome("Jon Smith", "John Smith")).isEqualTo(NameMatcher.Outcome.CLOSE_MATCH);
        assertThat(outcome("J Smith", "John Smith")).isEqualTo(NameMatcher.Outcome.CLOSE_MATCH);
        assertThat(outcome("Carol Whyte", "Carol White")).isEqualTo(NameMatcher.Outcome.CLOSE_MATCH);
        assertThat(outcome("John Smyth", "John Smith")).isEqualTo(NameMatcher.Outcome.CLOSE_MATCH);
    }

    @Test
    void noMatchForDifferentPeople() {
        assertThat(outcome("Jane Doe", "John Smith")).isEqualTo(NameMatcher.Outcome.NO_MATCH);
        assertThat(outcome("Totally Different", "Bob Jones")).isEqualTo(NameMatcher.Outcome.NO_MATCH);
        assertThat(outcome("", "Bob Jones")).isEqualTo(NameMatcher.Outcome.NO_MATCH);
    }

    @Test
    void jaroWinklerReferenceValues() {
        assertThat(NameMatcher.jaroWinkler("MARTHA", "MARHTA")).isBetween(0.960, 0.962);
        assertThat(NameMatcher.jaroWinkler("DWAYNE", "DUANE")).isBetween(0.839, 0.841);
        assertThat(NameMatcher.jaroWinkler("ABC", "XYZ")).isZero();
    }

    @Test
    void masking() {
        assertThat(NameMatcher.mask("Alice Smith")).isEqualTo("A**** S****");
        assertThat(NameMatcher.tokens("The Acme & Co, Ltd.")).containsExactly("ACME");
    }
}
