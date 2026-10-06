package io.meridian.cardclearing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClearingFileParserTest {

    @Test
    void parsesCsvWithQuotingAndDefaults() {
        String csv = "record_type,card_id,network_transaction_id,merchant_name,transaction_date,transaction_amount,"
                + "transaction_currency\r\n"
                + "PRESENTMENT,0199a000-0000-7000-8000-000000000001,NT1,\"Shop, \"\"The Best\"\"\",2026-10-01,25.50,usd\r\n"
                + "REFUND,,NT2,\"Multi\nline\",2026-10-02,5,EUR\n\n";
        ClearingFileParser.Result r = ClearingFileParser.parseCsv(csv);
        assertThat(r.errors()).isEmpty();
        assertThat(r.records()).hasSize(2);
        ParsedRecord p = r.records().getFirst();
        assertThat(p.merchantName()).isEqualTo("Shop, \"The Best\"");
        assertThat(p.transactionCurrency()).isEqualTo("USD");
        assertThat(p.billingAmount()).isEqualByComparingTo("25.50");
        assertThat(p.billingCurrency()).isEqualTo("USD");
        assertThat(p.settlementAmount()).isEqualByComparingTo("25.50");
        assertThat(p.settlementCurrency()).isEqualTo("USD");
        assertThat(p.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(r.records().get(1).merchantName()).isEqualTo("Multi\nline");
        assertThat(r.records().get(1).cardId()).isNull();
    }

    @Test
    void refusesPanColumnsAndReportsEveryRowError() {
        ClearingFileParser.Result r = ClearingFileParser.parseRows(List.of(
                Map.of("record_type", "PRESENTMENT", "pan", "4111111111111111", "transaction_date", "2026-10-01",
                        "transaction_amount", "10", "transaction_currency", "USD"),
                Map.of("record_type", "BOGUS", "transaction_date", "01/10/2026", "transaction_amount", "-1",
                        "transaction_currency", "US")));
        assertThat(r.ok()).isFalse();
        assertThat(r.records()).isEmpty();
        assertThat(String.join("\n", r.errors()))
                .contains("row 1: field 'pan' is not accepted")
                .contains("row 2: record_type BOGUS")
                .contains("row 2: transaction_date")
                .contains("row 2: transaction_amount must be greater than 0")
                .contains("row 2: transaction_currency must be an ISO 4217 code")
                .doesNotContain("4111111111111111");
    }

    @Test
    void crossCurrencyNeedsBillingAmount() {
        ClearingFileParser.Result r = ClearingFileParser.parseRows(List.of(Map.of("record_type", "PRESENTMENT",
                "transaction_date", "2026-10-01", "transaction_amount", "92", "transaction_currency", "EUR",
                "billing_currency", "USD")));
        assertThat(r.errors()).singleElement().asString().contains("billing_amount is required");
        ClearingFileParser.Result ok = ClearingFileParser.parseRows(List.of(Map.of("record_type", "PRESENTMENT",
                "transaction_date", "2026-10-01", "transaction_amount", "92", "transaction_currency", "EUR",
                "billing_currency", "USD", "billing_amount", "100.00")));
        assertThat(ok.records().getFirst().isCrossCurrency()).isTrue();
        assertThat(ok.records().getFirst().settlementAmount()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void emptyAndMalformedFiles() {
        assertThat(ClearingFileParser.parseCsv("").errors()).isNotEmpty();
        assertThat(ClearingFileParser.parseCsv("record_type,transaction_date\n").errors())
                .containsExactly("file contains no records");
        assertThat(ClearingFileParser.parseCsv("a,b\n\"unterminated,1\n").errors())
                .containsExactly("unterminated quoted field");
        assertThat(ClearingFileParser.parseCsv("a,b\n1,2,3\n").errors().getFirst()).contains("expected 2 fields");
    }
}
