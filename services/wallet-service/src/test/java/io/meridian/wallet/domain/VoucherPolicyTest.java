package io.meridian.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** WE-TXN-1 voucher rules: send-leg selection, passcode hashing/verification, expiry window and rollback target. */
class VoucherPolicyTest {

    @Test
    void sendLegIsPricedAsTheEquivalentRegisteredFlow() {
        // cash-in-others and transfer-between-unregistered are agent cash-ins; P2P-on-the-fly is a customer P2P
        assertThat(VoucherPolicy.issueTxnType("CASH_IN_OTHERS")).isEqualTo("CASH_IN");
        assertThat(VoucherPolicy.issueTxnType("UNREGISTERED_TRANSFER")).isEqualTo("CASH_IN");
        assertThat(VoucherPolicy.issueTxnType("P2P_UNREGISTERED")).isEqualTo("P2P");

        assertThat(VoucherPolicy.initiatorType("CASH_IN_OTHERS")).isEqualTo("AGENT");
        assertThat(VoucherPolicy.initiatorType("UNREGISTERED_TRANSFER")).isEqualTo("AGENT");
        assertThat(VoucherPolicy.initiatorType("P2P_UNREGISTERED")).isEqualTo("CUSTOMER");

        assertThat(VoucherPolicy.fundedByAgent("CASH_IN_OTHERS")).isTrue();
        assertThat(VoucherPolicy.fundedByAgent("UNREGISTERED_TRANSFER")).isTrue();
        assertThat(VoucherPolicy.fundedByAgent("P2P_UNREGISTERED")).isFalse();
    }

    @Test
    void rejectsUnknownVoucherType() {
        assertThatThrownBy(() -> VoucherPolicy.issueTxnType("MYSTERY"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid_voucher_type");
    }

    @Test
    void passcodeIsNumericHashedAndVerifiedWithoutPlaintext() {
        String passcode = VoucherPolicy.generatePasscode(6);
        assertThat(passcode).matches("\\d{6}");
        String hash = VoucherPolicy.hashPasscode(passcode, 1000);
        assertThat(hash).startsWith("pbkdf2$1000$").doesNotContain(passcode);
        assertThat(VoucherPolicy.verifyPasscode(passcode, hash)).isTrue();
        assertThat(VoucherPolicy.verifyPasscode(passcode + "0", hash)).isFalse();
        assertThat(VoucherPolicy.verifyPasscode(null, hash)).isFalse();
        // two issuances of the same passcode get different salts (no plaintext leak via equality)
        assertThat(VoucherPolicy.hashPasscode(passcode, 1000)).isNotEqualTo(hash);
    }

    @Test
    void passcodeHasAtLeastFourDigits() {
        assertThat(VoucherPolicy.generatePasscode(2)).hasSize(4);
        assertThat(VoucherPolicy.generatePasscode(8)).hasSize(8);
    }

    @Test
    void expiryUsesOverrideWhenPositiveElseTheDefault() {
        OffsetDateTime now = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(VoucherPolicy.expiry(now, null, 72)).isEqualTo(now.plusHours(72));
        assertThat(VoucherPolicy.expiry(now, 0, 72)).isEqualTo(now.plusHours(72));
        assertThat(VoucherPolicy.expiry(now, -5, 72)).isEqualTo(now.plusHours(72));
        assertThat(VoucherPolicy.expiry(now, 24, 72)).isEqualTo(now.plusHours(24));
    }

    @Test
    void expiredIsInclusiveOfTheDeadline() {
        OffsetDateTime expiresAt = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(VoucherPolicy.expired(expiresAt, expiresAt.minusSeconds(1))).isFalse();
        assertThat(VoucherPolicy.expired(expiresAt, expiresAt)).isTrue();
        assertThat(VoucherPolicy.expired(expiresAt, expiresAt.plusSeconds(1))).isTrue();
    }

    @Test
    void rollbackTargetDefaultsToSender() {
        assertThat(VoucherPolicy.rollbackToSender("SENDER")).isTrue();
        assertThat(VoucherPolicy.rollbackToSender(null)).isTrue();   // default returns to sender
        assertThat(VoucherPolicy.rollbackToSender("UNCLAIMED")).isFalse();
    }
}
