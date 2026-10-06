package io.meridian.loanorigination.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffordabilityCalculatorTest {
    static final AffordabilityCalculator.Policy POLICY = AffordabilityCalculator.Policy.standard();

    static AffordabilityCalculator.Input input(List<AffordabilityCalculator.Item> items, String amount, int term,
                                               String debtService, String totalDebt) {
        return new AffordabilityCalculator.Input(items, null, 1, debtService == null ? null : new BigDecimal(debtService),
                totalDebt == null ? null : new BigDecimal(totalDebt), new BigDecimal(amount), term, new BigDecimal("0.10"), "EUR");
    }

    static AffordabilityCalculator.Item item(String type, String amount, String freq, boolean verified) {
        return new AffordabilityCalculator.Item(type, new BigDecimal(amount), freq, verified);
    }

    @Test
    void comfortableApplicantPasses() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(List.of(
                item("SALARY", "4000", "MONTHLY", true), item("RENT", "900", "MONTHLY", false),
                item("LIVING_COSTS", "700", "MONTHLY", false)), "10000", 36, "150", "5000"), POLICY);
        assertThat(r.result()).isEqualTo("PASS");
        assertThat(r.monthlyIncome()).isEqualByComparingTo("4000.00");
        assertThat(r.commitments()).isEqualByComparingTo("1050.00");
        assertThat(r.livingCosts()).isEqualByComparingTo("700.00");
        assertThat(r.stressedInstalment()).isGreaterThan(r.instalment());
        assertThat(r.stressRate()).isEqualByComparingTo("0.13");
        // DSR = (1050 + stressed) / 4000; DTI = (5000 + 10000) / 48000
        assertThat(r.dsr().doubleValue()).isBetween(0.30, 0.36);
        assertThat(r.dti()).isEqualByComparingTo("0.312500");
        assertThat(r.residualIncome()).isEqualByComparingTo(new BigDecimal("4000").subtract(new BigDecimal("1050"))
                .subtract(new BigDecimal("700")).subtract(r.stressedInstalment()));
        assertThat(r.maxAffordableAmount()).isGreaterThan(new BigDecimal("10000"));
        assertThat(r.reasonCodes()).isEmpty();
    }

    @Test
    void unaffordableRequestFailsButStillHasAnAffordableAmount() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(List.of(
                item("SALARY", "3000", "MONTHLY", true), item("MORTGAGE", "1000", "MONTHLY", false)),
                "30000", 24, "200", "2000"), POLICY);
        assertThat(r.result()).isEqualTo("FAIL");
        assertThat(r.reasonCodes()).contains("DSR_ABOVE_MAX");
        assertThat(r.maxAffordableAmount()).isPositive().isLessThan(new BigDecimal("30000"));
        // the max affordable amount is itself affordable: its stressed instalment fits the DSR capacity
        BigDecimal stressed = OfferPricing.annuity(r.maxAffordableAmount(), new BigDecimal("0.13").divide(BigDecimal.valueOf(12),
                java.math.MathContext.DECIMAL128), 24);
        assertThat(r.commitments().add(stressed).divide(r.monthlyIncome(), java.math.MathContext.DECIMAL64).doubleValue())
                .isLessThanOrEqualTo(0.4501);
    }

    @Test
    void unverifiedIncomeIsHaircutAndFrequenciesAreNormalised() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(List.of(
                item("SALARY", "1200", "WEEKLY", false), item("RENTAL_INCOME", "3000", "QUARTERLY", true),
                item("OTHER_INCOME", "5000", "ONE_OFF", true)), "1000", 12, null, null), POLICY);
        // 1200 * 52 / 12 * 0.8 + 3000 / 3 = 4160 + 1000
        assertThat(r.monthlyIncome()).isEqualByComparingTo("5160.00");
        // living costs floor: 400 + 150 per dependant
        assertThat(r.livingCosts()).isEqualByComparingTo("550.00");
    }

    @Test
    void bureauDebtServiceWinsOverLowerDeclaredRepayments() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(List.of(
                item("SALARY", "5000", "MONTHLY", true), item("LOAN_REPAYMENT", "100", "MONTHLY", false)),
                "5000", 12, "450", "8000"), POLICY);
        assertThat(r.commitments()).isEqualByComparingTo("450.00");
    }

    @Test
    void noIncomeFails() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(input(List.of(), "1000", 12, null, null), POLICY);
        assertThat(r.result()).isEqualTo("FAIL");
        assertThat(r.reasonCodes()).containsExactly("NO_VERIFIABLE_INCOME");
        assertThat(r.maxAffordableAmount()).isEqualByComparingTo("0");
    }

    @Test
    void declaredNetIncomeIsUsedWhenNoIncomeItemsExist() {
        AffordabilityCalculator.Result r = AffordabilityCalculator.assess(new AffordabilityCalculator.Input(List.of(),
                new BigDecimal("2500"), 0, null, null, new BigDecimal("2000"), 12, new BigDecimal("0.10"), "EUR"), POLICY);
        assertThat(r.monthlyIncome()).isEqualByComparingTo("2000.00");
        assertThat(r.result()).isIn("PASS", "MARGINAL");
    }

    @Test
    void decisionPolicyPicksTheMostSevereOutcome() {
        DecisionPolicy.Facts clean = new DecisionPolicy.Facts("INSTALMENT", 35, "RECEIVED", 0, 0, 1, "APPROVE", "PASS",
                new BigDecimal("50000"), new BigDecimal("10000"), new BigDecimal("1000"), new BigDecimal("100"));
        assertThat(DecisionPolicy.evaluate(clean).outcome()).isEqualTo("APPROVED");
        assertThat(DecisionPolicy.evaluate(clean).approvedAmount()).isEqualByComparingTo("10000");
        DecisionPolicy.Outcome counter = DecisionPolicy.evaluate(new DecisionPolicy.Facts("INSTALMENT", 35, "RECEIVED", 0, 0, 1,
                "APPROVE", "FAIL", new BigDecimal("6789.55"), new BigDecimal("10000"), new BigDecimal("1000"), new BigDecimal("100")));
        assertThat(counter.outcome()).isEqualTo("COUNTER_OFFER");
        assertThat(counter.approvedAmount()).isEqualByComparingTo("6700");
        assertThat(DecisionPolicy.evaluate(new DecisionPolicy.Facts("INSTALMENT", 35, "RECEIVED", 120, 2, 1, "APPROVE", "PASS",
                null, new BigDecimal("10000"), null, null)).outcome()).isEqualTo("DECLINED");
        assertThat(DecisionPolicy.evaluate(new DecisionPolicy.Facts("INSTALMENT", 35, "NO_HIT", null, null, null, "APPROVE", "PASS",
                null, new BigDecimal("10000"), null, null)).outcome()).isEqualTo("DECLINED");
        assertThat(DecisionPolicy.evaluate(new DecisionPolicy.Facts("NANO", 35, "NO_HIT", null, null, null, "APPROVE", "PASS",
                null, new BigDecimal("100"), null, null)).outcome()).isEqualTo("APPROVED");
        assertThat(DecisionPolicy.evaluate(new DecisionPolicy.Facts("INSTALMENT", 17, "RECEIVED", 0, 0, 0, "APPROVE", "PASS",
                null, new BigDecimal("100"), null, null)).failed()).extracting(DecisionPolicy.Rule::code).contains("MINIMUM_AGE");
        assertThat(DecisionPolicy.evaluate(new DecisionPolicy.Facts("INSTALMENT", 35, "RECEIVED", 30, 0, 0, "APPROVE", "PASS",
                null, new BigDecimal("100"), null, null)).outcome()).isEqualTo("REFER");
    }
}
