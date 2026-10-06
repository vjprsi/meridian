package io.meridian.cardclearing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettlementTest {

    @Test
    void netIsPurchasesMinusRefundsChargebacksInterchangePlusSchemeFees() {
        Settlement.Totals t = new Settlement.Totals()
                .add("PRESENTMENT", new BigDecimal("100.00"), new BigDecimal("1.15"), null)
                .add("PRESENTMENT", new BigDecimal("50.00"), new BigDecimal("0.58"), new BigDecimal("0.10"))
                .add("REFUND", new BigDecimal("20.00"), null, null)
                .add("CHARGEBACK", new BigDecimal("10.00"), null, null)
                .add("FEE_COLLECTION", new BigDecimal("3.00"), null, null)
                .add("REPRESENTMENT", new BigDecimal("10.00"), null, null);
        assertThat(t.count).isEqualTo(6);
        assertThat(t.purchases).isEqualByComparingTo("160.00");
        assertThat(t.refunds).isEqualByComparingTo("20.00");
        assertThat(t.chargebacks).isEqualByComparingTo("10.00");
        assertThat(t.interchange).isEqualByComparingTo("1.73");
        assertThat(t.schemeFees).isEqualByComparingTo("3.10");
        // 160 - 20 - 10 - 1.73 + 3.10
        assertThat(t.net()).isEqualByComparingTo("131.37");
    }

    @Test
    void interchangeRoundsHalfEvenToCents() {
        assertThat(Settlement.interchange(new BigDecimal("100"), new BigDecimal("0.0115"))).isEqualByComparingTo("1.15");
        // 21.7391... * 0.0115 = 0.25 (0.24999965)
        assertThat(Settlement.interchange(new BigDecimal("21.739100"), new BigDecimal("0.0115")).toPlainString())
                .isEqualTo("0.25");
        // exact half: 0.125 -> 0.12 (even), 0.135 -> 0.14
        assertThat(Settlement.interchange(new BigDecimal("12.5"), new BigDecimal("0.01")).toPlainString()).isEqualTo("0.12");
        assertThat(Settlement.interchange(new BigDecimal("13.5"), new BigDecimal("0.01")).toPlainString()).isEqualTo("0.14");
    }
}
