package io.meridian.routing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class IsoMessagesTest {

    static Pacs008Builder.Transfer transfer(String creditorName, String remittance) {
        return new Pacs008Builder.Transfer("MSG0001", OffsetDateTime.parse("2026-10-06T10:15:30Z"), "INSTR1", "E2E-123",
                "TX-1", "0b6c2d55-3a1f-4c9b-8f0e-2f7a1d3c4b5a", new BigDecimal("1234.5"), "EUR",
                LocalDate.parse("2026-10-06"), "SLEV", "MERIDEFFXXX", "Alice Debtor", "DE89370400440532013000", "IBAN",
                "MERIDEFF", creditorName, "FR1420041010050500013M02606", "IBAN", "BNPAFRPP", null, "SUPP", remittance);
    }

    @Test
    void pacs008IsWellFormedAndParsesBack() {
        String xml = Pacs008Builder.build(transfer("Bob & Sons <Ltd>", "Invoice \"42\" & 'x'"));
        assertThat(xml).contains("xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"")
                .contains("<IntrBkSttlmAmt Ccy=\"EUR\">1234.50</IntrBkSttlmAmt>")
                .contains("<SttlmMtd>CLRG</SttlmMtd>")
                .contains("<ChrgBr>SLEV</ChrgBr>")
                .contains("<UETR>0b6c2d55-3a1f-4c9b-8f0e-2f7a1d3c4b5a</UETR>")
                .contains("Bob &amp; Sons &lt;Ltd&gt;")
                .doesNotContain("<Ltd>");
        IsoMessages.Parsed p = IsoMessages.parse(xml);
        assertThat(p.type()).isEqualTo("pacs.008");
        assertThat(p.messageId()).isEqualTo("MSG0001");
        assertThat(p.first()).containsEntry("end_to_end_id", "E2E-123")
                .containsEntry("amount", "1234.50").containsEntry("currency", "EUR")
                .containsEntry("creditor_name", "Bob & Sons <Ltd>")
                .containsEntry("creditor_account_number", "FR1420041010050500013M02606")
                .containsEntry("debtor_agent_bic", "MERIDEFF")
                .containsEntry("settlement_date", "2026-10-06")
                .containsEntry("remittance_unstructured", "Invoice \"42\" & 'x'");
    }

    @Test
    void nonIbanAccountsUseOthrAndJpyHasNoDecimals() {
        Pacs008Builder.Transfer t = new Pacs008Builder.Transfer("M2", OffsetDateTime.parse("2026-10-06T10:15:30Z"), null,
                "E2E-9", null, null, new BigDecimal("1000"), "JPY", null, null, null, "D", "12345678", "ACCOUNT_NUMBER",
                null, "C", "87654321", "ACCOUNT_NUMBER", null, "0001", null, null);
        String xml = Pacs008Builder.build(t);
        assertThat(xml).contains("<Othr><Id>87654321</Id></Othr>").contains(">1000</IntrBkSttlmAmt>")
                .contains("<ClrSysMmbId><MmbId>0001</MmbId></ClrSysMmbId>").contains("<ChrgBr>SHAR</ChrgBr>");
        assertThat(IsoMessages.parse(xml).first()).containsEntry("creditor_account_number", "87654321");
    }

    @Test
    void missingMandatoryFieldsAreRejected() {
        assertThatThrownBy(() -> Pacs008Builder.build(transfer(null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("creditor_name");
    }

    @Test
    void parsesPacs002StatusReport() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"><FIToFIPmtStsRpt>
                 <GrpHdr><MsgId>STS-1</MsgId><CreDtTm>2026-10-06T10:00:00Z</CreDtTm></GrpHdr>
                 <OrgnlGrpInfAndSts><OrgnlMsgId>MSG0001</OrgnlMsgId><OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId></OrgnlGrpInfAndSts>
                 <TxInfAndSts><OrgnlEndToEndId>E2E-123</OrgnlEndToEndId><TxSts>RJCT</TxSts>
                  <StsRsnInf><Rsn><Cd>AC04</Cd></Rsn><AddtlInf>Account closed</AddtlInf></StsRsnInf>
                  <ClrSysRef>CLR-77</ClrSysRef></TxInfAndSts>
                </FIToFIPmtStsRpt></Document>""";
        IsoMessages.Parsed p = IsoMessages.parse(xml);
        assertThat(p.type()).isEqualTo("pacs.002");
        assertThat(p.messageId()).isEqualTo("STS-1");
        assertThat(p.first()).containsEntry("original_end_to_end_id", "E2E-123").containsEntry("status", "RJCT")
                .containsEntry("reason_code", "AC04").containsEntry("reason", "Account closed")
                .containsEntry("clearing_system_ref", "CLR-77").containsEntry("original_message_id", "MSG0001");
    }

    @Test
    void parsesCamt056AndPacs004() {
        String camt = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.056.001.08"><FIToFIPmtCxlReq>
                 <Assgnmt><Id>ASG-1</Id><Assgnr><Agt><FinInstnId><BICFI>BNPAFRPP</BICFI></FinInstnId></Agt></Assgnr></Assgnmt>
                 <Undrlyg><TxInf><CxlId>C1</CxlId><OrgnlEndToEndId>E2E-IN-1</OrgnlEndToEndId>
                  <CxlRsnInf><Rsn><Cd>DUPL</Cd></Rsn></CxlRsnInf></TxInf></Undrlyg>
                </FIToFIPmtCxlReq></Document>""";
        IsoMessages.Parsed c = IsoMessages.parse(camt);
        assertThat(c.type()).isEqualTo("camt.056");
        assertThat(c.messageId()).isEqualTo("ASG-1");
        assertThat(c.first()).containsEntry("original_end_to_end_id", "E2E-IN-1").containsEntry("reason_code", "DUPL")
                .containsEntry("requested_by", "BNPAFRPP");
        String ret = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.004.001.09"><PmtRtr>
                 <GrpHdr><MsgId>RTR-1</MsgId></GrpHdr>
                 <TxInf><RtrId>R-1</RtrId><OrgnlEndToEndId>E2E-123</OrgnlEndToEndId>
                  <RtrdIntrBkSttlmAmt Ccy="EUR">100.00</RtrdIntrBkSttlmAmt>
                  <RtrRsnInf><Rsn><Cd>AC04</Cd></Rsn></RtrRsnInf></TxInf>
                </PmtRtr></Document>""";
        IsoMessages.Parsed r = IsoMessages.parse(ret);
        assertThat(r.type()).isEqualTo("pacs.004");
        assertThat(r.first()).containsEntry("return_id", "R-1").containsEntry("amount", "100.00")
                .containsEntry("currency", "EUR").containsEntry("reason_code", "AC04");
    }

    @Test
    void rejectsDoctypeAndUnknownMessages() {
        assertThatThrownBy(() -> IsoMessages.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Document>&e;</Document>"))
                .isInstanceOf(IsoMessages.InvalidMessage.class);
        assertThatThrownBy(() -> IsoMessages.parse("<Document xmlns=\"x\"><Foo/></Document>"))
                .isInstanceOf(IsoMessages.InvalidMessage.class).hasMessageContaining("unsupported");
        assertThatThrownBy(() -> IsoMessages.parse("not xml")).isInstanceOf(IsoMessages.InvalidMessage.class);
        assertThat(IsoMessages.canonical("pacs.002.001.10")).isEqualTo("pacs.002");
    }

    @Test
    void settlementFileCarriesControlSums() {
        String f = Pacs008Builder.settlementFile("F1", "BACS", "BACS-20261006-GBP", LocalDate.parse("2026-10-06"), "GBP",
                2, new BigDecimal("300"), 1, new BigDecimal("50"), new BigDecimal("-250"),
                OffsetDateTime.parse("2026-10-06T17:00:00Z"),
                java.util.List.of(new Pacs008Builder.BatchLine("M1", "E1", "DBIT", null)));
        assertThat(f).contains("<NetPos>-250.00</NetPos>").contains("<DbtSum>300.00</DbtSum>").contains("<MsgId>M1</MsgId>");
        assertThat(IsoMessages.read(f).getDocumentElement().getLocalName()).isEqualTo("SettlementBatchFile");
    }
}
