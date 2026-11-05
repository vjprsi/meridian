package io.meridian.wallet.domain;

import io.meridian.common.error.ApiException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * WE-TXN-2 (G-07): the pure rules of the three customer-authorised cash-out / authorisation mechanisms, separated
 * from the service orchestration so they can be unit tested without a database or ledger. All three reuse the
 * WE-TXN-1 voucher seam (a value held in the e-money suspense account against a hashed one-time code, released on
 * redeem or rolled back on expiry):
 *
 * <ul>
 *   <li><b>AGENT_PASSCODE</b> - a registered holder initiates a withdrawal at an agent; a one-time passcode
 *       authorises the agent to disburse cash (settles suspense -&gt; the agent float wallet).</li>
 *   <li><b>ATM_OTP</b> - cardless ATM withdrawal; an OTP holds the funds and, on dispense, settles out; if the OTP
 *       expires unredeemed the gated scheduler auto-rolls-back the hold to the customer wallet.</li>
 *   <li><b>AUTH_CODE</b> - a cardless / pre-authorised authorisation code redeemable once within a validity window,
 *       then captured (settled to the payee) or rolled back.</li>
 * </ul>
 *
 * <p>The debit (hold) leg is priced, limited and TCP-checked exactly as the equivalent registered CASH_OUT; the
 * settle and rollback legs are zero-fee system movements against the suspense account. The one-time code is generated,
 * hashed (PBKDF2, same scheme as the wallet PIN) and verified; the plaintext is never persisted or logged.
 */
public final class CashoutAuthPolicy {
    /** The three cash-out / authorisation mechanisms this feature issues (G-07). */
    public static final Set<String> AUTH_TYPES = Set.of("AGENT_PASSCODE", "ATM_OTP", "AUTH_CODE");
    /** Wallet types that may be credited when a passcode cash-out, ATM dispense or auth code is redeemed. */
    public static final Set<String> SETTLEMENT_WALLETS = Set.of("AGENT", "SUPER_AGENT", "FLOAT", "MERCHANT", "TREASURY");
    /** WE-FIN-1 / D-ED-09: cardless ATM OTP settlement targets. */
    public static final String ATM_MODE_WALLET = "WALLET";
    public static final String ATM_MODE_CLEARING_GL = "CLEARING_GL";

    private CashoutAuthPolicy() {}

    /**
     * WE-FIN-1 / D-ED-08: whether an <em>expiry</em> auto-rollback refunds the hold-leg fee + tax. Only an authorisation
     * that was never redeemed (still {@code ISSUED} when it is rolled back) refunds the fee, because the chargeable
     * service was never rendered; a redeemed / rolled-back item never does. Gated by the per-tenant flag (default true).
     */
    public static boolean refundFeeOnExpiry(boolean enabled, String status) {
        return enabled && "ISSUED".equals(status);
    }

    /**
     * WE-FIN-1 / D-ED-09: whether a redeem settles GL->GL to the ATM clearing account rather than to a settlement float
     * wallet. True only for a cardless ATM OTP when the tenant selected {@code CLEARING_GL}; an agent passcode or an
     * authorisation code always settles to a wallet, whatever the mode.
     */
    public static boolean atmSettlesToClearingGl(String authType, String atmSettlementMode) {
        return "ATM_OTP".equals(authType) && ATM_MODE_CLEARING_GL.equals(atmSettlementMode);
    }

    /** Throws 422 {@code invalid_auth_type} for anything outside {@link #AUTH_TYPES}. */
    public static void requireKnownType(String authType) {
        if (!AUTH_TYPES.contains(authType)) {
            throw ApiException.unprocessable("invalid_auth_type", "Unknown cash-out authorisation type " + authType);
        }
    }

    /**
     * Transaction type of the <em>hold</em> leg, so pricing, limits and TCP apply exactly as for a registered agent
     * cash-out: a CASH_OUT whose credit side is the e-money suspense account rather than an agent float wallet.
     */
    public static String issueTxnType(String authType) {
        requireKnownType(authType);
        return "CASH_OUT";
    }

    /** All three authorisations are initiated by the registered wallet holder (the customer). */
    public static String initiatorType(String authType) {
        requireKnownType(authType);
        return "CUSTOMER";
    }

    /**
     * The default validity window (minutes) of each mechanism - an agent passcode lives for hours, a cardless ATM OTP
     * only minutes, a pre-authorised auth code somewhere between - each overridable per issuance.
     */
    public static int defaultExpiryMinutes(String authType, int agentPasscodeMinutes, int atmOtpMinutes,
                                           int authCodeMinutes) {
        return switch (authType) {
            case "AGENT_PASSCODE" -> agentPasscodeMinutes;
            case "ATM_OTP" -> atmOtpMinutes;
            case "AUTH_CODE" -> authCodeMinutes;
            default -> throw ApiException.unprocessable("invalid_auth_type",
                    "Unknown cash-out authorisation type " + authType);
        };
    }

    /** An authorisation's expiry: {@code now} plus the override minutes when positive, otherwise the default. */
    public static OffsetDateTime expiry(OffsetDateTime now, Integer overrideMinutes, int defaultMinutes) {
        int minutes = overrideMinutes != null && overrideMinutes > 0 ? overrideMinutes : defaultMinutes;
        return now.plus(Duration.ofMinutes(minutes));
    }

    /** True once {@code now} is at or past the authorisation's expiry deadline. */
    public static boolean expired(OffsetDateTime expiresAt, OffsetDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** A random numeric one-time code (passcode / OTP / authorisation code). */
    public static String generateCode(int length) {
        return PinPolicy.otp(length < 4 ? 4 : length);
    }

    /** PBKDF2 hash of the one-time code (same scheme as the wallet PIN); the plaintext is never persisted. */
    public static String codeHash(String code, int iterations) {
        return PinPolicy.hash(code, iterations);
    }

    /** Constant-time verification of a presented one-time code against its stored hash. */
    public static boolean verifyCode(String code, String hash) {
        return PinPolicy.verify(code, hash);
    }
}
