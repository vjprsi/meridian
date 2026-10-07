package io.meridian.merchantchannel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Refund cap and fee statement rules (pure). */
class MerchantDomainTest {

    @Test
    void theRefundCapCountsCompletedAndPendingRefunds() {
        RefundCap cap = new RefundCap(new BigDecimal("150.00"), new BigDecimal("100.00"), new BigDecimal("20.00"));
        assertThat(cap.refundable()).isEqualByComparingTo("30.00");
        assertThat(cap.allows(new BigDecimal("30.00"))).isTrue();
        assertThat(cap.allows(new BigDecimal("30.01"))).isFalse();
        assertThat(cap.allows(BigDecimal.ZERO)).isFalse();
        assertThat(cap.allows(null)).isFalse();
        assertThat(new RefundCap(new BigDecimal("10"), new BigDecimal("12"), null).refundable()).isEqualByComparingTo("0");
    }

    @Test
    void theFeeStatementTotalsSettlementsOfTheRangeAndNeverEmitsFormulas() {
        assertThat(FeeStatement.inRange(LocalDate.parse("2026-09-30"), "PAID", LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-30"))).isTrue();
        assertThat(FeeStatement.inRange(LocalDate.parse("2026-10-01"), "PAID", null, LocalDate.parse("2026-09-30")))
                .isFalse();
        assertThat(FeeStatement.inRange(LocalDate.parse("2026-09-30"), "REJECTED", null, null)).isFalse();
        List<FeeStatement.Line> lines = List.of(
                new FeeStatement.Line("s1", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), "PAID", 5,
                        new BigDecimal("1000"), new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("990")),
                new FeeStatement.Line("s2", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), "APPROVED", 2,
                        new BigDecimal("200"), new BigDecimal("2"), new BigDecimal("20"), new BigDecimal("178")));
        FeeStatement.Totals t = FeeStatement.totals(lines);
        assertThat(t.gross()).isEqualByComparingTo("1200.00");
        assertThat(t.mdr()).isEqualByComparingTo("12.00");
        assertThat(t.payments()).isEqualTo(7);
        assertThat(t.effectiveRate()).isEqualByComparingTo("0.0100");
        String csv = FeeStatement.csv("=HYPERLINK(\"x\")", "KES", lines);
        assertThat(csv).startsWith("merchant_code,settlement_id,");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\",s1,2026-09-01,2026-09-30,PAID,KES,5,1000.00,10.00,0.00,990.00");
        assertThat(csv.lines().toList().getLast()).contains(",TOTAL,").endsWith(",7,1200.00,12.00,20.00,1168.00");
    }
}
