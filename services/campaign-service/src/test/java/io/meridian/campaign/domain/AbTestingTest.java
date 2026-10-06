package io.meridian.campaign.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

class AbTestingTest {
    static final UUID CAMPAIGN = UUID.fromString("0199a000-0000-7000-8000-00000000cafe");

    @Property
    void allocationIsDeterministicAndBucketInUnitInterval(@ForAll long hi, @ForAll long lo,
                                                          @ForAll @DoubleRange(min = 0, max = 0.5) double control) {
        UUID customer = new UUID(hi, lo);
        Map<String, BigDecimal> shares = Map.of("A", new BigDecimal("0.5"), "B", new BigDecimal("0.5"));
        AbTesting.Allocation a = AbTesting.allocate(CAMPAIGN, customer, BigDecimal.valueOf(control), shares);
        AbTesting.Allocation b = AbTesting.allocate(CAMPAIGN, customer, BigDecimal.valueOf(control), new LinkedHashMap<>(shares));
        assertThat(a).isEqualTo(b);
        assertThat(a.bucket()).isGreaterThanOrEqualTo(0).isLessThan(1);
        assertThat(a.control()).isEqualTo(a.bucket() < control);
        if (a.control()) {
            assertThat(a.variantCode()).isNull();
        } else {
            assertThat(a.variantCode()).isIn("A", "B");
        }
    }

    @Property
    void pickVariantFollowsCumulativeShares(@ForAll @DoubleRange(min = 0, max = 0.99) double u) {
        Map<String, BigDecimal> shares = Map.of("B", new BigDecimal("3"), "A", new BigDecimal("1"));
        // normalised: A = [0, 0.25), B = [0.25, 1)
        assertThat(AbTesting.pickVariant(u, shares)).isEqualTo(u < 0.25 ? "A" : "B");
    }

    @Test
    void distributionMatchesSharesAndControl() {
        Map<String, BigDecimal> shares = Map.of("A", new BigDecimal("0.7"), "B", new BigDecimal("0.3"));
        Map<String, Integer> counts = new HashMap<>();
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            AbTesting.Allocation a = AbTesting.allocate(CAMPAIGN, UUID.randomUUID(), new BigDecimal("0.1"), shares);
            counts.merge(a.control() ? "CONTROL" : a.variantCode(), 1, Integer::sum);
        }
        assertThat(counts.get("CONTROL") / (double) n).isCloseTo(0.10, within(0.015));
        double treated = n - counts.get("CONTROL");
        assertThat(counts.get("A") / treated).isCloseTo(0.70, within(0.02));
        assertThat(counts.get("B") / treated).isCloseTo(0.30, within(0.02));
    }

    @Test
    void controlAndVariantHashesAreIndependentAcrossCampaigns() {
        UUID customer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertThat(AbTesting.bucket(CAMPAIGN, customer, null)).isNotEqualTo(AbTesting.bucket(CAMPAIGN, customer, "variant"));
        assertThat(AbTesting.bucket(CAMPAIGN, customer, null)).isNotEqualTo(AbTesting.bucket(other, customer, null));
        assertThat(AbTesting.pickVariant(0.5, Map.of())).isNull();
    }
}
