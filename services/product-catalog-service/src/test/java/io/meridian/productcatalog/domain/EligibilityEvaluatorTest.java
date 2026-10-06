package io.meridian.productcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.productcatalog.domain.eligibility.EligibilityEvaluator;
import io.meridian.productcatalog.domain.eligibility.EligibilityEvaluator.Outcome;
import io.meridian.productcatalog.domain.eligibility.EligibilityEvaluator.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class EligibilityEvaluatorTest {
    static final LocalDate D = LocalDate.of(2026, 10, 6);

    static Rule rule(String type, String op, Object value, boolean hard) {
        return new Rule(UUID.randomUUID(), type, op, EligibilityEvaluator.normalizeValue(value), null, hard, null, null, null, null);
    }

    static List<Rule> standard() {
        return List.of(rule("AGE", "BETWEEN", Map.of("min", 18, "max", 70), true),
                rule("RESIDENCY", "IN", Map.of("values", List.of("NG", "GH")), true),
                rule("KYC_TIER", "GTE", Map.of("value", 2), true),
                rule("SEGMENT", "NOT_IN", Map.of("values", List.of("CORPORATE")), false));
    }

    @Test
    void eligibleApplicant() {
        var e = EligibilityEvaluator.evaluate(standard(),
                Map.of("age", 30, "residency_country", "ng", "kyc_tier", "3", "segment", "RETAIL"), D);
        assertThat(e.outcome()).isEqualTo(Outcome.ELIGIBLE);
        assertThat(e.reasons()).isEmpty();
    }

    @Test
    void hardFailureIsIneligibleSoftFailureIsRefer() {
        var hard = EligibilityEvaluator.evaluate(standard(),
                Map.of("age", 17, "residency_country", "NG", "kyc_tier", 3, "segment", "RETAIL"), D);
        assertThat(hard.outcome()).isEqualTo(Outcome.INELIGIBLE);
        assertThat(hard.reasons()).containsExactly("FAILED_AGE");
        var soft = EligibilityEvaluator.evaluate(standard(),
                Map.of("age", 40, "residency_country", "GH", "kyc_tier", 2, "segment", "CORPORATE"), D);
        assertThat(soft.outcome()).isEqualTo(Outcome.REFER);
    }

    @Test
    void missingFactsReferAndAgeDerivesFromDateOfBirth() {
        var missing = EligibilityEvaluator.evaluate(standard(), Map.of("residency_country", "NG", "kyc_tier", 2), D);
        assertThat(missing.outcome()).isEqualTo(Outcome.REFER);
        assertThat(missing.reasons()).contains("MISSING_AGE");
        var dob = EligibilityEvaluator.evaluate(List.of(rule("AGE", "GTE", Map.of("value", 18), true)),
                Map.of("date_of_birth", "2008-10-07"), D);
        assertThat(dob.outcome()).isEqualTo(Outcome.INELIGIBLE); // turns 18 tomorrow
        assertThat(dob.rules().getFirst().actual()).isEqualTo(BigDecimal.valueOf(17));
    }

    @Test
    void existingProductsAndCustomExpressionAndDecisionTables() {
        var holds = EligibilityEvaluator.evaluate(List.of(rule("EXISTING_PRODUCT", "IN", Map.of("values", List.of("CUR-1")), true)),
                Map.of("existing_products", List.of("SAV-1", "CUR-1")), D);
        assertThat(holds.outcome()).isEqualTo(Outcome.ELIGIBLE);
        Rule expr = new Rule(UUID.randomUUID(), "CUSTOM_EXPRESSION", "EQ", null,
                "income >= 50000 && segment == 'RETAIL' || credit_score > 750", true, "LOW_INCOME", null, null, null);
        assertThat(EligibilityEvaluator.evaluate(List.of(expr), Map.of("income", 60000, "segment", "RETAIL",
                "credit_score", 600), D).outcome()).isEqualTo(Outcome.ELIGIBLE);
        assertThat(EligibilityEvaluator.evaluate(List.of(expr), Map.of("income", 10000, "segment", "RETAIL",
                "credit_score", 600), D).reasons()).containsExactly("LOW_INCOME");
        Rule dt = new Rule(UUID.randomUUID(), "CREDIT_SCORE", "EQ", null, null, true, "DT_DECLINE", null, UUID.randomUUID(), false);
        assertThat(EligibilityEvaluator.evaluate(List.of(dt), Map.of(), D).outcome()).isEqualTo(Outcome.INELIGIBLE);
    }

    @Property(tries = 200)
    void betweenMatchesInclusiveBounds(@ForAll @IntRange(min = 0, max = 120) int age) {
        var e = EligibilityEvaluator.evaluate(List.of(rule("AGE", "BETWEEN", Map.of("min", 18, "max", 65), true)),
                Map.of("age", age), D);
        assertThat(e.outcome() == Outcome.ELIGIBLE).isEqualTo(age >= 18 && age <= 65);
    }
}
