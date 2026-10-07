package io.meridian.businesschannel.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BatchCsvTest {

    @Test
    void validLinesAreNormalisedAndInvalidOnesReported() {
        BatchCsv.Batch b = BatchCsv.parse("""
                creditor_name,creditor_account_number,amount,creditor_agent_bic
                "Smith, J",gb82 west 1234 5698 7654 32,10.50,westgb2l
                X,GB00WEST12345698765432,1,
                Y,123,-1,
                """, "GBP", 10);
        assertThat(b.lines()).hasSize(3);
        assertThat(b.lines().get(0).get("creditor_account_number")).isEqualTo("GB82WEST12345698765432");
        assertThat(b.lines().get(0).get("creditor_account_id_type")).isEqualTo("IBAN");
        assertThat(b.lines().get(0).get("creditor_agent_bic")).isEqualTo("WESTGB2L");
        assertThat(b.invalidLines()).isEqualTo(2);
        assertThat(b.total()).isEqualByComparingTo(new BigDecimal("10.50"));
        assertThat(b.errors()).extracting(BatchCsv.LineError::code).contains("invalid_iban", "invalid_amount");
        assertThat(BatchCsv.render(b.lines().subList(0, 1), "GBP")).contains("\"Smith, J\"", "\"GBP\"");
    }

    @Test
    void fileProblemsAreRefused() {
        assertThatThrownBy(() -> BatchCsv.parse("a,b\n1,2\n", "KES", 10)).isInstanceOf(BatchCsv.FileProblem.class);
        assertThatThrownBy(() -> BatchCsv.parse("creditor_name,creditor_account_number,amount\n", "KES", 10))
                .isInstanceOf(BatchCsv.FileProblem.class);
        assertThatThrownBy(() -> BatchCsv.parse("creditor_name,creditor_account_number,amount\nA,1,1\nB,2,2\n", "KES", 1))
                .isInstanceOf(BatchCsv.FileProblem.class);
        assertThatThrownBy(() -> BatchCsv.parse("creditor_name,creditor_account_number,amount\n\"A,1,1\n", "KES", 5))
                .isInstanceOf(BatchCsv.FileProblem.class);
    }
}
