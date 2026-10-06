package io.meridian.pricingrules.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DmnXmlTest {

    static final String DMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" id="d" name="Loan pricing" namespace="x">
              <decision id="loan_pricing" name="Loan pricing grid">
                <decisionTable id="t" hitPolicy="FIRST">
                  <input id="i1" label="credit_score">
                    <inputExpression typeRef="number"><text>applicant.score</text></inputExpression>
                  </input>
                  <output id="o1" name="rate" typeRef="number">
                    <defaultOutputEntry><text>0.25</text></defaultOutputEntry>
                  </output>
                  <rule id="r1">
                    <description>prime</description>
                    <inputEntry><text>[700..850]</text></inputEntry>
                    <outputEntry><text>0.06</text></outputEntry>
                  </rule>
                  <rule id="r2">
                    <inputEntry><text>-</text></inputEntry>
                    <outputEntry><text>0.15</text></outputEntry>
                  </rule>
                </decisionTable>
              </decision>
            </definitions>
            """;

    @Test
    void importsDecisionTable() {
        DmnXml.Imported imp = DmnXml.parse(DMN);
        assertThat(imp.decisionName()).isEqualTo("Loan pricing grid");
        assertThat(imp.hitPolicy()).isEqualTo("FIRST");
        DecisionTableModel m = DecisionTableModel.parse(imp.hitPolicy(), null, imp.inputs(), imp.outputs(), imp.rules());
        assertThat(m.inputs().getFirst().path()).isEqualTo("applicant.score");
        assertThat(m.rules()).hasSize(2);
        assertThat(m.rules().getFirst().annotation()).isEqualTo("prime");
        assertThat((BigDecimal) DecisionEngine.evaluate(m, Map.of("applicant", Map.of("score", 720))).outputs().get("rate"))
                .isEqualByComparingTo("0.06");
    }

    @Test
    void exportThenImportRoundTripsTheTable() {
        DecisionTableModel original = DecisionEngineTest.pricingGrid("RULE_ORDER", null);
        String xml = DmnXml.write("LOAN_GRID", "Loan grid", original);
        assertThat(xml).contains("hitPolicy=\"RULE ORDER\"");
        DmnXml.Imported imp = DmnXml.parse(xml);
        DecisionTableModel back = DecisionTableModel.parse(imp.hitPolicy(), null, imp.inputs(), imp.outputs(), imp.rules());
        assertThat(Canonical.of(back.rulesJson())).isEqualTo(Canonical.of(original.rulesJson()));
        assertThat(Canonical.of(back.inputsJson())).isEqualTo(Canonical.of(original.inputsJson()));
        assertThat(Canonical.of(back.outputsJson())).isEqualTo(Canonical.of(original.outputsJson()));
        assertThat(DmnXml.write("LOAN_GRID", "Loan grid", back)).isEqualTo(xml);
    }

    @Test
    void rejectsDoctypeAndMalformedXml() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<definitions><decision id=\"a\">&x;</decision></definitions>";
        assertThatThrownBy(() -> DmnXml.parse(xxe)).isInstanceOf(DecisionTableException.class)
                .extracting(e -> ((DecisionTableException) e).code()).isEqualTo("invalid_dmn");
        assertThatThrownBy(() -> DmnXml.parse("<definitions>")).isInstanceOf(DecisionTableException.class);
        assertThatThrownBy(() -> DmnXml.parse("<definitions/>")).isInstanceOf(DecisionTableException.class);
    }

    @Test
    void canonicalFormIgnoresKeyOrderAndScale() {
        assertThat(Canonical.sha256(Map.of("a", new BigDecimal("1.50"), "b", "x")))
                .isEqualTo(Canonical.sha256(new java.util.TreeMap<>(Map.of("b", "x", "a", new BigDecimal("1.5")))));
    }
}
