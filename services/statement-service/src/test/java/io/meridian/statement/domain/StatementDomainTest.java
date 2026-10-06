package io.meridian.statement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StatementDomainTest {
    static final LocalDate START = LocalDate.of(2026, 9, 1);
    static final LocalDate END = LocalDate.of(2026, 9, 30);

    static StatementDocument doc(List<StatementDocument.Line> lines, BigDecimal opening) {
        StatementDocument.Totals t = StatementDocument.totals(opening, lines);
        return new StatementDocument("0199a000-0000-7000-8000-000000000123", 7, "12345678", "GB82WEST12345698765432",
                "EUR", START, END, opening, t.credits(), t.debits(), t.closing(), new BigDecimal("1.25"),
                new BigDecimal("20"), OffsetDateTime.parse("2026-10-01T02:00:00Z"), "en", lines);
    }

    static List<StatementDocument.Line> sample() {
        return List.of(
                new StatementDocument.Line(1, LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), "Salary, ACME \"Ltd\"",
                        "REF-1", null, "CREDIT", new BigDecimal("500.00"), new BigDecimal("1500.00"), "e1", "PMNT-RCDT-BOOK"),
                new StatementDocument.Line(2, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 9), "Monthly fee <maint>",
                        null, null, "DEBIT", new BigDecimal("20.00"), new BigDecimal("1480.00"), "e2", "ACMT-MDOP-CHRG"),
                new StatementDocument.Line(3, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), "Credit interest",
                        null, null, "CREDIT", new BigDecimal("1.25"), new BigDecimal("1481.25"), "e3", "ACMT-MDOP-INTR"));
    }

    @Test
    void totalsAndChaining() {
        StatementDocument d = doc(sample(), new BigDecimal("1000.00"));
        assertThat(d.totalCredits()).isEqualByComparingTo("501.25");
        assertThat(d.totalDebits()).isEqualByComparingTo("20.00");
        assertThat(d.closingBalance()).isEqualByComparingTo("1481.25");
        assertThat(StatementDocument.chained(new BigDecimal("1000.00"), sample())).isTrue();
        assertThat(StatementDocument.chained(new BigDecimal("999.00"), sample())).isFalse();
    }

    @Test
    void csvEscapesAndCarriesBalances() {
        String csv = new String(StatementRenderer.render(doc(sample(), new BigDecimal("1000")), "CSV").content(),
                StandardCharsets.UTF_8);
        String[] rows = csv.split("\r\n");
        assertThat(rows).hasSize(6);
        assertThat(rows[1]).contains("Opening balance").contains("1000.00");
        assertThat(rows[2]).contains("\"Salary, ACME \"\"Ltd\"\"\"");
        assertThat(rows[5]).contains("Closing balance").contains("1481.25");
    }

    @Test
    void camt053HasBalancesSummaryAndEntries() {
        String xml = new String(StatementRenderer.render(doc(sample(), new BigDecimal("1000")), "CAMT_053").content(),
                StandardCharsets.UTF_8);
        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:camt.053.001.08")
                .contains("<IBAN>GB82WEST12345698765432</IBAN>")
                .contains("<Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"EUR\">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd>")
                .contains("<Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"EUR\">1481.25</Amt>")
                .contains("<TtlCdtNtries><NbOfNtries>2</NbOfNtries><Sum>501.25</Sum>")
                .contains("Monthly fee &lt;maint&gt;")
                .doesNotContain("<maint>");
        assertThat(count(xml, "<Ntry>")).isEqualTo(3);
        assertThat(count(xml, "<CdtDbtInd>DBIT</CdtDbtInd>")).isEqualTo(1);
    }

    @Test
    void mt940AndOfxAndJson() throws Exception {
        StatementDocument d = doc(sample(), new BigDecimal("1000"));
        String mt = new String(StatementRenderer.render(d, "MT940").content(), StandardCharsets.UTF_8);
        assertThat(mt).contains(":60F:C260901EUR1000,00").contains(":61:2609090910D20,00NTRF").contains(":62F:C260930EUR1481,25");
        String ofx = new String(StatementRenderer.render(d, "OFX").content(), StandardCharsets.UTF_8);
        assertThat(ofx).contains("<TRNAMT>-20.00</TRNAMT>").contains("<BALAMT>1481.25</BALAMT>");
        JsonNode json = JsonMapper.builder().build().readTree(StatementRenderer.render(d, "JSON").content());
        assertThat(json.get("lines")).hasSize(3);
        assertThat(json.get("lines").get(0).get("description").asString()).isEqualTo("Salary, ACME \"Ltd\"");
        assertThat(json.get("closing_balance").asString()).isEqualTo("1481.25");
    }

    @Test
    void pdfIsStructurallyValidWithCorrectXrefOffsets() {
        List<StatementDocument.Line> many = new ArrayList<>();
        BigDecimal bal = new BigDecimal("100.00");
        for (int i = 1; i <= 130; i++) {
            bal = bal.add(BigDecimal.ONE);
            many.add(new StatementDocument.Line(i, START, START, "Payment (" + i + ") \\ ok é", null, null, "CREDIT",
                    BigDecimal.ONE, bal, "e" + i, "PMNT-RCDT-OTHR"));
        }
        byte[] pdf = StatementRenderer.render(doc(many, new BigDecimal("100.00")), "PDF").content();
        String s = new String(pdf, StandardCharsets.US_ASCII);
        assertThat(s).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 3").contains("\\(1\\)");
        Matcher xref = Pattern.compile("startxref\n(\\d+)").matcher(s);
        assertThat(xref.find()).isTrue();
        int xrefAt = Integer.parseInt(xref.group(1));
        assertThat(s.substring(xrefAt)).startsWith("xref");
        Matcher offs = Pattern.compile("(\\d{10}) 00000 n").matcher(s.substring(xrefAt));
        int obj = 1;
        while (offs.find()) {
            assertThat(s.substring(Integer.parseInt(offs.group(1)))).startsWith(obj + " 0 obj");
            obj++;
        }
        assertThat(obj - 1).isEqualTo(3 + 2 * 3);
    }

    @Test
    void renderingIsDeterministic() {
        StatementDocument d = doc(sample(), new BigDecimal("1000"));
        for (String f : StatementPeriods.FORMATS) {
            assertThat(StatementRenderer.render(d, f).sha256()).isEqualTo(StatementRenderer.render(d, f).sha256()).hasSize(64);
        }
    }

    @Test
    void cyclesAndValidation() {
        assertThat(StatementPeriods.cycleEndingOn("MONTHLY", 31, LocalDate.of(2026, 2, 28)))
                .contains(new StatementPeriods.Period(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertThat(StatementPeriods.cycleEndingOn("MONTHLY", 31, LocalDate.of(2026, 3, 30))).isEmpty();
        assertThat(StatementPeriods.cycleEndingOn("MONTHLY", 15, LocalDate.of(2026, 3, 15)))
                .contains(new StatementPeriods.Period(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 3, 15)));
        assertThat(StatementPeriods.cycleEndingOn("QUARTERLY", 31, LocalDate.of(2026, 6, 30)))
                .contains(new StatementPeriods.Period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30)));
        assertThat(StatementPeriods.cycleEndingOn("QUARTERLY", 31, LocalDate.of(2026, 5, 31))).isEmpty();
        assertThat(StatementPeriods.cycleEndingOn("WEEKLY", 5, LocalDate.of(2026, 10, 2)))
                .contains(new StatementPeriods.Period(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 10, 2)));
        assertThat(StatementPeriods.cycleEndingOn("ONCE", 1, LocalDate.of(2026, 10, 1))).isEmpty();
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThatThrownBy(() -> StatementPeriods.validatePeriod(END, START, today)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> StatementPeriods.validatePeriod(START, today, today))
                .extracting("code").isEqualTo("period_not_closed");
        assertThatThrownBy(() -> StatementPeriods.requireTransition("READY", "GENERATING"))
                .extracting("code").isEqualTo("invalid_transition");
        StatementPeriods.requireTransition("FAILED", "GENERATING");
        assertThat(StatementPeriods.transactionCode("INTEREST", "CREDIT")).isEqualTo("ACMT-MDOP-INTR");
        assertThat(StatementPeriods.transactionCode(null, "DEBIT")).isEqualTo("PMNT-ICDT-OTHR");
    }

    @Test
    void taxCertificateTotalsFilterKindCurrencyAndPeriod() {
        List<TaxCertificates.Posting> postings = List.of(
                new TaxCertificates.Posting(LocalDate.of(2025, 12, 31), "CREDIT", "EUR", new BigDecimal("9"), new BigDecimal("1")),
                new TaxCertificates.Posting(LocalDate.of(2026, 1, 31), "CREDIT", "EUR", new BigDecimal("3.10"), new BigDecimal("0.62")),
                new TaxCertificates.Posting(LocalDate.of(2026, 2, 28), "CREDIT", "EUR", new BigDecimal("2.80"), new BigDecimal("0.56")),
                new TaxCertificates.Posting(LocalDate.of(2026, 3, 31), "DEBIT", "EUR", new BigDecimal("5"), BigDecimal.ZERO),
                new TaxCertificates.Posting(LocalDate.of(2026, 3, 31), "CREDIT", "USD", new BigDecimal("7"), BigDecimal.ONE));
        TaxCertificates.Totals t = TaxCertificates.sum(postings, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "EUR");
        assertThat(t.grossInterest()).isEqualByComparingTo("5.90");
        assertThat(t.taxWithheld()).isEqualByComparingTo("1.18");
        assertThat(t.net()).isEqualByComparingTo("4.72");
        assertThat(t.postings()).isEqualTo(2);
        assertThatThrownBy(() -> TaxCertificates.validate(2025, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 10, 6))).extracting("code").isEqualTo("tax_year_mismatch");
        assertThatThrownBy(() -> TaxCertificates.validate(2026, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 10, 6))).extracting("code").isEqualTo("period_too_long");
    }

    /** For any sequence of movements, closing = opening + credits - debits and the running balance chain holds. */
    @Property(tries = 300)
    void totalsReconcile(@ForAll @BigRange(min = "-10000", max = "10000") BigDecimal opening,
                         @ForAll @Size(max = 40) List<@IntRange(min = -500000, max = 500000) Integer> cents) {
        List<StatementDocument.Line> lines = new ArrayList<>();
        BigDecimal running = opening;
        int i = 0;
        for (int c : cents) {
            if (c == 0) {
                continue;
            }
            BigDecimal amt = BigDecimal.valueOf(Math.abs((long) c), 2);
            String dir = c > 0 ? "CREDIT" : "DEBIT";
            running = c > 0 ? running.add(amt) : running.subtract(amt);
            lines.add(new StatementDocument.Line(++i, START, START, "x", null, null, dir, amt, running, null, null));
        }
        StatementDocument.Totals t = StatementDocument.totals(opening, lines);
        assertThat(t.closing()).isEqualByComparingTo(running);
        assertThat(t.credits().subtract(t.debits())).isEqualByComparingTo(running.subtract(opening));
        assertThat(StatementDocument.chained(opening, lines)).isTrue();
        String csv = new String(StatementRenderer.render(doc(lines, opening), "CSV").content(), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(lines.size() + 3);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
