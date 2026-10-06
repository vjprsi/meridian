package io.meridian.liquidity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.liquidity.domain.IntradayCalculator.Movement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class IntradayAndPositionsTest {
    static final OffsetDateTime DAY = OffsetDateTime.parse("2026-10-06T08:00:00Z");

    /** Net cumulative: -100, -50, -250, +50 -> largest negative 250 (liquidity used), largest positive 50. */
    @Test
    void largestNegativeNetCumulativePosition() {
        IntradayCalculator.Result r = IntradayCalculator.calculate(List.of(
                new Movement(DAY.plusHours(3), false, new BigDecimal("300")),
                new Movement(DAY.plusHours(1), true, new BigDecimal("100")),
                new Movement(DAY.plusMinutes(90), false, new BigDecimal("50")),
                new Movement(DAY.plusHours(2), true, new BigDecimal("200"))), DAY.plusHours(4), new BigDecimal("600"));
        assertThat(r.sent()).isEqualByComparingTo("300");
        assertThat(r.received()).isEqualByComparingTo("350");
        assertThat(r.largestNegative()).isEqualByComparingTo("250");
        assertThat(r.usedLiquidity()).isEqualByComparingTo("250");
        assertThat(r.largestPositive()).isEqualByComparingTo("50");
        assertThat(r.throughput()).isEqualByComparingTo("0.5");
        assertThat(r.profile()).extracting(p -> p.netCumulative().toPlainString())
                .containsExactly("-100.00", "-50.00", "-250.00", "50.00");
        assertThatThrownBy(() -> IntradayCalculator.calculate(List.of(new Movement(DAY.plusHours(5), true, BigDecimal.ONE)),
                DAY.plusHours(4), null)).extracting("code").isEqualTo("movement_after_snapshot");
    }

    /** The liquidity used equals the deepest prefix deficit of the time-ordered movements. */
    @Property(tries = 200)
    void usedLiquidityIsDeepestDeficit(@ForAll @Size(max = 30) List<@IntRange(min = -1000, max = 1000) Integer> signed) {
        List<Movement> moves = new ArrayList<>();
        int net = 0;
        int deepest = 0;
        for (int i = 0; i < signed.size(); i++) {
            int v = signed.get(i);
            if (v == 0) {
                continue;
            }
            moves.add(new Movement(DAY.plusMinutes(i), v < 0, BigDecimal.valueOf(Math.abs(v))));
            net += v;
            deepest = Math.min(deepest, net);
        }
        IntradayCalculator.Result r = IntradayCalculator.calculate(moves, DAY.plusHours(1), null);
        assertThat(r.largestNegative()).isEqualByComparingTo(BigDecimal.valueOf(-deepest));
        assertThat(r.largestNegative().signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void projectionRollsClosingIntoNextOpeningAndFlagsShortfalls() {
        LocalDate d = LocalDate.of(2026, 10, 6);
        List<CashPositions.Position> p = CashPositions.project(d, 3, new BigDecimal("1000"), new BigDecimal("500"), List.of(
                new CashFlow(d, "EUR", true, new BigDecimal("200"), "TREASURY_DEALS", null, "PRINCIPAL_IN"),
                new CashFlow(d.plusDays(1), "EUR", false, new BigDecimal("900"), "PAYMENTS", null, "PAYMENT"),
                new CashFlow(d.plusDays(1), "EUR", false, new BigDecimal("100"), "TREASURY_DEALS", null, "PRINCIPAL_OUT"),
                new CashFlow(d.plusDays(5), "EUR", true, new BigDecimal("1"), "OTHER", null, null)));
        assertThat(p).hasSize(3);
        assertThat(p.get(0).closing()).isEqualByComparingTo("1200");
        assertThat(p.get(0).shortfall()).isNull();
        assertThat(p.get(1).opening()).isEqualByComparingTo("1200");
        assertThat(p.get(1).closing()).isEqualByComparingTo("200");
        assertThat(p.get(1).shortfall()).isEqualByComparingTo("300");
        assertThat(p.get(1).byCategory().get("PAYMENTS")[1]).isEqualByComparingTo("900");
        assertThat(p.get(2).opening()).isEqualByComparingTo("200");
        assertThat(p.get(2).inflows()).isZero();
        assertThat(CashPositions.shortfall(new BigDecimal("10"), null)).isNull();
    }
}
