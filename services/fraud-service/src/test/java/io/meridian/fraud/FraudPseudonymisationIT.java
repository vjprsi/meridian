package io.meridian.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.domain.ListValues;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Identifiers never reach fraud-service storage in clear: evaluation contexts are pseudonymised with the tenant's key
 * before rules, features and persistence (same value -> same pseudonym per tenant, digests sent by callers included),
 * fraud lists hold keyed hashes, and the one-off operator migration re-pseudonymises or drops what was stored before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.fraud.ml.feature-consumer.enabled=false"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FraudPseudonymisationIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String IBAN = "GB33BUKB20201555555555";
    static final String MSISDN = "+254712000111";
    static final String EMAIL = "jane.doe@example.com";
    static final String DEVICE_ID = "ANDROID-77f3c2a9";
    static final String PAN = "4111 1111 1111 1111";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final String customer = UUID.randomUUID().toString();
    String ibanPseudonym;

    @Test
    @Order(1)
    void storedFeaturesCarryPseudonymsNeverTheClearIdentifiers() throws Exception {
        JsonNode r = evaluate(TENANT, """
                {"beneficiary_account":"%s","beneficiary_bic":"BUKBGB22","payee_msisdn":"%s","email":"%s",
                 "device_id":"%s","card_number":"%s","purpose_code":"SALA","note":"invoice 4111111111111111",
                 "payee_id":"%s","requested_execution_date":"2026-10-08","nested":{"iban":"%s"}}"""
                .formatted(IBAN, MSISDN, EMAIL, DEVICE_ID, PAN, UUID.randomUUID(), IBAN));
        JsonNode stored = get("/v1/risk/fraud/scores/" + r.get("fraud_score_id").asString(), TENANT);
        String text = stored.toString();
        for (String raw : List.of(IBAN, "GB33 BUKB", MSISDN, "254712000111", EMAIL, DEVICE_ID, "4111111111111111", PAN)) {
            assertThat(text).as("stored score must not contain " + raw).doesNotContain(raw);
        }
        JsonNode ctx = stored.get("features").get("context");
        ibanPseudonym = ctx.get("beneficiary_account").asString();
        assertThat(ibanPseudonym).startsWith("psn:").hasSize(36);
        for (String f : List.of("payee_msisdn", "email", "device_id", "card_number")) {
            assertThat(ctx.get(f).asString()).as(f).startsWith("psn:");
        }
        assertThat(ctx.get("note").asString()).startsWith("invoice psn:");   // a card number inside free text
        assertThat(ctx.get("nested").get("iban").asString()).isEqualTo(ibanPseudonym);   // same value, same pseudonym
        assertThat(ctx.get("purpose_code").asString()).isEqualTo("SALA");                // codes, ids, dates kept
        assertThat(ctx.get("beneficiary_bic").asString()).isEqualTo("BUKBGB22");
        assertThat(ctx.get("requested_execution_date").asString()).isEqualTo("2026-10-08");
        assertThat(ctx.get("payee_id").asString()).hasSize(36).doesNotStartWith("psn:");
        assertThat(stored.get("features").get("pseudonymisation").asString()).isEqualTo("h1");
    }

    @Test
    @Order(2)
    void aCallersDigestGivesTheSamePseudonymAndTenantsNeverShareOne() throws Exception {
        String digest = ListValues.hash("BENEFICIARY_ACCOUNT", IBAN);
        JsonNode r = evaluate(TENANT, "{\"beneficiary_account_digest\":\"" + digest + "\"}");
        JsonNode ctx = get("/v1/risk/fraud/scores/" + r.get("fraud_score_id").asString(), TENANT).get("features").get("context");
        assertThat(ctx.get("beneficiary_account").asString()).isEqualTo(ibanPseudonym);
        assertThat(ctx.has("beneficiary_account_digest")).isFalse();

        JsonNode other = evaluate(OTHER_TENANT, "{\"beneficiary_account\":\"" + IBAN + "\"}");
        String otherPseudonym = get("/v1/risk/fraud/scores/" + other.get("fraud_score_id").asString(), OTHER_TENANT)
                .get("features").get("context").get("beneficiary_account").asString();
        assertThat(otherPseudonym).startsWith("psn:").isNotEqualTo(ibanPseudonym);
    }

    @Test
    @Order(3)
    void listsStoreKeyedHashesAndMatchDigestsAndClearValues() throws Exception {
        String list = post("/v1/risk/fraud/lists", """
                {"code":"MULES_PSN","name":"Mules","list_type":"BLOCK","entity_type":"BENEFICIARY_ACCOUNT"}""", 201)
                .get("id").asString();
        JsonNode entry = post("/v1/risk/fraud/lists/" + list + "/entries", "{\"value\":\"GB33 BUKB 2020 1555 5555 55\"}", 200);
        assertThat(entry.get("value_hash").asString()).startsWith("h1:").hasSize(67)
                .isNotEqualTo(ListValues.hash("BENEFICIARY_ACCOUNT", IBAN));
        assertThat(evaluate(TENANT, "{\"beneficiary_account\":\"" + IBAN + "\"}").get("decision").asString()).isEqualTo("BLOCK");
        assertThat(evaluate(TENANT, "{\"beneficiary_account_digest\":\"" + ListValues.hash("BENEFICIARY_ACCOUNT", IBAN) + "\"}")
                .get("decision").asString()).isEqualTo("BLOCK");
    }

    @Test
    @Order(4)
    void operatorMigrationRePseudonymisesOrDropsWhatWasStoredBefore() throws Exception {
        UUID tenant = UUID.fromString(TENANT);
        String legacyIban = "DE89370400440532013000";
        String list = post("/v1/risk/fraud/lists", """
                {"code":"LEGACY_MULES","name":"Legacy","list_type":"BLOCK","entity_type":"BENEFICIARY_ACCOUNT"}""", 201)
                .get("id").asString();
        TenantContext.callAs(tenant, "test", () -> {
            // an evaluation, a list entry and feature-store keys as the service stored them before pseudonymisation
            jdbc.sql("""
                    insert into fraud_score (tenant_id, created_by, subject_type, subject_id, score, decision, reason_codes,
                        latency_ms, features, idempotency_key)
                    values (current_setting('app.tenant_id')::uuid, 'svc', 'PAYMENT', ?, 10, 'ALLOW', '[]'::jsonb, 1,
                        cast(? as jsonb), ?)""")
                    .params(UUID.randomUUID(), "{\"amount\":5,\"context\":{\"beneficiary_account\":\"" + legacyIban
                            + "\",\"payee_msisdn\":\"" + MSISDN + "\",\"purpose_code\":\"SALA\"}}", "legacy-" + UUID.randomUUID())
                    .update();
            jdbc.sql("""
                    insert into fraud_list_entry (tenant_id, created_by, updated_by, fraud_list_id, value_hash, value_masked)
                    values (current_setting('app.tenant_id')::uuid, 'svc', 'svc', ?, ?, '****3000')""")
                    .params(UUID.fromString(list), ListValues.hash("BENEFICIARY_ACCOUNT", legacyIban)).update();
            jdbc.sql("""
                    insert into fraud_feature_counter (tenant_id, created_by, updated_by, entity_type, entity_key, granularity,
                        bucket_start, currency, event_count, amount_sum)
                    values (current_setting('app.tenant_id')::uuid, 'svc', 'svc', 'BENEFICIARY', ?, 'H1',
                        date_trunc('hour', now()), 'USD', 3, 30)""")
                    .params("acct:" + "ab".repeat(20)).update();
            jdbc.sql("""
                    insert into fraud_feature_link (tenant_id, created_by, updated_by, link_type, from_key, to_key,
                        first_seen_at, last_seen_at, event_count)
                    values (current_setting('app.tenant_id')::uuid, 'svc', 'svc', 'CUSTOMER_BENEFICIARY', ?, ?, now(), now(), 1)""")
                    .params(customer, "msisdn:" + "cd".repeat(20)).update();
            return null;
        });

        JsonNode dry = post("/v1/risk/fraud/scores/pseudonymise-stored", "{\"dry_run\":true}", 200);
        assertThat(dry.get("scores_to_pseudonymise").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dry.get("list_entries_to_rekey").asLong()).isEqualTo(1);
        assertThat(dry.get("feature_rows_to_drop").asLong()).isEqualTo(2);

        JsonNode run = post("/v1/risk/fraud/scores/pseudonymise-stored", "{\"batch_size\":2}", 200);
        assertThat(run.get("complete").asBoolean()).as(run.toString()).isTrue();
        assertThat(run.get("list_entries_rekeyed").asLong()).isEqualTo(1);
        assertThat(run.get("feature_rows_dropped").asLong()).isEqualTo(2);

        String legacyContext = TenantContext.callAs(tenant, "test", () -> jdbc.sql(
                "select string_agg(features::text, ' ') from fraud_score").query(String.class).single());
        assertThat(legacyContext).doesNotContain(legacyIban).doesNotContain(MSISDN).contains("\"purpose_code\": \"SALA\"");
        // the re-pseudonymised value is the one new evaluations use: list matches and comparisons keep working
        JsonNode fresh = evaluate(TENANT, "{\"beneficiary_account\":\"" + legacyIban + "\"}");
        assertThat(fresh.get("decision").asString()).isEqualTo("BLOCK");
        String freshPseudonym = get("/v1/risk/fraud/scores/" + fresh.get("fraud_score_id").asString(), TENANT)
                .get("features").get("context").get("beneficiary_account").asString();
        assertThat(legacyContext).contains(freshPseudonym);

        JsonNode again = post("/v1/risk/fraud/scores/pseudonymise-stored", "{}", 200);
        assertThat(again.get("scores_pseudonymised").asLong()).isZero();
        assertThat(again.get("list_entries_rekeyed").asLong()).isZero();
        assertThat(again.get("complete").asBoolean()).isTrue();

        // fraud_score stays append-only outside the migration
        assertThatThrownBy(() -> TenantContext.callAs(tenant, "test", () -> jdbc.sql(
                "update fraud_score set features = '{}'::jsonb").update())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> TenantContext.callAs(tenant, "test", () -> jdbc.sql(
                "delete from fraud_score").update())).hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode evaluate(String tenant, String context) throws Exception {
        String body = """
                {"subject_type":"PAYMENT","subject_id":"%s","customer_id":"%s","amount":"25.00","currency":"USD",
                 "channel":"MOBILE_APP","context":%s}""".formatted(UUID.randomUUID(), customer, context);
        HttpResponse<String> r = send("POST", "/v1/risk/fraud/scores/evaluate", body, UUID.randomUUID().toString(), tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        if (body == null) {
            return json.createObjectNode();
        }
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String key, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-Tenant-Id", tenant).header("X-User", "analyst")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
