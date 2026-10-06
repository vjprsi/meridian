package io.meridian.carddispute.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * card-dispute-service settings ({@code meridian.cards.*}).
 *
 * @param ledgerId ledger book of the dispute postings
 * @param dispute  internal ledger accounts used as counterparties of dispute postings
 */
@ConfigurationProperties(prefix = "meridian.cards")
public record DisputeProperties(UUID ledgerId, Dispute dispute) {

    public DisputeProperties {
        dispute = dispute == null ? new Dispute(null, null, null) : dispute;
    }

    /**
     * @param suspenseAccount          dispute suspense: funds provisional credit until the outcome is known
     * @param lossExpenseAccount       dispute losses the issuer absorbs (write-offs)
     * @param schemeReceivableAccount  chargeback receivable from the scheme / acquirer (recoveries)
     */
    public record Dispute(UUID suspenseAccount, UUID lossExpenseAccount, UUID schemeReceivableAccount) {}
}
