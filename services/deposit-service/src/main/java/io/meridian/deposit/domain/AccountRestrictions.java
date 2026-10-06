package io.meridian.deposit.domain;

import java.util.Collection;
import java.util.Set;

/**
 * Maps account blocks and freezes onto the deposit account status and onto the ledger account's posting
 * restriction (the ledger enforces it for every posting, whichever service sends it).
 */
public final class AccountRestrictions {
    /** Statuses whose value is derived from the set of active blocks. */
    private static final Set<String> BLOCKABLE = Set.of("ACTIVE", "BLOCKED_DEBIT", "BLOCKED_CREDIT", "BLOCKED");
    /** Statuses in which customer-initiated debits are refused by deposit-service. */
    private static final Set<String> DEBIT_ALLOWED = Set.of("ACTIVE", "BLOCKED_CREDIT", "INACTIVE");
    /** Statuses in which credits are accepted. */
    private static final Set<String> CREDIT_ALLOWED = Set.of("ACTIVE", "BLOCKED_DEBIT", "INACTIVE", "DORMANT",
            "PENDING_FUNDING", "CLOSING");

    private AccountRestrictions() {}

    /** Status after recomputing from active block scopes (DEBIT, CREDIT, BOTH; AMOUNT blocks are ledger holds). */
    public static String statusFor(String current, Collection<String> activeScopes) {
        if (!BLOCKABLE.contains(current)) {
            return current;
        }
        return blockedStatus(activeScopes);
    }

    /** The status an unfrozen account returns to given its active blocks. */
    public static String blockedStatus(Collection<String> activeScopes) {
        boolean debit = activeScopes.contains("DEBIT") || activeScopes.contains("BOTH");
        boolean credit = activeScopes.contains("CREDIT") || activeScopes.contains("BOTH");
        if (debit && credit) {
            return "BLOCKED";
        }
        if (debit) {
            return "BLOCKED_DEBIT";
        }
        return credit ? "BLOCKED_CREDIT" : "ACTIVE";
    }

    /** Ledger account status that enforces a deposit account status. */
    public static String ledgerStatusFor(String accountStatus) {
        return switch (accountStatus) {
            case "FROZEN", "BLOCKED" -> "FROZEN";
            case "BLOCKED_DEBIT" -> "FROZEN_DEBIT";
            case "BLOCKED_CREDIT" -> "FROZEN_CREDIT";
            default -> "ACTIVE";
        };
    }

    public static boolean allowsDebit(String status) {
        return DEBIT_ALLOWED.contains(status);
    }

    public static boolean allowsCredit(String status) {
        return CREDIT_ALLOWED.contains(status);
    }
}
