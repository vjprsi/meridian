package io.meridian.regulatoryreporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportEngineTest {
    static final List<Map<String, Object>> TB = List.of(
            Map.of("id", "a1", "cls", "ASSET", "bal", new BigDecimal("1000.004")),
            Map.of("id", "a2", "cls", "ASSET", "bal", "250.50"),
            Map.of("id", "l1", "cls", "LIABILITY", "bal", "-900"));

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    @Test
    void aggregatesFiltersDerivedCellsAndLineage() {
        List<ReportEngine.Mapping> mappings = ReportEngine.compile(List.of(
                m("cell_ref", "NET", "expression", "ASSETS - LIAB"),
                m("cell_ref", "ASSETS", "source", "tb", "value", "bal", "filter", "cls = 'ASSET'"),
                m("cell_ref", "LIAB", "source", "tb", "value", "-bal", "filter", "cls = 'LIABILITY'"),
                m("cell_ref", "N", "source", "tb"),
                m("cell_ref", "BIGGEST", "source", "tb", "aggregate", "MAX", "value", "bal"),
                m("cell_ref", "AVG_ASSET", "source", "tb", "aggregate", "AVG", "value", "bal", "filter", "cls = 'ASSET'", "scale", 4),
                m("cell_ref", "FIRST_CLS", "source", "tb", "aggregate", "FIRST", "value", "cls", "type", "TEXT"),
                m("cell_ref", "RATIO", "expression", "ROUND(LIAB / ASSETS * 100, 2) + :bump", "scale", 2)), Set.of("tb"));
        List<ReportEngine.Cell> cells = ReportEngine.compute(mappings,
                Map.of("tb", new ReportEngine.Source("tb", null, TB)), Map.of("bump", "0"));
        Map<String, ReportEngine.Cell> byRef = new LinkedHashMap<>();
        cells.forEach(c -> byRef.put(c.cellRef(), c));
        assertThat(cells.getFirst().cellRef()).isEqualTo("NET"); // declaration order is kept
        assertThat(byRef.get("ASSETS").value()).isEqualTo("1250.50");
        assertThat(byRef.get("LIAB").value()).isEqualTo("900.00");
        assertThat(byRef.get("NET").value()).isEqualTo("350.50");
        assertThat(byRef.get("N").value()).isEqualTo("3");
        assertThat(byRef.get("BIGGEST").value()).isEqualTo("1000.00");
        assertThat(byRef.get("AVG_ASSET").value()).isEqualTo("625.2520");
        assertThat(byRef.get("FIRST_CLS").value()).isEqualTo("ASSET");
        assertThat(byRef.get("RATIO").value()).isEqualTo("71.97");
        assertThat(byRef.get("ASSETS").sourceCount()).isEqualTo(2);
        assertThat(byRef.get("ASSETS").sourceRecords()).extracting(r -> r.get("id")).containsExactly("a1", "a2");
        assertThat(byRef.get("NET").sourceRecords()).isEmpty();
    }

    @Test
    void compileRejectsBadMappings() {
        assertThatThrownBy(() -> ReportEngine.compile(List.of(
                m("cell_ref", "A", "source", "nope", "value", "x"),
                m("cell_ref", "A", "source", "tb", "value", "x"),
                m("cell_ref", "B", "source", "tb", "aggregate", "MEDIAN", "value", "x"),
                m("cell_ref", "C", "source", "tb", "aggregate", "SUM"),
                m("cell_ref", "1BAD", "source", "tb")), Set.of("tb")))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionException.Problem::message)
                        .anyMatch(s -> s.contains("source must name a data source"))
                        .anyMatch(s -> s.contains("duplicate cell_ref A"))
                        .anyMatch(s -> s.contains("aggregate must be one of"))
                        .anyMatch(s -> s.contains("SUM needs a value expression"))
                        .anyMatch(s -> s.contains("cell_ref is required")));
        assertThatThrownBy(() -> ReportEngine.compile(List.of(
                m("cell_ref", "X", "expression", "Y + 1"), m("cell_ref", "Y", "expression", "Z * 2"),
                m("cell_ref", "Z", "expression", "X")), Set.of()))
                .hasMessageContaining("circular cell references: [X, Y, Z]");
    }

    @Test
    void sourcesAreWhitelistedAndPathsSafe() {
        Set<String> allowed = Set.of("gl-service");
        assertThat(ReportEngine.checkSources(List.of(m("name", "tb", "service", "gl-service",
                "path", "/v1/gl/periods/{param.period_id}/trial-balance")), allowed)).containsExactly("tb");
        assertThatThrownBy(() -> ReportEngine.checkSources(List.of(
                m("name", "a", "service", "identity-service", "path", "/v1/users"),
                m("name", "b", "service", "gl-service", "path", "http://evil/v1/x"),
                m("name", "c", "service", "gl-service", "path", "/v1/../admin"),
                m("name", "d", "service", "gl-service", "path", "/v1/x/{secret}"),
                m("name", "e", "type", "INLINE")), allowed))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems()).hasSize(5));
        assertThat(PathTemplate.expand("/v1/loans?le={legal_entity_id}&q={param.q}", Map.of("legal_entity_id", "x y"),
                Map.of("q", "a&b=c"))).isEqualTo("/v1/loans?le=x+y&q=a%26b%3Dc");
    }

    @Test
    void derivedCellsFollowAdjustedInputsUnlessPinned() {
        List<ReportEngine.Mapping> mappings = ReportEngine.compile(List.of(
                m("cell_ref", "A", "source", "tb", "value", "bal", "filter", "cls = 'ASSET'"),
                m("cell_ref", "B", "expression", "A * 2"),
                m("cell_ref", "C", "expression", "B + 1")), Set.of("tb"));
        List<ReportEngine.Cell> cells = ReportEngine.compute(mappings, Map.of("tb", new ReportEngine.Source("tb", null, TB)), Map.of());
        List<ReportEngine.Cell> adjusted = List.of(
                new ReportEngine.Cell("A", null, "NUMBER", "10.00", "tb", 2, cells.get(0).sourceRecords()), cells.get(1), cells.get(2));
        List<ReportEngine.Cell> r = ReportEngine.rederive(mappings, adjusted, Set.of("A"), Map.of());
        assertThat(r).extracting(ReportEngine.Cell::value).containsExactly("10.00", "20.00", "21.00");
        assertThat(r.getFirst().sourceRecords()).hasSize(2);
        List<ReportEngine.Cell> pinnedB = ReportEngine.rederive(mappings, List.of(adjusted.get(0),
                new ReportEngine.Cell("B", null, "NUMBER", "5.00", null, 0, List.of()), cells.get(2)), Set.of("A", "B"), Map.of());
        assertThat(pinnedB).extracting(ReportEngine.Cell::value).containsExactly("10.00", "5.00", "6.00");
    }

    @Test
    void numericAggregateOverTextFails() {
        List<ReportEngine.Mapping> mappings = ReportEngine.compile(List.of(m("cell_ref", "S", "source", "tb", "value", "cls")),
                Set.of("tb"));
        assertThatThrownBy(() -> ReportEngine.compute(mappings, Map.of("tb", new ReportEngine.Source("tb", null, TB)), Map.of()))
                .hasMessageContaining("cell S").hasMessageContaining("non-numeric");
    }

    @Test
    void renderedFilesAreDeterministicAndEscaped() {
        List<ReportEngine.Cell> cells = List.of(
                new ReportEngine.Cell("A<1>", "Assets & \"other\"", "NUMBER", "10.50", null, 0, List.of()),
                new ReportEngine.Cell("T", "=cmd()", "TEXT", "x,y", null, 0, List.of()));
        ReportRenderer.Header h = new ReportRenderer.Header("R-1", "Report", "CBN", "NG", 1, null, "le", "2026-01-31",
                "2026-01-01", "2026-01-31", "NGN", 1);
        ReportRenderer.Rendered xbrl = ReportRenderer.render("XBRL", h, cells);
        assertThat(xbrl.content()).contains("<rep:A_1_ contextRef=\"C1\" unitRef=\"U1\" decimals=\"2\">10.50</rep:A_1_>")
                .contains("<xbrli:startDate>2026-01-01</xbrli:startDate>");
        assertThat(ReportRenderer.render("XBRL", h, cells).sha256()).isEqualTo(xbrl.sha256());
        assertThat(ReportRenderer.render("XML", h, cells).content()).contains("label=\"Assets &amp; &quot;other&quot;\"");
        assertThat(ReportRenderer.render("CSV", h, cells).content()).contains("T,'=cmd(),TEXT,\"x,y\"");
        assertThat(ReportRenderer.render("JSON", h, cells).content()).contains("\"label\":\"Assets & \\\"other\\\"\"");
        assertThat(ReportRenderer.formatsFor("XBRL")).containsExactly("XBRL", "CSV", "JSON");
    }
}
