package io.meridian.reconciliation.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.text.SafeRegex;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Bounded subset-sum, name similarity and reference tokens. */
class SubsetSumAndSimilarityTest {

    @Test
    void uniqueAmbiguousAndNotFound() {
        SubsetSum.Result r = SubsetSum.find(new long[] {100, 200, 450, 975}, 750, 2, 4, 10_000);
        assertThat(r.status()).isEqualTo(SubsetSum.Status.UNIQUE);
        assertThat(r.indices()).containsExactly(0, 1, 2);

        SubsetSum.Result twins = SubsetSum.find(new long[] {100, 100, 50}, 150, 2, 3, 10_000);
        assertThat(twins.status()).as("equal amounts are different items").isEqualTo(SubsetSum.Status.AMBIGUOUS);

        assertThat(SubsetSum.find(new long[] {100, 200}, 50, 2, 2, 1000).status()).isEqualTo(SubsetSum.Status.NOT_FOUND);
        assertThat(SubsetSum.find(new long[] {}, 50, 2, 2, 1000).status()).isEqualTo(SubsetSum.Status.NOT_FOUND);
        assertThat(SubsetSum.find(new long[] {50}, 50, 2, 2, 1000).status()).as("min size").isEqualTo(SubsetSum.Status.NOT_FOUND);
        assertThat(SubsetSum.find(new long[] {10, 20, 30, 40}, 100, 2, 3, 1000).status())
                .as("max size 3 excludes the 4-item solution").isEqualTo(SubsetSum.Status.NOT_FOUND);
        assertThatThrownBy(() -> SubsetSum.find(new long[] {10, -5}, 5, 2, 2, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchIsBoundedByTheNodeBudget() {
        Random rnd = new Random(3);
        long[] v = new long[60];
        for (int i = 0; i < v.length; i++) {
            v[i] = 2L * (500_000 + rnd.nextInt(500_000)); // even amounts: an odd target is unreachable
        }
        long t0 = System.nanoTime();
        // an unreachable odd target among large values: the search must stop at the budget
        SubsetSum.Result r = SubsetSum.find(v, 25_000_001L, 2, 30, 50_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.nodes()).isLessThanOrEqualTo(50_001);
        assertThat(r.status()).isIn(SubsetSum.Status.BUDGET_EXHAUSTED, SubsetSum.Status.NOT_FOUND, SubsetSum.Status.AMBIGUOUS);
        assertThat(ms).isLessThan(2_000);
        assertThat(r.status() == SubsetSum.Status.UNIQUE).as("never claims uniqueness after a cut-off search").isFalse();
    }

    @Test
    void nameSimilarityHandlesTyposLegalFormsInitialsAndOrder() {
        assertThat(NameSimilarity.score("Acme Trading Ltd", "ACME TRADING LIMITED")).isEqualTo(1.0);
        assertThat(NameSimilarity.score("Acme Trading Ltd", "Acme Tradnig Ltd")).isGreaterThan(0.9);
        assertThat(NameSimilarity.score("John Smith", "SMITH JOHN")).isEqualTo(1.0);
        assertThat(NameSimilarity.score("John Smith", "J Smith")).isGreaterThan(0.9);
        assertThat(NameSimilarity.score("Müller GmbH", "MUELLER")).isGreaterThan(0.8);
        assertThat(NameSimilarity.score("John Smith", "Maria Smith")).isLessThan(0.7);
        assertThat(NameSimilarity.score("Acme Trading", "Acme Travel")).isLessThan(0.8);
        assertThat(NameSimilarity.score("Nordic Logistics", "Helios Media")).isLessThan(0.3);
        assertThat(NameSimilarity.score(null, "x")).isNull();
        assertThat(NameSimilarity.jaroWinkler("MARTHA", "MARHTA")).isBetween(0.96, 0.97);
        assertThat(NameSimilarity.levenshteinRatio("kitten", "sitting")).isBetween(0.57, 0.58);
    }

    @Test
    void referenceTokensNeedADigitAndTheStandardPatternsAreSafe() {
        assertThat(Keys.norm(" inv-2026/00123 ")).isEqualTo("INV202600123");
        assertThat(Keys.tokens("Payment for invoice INV 2026/00123", java.util.List.of())).contains("INV202600123")
                .doesNotContain("PAYMENT", "INVOICE");
        assertThat(Keys.tokens("BULK PAYMENT BATCH-ABCDEFGH", RuleSet.STANDARD_PATTERNS)).contains("BATCHABCDEFGH");
        assertThat(Keys.tokens("VISA NET SETTLEMENT", RuleSet.STANDARD_PATTERNS)).isEmpty();
        RuleSet.STANDARD_PATTERNS.forEach(p -> assertThat(SafeRegex.problem(p)).as(p).isEmpty());
    }
}
