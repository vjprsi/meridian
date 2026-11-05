package io.meridian.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * WE-TXN-2 cash-out authorisation rules: hold-leg pricing selection, one-time code hashing / verification, per-type
 * expiry windows (minutes), redeem-once lockout boundary and the rollback decision.
 */
class CashoutAuthPolicyTest {

    @Test
    void holdLegIsPricedAsTheEquivalentRegisteredCashOut() {
        for (String type : new String[] {"AGENT_PASSCODE", "ATM_OTP", "AUTH_CODE"}) {
            assertThat(CashoutAuthPolicy.issueTxnType(type)).isEqualTo("CASH_OUT");
            assertThat(CashoutAuthPolicy.initiatorType(type)).isEqualTo("CUSTOMER");
        }
    }

    @Test
    void rejectsUnknownAuthType() {
        assertThatThrownBy(() -> CashoutAuthPolicy.issueTxnType("MYSTERY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_auth_type");
        assertThatThrownBy(() -> CashoutAuthPolicy.requireKnownType("MYSTERY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_auth_type");
    }

    @Test
    void oneTimeCodeIsNumericHashedAndVerifiedWithoutPlaintext() {
        String code = CashoutAuthPolicy.generateCode(6);
        assertThat(code).matches("\\d{6}");
        String hash = CashoutAuthPolicy.codeHash(code, 1000);
        assertThat(hash).startsWith("pbkdf2$1000$").doesNotContain(code);
        assertThat(CashoutAuthPolicy.verifyCode(code, hash)).isTrue();
        assertThat(CashoutAuthPolicy.verifyCode(code + "0", hash)).isFalse();
        assertThat(CashoutAuthPolicy.verifyCode(null, hash)).isFalse();
        // two issuances of the same code get different salts (no plaintext leak via equality)
        assertThat(CashoutAuthPolicy.codeHash(code, 1000)).isNotEqualTo(hash);
    }

    @Test
    void codeHasAtLeastFourDigits() {
        assertThat(CashoutAuthPolicy.generateCode(2)).hasSize(4);
        assertThat(CashoutAuthPolicy.generateCode(8)).hasSize(8);
    }

    @Test
    void defaultExpiryMinutesIsSelectedPerMechanism() {
        assertThat(CashoutAuthPolicy.defaultExpiryMinutes("AGENT_PASSCODE", 1440, 15, 60)).isEqualTo(1440);
        assertThat(CashoutAuthPolicy.defaultExpiryMinutes("ATM_OTP", 1440, 15, 60)).isEqualTo(15);
        assertThat(CashoutAuthPolicy.defaultExpiryMinutes("AUTH_CODE", 1440, 15, 60)).isEqualTo(60);
        assertThatThrownBy(() -> CashoutAuthPolicy.defaultExpiryMinutes("MYSTERY", 1440, 15, 60))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_auth_type");
    }

    @Test
    void expiryUsesOverrideWhenPositiveElseTheDefault() {
        OffsetDateTime now = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(CashoutAuthPolicy.expiry(now, null, 15)).isEqualTo(now.plusMinutes(15));
        assertThat(CashoutAuthPolicy.expiry(now, 0, 15)).isEqualTo(now.plusMinutes(15));
        assertThat(CashoutAuthPolicy.expiry(now, -5, 15)).isEqualTo(now.plusMinutes(15));
        assertThat(CashoutAuthPolicy.expiry(now, 30, 15)).isEqualTo(now.plusMinutes(30));
    }

    @Test
    void expiredIsInclusiveOfTheDeadline() {
        OffsetDateTime expiresAt = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(CashoutAuthPolicy.expired(expiresAt, expiresAt.minusSeconds(1))).isFalse();
        assertThat(CashoutAuthPolicy.expired(expiresAt, expiresAt)).isTrue();
        assertThat(CashoutAuthPolicy.expired(expiresAt, expiresAt.plusSeconds(1))).isTrue();
        assertThat(CashoutAuthPolicy.expired(null, expiresAt)).isFalse();
    }

    // ------------------------------------------------------------------ WE-FIN-1 / D-ED-08 fee refund on expiry

    @Test
    void refundsFeeOnlyOnAnUnredeemedExpiryWhenEnabled() {
        // enabled + still ISSUED (never redeemed) -> refund the full total_debit_amount (principal + fee + tax)
        assertThat(CashoutAuthPolicy.refundFeeOnExpiry(true, "ISSUED")).isTrue();
        // disabled -> principal only, fee/tax stays earned (pre-WE-FIN-1 behaviour)
        assertThat(CashoutAuthPolicy.refundFeeOnExpiry(false, "ISSUED")).isFalse();
        // redeemed-state guard: a completed / already-rolled-back item never refunds the fee on this path
        assertThat(CashoutAuthPolicy.refundFeeOnExpiry(true, "REDEEMED")).isFalse();
        assertThat(CashoutAuthPolicy.refundFeeOnExpiry(true, "ROLLED_BACK")).isFalse();
    }

    // ------------------------------------------------------------------ WE-FIN-1 / D-ED-09 ATM settlement target

    @Test
    void onlyAtmOtpInClearingGlModeSettlesGlToGl() {
        assertThat(CashoutAuthPolicy.atmSettlesToClearingGl("ATM_OTP", CashoutAuthPolicy.ATM_MODE_CLEARING_GL)).isTrue();
        // default WALLET mode keeps the current settlement-float-wallet path
        assertThat(CashoutAuthPolicy.atmSettlesToClearingGl("ATM_OTP", CashoutAuthPolicy.ATM_MODE_WALLET)).isFalse();
        assertThat(CashoutAuthPolicy.atmSettlesToClearingGl("ATM_OTP", null)).isFalse();
        // agent passcode and auth code always settle to a wallet, whatever the mode
        assertThat(CashoutAuthPolicy.atmSettlesToClearingGl("AGENT_PASSCODE", CashoutAuthPolicy.ATM_MODE_CLEARING_GL)).isFalse();
        assertThat(CashoutAuthPolicy.atmSettlesToClearingGl("AUTH_CODE", CashoutAuthPolicy.ATM_MODE_CLEARING_GL)).isFalse();
    }
}
