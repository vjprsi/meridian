package io.meridian.alm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class CurveTest {
    static final Curve CURVE = new Curve(List.of(new Curve.Point(365, new BigDecimal("0.035")),
            new Curve.Point(30, new BigDecimal("0.03")), new Curve.Point(1825, new BigDecimal("0.04"))));

    @Test
    void exactLinearAndFlat() {
        assertThat(CURVE.interpolate(30).method()).isEqualTo("EXACT");
        assertThat(CURVE.rate(365)).isEqualByComparingTo("0.035");
        Curve.Interpolation i = CURVE.interpolate(197);
        assertThat(i.method()).isEqualTo("LINEAR");
        assertThat(i.lower().tenorDays()).isEqualTo(30);
        assertThat(i.upper().tenorDays()).isEqualTo(365);
        // 0.03 + 0.005 * 167 / 335
        assertThat(i.rate().setScale(10, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("0.0324925373");
        assertThat(CURVE.interpolate(1).method()).isEqualTo("FLAT_EXTRAPOLATION");
        assertThat(CURVE.rate(1)).isEqualByComparingTo("0.03");
        assertThat(CURVE.rate(10000)).isEqualByComparingTo("0.04");
        assertThat(CURVE.rate(new BigDecimal("1095"))).isEqualByComparingTo("0.0375");
    }

    @Test
    void rejectsEmptyAndDuplicateTenors() {
        assertThatThrownBy(() -> new Curve(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Curve(List.of(new Curve.Point(30, BigDecimal.ONE), new Curve.Point(30, BigDecimal.ONE))))
                .hasMessageContaining("duplicate tenor");
    }

    @Property
    void interpolatedRateLiesBetweenNeighbours(@ForAll @IntRange(min = 0, max = 4000) int tenor) {
        Curve.Interpolation i = CURVE.interpolate(tenor);
        BigDecimal lo = i.lower().rate().min(i.upper().rate());
        BigDecimal hi = i.lower().rate().max(i.upper().rate());
        assertThat(i.rate()).isBetween(lo, hi);
        assertThat(i.lower().tenorDays()).isLessThanOrEqualTo(Math.max(tenor, i.lower().tenorDays()));
    }

    @Property
    void monotoneCurveInterpolatesMonotonically(@ForAll @IntRange(min = 0, max = 3000) int a,
                                                @ForAll @IntRange(min = 0, max = 3000) int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        assertThat(CURVE.rate(lo)).isLessThanOrEqualTo(CURVE.rate(hi));
    }
}
