package io.meridian.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RecurringDetectorTest {
    private static RecurringDetector.Charge c(String date, String amount) {
        return new RecurringDetector.Charge(LocalDate.parse(date), new BigDecimal(amount));
    }

    @Test
    void monthlySubscriptionWithClampedMonthEnds() {
        Optional<RecurringDetector.Detection> d = RecurringDetector.detect(List.of(
                c("2026-01-31", "-9.99"), c("2026-02-28", "-9.99"), c("2026-03-31", "-9.99"), c("2026-04-30", "-9.99")));
        assertThat(d).isPresent();
        assertThat(d.get().frequency()).isEqualTo("MONTHLY");
        assertThat(d.get().typicalAmount()).isEqualByComparingTo("9.99");
        assertThat(d.get().nextExpected()).isEqualTo(LocalDate.parse("2026-05-30"));
        assertThat(d.get().occurrences()).isEqualTo(4);
        assertThat(d.get().priceChanged()).isFalse();
        assertThat(d.get().confidence()).isEqualByComparingTo("1.0000");
    }

    @Test
    void weeklyWithAmountTolerance() {
        Optional<RecurringDetector.Detection> d = RecurringDetector.detect(List.of(
                c("2026-03-02", "20.00"), c("2026-03-09", "21.50"), c("2026-03-16", "19.20"), c("2026-03-23", "20.40")));
        assertThat(d).isPresent();
        assertThat(d.get().frequency()).isEqualTo("WEEKLY");
        assertThat(d.get().nextExpected()).isEqualTo(LocalDate.parse("2026-03-30"));
    }

    @Test
    void annualNeedsOnlyTwoCharges() {
        Optional<RecurringDetector.Detection> d = RecurringDetector.detect(List.of(c("2025-06-10", "-99"), c("2026-06-12", "-99")));
        assertThat(d).isPresent();
        assertThat(d.get().frequency()).isEqualTo("YEARLY");
        assertThat(d.get().nextExpected()).isEqualTo(LocalDate.parse("2027-06-12"));
    }

    @Test
    void biweeklyAndQuarterly() {
        assertThat(RecurringDetector.detect(List.of(c("2026-01-02", "1500"), c("2026-01-16", "1500"), c("2026-01-30", "1500")))
                .map(RecurringDetector.Detection::frequency)).contains("BIWEEKLY");
        assertThat(RecurringDetector.detect(List.of(c("2026-01-15", "300"), c("2026-04-15", "300")))
                .map(RecurringDetector.Detection::frequency)).contains("QUARTERLY");
    }

    @Test
    void priceIncreaseIsReported() {
        Optional<RecurringDetector.Detection> d = RecurringDetector.detect(List.of(
                c("2026-01-05", "10.99"), c("2026-02-05", "10.99"), c("2026-03-05", "10.99"), c("2026-04-05", "12.49")));
        assertThat(d).isPresent();
        assertThat(d.get().priceChanged()).isTrue();
        assertThat(d.get().previousAmount()).isEqualByComparingTo("10.99");
        assertThat(d.get().lastAmount()).isEqualByComparingTo("12.49");
    }

    @Test
    void irregularOrVaryingSeriesAreRejected() {
        assertThat(RecurringDetector.detect(List.of(c("2026-01-01", "10"), c("2026-01-04", "10"), c("2026-02-20", "10"),
                c("2026-02-23", "10"), c("2026-05-01", "10")))).isEmpty();
        assertThat(RecurringDetector.detect(List.of(c("2026-01-05", "10"), c("2026-02-05", "55"), c("2026-03-05", "3"),
                c("2026-04-05", "80")))).isEmpty();
        assertThat(RecurringDetector.detect(List.of(c("2026-01-05", "10"), c("2026-02-05", "10")))).isEmpty();
        assertThat(RecurringDetector.detect(List.of(c("2026-01-05", "10")))).isEmpty();
    }

    @Test
    void toleranceAndPriceChangeHelpers() {
        assertThat(RecurringDetector.within(new BigDecimal("100"), new BigDecimal("115"), RecurringDetector.AMOUNT_TOLERANCE)).isTrue();
        assertThat(RecurringDetector.within(new BigDecimal("100"), new BigDecimal("115.01"), RecurringDetector.AMOUNT_TOLERANCE)).isFalse();
        assertThat(RecurringDetector.changed(new BigDecimal("10"), new BigDecimal("10.10"))).isFalse();
        assertThat(RecurringDetector.changed(new BigDecimal("10"), new BigDecimal("10.30"))).isTrue();
        assertThat(RecurringDetector.graceDays("MONTHLY")).isEqualTo(10);
    }

    @Property(tries = 200)
    void exactCadencesAreAlwaysDetected(@ForAll @IntRange(min = 3, max = 24) int n, @ForAll @IntRange(min = 1, max = 5000) int cents,
                                        @ForAll @IntRange(min = 0, max = 1) int kind) {
        LocalDate d = LocalDate.of(2024, 1, 10);
        List<RecurringDetector.Charge> charges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            charges.add(new RecurringDetector.Charge(kind == 0 ? d.plusWeeks(i) : d.plusMonths(i), BigDecimal.valueOf(cents, 2)));
        }
        Optional<RecurringDetector.Detection> det = RecurringDetector.detect(charges);
        assertThat(det).isPresent();
        assertThat(det.get().frequency()).isEqualTo(kind == 0 ? "WEEKLY" : "MONTHLY");
        assertThat(det.get().occurrences()).isEqualTo(n);
        assertThat(det.get().nextExpected()).isAfter(charges.getLast().date());
    }
}
