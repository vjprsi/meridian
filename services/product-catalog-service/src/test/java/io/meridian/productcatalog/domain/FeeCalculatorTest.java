package io.meridian.productcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.fees.FeeCalculator.Context;
import io.meridian.productcatalog.domain.fees.FeeCalculator.Fee;
import io.meridian.productcatalog.domain.fees.FeeCalculator.Tax;
import io.meridian.productcatalog.domain.fees.FeeCalculator.Tier;
import io.meridian.productcatalog.domain.fees.FeeCalculator.Waiver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {
    static final LocalDate D = LocalDate.of(2026, 6, 1);
    static final Tax VAT = new Tax(UUID.randomUUID(), "VAT_75", bd("0.075"), null);

    static Fee fee(String method, String flat, String pct, String min, String max, int free, List<Tier> tiers,
                   List<Waiver> waivers, String ccy) {
        return new Fee(UUID.randomUUID(), "F", method, flat == null ? null : bd(flat), pct == null ? null : bd(pct),
                min == null ? null : bd(min), max == null ? null : bd(max), null, free, ccy, null, null, tiers, waivers);
    }

    static Context ctx(Integer occurrence, String balance, String segment) {
        return new Context(D, occurrence, balance == null ? null : bd(balance), null, null, null, segment, null, null, null, null);
    }

    @Test
    void flatFeeWithTax() {
        FeeCalculator.Result r = FeeCalculator.calculate(fee("FLAT", "5.00", null, null, null, 0, null, null, "USD"),
                bd("0"), ctx(null, null, null), VAT);
        assertThat(r.netAmount()).isEqualByComparingTo("5.00");
        assertThat(r.tax().amount()).isEqualByComparingTo("0.38"); // 0.375 -> HALF_EVEN 0.38
        assertThat(r.totalAmount()).isEqualByComparingTo("5.38");
    }

    @Test
    void taxRoundsHalfEven() {
        Tax t = new Tax(UUID.randomUUID(), "T", bd("0.025"), null);
        // 5.00 * 0.025 = 0.125 -> 0.12 (HALF_EVEN), not 0.13
        assertThat(FeeCalculator.calculate(fee("FLAT", "5.00", null, null, null, 0, null, null, "USD"), bd("0"),
                ctx(null, null, null), t).tax().amount()).isEqualByComparingTo("0.12");
    }

    @Test
    void percentageClampedByMinAndMax() {
        Fee f = fee("PERCENTAGE", null, "0.01", "2.00", "25.00", 0, null, null, "USD");
        assertThat(FeeCalculator.calculate(f, bd("100"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("2.00");
        assertThat(FeeCalculator.calculate(f, bd("1000"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("10.00");
        FeeCalculator.Result big = FeeCalculator.calculate(f, bd("100000"), ctx(null, null, null), null);
        assertThat(big.netAmount()).isEqualByComparingTo("25.00");
        assertThat(big.maxApplied()).isTrue();
    }

    @Test
    void flatPlusPercentageAndPerUnitAndMinorUnits() {
        assertThat(FeeCalculator.calculate(fee("FLAT_PLUS_PERCENTAGE", "1.00", "0.015", null, null, 0, null, null, "USD"),
                bd("200"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("4.00");
        assertThat(FeeCalculator.calculate(fee("PER_UNIT", "0.35", null, null, null, 0, null, null, "USD"),
                bd("12"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("4.20");
        // JPY has no minor unit
        assertThat(FeeCalculator.calculate(fee("PERCENTAGE", null, "0.0333", null, null, 0, null, null, "JPY"),
                bd("1000"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("33");
    }

    @Test
    void tieredFeesPriceWholeAmountAtTier() {
        List<Tier> tiers = List.of(new Tier(1, bd("0"), bd("1000"), bd("1.00"), null),
                new Tier(2, bd("1000"), bd("5000"), bd("2.50"), null),
                new Tier(3, bd("5000"), null, null, bd("0.001")));
        Fee flat = fee("TIERED_FLAT", null, null, null, null, 0, tiers, null, "USD");
        assertThat(FeeCalculator.calculate(flat, bd("1500"), ctx(null, null, null), null).netAmount()).isEqualByComparingTo("2.50");
        Fee pct = fee("TIERED_PERCENTAGE", null, null, null, null, 0, tiers, null, "USD");
        FeeCalculator.Result r = FeeCalculator.calculate(pct, bd("8000"), ctx(null, null, null), null);
        assertThat(r.netAmount()).isEqualByComparingTo("8.00");
        assertThat(r.tierNo()).isEqualTo(3);
    }

    @Test
    void freeAllowanceAndBestWaiverWin() {
        Waiver half = new Waiver(UUID.randomUUID(), "SEGMENT", null, null, "STUDENT", bd("0.5"), null, null, null);
        Waiver full = new Waiver(UUID.randomUUID(), "MIN_BALANCE", bd("5000"), null, null, bd("1"), null, null, null);
        Fee f = fee("FLAT", "10.00", null, null, null, 3, null, List.of(half, full), "USD");
        FeeCalculator.Result free = FeeCalculator.calculate(f, bd("0"), ctx(2, null, null), null);
        assertThat(free.freeAllowanceApplied()).isTrue();
        assertThat(free.netAmount()).isEqualByComparingTo("0");
        FeeCalculator.Result student = FeeCalculator.calculate(f, bd("0"), ctx(4, "100", "STUDENT"), null);
        assertThat(student.netAmount()).isEqualByComparingTo("5.00");
        assertThat(student.waiverApplied().condition()).isEqualTo("SEGMENT");
        FeeCalculator.Result both = FeeCalculator.calculate(f, bd("0"), ctx(4, "6000", "STUDENT"), null);
        assertThat(both.netAmount()).isEqualByComparingTo("0");
        assertThat(both.waiverApplied().condition()).isEqualTo("MIN_BALANCE");
        FeeCalculator.Result none = FeeCalculator.calculate(f, bd("0"), ctx(4, "100", "RETAIL"), null);
        assertThat(none.netAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void periodCapLimitsCharge() {
        Fee f = new Fee(UUID.randomUUID(), "ATM", "FLAT", bd("2.00"), null, null, null, bd("5.00"), 0, "USD", null, null,
                null, null);
        Context c = new Context(D, 3, null, null, null, null, null, null, null, null, bd("4.00"));
        FeeCalculator.Result r = FeeCalculator.calculate(f, bd("100"), c, null);
        assertThat(r.netAmount()).isEqualByComparingTo("1.00");
        assertThat(r.capApplied()).isTrue();
    }

    @Test
    void invalidInputs() {
        Fee f = fee("PERCENTAGE", null, null, null, null, 0, null, null, "USD");
        assertThatThrownBy(() -> FeeCalculator.calculate(f, bd("10"), ctx(null, null, null), null))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("incomplete_fee_definition");
        assertThatThrownBy(() -> FeeCalculator.calculate(fee("FLAT", "1", null, null, null, 0, null, null, "USD"),
                bd("-1"), ctx(null, null, null), null)).isInstanceOf(ApiException.class);
    }

    @Property(tries = 400)
    void netIsBoundedAndTotalIsNetPlusTax(@ForAll @BigRange(min = "0", max = "1000000") BigDecimal base,
                                          @ForAll @BigRange(min = "0", max = "0.05") BigDecimal pct,
                                          @ForAll @BigRange(min = "0", max = "1") BigDecimal discount) {
        Waiver w = new Waiver(UUID.randomUUID(), "MANUAL", null, null, null,
                discount.signum() == 0 ? bd("0.01") : discount, null, null, null);
        Fee f = fee("PERCENTAGE", null, pct.toPlainString(), "0.50", "100.00", 0, null, List.of(w), "EUR");
        Context c = new Context(D, null, null, null, null, null, null, null, null, true, null);
        FeeCalculator.Result r = FeeCalculator.calculate(f, base, c, VAT);
        assertThat(r.netAmount()).isBetween(BigDecimal.ZERO, r.grossAmount());
        assertThat(r.grossAmount()).isLessThanOrEqualTo(bd("100.00"));
        assertThat(r.totalAmount()).isEqualByComparingTo(r.netAmount().add(r.tax().amount()));
        assertThat(r.netAmount().scale()).isLessThanOrEqualTo(2);
        assertThat(r.tax().amount().scale()).isLessThanOrEqualTo(2);
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
