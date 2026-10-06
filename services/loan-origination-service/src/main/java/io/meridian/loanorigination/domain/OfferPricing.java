package io.meridian.loanorigination.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Indicative pricing of an offer: repayment schedule (ANNUITY, EQUAL_PRINCIPAL, BULLET, BALLOON, INTEREST_ONLY)
 * with periodic interest at the nominal rate / periods per year, amounts rounded to the currency's minor unit and
 * the rounding residue trued-up in the last instalment, and the APR as the effective annual IRR of the borrower's
 * cash flows (net amount received = principal - upfront fees, then the instalments on their due dates).
 */
public final class OfferPricing {
    private OfferPricing() {}

    static final MathContext MC = MathContext.DECIMAL128;

    /** Repayment frequency: periods per year and the calendar step between due dates. */
    public enum Frequency {
        DAILY(365, 0, 1), WEEKLY(52, 0, 7), FORTNIGHTLY(26, 0, 14), MONTHLY(12, 1, 0), QUARTERLY(4, 3, 0),
        SEMI_ANNUAL(2, 6, 0), ANNUAL(1, 12, 0), AT_MATURITY(0, 0, 0);

        final int perYear;
        final int months;
        final int days;

        Frequency(int perYear, int months, int days) {
            this.perYear = perYear;
            this.months = months;
            this.days = days;
        }

        public static Frequency parse(String v) {
            return v == null || v.isBlank() ? MONTHLY : valueOf(v.trim().toUpperCase(Locale.ROOT));
        }

        LocalDate step(LocalDate from, int n) {
            return months > 0 ? from.plusMonths((long) months * n) : from.plusDays((long) days * n);
        }
    }

    public enum ScheduleType { ANNUITY, EQUAL_PRINCIPAL, BULLET, BALLOON, INTEREST_ONLY;

        public static ScheduleType parse(String v) {
            if (v == null || v.isBlank()) {
                return ANNUITY;
            }
            try {
                return valueOf(v.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unsupported schedule type " + v);
            }
        }
    }

    /** Pricing inputs. {@code annualRate} is a fraction (0.12 = 12%). */
    public record Request(BigDecimal principal, BigDecimal annualRate, int termMonths, Frequency frequency,
                          ScheduleType type, BigDecimal upfrontFees, BigDecimal balloon, LocalDate startDate,
                          LocalDate firstDueDate, String currency) {}

    public record Installment(int no, LocalDate dueDate, BigDecimal principal, BigDecimal interest, BigDecimal payment,
                              BigDecimal balanceAfter) {}

    public record Quote(BigDecimal instalment, BigDecimal totalInterest, BigDecimal upfrontFees,
                        BigDecimal totalRepayable, BigDecimal apr, List<Installment> installments) {}

    /** Number of instalments for a term and frequency (at least one). */
    public static int periods(int termMonths, Frequency f, ScheduleType type) {
        if (type == ScheduleType.BULLET || f == Frequency.AT_MATURITY) {
            return 1;
        }
        if (f.months > 0) {
            return Math.max(1, termMonths / f.months);
        }
        long days = ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1).plusMonths(termMonths));
        return (int) Math.max(1, days / f.days);
    }

    public static Quote price(Request q) {
        if (q.principal() == null || q.principal().signum() <= 0) {
            throw new IllegalArgumentException("principal must be positive");
        }
        if (q.termMonths() <= 0) {
            throw new IllegalArgumentException("term must be positive");
        }
        BigDecimal rate = q.annualRate() == null ? BigDecimal.ZERO : q.annualRate();
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("rate must not be negative");
        }
        int scale = Currencies.digits(q.currency());
        BigDecimal fees = q.upfrontFees() == null ? BigDecimal.ZERO : q.upfrontFees();
        Frequency f = q.frequency() == null ? Frequency.MONTHLY : q.frequency();
        ScheduleType type = q.type() == null ? ScheduleType.ANNUITY : q.type();
        int n = periods(q.termMonths(), f, type);
        LocalDate start = q.startDate();
        List<LocalDate> dates = dueDates(start, q.firstDueDate(), f, type, n, q.termMonths());
        // periodic rate: nominal / periods per year; single-instalment schedules accrue over the whole term
        BigDecimal r = n == 1 && (type == ScheduleType.BULLET || f == Frequency.AT_MATURITY)
                ? rate.multiply(BigDecimal.valueOf(q.termMonths())).divide(BigDecimal.valueOf(12), MC)
                : rate.divide(BigDecimal.valueOf(f.perYear == 0 ? 12 : f.perYear), MC);
        BigDecimal p = q.principal();
        List<Installment> rows = new ArrayList<>();
        BigDecimal balance = p;
        BigDecimal level = switch (type) {
            case ANNUITY -> annuity(p, r, n).setScale(scale, RoundingMode.HALF_EVEN);
            case BALLOON -> {
                BigDecimal b = q.balloon() == null ? p.multiply(new BigDecimal("0.30")) : q.balloon();
                if (b.signum() < 0 || b.compareTo(p) >= 0) {
                    throw new IllegalArgumentException("balloon must be between 0 and the principal");
                }
                BigDecimal pvBalloon = b.divide(BigDecimal.ONE.add(r).pow(n, MC), MC);
                yield annuity(p.subtract(pvBalloon), r, n).setScale(scale, RoundingMode.HALF_EVEN);
            }
            case EQUAL_PRINCIPAL -> p.divide(BigDecimal.valueOf(n), MC).setScale(scale, RoundingMode.HALF_EVEN);
            default -> BigDecimal.ZERO;
        };
        for (int i = 1; i <= n; i++) {
            boolean last = i == n;
            BigDecimal interest = balance.multiply(r, MC).setScale(scale, RoundingMode.HALF_EVEN);
            BigDecimal principal = switch (type) {
                case ANNUITY, BALLOON -> last ? balance : level.subtract(interest).max(BigDecimal.ZERO).min(balance);
                case EQUAL_PRINCIPAL -> last ? balance : level.min(balance);
                case BULLET, INTEREST_ONLY -> last ? balance : BigDecimal.ZERO;
            };
            balance = balance.subtract(principal);
            rows.add(new Installment(i, dates.get(i - 1), principal, interest, principal.add(interest), balance));
        }
        BigDecimal totalInterest = rows.stream().map(Installment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        // the regular instalment (first one; EQUAL_PRINCIPAL declines from it, BALLOON/INTEREST_ONLY end higher)
        BigDecimal instalment = rows.getFirst().payment();
        BigDecimal apr = apr(p, fees, start, rows);
        return new Quote(instalment, totalInterest, fees, p.add(totalInterest).add(fees), apr, rows);
    }

    /** APR = effective annual IRR of: +(principal - fees) at start, -payment at each due date (Act/365.25 years). */
    public static BigDecimal apr(BigDecimal principal, BigDecimal fees, LocalDate start, List<Installment> rows) {
        List<Irr.Flow> flows = new ArrayList<>();
        flows.add(new Irr.Flow(0, -principal.subtract(fees).doubleValue()));
        for (Installment i : rows) {
            double years = ChronoUnit.DAYS.between(start, i.dueDate()) / 365.25;
            flows.add(new Irr.Flow(years, i.payment().doubleValue()));
        }
        return Irr.apr(flows);
    }

    /** Level payment that amortises {@code p} over {@code n} periods at periodic rate {@code r}. */
    public static BigDecimal annuity(BigDecimal p, BigDecimal r, int n) {
        if (r.signum() == 0) {
            return p.divide(BigDecimal.valueOf(n), MC);
        }
        BigDecimal factor = BigDecimal.ONE.add(r).pow(n, MC);
        return p.multiply(r, MC).multiply(factor, MC).divide(factor.subtract(BigDecimal.ONE), MC);
    }

    /** Present value of {@code n} level payments (inverse annuity): the largest principal a payment can service. */
    public static BigDecimal presentValue(BigDecimal payment, BigDecimal r, int n) {
        if (r.signum() == 0) {
            return payment.multiply(BigDecimal.valueOf(n));
        }
        BigDecimal factor = BigDecimal.ONE.add(r).pow(n, MC);
        return payment.multiply(factor.subtract(BigDecimal.ONE), MC).divide(r.multiply(factor, MC), MC);
    }

    private static List<LocalDate> dueDates(LocalDate start, LocalDate first, Frequency f, ScheduleType type, int n,
                                            int termMonths) {
        List<LocalDate> out = new ArrayList<>(n);
        if (n == 1 && (type == ScheduleType.BULLET || f == Frequency.AT_MATURITY)) {
            out.add(first != null ? first : start.plusMonths(termMonths));
            return out;
        }
        LocalDate anchor = first != null ? first : f.step(start, 1);
        for (int i = 0; i < n; i++) {
            out.add(f.step(anchor, i));
        }
        return out;
    }
}
