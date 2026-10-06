package io.meridian.loanorigination.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScorecardEngineTest {

    static List<Object> features() {
        return List.of(
                Map.of("name", "bureau_score", "missing_points", 0, "reason_code", "R01_BUREAU_SCORE",
                        "description", "Credit bureau score too low",
                        "bins", List.of(Map.of("max", 600, "points", 0), Map.of("min", 600, "max", 700, "points", 40),
                                Map.of("min", 700, "points", 80))),
                Map.of("name", "months_employed", "missing_points", 5, "reason_code", "R02_EMPLOYMENT",
                        "bins", List.of(Map.of("max", 12, "points", 5), Map.of("min", 12, "max", 36, "points", 20),
                                Map.of("min", 36, "points", 30))),
                Map.of("name", "residential_status", "missing_points", 0, "reason_code", "R03_RESIDENCE",
                        "bins", List.of(Map.of("values", List.of("OWNER"), "points", 25),
                                Map.of("values", List.of("TENANT", "WITH_PARENTS"), "points", 10))),
                Map.of("name", "dti", "missing_points", 0, "reason_code", "R04_DTI",
                        "bins", List.of(Map.of("max", "0.30", "points", 30), Map.of("min", "0.30", "max", "0.60", "points", 15),
                                Map.of("min", "0.60", "points", 0))));
    }

    static List<Object> cutoffs() {
        return List.of(Map.of("min_score", 700, "outcome", "APPROVE", "risk_grade", "A1", "rate_adjustment", "-0.01"),
                Map.of("min_score", 640, "outcome", "APPROVE", "risk_grade", "B1", "rate_adjustment", "0.02"),
                Map.of("min_score", 600, "outcome", "REFER", "risk_grade", "C1", "rate_adjustment", "0.05"),
                Map.of("min_score", 0, "outcome", "DECLINE", "risk_grade", "E1"));
    }

    static ScorecardEngine.Model model() {
        return ScorecardEngine.model(550, features(), cutoffs(), 600, BigDecimal.valueOf(50), BigDecimal.valueOf(20));
    }

    @Test
    void bestApplicantGetsMaximumPointsAndNoReasons() {
        ScorecardEngine.Result r = ScorecardEngine.score(model(), Map.of("bureau_score", 780, "months_employed", 60,
                "residential_status", "owner", "dti", new BigDecimal("0.10")));
        assertThat(r.score()).isEqualTo(550 + 80 + 30 + 25 + 30);
        assertThat(r.band().riskGrade()).isEqualTo("A1");
        assertThat(r.band().outcome()).isEqualTo("APPROVE");
        assertThat(r.reasons()).isEmpty();
        assertThat(r.contributions()).hasSize(4).allMatch(c -> c.points() == c.maxPoints());
    }

    @Test
    void reasonCodesRankFeaturesByPointsLostAndMissingValuesUseMissingPoints() {
        Map<String, Object> in = new HashMap<>();
        in.put("bureau_score", 620);          // 40 of 80 -> lost 40
        in.put("residential_status", "TENANT"); // 10 of 25 -> lost 15
        in.put("dti", new BigDecimal("0.75"));  // 0 of 30 -> lost 30
        // months_employed missing -> 5 of 30 -> lost 25
        ScorecardEngine.Result r = ScorecardEngine.score(model(), in);
        assertThat(r.score()).isEqualTo(550 + 40 + 5 + 10 + 0);
        assertThat(r.reasons()).extracting(ScorecardEngine.ReasonCode::code)
                .containsExactly("R01_BUREAU_SCORE", "R04_DTI", "R02_EMPLOYMENT", "R03_RESIDENCE");
        assertThat(r.reasons().getFirst().pointsLost()).isEqualTo(40);
        assertThat(r.reasons().getFirst().description()).isEqualTo("Credit bureau score too low");
        assertThat(r.contributions().stream().filter(c -> c.feature().equals("months_employed")).findFirst().orElseThrow()
                .bin()).isEqualTo("MISSING");
        assertThat(r.band().riskGrade()).isEqualTo("C1");
        assertThat(r.band().outcome()).isEqualTo("REFER");
    }

    @Test
    void scoreBelowEveryBandFallsIntoTheLowestBand() {
        ScorecardEngine.Model m = ScorecardEngine.model(0, features(), List.of(
                Map.of("min_score", 500, "outcome", "APPROVE", "risk_grade", "A"),
                Map.of("min_score", 100, "outcome", "DECLINE", "risk_grade", "E")), null, null, null);
        ScorecardEngine.Result r = ScorecardEngine.score(m, Map.of());
        assertThat(r.score()).isEqualTo(5);
        assertThat(r.band().riskGrade()).isEqualTo("E");
    }

    @Test
    void probabilityOfDefaultFollowsTheOddsCalibration() {
        ScorecardEngine.Model m = model();
        // at the target score odds are 50:1 -> PD = 1/51; every 20 points double the odds
        assertThat(ScorecardEngine.pd(m, 600)).isEqualByComparingTo("0.019608");
        assertThat(ScorecardEngine.pd(m, 620)).isEqualByComparingTo("0.009901");
        assertThat(ScorecardEngine.pd(m, 580).doubleValue()).isCloseTo(1 / 26.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(ScorecardEngine.pd(m, 700).compareTo(ScorecardEngine.pd(m, 650))).isNegative();
    }

    @Test
    void inputsHashIsCanonicalAndDeterministic() {
        Map<String, Object> a = new HashMap<>();
        a.put("b", 2);
        a.put("a", new BigDecimal("1.50"));
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("a", new BigDecimal("1.5"));
        b.put("b", 2);
        assertThat(ScorecardEngine.hash(a)).isEqualTo(ScorecardEngine.hash(b)).hasSize(64);
        assertThat(ScorecardEngine.hash(Map.of("a", 1))).isNotEqualTo(ScorecardEngine.hash(Map.of("a", 2)));
    }

    @Test
    void trafficBucketIsDeterministicAndUniformEnough() {
        assertThat(ScorecardEngine.bucket("app-1")).isEqualTo(ScorecardEngine.bucket("app-1"));
        long below = java.util.stream.IntStream.range(0, 10_000)
                .filter(i -> ScorecardEngine.bucket("application-" + i) < 0.2).count();
        assertThat(below).isBetween(1700L, 2300L);
    }

    @Test
    void invalidDefinitionsAreRejected() {
        assertThatThrownBy(() -> ScorecardEngine.model(0, List.of(), cutoffs(), null, null, null))
                .isInstanceOf(ScorecardEngine.InvalidScorecard.class);
        assertThatThrownBy(() -> ScorecardEngine.model(0, List.of(Map.of("name", "x", "bins", List.of())), cutoffs(), null, null, null))
                .hasMessageContaining("at least one bin");
        assertThatThrownBy(() -> ScorecardEngine.model(0, features(), List.of(Map.of("min_score", 1, "outcome", "MAYBE",
                "risk_grade", "A")), null, null, null)).hasMessageContaining("APPROVE");
        assertThatThrownBy(() -> ScorecardEngine.model(0, List.of(Map.of("name", "x", "bins",
                List.of(Map.of("min", 5, "max", 1, "points", 1)))), cutoffs(), null, null, null)).hasMessageContaining("min >= max");
    }
}
