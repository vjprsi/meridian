package io.meridian.disbursement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeneficiaryCsvParserTest {
    final BeneficiaryCsvParser parser = new BeneficiaryCsvParser("254", 100);

    @Test
    void headerWithAliasesInAnyOrderAndQuotedFields() {
        String csv = "Name,Phone,Amount,ID Number,Reference,Description\r\n"
                + "\"Kamau, John\",+254712000001,1500.50,ID1,REF-1,\"Term 1 \"\"bonus\"\"\"\r\n"
                + "Achieng Otieno,0712 000-002,200,ID2,REF-2,\"multi\nline\"\r\n";
        List<BeneficiaryCsvParser.Row> rows = parser.parse(csv);
        assertThat(rows).hasSize(2);
        BeneficiaryCsvParser.Row r1 = rows.get(0);
        assertThat(r1.lineNo()).isEqualTo(1);
        assertThat(r1.fullName()).isEqualTo("Kamau, John");
        assertThat(r1.msisdn()).isEqualTo("+254712000001");
        assertThat(r1.amount()).isEqualByComparingTo("1500.50");
        assertThat(r1.nationalId()).isEqualTo("ID1");
        assertThat(r1.beneficiaryReference()).isEqualTo("REF-1");
        assertThat(r1.narrative()).isEqualTo("Term 1 \"bonus\"");
        assertThat(r1.valid()).isTrue();
        BeneficiaryCsvParser.Row r2 = rows.get(1);
        assertThat(r2.msisdn()).isEqualTo("+254712000002");
        assertThat(r2.narrative()).isEqualTo("multi\nline");
    }

    @Test
    void positionalColumnsWithoutHeaderAndSemicolonDelimiter() {
        List<BeneficiaryCsvParser.Row> rows = parser.parse("+254712000001;10;Jane Doe\n+254712000002;20\n");
        assertThat(rows).extracting(BeneficiaryCsvParser.Row::amount)
                .containsExactly(new BigDecimal("10"), new BigDecimal("20"));
        assertThat(rows.getFirst().fullName()).isEqualTo("Jane Doe");
        assertThat(rows.get(1).fullName()).isNull();
    }

    @Test
    void rowErrorsAreKeptNotFatal() {
        String csv = "msisdn,amount\n12345,10\n+254712000003,-5\n+254712000004,1.005\n+254712000005,abc\n,7\n+254712000006,\n\n";
        List<BeneficiaryCsvParser.Row> rows = parser.parse(csv);
        assertThat(rows).hasSize(6);
        assertThat(rows).allMatch(r -> !r.valid() && r.error().startsWith(BeneficiaryCsvParser.ROW_ERROR_PREFIX));
        assertThat(rows.get(0).error()).contains("not a valid E.164");
        assertThat(rows.get(0).msisdn()).isEqualTo("12345");
        assertThat(rows.get(1).error()).contains("positive");
        assertThat(rows.get(2).error()).contains("2 decimals");
        assertThat(rows.get(3).error()).contains("not a number");
        assertThat(rows.get(4).error()).contains("msisdn is missing");
        assertThat(rows.get(5).error()).contains("amount is missing");
    }

    @Test
    void trailingZerosAndThousandsSeparatorsAreAccepted() {
        List<BeneficiaryCsvParser.Row> rows = parser.parse("msisdn,amount\n+254712000001,\"1,000.500\"\n");
        assertThat(rows.getFirst().valid()).isTrue();
        assertThat(rows.getFirst().amount()).isEqualByComparingTo("1000.5");
    }

    @Test
    void bomIsIgnored() {
        assertThat(parser.parse("﻿msisdn,amount\n+254712000001,5\n")).hasSize(1);
    }

    @Test
    void fileLevelProblemsAreRejected() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(BeneficiaryCsvParser.FileFormatException.class);
        assertThatThrownBy(() -> parser.parse("msisdn,amount\n"))
                .isInstanceOf(BeneficiaryCsvParser.FileFormatException.class).hasMessageContaining("no beneficiary rows");
        assertThatThrownBy(() -> parser.parse("name,amount\nJohn,5\n"))
                .isInstanceOf(BeneficiaryCsvParser.FileFormatException.class).hasMessageContaining("msisdn");
        StringBuilder big = new StringBuilder("msisdn,amount\n");
        for (int i = 0; i < 101; i++) {
            big.append("+254712000001,1\n");
        }
        assertThatThrownBy(() -> parser.parse(big.toString())).hasMessageContaining("more than 100");
    }

    @Test
    void msisdnNormalisation() {
        assertThat(Msisdn.normalize("+254 712-345 678", null)).contains("+254712345678");
        assertThat(Msisdn.normalize("00254712345678", null)).contains("+254712345678");
        assertThat(Msisdn.normalize("0712345678", "254")).contains("+254712345678");
        assertThat(Msisdn.normalize("254712345678", "254")).contains("+254712345678");
        assertThat(Msisdn.normalize("0712345678", null)).isEmpty();
        assertThat(Msisdn.normalize("+0712", null)).isEmpty();
        assertThat(Msisdn.normalize("  ", "254")).isEmpty();
    }
}
