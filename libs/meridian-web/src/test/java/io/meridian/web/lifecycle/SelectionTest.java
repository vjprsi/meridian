package io.meridian.web.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.web.lifecycle.DataLifecycleService.Column;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SelectionTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final UUID C1 = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    // wallet -> customer_id; wallet_favorite -> wallet_id (parent); notification -> polymorphic owner; ledger_entry: none
    static final PersonalDataCatalog CATALOG = PersonalDataCatalog.of(JSON.readTree("""
            {"wallet": {"pii": ["msisdn"], "subject": [{"column": "customer_id"}]},
             "wallet_favorite": {"pii": ["target_msisdn"], "subject": [{"column": "wallet_id", "parent": "wallet"}]},
             "notification": {"pii": ["destination", "body"], "subject": [{"column": "recipient_id",
                 "type_column": "recipient_type", "type_value": "CUSTOMER"}], "hidden": ["secret_x"]},
             "ledger_entry": {"pii": ["counterparty_name"], "subject": [], "immutable": true}}"""));
    static final Map<String, Set<String>> COLUMNS = Map.of(
            "wallet", Set.of("id", "customer_id", "msisdn", "created_at"),
            "wallet_favorite", Set.of("id", "wallet_id", "target_msisdn", "created_at"),
            "notification", Set.of("id", "recipient_id", "recipient_type", "destination", "body", "created_at"),
            "ledger_entry", Set.of("id", "counterparty_name", "created_at", "document_id"));

    static Selection.Sql predicate(String table, String body) {
        return Selection.from(JSON.readTree(body)).predicate(table, CATALOG.table(table).orElse(null), COLUMNS.get(table),
                t -> COLUMNS.getOrDefault(t, Set.of()), t -> CATALOG.table(t).orElse(null));
    }

    @Test
    void customerSelectionFollowsDirectPolymorphicAndParentPaths() {
        String sel = "{\"record_selector\": {\"customer_ids\": [\"" + C1 + "\"]}}";
        assertThat(predicate("wallet", sel).where()).isEqualTo(" and (t.customer_id = any(?::uuid[]))");
        assertThat(predicate("notification", sel).where())
                .isEqualTo(" and ((t.recipient_type = ? and t.recipient_id = any(?::uuid[])))");
        assertThat(predicate("notification", sel).args()).containsExactly("CUSTOMER", "{" + C1 + "}");
        assertThat(predicate("wallet_favorite", sel).where())
                .isEqualTo(" and (t.wallet_id in (select p0.id from wallet p0 where (p0.customer_id = any(?::uuid[]))))");
    }

    @Test
    void cutoffAndLegalHoldExclusions() {
        Selection.Sql p = predicate("wallet", "{\"created_before\": \"2020-01-31\", \"exclude\": {\"customer_ids\": [\"" + C1 + "\"]}}");
        assertThat(p.where()).isEqualTo(" and t.created_at < ? and not coalesce((t.customer_id = any(?::uuid[])), false)");
        assertThat(p.args().get(0).toString()).startsWith("2020-01-31T00:00Z");
        // a hold on a customer cannot concern rows that are not linked to any customer: nothing to exclude
        assertThat(predicate("ledger_entry", "{\"created_before\": \"2020-01-31\", \"exclude\": {\"customer_ids\": [\"" + C1 + "\"]}}")
                .where()).isEqualTo(" and t.created_at < ?");
        // document holds do apply where the table references documents
        assertThat(predicate("ledger_entry", "{\"exclude\": {\"document_ids\": [\"" + C1 + "\"]}}").where())
                .isEqualTo(" and not coalesce((t.document_id = any(?::uuid[])), false)");
    }

    @Test
    void selectingByAnEntityTheTableCannotReachIsRefused() {
        assertThatThrownBy(() -> predicate("ledger_entry", "{\"record_selector\": {\"customer_ids\": [\"" + C1 + "\"]}}"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot be selected by customer_ids");
        assertThatThrownBy(() -> predicate("wallet", "{\"record_selector\": {\"customer_ids\": [\"not-a-uuid\"]}}"))
                .isInstanceOf(ApiException.class);
        JsonNode empty = JSON.readTree("{}");
        assertThat(Selection.from(empty).restricted()).isFalse();
    }

    @Test
    void anonymisedValuesKeepConstraintsSatisfied() {
        assertThat(DataLifecycleService.replacement(new Column("email", "character varying", true, 320, false))).isEqualTo("null");
        assertThat(DataLifecycleService.replacement(new Column("msisdn", "character varying", false, 20, false)))
                .isEqualTo("left('erased:' || md5(t.id::text), 20)");
        assertThat(DataLifecycleService.replacement(new Column("body", "text", false, null, false)))
                .isEqualTo("left('erased:' || md5(t.id::text), 40)");
        assertThat(DataLifecycleService.replacement(new Column("data", "jsonb", false, null, false))).isEqualTo("'{}'::jsonb");
        assertThat(DataLifecycleService.replacement(new Column("dob", "date", false, null, false))).isEqualTo("date '1900-01-01'");
        assertThat(CATALOG.subjectTables()).extracting(PersonalDataCatalog.TableInfo::table)
                .containsExactly("wallet", "wallet_favorite", "notification");
        assertThat(CATALOG.table("notification").orElseThrow().hidden()).containsExactly("secret_x");
        assertThat(List.of(CATALOG.table("ledger_entry").orElseThrow().immutable())).containsExactly(true);
    }
}
