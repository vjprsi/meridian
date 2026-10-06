package io.meridian.deposit.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * deposit-service settings ({@code meridian.deposit.*}).
 *
 * @param ledgerId          ledger book that holds customer deposit accounts and the GL accounts below
 * @param country           booking country (ISO 3166 alpha-2) used for IBAN generation
 * @param bankCode          bank identifier inside the BBAN (e.g. "MERI" for GB, BLZ for DE)
 * @param branchCode        branch / sort code inside the BBAN where the country uses one
 * @param accountNumberLength default account number length (incl. Luhn check digit) when no IBAN format applies
 * @param gl                internal ledger accounts used as counterparties
 * @param withholdingTaxRate default withholding tax on credit interest when the product snapshot has none
 * @param holidays          public holidays for business-day adjustment (weekends are always non-business days)
 * @param dormancy          dormancy thresholds in days since last customer activity
 * @param maxPotsPerAccount limit of open savings pots per account
 * @param batch             scheduled jobs (accrual, capitalisation, maturities, standing orders, dormancy)
 */
@ConfigurationProperties(prefix = "meridian.deposit")
public record DepositProperties(UUID ledgerId, String country, String bankCode, String branchCode,
                                Integer accountNumberLength, Gl gl, BigDecimal withholdingTaxRate,
                                List<LocalDate> holidays, Dormancy dormancy, Integer maxPotsPerAccount, Batch batch) {

    public DepositProperties {
        country = country == null ? "GB" : country;
        bankCode = bankCode == null ? "MERI" : bankCode;
        branchCode = branchCode == null ? "000001" : branchCode;
        accountNumberLength = accountNumberLength == null ? 10 : accountNumberLength;
        gl = gl == null ? new Gl(null, null, null, null, null) : gl;
        withholdingTaxRate = withholdingTaxRate == null ? BigDecimal.ZERO : withholdingTaxRate;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
        dormancy = dormancy == null ? new Dormancy(null, null, null) : dormancy;
        maxPotsPerAccount = maxPotsPerAccount == null ? 20 : maxPotsPerAccount;
        batch = batch == null ? new Batch(false, List.of(), null) : batch;
    }

    /** Internal ledger accounts (ids in ledger-service). */
    public record Gl(UUID interestExpense, UUID interestIncome, UUID feeIncome, UUID taxPayable, UUID clearing) {}

    public record Dormancy(Integer warningDays, Integer inactiveDays, Integer dormantDays) {
        public Dormancy {
            warningDays = warningDays == null ? 335 : warningDays;
            inactiveDays = inactiveDays == null ? 365 : inactiveDays;
            dormantDays = dormantDays == null ? 730 : dormantDays;
        }
    }

    /** @param tenants tenants processed by the scheduled jobs; @param cron when the end-of-day jobs run */
    public record Batch(boolean enabled, List<UUID> tenants, String cron) {
        public Batch {
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
            cron = cron == null ? "0 30 0 * * *" : cron;
        }
    }
}
