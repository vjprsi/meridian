package io.meridian.alm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.alm.domain.Assumption.AssumptionType;
import io.meridian.alm.domain.Position.RateType;
import io.meridian.alm.domain.Position.Side;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class GapAndBehaviourTest {
    static final LocalDate AS_OF = LocalDate.of(2026, 1, 1);
    static final UUID PRODUCT = UUID.fromString("0199a000-0000-7000-8000-00000000a001");

    static BigDecimal sum(List<Behaviour.Flow> flows) {
        return flows.stream().map(Behaviour.Flow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void parameterValidation() {
        assertThat(Behaviour.validate(AssumptionType.PREPAYMENT, Map.of("cpr", "0.08"))).containsEntry("cpr", new BigDecimal("0.08"));
        assertThatThrownBy(() -> Behaviour.validate(AssumptionType.PREPAYMENT, Map.of("cpr", 1.5)))
                .hasMessageContaining("cpr must be between 0 and 1");
        assertThatThrownBy(() -> Behaviour.validate(AssumptionType.PREPAYMENT, Map.of())).hasMessageContaining("cpr is required");
        assertThatThrownBy(() -> Behaviour.validate(AssumptionType.DEPOSIT_DECAY, Map.of("core_ratio", 0.6, "speed", 2)))
                .hasMessageContaining("unknown parameter speed").hasMessageContaining("decay_rate is required");
        Map<String, BigDecimal> decay = Behaviour.validate(AssumptionType.DEPOSIT_DECAY,
                Map.of("core_ratio", 0.6, "decay_rate", 0.2));
        assertThat(decay.get("core_max_months")).isEqualByComparingTo("60");
        assertThat(decay.get("pass_through")).isEqualByComparingTo("1");
        assertThatThrownBy(() -> Behaviour.validate(AssumptionType.NON_MATURITY_REPRICING, Map.of("repricing_months", 2.5)))
                .hasMessageContaining("whole number");
    }

    @Test
    void cprPrepaymentRunsOffMonthly() {
        assertThat(Behaviour.monthly(new BigDecimal("0.12")).doubleValue()).isCloseTo(1 - Math.pow(0.88, 1.0 / 12),
                org.assertj.core.api.Assertions.within(1e-12));
        List<Behaviour.Flow> flows = Behaviour.prepayment(AS_OF, LocalDate.of(2027, 1, 1), new BigDecimal("1200000"),
                new BigDecimal("0.12"));
        assertThat(flows).hasSize(12);
        assertThat(sum(flows).setScale(8, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1200000");
        // after 11 monthly prepayments 88^(11/12) % remains for maturity
        assertThat(flows.getLast().amount().doubleValue()).isCloseTo(1200000 * Math.pow(0.88, 11.0 / 12),
                org.assertj.core.api.Assertions.within(0.01));
    }

    @Property
    void decayProfileConservesTheBalance(@ForAll @BigRange(min = "0", max = "1") BigDecimal core,
                                         @ForAll @BigRange(min = "0", max = "0.99") BigDecimal decayRate,
                                         @ForAll @IntRange(min = 1, max = 120) int maxMonths) {
        Map<String, BigDecimal> p = Map.of("core_ratio", core, "decay_rate", decayRate,
                "core_max_months", BigDecimal.valueOf(maxMonths));
        List<Behaviour.Flow> flows = Behaviour.decay(AS_OF, new BigDecimal("1000000"), p);
        assertThat(sum(flows).subtract(new BigDecimal("1000000")).abs()).isLessThan(new BigDecimal("0.000001"));
        assertThat(flows).allMatch(f -> !f.date().isAfter(AS_OF.plusMonths(maxMonths)));
    }

    @Test
    void bucketBoundaries() {
        assertThat(GapCalculator.bucketIndex(AS_OF, AS_OF.minusDays(3))).isZero();
        assertThat(GapCalculator.bucketIndex(AS_OF, LocalDate.of(2026, 2, 1))).isZero();
        assertThat(GapCalculator.bucketIndex(AS_OF, LocalDate.of(2026, 2, 2))).isEqualTo(1);
        assertThat(GapCalculator.bucketIndex(AS_OF, LocalDate.of(2027, 1, 1))).isEqualTo(3);
        assertThat(GapCalculator.bucketIndex(AS_OF, LocalDate.of(2031, 1, 1))).isEqualTo(5);
        assertThat(GapCalculator.bucketIndex(AS_OF, LocalDate.of(2031, 1, 2))).isEqualTo(6);
    }

    @Test
    void contractualAndBehaviouralGap() {
        Position loan = new Position("MORT", PRODUCT, "RETAIL", Side.ASSET, new BigDecimal("1000000"), "EUR",
                new BigDecimal("0.04"), RateType.FIXED, null, LocalDate.of(2028, 1, 1), false, 12);
        Position frn = new Position("FRN", null, null, Side.ASSET, new BigDecimal("500000"), "EUR",
                new BigDecimal("0.03"), RateType.FLOATING, LocalDate.of(2026, 3, 15), LocalDate.of(2030, 1, 1), false, 3);
        Position savings = new Position("SAV", null, "RETAIL", Side.LIABILITY, new BigDecimal("800000"), "EUR",
                new BigDecimal("0.01"), RateType.FLOATING, null, null, true, 12);
        Position swap = new Position("IRS", null, null, Side.OFF_BALANCE, new BigDecimal("-200000"), "EUR",
                null, RateType.FIXED, null, LocalDate.of(2027, 6, 1), false, 12);
        List<Position> book = List.of(loan, frn, savings, swap);

        GapCalculator.Result contractual = GapCalculator.compute(AS_OF, book, false, List.of());
        assertThat(contractual.buckets()).hasSize(7);
        assertThat(contractual.buckets().get(0).liabilities()).isEqualByComparingTo("800000");
        assertThat(contractual.buckets().get(1).assets()).isEqualByComparingTo("500000");
        assertThat(contractual.buckets().get(4).assets()).isEqualByComparingTo("1000000");
        assertThat(contractual.buckets().get(4).offBalance()).isEqualByComparingTo("-200000");
        assertThat(contractual.buckets().getLast().cumulativeGap()).isEqualByComparingTo("500000");
        assertThat(contractual.totalAssets()).isEqualByComparingTo("1500000");
        assertThat(contractual.buckets().get(0).breakdown()).containsEntry("SAV", new BigDecimal("-800000.00"));

        Assumption cpr = new Assumption(UUID.randomUUID(), AssumptionType.PREPAYMENT, PRODUCT, null, null,
                Map.of("cpr", new BigDecimal("0.10")), LocalDate.of(2025, 1, 1), null);
        Assumption genericCpr = new Assumption(UUID.randomUUID(), AssumptionType.PREPAYMENT, null, null, null,
                Map.of("cpr", new BigDecimal("0.50")), LocalDate.of(2025, 1, 1), null);
        Assumption decay = new Assumption(UUID.randomUUID(), AssumptionType.DEPOSIT_DECAY, null, "RETAIL", "EUR",
                Map.of("core_ratio", new BigDecimal("0.6"), "decay_rate", new BigDecimal("0.2"),
                        "core_max_months", BigDecimal.valueOf(60)), LocalDate.of(2025, 1, 1), null);
        GapCalculator.Result behavioural = GapCalculator.compute(AS_OF, book, true, List.of(cpr, genericCpr, decay));
        assertThat(behavioural.assumptionsUsed()).containsExactlyInAnyOrder(cpr.id(), decay.id());
        BigDecimal firstBucketLiab = behavioural.buckets().get(0).liabilities();
        // volatile 40% plus the first month of core decay
        BigDecimal monthly = Behaviour.monthly(new BigDecimal("0.2"));
        assertThat(firstBucketLiab).isEqualByComparingTo(new BigDecimal("320000").add(new BigDecimal("480000")
                .multiply(monthly, MathContext.DECIMAL128)).setScale(2, java.math.RoundingMode.HALF_EVEN));
        BigDecimal assets = behavioural.buckets().stream().map(GapCalculator.BucketResult::assets)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(assets.subtract(new BigDecimal("1500000")).abs()).isLessThanOrEqualTo(new BigDecimal("0.05"));
        assertThat(behavioural.buckets().get(4).assets()).isLessThan(new BigDecimal("1000000"));
    }

    @Test
    void positionWithoutDatesCannotBeBucketed() {
        Position p = new Position("X", null, null, Side.ASSET, BigDecimal.TEN, "EUR", null, null, null, null, false, 0);
        assertThatThrownBy(() -> GapCalculator.compute(AS_OF, List.of(p), false, List.of()))
                .isInstanceOf(GapCalculator.UnbucketablePosition.class).hasMessageContaining("X");
    }

    @Test
    void assumptionSpecificity() {
        Assumption generic = new Assumption(UUID.randomUUID(), AssumptionType.PREPAYMENT, null, null, null, Map.of(),
                LocalDate.of(2025, 1, 1), null);
        Assumption seg = new Assumption(UUID.randomUUID(), AssumptionType.PREPAYMENT, null, "RETAIL", null, Map.of(),
                LocalDate.of(2025, 1, 1), null);
        Assumption expired = new Assumption(UUID.randomUUID(), AssumptionType.PREPAYMENT, PRODUCT, null, null, Map.of(),
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 30));
        List<Assumption> all = List.of(generic, seg, expired);
        assertThat(Assumption.best(all, AssumptionType.PREPAYMENT, PRODUCT, "RETAIL", "EUR", AS_OF)).contains(seg);
        assertThat(Assumption.best(all, AssumptionType.PREPAYMENT, PRODUCT, "CORP", "EUR", AS_OF)).contains(generic);
        assertThat(Assumption.best(all, AssumptionType.DEPOSIT_DECAY, PRODUCT, "RETAIL", "EUR", AS_OF)).isEmpty();
    }

    @Test
    void averageLifeOfDecay() {
        // all volatile -> one day; all core at 12 months without decay -> 365 days
        assertThat(Behaviour.averageLifeDays(Map.of("core_ratio", BigDecimal.ZERO, "decay_rate", BigDecimal.ZERO,
                "core_max_months", BigDecimal.valueOf(12)))).isEqualTo(1);
        assertThat(Behaviour.averageLifeDays(Map.of("core_ratio", BigDecimal.ONE, "decay_rate", BigDecimal.ZERO,
                "core_max_months", BigDecimal.valueOf(12)))).isEqualTo(366);
    }
}
