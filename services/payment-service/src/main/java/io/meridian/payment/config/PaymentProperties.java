package io.meridian.payment.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * payment-service settings ({@code meridian.payment.*}).
 *
 * @param ledgerId        ledger book holding customer accounts and the internal accounts below
 * @param bankBic         our BIC (debtor agent on outbound payments)
 * @param bankCode        bank code embedded in our IBANs (on-us detection for Confirmation of Payee)
 * @param bankName        name printed on receipts
 * @param gl              internal ledger accounts
 * @param fees            flat fee per payment type in the payment currency (debtor-borne unless charge bearer CRED)
 * @param approval        business approval matrix (four-eyes thresholds)
 * @param cop             Confirmation of Payee thresholds
 * @param otp             alias ownership OTP settings
 * @param payeeCoolingOffHours new-payee limit window
 * @param linkBaseUrl     public base URL for payment links
 * @param qrGuid          globally unique id placed in EMVCo merchant account information (tag 26 sub-tag 00)
 * @param screeningEnabled call sanctions screening for non-internal payments
 * @param limitsEnabled   reserve limits for customer payments
 * @param refundDays      debtor's right-to-refund window for direct debits (SEPA Core: 8 weeks)
 */
@ConfigurationProperties(prefix = "meridian.payment")
public record PaymentProperties(UUID ledgerId, String bankBic, String bankCode, String bankName, Gl gl,
                                Map<String, BigDecimal> fees, Approval approval, Cop cop, Otp otp,
                                Integer payeeCoolingOffHours, String linkBaseUrl, String qrGuid,
                                Boolean screeningEnabled, Boolean limitsEnabled, Integer refundDays) {

    public PaymentProperties {
        bankBic = bankBic == null ? "MERIGB2LXXX" : bankBic;
        bankCode = bankCode == null ? "MERI" : bankCode;
        bankName = bankName == null ? "Meridian Bank" : bankName;
        gl = gl == null ? new Gl(null, null, null) : gl;
        fees = fees == null ? Map.of() : Map.copyOf(fees);
        approval = approval == null ? new Approval(null, null, null) : approval;
        cop = cop == null ? new Cop(null, null) : cop;
        otp = otp == null ? new Otp(null, null) : otp;
        payeeCoolingOffHours = payeeCoolingOffHours == null ? 24 : payeeCoolingOffHours;
        linkBaseUrl = linkBaseUrl == null ? "https://pay.meridian.example" : linkBaseUrl;
        qrGuid = qrGuid == null ? "IO.MERIDIAN.PAY" : qrGuid;
        screeningEnabled = screeningEnabled == null || screeningEnabled;
        limitsEnabled = limitsEnabled == null || limitsEnabled;
        refundDays = refundDays == null ? 56 : refundDays;
    }

    /** Ledger book: the tenant's setting in config-service (namespace {@code ledger}) wins over this deployment value. */
    @Override
    public UUID ledgerId() {
        return io.meridian.web.config.TenantSettings.uuid("payment-service", "ledger_id", ledgerId);
    }

    /** Internal accounts, per tenant from config-service (namespace {@code ledger}, key {@code payment-service}). */
    @Override
    public Gl gl() {
        return new Gl(io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.clearing", gl.clearing()),
                io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.fee_income", gl.feeIncome()),
                io.meridian.web.config.TenantSettings.uuid("payment-service", "gl.direct_debit_clearing", gl.directDebitClearing()));
    }

    /**
     * @param clearing          default rail clearing account when a rail has no settlement account
     * @param feeIncome         fee income account
     * @param directDebitClearing clearing account for direct-debit collections
     */
    public record Gl(UUID clearing, UUID feeIncome, UUID directDebitClearing) {}

    /**
     * @param levels        approval levels: a payment at or above {@code minAmount} needs that level's approvals
     * @param bulkMinApprovals approvals always required for bulk payments
     * @param expiryHours   approval window
     */
    public record Approval(List<Level> levels, Integer bulkMinApprovals, Integer expiryHours) {
        public Approval {
            levels = levels == null ? List.of() : List.copyOf(levels);
            bulkMinApprovals = bulkMinApprovals == null ? 1 : bulkMinApprovals;
            expiryHours = expiryHours == null ? 72 : expiryHours;
        }
    }

    public record Level(BigDecimal minAmount, Integer approvals) {}

    public record Cop(BigDecimal matchThreshold, BigDecimal closeMatchThreshold) {
        public Cop {
            matchThreshold = matchThreshold == null ? new BigDecimal("0.98") : matchThreshold;
            closeMatchThreshold = closeMatchThreshold == null ? new BigDecimal("0.80") : closeMatchThreshold;
        }
    }

    public record Otp(Integer ttlMinutes, Integer maxAttempts) {
        public Otp {
            ttlMinutes = ttlMinutes == null ? 10 : ttlMinutes;
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
        }
    }
}
