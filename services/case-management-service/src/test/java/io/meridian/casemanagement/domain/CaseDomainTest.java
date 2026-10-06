package io.meridian.casemanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class CaseDomainTest {

    static NarrativeBuilder.Facts facts() {
        return new NarrativeBuilder.Facts("AML-2026-000042", "AML", "Structuring below the CTR threshold",
                "Cash deposits across branches", "c-1", new BigDecimal("27950"), "USD", "2026-09-01T10:00:00Z",
                "2026-08-01", "2026-08-20", "STR", "GB", "NCA",
                List.of(new NarrativeBuilder.Alert("a1", "TM_ALERT", "STRUCTURING: 3 cash deposits of 9,300-9,500", "2026-08-21"),
                        new NarrativeBuilder.Alert("a2", "FRAUD_ALERT", "Shared device with 3 customers", "2026-08-22")),
                List.of(new NarrativeBuilder.Subject("John <Doe> & Sons", "INDIVIDUAL", "SUBJECT", "GB", "P123")),
                List.of(new NarrativeBuilder.Txn("t1", "2026-08-01", new BigDecimal("9300"), "USD", "cash", "acct 1", "deposit"),
                        new NarrativeBuilder.Txn("t2", "2026-08-05", new BigDecimal("9450"), "USD", "cash", "acct 1", "deposit")),
                2, 1, List.of("Account restricted"));
    }

    @Test
    void narrativeIsDeterministicAndCoversTheFiveWs() {
        String a = NarrativeBuilder.narrative(facts());
        String b = NarrativeBuilder.narrative(facts());
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("STR for case AML-2026-000042", "WHO: John <Doe> & Sons", "WHAT: 2 transaction(s) totalling 27950.00 USD",
                "WHEN: The activity took place between 2026-08-01 and 2026-08-20", "WHERE: Parties located in GB",
                "WHY: The case consolidates 1 tm alert, 1 fraud alert", "HOW: 2026-08-01: 9300.00 USD from cash to acct 1",
                "ACTIONS: Account restricted");
    }

    @Test
    void summaryCitesEachAlert() {
        NarrativeBuilder.Summary s = NarrativeBuilder.summary(facts(), Map.of("case", "aml_case:x"));
        assertThat(s.text()).startsWith("aml case AML-2026-000042").contains("[2]", "[3]");
        assertThat(s.citations()).extracting(NarrativeBuilder.Citation::ref).containsExactly("aml_case:x", "aml_case_alert:a1", "aml_case_alert:a2");
    }

    @Test
    void goAmlXmlIsWellFormedAndEscaped() throws Exception {
        NarrativeBuilder.Facts f = facts();
        String xml = GoAmlWriter.write(new GoAmlWriter.Report("tenant-1", "STR", "sar-1", "2026-10-06T10:00:00Z", "USD",
                "alice", "Narrative with <tags> & ampersands", "Account restricted", null, f.transactions(), f.subjects(),
                List.of("AML", "TM_ALERT")));
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getTagName()).isEqualTo("report");
        assertThat(doc.getElementsByTagName("transaction").getLength()).isEqualTo(2);
        assertThat(doc.getElementsByTagName("reason").item(0).getTextContent()).isEqualTo("Narrative with <tags> & ampersands");
        assertThat(doc.getElementsByTagName("amount_local").item(1).getTextContent()).isEqualTo("9450.00");
        assertThat(doc.getElementsByTagName("indicator").getLength()).isEqualTo(2);
        assertThat(xml).contains("&lt;tags&gt; &amp; ampersands");
    }

    @Test
    void lifecycleRules() {
        assertThat(CaseRules.caseAllows("close", "INVESTIGATING")).isTrue();
        assertThat(CaseRules.caseAllows("close", "PENDING_APPROVAL")).isFalse();
        assertThat(CaseRules.caseAllows("reopen", "CLOSED")).isTrue();
        assertThat(CaseRules.caseAllows("escalate", "ESCALATED")).isFalse();
        assertThat(CaseRules.afterAssign("OPEN")).isEqualTo("INVESTIGATING");
        assertThat(CaseRules.afterAssign("ESCALATED")).isEqualTo("ESCALATED");
        assertThat(CaseRules.taskTransition("OPEN", "IN_PROGRESS")).isTrue();
        assertThat(CaseRules.taskTransition("DONE", "OPEN")).isFalse();
        assertThat(CaseRules.sarAllows("approve", "DRAFT")).isFalse();
        assertThat(CaseRules.sarAllows("submit_for_approval", "REJECTED")).isTrue();
        assertThat(CaseRules.sarAllows("export", "DRAFT")).isFalse();
        assertThat(CaseRules.prefix("SANCTIONS")).isEqualTo("SAN");
    }
}
