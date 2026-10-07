package io.meridian.regulatoryreporting.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reporting periods and filing deadlines. Periods are calendar aligned (weeks end on Sunday, quarters in
 * March/June/September/December, half-years in June/December). The due date is the period end plus the deadline
 * rule ({@code PERIOD_END+15D} calendar days, {@code PERIOD_END+10BD} business days; {@code MONTH_END} and {@code T}
 * are synonyms of {@code PERIOD_END}), moved to a business day, plus the jurisdiction's extension in calendar days
 * (again moved to a business day). Business days are Monday to Friday minus the regulator's holidays
 * ({@link Conventions}); a date on a non-business day moves by the definition's business-day convention (FOLLOWING
 * by default, MODIFIED_FOLLOWING, PRECEDING or NONE).
 */
public final class FilingCalendar {
    private static final Pattern RULE = Pattern.compile("(PERIOD_END|MONTH_END|T)\\+(\\d{1,3})(BD|D)");

    private FilingCalendar() {}

    public record Rule(int amount, boolean businessDays) {}

    /** Parses a deadline rule; null or blank means the frequency default. */
    public static Rule parse(String rule, String frequency) {
        if (rule == null || rule.isBlank()) {
            return defaultRule(frequency);
        }
        Matcher m = RULE.matcher(rule.trim().toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("deadline_rule must look like PERIOD_END+15D or PERIOD_END+10BD");
        }
        return new Rule(Integer.parseInt(m.group(2)), "BD".equals(m.group(3)));
    }

    static Rule defaultRule(String frequency) {
        return switch (frequency) {
            case "DAILY" -> new Rule(1, true);
            case "WEEKLY" -> new Rule(3, true);
            case "MONTHLY" -> new Rule(15, false);
            case "QUARTERLY" -> new Rule(30, false);
            case "SEMI_ANNUAL" -> new Rule(45, false);
            case "ANNUAL" -> new Rule(90, false);
            default -> new Rule(5, true);
        };
    }

    public static final Set<String> CONVENTIONS = Set.of("FOLLOWING", "MODIFIED_FOLLOWING", "PRECEDING", "NONE");

    /**
     * The regulator's business-day rules: the convention applied to a date that falls on a weekend or holiday, and
     * the holidays besides Saturdays and Sundays.
     */
    public record Conventions(String convention, Set<LocalDate> holidays) {
        public static final Conventions DEFAULT = new Conventions("FOLLOWING", Set.of());

        public Conventions {
            convention = convention == null || convention.isBlank() ? "FOLLOWING" : convention.toUpperCase(java.util.Locale.ROOT);
            if (!CONVENTIONS.contains(convention)) {
                throw new IllegalArgumentException("business_day_convention must be one of " + new java.util.TreeSet<>(CONVENTIONS));
            }
            holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
        }

        public boolean isBusinessDay(LocalDate d) {
            return FilingCalendar.isBusinessDay(d) && !holidays.contains(d);
        }

        /** Moves a date that is not a business day according to the convention. */
        public LocalDate adjust(LocalDate d) {
            if (isBusinessDay(d) || "NONE".equals(convention)) {
                return d;
            }
            return switch (convention) {
                case "PRECEDING" -> previous(d);
                case "MODIFIED_FOLLOWING" -> {
                    LocalDate next = next(d);
                    yield next.getMonth() == d.getMonth() ? next : previous(d);
                }
                default -> next(d);
            };
        }

        LocalDate next(LocalDate d) {
            LocalDate x = d;
            for (int i = 0; i < 370 && !isBusinessDay(x); i++) {
                x = x.plusDays(1);
            }
            return x;
        }

        LocalDate previous(LocalDate d) {
            LocalDate x = d;
            for (int i = 0; i < 370 && !isBusinessDay(x); i++) {
                x = x.minusDays(1);
            }
            return x;
        }

        /** {@code n} business days after {@code d} (the result is a business day by construction). */
        LocalDate addBusinessDays(LocalDate d, int n) {
            LocalDate x = d;
            int left = n;
            for (int i = 0; left > 0 && i < 5000; i++) {
                x = x.plusDays(1);
                if (isBusinessDay(x)) {
                    left--;
                }
            }
            return x;
        }
    }

    public static LocalDate dueDate(String rule, String frequency, LocalDate periodEnd, int jurisdictionOffsetDays) {
        return dueDate(rule, frequency, periodEnd, jurisdictionOffsetDays, Conventions.DEFAULT);
    }

    /**
     * Due date of the period ending {@code periodEnd}: business-day rules count business days of the regulator's
     * calendar (weekends and its holidays excluded); calendar-day rules and the jurisdiction's extension move by the
     * business-day convention when they land on a non-business day.
     */
    public static LocalDate dueDate(String rule, String frequency, LocalDate periodEnd, int jurisdictionOffsetDays,
                                    Conventions conventions) {
        Rule r = parse(rule, frequency);
        Conventions c = conventions == null ? Conventions.DEFAULT : conventions;
        LocalDate due = r.businessDays() ? c.addBusinessDays(periodEnd, r.amount()) : c.adjust(periodEnd.plusDays(r.amount()));
        if (jurisdictionOffsetDays > 0) {
            due = c.adjust(due.plusDays(jurisdictionOffsetDays));
        }
        return due;
    }

    public static boolean isBusinessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** True when {@code d} is the last day of a reporting period of the frequency. */
    public static boolean isPeriodEnd(String frequency, LocalDate d) {
        return switch (frequency) {
            case "WEEKLY" -> d.getDayOfWeek() == DayOfWeek.SUNDAY;
            case "MONTHLY" -> d.equals(d.with(TemporalAdjusters.lastDayOfMonth()));
            case "QUARTERLY" -> d.getMonthValue() % 3 == 0 && d.equals(d.with(TemporalAdjusters.lastDayOfMonth()));
            case "SEMI_ANNUAL" -> d.getMonthValue() % 6 == 0 && d.equals(d.with(TemporalAdjusters.lastDayOfMonth()));
            case "ANNUAL" -> d.getMonthValue() == 12 && d.getDayOfMonth() == 31;
            default -> true;
        };
    }

    /** The last period end on or before {@code d} (the reporting date a scheduled run on {@code d} produces). */
    public static LocalDate latestPeriodEnd(String frequency, LocalDate d) {
        LocalDate x = d;
        for (int i = 0; i < 400 && !isPeriodEnd(frequency, x); i++) {
            x = x.minusDays(1);
        }
        return x;
    }

    /** First day of the period that ends on {@code periodEnd}. */
    public static LocalDate periodStart(String frequency, LocalDate periodEnd) {
        return switch (frequency) {
            case "WEEKLY" -> periodEnd.minusDays(6);
            case "MONTHLY" -> periodEnd.withDayOfMonth(1);
            case "QUARTERLY" -> periodEnd.withDayOfMonth(1).minusMonths(2);
            case "SEMI_ANNUAL" -> periodEnd.withDayOfMonth(1).minusMonths(5);
            case "ANNUAL" -> periodEnd.withDayOfYear(1);
            default -> periodEnd;
        };
    }

    /** Period ends in [from, to] (inclusive), oldest first; none for AD_HOC / EVENT_DRIVEN. */
    public static List<LocalDate> periodEnds(String frequency, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if ("AD_HOC".equals(frequency) || "EVENT_DRIVEN".equals(frequency)) {
            return out;
        }
        LocalDate x = from;
        while (!x.isAfter(to) && out.size() < 1000) {
            if (isPeriodEnd(frequency, x)) {
                out.add(x);
            }
            x = "DAILY".equals(frequency) || "WEEKLY".equals(frequency) ? x.plusDays(1)
                    : isPeriodEnd("MONTHLY", x) ? x.plusDays(1) : x.with(TemporalAdjusters.lastDayOfMonth());
        }
        return out;
    }
}
