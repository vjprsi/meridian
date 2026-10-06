package io.meridian.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QueryDslTest {
    static final Map<String, String> FIELDS = DatasetCatalog.find("loans.loans").orElseThrow().fields();

    static QueryDsl.Query parse(String q) {
        return QueryDsl.parse(q, name -> DatasetCatalog.find(name).map(DatasetCatalog.Dataset::fields));
    }

    static Map<String, Object> loan(String id, String status, String product, String outstanding, int dpd) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("status", status);
        m.put("product_id", product);
        m.put("outstanding_principal", outstanding == null ? null : new BigDecimal(outstanding));
        m.put("days_past_due", new BigDecimal(dpd));
        m.put("currency", "NGN");
        return m;
    }

    static final List<Map<String, Object>> LOANS = List.of(
            loan("l1", "ACTIVE", "p1", "100.00", 0),
            loan("l2", "ACTIVE", "p1", "250.50", 45),
            loan("l3", "ARREARS", "p2", "75.25", 95),
            loan("l4", "CLOSED", "p2", "0", 0),
            loan("l5", "ACTIVE", "p2", null, 10));

    @Test
    void groupedAggregatesOrderedAndLimited() {
        QueryDsl.Query q = parse("""
                select status, count(*) as n, sum(outstanding_principal) as total, avg(days_past_due) as avg_dpd,
                       count_distinct(product_id) AS products
                from loans.loans where status != 'CLOSED' group by status order by total desc limit 10""");
        assertThat(q.dataset()).isEqualTo("loans.loans");
        assertThat(q.aggregated()).isTrue();
        QueryDsl.Result r = QueryDsl.execute(q, FIELDS, LOANS, Map.of(), null, 100);
        assertThat(r.columns()).containsExactly("status", "n", "total", "avg_dpd", "products");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).get("status")).isEqualTo("ACTIVE");
        assertThat((BigDecimal) r.rows().get(0).get("n")).isEqualByComparingTo("3");
        assertThat((BigDecimal) r.rows().get(0).get("total")).isEqualByComparingTo("350.50");
        assertThat((BigDecimal) r.rows().get(0).get("products")).isEqualByComparingTo("2");
        assertThat((BigDecimal) r.rows().get(1).get("avg_dpd")).isEqualByComparingTo("95");
        assertThat(r.matched()).isEqualTo(4);
    }

    @Test
    void projectionsParametersNullOrderingAndTruncation() {
        QueryDsl.Query q = parse("SELECT id, outstanding_principal * 2 AS doubled FROM loans.loans "
                + "WHERE days_past_due >= :min_dpd OR id IN ('l5') ORDER BY doubled DESC LIMIT 2");
        assertThat(q.parameters()).containsExactly("min_dpd");
        QueryDsl.Result r = QueryDsl.execute(q, FIELDS, LOANS, Map.of("min_dpd", new BigDecimal("10")), null, 100);
        assertThat(r.rows()).extracting(m -> m.get("id")).containsExactly("l2", "l3");
        assertThat(r.truncated()).isTrue();
        QueryDsl.Result all = QueryDsl.execute(parse("SELECT id, outstanding_principal FROM loans.loans ORDER BY outstanding_principal"),
                FIELDS, LOANS, Map.of(), null, 100);
        assertThat(all.rows().getLast().get("id")).isEqualTo("l5"); // nulls last
        QueryDsl.Result star = QueryDsl.execute(parse("SELECT * FROM loans.loans WHERE id = 'l1'"), FIELDS, LOANS, Map.of(),
                r2 -> true, 100);
        assertThat(star.columns()).containsAll(List.of("id", "status", "outstanding_principal"));
        QueryDsl.Result empty = QueryDsl.execute(parse("SELECT COUNT(*) AS n, SUM(outstanding_principal) AS s FROM loans.loans WHERE id = 'x'"),
                FIELDS, LOANS, Map.of(), null, 100);
        assertThat(empty.rows()).hasSize(1);
        assertThat((BigDecimal) empty.rows().getFirst().get("n")).isEqualByComparingTo("0");
        // Keywords inside literals do not split clauses.
        assertThat(parse("SELECT id FROM loans.loans WHERE status = 'FROM WHERE LIMIT'").where().source())
                .isEqualTo(" status = 'FROM WHERE LIMIT'");
    }

    @Test
    void rejectsAnythingButOneReadOnlyQueryOverAWhitelistedDataset() {
        assertThatThrownBy(() -> parse("DELETE FROM loans.loans")).hasMessageContaining("must start with SELECT");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans; DROP TABLE x")).hasMessageContaining("separators");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans -- comment")).hasMessageContaining("comments");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans l JOIN customers.customers c")).hasMessageContaining("one dataset");
        assertThatThrownBy(() -> parse("SELECT id FROM pg_catalog.pg_user")).hasMessageContaining("unknown dataset");
        assertThatThrownBy(() -> parse("SELECT id FROM (SELECT 1)")).hasMessageContaining("one dataset");
        assertThatThrownBy(() -> parse("SELECT password FROM customers.customers")).hasMessageContaining("unknown field password");
        assertThatThrownBy(() -> parse("SELECT status, id, COUNT(*) FROM loans.loans GROUP BY status"))
                .hasMessageContaining("column id must be aggregated");
        assertThatThrownBy(() -> parse("SELECT SUM(outstanding_principal) + 1 FROM loans.loans"))
                .hasMessageContaining("aggregates cannot be combined");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans LIMIT 0")).hasMessageContaining("LIMIT must be");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans ORDER BY status")).hasMessageContaining("not an output column");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans WHERE id = 1 WHERE id = 2")).hasMessageContaining("more than once");
        assertThatThrownBy(() -> parse("SELECT id FROM loans.loans LIMIT 5 WHERE id = 1")).hasMessageContaining("out of order");
        assertThatThrownBy(() -> parse("SELECT id AS a, status AS a FROM loans.loans")).hasMessageContaining("duplicate column");
        assertThatThrownBy(() -> parse("SELECT SUM(*) FROM loans.loans")).hasMessageContaining("only COUNT accepts *");
        assertThatThrownBy(() -> parse("SELECT exec('x') FROM loans.loans")).hasMessageContaining("unknown function");
        assertThatThrownBy(() -> QueryDsl.parse("SELECT id FROM loans.loans", n -> Optional.empty()))
                .isInstanceOf(QueryDsl.QueryException.class);
    }

    @Test
    void filtersApplyLikeTheQueryLanguage() {
        List<String> errors = new java.util.ArrayList<>();
        List<Filters.Filter> f = Filters.parse(List.of(Map.of("field", "status", "op", "IN", "value", List.of("ACTIVE", "ARREARS")),
                Map.of("field", "days_past_due", "op", ">=", "value", "10"), Map.of("field", "region", "value", "NG")), errors);
        assertThat(errors).isEmpty();
        var p = Filters.predicate(f, FIELDS.keySet()); // region is not a loans field: ignored
        assertThat(LOANS.stream().filter(p).map(m -> m.get("id")).toList()).containsExactly("l2", "l3", "l5");
        Filters.parse(List.of(Map.of("field", "x;y", "op", "="), Map.of("field", "a", "op", "LIKE"),
                Map.of("field", "a", "op", "IN", "value", "x")), errors);
        assertThat(errors).hasSize(3);
    }
}
