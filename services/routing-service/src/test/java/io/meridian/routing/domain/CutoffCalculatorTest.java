package io.meridian.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CutoffCalculatorTest {
    // 2026-10-06 is a Tuesday; 2026-10-09 Friday; 2026-10-12 Monday (a holiday in the test calendar)
    static final CutoffCalculator.BusinessCalendar CAL = d -> CutoffCalculator.WEEKENDS_ONLY.isBusinessDay(d)
            && !Set.of(LocalDate.parse("2026-10-12")).contains(d);
    static final CutoffCalculator.Cutoff LONDON_1530 = new CutoffCalculator.Cutoff("15:30", "Europe/London", List.of(), true);

    @Test
    void beforeCutoffExecutesToday() {
        CutoffCalculator.Result r = CutoffCalculator.compute(Instant.parse("2026-10-06T13:00:00Z"), false, null,
                LONDON_1530, null, CAL);
        assertThat(r.executionDate()).isEqualTo(LocalDate.parse("2026-10-06"));
        assertThat(r.afterCutoff()).isFalse();
        assertThat(r.cutoffAt().toString()).isEqualTo("2026-10-06T15:30+01:00");
    }

    @Test
    void afterCutoffRollsOverWeekendAndHoliday() {
        // Friday 15:00Z = 16:00 London: after cut-off -> Monday is a holiday -> Tuesday
        CutoffCalculator.Result r = CutoffCalculator.compute(Instant.parse("2026-10-09T15:00:00Z"), false, null,
                LONDON_1530, null, CAL);
        assertThat(r.afterCutoff()).isTrue();
        assertThat(r.executionDate()).isEqualTo(LocalDate.parse("2026-10-13"));
        CutoffCalculator.Result noRoll = CutoffCalculator.compute(Instant.parse("2026-10-09T15:00:00Z"), false, null,
                new CutoffCalculator.Cutoff("15:30", "Europe/London", null, false), null, CAL);
        assertThat(noRoll.executionDate()).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(noRoll.afterCutoff()).isTrue();
    }

    @Test
    void weekendAndRequestedDatesAndDaysOfWeek() {
        CutoffCalculator.Result sat = CutoffCalculator.compute(Instant.parse("2026-10-10T09:00:00Z"), false, null,
                LONDON_1530, null, CAL);
        assertThat(sat.executionDate()).isEqualTo(LocalDate.parse("2026-10-13"));
        assertThat(sat.todayIsBusinessDay()).isFalse();
        CutoffCalculator.Result future = CutoffCalculator.compute(Instant.parse("2026-10-06T09:00:00Z"), false, null,
                LONDON_1530, LocalDate.parse("2026-10-08"), CAL);
        assertThat(future.executionDate()).isEqualTo(LocalDate.parse("2026-10-08"));
        CutoffCalculator.Result thursdaysOnly = CutoffCalculator.compute(Instant.parse("2026-10-06T09:00:00Z"), false,
                null, new CutoffCalculator.Cutoff("12:00", "UTC", List.of("THURSDAY"), true), null, CAL);
        assertThat(thursdaysOnly.executionDate()).isEqualTo(LocalDate.parse("2026-10-08"));
    }

    @Test
    void instantRailsRunAnyDayWithinTheirWindow() {
        CutoffCalculator.Result sunday = CutoffCalculator.compute(Instant.parse("2026-10-11T23:00:00Z"), true, null, null,
                null, CAL);
        assertThat(sunday.immediate()).isTrue();
        assertThat(sunday.executionDate()).isEqualTo(LocalDate.parse("2026-10-11"));
        CutoffCalculator.Result late = CutoffCalculator.compute(Instant.parse("2026-10-06T22:30:00Z"), true,
                new CutoffCalculator.Window("06:00", "22:00", "UTC"), null, null, CAL);
        assertThat(late.immediate()).isFalse();
        assertThat(late.afterCutoff()).isTrue();
        assertThat(late.executionDate()).isEqualTo(LocalDate.parse("2026-10-07"));
    }

    @Test
    void bankCodesAndSignatures() {
        assertThat(BankCodes.ibanError("GB82 WEST 1234 5698 7654 32")).isEmpty();
        assertThat(BankCodes.ibanError("GB82WEST12345698765433")).contains("invalid_iban_checksum");
        assertThat(BankCodes.bankOf("GB82WEST12345698765432").bankCode()).isEqualTo("123456");
        assertThat(BankCodes.bankOf("DE89370400440532013000").bankCode()).isEqualTo("37040044");
        String sig = BankCodes.sign("s3cret", "<x/>");
        assertThat(BankCodes.verify("s3cret", "<x/>", sig)).isTrue();
        assertThat(BankCodes.verify("s3cret", "<y/>", sig)).isFalse();
        assertThat(BankCodes.verify(null, "<x/>", sig)).isFalse();
    }
}
