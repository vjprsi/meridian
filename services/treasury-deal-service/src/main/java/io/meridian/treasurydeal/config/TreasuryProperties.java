package io.meridian.treasurydeal.config;

import io.meridian.web.config.TenantSettings;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * treasury-deal-service settings ({@code meridian.treasury.*}).
 *
 * <p>The ledger book and the treasury accounts are tenant data: resolved per tenant from config-service (namespace
 * {@code ledger}, key {@code treasury-deal-service}: {@code ledger_id}, {@code accounts.<CCY>.<role>} with the role in
 * snake_case, e.g. {@code accounts.EUR.mm_placements}), falling back to the deployment values here.
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

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return TenantSettings.uuid(SERVICE, "ledger_id", ledgerId);
    }

    /** Ledger account for a role in a currency (tenant setting first, deployment value second), or null when not configured. */
    public UUID account(String currency, String role) {
        if (currency == null || role == null) {
            return null;
        }
        Map<String, UUID> byRole = accounts.get(currency);
        UUID deployed = byRole == null ? null : byRole.get(role);
        return TenantSettings.uuid(SERVICE, "accounts." + currency + "." + role.replace('-', '_'), deployed);
    }

    private static final String SERVICE = "treasury-deal-service";
}
