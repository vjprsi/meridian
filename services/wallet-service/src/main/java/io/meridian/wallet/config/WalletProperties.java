package io.meridian.wallet.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * wallet-service settings ({@code meridian.wallet.*}).
 *
 * @param ledgerId              ledger book holding wallet accounts and the GL accounts below
 * @param walletNumberPrefix    leading digits of generated wallet numbers
 * @param gl                    internal ledger accounts used as counterparties
 * @param pin                   PIN policy (hash cost, lockout)
 * @param confirmationMinutes   how long a pending (two-phase) transaction waits for confirmation
 * @param pinResetMinutes       validity of a PIN reset request / OTP
 * @param dormancyDays          days without customer activity before an active wallet becomes dormant
 * @param risk                  limits-service / fraud-service integration
 */
@ConfigurationProperties(prefix = "meridian.wallet")
public record WalletProperties(UUID ledgerId, String walletNumberPrefix, Gl gl, Pin pin, Integer confirmationMinutes,
                               Integer pinResetMinutes, Integer dormancyDays, Risk risk, Remote remote, Voucher voucher,
                               CashoutAuth cashoutAuth) {

    public WalletProperties {
        walletNumberPrefix = walletNumberPrefix == null ? "26" : walletNumberPrefix;
        gl = gl == null ? new Gl(null, null, null, null, null, null) : gl;
        pin = pin == null ? new Pin(null, null, null, null) : pin;
        confirmationMinutes = confirmationMinutes == null ? 10 : confirmationMinutes;
        pinResetMinutes = pinResetMinutes == null ? 10 : pinResetMinutes;
        dormancyDays = dormancyDays == null ? 180 : dormancyDays;
        risk = risk == null ? new Risk(null, null, null) : risk;
        remote = remote == null ? new Remote(null, null) : remote;
        voucher = voucher == null ? new Voucher(null, null, null, null, null, null, null) : voucher;
        cashoutAuth = cashoutAuth == null ? new CashoutAuth(null, null, null, null, null, null, null, null, null) : cashoutAuth;
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("wallet-service", "ledger_id", ledgerId);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code wallet-service}). */
    @Override
    public Gl gl() {
        return new Gl(io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.fee_income", gl.feeIncome()),
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.tax_payable", gl.taxPayable()),
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.bank_clearing", gl.bankClearing()),
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.suspense", gl.suspense()),
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.unclaimed", gl.unclaimed()),
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.atm_clearing", gl.atmClearing()));
    }

    /**
     * Ledger accounts: fee income, tax payable, the bank clearing (nostro / settlement) account and, for the
     * WE-TXN-1 unregistered / voucher flows, the e-money suspense (2710 / IND02) and unclaimed (2720 / IND06) accounts.
     * WE-FIN-1 / D-ED-09: {@code atmClearing} is the ATM settlement / clearing GL a cardless ATM OTP dispense settles to
     * ({@code atm-settlement-mode: CLEARING_GL}); daily-settled to the acquirer and reconciled by WE-RECON-1.
     */
    public record Gl(UUID feeIncome, UUID taxPayable, UUID bankClearing, UUID suspense, UUID unclaimed, UUID atmClearing) {}

    /**
     * @param iterations     PBKDF2 iterations
     * @param maxAttempts    consecutive failures before the PIN locks
     * @param lockMinutes    lock duration
     * @param otpMaxAttempts OTP attempts for PIN resets and OTP confirmations
     */
    public record Pin(Integer iterations, Integer maxAttempts, Integer lockMinutes, Integer otpMaxAttempts) {
        public Pin {
            iterations = iterations == null ? 120_000 : iterations;
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            lockMinutes = lockMinutes == null ? 30 : lockMinutes;
            otpMaxAttempts = otpMaxAttempts == null ? 3 : otpMaxAttempts;
        }
    }

    /**
     * @param limitsEnabled reserve usage in limits-service for every debit
     * @param fraudEnabled  score customer and agent initiated debits in fraud-service
     * @param failOpen      allow the transaction when a risk service is unavailable (502)
     */
    public record Risk(Boolean limitsEnabled, Boolean fraudEnabled, Boolean failOpen) {
        public Risk {
            limitsEnabled = limitsEnabled == null || limitsEnabled;
            fraudEnabled = fraudEnabled == null || fraudEnabled;
            failOpen = failOpen != null && failOpen;
        }
    }

    /**
     * WE-PRICE-1 (PE-16): onsite/offsite block policy. When enabled, a cash-in/cash-out classified OFFSITE (or
     * NOT_CLASSIFIED) is rejected. Off by default, so classification is recorded and priced but never blocks.
     */
    public record Remote(Boolean blockOffsite, Boolean blockNotClassified) {
        public Remote {
            blockOffsite = blockOffsite != null && blockOffsite;
            blockNotClassified = blockNotClassified != null && blockNotClassified;
        }
    }

    /**
     * WE-TXN-1 (G-03): unregistered / voucher flow settings.
     *
     * @param expiryHours        default voucher validity window (overridable per issuance); after it the rollback runs
     * @param passcodeLength     digits in the generated one-time passcode
     * @param passcodeMaxAttempts wrong-passcode attempts before a claim is locked out
     * @param rollbackTarget     where expiry returns funds: SENDER (DEBIT suspense / CREDIT sender) or UNCLAIMED
     *                           (leave in suspense to age via the WE-FLOAT-1 unclaimed-settlement run)
     * @param scheduleEnabled    run the in-process rollback scheduler (default false; opt-in per deployment)
     * @param intervalMs         delay between scheduled rollback passes
     * @param refundFeeOnExpiry  WE-FIN-1 / D-ED-08: when an unclaimed voucher is rolled back to the sender on expiry,
     *                           also refund the issuance fee + tax (the chargeable service was never rendered); default
     *                           true (safe-by-default conduct fix). When false the fee/tax stays earned (pre-WE-FIN-1)
     */
    public record Voucher(Integer expiryHours, Integer passcodeLength, Integer passcodeMaxAttempts, String rollbackTarget,
                          Boolean scheduleEnabled, Long intervalMs, Boolean refundFeeOnExpiry) {
        public Voucher {
            expiryHours = expiryHours == null ? 72 : expiryHours;
            passcodeLength = passcodeLength == null ? 6 : passcodeLength;
            passcodeMaxAttempts = passcodeMaxAttempts == null ? 3 : passcodeMaxAttempts;
            rollbackTarget = rollbackTarget == null ? "SENDER" : rollbackTarget;
            scheduleEnabled = scheduleEnabled != null && scheduleEnabled;
            intervalMs = intervalMs == null ? 3_600_000L : intervalMs;
            refundFeeOnExpiry = refundFeeOnExpiry == null || refundFeeOnExpiry;
        }
    }

    /**
     * WE-TXN-2 (G-07): agent passcode / ATM OTP / authorisation-code cash-out settings. Each mechanism holds value in
     * the e-money suspense account (reusing the WE-TXN-1 routing) against a hashed one-time code; the gated scheduler
     * rolls back codes that expire unredeemed (chiefly the ATM OTP) to the customer wallet.
     *
     * @param codeLength                 digits in the generated one-time passcode / OTP / authorisation code
     * @param codeMaxAttempts            wrong-code attempts before a redeem is locked out
     * @param agentPasscodeExpiryMinutes default validity window of an agent passcode cash-out (overridable per issuance)
     * @param atmOtpExpiryMinutes        default validity window of a cardless ATM OTP (short-lived)
     * @param authCodeExpiryMinutes      default validity window of a pre-authorised authorisation code
     * @param scheduleEnabled            run the in-process auto-rollback scheduler (default false; opt-in per deployment)
     * @param intervalMs                 delay between scheduled rollback passes
     * @param refundFeeOnExpiry          WE-FIN-1 / D-ED-08: when an unredeemed authorisation is auto-rolled-back on
     *                                   expiry, also refund the hold-leg fee + tax (service never rendered); default
     *                                   true (safe-by-default conduct fix). False keeps the fee/tax earned (pre-WE-FIN-1)
     * @param atmSettlementMode          WE-FIN-1 / D-ED-09: how a cardless ATM OTP dispense settles out of suspense -
     *                                   WALLET (to the ATM / branch settlement float wallet, default = current
     *                                   behaviour) or CLEARING_GL (GL->GL to {@code gl.atm-clearing}, daily-settled to
     *                                   the acquirer and reconciled by WE-RECON-1). Agent passcode / auth code are
     *                                   always wallet-settled regardless of this mode
     */
    public record CashoutAuth(Integer codeLength, Integer codeMaxAttempts, Integer agentPasscodeExpiryMinutes,
                              Integer atmOtpExpiryMinutes, Integer authCodeExpiryMinutes, Boolean scheduleEnabled,
                              Long intervalMs, Boolean refundFeeOnExpiry, String atmSettlementMode) {
        public CashoutAuth {
            codeLength = codeLength == null ? 6 : codeLength;
            codeMaxAttempts = codeMaxAttempts == null ? 3 : codeMaxAttempts;
            agentPasscodeExpiryMinutes = agentPasscodeExpiryMinutes == null ? 1_440 : agentPasscodeExpiryMinutes;
            atmOtpExpiryMinutes = atmOtpExpiryMinutes == null ? 15 : atmOtpExpiryMinutes;
            authCodeExpiryMinutes = authCodeExpiryMinutes == null ? 60 : authCodeExpiryMinutes;
            scheduleEnabled = scheduleEnabled != null && scheduleEnabled;
            intervalMs = intervalMs == null ? 300_000L : intervalMs;
            refundFeeOnExpiry = refundFeeOnExpiry == null || refundFeeOnExpiry;
            atmSettlementMode = atmSettlementMode == null ? "WALLET" : atmSettlementMode;
        }
    }
}
