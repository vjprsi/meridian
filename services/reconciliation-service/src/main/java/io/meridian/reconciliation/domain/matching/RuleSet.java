package io.meridian.reconciliation.domain.matching;

import java.util.List;
import java.util.UUID;

/**
 * An ordered, versioned set of matching rules with the confidence thresholds: a proposal at or above
 * {@code autoThreshold} (or its rule's own threshold) that is unambiguous is auto-confirmed, one at or above
 * {@code suggestThreshold} is kept as a suggestion on the break.
 */
public record RuleSet(UUID id, int version, double autoThreshold, double suggestThreshold, List<MatchRule> rules) {

    public RuleSet {
        rules = List.copyOf(rules);
    }

    /** Default remittance reference patterns: invoice / order / RF creditor reference / batch and settlement ids. */
    public static final List<String> STANDARD_PATTERNS = List.of(
            "(?i)\\b(?:INV|INVOICE|RECHNUNG|FACTURE|FACT)\\s*(?:NO\\.?|NR\\.?|#|:)?\\s*([A-Z0-9][A-Z0-9/.\\-]{3,30})",
            "(?i)\\b(RF\\d{2}[A-Z0-9]{1,21})\\b",
            "(?i)\\b(?:ORDER|PO|P\\.O\\.)\\s*(?:NO\\.?|#|:)?\\s*([A-Z0-9][A-Z0-9\\-]{3,30})",
            "(?i)\\b((?:BATCH|BULK|SETTLE|STL|SWEEP)[\\-_][A-Z0-9][A-Z0-9\\-]{3,30})");

    /**
     * The standard ordered rules (template for new rule sets and the benchmark): exact keys first (UETR, end-to-end /
     * instruction id, bank reference), keyed batch and bulk grouping, reversal pairing, remittance references,
     * fuzzy name + amount + date (exact amount, then with a small fee), and a keyless subset-sum suggestion rule.
     */
    public static RuleSet standard() {
        return new RuleSet(null, 0, 0.90, 0.50, List.of(
                MatchRule.builder("exact_uetr", MatchRule.Type.EXACT_KEY).keys("uetr")
                        .fee("50", "1").fxPct("2").window(7).build(),
                MatchRule.builder("exact_end_to_end", MatchRule.Type.EXACT_KEY).keys("end_to_end_id", "instruction_id")
                        .fee("50", "1").fxPct("2").window(5).build(),
                MatchRule.builder("exact_bank_ref", MatchRule.Type.EXACT_KEY).keys("bank_ref")
                        .fee("25", "0").fxPct("1").window(5).build(),
                MatchRule.builder("batch_keyed", MatchRule.Type.ONE_TO_MANY)
                        .keys("batch_ref", "bank_ref", "end_to_end_id", "remittance").fee("0", "3").window(3)
                        .group(MatchRule.MAX_GROUP, 40, 500_000).build(),
                MatchRule.builder("bulk_keyed", MatchRule.Type.MANY_TO_ONE)
                        .keys("batch_ref", "bank_ref", "end_to_end_id", "remittance").window(3)
                        .group(50, 30, 200_000).build(),
                MatchRule.builder("reversal", MatchRule.Type.REVERSAL_PAIR)
                        .keys("uetr", "end_to_end_id", "instruction_id", "bank_ref").window(10).build(),
                MatchRule.builder("remittance_ref", MatchRule.Type.REFERENCE_EXTRACT).patterns(STANDARD_PATTERNS)
                        .fee("25", "0").fxPct("1").window(5).build(),
                MatchRule.builder("fuzzy_exact_amount", MatchRule.Type.FUZZY).window(3).nameThreshold(0.80).build(),
                MatchRule.builder("fuzzy_fee", MatchRule.Type.FUZZY).fee("15", "0").window(3).nameThreshold(0.90)
                        .autoThreshold(0.93).build(),
                MatchRule.builder("batch_subset", MatchRule.Type.ONE_TO_MANY).window(3).nameThreshold(0.85)
                        .group(20, 20, 100_000).build()));
    }
}
