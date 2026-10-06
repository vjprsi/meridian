package io.meridian.liquidity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.liquidity.domain.StressEngine.Line;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StressEngineTest {
    static final List<Line> LINES = List.of(
            new Line("govies", "L1", new BigDecimal("1000"), null),
            new Line("covered", "L2A", new BigDecimal("200"), null),
            new Line("retail", "RETAIL_LESS_STABLE", new BigDecimal("3000"), null),
            new Line("interbank", "FINANCIAL_INSTITUTION", new BigDecimal("600"), 10),
            new Line("loan repayment", "CORPORATE_LOANS", new BigDecimal("400"), 5));

    /**
     * Capacity 1000 + 200 x 85% = 1170; retail 3000 x 30% = 900 runs off 30/day; interbank 600 on day 10; loan inflow
     * 400 x 50% = 200 on day 5. Day 10: 1170 + 200 - 300 - 600 = 470; day 25: 20; day 26: -10 -> survives 25 days.
     */
    @Test
    void linearRunOffSurvivalHorizon() {
        StressEngine.Assumptions a = StressEngine.validate(
                Map.of("runoff_rates", Map.of("RETAIL_LESS_STABLE", "0.30")), 30);
        StressEngine.Result r = StressEngine.run(a, 30, LINES);
        assertThat(r.counterbalancingCapacity()).isEqualByComparingTo("1170");
        assertThat(r.stressedOutflows()).isEqualByComparingTo("1500");
        assertThat(r.stressedInflows()).isEqualByComparingTo("200");
        assertThat(r.profile().get(9).cumulativePosition()).isEqualByComparingTo("470");
        assertThat(r.profile().get(24).cumulativePosition()).isEqualByComparingTo("20");
        assertThat(r.survivalDays()).isEqualTo(25);
        assertThat(r.netPosition()).isEqualByComparingTo("-130");
        assertThat(r.breach()).isTrue();
    }

    @Test
    void upfrontProfileAndMinimumSurvival() {
        StressEngine.Assumptions a = StressEngine.validate(Map.of("runoff_rates", Map.of("RETAIL_LESS_STABLE", "0.30"),
                "runoff_profile", "UPFRONT", "min_survival_days", 7), 30);
        StressEngine.Result r = StressEngine.run(a, 30, LINES);
        assertThat(r.profile().getFirst().cumulativePosition()).isEqualByComparingTo("270");
        assertThat(r.survivalDays()).isEqualTo(9);
        assertThat(r.breach()).isFalse();
    }

    @Test
    void defaultRatesWithMultiplierAndHaircutOverride() {
        StressEngine.Assumptions a = StressEngine.validate(Map.of("runoff_multiplier", 2,
                "hqla_haircuts", Map.of("L2A", "0.50")), 10);
        StressEngine.Result r = StressEngine.run(a, 10, List.of(
                new Line("1", "L1", new BigDecimal("100"), null), new Line("2", "L2A", new BigDecimal("100"), null),
                new Line("3", "RETAIL_STABLE", new BigDecimal("1000"), null)));
        assertThat(r.counterbalancingCapacity()).isEqualByComparingTo("150");
        assertThat(r.stressedOutflows()).isEqualByComparingTo("100");
        assertThat(r.survivalDays()).isEqualTo(11);
        assertThat(r.breach()).isFalse();
    }

    @Test
    void validatesAssumptions() {
        assertThatThrownBy(() -> StressEngine.validate(Map.of("magic", 1), 30)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StressEngine.validate(Map.of("runoff_rates", Map.of("RETAIL_STABLE", "1.2")), 30))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StressEngine.validate(Map.of("runoff_rates", Map.of("NOPE", "0.2")), 30))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StressEngine.validate(Map.of("runoff_profile", "SIDEWAYS"), 30))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StressEngine.validate(Map.of("min_survival_days", 31), 30))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StressEngine.validate(Map.of(), 0)).extracting("code").isEqualTo("invalid_horizon");
    }
}
