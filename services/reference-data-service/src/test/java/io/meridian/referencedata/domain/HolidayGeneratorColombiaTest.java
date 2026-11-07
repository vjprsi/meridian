package io.meridian.referencedata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Colombian festivos (Ley 51 de 1983) against the officially published calendars. */
class HolidayGeneratorColombiaTest {

    private static List<LocalDate> dates(int year) {
        return HolidayGenerator.generate("CO", year).stream().map(HolidayGenerator.GeneratedHoliday::date).toList();
    }

    @Test
    void the2026CalendarMatchesThePublishedFestivos() {
        assertThat(dates(2026)).containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 25));
    }

    @Test
    void the2027CalendarMatchesThePublishedFestivos() {
        assertThat(dates(2027)).containsExactly(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 11), LocalDate.of(2027, 3, 22),
                LocalDate.of(2027, 3, 25), LocalDate.of(2027, 3, 26), LocalDate.of(2027, 5, 1),
                LocalDate.of(2027, 5, 10), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 7),
                LocalDate.of(2027, 7, 5), LocalDate.of(2027, 7, 20), LocalDate.of(2027, 8, 7),
                LocalDate.of(2027, 8, 16), LocalDate.of(2027, 10, 18), LocalDate.of(2027, 11, 1),
                LocalDate.of(2027, 11, 15), LocalDate.of(2027, 12, 8), LocalDate.of(2027, 12, 25));
    }

    @Test
    void movedFeastsAlwaysLandOnAMondayAndThereAreEighteenEveryYear() {
        for (int year = 2024; year <= 2040; year++) {
            final int y = year;
            List<HolidayGenerator.GeneratedHoliday> h = HolidayGenerator.generate("CO", y);
            assertThat(h).as("festivos " + y).hasSize(18);
            h.stream().filter(x -> x.name().startsWith("Ascensi") || x.name().startsWith("Corpus")
                            || x.name().startsWith("Sagrado") || x.name().contains("Reyes") || x.name().contains("San Jos")
                            || x.name().contains("Asunci") || x.name().contains("Raza") || x.name().contains("Santos")
                            || x.name().contains("Cartagena") || x.name().startsWith("San Pedro"))
                    .forEach(x -> assertThat(x.date().getDayOfWeek()).as(x.name() + " " + y).isEqualTo(DayOfWeek.MONDAY));
        }
    }

    @Test
    void colombianRailAndCalendarCodesInferTheColombianFeed() {
        for (String code : List.of("CO", "CO-FESTIVOS", "CO_ACH", "TRANSFIYA", "ACH_CO", "ACH-CO", "CENIT")) {
            assertThat(HolidayGenerator.inferSource(code)).as(code).isEqualTo("CO");
        }
        assertThat(HolidayGenerator.inferSource("ACH")).isEqualTo("US_FEDERAL");
        // Bre-B runs 24x7x365 including festivos: no holiday feed
        assertThat(HolidayGenerator.inferSource("BRE-B")).isNull();
        assertThat(HolidayGenerator.inferSource("BREB")).isNull();
    }
}
