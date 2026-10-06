package io.meridian.treasurydeal.config;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * treasury-deal-service settings ({@code meridian.treasury.*}).
 *
 * @param ledgerId    ledger book in ledger-service holding the treasury accounts
 * @param ourBic      our BIC (sender of confirmations, field 82A)
 * @param accounts    ledger accounts per currency and role, e.g. {@code accounts.EUR.nostro}; roles: nostro,
 *                    mm-placements, mm-borrowings, interest-receivable, interest-payable, interest-income,
 *                    interest-expense, fx-position, securities, realised-pnl
 */
@ConfigurationProperties(prefix = "meridian.treasury")
public record TreasuryProperties(UUID ledgerId, String ourBic, Map<String, Map<String, UUID>> accounts) {

    public TreasuryProperties {
        ourBic = ourBic == null ? "MERIGB2LXXX" : ourBic;
        accounts = accounts == null ? Map.of() : accounts;
    }

    /** Ledger account for a role in a currency, or null when not configured. */
    public UUID account(String currency, String role) {
        Map<String, UUID> byRole = accounts.get(currency);
        return byRole == null ? null : byRole.get(role);
    }
}
