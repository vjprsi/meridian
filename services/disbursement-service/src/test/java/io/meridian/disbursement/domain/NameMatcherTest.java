package io.meridian.disbursement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import org.junit.jupiter.api.Test;

class NameMatcherTest {

    @Test
    void caseAccentsPunctuationAndOrderAreIgnored() {
        assertThat(NameMatcher.matches("KAMAU, John", "John Kamau")).isTrue();
        assertThat(NameMatcher.matches("José Müller-Ñúñez", "jose muller nunez")).isTrue();
        assertThat(NameMatcher.matches("Mr. John Kamau", "John Kamau")).isTrue();
    }

    @Test
    void initialsAndSubsetsOfLongerNames() {
        assertThat(NameMatcher.matches("J. Kamau", "John Kamau")).isTrue();
        assertThat(NameMatcher.matches("John Kamau", "John Mwangi Kamau")).isTrue();
        assertThat(NameMatcher.matches("J K", "John Kamau")).isFalse(); // initials only: no full token
    }

    @Test
    void oneTypoInLongTokens() {
        assertThat(NameMatcher.matches("Achieng Otieno", "Achieng Otienno")).isTrue();
        assertThat(NameMatcher.matches("Ann Otieno", "Amy Otieno")).isFalse(); // short tokens must be exact
    }

    @Test
    void mismatches() {
        assertThat(NameMatcher.matches("Peter Njoroge", "John Kamau")).isFalse();
        assertThat(NameMatcher.matches("Kamau", "John Kamau")).isFalse(); // single token vs full name
        assertThat(NameMatcher.matches("Kamau", "kamau")).isTrue();
        assertThat(NameMatcher.matches("", "John")).isFalse();
        assertThat(NameMatcher.matches(null, "John")).isFalse();
    }

    @Test
    void levenshtein() {
        assertThat(NameMatcher.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(NameMatcher.distance("same", "same")).isZero();
    }

    @Test
    void lifecycleTransitionsAndOutcome() {
        Lifecycle.batch("VALIDATED", "PENDING_APPROVAL");
        assertThatThrownBy(() -> Lifecycle.batch("DRAFT", "PENDING_APPROVAL"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_transition");
        assertThatThrownBy(() -> Lifecycle.batch("PROCESSING", "CANCELLED")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Lifecycle.program("CLOSED", "ACTIVE")).isInstanceOf(ApiException.class);
        assertThat(Lifecycle.outcome(3, 0)).isEqualTo("COMPLETED");
        assertThat(Lifecycle.outcome(0, 2)).isEqualTo("FAILED");
        assertThat(Lifecycle.outcome(1, 1)).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(Lifecycle.txnType("SOCIAL_TRANSFER")).isEqualTo("G2P");
        assertThat(Lifecycle.txnType("PAYROLL")).isEqualTo("BULK_DISBURSEMENT");
    }
}
