package io.meridian.carddispute.config;

import io.meridian.web.config.TenantSettings;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-dispute-service settings ({@code meridian.cards.*}).
 *
 * <p>The ledger book and the dispute accounts are tenant data: resolved per tenant from config-service (namespace
 * {@code ledger}, key {@code card-dispute-service}: {@code ledger_id}, {@code dispute.suspense_account},
 * {@code dispute.loss_expense_account}, {@code dispute.scheme_receivable_account}), falling back to the deployment
 * values here. There are no defaults: with neither set, postings fail with {@code ledger_not_configured}.
 *
 * @param ledgerId ledger book of the dispute postings
 * @param dispute  internal ledger accounts used as counterparties of dispute postings
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record DisputeProperties(UUID ledgerId, Dispute dispute) {

    public DisputeProperties {
        dispute = dispute == null ? new Dispute(null, null, null) : dispute;
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return TenantSettings.uuid(SERVICE, "ledger_id", ledgerId);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code card-dispute-service}). */
    @Override
    public Dispute dispute() {
        return new Dispute(TenantSettings.uuid(SERVICE, "dispute.suspense_account", dispute.suspenseAccount()),
                TenantSettings.uuid(SERVICE, "dispute.loss_expense_account", dispute.lossExpenseAccount()),
                TenantSettings.uuid(SERVICE, "dispute.scheme_receivable_account", dispute.schemeReceivableAccount()));
    }

    private static final String SERVICE = "card-dispute-service";

    /**
     * @param suspenseAccount          dispute suspense: funds provisional credit until the outcome is known
     * @param lossExpenseAccount       dispute losses the issuer absorbs (write-offs)
     * @param schemeReceivableAccount  chargeback receivable from the scheme / acquirer (recoveries)
     */
    public record Dispute(UUID suspenseAccount, UUID lossExpenseAccount, UUID schemeReceivableAccount) {}
}
