package io.meridian.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BulkAndDocumentsTest {

    @Test
    void csvWithQuotingAndBlankLines() {
        String csv = "Creditor_Name,creditor_account_number,AMOUNT,currency,end_to_end_id,remittance\r\n"
                + "\"Lee, Ann\",GB29NWBK60161331926819,100.00,GBP,E1,\"Pay \"\"Oct\"\"\"\r\n"
                + "\r\n"
                + "Ben,DE89370400440532013000,abc,GBP,,\n";
        BulkFileParser.Parsed p = BulkFileParser.parse("CSV", csv);
        assertThat(p.lines()).hasSize(2);
        assertThat(p.lines().get(0).creditorName()).isEqualTo("Lee, Ann");
        assertThat(p.lines().get(0).remittance()).isEqualTo("Pay \"Oct\"");
        assertThat(p.lines().get(1).amount()).isEqualTo("abc");
        assertThat(p.lines().get(1).endToEndId()).isNull();
    }

    @Test
    void csvNeedsRequiredColumns() {
        assertThatThrownBy(() -> BulkFileParser.parse("CSV", "name,amount\nA,1\n"))
                .isInstanceOf(BulkFileParser.FileFormatException.class).hasMessageContaining("creditor_name");
        assertThatThrownBy(() -> BulkFileParser.parse("NACHA", "x")).hasMessageContaining("cannot be uploaded");
    }

    @Test
    void pain001WithControlTotals() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.03"><CstmrCdtTrfInitn>
                 <GrpHdr><MsgId>M1</MsgId><NbOfTxs>2</NbOfTxs><CtrlSum>30.50</CtrlSum></GrpHdr>
                 <PmtInf><BtchBookg>false</BtchBookg><ReqdExctnDt>2026-10-07</ReqdExctnDt>
                  <PmtTpInf><CtgyPurp><Cd>SALA</Cd></CtgyPurp></PmtTpInf>
                  <DbtrAcct><Id><IBAN>GB29NWBK60161331926819</IBAN></Id></DbtrAcct>
                  <CdtTrfTxInf><PmtId><EndToEndId>A1</EndToEndId></PmtId><Amt><InstdAmt Ccy="EUR">10.25</InstdAmt></Amt>
                   <CdtrAgt><FinInstnId><BIC>DEUTDEFF</BIC></FinInstnId></CdtrAgt><Cdtr><Nm>Anna</Nm></Cdtr>
                   <CdtrAcct><Id><IBAN>DE89370400440532013000</IBAN></Id></CdtrAcct><RmtInf><Ustrd>Oct</Ustrd></RmtInf></CdtTrfTxInf>
                  <CdtTrfTxInf><PmtId><EndToEndId>A2</EndToEndId></PmtId><Amt><InstdAmt Ccy="EUR">20.25</InstdAmt></Amt>
                   <Cdtr><Nm>Bert</Nm></Cdtr><CdtrAcct><Id><Othr><Id>12345678</Id></Othr></Id></CdtrAcct></CdtTrfTxInf>
                 </PmtInf></CstmrCdtTrfInitn></Document>""";
        BulkFileParser.Parsed p = BulkFileParser.parse("PAIN_001", xml);
        assertThat(p.lines()).hasSize(2);
        assertThat(p.categoryPurpose()).isEqualTo("SALA");
        assertThat(p.batchBooking()).isFalse();
        assertThat(p.requestedExecutionDate()).isEqualTo("2026-10-07");
        assertThat(p.debtorIban()).isEqualTo("GB29NWBK60161331926819");
        BulkFileParser.Line first = p.lines().getFirst();
        assertThat(first.bic()).isEqualTo("DEUTDEFF");
        assertThat(first.accountIdType()).isEqualTo("IBAN");
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(p.lines().get(1).accountIdType()).isEqualTo("ACCOUNT_NUMBER");
        assertThat(p.lines().get(1).accountNumber()).isEqualTo("12345678");
        assertThatThrownBy(() -> BulkFileParser.parse("PAIN_001", xml.replace("<NbOfTxs>2", "<NbOfTxs>3")))
                .hasMessageContaining("NbOfTxs");
        assertThatThrownBy(() -> BulkFileParser.parse("PAIN_001", xml.replace("30.50", "30.00")))
                .hasMessageContaining("CtrlSum");
    }

    @Test
    void externalEntitiesAreRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Document>&x;</Document>";
        assertThatThrownBy(() -> BulkFileParser.parse("PAIN_001", xxe))
                .isInstanceOf(BulkFileParser.FileFormatException.class).hasMessageContaining("well-formed");
    }

    @Test
    void receiptIsAValidPdfWithCorrectXrefOffsets() {
        byte[] pdf = ReceiptPdf.render("Bank - Payment advice", "Issued now", List.of(
                new ReceiptPdf.Row("Amount", "10.00 GBP"), new ReceiptPdf.Row("Payee", "O'Brien (Ltd) \\ Ñ")), "footer");
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(s).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("(O'Brien \\(Ltd\\) \\\\ N) Tj");
        int startxref = Integer.parseInt(s.substring(s.lastIndexOf("startxref") + 10, s.lastIndexOf("\n%%EOF")).trim());
        assertThat(s.substring(startxref)).startsWith("xref");
        String[] xref = s.substring(startxref).split("\n");
        for (int obj = 1; obj <= 6; obj++) {
            int offset = Integer.parseInt(xref[2 + obj].substring(0, 10));
            assertThat(s.substring(offset)).startsWith(obj + " 0 obj");
        }
    }

    @Test
    void lifecycleAndApprovalMatrix() {
        assertThat(PaymentLifecycle.allowed("SUBMITTED", "SETTLED")).isTrue();
        assertThat(PaymentLifecycle.allowed("SETTLED", "SUBMITTED")).isFalse();
        assertThat(PaymentLifecycle.allowed("REJECTED", "APPROVED")).isFalse();
        assertThat(PaymentLifecycle.event("SETTLED", "INBOUND")).contains("payments.payment.received");
        ApprovalMatrix m = new ApprovalMatrix(List.of(new ApprovalMatrix.Level(new BigDecimal("100000"), 2),
                new ApprovalMatrix.Level(new BigDecimal("10000"), 1)));
        assertThat(m.stepsFor(new BigDecimal("5000"), 0)).isEmpty();
        assertThat(m.stepsFor(new BigDecimal("5000"), 1)).containsExactly(new ApprovalMatrix.Step(1, 1));
        assertThat(m.stepsFor(new BigDecimal("150000"), 0))
                .containsExactly(new ApprovalMatrix.Step(1, 1), new ApprovalMatrix.Step(2, 2));
    }

    @Test
    void aliasNormalisation() {
        assertThat(AliasRules.normalise("PHONE", "0044 (7700) 900-123")).isEqualTo("+447700900123");
        assertThat(AliasRules.normalise("EMAIL", " Bob@Example.COM ")).isEqualTo("bob@example.com");
        assertThat(AliasRules.normalise("PIX_KEY_CPF", "529.982.247-25")).isEqualTo("52998224725");
        assertThat(AliasRules.cnpjValid("11222333000181")).isTrue();
        assertThat(AliasRules.cpfValid("11111111111")).isFalse();
        assertThatThrownBy(() -> AliasRules.normalise("PIX_KEY_CPF", "52998224724")).isInstanceOf(AliasRules.InvalidAlias.class);
        assertThatThrownBy(() -> AliasRules.normalise("UPI_VPA", "no-at-sign")).isInstanceOf(AliasRules.InvalidAlias.class);
        assertThat(AliasRules.mask("PHONE", "+447700900123")).isEqualTo("+44*******123");
    }
}
