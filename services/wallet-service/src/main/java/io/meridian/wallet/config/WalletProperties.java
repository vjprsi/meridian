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
                               Integer pinResetMinutes, Integer dormancyDays, Risk risk) {

    public WalletProperties {
        walletNumberPrefix = walletNumberPrefix == null ? "26" : walletNumberPrefix;
        gl = gl == null ? new Gl(null, null, null) : gl;
        pin = pin == null ? new Pin(null, null, null, null) : pin;
        confirmationMinutes = confirmationMinutes == null ? 10 : confirmationMinutes;
        pinResetMinutes = pinResetMinutes == null ? 10 : pinResetMinutes;
        dormancyDays = dormancyDays == null ? 180 : dormancyDays;
        risk = risk == null ? new Risk(null, null, null) : risk;
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
                io.meridian.web.config.TenantSettings.uuid("wallet-service", "gl.bank_clearing", gl.bankClearing()));
    }

    /** Ledger accounts: fee income, tax payable and the bank clearing (nostro / settlement) account. */
    public record Gl(UUID feeIncome, UUID taxPayable, UUID bankClearing) {}

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
}
