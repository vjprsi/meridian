package io.meridian.statement.domain;

import io.meridian.common.error.ApiException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Statement cycles and status rules. A schedule's cycle ends on {@code cycle_day}: the day of month (clamped to
 * month end) for monthly and longer cycles - in the last month of each quarter / half-year / year for QUARTERLY /
 * SEMI_ANNUAL / ANNUAL - or the ISO weekday (1 = Monday) for WEEKLY and BIWEEKLY (even ISO weeks).
 */
public final class StatementPeriods {
    public static final Set<String> FORMATS = Set.of("PDF", "CSV", "CAMT_053", "MT940", "OFX", "JSON");

    public record Period(LocalDate start, LocalDate end) {}

    private StatementPeriods() {}

    /** The cycle ending on {@code date}, if {@code date} is a cycle end for this frequency. */
    public static Optional<Period> cycleEndingOn(String frequency, int cycleDay, LocalDate date) {
        String f = frequency.toUpperCase(Locale.ROOT);
        return switch (f) {
            case "DAILY" -> Optional.of(new Period(date, date));
            case "WEEKLY", "BIWEEKLY" -> {
                int weekday = Math.max(1, Math.min(7, cycleDay));
                boolean weekdayMatch = date.getDayOfWeek() == DayOfWeek.of(weekday);
                boolean weekMatch = "WEEKLY".equals(f) || date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0;
                yield weekdayMatch && weekMatch
                        ? Optional.of(new Period(date.minusDays("WEEKLY".equals(f) ? 6 : 13), date)) : Optional.empty();
            }
            case "MONTHLY", "QUARTERLY", "SEMI_ANNUAL", "ANNUAL" -> {
                int months = switch (f) {
                    case "MONTHLY" -> 1;
                    case "QUARTERLY" -> 3;
                    case "SEMI_ANNUAL" -> 6;
                    default -> 12;
                };
                if (date.getMonthValue() % months != 0 || !date.equals(cycleEnd(YearMonth.from(date), cycleDay))) {
                    yield Optional.empty();
                }
                LocalDate previousEnd = cycleEnd(YearMonth.from(date).minusMonths(months), cycleDay);
                yield Optional.of(new Period(previousEnd.plusDays(1), date));
            }
            default -> Optional.empty(); // ONCE: on demand only
        };
    }

    static LocalDate cycleEnd(YearMonth month, int cycleDay) {
        return month.atDay(Math.max(1, Math.min(cycleDay, month.lengthOfMonth())));
    }

    public static void validatePeriod(LocalDate start, LocalDate end, LocalDate today) {
        if (end.isBefore(start)) {
            throw ApiException.unprocessable("invalid_period", "period_end must not be before period_start");
        }
        if (!end.isBefore(today)) {
            throw ApiException.unprocessable("period_not_closed", "Statements cover closed periods; period_end must be before today");
        }
        if (start.plusYears(1).isBefore(end)) {
            throw ApiException.unprocessable("period_too_long", "A statement covers at most one year");
        }
    }

    /** Statement status machine: PENDING -> GENERATING -> READY -> DELIVERED; GENERATING -> FAILED -> GENERATING. */
    public static void requireTransition(String from, String to) {
        boolean ok = switch (from) {
            case "PENDING" -> to.equals("GENERATING");
            case "GENERATING" -> to.equals("READY") || to.equals("FAILED");
            case "FAILED" -> to.equals("GENERATING");
            case "READY", "DELIVERED" -> to.equals("DELIVERED");
            default -> false;
        };
        if (!ok) {
            throw ApiException.conflict("invalid_transition", "A statement in status " + from + " cannot move to " + to);
        }
    }

    /** ISO 20022 bank transaction code for a ledger entry type and direction. */
    public static String transactionCode(String entryType, String direction) {
        boolean credit = "CREDIT".equals(direction);
        if (entryType == null) {
            return credit ? "PMNT-RCDT-OTHR" : "PMNT-ICDT-OTHR";
        }
        return switch (entryType) {
            case "INTEREST" -> "ACMT-MDOP-INTR";
            case "FEE" -> "ACMT-MDOP-CHRG";
            case "REVERSAL", "CORRECTION", "ADJUSTMENT" -> "ACMT-MDOP-ADJT";
            case "TRANSFER" -> credit ? "PMNT-RCDT-BOOK" : "PMNT-ICDT-BOOK";
            case "CARD_SETTLEMENT" -> "PMNT-CCRD-POSD";
            case "FX" -> "FORX-SPOT-OTHR";
            default -> credit ? "PMNT-RCDT-OTHR" : "PMNT-ICDT-OTHR";
        };
    }
}
