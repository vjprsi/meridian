package io.meridian.loan.domain;

import static io.meridian.loan.domain.Waterfall.Component.FEE;
import static io.meridian.loan.domain.Waterfall.Component.INTEREST;
import static io.meridian.loan.domain.Waterfall.Component.PENALTY;
import static io.meridian.loan.domain.Waterfall.Component.PRINCIPAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class WaterfallTest {
    static final LocalDate D1 = LocalDate.of(2026, 1, 31);
    static final LocalDate D2 = LocalDate.of(2026, 2, 28);

    static Waterfall.Bucket inst(String ref, LocalDate due, String principal, String interest, String fee, String penalty) {
        return new Waterfall.Bucket(ref, due, Map.of(PRINCIPAL, new BigDecimal(principal), INTEREST, new BigDecimal(interest),
                FEE, new BigDecimal(fee), PENALTY, new BigDecimal(penalty)));
    }

    List<Waterfall.Bucket> twoOverdue() {
        return List.of(inst("i2", D2, "100", "10", "5", "2"), inst("i1", D1, "100", "10", "5", "2"));
    }

    @Test
    void defaultOrderIsFeesPenaltiesInterestPrincipalOldestFirst() {
        Waterfall.Result r = Waterfall.allocate(twoOverdue(), null, true, new BigDecimal("130"));
        // i1 fully (117), then i2 fee 5, penalty 2, interest 6 of 10
        assertThat(r.allocations()).extracting(Waterfall.Allocation::ref, Waterfall.Allocation::component)
                .startsWith(org.assertj.core.groups.Tuple.tuple("i1", FEE), org.assertj.core.groups.Tuple.tuple("i1", PENALTY),
                        org.assertj.core.groups.Tuple.tuple("i1", INTEREST), org.assertj.core.groups.Tuple.tuple("i1", PRINCIPAL));
        assertThat(r.total(PRINCIPAL)).isEqualByComparingTo("100");
        assertThat(r.total(INTEREST)).isEqualByComparingTo("16");
        assertThat(r.total(FEE)).isEqualByComparingTo("10");
        assertThat(r.total(PENALTY)).isEqualByComparingTo("4");
        assertThat(r.excess()).isEqualByComparingTo("0");
    }

    @Test
    void componentFirstClearsAComponentAcrossInstalmentsBeforeTheNext() {
        Waterfall.Result r = Waterfall.allocate(twoOverdue(), List.of(INTEREST, PRINCIPAL, FEE, PENALTY), false,
                new BigDecimal("50"));
        assertThat(r.total(INTEREST)).isEqualByComparingTo("20");
        assertThat(r.total(PRINCIPAL)).isEqualByComparingTo("30");
        assertThat(r.total(FEE)).isEqualByComparingTo("0");
        // the older instalment's principal is served first
        assertThat(r.allocations().get(2)).isEqualTo(new Waterfall.Allocation("i1", PRINCIPAL, new BigDecimal("30")));
    }

    @Test
    void excessIsReturnedWhenEverythingIsPaid() {
        Waterfall.Result r = Waterfall.allocate(twoOverdue(), null, true, new BigDecimal("300"));
        assertThat(r.allocated()).isEqualByComparingTo("234");
        assertThat(r.excess()).isEqualByComparingTo("66");
    }

    @Test
    void componentsMissingFromTheOrderAreNotPaid() {
        Waterfall.Result r = Waterfall.allocate(twoOverdue(), List.of(PRINCIPAL, INTEREST), true, new BigDecimal("1000"));
        assertThat(r.total(FEE)).isEqualByComparingTo("0");
        assertThat(r.total(PENALTY)).isEqualByComparingTo("0");
        assertThat(r.excess()).isEqualByComparingTo("780");
    }

    @Property(tries = 300)
    void allocationNeverExceedsWhatIsOwedAndConservesMoney(@ForAll @BigRange(min = "0", max = "2000") BigDecimal amount,
                                                          @ForAll @IntRange(min = 1, max = 8) int count,
                                                          @ForAll boolean oldestFirst) {
        List<Waterfall.Bucket> buckets = new ArrayList<>();
        BigDecimal owed = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            buckets.add(inst("i" + i, D1.plusMonths(i), "100.00", "9.37", "3.10", "0.55"));
            owed = owed.add(new BigDecimal("113.02"));
        }
        BigDecimal a = amount.setScale(2, java.math.RoundingMode.DOWN);
        Waterfall.Result r = Waterfall.allocate(buckets, Waterfall.DEFAULT_ORDER, oldestFirst, a);
        assertThat(r.allocated().add(r.excess())).isEqualByComparingTo(a);
        assertThat(r.allocated()).isLessThanOrEqualTo(owed);
        assertThat(r.excess().signum() > 0).isEqualTo(a.compareTo(owed) > 0);
        assertThat(r.allocations()).allSatisfy(x -> assertThat(x.amount().signum()).isPositive());
    }
}
