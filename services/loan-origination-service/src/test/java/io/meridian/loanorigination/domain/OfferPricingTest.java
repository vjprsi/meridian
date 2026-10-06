package io.meridian.loanorigination.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class OfferPricingTest {
    static final LocalDate START = LocalDate.of(2026, 1, 15);

    static OfferPricing.Quote price(String principal, String rate, int term, OfferPricing.Frequency f,
                                    OfferPricing.ScheduleType t, String fees) {
        return OfferPricing.price(new OfferPricing.Request(new BigDecimal(principal), new BigDecimal(rate), term, f, t,
                new BigDecimal(fees), null, START, null, "EUR"));
    }

    @Test
    void annuityInstalmentMatchesTheTextbookFormula() {
        OfferPricing.Quote q = price("10000", "0.12", 12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, "0");
        assertThat(q.instalment()).isEqualByComparingTo("888.49");
        assertThat(q.installments()).hasSize(12);
        assertThat(q.installments().getFirst().interest()).isEqualByComparingTo("100.00");
        assertThat(q.installments().getLast().balanceAfter()).isEqualByComparingTo("0");
        assertThat(q.totalInterest()).isEqualByComparingTo("661.86");
        assertThat(q.totalRepayable()).isEqualByComparingTo("10661.86");
        assertThat(q.installments().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void aprOfAFeeFreeMonthlyLoanIsTheEffectiveAnnualRate() {
        OfferPricing.Quote q = price("10000", "0.12", 12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, "0");
        // (1 + 0.12/12)^12 - 1 = 12.6825%; calendar-day timing moves it only marginally
        assertThat(q.apr().doubleValue()).isCloseTo(0.126825, within(0.0015));
    }

    @Test
    void feesRaiseTheApr() {
        OfferPricing.Quote noFee = price("10000", "0.12", 12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, "0");
        OfferPricing.Quote fee = price("10000", "0.12", 12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, "200");
        assertThat(fee.apr()).isGreaterThan(noFee.apr());
        // a 2% upfront fee on a one-year amortising loan costs roughly 3.5-4 APR points
        assertThat(fee.apr().subtract(noFee.apr()).doubleValue()).isBetween(0.03, 0.045);
        assertThat(fee.totalRepayable()).isEqualByComparingTo(noFee.totalRepayable().add(new BigDecimal("200")));
    }

    @Test
    void zeroRateWithoutFeesHasZeroApr() {
        OfferPricing.Quote q = price("1200", "0", 4, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, "0");
        assertThat(q.instalment()).isEqualByComparingTo("300.00");
        assertThat(q.apr().doubleValue()).isCloseTo(0, within(1e-6));
    }

    @Test
    void bulletPaysEverythingAtMaturity() {
        OfferPricing.Quote q = price("10000", "0.10", 12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.BULLET, "0");
        assertThat(q.installments()).hasSize(1);
        assertThat(q.installments().getFirst().payment()).isEqualByComparingTo("11000.00");
        assertThat(q.installments().getFirst().dueDate()).isEqualTo(START.plusMonths(12));
        assertThat(q.apr().doubleValue()).isCloseTo(0.10, within(0.001));
    }

    @Test
    void interestOnlyAndBalloonCarryPrincipalToTheEnd() {
        OfferPricing.Quote io = price("12000", "0.06", 6, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.INTEREST_ONLY, "0");
        assertThat(io.installments().subList(0, 5)).allMatch(i -> i.principal().signum() == 0 && i.interest().compareTo(new BigDecimal("60.00")) == 0);
        assertThat(io.installments().getLast().principal()).isEqualByComparingTo("12000");
        OfferPricing.Quote balloon = OfferPricing.price(new OfferPricing.Request(new BigDecimal("20000"), new BigDecimal("0.08"),
                36, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.BALLOON, BigDecimal.ZERO, new BigDecimal("8000"),
                START, null, "EUR"));
        assertThat(balloon.installments().get(34).balanceAfter().doubleValue()).isCloseTo(8000, within(400.0));
        assertThat(balloon.installments().getLast().payment()).isGreaterThan(new BigDecimal("8000"));
        assertThat(balloon.instalment()).isLessThan(price("20000", "0.08", 36, OfferPricing.Frequency.MONTHLY,
                OfferPricing.ScheduleType.ANNUITY, "0").instalment());
    }

    @Test
    void equalPrincipalDeclinesAndRoundsToTheMinorUnit() {
        OfferPricing.Quote q = price("1000", "0.12", 3, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.EQUAL_PRINCIPAL, "0");
        assertThat(q.installments()).extracting(OfferPricing.Installment::principal)
                .containsExactly(new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
        assertThat(q.installments().get(0).payment()).isGreaterThan(q.installments().get(1).payment());
        OfferPricing.Quote yen = OfferPricing.price(new OfferPricing.Request(new BigDecimal("100000"), new BigDecimal("0.05"),
                12, OfferPricing.Frequency.MONTHLY, OfferPricing.ScheduleType.ANNUITY, BigDecimal.ZERO, null, START, null, "JPY"));
        assertThat(yen.installments()).allMatch(i -> i.payment().scale() <= 0 || i.payment().stripTrailingZeros().scale() <= 0);
    }

    @Test
    void weeklyAndExplicitFirstDueDate() {
        OfferPricing.Quote q = OfferPricing.price(new OfferPricing.Request(new BigDecimal("520"), new BigDecimal("0.10"), 3,
                OfferPricing.Frequency.WEEKLY, OfferPricing.ScheduleType.ANNUITY, BigDecimal.ZERO, null, START,
                LocalDate.of(2026, 1, 20), "EUR"));
        assertThat(q.installments().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(q.installments().get(1).dueDate()).isEqualTo(LocalDate.of(2026, 1, 27));
        assertThat(q.installments().size()).isBetween(12, 13);
    }

    @Test
    void irrFallsBackToBisectionAndRejectsOneSidedFlows() {
        double r = Irr.annualRate(List.of(new Irr.Flow(0, -100), new Irr.Flow(1, 0), new Irr.Flow(2, 0), new Irr.Flow(3, 400)));
        assertThat(r).isCloseTo(Math.cbrt(4) - 1, within(1e-8));
        double negative = Irr.annualRate(List.of(new Irr.Flow(0, -100), new Irr.Flow(1, 50)));
        assertThat(negative).isCloseTo(-0.5, within(1e-8));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Irr.annualRate(List.of(new Irr.Flow(0, 100), new Irr.Flow(1, 50))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ properties

    @Property(tries = 300)
    void principalIsFullyRepaidAndAprIsAtLeastNominal(@ForAll @BigRange(min = "100", max = "1000000") BigDecimal amount,
                                                       @ForAll @IntRange(min = 0, max = 3500) int rateBp,
                                                       @ForAll @IntRange(min = 3, max = 120) int term,
                                                       @ForAll("types") OfferPricing.ScheduleType type,
                                                       @ForAll("frequencies") OfferPricing.Frequency f,
                                                       @ForAll @IntRange(min = 0, max = 300) int feeBp) {
        BigDecimal principal = amount.setScale(2, java.math.RoundingMode.DOWN);
        BigDecimal rate = BigDecimal.valueOf(rateBp, 4);
        BigDecimal fees = principal.multiply(BigDecimal.valueOf(feeBp, 4)).setScale(2, java.math.RoundingMode.DOWN);
        OfferPricing.Quote q = OfferPricing.price(new OfferPricing.Request(principal, rate, term, f, type, fees, null, START,
                null, "EUR"));
        BigDecimal sum = q.installments().stream().map(OfferPricing.Installment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(principal);
        assertThat(q.installments().getLast().balanceAfter()).isEqualByComparingTo("0");
        assertThat(q.installments()).allMatch(i -> i.principal().signum() >= 0 && i.interest().signum() >= 0);
        assertThat(q.totalRepayable()).isEqualByComparingTo(principal.add(q.totalInterest()).add(fees));
        if (rate.signum() > 0 || fees.signum() > 0) {
            // periodic compounding and fees can only push the effective annual cost above the nominal rate
            assertThat(q.apr().doubleValue()).isGreaterThanOrEqualTo(rate.doubleValue() - 0.002);
        }
        if (fees.signum() > 0) {
            OfferPricing.Quote noFee = OfferPricing.price(new OfferPricing.Request(principal, rate, term, f, type,
                    BigDecimal.ZERO, null, START, null, "EUR"));
            assertThat(q.apr()).isGreaterThanOrEqualTo(noFee.apr());
        }
    }

    @Provide
    Arbitrary<OfferPricing.ScheduleType> types() {
        return Arbitraries.of(OfferPricing.ScheduleType.ANNUITY, OfferPricing.ScheduleType.EQUAL_PRINCIPAL,
                OfferPricing.ScheduleType.INTEREST_ONLY, OfferPricing.ScheduleType.BALLOON);
    }

    @Provide
    Arbitrary<OfferPricing.Frequency> frequencies() {
        return Arbitraries.of(OfferPricing.Frequency.MONTHLY, OfferPricing.Frequency.QUARTERLY, OfferPricing.Frequency.FORTNIGHTLY);
    }
}
