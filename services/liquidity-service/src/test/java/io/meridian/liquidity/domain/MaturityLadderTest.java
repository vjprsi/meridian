package io.meridian.liquidity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.liquidity.domain.MaturityLadder.Bucket;
import io.meridian.liquidity.domain.MaturityLadder.Item;
import io.meridian.liquidity.domain.MaturityLadder.Share;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MaturityLadderTest {

    @Test
    void bucketBoundaries() {
        assertThat(Bucket.forDays(-3)).isEqualTo(Bucket.ON);
        assertThat(Bucket.forDays(0)).isEqualTo(Bucket.ON);
        assertThat(Bucket.forDays(1)).isEqualTo(Bucket.ON);
        assertThat(Bucket.forDays(2)).isEqualTo(Bucket.D7);
        assertThat(Bucket.forDays(7)).isEqualTo(Bucket.D7);
        assertThat(Bucket.forDays(8)).isEqualTo(Bucket.D30);
        assertThat(Bucket.forDays(30)).isEqualTo(Bucket.D30);
        assertThat(Bucket.forDays(31)).isEqualTo(Bucket.M3);
        assertThat(Bucket.forDays(180)).isEqualTo(Bucket.M6);
        assertThat(Bucket.forDays(365)).isEqualTo(Bucket.M12);
        assertThat(Bucket.forDays(366)).isEqualTo(Bucket.Y5);
        assertThat(Bucket.forDays(1825)).isEqualTo(Bucket.Y5);
        assertThat(Bucket.forDays(1826)).isEqualTo(Bucket.OVER5Y);
        assertThat(Bucket.ofCode("1-3m")).isEqualTo(Bucket.M3);
    }

    @Test
    void contractualAndBehaviouralItemsWithCumulativeGap() {
        List<MaturityLadder.Row> rows = MaturityLadder.build(List.of(
                new Item("LOANS", true, new BigDecimal("500"), 45L, List.of()),
                new Item("TREASURY_DEALS", false, new BigDecimal("300"), 5L, List.of()),
                new Item("DEPOSITS", false, new BigDecimal("1000"), null, List.of())), true);
        assertThat(rows).hasSize(8);
        assertThat(rows.get(0).outflows()).isEqualByComparingTo("50");     // 5% overnight
        assertThat(rows.get(1).outflows()).isEqualByComparingTo("350");    // 300 contractual + 5%
        assertThat(rows.get(3).inflows()).isEqualByComparingTo("500");
        assertThat(rows.get(6).outflows()).isEqualByComparingTo("500");    // core 50% in 1-5Y
        assertThat(rows.get(1).cumulativeGap()).isEqualByComparingTo("-400");
        assertThat(rows.get(7).cumulativeGap()).isEqualByComparingTo("-800");
        assertThat(MaturityLadder.firstNegative(rows)).isEqualTo(Bucket.ON);
        assertThat(rows.get(1).breakdown().get("TREASURY_DEALS")[1]).isEqualByComparingTo("300");

        List<MaturityLadder.Row> contractual = MaturityLadder.build(List.of(
                new Item("DEPOSITS", false, new BigDecimal("1000"), null, List.of())), false);
        assertThat(contractual.get(0).outflows()).isEqualByComparingTo("1000");
    }

    @Test
    void customProfileMustSumToOne() {
        List<MaturityLadder.Row> rows = MaturityLadder.build(List.of(new Item("DEPOSITS", false, new BigDecimal("100"), null,
                List.of(new Share(Bucket.ON, new BigDecimal("0.4")), new Share(Bucket.OVER5Y, new BigDecimal("0.6"))))), true);
        assertThat(rows.get(0).outflows()).isEqualByComparingTo("40");
        assertThat(rows.get(7).outflows()).isEqualByComparingTo("60");
        assertThatThrownBy(() -> MaturityLadder.build(List.of(new Item("DEPOSITS", false, BigDecimal.TEN, null,
                List.of(new Share(Bucket.ON, new BigDecimal("0.5"))))), true))
                .extracting("code").isEqualTo("invalid_behavioural_profile");
    }

    /** Allocation never creates or loses money: bucket totals equal the item amount, wherever it falls. */
    @Property(tries = 300)
    void allocationPreservesTotals(@ForAll @BigRange(min = "0", max = "100000000") BigDecimal amount,
                                   @ForAll @LongRange(min = -10, max = 10000) long days,
                                   @ForAll boolean contractual) {
        BigDecimal cents = amount.setScale(2, java.math.RoundingMode.HALF_EVEN);
        List<MaturityLadder.Row> rows = MaturityLadder.build(List.of(
                new Item("DEPOSITS", false, cents, contractual ? days : null, List.of())), true);
        BigDecimal total = rows.stream().map(MaturityLadder.Row::outflows).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo(cents);
        assertThat(rows.getLast().cumulativeGap()).isEqualByComparingTo(cents.negate());
    }
}
