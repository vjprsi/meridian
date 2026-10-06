package io.meridian.disbursement.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** What disbursement-service needs from wallet-service (the wallets money engine). */
public interface WalletPort {
    /** Wallet by id (404 when unknown). */
    JsonNode wallet(UUID walletId);

    /** Active or dormant wallet bound to the msisdn in the currency; empty when none exists. */
    Optional<JsonNode> lookup(String msisdn, String currency);

    /** Opens and activates a Tier 0 customer wallet for an unregistered beneficiary (idempotent per key). */
    JsonNode openTierZeroWallet(String key, String msisdn, String displayName, String currency);

    /** Available balance of a wallet. */
    BigDecimal available(UUID walletId);

    /** One payout from the funding wallet to a beneficiary wallet (idempotent per key); returns the wallet transaction. */
    JsonNode payout(String key, Payout payout);

    record Payout(String txnType, UUID payerWalletId, UUID payeeWalletId, BigDecimal amount, String currency,
                  UUID beneficiaryId, String description) {}
}
