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
 * @param country           single-tenant fallback: the country whose legal entities may use the three numbering values
 *                          below when the tenant's numbering settings (config-service namespace account_numbering) do
 *                          not set them; never applied to a legal entity of another country (unset by default)
 * @param bankCode          fallback bank identifier inside the BBAN / national number (e.g. "MERI" for GB, BLZ for DE)
 * @param branchCode        fallback branch / sort code where the country's format uses one
 * @param accountNumberLength fallback length of a generic national account number (incl. check digit)
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
        gl = gl == null ? new Gl(null, null, null, null, null) : gl;
        withholdingTaxRate = withholdingTaxRate == null ? BigDecimal.ZERO : withholdingTaxRate;

        holidays = holidays == null ? List.of() : List.copyOf(holidays);
        dormancy = dormancy == null ? new Dormancy(null, null, null) : dormancy;
        maxPotsPerAccount = maxPotsPerAccount == null ? 20 : maxPotsPerAccount;
        batch = batch == null ? new Batch(false, List.of(), null) : batch;
    }

    /**
     * Withholding tax on credit interest when the product has none: the tenant's {@code withholding_tax_rate}
     * (deposit-service settings, e.g. Colombia retencion en la fuente 7%) wins over this deployment default.
     */
    @Override
    public BigDecimal withholdingTaxRate() {
        return io.meridian.web.config.TenantSettings.json("deposit-service", "withholding_tax_rate")
                .map(n -> {
                    try {
                        return new BigDecimal(n.asString());
                    } catch (RuntimeException e) {
                        return withholdingTaxRate;
                    }
                }).orElse(withholdingTaxRate);
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("deposit-service", "ledger_id", ledgerId);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code deposit-service}). */
    @Override
    public Gl gl() {
        return new Gl(io.meridian.web.config.TenantSettings.uuid("deposit-service", "gl.interest_expense", gl.interestExpense()),
                io.meridian.web.config.TenantSettings.uuid("deposit-service", "gl.interest_income", gl.interestIncome()),
                io.meridian.web.config.TenantSettings.uuid("deposit-service", "gl.fee_income", gl.feeIncome()),
                io.meridian.web.config.TenantSettings.uuid("deposit-service", "gl.tax_payable", gl.taxPayable()),
                io.meridian.web.config.TenantSettings.uuid("deposit-service", "gl.clearing", gl.clearing()));
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
