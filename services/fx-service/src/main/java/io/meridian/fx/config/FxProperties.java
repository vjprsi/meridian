package io.meridian.fx.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * fx-service settings ({@code meridian.fx.*}).
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
}
