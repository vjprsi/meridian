package io.meridian.collections.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class NextBestActionModelTest {
    static final OffsetDateTime NOON = OffsetDateTime.parse("2026-10-06T12:00:00Z");

    private static NextBestActionModel.Signals signals(int dpd, int broken, Integer sincePayment, int rpc, int noAnswer,
                                                       int refused, int contacts, boolean disputed, boolean vulnerable,
                                                       boolean promise, OffsetDateTime now) {
        return new NextBestActionModel.Signals(dpd, new BigDecimal("300.00"), new BigDecimal("5000.00"), "EUR", broken,
                sincePayment, rpc, noAnswer, refused, contacts, 3, contacts > 0 ? now.plusDays(2) : null, disputed,
                vulnerable, promise, false, LocalTime.of(21, 0), LocalTime.of(8, 0), now);
    }

    @Test
    void earlyDelinquencyWithRecentPaymentGetsADigitalNudge() {
        var s = NextBestActionModel.suggest(signals(5, 0, 10, 0, 0, 0, 0, false, false, false, NOON));
        assertThat(s.action()).isIn("SMS", "PUSH");
        assertThat(s.propensity()).isGreaterThan(new BigDecimal("0.7"));
        assertThat(s.reasons()).extracting(NextBestActionModel.ReasonCode::code).contains("RECENT_PAYMENT");
        assertThat(s.offer()).isNull();
        assertThat(s.at()).isEqualTo(NOON);
    }

    @Test
    void deepDelinquencyWithBrokenPromisesEscalatesToLegalWithAnExplainedOffer() {
        var s = NextBestActionModel.suggest(signals(130, 3, 200, 0, 4, 2, 0, false, false, false, NOON));
        assertThat(s.action()).isEqualTo("LEGAL_NOTICE");
        assertThat(s.propensity()).isLessThan(new BigDecimal("0.3"));
        assertThat(s.offer()).containsEntry("type", "SETTLEMENT").containsEntry("settlement_amount", "4000.00");
        assertThat(s.reasons().getFirst().code()).isEqualTo("DAYS_PAST_DUE");
        assertThat(s.explanation()).contains("LEGAL_NOTICE").contains("DAYS_PAST_DUE");
        assertThat(s.alternatives()).hasSize(7);
    }

    @Test
    void disputedDebtGetsWrittenCommunicationOnly() {
        var s = NextBestActionModel.suggest(signals(130, 3, null, 0, 4, 2, 0, true, false, false, NOON));
        assertThat(s.action()).isIn("LETTER", "EMAIL");
        assertThat(s.reasons().getFirst().code()).isEqualTo("DEBT_DISPUTED");
        assertThat(s.alternatives()).filteredOn(a -> a.action().equals("LEGAL_NOTICE"))
                .allMatch(a -> "DEBT_DISPUTED".equals(a.excludedBy()));
        assertThat(s.offer()).isNull();
    }

    @Test
    void vulnerableCustomerNeverGetsLegalOrFieldVisit() {
        var s = NextBestActionModel.suggest(signals(130, 3, 200, 0, 4, 2, 0, false, true, false, NOON));
        assertThat(s.action()).isNotIn("LEGAL_NOTICE", "FIELD_VISIT");
        assertThat(s.reasons()).extracting(NextBestActionModel.ReasonCode::code).contains("VULNERABLE_CUSTOMER");
    }

    @Test
    void contactCapDefersAndQuietHoursAreRespected() {
        var s = NextBestActionModel.suggest(signals(20, 0, 40, 1, 0, 0, 3, false, false, false, NOON));
        assertThat(s.reasons().getFirst().code()).isEqualTo("CONTACT_CAP_REACHED");
        assertThat(s.at()).isAfterOrEqualTo(NOON.plusDays(2));
        var night = NextBestActionModel.suggest(signals(20, 0, 40, 1, 0, 0, 0, false, false, false,
                OffsetDateTime.parse("2026-10-06T22:30:00Z")));
        assertThat(night.at()).isEqualTo(OffsetDateTime.parse("2026-10-07T08:00:00Z"));
    }

    @Test
    void activePromiseFavoursAReminder() {
        var s = NextBestActionModel.suggest(signals(40, 0, 40, 2, 0, 0, 0, false, false, true, NOON));
        assertThat(s.action()).isEqualTo("SMS");
        assertThat(s.reasons()).extracting(NextBestActionModel.ReasonCode::code).contains("ACTIVE_PROMISE");
    }

    @Test
    void deterministic() {
        var a = NextBestActionModel.suggest(signals(65, 2, 100, 1, 3, 0, 1, false, false, false, NOON));
        var b = NextBestActionModel.suggest(signals(65, 2, 100, 1, 3, 0, 1, false, false, false, NOON));
        assertThat(a).isEqualTo(b);
        assertThat(a.offer()).containsEntry("type", "PAYMENT_PLAN").containsEntry("installment_amount", "50.00");
    }

    @Test
    void quietHoursWindowsThatDoNotWrapMidnight() {
        assertThat(NextBestActionModel.outsideQuietHours(OffsetDateTime.parse("2026-10-06T13:30:00Z"),
                LocalTime.of(13, 0), LocalTime.of(14, 0))).isEqualTo(OffsetDateTime.parse("2026-10-06T14:00:00Z"));
        assertThat(NextBestActionModel.outsideQuietHours(OffsetDateTime.parse("2026-10-06T06:00:00Z"),
                LocalTime.of(21, 0), LocalTime.of(8, 0))).isEqualTo(OffsetDateTime.parse("2026-10-06T08:00:00Z"));
    }
}
