package io.meridian.loanorigination.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Affordability policy: normalises declared and verified income/expenses to monthly amounts, takes the higher of
 * declared and bureau-reported debt service, applies a statistical living-cost floor, and tests the requested loan
 * at a stressed rate (nominal + buffer):
 * <ul>
 *   <li>DSR = (commitments + stressed instalment) / monthly income</li>
 *   <li>DTI = (reported total debt + requested amount) / annual income</li>
 *   <li>residual income = income - commitments - living costs - stressed instalment</li>
 *   <li>max affordable amount = present value at the stressed rate of the payment capacity
 *       min(income * max DSR - commitments, income - commitments - living costs)</li>
 * </ul>
 * FAIL when there is no income, DSR or DTI exceed their maximum or residual income is negative; MARGINAL when DSR or
 * DTI are above their warning levels or residual income is below the buffer; otherwise PASS.
 */
public final class AffordabilityCalculator {
    private AffordabilityCalculator() {}

    static final MathContext MC = MathContext.DECIMAL128;
    public static final Set<String> INCOME_TYPES = Set.of("SALARY", "BUSINESS_INCOME", "RENTAL_INCOME", "PENSION", "OTHER_INCOME");
    static final Set<String> HOUSING = Set.of("RENT", "MORTGAGE");
    static final Set<String> LIVING = Set.of("LIVING_COSTS", "EDUCATION", "OTHER_EXPENSE");

    public record Policy(String version, BigDecimal unverifiedIncomeHaircut, BigDecimal stressBuffer,
                         BigDecimal maxDsr, BigDecimal warnDsr, BigDecimal maxDti, BigDecimal warnDti,
                         BigDecimal livingFloorBase, BigDecimal livingFloorPerDependant,
                         BigDecimal residualBufferPct) {
        public static Policy standard() {
            return new Policy("AFF-2026.1", new BigDecimal("0.20"), new BigDecimal("0.03"), new BigDecimal("0.45"),
                    new BigDecimal("0.40"), new BigDecimal("4.5"), new BigDecimal("3.5"), new BigDecimal("400"),
                    new BigDecimal("150"), new BigDecimal("0.10"));
        }
    }

    /** A financial line: type (FinancialItemType), amount per frequency, frequency, verified. */
    public record Item(String type, BigDecimal amount, String frequency, boolean verified) {}

    public record Input(List<Item> items, BigDecimal declaredMonthlyNetIncome, int dependants,
                        BigDecimal bureauMonthlyDebtService, BigDecimal bureauTotalDebt, BigDecimal requestedAmount,
                        int termMonths, BigDecimal annualRate, String currency) {}

    public record Result(BigDecimal monthlyIncome, BigDecimal commitments, BigDecimal livingCosts,
                         BigDecimal instalment, BigDecimal stressedInstalment, BigDecimal stressRate, BigDecimal dti,
                         BigDecimal dsr, BigDecimal residualIncome, BigDecimal maxAffordableAmount, String result,
                         List<String> reasonCodes, String policyVersion) {}

    public static BigDecimal monthly(BigDecimal amount, String frequency) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return switch (frequency == null ? "MONTHLY" : frequency) {
            case "WEEKLY" -> amount.multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), MC);
            case "FORTNIGHTLY" -> amount.multiply(BigDecimal.valueOf(26)).divide(BigDecimal.valueOf(12), MC);
            case "QUARTERLY" -> amount.divide(BigDecimal.valueOf(3), MC);
            case "ANNUAL" -> amount.divide(BigDecimal.valueOf(12), MC);
            case "ONE_OFF" -> BigDecimal.ZERO; // not recurring: excluded from monthly affordability
            default -> amount;
        };
    }

    public static Result assess(Input in, Policy p) {
        BigDecimal keep = BigDecimal.ONE.subtract(p.unverifiedIncomeHaircut());
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal housing = BigDecimal.ZERO;
        BigDecimal declaredDebt = BigDecimal.ZERO;
        BigDecimal living = BigDecimal.ZERO;
        boolean anyIncomeItem = false;
        for (Item i : in.items()) {
            BigDecimal m = monthly(i.amount(), i.frequency());
            if (INCOME_TYPES.contains(i.type())) {
                anyIncomeItem = true;
                income = income.add(i.verified() ? m : m.multiply(keep, MC));
            } else if (HOUSING.contains(i.type())) {
                housing = housing.add(m);
            } else if ("LOAN_REPAYMENT".equals(i.type())) {
                declaredDebt = declaredDebt.add(m);
            } else if (LIVING.contains(i.type())) {
                living = living.add(m);
            }
        }
        if (!anyIncomeItem && in.declaredMonthlyNetIncome() != null) {
            income = in.declaredMonthlyNetIncome().multiply(keep, MC);
        }
        BigDecimal bureauDebt = in.bureauMonthlyDebtService() == null ? BigDecimal.ZERO : in.bureauMonthlyDebtService();
        BigDecimal commitments = housing.add(declaredDebt.max(bureauDebt));
        BigDecimal floor = p.livingFloorBase().add(p.livingFloorPerDependant().multiply(BigDecimal.valueOf(Math.max(0, in.dependants()))));
        living = living.max(floor);

        int n = Math.max(1, in.termMonths());
        BigDecimal rate = in.annualRate() == null ? BigDecimal.ZERO : in.annualRate();
        BigDecimal stressRate = rate.add(p.stressBuffer());
        BigDecimal instalment = OfferPricing.annuity(in.requestedAmount(), rate.divide(BigDecimal.valueOf(12), MC), n);
        BigDecimal stressed = OfferPricing.annuity(in.requestedAmount(), stressRate.divide(BigDecimal.valueOf(12), MC), n);

        List<String> reasons = new ArrayList<>();
        BigDecimal dsr;
        BigDecimal dti;
        BigDecimal residual = income.subtract(commitments).subtract(living).subtract(stressed);
        BigDecimal maxAffordable;
        String result;
        if (income.signum() <= 0) {
            dsr = BigDecimal.ONE.scaleByPowerOfTen(3);
            dti = BigDecimal.ONE.scaleByPowerOfTen(3);
            maxAffordable = BigDecimal.ZERO;
            reasons.add("NO_VERIFIABLE_INCOME");
            result = "FAIL";
        } else {
            dsr = commitments.add(stressed).divide(income, MC);
            BigDecimal totalDebt = in.bureauTotalDebt() == null ? BigDecimal.ZERO : in.bureauTotalDebt();
            dti = totalDebt.add(in.requestedAmount()).divide(income.multiply(BigDecimal.valueOf(12)), MC);
            BigDecimal capacity = income.multiply(p.maxDsr(), MC).subtract(commitments)
                    .min(income.subtract(commitments).subtract(living));
            maxAffordable = capacity.signum() <= 0 ? BigDecimal.ZERO
                    : OfferPricing.presentValue(capacity, stressRate.divide(BigDecimal.valueOf(12), MC), n);
            boolean fail = false;
            boolean warn = false;
            if (dsr.compareTo(p.maxDsr()) > 0) {
                reasons.add("DSR_ABOVE_MAX");
                fail = true;
            } else if (dsr.compareTo(p.warnDsr()) > 0) {
                reasons.add("DSR_NEAR_MAX");
                warn = true;
            }
            if (residual.signum() < 0) {
                reasons.add("NEGATIVE_RESIDUAL_INCOME");
                fail = true;
            } else if (residual.compareTo(income.multiply(p.residualBufferPct(), MC)) < 0) {
                reasons.add("LOW_RESIDUAL_INCOME");
                warn = true;
            }
            if (dti.compareTo(p.maxDti()) > 0) {
                reasons.add("DTI_ABOVE_MAX");
                fail = true;
            } else if (dti.compareTo(p.warnDti()) > 0) {
                reasons.add("DTI_NEAR_MAX");
                warn = true;
            }
            result = fail ? "FAIL" : warn ? "MARGINAL" : "PASS";
        }
        int s = Currencies.digits(in.currency());
        return new Result(money(income, s), money(commitments, s), money(living, s), money(instalment, s),
                money(stressed, s), stressRate, ratio(dti), ratio(dsr), money(residual, s),
                maxAffordable.setScale(s, RoundingMode.DOWN), result, List.copyOf(reasons), p.version());
    }

    private static BigDecimal money(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
