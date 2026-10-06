package io.meridian.wallet.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** Wallet status machine and the transaction state machine. */
public final class WalletLifecycle {
    private WalletLifecycle() {}

    private static final Map<String, Set<String>> WALLET = Map.of(
            "PENDING", Set.of("ACTIVE", "CLOSED", "BLOCKED"),
            "ACTIVE", Set.of("SUSPENDED", "BLOCKED", "DORMANT", "CLOSED"),
            "DORMANT", Set.of("ACTIVE", "SUSPENDED", "BLOCKED", "CLOSED"),
            "SUSPENDED", Set.of("ACTIVE", "BLOCKED", "CLOSED"),
            "BLOCKED", Set.of("ACTIVE"),
            "CLOSED", Set.of());

    /** Ledger posting restriction matching a wallet status. */
    public static String ledgerStatus(String walletStatus) {
        return switch (walletStatus) {
            case "SUSPENDED" -> "FROZEN_DEBIT";
            case "BLOCKED" -> "FROZEN";
            case "CLOSED" -> "CLOSED";
            default -> "ACTIVE";
        };
    }

    public static void requireWalletTransition(String from, String to) {
        if (!WALLET.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Wallet cannot move from " + from + " to " + to);
        }
    }

    /** Whether money may leave (debit) or arrive (credit) at a wallet in this status. */
    public static boolean canDebit(String status) {
        return "ACTIVE".equals(status);
    }

    public static boolean canCredit(String status) {
        return "ACTIVE".equals(status) || "DORMANT".equals(status) || "SUSPENDED".equals(status);
    }

    private static final Map<String, Set<String>> TXN = Map.of(
            "INITIATED", Set.of("PENDING_CONFIRMATION", "PROCESSING", "COMPLETED", "FAILED"),
            "PROCESSING", Set.of("COMPLETED", "FAILED"),
            "PENDING_CONFIRMATION", Set.of("COMPLETED", "CANCELLED", "EXPIRED", "FAILED"),
            "COMPLETED", Set.of("REVERSED"),
            "FAILED", Set.of(),
            "CANCELLED", Set.of(),
            "EXPIRED", Set.of(),
            "REVERSED", Set.of());

    public static void requireTxnTransition(String from, String to) {
        if (!TXN.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Transaction cannot move from " + from + " to " + to);
        }
    }
}
