package io.meridian.fx.config;

import io.meridian.web.config.TenantSettings;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * fx-service settings ({@code meridian.fx.*}).
 *
 * <p>The ledger book and the position / clearing accounts are tenant data: each is resolved per tenant from
 * config-service (namespace {@code ledger}, key {@code fx-service}: {@code ledger_id},
 * {@code position_accounts.<CCY>}, {@code clearing_accounts.<CCY>}) and falls back to the deployment value here.
 *
 * @param ledgerId          ledger book holding the FX position and clearing accounts
 * @param quoteTtlSeconds   validity of a firm quote
 * @param defaultMarginBps  margin when no margin rule matches
 * @param spotDays          business days to the value date of deals not linked to a payment (spot = T+2)
 * @param positionAccounts  FX position ledger account per currency (ISO 4217 code -> ledger account id)
 * @param clearingAccounts  FX clearing ledger account per currency (counterparty of the customer leg)
 */
@ConfigurationProperties(prefix = "meridian.fx")
public record FxProperties(UUID ledgerId, Integer quoteTtlSeconds, Integer defaultMarginBps, Integer spotDays,
                           Map<String, UUID> positionAccounts, Map<String, UUID> clearingAccounts) {
    public FxProperties {
        quoteTtlSeconds = quoteTtlSeconds == null ? 30 : quoteTtlSeconds;
        defaultMarginBps = defaultMarginBps == null ? 50 : defaultMarginBps;
        spotDays = spotDays == null ? 2 : spotDays;
        positionAccounts = positionAccounts == null ? Map.of() : Map.copyOf(positionAccounts);
        clearingAccounts = clearingAccounts == null ? Map.of() : Map.copyOf(clearingAccounts);
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return TenantSettings.uuid(SERVICE, "ledger_id", ledgerId);
    }

    /** The tenant's FX position account for a currency, else the deployment value, else null. */
    public UUID positionAccount(String currency) {
        return currency == null ? null
                : TenantSettings.uuid(SERVICE, "position_accounts." + currency, positionAccounts.get(currency));
    }

    /** The tenant's FX clearing account for a currency, else the deployment value, else null. */
    public UUID clearingAccount(String currency) {
        return currency == null ? null
                : TenantSettings.uuid(SERVICE, "clearing_accounts." + currency, clearingAccounts.get(currency));
    }

    private static final String SERVICE = "fx-service";
}
