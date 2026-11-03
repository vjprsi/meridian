package io.meridian.agentnetwork.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainRulesTest {

    // ------------------------------------------------------------------ geo

    @Test
    void haversineMatchesKnownDistances() {
        // Nairobi CBD to Westlands ~ 3.3 km; one degree of latitude ~ 111.2 km
        assertThat(Geo.distanceMetres(-1.2864, 36.8172, -1.2676, 36.8108)).isCloseTo(2_210, within(60d));
        assertThat(Geo.distanceMetres(0, 0, 1, 0)).isCloseTo(111_195, within(10d));
        assertThat(Geo.distanceMetres(10, 20, 10, 20)).isZero();
        // antipodal points: half the circumference
        assertThat(Geo.distanceMetres(0, 0, 0, 180)).isCloseTo(Math.PI * Geo.EARTH_RADIUS_M, within(1d));
    }

    @Test
    void boundingBoxContainsTheCircle() {
        double[] box = Geo.boundingBox(-1.3, 36.8, 5_000);
        assertThat(box[0]).isLessThan(-1.3);
        assertThat(box[1]).isGreaterThan(-1.3);
        // a point 4.9 km due east lies inside the box
        double east = 36.8 + 4_900 / (111_320 * Math.cos(Math.toRadians(-1.3)));
        assertThat(east).isBetween(box[2], box[3]);
        assertThat(Geo.distanceMetres(-1.3, 36.8, -1.3, east)).isLessThan(5_000);
    }

    @Test
    void radiusAndLocationValidation() {
        assertThat(Geo.radius(null)).isEqualTo(Geo.DEFAULT_RADIUS_M);
        assertThatThrownBy(() -> Geo.radius(0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Geo.radius(50_001)).isInstanceOf(ApiException.class);
        Geo.validate(null, null);
        Geo.validate(new BigDecimal("-90"), new BigDecimal("180"));
        assertThatThrownBy(() -> Geo.validate(new BigDecimal("1"), null)).hasMessageContaining("together");
        assertThatThrownBy(() -> Geo.validate(new BigDecimal("90.1"), BigDecimal.ONE)).isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ hierarchy

    @Test
    void hierarchyLevelsAndDerivedSuperAgent() {
        UUID sa = UUID.randomUUID();
        UUID ag = UUID.randomUUID();
        assertThat(Hierarchy.validateParent("SUPER_AGENT", null, null, null, null)).isNull();
        assertThat(Hierarchy.validateParent("AGENT", sa, "SUPER_AGENT", "ACTIVE", null)).isEqualTo(sa);
        assertThat(Hierarchy.validateParent("SUB_AGENT", ag, "AGENT", "ONBOARDING", sa)).isEqualTo(sa);
        assertThatThrownBy(() -> Hierarchy.validateParent("SUPER_AGENT", sa, "SUPER_AGENT", "ACTIVE", null))
                .hasMessageContaining("cannot have a parent");
        assertThatThrownBy(() -> Hierarchy.validateParent("SUB_AGENT", sa, "SUPER_AGENT", "ACTIVE", null))
                .hasMessageContaining("must be a AGENT");
        assertThatThrownBy(() -> Hierarchy.validateParent("AGENT", null, null, null, null))
                .hasMessageContaining("needs a parent");
        assertThatThrownBy(() -> Hierarchy.validateParent("AGENT", sa, "SUPER_AGENT", "TERMINATED", null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("parent_terminated");
        assertThat(Hierarchy.root(sa, null)).isEqualTo(sa);
        assertThat(Hierarchy.root(ag, sa)).isEqualTo(sa);
    }

    @Test
    void configurableHierarchyCategoryRules() {
        UUID domain = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        // owner (no parent) sits at level 1 when the domain has no owner yet
        assertThat(Hierarchy.validateCategoryParent(domain, null, null, null, null, false)).isEqualTo(1);
        // a second owner is rejected
        assertThatThrownBy(() -> Hierarchy.validateCategoryParent(domain, null, null, null, null, true))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("owner_exists");
        // a child derives parent.level + 1
        assertThat(Hierarchy.validateCategoryParent(domain, parent, domain, 1, "ACTIVE", true)).isEqualTo(2);
        assertThat(Hierarchy.validateCategoryParent(domain, parent, domain, 3, "DRAFT", true)).isEqualTo(4);
        // parent must be in the same domain and not retired
        assertThatThrownBy(() -> Hierarchy.validateCategoryParent(domain, parent, other, 1, "ACTIVE", true))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("different_domain");
        assertThatThrownBy(() -> Hierarchy.validateCategoryParent(domain, parent, domain, 1, "RETIRED", true))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("parent_retired");
        assertThat(Hierarchy.deriveLevel(null)).isEqualTo(1);
        assertThat(Hierarchy.deriveLevel(2)).isEqualTo(3);
        // legacy agent levels map onto the seeded factory category codes (compat path)
        assertThat(Hierarchy.legacyCategoryCode("SUPER_AGENT")).isEqualTo("SUPER_AGENT");
        assertThat(Hierarchy.legacyCategoryCode("SUB_AGENT")).isEqualTo("SUB_AGENT");
        assertThatThrownBy(() -> Hierarchy.legacyCategoryCode("NOPE")).isInstanceOf(ApiException.class);
    }

    @Test
    void configMakerCheckerTransitions() {
        Hierarchy.requireConfigTransition("DRAFT", "PENDING_APPROVAL");
        Hierarchy.requireConfigTransition("PENDING_APPROVAL", "ACTIVE");
        Hierarchy.requireConfigTransition("PENDING_APPROVAL", "DRAFT");
        Hierarchy.requireConfigTransition("ACTIVE", "RETIRED");
        assertThatThrownBy(() -> Hierarchy.requireConfigTransition("DRAFT", "ACTIVE"))
                .extracting(e -> ((ApiException) e).status()).isEqualTo(409);
        assertThatThrownBy(() -> Hierarchy.requireConfigTransition("RETIRED", "ACTIVE")).isInstanceOf(ApiException.class);
    }

    @Test
    void lifecycleTransitions() {
        Hierarchy.requireAgentTransition("ONBOARDING", "ACTIVE");
        Hierarchy.requireAgentTransition("SUSPENDED", "ACTIVE");
        assertThatThrownBy(() -> Hierarchy.requireAgentTransition("TERMINATED", "ACTIVE"))
                .extracting(e -> ((ApiException) e).status()).isEqualTo(409);
        assertThatThrownBy(() -> Hierarchy.requireAgentTransition("ONBOARDING", "SUSPENDED")).isInstanceOf(ApiException.class);
        Hierarchy.requireDeviceTransition("LOST", "RETIRED");
        assertThatThrownBy(() -> Hierarchy.requireDeviceTransition("RETIRED", "ACTIVE")).isInstanceOf(ApiException.class);
        Hierarchy.requireRequestTransition("REQUESTED", "FAILED");
        assertThatThrownBy(() -> Hierarchy.requireRequestTransition("COMPLETED", "CANCELLED")).isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ tills

    @Test
    void tillVarianceClassification() {
        TillCount.Result short_ = TillCount.close(new BigDecimal("10000"), new BigDecimal("5000"), new BigDecimal("2000"),
                new BigDecimal("12900"));
        assertThat(short_.expected()).isEqualByComparingTo("13000");
        assertThat(short_.variance()).isEqualByComparingTo("-100");
        assertThat(short_.status()).isEqualTo("SHORT");
        assertThat(TillCount.close(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN).status()).isEqualTo("BALANCED");
        assertThat(TillCount.close(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("12")).status()).isEqualTo("OVER");
    }

    @Test
    void denominationsMustAddUp() {
        TillCount.validateCount(new BigDecimal("10000"), Map.of("1000", 8, "500", 4));
        TillCount.validateCount(new BigDecimal("50.50"), Map.of("50", 1, "0.50", 1));
        TillCount.validateCount(BigDecimal.ZERO, null);
        assertThatThrownBy(() -> TillCount.validateCount(new BigDecimal("10000"), Map.of("1000", 5)))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("denominations_mismatch");
        assertThatThrownBy(() -> TillCount.validateCount(new BigDecimal("-1"), null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_cash_count");
        assertThatThrownBy(() -> TillCount.validateCount(BigDecimal.TEN, Map.of("ten", 1)))
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_denominations");
    }
}
