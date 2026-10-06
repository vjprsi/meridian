package io.meridian.regulatoryreporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportValidatorTest {
    static final Set<String> CELLS = Set.of("ASSETS", "LIAB", "EQUITY", "TOTAL", "COUNT", "NAME");

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static final List<ReportValidator.Rule> RULES = ReportValidator.compile(List.of(
            m("code", "REQ", "type", "REQUIRED", "cells", List.of("ASSETS", "NAME")),
            m("code", "BAL", "type", "BALANCE", "debit", List.of("ASSETS"), "credit", List.of("LIAB", "EQUITY"), "tolerance", "0.01"),
            m("code", "TOT", "type", "EQUALS", "left", "ASSETS", "right", "TOTAL", "tolerance", 0),
            m("code", "RNG", "type", "RANGE", "cell", "COUNT", "min", 1, "max", 100, "severity", "WARNING"),
            m("code", "EXP", "type", "EXPRESSION", "expression", "LIAB <= ASSETS", "severity", "info", "message", "Leverage")),
            CELLS);

    @Test
    void cleanCellsPass() {
        Map<String, Object> cells = Map.of("ASSETS", new BigDecimal("100.00"), "LIAB", new BigDecimal("60.00"),
                "EQUITY", new BigDecimal("40.005"), "TOTAL", new BigDecimal("100"), "COUNT", new BigDecimal("5"), "NAME", "Bank");
        assertThat(ReportValidator.validate(RULES, cells, Map.of())).isEmpty();
    }

    @Test
    void failuresCarrySeverityExpectedAndActual() {
        Map<String, Object> cells = new HashMap<>();
        cells.put("ASSETS", new BigDecimal("100"));
        cells.put("LIAB", new BigDecimal("150"));
        cells.put("EQUITY", new BigDecimal("0"));
        cells.put("TOTAL", new BigDecimal("150"));
        cells.put("COUNT", new BigDecimal("0"));
        cells.put("NAME", " ");
        List<ReportValidator.Finding> f = ReportValidator.validate(RULES, cells, Map.of());
        assertThat(f).extracting(ReportValidator.Finding::ruleCode).containsExactly("REQ", "BAL", "TOT", "RNG", "EXP");
        assertThat(f.get(0).cellRef()).isEqualTo("NAME");
        assertThat(f.get(1).actual()).isEqualTo("100");
        assertThat(f.get(1).expected()).isEqualTo("150");
        assertThat(f.get(2).severity()).isEqualTo("ERROR");
        assertThat(f.get(3).severity()).isEqualTo("WARNING");
        assertThat(f.get(4).severity()).isEqualTo("INFO");
        assertThat(f.get(4).message()).startsWith("Leverage - Condition failed: LIAB <= ASSETS");
    }

    @Test
    void toleranceIsInclusive() {
        List<ReportValidator.Rule> r = ReportValidator.compile(List.of(
                m("code", "T", "type", "EQUALS", "left", "ASSETS", "right", "LIAB + EQUITY", "tolerance", "0.05")), CELLS);
        assertThat(ReportValidator.validate(r, Map.of("ASSETS", new BigDecimal("100.05"), "LIAB", new BigDecimal("60"),
                "EQUITY", new BigDecimal("40")), Map.of())).isEmpty();
        assertThat(ReportValidator.validate(r, Map.of("ASSETS", new BigDecimal("100.06"), "LIAB", new BigDecimal("60"),
                "EQUITY", new BigDecimal("40")), Map.of())).hasSize(1);
        // A missing operand fails the comparison rather than passing silently.
        assertThat(ReportValidator.validate(r, Map.of("LIAB", new BigDecimal("60"), "EQUITY", new BigDecimal("40")), Map.of()))
                .hasSize(1);
    }

    @Test
    void compileRejectsUnknownCellsTypesAndDuplicates() {
        assertThatThrownBy(() -> ReportValidator.compile(List.of(
                m("code", "A", "type", "REQUIRED", "cells", List.of("NOPE")),
                m("code", "A", "type", "EQUALS", "left", "ASSETS"),
                m("code", "C", "type", "MAGIC"),
                m("code", "D", "type", "RANGE", "cell", "COUNT"),
                m("code", "E", "type", "BALANCE", "debit", List.of("ASSETS"), "credit", List.of(), "tolerance", "-1")), CELLS))
                .isInstanceOfSatisfying(DefinitionException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionException.Problem::message)
                        .anyMatch(s -> s.contains("unknown cell NOPE"))
                        .anyMatch(s -> s.contains("duplicate rule code A"))
                        .anyMatch(s -> s.contains("expression is required"))
                        .anyMatch(s -> s.contains("type must be one of"))
                        .anyMatch(s -> s.contains("RANGE needs min and/or max"))
                        .anyMatch(s -> s.contains("tolerance cannot be negative"))
                        .anyMatch(s -> s.contains("at least one cell is required")));
    }

    @Test
    void filingCalendarDeadlinesAndPeriods() {
        // 10 business days after Sat 31 Jan 2026 = Fri 13 Feb; +2 days lands on Sunday, rolled to Monday.
        assertThat(FilingCalendar.dueDate("PERIOD_END+10BD", "MONTHLY", LocalDate.parse("2026-01-31"), 2))
                .isEqualTo(LocalDate.parse("2026-02-16"));
        assertThat(FilingCalendar.dueDate("MONTH_END+15D", "MONTHLY", LocalDate.parse("2026-02-28"), 0))
                .isEqualTo(LocalDate.parse("2026-03-16")); // 15 March is a Sunday
        assertThat(FilingCalendar.dueDate(null, "QUARTERLY", LocalDate.parse("2026-03-31"), 0))
                .isEqualTo(LocalDate.parse("2026-04-30"));
        assertThat(FilingCalendar.dueDate(null, "DAILY", LocalDate.parse("2026-03-06"), 0))
                .isEqualTo(LocalDate.parse("2026-03-09"));
        assertThatThrownBy(() -> FilingCalendar.parse("T+X", "MONTHLY")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FilingCalendar.periodEnds("QUARTERLY", LocalDate.parse("2025-11-15"), LocalDate.parse("2026-07-01")))
                .containsExactly(LocalDate.parse("2025-12-31"), LocalDate.parse("2026-03-31"), LocalDate.parse("2026-06-30"));
        assertThat(FilingCalendar.periodEnds("WEEKLY", LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-14")))
                .containsExactly(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-08"));
        assertThat(FilingCalendar.periodEnds("AD_HOC", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"))).isEmpty();
        assertThat(FilingCalendar.latestPeriodEnd("MONTHLY", LocalDate.parse("2026-03-02"))).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(FilingCalendar.latestPeriodEnd("ANNUAL", LocalDate.parse("2026-03-02"))).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(FilingCalendar.periodStart("SEMI_ANNUAL", LocalDate.parse("2026-06-30"))).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(FilingCalendar.periodStart("QUARTERLY", LocalDate.parse("2026-03-31"))).isEqualTo(LocalDate.parse("2026-01-01"));
    }
}
