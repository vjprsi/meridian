package io.meridian.carddispute.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import org.junit.jupiter.api.Test;

/** Deadlines, reason codes, summary determinism and settlement balancing. */
class DisputeRulesTest {

    @Test
    void schemeWindows() {
        LocalDate tx = LocalDate.of(2026, 6, 1);
        assertThat(DisputeDeadlines.chargebackFiling("VISA", tx, LocalDate.of(2026, 7, 1))).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(DisputeDeadlines.chargebackFiling("VISA", null, LocalDate.of(2026, 7, 1))).isEqualTo(LocalDate.of(2026, 10, 29));
        LocalDate d = LocalDate.of(2026, 10, 6);
        assertThat(DisputeDeadlines.representment("VISA", d)).isEqualTo(d.plusDays(30));
        assertThat(DisputeDeadlines.representment("MASTERCARD", d)).isEqualTo(d.plusDays(45));
        assertThat(DisputeDeadlines.retrieval(d)).isEqualTo(d.plusDays(30));
        assertThat(DisputeDeadlines.preArbitration(d)).isEqualTo(d.plusDays(30));
        assertThat(DisputeDeadlines.arbitration(d)).isEqualTo(d.plusDays(10));
        assertThat(DisputeDeadlines.regulatory(d)).isEqualTo(d.plusDays(45));
    }

    @Test
    void businessDaysSkipWeekends() {
        // Friday 2026-10-09 + 1 business day = Monday 2026-10-12
        assertThat(DisputeDeadlines.plusBusinessDays(LocalDate.of(2026, 10, 9), 1)).isEqualTo(LocalDate.of(2026, 10, 12));
        // Tuesday 2026-10-06 + 10 business days = Tuesday 2026-10-20
        assertThat(DisputeDeadlines.provisionalCreditDecision(LocalDate.of(2026, 10, 6))).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void approachingAndPassed() {
        LocalDate asOf = LocalDate.of(2026, 10, 6);
        assertThat(DisputeDeadlines.approaching(asOf, asOf)).isTrue();
        assertThat(DisputeDeadlines.approaching(asOf.plusDays(3), asOf)).isTrue();
        assertThat(DisputeDeadlines.approaching(asOf.plusDays(4), asOf)).isFalse();
        assertThat(DisputeDeadlines.approaching(asOf.minusDays(1), asOf)).isFalse();
        assertThat(DisputeDeadlines.passed(asOf.minusDays(1), asOf)).isTrue();
        assertThat(DisputeDeadlines.passed(asOf, asOf)).isFalse();
    }

    @Test
    void reasonCodesPerScheme() {
        assertThat(ReasonCodes.suggest("FRAUD", "VISA")).isEqualTo("10.4");
        assertThat(ReasonCodes.suggest("FRAUD", "MASTERCARD")).isEqualTo("4837");
        assertThat(ReasonCodes.suggest("NOT_RECEIVED", "MASTERCARD")).isEqualTo("4855");
        assertThat(ReasonCodes.suggest("DUPLICATE", "VISA")).isEqualTo("12.6.1");
        assertThat(ReasonCodes.suggest("CREDIT_NOT_PROCESSED", "VISA")).isEqualTo("13.6");
        assertThat(ReasonCodes.fits("VISA", "10.4")).isTrue();
        assertThat(ReasonCodes.fits("VISA", "12.6.1")).isTrue();
        assertThat(ReasonCodes.fits("VISA", "4837")).isFalse();
        assertThat(ReasonCodes.fits("MASTERCARD", "4837")).isTrue();
        assertThat(ReasonCodes.fits("MASTERCARD", "13.1")).isFalse();
        assertThat(ReasonCodes.fits("VISA", null)).isFalse();
        for (String category : new String[] {"FRAUD", "AUTHORIZATION", "PROCESSING_ERROR", "CONSUMER_DISPUTE",
                "NOT_RECEIVED", "NOT_AS_DESCRIBED", "DUPLICATE", "CANCELLED_RECURRING", "CREDIT_NOT_PROCESSED",
                "ATM_DISPUTE"}) {
            assertThat(ReasonCodes.fits("VISA", ReasonCodes.suggest(category, "VISA"))).as(category).isTrue();
            assertThat(ReasonCodes.fits("MASTERCARD", ReasonCodes.suggest(category, "MASTERCARD"))).as(category).isTrue();
        }
    }

    @Test
    void summaryIsDeterministic() {
        DisputeSummary.Facts f = new DisputeSummary.Facts("NOT_RECEIVED", "VISA", null, "INQUIRY", "OPEN",
                new BigDecimal("120.5"), "USD", new BigDecimal("120.5"), false, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 30), LocalDate.of(2026, 11, 20), 2, 0, "  Ordered   shoes, never arrived ");
        String a = DisputeSummary.render(f);
        assertThat(a).isEqualTo(DisputeSummary.render(f));
        assertThat(a).contains("13.1", "120.50 USD", "(outstanding)", "Ordered shoes, never arrived",
                "submit the chargeback with the evidence on file", "2026-12-30");
        assertThat(DisputeSummary.suggestedCode(f)).isEqualTo("13.1");
    }

    @Property
    void settlementAlwaysBalances(@ForAll @BigRange(min = "0", max = "1000") BigDecimal provisional,
                                  @ForAll @BigRange(min = "0", max = "1000") BigDecimal recovered,
                                  @ForAll boolean customerLiable) {
        BigDecimal total = customerLiable ? recovered : recovered.max(provisional);
        Settlement s = new Settlement(provisional, recovered, total);
        assertThat(s.totalDebits()).isEqualByComparingTo(s.totalCredits());
        assertThat(s.writeOff().signum()).isGreaterThanOrEqualTo(0);
        if (customerLiable) {
            assertThat(s.writeOff()).isEqualByComparingTo("0");
        }
    }

    @Test
    void pureReversalWhenNothingRecoveredAndCustomerLiable() {
        Settlement s = new Settlement(new BigDecimal("50"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(s.isPureReversal()).isTrue();
        assertThat(s.cardholderDelta()).isEqualByComparingTo("-50");
        Settlement won = new Settlement(new BigDecimal("50"), new BigDecimal("80"), new BigDecimal("80"));
        assertThat(won.isPureReversal()).isFalse();
        assertThat(won.cardholderDelta()).isEqualByComparingTo("30");
    }
}
