package io.meridian.referencedata.domain;

import io.meridian.common.error.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic built-in holiday feeds used by the calendar import action. Each source produces the
 * non-business days of one year; rule-based so no external provider is required.
 */
public final class HolidayGenerator {
    public static final Set<String> SOURCES = Set.of("TARGET2", "WESTERN_CHRISTIAN", "US_FEDERAL");

    public record GeneratedHoliday(LocalDate date, String name, String type) {}

    private HolidayGenerator() {}

    /** Infers a feed from a calendar code when the request names none (e.g. TARGET2, FEDNOW, US-*). */
    public static String inferSource(String calendarCode) {
        String c = calendarCode == null ? "" : calendarCode.toUpperCase(Locale.ROOT);
        if (c.startsWith("TARGET")) {
            return "TARGET2";
        }
        if (c.startsWith("US") || c.equals("FEDNOW") || c.equals("FEDWIRE") || c.equals("ACH")) {
            return "US_FEDERAL";
        }
        return null;
    }

    public static List<GeneratedHoliday> generate(String source, int year) {
        if (year < 1900 || year > 2200) {
            throw ApiException.unprocessable("invalid_year", "year must be between 1900 and 2200");
        }
        String s = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "TARGET2" -> target2(year);
            case "WESTERN_CHRISTIAN" -> westernChristian(year);
            case "US_FEDERAL" -> usFederal(year);
            default -> throw ApiException.unprocessable("unknown_holiday_source",
                    "Unknown holiday source '" + source + "'; supported: " + SOURCES.stream().sorted().toList());
        };
    }

    /** Easter Sunday (Gregorian) by the anonymous Gregorian (Meeus/Jones/Butcher) algorithm. */
    public static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    static List<GeneratedHoliday> target2(int year) {
        LocalDate easter = easterSunday(year);
        return List.of(
                new GeneratedHoliday(LocalDate.of(year, 1, 1), "New Year's Day", "RAIL"),
                new GeneratedHoliday(easter.minusDays(2), "Good Friday", "RAIL"),
                new GeneratedHoliday(easter.plusDays(1), "Easter Monday", "RAIL"),
                new GeneratedHoliday(LocalDate.of(year, 5, 1), "Labour Day", "RAIL"),
                new GeneratedHoliday(LocalDate.of(year, 12, 25), "Christmas Day", "RAIL"),
                new GeneratedHoliday(LocalDate.of(year, 12, 26), "Christmas Holiday", "RAIL"));
    }

    static List<GeneratedHoliday> westernChristian(int year) {
        LocalDate easter = easterSunday(year);
        return List.of(
                new GeneratedHoliday(LocalDate.of(year, 1, 1), "New Year's Day", "PUBLIC"),
                new GeneratedHoliday(easter.minusDays(2), "Good Friday", "PUBLIC"),
                new GeneratedHoliday(easter.plusDays(1), "Easter Monday", "PUBLIC"),
                new GeneratedHoliday(LocalDate.of(year, 12, 25), "Christmas Day", "PUBLIC"),
                new GeneratedHoliday(LocalDate.of(year, 12, 26), "Boxing Day", "PUBLIC"));
    }

    /** US federal holidays (5 U.S.C. 6103), fixed-date ones observed on Friday/Monday when on a weekend. */
    static List<GeneratedHoliday> usFederal(int year) {
        List<GeneratedHoliday> out = new ArrayList<>();
        out.add(observed(LocalDate.of(year, 1, 1), "New Year's Day"));
        out.add(new GeneratedHoliday(nth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day", "PUBLIC"));
        out.add(new GeneratedHoliday(nth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday", "PUBLIC"));
        out.add(new GeneratedHoliday(LocalDate.of(year, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                "Memorial Day", "PUBLIC"));
        if (year >= 2021) {
            out.add(observed(LocalDate.of(year, 6, 19), "Juneteenth National Independence Day"));
        }
        out.add(observed(LocalDate.of(year, 7, 4), "Independence Day"));
        out.add(new GeneratedHoliday(nth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day", "PUBLIC"));
        out.add(new GeneratedHoliday(nth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Columbus Day", "PUBLIC"));
        out.add(observed(LocalDate.of(year, 11, 11), "Veterans Day"));
        out.add(new GeneratedHoliday(nth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day", "PUBLIC"));
        out.add(observed(LocalDate.of(year, 12, 25), "Christmas Day"));
        // An observed New Year's Day of the next year can fall on Dec 31 of this year.
        GeneratedHoliday nextNewYear = observed(LocalDate.of(year + 1, 1, 1), "New Year's Day");
        if (nextNewYear.date().getYear() == year) {
            out.add(nextNewYear);
        }
        out.removeIf(h -> h.date().getYear() != year);
        return out;
    }

    private static GeneratedHoliday observed(LocalDate date, String name) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> new GeneratedHoliday(date.minusDays(1), name + " (observed)", "OBSERVED");
            case SUNDAY -> new GeneratedHoliday(date.plusDays(1), name + " (observed)", "OBSERVED");
            default -> new GeneratedHoliday(date, name, "PUBLIC");
        };
    }

    private static LocalDate nth(int year, Month month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }
}
