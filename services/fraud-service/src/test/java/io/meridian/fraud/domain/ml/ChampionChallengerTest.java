package io.meridian.fraud.domain.ml;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.fraud.domain.DecisionEngine;
import io.meridian.fraud.domain.DecisionEngine.Decision;
import io.meridian.fraud.domain.FeatureEngineering;
import io.meridian.fraud.domain.model.LogisticRegressionModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Champion / canary / challenger / shadow execution, fail-safe fallback and score combination. Observers (challengers,
 * shadows) are scored and logged but can never change what decides.
 */
class ChampionChallengerTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID MODEL = UUID.randomUUID();

    /** A one-feature logistic artefact whose score is driven by {@code intercept}. */
    static ModelArtifact artefact(double intercept) {
        return ModelArtifact.parse("""
                {"format":"meridian-fraud-model/1","algorithm":"LOGISTIC_REGRESSION","features":["amount_zscore"],
                 "logistic":{"intercept":%s,"coefficients":[1.0]},
                 "thresholds":{"default":{"step_up":100,"review":200,"block":300}}}""".formatted(intercept), JSON, null);
    }

    static ModelSet.Deployment dep(ModelSet.Role role, int traffic, ModelArtifact a) {
        return new ModelSet.Deployment(UUID.randomUUID(), UUID.randomUUID(), role.name().toLowerCase(), role, traffic,
                "x".repeat(64), a, null, null);
    }

    static ModelArtifact fake(CompiledModel m) {
        ModelArtifact a = artefact(0);
        return new ModelArtifact(a.algorithm(), a.features(), a.imputation(), m, a.reasonCodes(), a.reasonMinContribution(),
                a.defaultThresholds(), a.channelThresholds(), a.distributions(), a.metadata());
    }

    static CompiledModel m(java.util.function.Function<double[], CompiledModel.Prediction> f) {
        return new CompiledModel() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            public Prediction predict(double[] x) {
                return f.apply(x);
            }
        };
    }

    static final Map<String, Double> X = Map.of("amount_zscore", 0.5);
    static final long BUDGET = 10_000_000L;

    @Test
    void championDecidesAndObserversAreLoggedOnly() {
        ModelSet.Deployment champion = dep(ModelSet.Role.CHAMPION, 100, artefact(-1));
        ModelSet.Deployment challenger = dep(ModelSet.Role.CHALLENGER, 10, artefact(3));
        ModelSet.Deployment shadow = dep(ModelSet.Role.SHADOW, 0, artefact(-4));
        MlExecution.Result r = MlExecution.execute(new ModelSet(MODEL, "FRAUD", List.of(shadow, challenger, champion)),
                UUID.randomUUID(), X, BUDGET, System::nanoTime);
        assertThat(r.degradedReason()).isNull();
        assertThat(r.deciding().deployment()).isSameAs(champion);
        assertThat(r.runs()).hasSize(3);
        assertThat(r.runs()).filteredOn(MlExecution.Run::decided).hasSize(1);
        assertThat(r.runs()).filteredOn(run -> !run.decided()).extracting(MlExecution.Run::role)
                .containsExactlyInAnyOrder(ModelSet.Role.CHALLENGER, ModelSet.Role.SHADOW);
        // sigmoid(-1 + 0.5) = 0.3775
        assertThat(r.deciding().score()).isEqualTo(378);
    }

    @Property(tries = 200)
    void observersNeverChangeTheDecision(@ForAll @DoubleRange(min = -8, max = 8) double challengerIntercept,
                                         @ForAll @DoubleRange(min = -8, max = 8) double shadowIntercept,
                                         @ForAll boolean challengerFails,
                                         @ForAll @IntRange(min = 0, max = 1000) int heuristic) {
        ModelSet.Deployment champion = dep(ModelSet.Role.CHAMPION, 100, artefact(-0.7));
        ModelSet alone = new ModelSet(MODEL, "FRAUD", List.of(champion));
        ModelSet.Deployment challenger = challengerFails
                ? dep(ModelSet.Role.CHALLENGER, 20, fake(m(x -> { throw new IllegalStateException("boom"); })))
                : dep(ModelSet.Role.CHALLENGER, 20, artefact(challengerIntercept));
        ModelSet withObservers = new ModelSet(MODEL, "FRAUD", List.of(champion, challenger,
                dep(ModelSet.Role.SHADOW, 0, artefact(shadowIntercept))));
        UUID subject = UUID.randomUUID();
        MlExecution.Result a = MlExecution.execute(alone, subject, X, BUDGET, System::nanoTime);
        MlExecution.Result b = MlExecution.execute(withObservers, subject, X, BUDGET, System::nanoTime);
        assertThat(b.deciding().deployment()).isSameAs(a.deciding().deployment());
        assertThat(b.deciding().score()).isEqualTo(a.deciding().score());
        assertThat(b.degradedReason()).isEqualTo(a.degradedReason());
        DecisionEngine.Policy policy = policy(ScoreCombination.MAX);
        Decision da = decide(heuristic, policy, a);
        Decision db = decide(heuristic, policy, b);
        assertThat(db).isEqualTo(da);
        assertThat(b.runs()).filteredOn(r -> r.role() != ModelSet.Role.CHAMPION).noneMatch(MlExecution.Run::decided);
    }

    @Test
    void canaryDecidesForADeterministicSliceOfSubjects() {
        ModelSet.Deployment champion = dep(ModelSet.Role.CHAMPION, 100, artefact(-1));
        ModelSet.Deployment canary = dep(ModelSet.Role.CANARY, 20, artefact(1));
        ModelSet set = new ModelSet(MODEL, "FRAUD", List.of(champion, canary));
        int canaryHits = 0;
        for (int i = 0; i < 5000; i++) {
            UUID s = UUID.nameUUIDFromBytes(("subject-" + i).getBytes());
            MlExecution.Result r = MlExecution.execute(set, s, X, BUDGET, System::nanoTime);
            MlExecution.Result again = MlExecution.execute(set, s, X, BUDGET, System::nanoTime);
            assertThat(again.deciding().deployment()).isSameAs(r.deciding().deployment());
            if (r.deciding().deployment() == canary) {
                canaryHits++;
                // the champion is logged, not deciding, for this event
                assertThat(r.runs()).filteredOn(x -> x.deployment() == champion).singleElement()
                        .satisfies(x -> assertThat(x.decided()).isFalse());
            }
        }
        assertThat(canaryHits / 5000.0).isBetween(0.17, 0.23);
    }

    @Test
    void failuresDegradeToRules() {
        UUID subject = UUID.randomUUID();
        // no deployments of the model at all
        assertThat(MlExecution.execute(null, subject, X, BUDGET, System::nanoTime).degradedReason()).isEqualTo("MODEL_UNAVAILABLE");
        // only a shadow, no champion
        MlExecution.Result noChampion = MlExecution.execute(new ModelSet(MODEL, "F", List.of(dep(ModelSet.Role.SHADOW, 0, artefact(0)))),
                subject, X, BUDGET, System::nanoTime);
        assertThat(noChampion.degradedReason()).isEqualTo("NO_CHAMPION");
        assertThat(noChampion.runs()).singleElement().satisfies(r -> assertThat(r.decided()).isFalse());
        // champion throws
        MlExecution.Result error = MlExecution.execute(new ModelSet(MODEL, "F", List.of(dep(ModelSet.Role.CHAMPION, 100,
                fake(m(x -> { throw new ArithmeticException(); }))))), subject, X, BUDGET, System::nanoTime);
        assertThat(error.deciding()).isNull();
        assertThat(error.degradedReason()).isEqualTo("MODEL_ERROR");
        // champion produces NaN
        MlExecution.Result nan = MlExecution.execute(new ModelSet(MODEL, "F", List.of(dep(ModelSet.Role.CHAMPION, 100,
                fake(m(x -> new CompiledModel.Prediction(Double.NaN, Double.NaN, 0, new double[1])))))), subject, X, BUDGET, System::nanoTime);
        assertThat(nan.degradedReason()).isEqualTo("MODEL_ERROR");
        // champion slower than the budget (deterministic clock: every reading advances 11 ms)
        AtomicLong clock = new AtomicLong();
        MlExecution.Result slow = MlExecution.execute(new ModelSet(MODEL, "F", List.of(dep(ModelSet.Role.CHAMPION, 100, artefact(0)))),
                subject, X, BUDGET, () -> clock.addAndGet(11_000_000L));
        assertThat(slow.deciding()).isNull();
        assertThat(slow.degradedReason()).isEqualTo("MODEL_TIMEOUT");
        assertThat(slow.runs()).singleElement().satisfies(r -> {
            assertThat(r.error()).isEqualTo("MODEL_TIMEOUT");
            assertThat(r.score()).isNotNull(); // the late score is still logged
        });
        // artefact failed its checksum at load time
        ModelSet.Deployment bad = new ModelSet.Deployment(UUID.randomUUID(), UUID.randomUUID(), "2", ModelSet.Role.CHAMPION, 100,
                "y".repeat(64), null, "CHECKSUM_MISMATCH", "sha differs");
        assertThat(MlExecution.execute(new ModelSet(MODEL, "F", List.of(bad)), subject, X, BUDGET, System::nanoTime).degradedReason())
                .isEqualTo("CHECKSUM_MISMATCH");
    }

    @Test
    void degradedEvaluationIsExactlyTheRuleBasedDecision() {
        DecisionEngine.Policy policy = policy(ScoreCombination.ADDITIVE);
        Map<String, Object> f = facts();
        var heuristic = LogisticRegressionModel.champion().score(FeatureEngineering.engineer(f));
        var legacy = DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), heuristic, null, policy);
        var degraded = DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), heuristic, null, policy, "POS", null);
        assertThat(degraded.score()).isEqualTo(legacy.score());
        assertThat(degraded.decision()).isEqualTo(legacy.decision());
        assertThat(degraded.reasonCodes()).isEqualTo(legacy.reasonCodes());
        assertThat(degraded.combination()).isNull();
    }

    // ------------------------------------------------------------------ combination

    @Test
    void combinationsFollowTheirDefinitions() {
        // R = 300 (heuristic 250 + 50 adjustments), M = 700, p = 0.7
        assertThat(ScoreCombination.ADDITIVE.combine(300, 50, 700, 0.7, 0.5, 0.8).score()).isEqualTo(750);
        assertThat(ScoreCombination.MAX.combine(300, 50, 700, 0.7, 0.5, 0.8).score()).isEqualTo(700);
        assertThat(ScoreCombination.WEIGHTED.combine(300, 50, 700, 0.7, 0.25, 0.8).score()).isEqualTo(400);
        assertThat(ScoreCombination.RULES_ONLY.combine(300, 50, 700, 0.7, 0.5, 0.8).score()).isEqualTo(300);
        // |2p - 1| = 0.4 < 0.8 -> max(M, R); with p = 0.95 (0.9 >= 0.8) the model alone
        assertThat(ScoreCombination.MODEL_ONLY_ABOVE_CONFIDENCE.combine(800, 50, 700, 0.7, 0.5, 0.8).score()).isEqualTo(800);
        assertThat(ScoreCombination.MODEL_ONLY_ABOVE_CONFIDENCE.combine(800, 50, 950, 0.95, 0.5, 0.8).score()).isEqualTo(950);
        assertThat(ScoreCombination.MODEL_ONLY_ABOVE_CONFIDENCE.combine(800, 50, 20, 0.02, 0.5, 0.8).score()).isEqualTo(20);
        assertThat(ScoreCombination.ADDITIVE.combine(300, 500, 700, 0.7, 0.5, 0.8).score()).isEqualTo(1000);
    }

    @Test
    void mlDecisionUsesModelReasonsChannelAndModelThresholds() {
        Map<String, Object> f = facts();
        var heuristic = LogisticRegressionModel.champion().score(FeatureEngineering.engineer(f));
        List<ModelArtifact.Contribution> cs = List.of(
                new ModelArtifact.Contribution("new_payee", 1, 1.4, "ML_NEW_PAYEE", "First payment to the payee"),
                new ModelArtifact.Contribution("amount_zscore", 3, 0.9, "ML_UNUSUAL_AMOUNT", "Unusual amount"),
                new ModelArtifact.Contribution("hour_deviation", 0.1, 0.1, "ML_UNUSUAL_TIME", "Odd hour"));
        DecisionEngine.MlInput ml = new DecisionEngine.MlInput(620, 0.62, cs, 0.25,
                new ModelArtifact.Thresholds(300, 600, 900));
        DecisionEngine.Policy base = policy(ScoreCombination.ADDITIVE);
        var o = DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), heuristic, null, base, "POS", ml);
        assertThat(o.score()).isEqualTo(620);
        assertThat(o.modelScore()).isEqualTo(620);
        assertThat(o.decision()).isEqualTo(Decision.STEP_UP);        // policy thresholds 450 / 650 / 850
        assertThat(o.reasonCodes()).containsExactly("ML_NEW_PAYEE", "ML_UNUSUAL_AMOUNT");
        assertThat(o.combination()).isEqualTo(ScoreCombination.ADDITIVE);
        var withModelThresholds = new DecisionEngine.Policy(450, 650, 850, MODEL, null, 50, Decision.ALLOW,
                ScoreCombination.ADDITIVE, 0.5, 0.8, 10, Map.of("ECOMMERCE", new DecisionEngine.Thresholds(100, 200, 300)), true);
        assertThat(DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), heuristic, null, withModelThresholds, "POS", ml).decision())
                .isEqualTo(Decision.REVIEW);                          // model thresholds 300 / 600 / 900
        assertThat(DecisionEngine.decide(List.of(), f, Set.of(), Set.of(), heuristic, null, withModelThresholds, "ECOMMERCE", ml).decision())
                .isEqualTo(Decision.BLOCK);                           // channel override wins
    }

    static Map<String, Object> facts() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("amount", new BigDecimal("120"));
        f.put("device", Map.of("is_new", false, "customers_seen", 1));
        f.put("velocity", Map.of("count_1h", 0, "count_30d", 10, "avg_amount_30d", "100"));
        return f;
    }

    static DecisionEngine.Policy policy(ScoreCombination c) {
        return new DecisionEngine.Policy(450, 650, 850, MODEL, null, 50, Decision.ALLOW, c, 0.5, 0.8, 10, Map.of(), false);
    }

    static Decision decide(int heuristicScore, DecisionEngine.Policy policy, MlExecution.Result r) {
        var heuristic = new io.meridian.fraud.domain.model.FraudModel.Output(heuristicScore, 0.5, 0, new ArrayList<>());
        DecisionEngine.MlInput in = r.deciding() == null ? null : new DecisionEngine.MlInput(r.deciding().score(),
                r.deciding().prediction().probability(), r.deciding().contributions(), 0.25, null);
        return DecisionEngine.decide(List.of(), facts(), Set.of(), Set.of(), heuristic, null, policy, "POS", in).decision();
    }
}
