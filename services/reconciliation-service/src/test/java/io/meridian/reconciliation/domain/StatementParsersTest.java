package io.meridian.reconciliation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.reconciliation.domain.StatementLine.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** camt.053 / camt.054, MT940 / MT942 and CSV (bank statement, scheme settlement) sample files. */
class StatementParsersTest {

    static String sample(String name) throws IOException {
        try (InputStream in = StatementParsersTest.class.getResourceAsStream("/statements/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void camt053CarriesIdentificationBalancesAndAllReferences() throws Exception {
        ParsedStatement p = CamtStatementParser.parse(sample("camt053-sample.xml"));
        assertThat(p.statementId()).isEqualTo("STMT-2026-10-06-EUR");
        assertThat(p.sequenceNo()).isEqualTo("279");
        assertThat(p.accountId()).isEqualTo("DE89370400440532013000");
        assertThat(p.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(p.closingBalance()).isEqualByComparingTo("13662.50");
        assertThat(p.statementDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(p.lines()).as("the pending entry is skipped").hasSize(4);
        p.checkTotals(p.openingBalance(), p.closingBalance());

        StatementLine swift = p.lines().get(0);
        assertThat(swift.direction()).isEqualTo(Direction.CREDIT);
        assertThat(swift.amount()).isEqualByComparingTo("4987.50");
        assertThat(swift.externalRef()).isEqualTo("BR2026100600001");
        assertThat(swift.endToEndId()).isEqualTo("E2E-ACME-0001");
        assertThat(swift.counterparty()).isEqualTo("ACME Trading Ltd");
        assertThat(swift.narrative()).isEqualTo("Invoice INV-2026-00042");
        StatementLine.Details d = swift.details();
        assertThat(d.instructionId()).isEqualTo("INSTR-77");
        assertThat(d.uetr()).isEqualTo("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        assertThat(d.counterpartyAccount()).isEqualTo("GB29NWBK60161331926819");
        assertThat(d.charges()).isEqualByComparingTo("12.50");
        assertThat(d.originalAmount()).isEqualByComparingTo("5000.00");
        assertThat(d.originalCurrency()).isEqualTo("EUR");
        assertThat(d.transactionCode()).isEqualTo("PMNT/RCDT/XBCT");

        StatementLine batch = p.lines().get(1);
        assertThat(batch.direction()).isEqualTo(Direction.DEBIT);
        assertThat(batch.details().batchRef()).isEqualTo("BATCH-7Q2X9");
        assertThat(batch.details().batchCount()).isEqualTo(3);
        assertThat(batch.endToEndId()).as("a batch has no single end-to-end id").isNull();
        assertThat(batch.narrative()).isEqualTo("SEPA BULK DEBIT");

        StatementLine reversal = p.lines().get(2);
        assertThat(reversal.details().reversal()).isTrue();
        assertThat(reversal.endToEndId()).as("NOTPROVIDED is no reference").isNull();
        assertThat(reversal.narrative()).isEqualTo("RF18539007547034");

        StatementLine dtTm = p.lines().get(3);
        assertThat(dtTm.valueDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(dtTm.counterparty()).isEqualTo("Nordic Logistics AB");
        assertThat(dtTm.details().counterpartyAccount()).isEqualTo("SE4550000000058398257466");
    }

    @Test
    void camt053BalanceMismatchAndHostileXmlAreRejected() throws Exception {
        ParsedStatement p = CamtStatementParser.parse(sample("camt053-sample.xml").replace("13662.50", "13662.51"));
        assertThatThrownBy(() -> p.checkTotals(p.openingBalance(), p.closingBalance()))
                .isInstanceOf(ParsedStatement.ParseException.class).hasMessageContaining("Balance check failed");
        String xxe = """
                <?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Ntry>&x;</Ntry></Stmt></BkToCstmrStmt></Document>""";
        assertThatThrownBy(() -> CamtStatementParser.parse(xxe)).isInstanceOf(ParsedStatement.ParseException.class);
        assertThatThrownBy(() -> CamtStatementParser.parse("<Document/>"))
                .hasMessageContaining("not a camt");
    }

    @Test
    void camt054Notification() throws Exception {
        ParsedStatement p = CamtStatementParser.parse(sample("camt054-sample.xml"));
        assertThat(p.statementId()).isEqualTo("NTFCTN-17");
        assertThat(p.sequenceNo()).isEqualTo("17");
        assertThat(p.openingBalance()).isNull();
        assertThat(p.lines()).singleElement().satisfies(l -> {
            assertThat(l.endToEndId()).isEqualTo("E2E-NTF-1");
            assertThat(l.counterparty()).isEqualTo("Maria Rossi");
            assertThat(l.amount()).isEqualByComparingTo("320.00");
        });
    }

    @Test
    void mt940PagesStructuredInformationAndReversals() throws Exception {
        ParsedStatement p = Mt940Parser.parse(sample("mt940-sample.txt"));
        assertThat(p.statementId()).isEqualTo("STMT261006EUR");
        assertThat(p.sequenceNo()).isEqualTo("279/1");
        assertThat(p.accountId()).isEqualTo("DE89370400440532013000");
        assertThat(p.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(p.closingBalance()).as("closing balance of the last page").isEqualByComparingTo("13662.50");
        assertThat(p.lines()).hasSize(4);
        p.checkTotals(p.openingBalance(), p.closingBalance());

        StatementLine swift = p.lines().get(0);
        assertThat(swift.valueDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(swift.bookingDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(swift.currency()).isEqualTo("EUR");
        assertThat(swift.amount()).isEqualByComparingTo("4987.50");
        assertThat(swift.externalRef()).isEqualTo("BR2026100600001");
        assertThat(swift.endToEndId()).isEqualTo("E2E-ACME-0001");
        assertThat(swift.counterparty()).isEqualTo("ACME Trading Ltd");
        assertThat(swift.narrative()).isEqualTo("Invoice INV-2026-00042");
        assertThat(swift.details().instructionId()).isEqualTo("INSTR-77");
        assertThat(swift.details().uetr()).isEqualTo("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
        assertThat(swift.details().counterpartyAccount()).isEqualTo("GB29NWBK60161331926819");
        assertThat(swift.details().transactionCode()).isEqualTo("NTRF");

        StatementLine bulk = p.lines().get(1);
        assertThat(bulk.direction()).isEqualTo(Direction.DEBIT);
        assertThat(bulk.endToEndId()).as("NONREF").isNull();
        assertThat(bulk.externalRef()).isEqualTo("BATCH-7Q2X9");
        assertThat(bulk.narrative()).isEqualTo("SEPA BULK DEBIT 3 TXNS");

        StatementLine rd = p.lines().get(2);
        assertThat(rd.direction()).as("RD = reversal of a debit, a credit").isEqualTo(Direction.CREDIT);
        assertThat(rd.details().reversal()).isTrue();
        assertThat(rd.endToEndId()).as("EREF+ in ?20").isEqualTo("E2E-RET-5");
        assertThat(rd.counterparty()).isEqualTo("Mueller GmbH");
        assertThat(rd.details().counterpartyAccount()).isEqualTo("DE12500105170648489890");
        assertThat(rd.narrative()).contains("Rueckgabe AC04");

        assertThat(p.lines().get(3).narrative()).isEqualTo("Payment to Nordic Logistics AB");
    }

    @Test
    void mt942ControlTotalsAreChecked() throws Exception {
        ParsedStatement p = Mt940Parser.parse(sample("mt942-sample.txt"));
        assertThat(p.statementId()).isEqualTo("INTRA261006");
        assertThat(p.openingBalance()).isNull();
        assertThat(p.declaredDebits()).isEqualByComparingTo("99.90");
        assertThat(p.declaredCredits()).isEqualByComparingTo("250.00");
        assertThat(p.lines()).hasSize(2);
        assertThat(p.lines().getFirst().counterparty()).isEqualTo("Helios Media");
        p.checkTotals(null, null);
        ParsedStatement bad = Mt940Parser.parse(sample("mt942-sample.txt").replace(":90C:1EUR250,00", ":90C:1EUR251,00"));
        assertThatThrownBy(() -> bad.checkTotals(null, null)).hasMessageContaining("declared credits 251");
    }

    @Test
    void mt940MalformedLinesNameTheProblem() {
        assertThatThrownBy(() -> Mt940Parser.parse(":20:X\n:60F:C261005EUR1,00\n:61:26100XC1,00NTRF\n:62F:C261005EUR1,00\n"))
                .isInstanceOf(ParsedStatement.ParseException.class).hasMessageContaining(":61:");
        assertThatThrownBy(() -> Mt940Parser.parse("hello")).hasMessageContaining("No MT940");
        assertThatThrownBy(() -> Mt940Parser.parse(":20:X\n:61:2610061006C1,00NTRFX\n"))
                .hasMessageContaining("currency");
    }

    @Test
    void csvBankStatementWithPreambleSemicolonsAndDecimalComma() throws Exception {
        CsvStatementParser.Config cfg = new CsvStatementParser.Config(';', true, Map.of("booking_date", "Buchungstag",
                "value_date", "Valuta", "amount", "Betrag", "currency", "Waehrung", "narrative", "Verwendungszweck",
                "counterparty", "Auftraggeber", "external_ref", "Referenz", "end_to_end_id", "EndToEnd"),
                DateTimeFormatter.ofPattern("dd.MM.yyyy"), "EUR", Set.of("C"), Set.of("D"), ',', 2,
                CsvStatementParser.Config.REVERSAL_VALUES, Map.of());
        ParsedStatement p = CsvStatementParser.parse(sample("bank-statement-de.csv"), cfg);
        assertThat(p.lines()).hasSize(2);
        StatementLine in = p.lines().getFirst();
        assertThat(in.amount()).isEqualByComparingTo("1234.56");
        assertThat(in.direction()).isEqualTo(Direction.CREDIT);
        assertThat(in.narrative()).isEqualTo("Rechnung INV-2026-00077; danke");
        assertThat(in.endToEndId()).isEqualTo("E2E-77");
        StatementLine out = p.lines().get(1);
        assertThat(out.direction()).isEqualTo(Direction.DEBIT);
        assertThat(out.amount()).isEqualByComparingTo("89.90");
        assertThat(out.valueDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(out.bookingDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void schemeSettlementPreset() throws Exception {
        CsvStatementParser.Config d = CsvStatementParser.Config.defaults("EUR");
        CsvStatementParser.Config cfg = new CsvStatementParser.Config(',', true, Map.of(), d.dateFormat(), "EUR",
                d.creditValues(), d.debitValues(), '.', 0, d.reversalValues(), CsvStatementParser.SCHEME_SETTLEMENT);
        ParsedStatement p = CsvStatementParser.parse(sample("scheme-settlement.csv"), cfg);
        assertThat(p.lines()).hasSize(2);
        StatementLine visa = p.lines().getFirst();
        assertThat(visa.amount()).isEqualByComparingTo("9854.70");
        assertThat(visa.direction()).isEqualTo(Direction.CREDIT);
        assertThat(visa.externalRef()).isEqualTo("SETTLE-20261006-0042");
        assertThat(visa.details().batchRef()).isEqualTo("SETTLE-20261006-0042");
        assertThat(visa.details().charges()).isEqualByComparingTo(new BigDecimal("145.30"));
        assertThat(visa.details().originalAmount()).isEqualByComparingTo("10000.00");
        assertThat(visa.details().batchCount()).isEqualTo(57);
        assertThat(visa.narrative()).isEqualTo("VISA NET SETTLEMENT");
    }
}
