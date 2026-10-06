package io.meridian.filegateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LayoutValidatorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode j(String s) {
        return JSON.readTree(s);
    }

    private static final JsonNode CSV = j("""
            {"fields":[{"name":"id","type":"NUMERIC","required":true},{"name":"name"},
                       {"name":"amount","type":"DECIMAL","required":true},{"name":"value_date","type":"DATE"}]}""");

    @Test
    void csvWithHeaderAndQuotedFields() {
        String content = "id,name,amount,value_date\n1,\"Doe, John\",10.50,2026-01-30\n2,Acme,-3,2026-02-03\n";
        LayoutValidator.Result r = LayoutValidator.validate("CSV", CSV, null, ",", true, content);
        assertThat(r.valid()).isTrue();
        assertThat(r.recordCount()).isEqualTo(2);
    }

    @Test
    void csvReportsFieldErrorsWithLineNumbers() {
        String content = "id,name,amount,value_date\nX1,a,10,2026-01-30\n2,b,,2026-13-01\n3,c\n";
        LayoutValidator.Result r = LayoutValidator.validate("CSV", CSV, null, ",", true, content);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).extracting(LayoutValidator.FieldError::code)
                .containsExactly("invalid_numeric", "required", "invalid_date", "column_count");
        assertThat(r.errors().getFirst().line()).isEqualTo(2);
    }

    @Test
    void fixedWidthWithRecordTypes() {
        JsonNode schema = j("""
                {"type_start":1,"type_length":1,"record_types":[
                  {"code":"H","fields":[{"name":"type","start":1,"length":1},{"name":"date","start":2,"length":8,"type":"DATE"}]},
                  {"code":"D","fields":[{"name":"type","start":1,"length":1},{"name":"account","start":2,"length":6,"type":"NUMERIC","required":true},
                                        {"name":"amount","start":8,"length":3,"type":"NUMERIC"}]}]}""");
        assertThat(LayoutValidator.schemaProblems("FIXED_WIDTH", schema, 10, null)).isEmpty();
        String ok = "H20260130 \nD000123045\nD000124100\n";
        LayoutValidator.Result r = LayoutValidator.validate("FIXED_WIDTH", schema, 10, null, false, ok);
        assertThat(r.valid()).as(r.errors().toString()).isTrue();
        assertThat(r.recordCount()).isEqualTo(3);
        LayoutValidator.Result bad = LayoutValidator.validate("FIXED_WIDTH", schema, 10, null, false, "X000000000\nD0001\n");
        assertThat(bad.errors()).extracting(LayoutValidator.FieldError::code)
                .containsExactly("unknown_record_type", "record_length");
    }

    @Test
    void schemaProblemsDetectOverlapsAndMissingLength() {
        JsonNode overlap = j("{\"fields\":[{\"name\":\"a\",\"start\":1,\"length\":5},{\"name\":\"b\",\"start\":4,\"length\":3}]}");
        assertThat(LayoutValidator.schemaProblems("FIXED_WIDTH", overlap, 10, null)).anyMatch(p -> p.contains("overlaps"));
        assertThat(LayoutValidator.schemaProblems("FIXED_WIDTH", overlap, null, null)).anyMatch(p -> p.contains("record_length"));
        assertThat(LayoutValidator.schemaProblems("FIXED_WIDTH", overlap, 5, null)).anyMatch(p -> p.contains("beyond"));
        assertThat(LayoutValidator.schemaProblems("CSV", j("{\"fields\":[]}"), null, ",")).isNotEmpty();
        assertThat(LayoutValidator.schemaProblems("XML_ISO20022", j("{\"message_type\":\"camt53\"}"), null, null)).hasSize(2);
    }

    @Test
    void iso20022CountsEntriesAndChecksNamespace() {
        JsonNode schema = j("{\"message_type\":\"camt.053.001.08\",\"record_element\":\"Ntry\",\"required_elements\":[\"Amt\"]}");
        String xml = """
                <?xml version="1.0"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"><BkToCstmrStmt><Stmt>
                <Ntry><Amt Ccy="EUR">10.00</Amt></Ntry><Ntry><Amt Ccy="EUR">5.00</Amt></Ntry>
                </Stmt></BkToCstmrStmt></Document>""";
        LayoutValidator.Result r = LayoutValidator.validate("XML_ISO20022", schema, null, null, false, xml);
        assertThat(r.valid()).isTrue();
        assertThat(r.recordCount()).isEqualTo(2);
        String wrong = xml.replace("camt.053.001.08", "pain.001.001.09").replace("<Amt Ccy=\"EUR\">5.00</Amt>", "");
        assertThat(LayoutValidator.validate("XML_ISO20022", schema, null, null, false, wrong).errors())
                .extracting(LayoutValidator.FieldError::code).containsExactly("message_type", "required");
    }

    @Test
    void xmlExternalEntitiesAreRejected() {
        JsonNode schema = j("{\"message_type\":\"camt.053.001.08\",\"record_element\":\"Ntry\"}");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Document>&x;</Document>";
        assertThat(LayoutValidator.validate("XML_ISO20022", schema, null, null, false, xxe).errors().getFirst().code())
                .isEqualTo("malformed_xml");
    }

    @Test
    void virusScannerDetectsEicar() {
        assertThat(VirusScanner.scan(("header\n" + VirusScanner.EICAR).getBytes(StandardCharsets.US_ASCII)).status())
                .isEqualTo("INFECTED");
        assertThat(VirusScanner.scan("id,amount\n1,2\n".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo("CLEAN");
    }

    @Test
    void rendererWritesCsvAndFixedWidthWithControlTotals() {
        JsonNode records = j("[{\"id\":\"1\",\"name\":\"A\",\"amount\":\"10.50\"},{\"id\":\"2\",\"name\":\"B, Ltd\",\"amount\":\"4.50\"}]");
        FileRenderer.Rendered csv = FileRenderer.render("CSV", CSV, null, ",", true, records);
        assertThat(csv.content()).isEqualTo("id,name,amount,value_date\n1,A,10.50,\n2,\"B, Ltd\",4.50,\n");
        assertThat(csv.controlSum()).isEqualByComparingTo("15.00");
        JsonNode fixed = j("{\"fields\":[{\"name\":\"id\",\"start\":1,\"length\":3,\"type\":\"NUMERIC\"},{\"name\":\"name\",\"start\":4,\"length\":7}]}");
        FileRenderer.Rendered fw = FileRenderer.render("FIXED_WIDTH", fixed, 10, null, false, records);
        assertThat(fw.content()).isEqualTo("001A      \n002B, Ltd \n");
        assertThat(LayoutValidator.validate("FIXED_WIDTH", fixed, 10, null, false, fw.content()).valid()).isTrue();
    }
}
