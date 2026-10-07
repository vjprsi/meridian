package io.meridian.cardclearing.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-clearing-service settings ({@code meridian.cards.*}).
 *
 * @param ledgerId ledger book of the journal entries posted by clearing
 * @param clearing internal accounts, tolerances, fees and interchange rates
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record ClearingProperties(UUID ledgerId, Clearing clearing) {

    public ClearingProperties {
        clearing = clearing == null ? new Clearing(null, null, null, null, null, null, null, null) : clearing;
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "ledger_id", ledgerId);
    }

    /**
     * Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code card-clearing-service}:
     * {@code clearing.settlement_accounts.<SCHEME>}, {@code clearing.fee_income_account}, ...).
     */
    @Override
    public Clearing clearing() {
        Map<String, UUID> settlement = new HashMap<>(clearing.settlementAccounts());
        for (String scheme : java.util.List.of("VISA", "MASTERCARD", "AMEX", "UNIONPAY", "JCB", "DISCOVER", "RUPAY", "ELO", "LOCAL")) {
            UUID tenant = io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "clearing.settlement_accounts." + scheme, null);
            if (tenant != null) {
                settlement.put(scheme, tenant);
            }
        }
        return new Clearing(settlement, io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "clearing.fee_income_account", clearing.feeIncomeAccount()),
                io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "clearing.interchange_income_account", clearing.interchangeIncomeAccount()),
                io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "clearing.nostro_account", clearing.nostroAccount()),
                io.meridian.web.config.TenantSettings.uuid("card-clearing-service", "clearing.tax_account", clearing.taxAccount()),
                clearing.matchTolerancePct(), clearing.atmFee(), clearing.interchangeRates());
    }

    /**
     * @param settlementAccounts  scheme settlement (clearing) ledger account per scheme, e.g. VISA -> uuid
     * @param feeIncomeAccount    fee income for cardholder fees
     * @param interchangeIncomeAccount interchange income
     * @param nostroAccount       bank account the scheme settles through
     * @param taxAccount          tax payable on fees
     * @param matchTolerancePct   cleared may exceed the remaining authorised amount by this fraction (default 0.20)
     * @param atmFee              flat ATM withdrawal fee in billing currency (default 2.50; 0 = none)
     * @param interchangeRates    interchange rate per scheme (defaults VISA 0.0115, MASTERCARD 0.0120, others 0.0100)
     */
    public record Clearing(Map<String, UUID> settlementAccounts, UUID feeIncomeAccount, UUID interchangeIncomeAccount,
                           UUID nostroAccount, UUID taxAccount, BigDecimal matchTolerancePct, BigDecimal atmFee,
                           Map<String, BigDecimal> interchangeRates) {
        public Clearing {
            settlementAccounts = settlementAccounts == null ? Map.of() : Map.copyOf(settlementAccounts);
            matchTolerancePct = matchTolerancePct == null ? new BigDecimal("0.20") : matchTolerancePct;
            atmFee = atmFee == null ? new BigDecimal("2.50") : atmFee;
            Map<String, BigDecimal> rates = new HashMap<>(Map.of("VISA", new BigDecimal("0.0115"),
                    "MASTERCARD", new BigDecimal("0.0120")));
            if (interchangeRates != null) {
                rates.putAll(interchangeRates);
            }
            interchangeRates = Map.copyOf(rates);
        }

        public BigDecimal interchangeRate(String scheme) {
            return interchangeRates.getOrDefault(scheme, new BigDecimal("0.0100"));
        }

        public UUID settlementAccount(String scheme) {
            return settlementAccounts.get(scheme);
        }
    }
}
