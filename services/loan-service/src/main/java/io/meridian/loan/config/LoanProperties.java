package io.meridian.loan.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * loan-service settings ({@code meridian.loan.*}).
 *
 * @param ledgerId          ledger book holding loan receivable accounts and the GL accounts below
 * @param gl                internal ledger accounts used as counterparties
 * @param holidays          public holidays for due-date adjustment (weekends are always non-business days)
 * @param coolingOffDays    default cooling-off period after the first disbursement
 * @param quoteValidityHours prepayment quote validity (database clock)
 * @param limitIncreaseApprovalPct credit-limit increases above this fraction of the current limit need an approval
 * @param bnpl              BNPL eligibility policy
 * @param batch             in-process fallback scheduler of the end-of-day jobs
 */
@ConfigurationProperties(prefix = "meridian.loan")
public record LoanProperties(UUID ledgerId, Gl gl, List<LocalDate> holidays, Integer coolingOffDays,
                             Integer quoteValidityHours, BigDecimal limitIncreaseApprovalPct, Bnpl bnpl, Batch batch) {

    public LoanProperties {
        gl = gl == null ? new Gl(null, null, null, null, null, null, null, null, null, null, null, null, null, null) : gl;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
        coolingOffDays = coolingOffDays == null ? 14 : coolingOffDays;
        quoteValidityHours = quoteValidityHours == null ? 24 : quoteValidityHours;
        limitIncreaseApprovalPct = limitIncreaseApprovalPct == null ? new BigDecimal("0.25") : limitIncreaseApprovalPct;
        bnpl = bnpl == null ? new Bnpl(null, null, null, null, null) : bnpl;
        batch = batch == null ? new Batch(false, List.of(), null) : batch;
    }

    /**
     * Internal ledger accounts (ids in ledger-service). {@code clearing} receives funds paid out to wallets, cards
     * and external transfers and funds received from outside the bank (cash, transfers, collateral sales).
     */
    public record Gl(UUID interestReceivable, UUID interestIncome, UUID interestSuspense, UUID feeReceivable,
                     UUID feeIncome, UUID penaltyReceivable, UUID penaltyIncome, UUID writeOffExpense,
                     UUID recoveryIncome, UUID agencyCommission, UUID clearing, UUID overpayment, UUID taxPayable,
                     UUID waiverExpense) {

        public UUID penaltyReceivableOrInterest() {
            return penaltyReceivable != null ? penaltyReceivable : interestReceivable;
        }

        public UUID penaltyIncomeOrInterest() {
            return penaltyIncome != null ? penaltyIncome : interestIncome;
        }

        public UUID suspenseOrIncome() {
            return interestSuspense != null ? interestSuspense : interestIncome;
        }

        public UUID overpaymentOrClearing() {
            return overpayment != null ? overpayment : clearing;
        }

        public UUID waiverOrWriteOff() {
            return waiverExpense != null ? waiverExpense : writeOffExpense;
        }
    }

    /** @param maxExposure total open BNPL principal per customer; min/max purchase; max instalments */
    public record Bnpl(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal maxExposure, Integer maxInstallments,
                       Integer defaultInstallments) {
        public Bnpl {
            minAmount = minAmount == null ? new BigDecimal("20") : minAmount;
            maxAmount = maxAmount == null ? new BigDecimal("3000") : maxAmount;
            maxExposure = maxExposure == null ? new BigDecimal("5000") : maxExposure;
            maxInstallments = maxInstallments == null ? 12 : maxInstallments;
            defaultInstallments = defaultInstallments == null ? 4 : defaultInstallments;
        }
    }

    public record Batch(boolean enabled, List<UUID> tenants, String cron) {
        public Batch {
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
            cron = cron == null ? "0 45 0 * * *" : cron;
        }
    }
}
