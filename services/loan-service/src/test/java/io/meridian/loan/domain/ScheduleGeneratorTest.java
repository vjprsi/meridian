package io.meridian.loan.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {
    static final LocalDate START = LocalDate.of(2026, 1, 15);
    static final BusinessCalendar CAL = new BusinessCalendar(Set.of(LocalDate.of(2026, 12, 25)));

    static ScheduleGenerator.Terms terms(String principal, String rate, ScheduleGenerator.Type type, Frequency f, int n,
                                         BigDecimal balloon, Integer io, List<ScheduleGenerator.CustomLine> custom) {
        return new ScheduleGenerator.Terms(new BigDecimal(principal), new BigDecimal(rate), DayCount.ACT_365, type, f, n,
                START, null, balloon, io, custom, 2, CAL, BusinessCalendar.Convention.MODIFIED_FOLLOWING);
    }

    @Test
    void annuityHasLevelInstalmentsAndTrueUpOnTheLast() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(terms("10000", "0.12", ScheduleGenerator.Type.ANNUITY,
                Frequency.MONTHLY, 12, null, null, null));
        assertThat(s.lines()).hasSize(12);
        // textbook level payment 888.49 for 10,000 at 1% per month over 12 months
        assertThat(s.regularInstalment()).isEqualByComparingTo("888.49");
        for (int i = 0; i < 11; i++) {
            assertThat(s.lines().get(i).total()).isEqualByComparingTo("888.49");
        }
        assertThat(s.lines().getLast().balanceAfter()).isEqualByComparingTo("0");
        assertThat(sumPrincipal(s)).isEqualByComparingTo("10000");
        // interest uses actual days: a 31-day month costs more than a 28-day one
        assertThat(s.lines().getFirst().interest()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void equalPrincipalRepaysTheSamePrincipalEachTimeWithDecreasingInterest() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(terms("1200", "0.10", ScheduleGenerator.Type.EQUAL_PRINCIPAL,
                Frequency.MONTHLY, 12, null, null, null));
        assertThat(s.lines()).allSatisfy(l -> assertThat(l.principal()).isEqualByComparingTo("100"));
        assertThat(s.lines().get(0).interest()).isGreaterThan(s.lines().get(11).interest());
    }

    @Test
    void bulletPaysEverythingAtMaturity() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(terms("5000", "0.0730", ScheduleGenerator.Type.BULLET,
                Frequency.MONTHLY, 6, null, null, null));
        assertThat(s.lines()).hasSize(1);
        ScheduleGenerator.Line l = s.lines().getFirst();
        assertThat(l.principal()).isEqualByComparingTo("5000");
        // 15 Jan -> 15 Jul 2026 (Wednesday) = 181 days at 7.3% ACT/365 = 181.00
        assertThat(l.dueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(l.interest()).isEqualByComparingTo("181.00");
    }

    @Test
    void interestOnlyRepaysPrincipalAtTheEndOrAmortisesAfterTheInterestOnlyPeriods() {
        ScheduleGenerator.Schedule io = ScheduleGenerator.generate(terms("10000", "0.06", ScheduleGenerator.Type.INTEREST_ONLY,
                Frequency.MONTHLY, 6, null, null, null));
        assertThat(io.lines().subList(0, 5)).allSatisfy(l -> assertThat(l.principal()).isEqualByComparingTo("0"));
        assertThat(io.lines().getLast().principal()).isEqualByComparingTo("10000");

        ScheduleGenerator.Schedule mixed = ScheduleGenerator.generate(terms("10000", "0.06", ScheduleGenerator.Type.INTEREST_ONLY,
                Frequency.MONTHLY, 12, null, 3, null));
        assertThat(mixed.lines().subList(0, 3)).allSatisfy(l -> assertThat(l.principal()).isEqualByComparingTo("0"));
        assertThat(mixed.lines().get(3).principal()).isGreaterThan(BigDecimal.ZERO);
        assertThat(mixed.lines().getLast().balanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    void balloonLeavesTheBalloonForTheLastInstalment() {
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(terms("20000", "0.08", ScheduleGenerator.Type.BALLOON,
                Frequency.MONTHLY, 24, new BigDecimal("8000"), null, null));
        ScheduleGenerator.Line before = s.lines().get(22);
        assertThat(before.balanceAfter()).isBetween(new BigDecimal("8000"), new BigDecimal("8700"));
        assertThat(s.lines().getLast().principal()).isGreaterThan(new BigDecimal("8000"));
        assertThat(s.lines().getLast().balanceAfter()).isEqualByComparingTo("0");
        assertThat(s.regularInstalment()).isLessThan(ScheduleGenerator.generate(terms("20000", "0.08",
                ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 24, null, null, null)).regularInstalment());
    }

    @Test
    void customUsesGivenPrincipalsAndDates() {
        List<ScheduleGenerator.CustomLine> rows = List.of(
                new ScheduleGenerator.CustomLine(LocalDate.of(2026, 3, 1), new BigDecimal("300")),
                new ScheduleGenerator.CustomLine(LocalDate.of(2026, 2, 1), new BigDecimal("200")),
                new ScheduleGenerator.CustomLine(LocalDate.of(2026, 4, 1), new BigDecimal("500")));
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(terms("1000", "0", ScheduleGenerator.Type.CUSTOM,
                Frequency.MONTHLY, 0, null, null, rows));
        assertThat(s.lines()).extracting(ScheduleGenerator.Line::principal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("200"), new BigDecimal("300"), new BigDecimal("500"));
        // 1 Feb 2026 is a Sunday: modified following moves it to Monday 2 Feb
        assertThat(s.lines().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(s.totalInterest()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> ScheduleGenerator.generate(terms("999", "0", ScheduleGenerator.Type.CUSTOM,
                Frequency.MONTHLY, 0, null, null, rows))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not add up");
    }

    @Test
    void dueDatesAreBusinessDayAdjustedWithoutEndOfMonthDrift() {
        ScheduleGenerator.Terms t = new ScheduleGenerator.Terms(new BigDecimal("1000"), new BigDecimal("0.05"),
                DayCount.THIRTY_360, ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 4, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31), null, null, null, 2, CAL, BusinessCalendar.Convention.MODIFIED_FOLLOWING);
        List<LocalDate> due = ScheduleGenerator.generate(t).lines().stream().map(ScheduleGenerator.Line::dueDate).toList();
        // 31 Jan 2026 is a Saturday -> modified following would leave the month -> Friday 30 Jan
        assertThat(due.get(0)).isEqualTo(LocalDate.of(2026, 1, 30));
        assertThat(due.get(1)).isEqualTo(LocalDate.of(2026, 2, 27)); // 28 Feb is a Saturday
        assertThat(due.get(2)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(due.get(3)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(due).allSatisfy(d -> assertThat(d.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
    }

    @Test
    void zeroDecimalCurrenciesRoundToWholeUnits() {
        ScheduleGenerator.Terms t = new ScheduleGenerator.Terms(new BigDecimal("1000000"), new BigDecimal("0.035"),
                DayCount.ACT_365, ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 10, START, null, null, null, null, 0,
                CAL, BusinessCalendar.Convention.FOLLOWING);
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(t);
        assertThat(s.lines()).allSatisfy(l -> {
            assertThat(l.total().scale()).isLessThanOrEqualTo(0);
            assertThat(l.principal().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
        });
        assertThat(sumPrincipal(s)).isEqualByComparingTo("1000000");
    }

    @Test
    void invalidTermsAreRejected() {
        assertThatThrownBy(() -> terms("1000", "0.1", ScheduleGenerator.Type.BALLOON, Frequency.MONTHLY, 12,
                new BigDecimal("1000"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> terms("0", "0.1", ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 12, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> terms("100", "-0.1", ScheduleGenerator.Type.ANNUITY, Frequency.MONTHLY, 12, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void periodsForInvertsTheAnnuity() {
        BigDecimal r = new BigDecimal("0.01");
        BigDecimal a = ScheduleGenerator.annuity(new BigDecimal("10000"), r, 24);
        assertThat(ScheduleGenerator.periodsFor(new BigDecimal("10000"), a, r)).isEqualTo(24);
        assertThat(ScheduleGenerator.periodsFor(new BigDecimal("5000"), a, r)).isLessThan(24);
        assertThat(ScheduleGenerator.presentValue(a, r, 24)).isCloseTo(new BigDecimal("10000"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.0001")));
    }

    // ------------------------------------------------------------------ properties

    @Property(tries = 400)
    void principalAlwaysSumsToTheLoanAndTheFinalBalanceIsZero(
            @ForAll("principals") BigDecimal principal, @ForAll("rates") BigDecimal rate,
            @ForAll("types") ScheduleGenerator.Type type, @ForAll("frequencies") Frequency frequency,
            @ForAll @IntRange(min = 1, max = 120) int periods, @ForAll @IntRange(min = 0, max = 2) int scale) {
        BigDecimal p = principal.setScale(scale, java.math.RoundingMode.DOWN).max(BigDecimal.ONE);
        BigDecimal balloon = type == ScheduleGenerator.Type.BALLOON
                ? p.divide(new BigDecimal("3"), scale, java.math.RoundingMode.DOWN).max(BigDecimal.ONE.movePointLeft(scale)) : null;
        if (balloon != null && balloon.compareTo(p) >= 0) {
            return;
        }
        ScheduleGenerator.Terms t = new ScheduleGenerator.Terms(p, rate, DayCount.ACT_365, type, frequency, periods, START,
                null, balloon, type == ScheduleGenerator.Type.INTEREST_ONLY ? periods / 2 : null, null, scale, CAL,
                BusinessCalendar.Convention.MODIFIED_FOLLOWING);
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(t);
        assertThat(sumPrincipal(s)).isEqualByComparingTo(p);
        assertThat(s.lines().getLast().balanceAfter()).isEqualByComparingTo("0");
        BigDecimal balance = p;
        LocalDate prev = START;
        for (ScheduleGenerator.Line l : s.lines()) {
            assertThat(l.principal().signum()).isGreaterThanOrEqualTo(0);
            assertThat(l.interest().signum()).isGreaterThanOrEqualTo(0);
            assertThat(l.dueDate()).isAfter(prev);
            assertThat(l.total()).isEqualByComparingTo(l.principal().add(l.interest()));
            balance = balance.subtract(l.principal());
            assertThat(l.balanceAfter()).isEqualByComparingTo(balance);
            assertThat(l.principal().stripTrailingZeros().scale()).isLessThanOrEqualTo(Math.max(scale, 0));
            prev = l.dueDate();
        }
        if (type == ScheduleGenerator.Type.BULLET) {
            assertThat(s.lines()).hasSize(1);
        } else {
            assertThat(s.lines()).hasSize(frequency == Frequency.AT_MATURITY ? periods : periods);
        }
    }

    @Property(tries = 200)
    void zeroRateMeansNoInterest(@ForAll("principals") BigDecimal principal, @ForAll("types") ScheduleGenerator.Type type,
                                 @ForAll @IntRange(min = 1, max = 60) int periods) {
        BigDecimal p = principal.setScale(2, java.math.RoundingMode.DOWN).max(BigDecimal.ONE);
        BigDecimal balloon = type == ScheduleGenerator.Type.BALLOON ? p.divide(new BigDecimal("4"), 2, java.math.RoundingMode.DOWN) : null;
        if (balloon != null && balloon.signum() == 0) {
            return;
        }
        ScheduleGenerator.Schedule s = ScheduleGenerator.generate(new ScheduleGenerator.Terms(p, BigDecimal.ZERO,
                DayCount.ACT_360, type, Frequency.FORTNIGHTLY, periods, START, null, balloon, null, null, 2, CAL,
                BusinessCalendar.Convention.FOLLOWING));
        assertThat(s.totalInterest()).isEqualByComparingTo("0");
        assertThat(s.totalRepayable()).isEqualByComparingTo(p);
    }

    @Provide
    Arbitrary<BigDecimal> principals() {
        return Arbitraries.bigDecimals().between(new BigDecimal("1"), new BigDecimal("5000000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("0.60")).ofScale(4);
    }

    @Provide
    Arbitrary<ScheduleGenerator.Type> types() {
        return Arbitraries.of(ScheduleGenerator.Type.ANNUITY, ScheduleGenerator.Type.EQUAL_PRINCIPAL,
                ScheduleGenerator.Type.BULLET, ScheduleGenerator.Type.BALLOON, ScheduleGenerator.Type.INTEREST_ONLY);
    }

    @Provide
    Arbitrary<Frequency> frequencies() {
        return Arbitraries.of(Frequency.WEEKLY, Frequency.FORTNIGHTLY, Frequency.MONTHLY, Frequency.QUARTERLY,
                Frequency.SEMI_ANNUAL, Frequency.ANNUAL);
    }

    static BigDecimal sumPrincipal(ScheduleGenerator.Schedule s) {
        return s.lines().stream().map(ScheduleGenerator.Line::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
