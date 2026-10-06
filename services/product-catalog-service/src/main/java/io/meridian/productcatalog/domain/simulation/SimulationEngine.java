package io.meridian.productcatalog.domain.simulation;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.fees.FeeCalculator;
import io.meridian.productcatalog.domain.rates.RateCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Deterministic month-by-month projection of a product version for one synthetic customer.
 *
 * <p>DEPOSIT mode: deposits/withdrawals at the start of each month, interest accrued on the resulting balance with
 * the configuration's day count, capitalised per the capitalisation frequency (rounded with the configuration's
 * rounding rule/scale, withholding tax deducted), periodic and per-transaction fees charged at month end.
 *
 * <p>LOAN mode: annuity repayment re-computed each month on the outstanding balance and remaining term (so floating
 * rates re-price), interest by day count, upfront fees in month 1, APR from the IRR of the cash flows.
 * The same inputs always produce the same output.
 */
public final class SimulationEngine {
    private static final MathContext MC = MathContext.DECIMAL128;
    static final Set<String> UPFRONT = Set.of("ACCOUNT_OPENING", "LOAN_ORIGINATION", "CARD_ISSUANCE");
    static final Set<String> PER_TRANSACTION = Set.of("TRANSACTION", "ATM_WITHDRAWAL", "TRANSFER_OUT", "CASH_OUT",
            "CASH_IN", "INTERNATIONAL_TRANSFER", "FX_CONVERSION");
    public static final int MAX_HORIZON = 600;

    private SimulationEngine() {}

    public record PeriodicFee(String trigger, String frequency, FeeCalculator.Fee fee, FeeCalculator.Tax tax) {}

    public record Scenario(String mode, LocalDate startDate, int horizonMonths, BigDecimal openingBalance,
                           BigDecimal monthlyDeposit, BigDecimal monthlyWithdrawal, BigDecimal principal,
                           Integer termMonths, int monthlyTransactions, BigDecimal averageTransactionAmount,
                           BigDecimal withholdingTaxRate, NavigableMap<Integer, BigDecimal> indexRatePath,
                           String segment) {}

    public record Product(String currency, RateCalculator.Config rate, String capitalisationFrequency,
                          Function<LocalDate, BigDecimal> indexRateAt, List<PeriodicFee> fees) {}

    public record Result(List<Map<String, Object>> periods, Map<String, Object> summary) {}

    public static Result run(Product p, Scenario s) {
        if (s.horizonMonths() < 1 || s.horizonMonths() > MAX_HORIZON) {
            throw ApiException.unprocessable("invalid_horizon", "horizon_months must be 1.." + MAX_HORIZON);
        }
        RateCalculator.Config cfg = p.rate() == null ? null : new RateCalculator.Config(p.rate().rateType(),
                p.rate().fixedRate(), p.rate().spread(), p.rate().floorRate(), p.rate().capRate(),
                p.rate().minBalanceForInterest(), p.rate().dayCount(), p.rate().rounding(), p.rate().roundingScale(),
                s.startDate(), null, p.rate().tiers());
        return "LOAN".equals(s.mode()) ? loan(p, cfg, s) : deposit(p, cfg, s);
    }

    private static Result deposit(Product p, RateCalculator.Config cfg, Scenario s) {
        int minor = FeeCalculator.minorUnits(p.currency());
        BigDecimal balance = nz(s.openingBalance());
        if (balance.signum() < 0) {
            throw ApiException.unprocessable("invalid_scenario", "opening_balance cannot be negative");
        }
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal totInterest = BigDecimal.ZERO;
        BigDecimal totTax = BigDecimal.ZERO;
        BigDecimal totFees = BigDecimal.ZERO;
        BigDecimal totDep = BigDecimal.ZERO;
        BigDecimal totWd = BigDecimal.ZERO;
        BigDecimal firstRate = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (int m = 1; m <= s.horizonMonths(); m++) {
            LocalDate ps = s.startDate().plusMonths(m - 1);
            LocalDate pe = s.startDate().plusMonths(m);
            BigDecimal open = balance;
            BigDecimal dep = nz(s.monthlyDeposit());
            BigDecimal wd = nz(s.monthlyWithdrawal()).min(balance.add(dep));
            balance = balance.add(dep).subtract(wd);
            totDep = totDep.add(dep);
            totWd = totWd.add(wd);

            BigDecimal rate = rateAt(p, cfg, s, balance, ps, m);
            if (firstRate == null) {
                firstRate = rate;
            }
            BigDecimal interest = cfg == null ? BigDecimal.ZERO
                    : RateCalculator.accrue(balance.max(BigDecimal.ZERO), rate, cfg.dayCount(), ps, pe);
            accrued = accrued.add(interest, MC);
            BigDecimal posted = BigDecimal.ZERO.setScale(scale(cfg));
            BigDecimal tax = BigDecimal.ZERO.setScale(minor);
            if (capitalises(p.capitalisationFrequency(), m, s.horizonMonths())) {
                posted = accrued.setScale(scale(cfg), cfg == null ? RoundingMode.HALF_EVEN : cfg.rounding());
                tax = posted.multiply(nz(s.withholdingTaxRate()), MC).setScale(minor, RoundingMode.HALF_EVEN);
                balance = balance.add(posted).subtract(tax);
                accrued = BigDecimal.ZERO;
                totInterest = totInterest.add(posted);
                totTax = totTax.add(tax);
            }
            BigDecimal fees = fees(p, s, m, balance, open, occurrences, minor, null);
            balance = balance.subtract(fees);
            totFees = totFees.add(fees);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m);
            row.put("period_start", ps.toString());
            row.put("period_end", pe.minusDays(1).toString());
            row.put("opening_balance", money(open, minor));
            row.put("deposits", money(dep, minor));
            row.put("withdrawals", money(wd, minor));
            row.put("interest_rate", rate.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
            row.put("interest_accrued", interest.setScale(8, RoundingMode.HALF_EVEN));
            row.put("interest_capitalised", posted);
            row.put("tax", tax);
            row.put("fees", money(fees, minor));
            row.put("closing_balance", money(balance, minor));
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", "DEPOSIT");
        summary.put("currency", p.currency());
        summary.put("months", s.horizonMonths());
        summary.put("opening_balance", money(nz(s.openingBalance()), minor));
        summary.put("total_deposits", money(totDep, minor));
        summary.put("total_withdrawals", money(totWd, minor));
        summary.put("total_interest", totInterest);
        summary.put("total_tax", totTax);
        summary.put("total_fees", money(totFees, minor));
        summary.put("closing_balance", money(balance, minor));
        summary.put("net_return", money(totInterest.subtract(totTax).subtract(totFees), minor));
        BigDecimal r0 = firstRate == null ? BigDecimal.ZERO : firstRate;
        summary.put("nominal_rate", r0.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
        summary.put("aer", FinanceMath.aer(r0, periodsPerYear(p.capitalisationFrequency()))
                .setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
        return new Result(rows, summary);
    }

    private static Result loan(Product p, RateCalculator.Config cfg, Scenario s) {
        int minor = FeeCalculator.minorUnits(p.currency());
        BigDecimal principal = s.principal() != null ? s.principal() : nz(s.openingBalance());
        if (principal.signum() <= 0) {
            throw ApiException.unprocessable("invalid_scenario", "LOAN simulations need a positive principal");
        }
        int term = s.termMonths() == null ? s.horizonMonths() : s.termMonths();
        if (term < 1 || term > MAX_HORIZON) {
            throw ApiException.unprocessable("invalid_scenario", "term_months must be 1.." + MAX_HORIZON);
        }
        int months = Math.min(term, s.horizonMonths());
        BigDecimal balance = principal;
        BigDecimal totInterest = BigDecimal.ZERO;
        BigDecimal totFees = BigDecimal.ZERO;
        BigDecimal totPaid = BigDecimal.ZERO;
        BigDecimal upfront = BigDecimal.ZERO;
        List<BigDecimal> outflows = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        BigDecimal firstRate = null;
        for (int m = 1; m <= months; m++) {
            LocalDate ps = s.startDate().plusMonths(m - 1);
            LocalDate pe = s.startDate().plusMonths(m);
            BigDecimal open = balance;
            BigDecimal rate = rateAt(p, cfg, s, balance, ps, m);
            if (firstRate == null) {
                firstRate = rate;
            }
            int remaining = term - m + 1;
            BigDecimal payment = FinanceMath.annuityPayment(balance, rate.divide(BigDecimal.valueOf(12), MC), remaining)
                    .setScale(minor, RoundingMode.HALF_EVEN);
            BigDecimal interest = cfg == null ? BigDecimal.ZERO.setScale(minor)
                    : RateCalculator.accrue(balance, rate, cfg.dayCount(), ps, pe).setScale(minor, cfg.rounding());
            BigDecimal principalPaid = payment.subtract(interest);
            if (m == term || principalPaid.compareTo(balance) > 0) {
                principalPaid = balance;
                payment = principalPaid.add(interest);
            }
            if (principalPaid.signum() < 0) {
                principalPaid = BigDecimal.ZERO.setScale(minor);
                payment = interest;
            }
            balance = balance.subtract(principalPaid);
            BigDecimal recurring = fees(p, s, m, balance, open, occurrences, minor, principal);
            if (m == 1) {
                upfront = upfrontFees(p, s, principal, minor);
            }
            BigDecimal fees = m == 1 ? recurring.add(upfront) : recurring;
            totInterest = totInterest.add(interest);
            totFees = totFees.add(fees);
            totPaid = totPaid.add(payment).add(fees);
            outflows.add(payment.add(recurring));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", m);
            row.put("period_start", ps.toString());
            row.put("period_end", pe.minusDays(1).toString());
            row.put("opening_balance", money(open, minor));
            row.put("interest_rate", rate.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
            row.put("payment", payment);
            row.put("interest", interest);
            row.put("principal", principalPaid);
            row.put("fees", money(fees, minor));
            row.put("closing_balance", money(balance, minor));
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", "LOAN");
        summary.put("currency", p.currency());
        summary.put("months", months);
        summary.put("principal", money(principal, minor));
        summary.put("term_months", term);
        summary.put("total_interest", totInterest);
        summary.put("total_fees", money(totFees, minor));
        summary.put("total_paid", money(totPaid, minor));
        summary.put("outstanding_balance", money(balance, minor));
        BigDecimal r0 = firstRate == null ? BigDecimal.ZERO : firstRate;
        summary.put("nominal_rate", r0.setScale(RateCalculator.RATE_SCALE, RoundingMode.HALF_EVEN));
        if (balance.signum() == 0) {
            BigDecimal apr = FinanceMath.monthlyApr(principal.subtract(upfront), outflows);
            if (apr != null) {
                summary.put("apr", apr);
            }
        }
        return new Result(rows, summary);
    }

    private static BigDecimal rateAt(Product p, RateCalculator.Config cfg, Scenario s, BigDecimal balance, LocalDate at, int month) {
        if (cfg == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal index = null;
        if (cfg.isFloating()) {
            Map.Entry<Integer, BigDecimal> path = s.indexRatePath() == null ? null : s.indexRatePath().floorEntry(month);
            index = path != null ? path.getValue() : (p.indexRateAt() == null ? null : p.indexRateAt().apply(at));
        }
        return RateCalculator.compute(cfg, balance, at, index).effectiveRate();
    }

    private static BigDecimal fees(Product p, Scenario s, int m, BigDecimal balance, BigDecimal open,
                                   Map<String, Integer> occurrences, int minor, BigDecimal loanPrincipal) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDate at = s.startDate().plusMonths(m - 1);
        BigDecimal avg = balance.add(open).divide(BigDecimal.TWO, MC);
        for (PeriodicFee pf : p.fees()) {
            String trig = pf.trigger();
            String freq = pf.frequency();
            List<BigDecimal> bases = new ArrayList<>();
            if (UPFRONT.contains(trig)) {
                if (m == 1 && loanPrincipal == null) {
                    bases.add(nz(s.openingBalance()));
                }
            } else if (PER_TRANSACTION.contains(trig)) {
                for (int k = 0; k < s.monthlyTransactions(); k++) {
                    bases.add(nz(s.averageTransactionAmount()));
                }
            } else if (due(trig, freq, m)) {
                bases.add(balance.max(BigDecimal.ZERO));
            }
            BigDecimal chargedThisPeriod = BigDecimal.ZERO;
            for (BigDecimal base : bases) {
                int occ = occurrences.merge(pf.fee().code() + "@" + m, 1, Integer::sum);
                FeeCalculator.Context ctx = new FeeCalculator.Context(at, occ, balance, avg, null, s.monthlyTransactions(),
                        s.segment(), null, null, null, chargedThisPeriod);
                FeeCalculator.Fee undated = new FeeCalculator.Fee(pf.fee().id(), pf.fee().code(), pf.fee().calculationMethod(),
                        pf.fee().flatAmount(), pf.fee().percentage(), pf.fee().minAmount(), pf.fee().maxAmount(),
                        pf.fee().capPerPeriod(), pf.fee().freeCount(), pf.fee().currency(), null, null,
                        pf.fee().tiers(), pf.fee().waivers());
                FeeCalculator.Result r = FeeCalculator.calculate(undated, base, ctx, pf.tax());
                chargedThisPeriod = chargedThisPeriod.add(r.netAmount());
                total = total.add(r.totalAmount());
            }
        }
        return total.setScale(minor, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal upfrontFees(Product p, Scenario s, BigDecimal principal, int minor) {
        BigDecimal total = BigDecimal.ZERO;
        for (PeriodicFee pf : p.fees()) {
            if (UPFRONT.contains(pf.trigger())) {
                FeeCalculator.Fee undated = new FeeCalculator.Fee(pf.fee().id(), pf.fee().code(), pf.fee().calculationMethod(),
                        pf.fee().flatAmount(), pf.fee().percentage(), pf.fee().minAmount(), pf.fee().maxAmount(),
                        pf.fee().capPerPeriod(), pf.fee().freeCount(), pf.fee().currency(), null, null,
                        pf.fee().tiers(), pf.fee().waivers());
                total = total.add(FeeCalculator.calculate(undated, principal,
                        new FeeCalculator.Context(s.startDate(), 1, principal, principal, null, null, s.segment(),
                                null, null, null, BigDecimal.ZERO), pf.tax()).totalAmount());
            }
        }
        return total.setScale(minor, RoundingMode.HALF_EVEN);
    }

    /** Whether a periodic fee falls due in month {@code m} (1-based). */
    static boolean due(String trigger, String frequency, int m) {
        String f = frequency != null ? frequency : switch (trigger) {
            case "MONTHLY_MAINTENANCE", "BELOW_MIN_BALANCE", "DORMANCY" -> "MONTHLY";
            case "ANNUAL" -> "ANNUAL";
            default -> "NONE";
        };
        return switch (f) {
            case "DAILY", "WEEKLY", "MONTHLY" -> true;
            case "QUARTERLY" -> (m - 1) % 3 == 0;
            case "SEMI_ANNUAL" -> (m - 1) % 6 == 0;
            case "ANNUAL" -> (m - 1) % 12 == 0;
            default -> false;
        };
    }

    /** Whether accrued interest is capitalised at the end of month {@code m}. */
    static boolean capitalises(String frequency, int m, int horizon) {
        String f = frequency == null ? "MONTHLY" : frequency;
        return switch (f) {
            case "DAILY", "WEEKLY", "MONTHLY" -> true;
            case "QUARTERLY" -> m % 3 == 0 || m == horizon;
            case "SEMI_ANNUAL" -> m % 6 == 0 || m == horizon;
            case "ANNUAL" -> m % 12 == 0 || m == horizon;
            default -> m == horizon;
        };
    }

    static int periodsPerYear(String frequency) {
        return switch (frequency == null ? "MONTHLY" : frequency) {
            case "DAILY" -> 365;
            case "WEEKLY" -> 52;
            case "MONTHLY" -> 12;
            case "QUARTERLY" -> 4;
            case "SEMI_ANNUAL" -> 2;
            case "ANNUAL" -> 1;
            default -> 1;
        };
    }

    private static int scale(RateCalculator.Config cfg) {
        return cfg == null ? 2 : cfg.roundingScale();
    }

    private static BigDecimal money(BigDecimal v, int minor) {
        return v.setScale(minor, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Builds an index path from a list of (month, rate) points. */
    public static NavigableMap<Integer, BigDecimal> path(Map<Integer, BigDecimal> points) {
        return new TreeMap<>(points);
    }
}
