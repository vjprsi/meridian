package io.meridian.commission.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * commission-service settings ({@code meridian.commission.*}).
 *
 * @param payoutSourceWalletId commission pool wallet funding payouts (FLOAT / ESCROW wallet in wallet-service); per
 *                             tenant from config-service (namespace {@code ledger}, key {@code commission-service},
 *                             {@code payout_source_wallet_id}), this deployment value otherwise
 * @param listenerEnabled      consume wallet transaction events from Kafka to accrue commissions automatically
 */
@ConfigurationProperties(prefix = "meridian.commission")
public record CommissionProperties(UUID payoutSourceWalletId, Boolean listenerEnabled, UUID ledgerId,
                                   UUID commissionPayableAccountId, UUID commissionReceivableAccountId) {

    // WE-PRICE-1 added the CP/CR fields; the single canonical constructor is kept so Spring Boot
    // @ConfigurationProperties binds this record unambiguously (a second constructor made the binding
    // constructor ambiguous -> "No default constructor found" at context load).

    /** The tenant's commission pool wallet (wallets are tenant data): config-service setting first, deployment value second. */
    @Override
    public UUID payoutSourceWalletId() {
        return TenantSettings.uuid("commission-service", "payout_source_wallet_id", payoutSourceWalletId);
    }

    /** WE-PRICE-1 (PE-14): ledger book the CP/CR commission-sharing journal posts to. */
    @Override
    public UUID ledgerId() {
        return TenantSettings.uuid("commission-service", "ledger_id", ledgerId);
    }

    /** WE-PRICE-1 (PE-14): Commission Payable (CP) GL account credited when a shared commission is recognised. */
    @Override
    public UUID commissionPayableAccountId() {
        return TenantSettings.uuid("commission-service", "gl.commission_payable", commissionPayableAccountId);
    }

    /** WE-PRICE-1 (PE-14): Commission Receivable (CR) GL account debited when a shared commission is recognised. */
    @Override
    public UUID commissionReceivableAccountId() {
        return TenantSettings.uuid("commission-service", "gl.commission_receivable", commissionReceivableAccountId);
    }
}
