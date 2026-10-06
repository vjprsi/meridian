package io.meridian.archive.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ArchiveDomainTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HoldMatcher.Hold hold(String id, String scope, String... ids) {
        return new HoldMatcher.Hold(id, "matter " + id, scope, Set.of(ids), null);
    }

    @Test
    void holdsBlockTargetedSelectionsAndExcludeOthers() {
        List<HoldMatcher.Hold> holds = List.of(hold("h1", "CUSTOMER", "c1"), hold("h2", "ACCOUNT", "a9"));
        HoldMatcher.Decision targeted = HoldMatcher.decide(holds, "customer_db", "customer", Map.of("CUSTOMER", Set.of("c1", "c2")));
        assertThat(targeted.blocked()).isTrue();
        assertThat(targeted.blockedBy().id()).isEqualTo("h1");
        HoldMatcher.Decision range = HoldMatcher.decide(holds, "customer_db", "customer", Map.of());
        assertThat(range.blocked()).isFalse();
        assertThat(range.exclusions()).containsEntry("CUSTOMER", Set.of("c1")).containsEntry("ACCOUNT", Set.of("a9"));
    }

    @Test
    void tableAndTenantHoldsBlockEverythingInScope() {
        assertThat(HoldMatcher.decide(List.of(hold("t", "TABLE", "ledger_db.journal_entry")), "ledger_db", "journal_entry",
                Map.of()).blocked()).isTrue();
        assertThat(HoldMatcher.decide(List.of(hold("t", "TABLE", "journal_entry")), "ledger_db", "ledger_entry",
                Map.of()).blocked()).isFalse();
        assertThat(HoldMatcher.decide(List.of(hold("all", "TENANT")), "x", "y", Map.of()).blocked()).isTrue();
        assertThat(HoldMatcher.matching(List.of(hold("all", "TENANT")), "CASE", "k1")).hasSize(1);
        assertThat(HoldMatcher.matching(List.of(hold("h1", "CUSTOMER", "c1")), "ACCOUNT", "c1")).isEmpty();
    }

    @Test
    void retentionArithmeticAndStateRules() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThat(ArchiveRules.archiveCutoff(today, 365)).isEqualTo(LocalDate.of(2025, 10, 6));
        assertThat(ArchiveRules.purgeCutoff(today, 7)).isEqualTo(LocalDate.of(2019, 10, 6));
        assertThat(ArchiveRules.wormRetainUntil(LocalDate.of(2020, 2, 29), 7)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThatThrownBy(() -> ArchiveRules.requirePolicyShape(365, 0)).extracting("code").isEqualTo("invalid_retention_years");
        assertThatThrownBy(() -> ArchiveRules.requirePolicyShape(5000, 5)).extracting("code").isEqualTo("online_exceeds_retention");
        assertThatThrownBy(() -> ArchiveRules.requireExecute("BLOCKED_BY_HOLD")).extracting("code").isEqualTo("blocked_by_legal_hold");
        assertThatThrownBy(() -> ArchiveRules.requireExecute("PENDING_APPROVAL")).extracting("status").isEqualTo(409);
        assertThatThrownBy(() -> ArchiveRules.requirePurgeAction("ARCHIVE")).extracting("status").isEqualTo(422);
        ArchiveRules.requireExecute("APPROVED");
        ArchiveRules.requireCancel("BLOCKED_BY_HOLD");
    }

    @Test
    void certificateHashIsIndependentOfKeyOrder() {
        String a = Certificate.hash(JSON.readTree("{\"b\":1,\"a\":{\"y\":[1,2],\"x\":\"v\"}}"));
        String b = Certificate.hash(JSON.readTree("{\"a\":{\"x\":\"v\",\"y\":[1,2]},\"b\":1}"));
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(Certificate.hash(JSON.readTree("{\"a\":{\"x\":\"v\",\"y\":[2,1]},\"b\":1}"))).isNotEqualTo(a);
    }

    @Test
    void catalogueParsingAndSuggestions() {
        String md = """
                ### Service `customer-service` — database `customer_db`

                #### `customer`

                | Column | Type | Null | Key / ref | API field | Description |
                | --- | --- | --- | --- | --- | --- |
                | id | uuid | no | PK | id (read-only) | Unique id |
                | full_name | varchar(200) | no |  | full_name | Legal name PII |
                | email | varchar(320) | yes |  | email | Contact email |

                #### `customer_segment`

                | Column | Type | Null | Key / ref | API field | Description |
                | --- | --- | --- | --- | --- | --- |
                | code | varchar(20) | no |  | code | Segment code |

                ### Service `ledger-service` — database `ledger_db`

                #### `journal_entry`

                | Column | Type | Null | Key / ref | API field | Description |
                | --- | --- | --- | --- | --- | --- |
                | hash | varchar(64) | no |  | — | Chain hash |
                """;
        List<CatalogAdvisor.Table> tables = CatalogAdvisor.parse(md);
        assertThat(tables).extracting(CatalogAdvisor.Table::table).containsExactly("customer", "customer_segment", "journal_entry");
        assertThat(tables.getFirst().piiColumns()).containsExactly("full_name", "email");
        assertThat(tables.get(2).database()).isEqualTo("ledger_db");
        CatalogAdvisor.Suggestion c = CatalogAdvisor.suggest(tables.getFirst());
        assertThat(c.dataClass()).isEqualTo("CUSTOMER");
        assertThat(c.sensitivity()).isEqualTo("PII");
        CatalogAdvisor.Suggestion j = CatalogAdvisor.suggest(tables.get(2));
        assertThat(j.dataClass()).isEqualTo("TRANSACTION");
        assertThat(j.sensitivity()).isEqualTo("RESTRICTED");
    }
}
