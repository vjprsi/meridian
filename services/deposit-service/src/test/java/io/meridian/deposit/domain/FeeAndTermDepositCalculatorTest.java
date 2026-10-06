package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class FeeAndTermDepositCalculatorTest {

    @Test
    void flatPercentageMinMaxAndTax() {
        var flat = new FeeCalculator.FeeDefinition("MAINT", "FLAT", new BigDecimal("5"), null, null, null,
                new BigDecimal("0.20"), true);
        assertThat(FeeCalculator.compute(flat, null, "EUR")).isEqualTo(
                new FeeCalculator.Charge(new BigDecimal("5.00"), new BigDecimal("1.00")));
        var pct = new FeeCalculator.FeeDefinition("FX", "PERCENTAGE", null, new BigDecimal("0.015"),
                new BigDecimal("1.00"), new BigDecimal("25.00"), null, true);
        assertThat(FeeCalculator.compute(pct, new BigDecimal("10"), "USD").amount()).isEqualByComparingTo("1.00"); // min
        assertThat(FeeCalculator.compute(pct, new BigDecimal("1000"), "USD").amount()).isEqualByComparingTo("15.00");
        assertThat(FeeCalculator.compute(pct, new BigDecimal("100000"), "USD").amount()).isEqualByComparingTo("25.00"); // max
        var mixed = new FeeCalculator.FeeDefinition("TX", "FLAT_PLUS_PERCENTAGE", new BigDecimal("0.30"),
                new BigDecimal("0.029"), null, null, null, true);
        assertThat(FeeCalculator.compute(mixed, new BigDecimal("12.35"), "USD").amount()).isEqualByComparingTo("0.66");
        assertThat(FeeCalculator.compute(flat, null, "JPY").amount().scale()).isZero();
        assertThatThrownBy(() -> FeeCalculator.compute(pct, null, "USD")).isInstanceOf(ApiException.class);
    }

    @Test
    void partialRefundTaxIsProRataAndFullRefundReturnsAllTax() {
        assertThat(FeeCalculator.refundTax(new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("2.50"), "EUR"))
                .isEqualByComparingTo("0.50");
        assertThat(FeeCalculator.refundTax(new BigDecimal("3.00"), new BigDecimal("0.61"), new BigDecimal("3.00"), "EUR"))
                .isEqualByComparingTo("0.61");
    }

    /** Refunding a fee in any number of parts never returns more tax than was charged. */
    @Property(tries = 300)
    void refundsNeverExceedChargedTax(@ForAll @BigRange(min = "0.01", max = "10000") BigDecimal fee,
                                      @ForAll @IntRange(min = 1, max = 7) int parts) {
        BigDecimal amount = Currencies.round(fee, "EUR");
        if (amount.signum() == 0) {
            return;
        }
        BigDecimal tax = Currencies.round(amount.multiply(new BigDecimal("0.21")), "EUR");
        BigDecimal part = amount.divide(BigDecimal.valueOf(parts), 2, java.math.RoundingMode.DOWN);
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal taxRefunded = BigDecimal.ZERO;
        for (int i = 0; i < parts - 1 && part.signum() > 0; i++) {
            BigDecimal t = FeeCalculator.refundTax(amount, tax, part, refunded, taxRefunded, "EUR");
            assertThat(t.signum()).isGreaterThanOrEqualTo(0);
            taxRefunded = taxRefunded.add(t);
            refunded = refunded.add(part);
            assertThat(taxRefunded).isLessThanOrEqualTo(tax);
        }
        BigDecimal last = amount.subtract(refunded);
        assertThat(last.signum()).isPositive();
        BigDecimal lastTax = FeeCalculator.refundTax(amount, tax, last, refunded, taxRefunded, "EUR");
        assertThat(lastTax.signum()).isGreaterThanOrEqualTo(0);
        assertThat(taxRefunded.add(lastTax)).isEqualByComparingTo(tax);
    }

    @Test
    void termDepositInterestMaturityAndBreak() {
        var cal = Schedule.BusinessCalendar.weekendsOnly();
        LocalDate start = LocalDate.of(2026, 1, 5); // Monday
        LocalDate maturity = TermDepositCalculator.maturityDate(start, 90, cal); // 2026-04-05 is a Sunday
        assertThat(maturity).isEqualTo(LocalDate.of(2026, 4, 6));
        BigDecimal p = new BigDecimal("10000.00");
        BigDecimal rate = new BigDecimal("0.0365");
        assertThat(TermDepositCalculator.expectedInterest(p, rate, DayCount.ACT_365, start, start.plusDays(90), "EUR"))
                .isEqualByComparingTo("90.00");
        var m = TermDepositCalculator.atMaturity(p, rate, DayCount.ACT_365, start, start.plusDays(90), new BigDecimal("0.20"), "EUR");
        assertThat(m.tax()).isEqualByComparingTo("18.00");
        assertThat(m.netInterest()).isEqualByComparingTo("72.00");

        var q = TermDepositCalculator.breakQuote(p, rate, new BigDecimal("0.01"), DayCount.ACT_365, start,
                start.plusDays(30), new BigDecimal("0.10"), "EUR");
        assertThat(q.daysHeld()).isEqualTo(30);
        assertThat(q.accruedAtContractRate()).isEqualByComparingTo("30.00");
        assertThat(q.effectiveRate()).isEqualByComparingTo("0.0265");
        assertThat(q.interestPayable()).isEqualByComparingTo("21.78");
        assertThat(q.penalty()).isEqualByComparingTo("8.22");
        assertThat(q.tax()).isEqualByComparingTo("2.18");
        assertThat(q.payout()).isEqualByComparingTo("10019.60");
    }

    @Test
    void penaltyAboveTheRateForfeitsAllInterest() {
        var q = TermDepositCalculator.breakQuote(new BigDecimal("5000"), new BigDecimal("0.02"), new BigDecimal("0.05"),
                DayCount.ACT_360, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), null, "USD");
        assertThat(q.interestPayable()).isZero();
        assertThat(q.payout()).isEqualByComparingTo("5000.00");
        assertThat(q.penalty()).isEqualByComparingTo(q.accruedAtContractRate());
    }

    /** Break payout is between principal and principal plus full contract interest, and penalty is never negative. */
    @Property(tries = 300)
    void breakQuoteIsBounded(@ForAll @BigRange(min = "1", max = "1000000") BigDecimal principal,
                             @ForAll @BigRange(min = "0", max = "0.15") BigDecimal rate,
                             @ForAll @BigRange(min = "0", max = "0.2") BigDecimal penalty,
                             @ForAll @IntRange(min = 0, max = 720) int held) {
        BigDecimal p = Currencies.round(principal, "EUR");
        LocalDate start = LocalDate.of(2026, 1, 1);
        var q = TermDepositCalculator.breakQuote(p, rate, penalty, DayCount.ACT_365, start, start.plusDays(held),
                new BigDecimal("0.2"), "EUR");
        assertThat(q.penalty().signum()).isGreaterThanOrEqualTo(0);
        assertThat(q.payout()).isGreaterThanOrEqualTo(p);
        assertThat(q.payout()).isLessThanOrEqualTo(p.add(q.accruedAtContractRate()));
        assertThat(q.interestPayable().add(q.penalty())).isEqualByComparingTo(q.accruedAtContractRate());
    }

    @Test
    void closureQuoteNetsInterestTaxDebitInterestAndFees() {
        var q = ClosureCalculator.quote(new BigDecimal("1000.00"), new BigDecimal("12.345678"), new BigDecimal("0.004"),
                new BigDecimal("0.20"), new BigDecimal("5.00"), new BigDecimal("0.20"), "EUR");
        assertThat(q.creditInterest()).isEqualByComparingTo("12.35");
        assertThat(q.interestTax()).isEqualByComparingTo("2.47");
        assertThat(q.debitInterest()).isEqualByComparingTo("0.00");
        assertThat(q.fees()).isEqualByComparingTo("5.00");
        assertThat(q.feeTax()).isEqualByComparingTo("1.00");
        assertThat(q.finalPayout()).isEqualByComparingTo("1003.88");
    }
}
