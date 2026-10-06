package io.meridian.referencedata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.referencedata.domain.BusinessCalendar.HolidayDay;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plain unit tests of the reference-data domain rules (no Spring, no database). */
class ReferenceDataDomainTest {

    @Test
    void validIbansPassMod97AndExposeBankCodes() {
        var de = AccountNumbers.checkIban("DE89 3704 0044 0532 0130 00", "DE", null);
        assertThat(de.valid()).isTrue();
        assertThat(de.clearingCode()).isEqualTo("37040044");
        var gb = AccountNumbers.checkIban("GB82WEST12345698765432", "GB", 22);
        assertThat(gb.valid()).isTrue();
        assertThat(gb.clearingCode()).isEqualTo("123456");
        assertThat(gb.bicBankCode()).isEqualTo("WEST");
        assertThat(AccountNumbers.checkIban("NL91ABNA0417164300", null, null).valid()).isTrue();
    }

    @Test
    void invalidIbansReportSpecificIssues() {
        assertThat(codes(AccountNumbers.checkIban("DE89370400440532013001", "DE", null)))
                .containsExactly("invalid_iban_checksum");
        assertThat(codes(AccountNumbers.checkIban("DE8937040044053201300", "DE", null)))
                .contains("invalid_iban_length");
        assertThat(codes(AccountNumbers.checkIban("DE89370400440532013000", "FR", null)))
                .containsExactly("iban_country_mismatch");
        assertThat(codes(AccountNumbers.checkIban("D!89", "DE", null))).containsExactly("invalid_iban_format");
    }

    @Test
    void bicFormat() {
        assertThat(AccountNumbers.isValidBic("DEUTDEFF")).isTrue();
        assertThat(AccountNumbers.isValidBic("DEUTDEFF500")).isTrue();
        assertThat(AccountNumbers.isValidBic("DEUT1EFF")).isFalse();
        assertThat(AccountNumbers.isValidBic("DEUTDEFF5")).isFalse();
    }

    @Test
    void easterAndTarget2() {
        assertThat(HolidayGenerator.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(HolidayGenerator.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(HolidayGenerator.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(HolidayGenerator.easterSunday(2038)).isEqualTo(LocalDate.of(2038, 4, 25));
        assertThat(HolidayGenerator.generate("TARGET2", 2026)).extracting(HolidayGenerator.GeneratedHoliday::date)
                .containsExactly(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 4, 6),
                        LocalDate.of(2026, 5, 1), LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26));
        assertThatThrownBy(() -> HolidayGenerator.generate("MARS", 2026)).isInstanceOf(ApiException.class);
    }

    @Test
    void usFederalObservesWeekendHolidays() {
        // 2026-07-04 is a Saturday -> observed Friday 2026-07-03; Thanksgiving 2026-11-26.
        var dates = HolidayGenerator.generate("US_FEDERAL", 2026).stream().map(HolidayGenerator.GeneratedHoliday::date).toList();
        assertThat(dates).contains(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 1, 19));
        // 2022-01-01 was a Saturday: observed 2021-12-31 belongs to 2021.
        assertThat(HolidayGenerator.generate("US_FEDERAL", 2021).stream().map(HolidayGenerator.GeneratedHoliday::date))
                .contains(LocalDate.of(2021, 12, 31));
    }

    @Test
    void businessCalendarSkipsWeekendsAndHolidays() {
        BusinessCalendar cal = new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), List.of(
                new HolidayDay(LocalDate.of(2026, 12, 25), "Christmas", "PUBLIC", false),
                new HolidayDay(LocalDate.of(2026, 12, 24), "Christmas Eve", "BANK", true)));
        assertThat(cal.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();
        assertThat(cal.status(LocalDate.of(2026, 12, 24)).halfDay()).isTrue();
        assertThat(cal.status(LocalDate.of(2026, 12, 26)).reason()).isEqualTo("WEEKEND");
        // Thu 24 (half) -> +1 skips Fri 25 (holiday) and the weekend -> Mon 28
        assertThat(cal.shift(LocalDate.of(2026, 12, 24), 1)).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(cal.shift(LocalDate.of(2026, 12, 28), -1)).isEqualTo(LocalDate.of(2026, 12, 24));
        assertThat(cal.shift(LocalDate.of(2026, 12, 26), 0)).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThatThrownBy(() -> cal.shift(LocalDate.of(2026, 1, 1), 5000)).isInstanceOf(ApiException.class);
    }

    @Test
    void weekendParsingRejectsUnknownAndFullWeek() {
        assertThat(BusinessCalendar.parseWeekend(List.of("fri", "SATURDAY"))).containsExactly(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        assertThatThrownBy(() -> BusinessCalendar.parseWeekend(List.of("FUNDAY"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BusinessCalendar.parseWeekend(
                List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"))).isInstanceOf(ApiException.class);
    }

    @Test
    void fxMathRoundsHalfEven() {
        assertThat(FxMath.inverse(new BigDecimal("1.25"))).isEqualByComparingTo("0.8");
        assertThat(FxMath.convert(new BigDecimal("10.00"), new BigDecimal("0.1225"), 2)).isEqualByComparingTo("1.22");
        assertThat(FxMath.convert(new BigDecimal("10.00"), new BigDecimal("0.1235"), 2)).isEqualByComparingTo("1.24");
        assertThat(FxMath.consistent(new BigDecimal("1.0850000000"), FxMath.inverse(new BigDecimal("1.085")))).isTrue();
        assertThat(FxMath.consistent(new BigDecimal("1.085"), new BigDecimal("0.95"))).isFalse();
    }

    private static List<String> codes(AccountNumbers.IbanCheck c) {
        return c.issues().stream().map(AccountNumbers.Issue::code).toList();
    }
}
