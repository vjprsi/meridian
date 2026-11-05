package io.meridian.casemanagement.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * WE-OPS-1: the seam through which an approved {@code wallet_txn_correction} executes its reversal via the existing
 * wallet-service reversal path (the checker here is the reversal's checker). case-management never posts ledger
 * entries directly; it calls wallet-service's {@code POST /v1/wallet-transactions/{id}/reverse}, which owns the
 * REVERSAL transaction type and the compensating postings. The call is idempotent on {@code idempotencyKey}.
 */
public interface WalletReversalPort {

    /**
     * Reverses (fully when {@code amount} is null, otherwise partially) the given wallet transaction.
     *
     * @return the id of the resulting wallet REVERSAL transaction
     */
    UUID reverse(UUID walletTransactionId, BigDecimal amount, String currency, String reason, String idempotencyKey);
}
