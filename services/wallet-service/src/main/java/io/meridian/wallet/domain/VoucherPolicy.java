package io.meridian.wallet.domain;

import io.meridian.common.error.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * WE-TXN-1 (G-03): the pure rules of the unregistered / voucher flows, separated from the service orchestration so
 * they can be unit tested without a database or ledger. Covers which money leg a voucher phase posts (the send is
 * priced and limited as the equivalent registered flow; the claim and rollback are zero-fee system movements against
 * the e-money suspense account), the one-time passcode (generated, hashed, verified; never stored in plaintext), the
 * expiry window and where expiry returns the funds.
 */
public final class VoucherPolicy {
    /** The three send flows this feature issues (MQ-FIN-4 / MQ-FIN-17 / MQ-FIN-14). */
    public static final Set<String> VOUCHER_TYPES = Set.of("CASH_IN_OTHERS", "P2P_UNREGISTERED", "UNREGISTERED_TRANSFER");
    /** Agent-held float wallet types that may fund an assisted voucher (cash-in-others, transfer-between-unregistered). */
    public static final Set<String> AGENT_WALLETS = Set.of("AGENT", "SUPER_AGENT", "FLOAT");

    private VoucherPolicy() {}

    /**
     * Transaction type of the <em>send</em> leg, so pricing, limits and TCP apply exactly as for the equivalent
     * registered flow: an agent cash-in (CASH_IN) for the two agent-assisted flows, a customer transfer (P2P) for the
     * on-the-fly flow. The funds are credited to the e-money suspense account rather than a payee wallet.
     */
    public static String issueTxnType(String voucherType) {
        return switch (voucherType) {
            case "CASH_IN_OTHERS", "UNREGISTERED_TRANSFER" -> "CASH_IN";
            case "P2P_UNREGISTERED" -> "P2P";
            default -> throw ApiException.unprocessable("invalid_voucher_type", "Unknown voucher type " + voucherType);
        };
    }

    /** The sender is a customer for the on-the-fly P2P, an agent for the two assisted flows. */
    public static String initiatorType(String voucherType) {
        return "P2P_UNREGISTERED".equals(voucherType) ? "CUSTOMER" : "AGENT";
    }

    /** True when the sender funds with an agent float wallet (assisted flows); false for the customer P2P. */
    public static boolean fundedByAgent(String voucherType) {
        return !"P2P_UNREGISTERED".equals(voucherType);
    }

    /** A voucher's expiry: {@code now} plus the override hours when positive, otherwise the configured default. */
    public static OffsetDateTime expiry(OffsetDateTime now, Integer overrideHours, int defaultHours) {
        int hours = overrideHours != null && overrideHours > 0 ? overrideHours : defaultHours;
        return now.plus(Duration.ofHours(hours));
    }

    /** True once {@code now} is at or past the voucher's expiry deadline. */
    public static boolean expired(OffsetDateTime expiresAt, OffsetDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** On expiry the funds go back to the sender unless the voucher is configured to age to unclaimed. */
    public static boolean rollbackToSender(String rollbackTarget) {
        return !"UNCLAIMED".equals(rollbackTarget);
    }

    /**
     * WE-FIN-1 / D-ED-08: whether an <em>expiry</em> rollback to the sender also refunds the issuance fee + tax. Only an
     * unclaimed voucher (still {@code ISSUED} when rolled back) refunds the fee, because the chargeable service was
     * never rendered. Gated by the per-tenant flag (default true). A voucher left to age to unclaimed returns nothing,
     * so this never applies to it.
     */
    public static boolean refundFeeOnExpiry(boolean enabled, String status) {
        return enabled && "ISSUED".equals(status);
    }

    /** A random numeric one-time passcode. */
    public static String generatePasscode(int length) {
        return PinPolicy.otp(length < 4 ? 4 : length);
    }

    /** PBKDF2 hash of the passcode (same scheme as the wallet PIN); the plaintext is never persisted. */
    public static String hashPasscode(String passcode, int iterations) {
        return PinPolicy.hash(passcode, iterations);
    }

    /** Constant-time verification of a presented passcode against its stored hash. */
    public static boolean verifyPasscode(String passcode, String hash) {
        return PinPolicy.verify(passcode, hash);
    }
}
