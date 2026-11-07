package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Colombia GMF 4x1000 and IVA on the fee. */
class TransactionTaxesTest {
    private static final JsonNode CO = JsonMapper.builder().build().readTree("""
            {"scale": 0, "gmf": {"rate": "0.004", "directions": ["OUTBOUND", "INTERNAL"], "exempt_same_holder": true,
                                  "exempt_monthly_amount": "18330900"},
             "fee_vat": {"rate": "0.19"}}""");

    private static TransactionTaxes.Facts f(String dir, String amount, String fee, boolean same, boolean exemptAcct, String used) {
        return new TransactionTaxes.Facts(dir, "INSTANT", new BigDecimal(amount), new BigDecimal(fee), same, exemptAcct,
                new BigDecimal(used));
    }

    @Test
    void gmfIsFourPerThousandOfTheDebitRoundedToPesos() {
        List<TransactionTaxes.Line> l = TransactionTaxes.compute(CO, f("OUTBOUND", "150000", "0", false, false, "0"));
        assertThat(l).hasSize(1);
        assertThat(l.get(0).taxType()).isEqualTo("FINANCIAL_TRANSACTION_TAX");
        assertThat(l.get(0).amount()).isEqualByComparingTo("600");
        assertThat(TransactionTaxes.compute(CO, f("OUTBOUND", "1250", "0", false, false, "0")).get(0).amount())
                .isEqualByComparingTo("5"); // 5.0
        assertThat(TransactionTaxes.compute(CO, f("OUTBOUND", "1000", "0", false, false, "0")).get(0).amount())
                .isEqualByComparingTo("4");
    }

    @Test
    void sameHolderTransfersAreExempt() {
        TransactionTaxes.Line g = TransactionTaxes.compute(CO, f("INTERNAL", "9500000", "0", true, false, "0")).get(0);
        assertThat(g.amount()).isEqualByComparingTo("0");
        assertThat(g.exemptAmount()).isEqualByComparingTo("9500000");
        assertThat(g.reason()).contains("879");
    }

    @Test
    void exemptAccountUsesTheMonthlyAllowanceThenPays() {
        // 18,330,900 allowance; 15,000,000 already used this month; 5,000,000 now -> 3,330,900 exempt, 1,669,100 taxed
        TransactionTaxes.Line g = TransactionTaxes.compute(CO, f("OUTBOUND", "5000000", "0", false, true, "15000000")).get(0);
        assertThat(g.exemptAmount()).isEqualByComparingTo("3330900");
        assertThat(g.amount()).isEqualByComparingTo("6676"); // 1,669,100 x 0.004 = 6,676.4
        assertThat(TransactionTaxes.compute(CO, f("OUTBOUND", "100000", "0", false, true, "0")).get(0).amount())
                .isEqualByComparingTo("0");
        assertThat(TransactionTaxes.compute(CO, f("OUTBOUND", "100000", "0", false, true, "18330900")).get(0).amount())
                .isEqualByComparingTo("400");
    }

    @Test
    void ivaOnTheFeeAndNothingInbound() {
        List<TransactionTaxes.Line> l = TransactionTaxes.compute(CO, f("OUTBOUND", "100000", "7500", false, false, "0"));
        assertThat(l).extracting(TransactionTaxes.Line::taxType).containsExactly("FINANCIAL_TRANSACTION_TAX", "VAT");
        assertThat(l.get(1).amount()).isEqualByComparingTo("1425");
        assertThat(TransactionTaxes.total(l)).isEqualByComparingTo("1825");
        assertThat(TransactionTaxes.compute(CO, f("INBOUND", "100000", "0", false, false, "0"))).isEmpty();
        assertThat(TransactionTaxes.compute(null, f("OUTBOUND", "100000", "7500", false, false, "0"))).isEmpty();
    }
}
