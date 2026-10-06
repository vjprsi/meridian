package io.meridian.loan.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Repayment schedule generator (pure). Supports ANNUITY, EQUAL_PRINCIPAL, BULLET, BALLOON, INTEREST_ONLY and CUSTOM.
 *
 * <ul>
 *   <li>Due dates: nominal dates every period from the first due date (month-based dates computed from the anchor,
 *       no end-of-month drift), adjusted to business days by the loan's convention. Interest periods run between
 *       adjusted dates.</li>
 *   <li>Interest per instalment: outstanding balance x annual rate x day-count year fraction of the period.</li>
 *   <li>Instalment levels (annuity, balloon, amortising part of interest-only) use the periodic rate
 *       {@code rate / periods per year}.</li>
 *   <li>Every amount is rounded to the currency minor unit (HALF_EVEN); the last instalment takes the remaining
 *       balance, so the principal always sums to the loan amount and the final balance is exactly zero.</li>
 * </ul>
 */
public final class ScheduleGenerator {
    static final MathContext MC = MathContext.DECIMAL128;

    public enum Type {
        ANNUITY, EQUAL_PRINCIPAL, BULLET, BALLOON, INTEREST_ONLY, CUSTOM;

        public static Type parse(String v) {
            return v == null || v.isBlank() ? ANNUITY : valueOf(v.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** A CUSTOM schedule row: principal due on a date (interest is computed). */
    public record CustomLine(LocalDate dueDate, BigDecimal principal) {}

    /**
     * Generator input. {@code periods} is the number of instalments (ignored for BULLET = 1 and CUSTOM = rows).
     * {@code firstDue} is the nominal first due date (default: one period after start).
     */
    public record Terms(BigDecimal principal, BigDecimal annualRate, DayCount dayCount, Type type, Frequency frequency,
                        int periods, LocalDate start, LocalDate firstDue, BigDecimal balloon, Integer interestOnlyPeriods,
                        List<CustomLine> custom, int scale, BusinessCalendar calendar,
                        BusinessCalendar.Convention convention) {
        public Terms {
            if (principal == null || principal.signum() <= 0) {
                throw new IllegalArgumentException("principal must be positive");
            }
            annualRate = annualRate == null ? BigDecimal.ZERO : annualRate;
            if (annualRate.signum() < 0) {
                throw new IllegalArgumentException("rate must not be negative");
            }
            dayCount = dayCount == null ? DayCount.ACT_365 : dayCount;
            type = type == null ? Type.ANNUITY : type;
            frequency = frequency == null ? Frequency.MONTHLY : frequency;
            calendar = calendar == null ? BusinessCalendar.weekendsOnly() : calendar;
            convention = convention == null ? BusinessCalendar.Convention.MODIFIED_FOLLOWING : convention;
            if (start == null) {
                throw new IllegalArgumentException("start date is required");
            }
            if (type != Type.CUSTOM && periods < 1) {
                throw new IllegalArgumentException("at least one instalment is required");
            }
            if (firstDue != null && !firstDue.isAfter(start)) {
                throw new IllegalArgumentException("first due date must be after the start date");
            }
            if (type == Type.BALLOON && (balloon == null || balloon.signum() <= 0 || balloon.compareTo(principal) >= 0)) {
                throw new IllegalArgumentException("BALLOON needs 0 < balloon_amount < principal");
            }
            if (type == Type.CUSTOM && (custom == null || custom.isEmpty())) {
                throw new IllegalArgumentException("CUSTOM needs custom_schedule rows");
            }
            if (custom != null) {
                List<CustomLine> sorted = new ArrayList<>(custom);
                sorted.sort(Comparator.comparing(CustomLine::dueDate, Comparator.nullsFirst(Comparator.naturalOrder())));
                custom = List.copyOf(sorted);
            }
        }
    }

    /** One generated instalment. {@code total = principal + interest}. */
    public record Line(int no, LocalDate periodStart, LocalDate dueDate, BigDecimal principal, BigDecimal interest,
                       BigDecimal total, BigDecimal balanceAfter) {}

    public record Schedule(List<Line> lines, BigDecimal totalPrincipal, BigDecimal totalInterest,
                           BigDecimal regularInstalment) {
        public LocalDate maturity() {
            return lines.getLast().dueDate();
        }

        public BigDecimal totalRepayable() {
            return totalPrincipal.add(totalInterest);
        }
    }

    private ScheduleGenerator() {}

    public static Schedule generate(Terms t) {
        List<LocalDate> dates = dueDates(t);
        int n = dates.size();
        BigDecimal r = t.annualRate().divide(BigDecimal.valueOf(t.frequency().periodsPerYear()), MC);
        int io = t.type() == Type.INTEREST_ONLY
                ? (t.interestOnlyPeriods() == null ? n - 1 : Math.min(Math.max(t.interestOnlyPeriods(), 0), n - 1))
                : 0;
        BigDecimal level = switch (t.type()) {
            case ANNUITY -> round(annuity(t.principal(), r, n), t.scale());
            case BALLOON -> round(balloonInstalment(t.principal(), t.balloon(), r, n), t.scale());
            case INTEREST_ONLY -> round(annuity(t.principal(), r, n - io), t.scale());
            case EQUAL_PRINCIPAL -> round(t.principal().divide(BigDecimal.valueOf(n), MC), t.scale());
            case BULLET, CUSTOM -> BigDecimal.ZERO;
        };
        List<Line> lines = new ArrayList<>(n);
        BigDecimal balance = t.principal();
        LocalDate periodStart = t.start();
        BigDecimal totalInterest = BigDecimal.ZERO;
        for (int i = 1; i <= n; i++) {
            LocalDate due = dates.get(i - 1);
            BigDecimal interest = t.annualRate().signum() == 0 ? zero(t.scale())
                    : round(balance.multiply(t.annualRate(), MC).multiply(t.dayCount().yearFraction(periodStart, due), MC),
                    t.scale());
            BigDecimal principal;
            if (i == n) {
                principal = balance;
            } else {
                principal = switch (t.type()) {
                    case ANNUITY, BALLOON -> level.subtract(interest);
                    case INTEREST_ONLY -> i <= io ? BigDecimal.ZERO : level.subtract(interest);
                    case EQUAL_PRINCIPAL -> level;
                    case BULLET -> BigDecimal.ZERO;
                    case CUSTOM -> round(t.custom().get(i - 1).principal(), t.scale());
                };
                principal = principal.max(BigDecimal.ZERO).min(balance);
            }
            principal = principal.setScale(t.scale(), RoundingMode.HALF_EVEN);
            balance = balance.subtract(principal).setScale(t.scale(), RoundingMode.HALF_EVEN);
            totalInterest = totalInterest.add(interest);
            lines.add(new Line(i, periodStart, due, principal, interest, principal.add(interest), balance));
            periodStart = due;
        }
        BigDecimal regular = switch (t.type()) {
            case ANNUITY, BALLOON -> level;
            case INTEREST_ONLY -> n > 1 ? lines.getFirst().total() : lines.getLast().total();
            default -> lines.getFirst().total();
        };
        return new Schedule(List.copyOf(lines), t.principal().setScale(t.scale(), RoundingMode.HALF_EVEN),
                totalInterest.setScale(t.scale(), RoundingMode.HALF_EVEN), regular);
    }

    /** Adjusted due dates (strictly increasing, all after the start date). */
    static List<LocalDate> dueDates(Terms t) {
        List<LocalDate> out = new ArrayList<>();
        if (t.type() == Type.CUSTOM) {
            List<CustomLine> rows = new ArrayList<>(t.custom());
            rows.sort(Comparator.comparing(CustomLine::dueDate));
            BigDecimal sum = BigDecimal.ZERO;
            for (CustomLine c : rows) {
                if (c.dueDate() == null || c.principal() == null || c.principal().signum() < 0) {
                    throw new IllegalArgumentException("custom rows need a due_date and a non-negative principal");
                }
                sum = sum.add(c.principal());
                out.add(t.calendar().adjust(c.dueDate(), t.convention()));
            }
            if (sum.compareTo(t.principal()) != 0) {
                throw new IllegalArgumentException("custom_schedule principal " + sum.toPlainString()
                        + " does not add up to the principal " + t.principal().toPlainString());
            }
        } else {
            LocalDate first = t.firstDue() != null ? t.firstDue() : t.frequency().next(t.start(), t.periods());
            int n = t.type() == Type.BULLET ? 1 : t.periods();
            if (t.type() == Type.BULLET) {
                LocalDate maturity = t.frequency() == Frequency.AT_MATURITY ? first : t.frequency().nth(first, t.periods() - 1);
                out.add(t.calendar().adjust(maturity, t.convention()));
            } else {
                for (int k = 0; k < n; k++) {
                    out.add(t.calendar().adjust(t.frequency().nth(first, k), t.convention()));
                }
            }
        }
        LocalDate prev = t.start();
        for (LocalDate d : out) {
            if (!d.isAfter(prev)) {
                throw new IllegalArgumentException("due dates must be strictly increasing and after the start date ("
                        + d + " is not after " + prev + ")");
            }
            prev = d;
        }
        return out;
    }

    /** Level instalment repaying {@code p} over {@code n} periods at periodic rate {@code r}. */
    public static BigDecimal annuity(BigDecimal p, BigDecimal r, int n) {
        if (n <= 0) {
            return p;
        }
        if (r.signum() == 0) {
            return p.divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal disc = BigDecimal.ONE.add(r, MC).pow(-n, MC);
        return p.multiply(r, MC).divide(BigDecimal.ONE.subtract(disc, MC), MC);
    }

    /** Principal repaid by a level instalment {@code a} over {@code n} periods at periodic rate {@code r} (inverse annuity). */
    public static BigDecimal presentValue(BigDecimal a, BigDecimal r, int n) {
        if (r.signum() == 0) {
            return a.multiply(BigDecimal.valueOf(n), MC);
        }
        BigDecimal disc = BigDecimal.ONE.add(r, MC).pow(-n, MC);
        return a.multiply(BigDecimal.ONE.subtract(disc, MC), MC).divide(r, MC);
    }

    /** Number of level instalments {@code a} needed to repay {@code p} at periodic rate {@code r} (rounded up). */
    public static int periodsFor(BigDecimal p, BigDecimal a, BigDecimal r) {
        if (a.signum() <= 0) {
            throw new IllegalArgumentException("instalment must be positive");
        }
        if (r.signum() == 0) {
            return Math.max(1, p.divide(a, 0, RoundingMode.CEILING).intValue());
        }
        double x = 1 - r.doubleValue() * p.doubleValue() / a.doubleValue();
        if (x <= 0) {
            throw new IllegalArgumentException("instalment does not cover the periodic interest");
        }
        return Math.max(1, (int) Math.ceil(-Math.log(x) / Math.log(1 + r.doubleValue()) - 1e-9));
    }

    private static BigDecimal balloonInstalment(BigDecimal p, BigDecimal balloon, BigDecimal r, int n) {
        if (r.signum() == 0) {
            return p.subtract(balloon).divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal disc = BigDecimal.ONE.add(r, MC).pow(-n, MC);
        return p.subtract(balloon.multiply(disc, MC), MC).multiply(r, MC).divide(BigDecimal.ONE.subtract(disc, MC), MC);
    }

    private static BigDecimal round(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_EVEN);
    }
}
