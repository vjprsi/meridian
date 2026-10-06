package io.meridian.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enterprise search over HTTP on the PostgreSQL full-text engine (no OpenSearch needed): index definitions,
 * ingestion, federated ranked queries with synonyms and filters, PII masking, suggestions, saved searches,
 * zero-downtime reindex with alias swap, catch-up, health and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.search.batch-size=2"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String OWNER = "0199a000-0000-7000-8000-0000000000e1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    String customers;
    String savedId;

    @Test
    @Order(1)
    void indexDefinitionsAreValidated() throws Exception {
        String mapping = "{\"properties\":{\"full_name\":{\"type\":\"text\"},\"email\":{\"type\":\"keyword\"},"
                + "\"phone\":{\"type\":\"keyword\"},\"city\":{\"type\":\"text\"},\"segment\":{\"type\":\"keyword\"}}}";
        assertThat(post("/v1/search/indexes", index("CUSTOMER", "customers", mapping, "[\"full_name^3\",\"nickname\"]",
                "[\"meridian.party.v1\"]"), 422).get("code").asString()).isEqualTo("unmapped_field");
        assertThat(post("/v1/search/indexes", index("CUSTOMER", "customers", mapping, "[\"full_name^3\"]",
                "[\"party-topic\"]"), 422).get("code").asString()).isEqualTo("invalid_source_topics");
        JsonNode idx = post("/v1/search/indexes", index("CUSTOMER", "customers", mapping,
                "[\"full_name^3\",\"email\",\"phone\",\"city^2\"]", "[\"meridian.party.v1\"]"), 201);
        customers = idx.get("id").asString();
        assertThat(idx.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(idx.get("physical_index").asString()).startsWith("customers_v1_");
        post("/v1/search/indexes", index("PAYMENT", "payments",
                "{\"properties\":{\"reference\":{},\"payer_name\":{},\"amount\":{}}}", "[\"reference^3\",\"payer_name\"]",
                "[\"meridian.payments.v1\"]").replace(",\"masked_fields\":[\"email\",\"phone\"]", ""), 201);
        assertThat(post("/v1/search/indexes", index("CARD", "customers", mapping, "[\"full_name\"]", "[\"meridian.cards.v1\"]"),
                409).get("code").asString()).isEqualTo("alias_in_use");
    }

    @Test
    @Order(2)
    void documentsAreIngested() throws Exception {
        JsonNode r = post("/v1/search/documents", """
                {"documents":[
                 {"entity_type":"CUSTOMER","entity_id":"c1","version":1,"document":{"full_name":"Mohammed Ali","email":"mo@example.com","phone":"+254700123456","city":"Nairobi","segment":"RETAIL"}},
                 {"entity_type":"CUSTOMER","entity_id":"c2","version":1,"document":{"full_name":"Muhammad Hassan","email":"mh@example.com","phone":"+254711000222","city":"Mombasa","segment":"RETAIL"}},
                 {"entity_type":"CUSTOMER","entity_id":"c3","version":1,"document":{"full_name":"Jane Doe","email":"jane@example.com","phone":"+254722333444","city":"Nairobi","segment":"PREMIUM"}},
                 {"entity_type":"PAYMENT","entity_id":"p1","document":{"reference":"INV-2026-001","payer_name":"Jane Doe","amount":"120.00"}}]}""", 200);
        assertThat(r.get("indexed").asInt()).isEqualTo(4);
        // stale version is ignored (out-of-order event)
        post("/v1/search/documents", """
                {"documents":[{"entity_type":"CUSTOMER","entity_id":"c1","version":0,"document":{"full_name":"Old Name"}}]}""", 200);
        assertThat(post("/v1/search/documents", """
                {"documents":[{"entity_type":"WALLET","entity_id":"w1","document":{"x":1}}]}""", 422).get("code").asString())
                .isEqualTo("index_not_found");
        // the batch is the declared contract: a bare document (no documents array) is rejected
        assertThat(post("/v1/search/documents", """
                {"entity_type":"CUSTOMER","entity_id":"c5","document":{"full_name":"X"}}""", 400).get("code").asString())
                .isEqualTo("validation_failed");
    }

    @Test
    @Order(3)
    void federatedQueryRanksAndMasksPii() throws Exception {
        JsonNode res = post("/v1/search/query", "{\"query_text\":\"jane\"}", 200);
        assertThat(ids(res)).containsExactlyInAnyOrder("c3", "p1");
        JsonNode customer = hit(res, "c3");
        assertThat(customer.get("title").asString()).isEqualTo("Jane Doe");
        assertThat(customer.get("document").get("email").asString()).isEqualTo("j***@example.com");
        assertThat(customer.get("document").get("phone").asString()).isEqualTo("********3444");
        assertThat(res.get("masked").asBoolean()).isTrue();

        HttpResponse<String> pii = send("POST", "/v1/search/query", "{\"query_text\":\"jane\"}", UUID.randomUUID().toString(),
                TENANT, "dev-user", "pii:read");
        JsonNode unmasked = hit(json.readTree(pii.body()), "c3");
        assertThat(unmasked.get("document").get("email").asString()).isEqualTo("jane@example.com");

        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"254700123456\"}", 200))).containsExactly("c1");
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"mohammed\"}", 200))).containsExactly("c1");
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"old\"}", 200))).isEmpty();
        assertThat(ids(post("/v1/search/query",
                "{\"query_text\":\"nairobi\",\"filters\":{\"segment\":\"PREMIUM\"},\"entity_types\":[\"CUSTOMER\"]}", 200)))
                .containsExactly("c3");
        assertThat(post("/v1/search/query", "{\"query_text\":\"x\",\"entity_types\":[\"SPACESHIP\"]}", 422)
                .get("code").asString()).isEqualTo("unknown_entity_type");
        assertThat(post("/v1/search/query", "{\"query_text\":\"x\",\"entity_types\":{\"types\":[\"CUSTOMER\"]}}", 400)
                .get("code").asString()).isEqualTo("malformed_request");
        assertThat(post("/v1/search/query", "{}", 400).get("code").asString()).isEqualTo("query_required");

        JsonNode page1 = post("/v1/search/query", "{\"query_text\":\"nairobi\",\"limit\":1}", 200);
        assertThat(page1.get("data")).hasSize(1);
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        JsonNode page2 = post("/v1/search/query", "{\"query_text\":\"nairobi\",\"limit\":1,\"cursor\":\""
                + page1.get("next_cursor").asString() + "\"}", 200);
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        assertThat(ids(page1).getFirst()).isNotEqualTo(ids(page2).getFirst());
    }

    @Test
    @Order(4)
    void synonymsExpandQueries() throws Exception {
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"mohamed\"}", 200))).isEmpty();
        assertThat(post("/v1/search/synonyms", "{\"terms\":[\"solo\"]}", 422).get("code").asString())
                .isEqualTo("invalid_synonyms");
        JsonNode syn = post("/v1/search/synonyms",
                "{\"search_index_id\":\"" + customers + "\",\"terms\":[\"Mohammed\",\"Muhammad\",\"Mohamed\",\"mohamed\"]}", 201);
        assertThat(syn.get("terms").toString()).isEqualTo("[\"mohammed\",\"muhammad\",\"mohamed\"]");
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"mohamed\"}", 200))).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    @Order(5)
    void suggestions() throws Exception {
        JsonNode s = get("/v1/search/suggest?q=ja", TENANT);
        assertThat(s.get("data")).hasSize(1);
        assertThat(s.get("data").get(0).get("text").asString()).isEqualTo("Jane Doe");
        assertThat(get("/v1/search/suggest?q=inv&entity_type=PAYMENT", TENANT).get("data").get(0).get("entity_id").asString())
                .isEqualTo("p1");
        assertThat(send("GET", "/v1/search/suggest?q=j", null, null, TENANT, "dev-user", null).statusCode()).isEqualTo(400);
    }

    @Test
    @Order(6)
    void savedSearchesRunForTheirOwner() throws Exception {
        assertThat(post("/v1/search/saved", "{\"app_user_id\":\"" + OWNER + "\",\"name\":\"bad\",\"entity_types\":[\"PLANET\"],"
                + "\"query_text\":\"x\"}", 422).get("code").asString()).isEqualTo("unknown_entity_type");
        JsonNode saved = post("/v1/search/saved", "{\"app_user_id\":\"" + OWNER + "\",\"name\":\"Nairobi customers\","
                + "\"entity_types\":[\"CUSTOMER\"],\"query_text\":\"nairobi\"}", 201);
        savedId = saved.get("id").asString();
        HttpResponse<String> other = send("POST", "/v1/search/saved/" + savedId + "/run", "{}", UUID.randomUUID().toString(),
                TENANT, UUID.randomUUID().toString(), null);
        assertThat(other.statusCode()).isEqualTo(403);
        HttpResponse<String> mine = send("POST", "/v1/search/saved/" + savedId + "/run", "{\"limit\":10}",
                UUID.randomUUID().toString(), TENANT, OWNER, null);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        assertThat(ids(json.readTree(mine.body()))).containsExactlyInAnyOrder("c1", "c3");
        assertThat(get("/v1/search/saved/" + savedId, TENANT).has("last_used_at")).isTrue();
    }

    @Test
    @Order(7)
    void reindexBuildsNewPhysicalIndexAndSwapsAlias() throws Exception {
        JsonNode before = get("/v1/search/indexes/" + customers, TENANT);
        String oldPhysical = before.get("physical_index").asString();
        HttpResponse<String> patch = send("PATCH", "/v1/search/indexes/" + customers,
                "{\"searchable_fields\":[\"full_name^3\",\"email\",\"phone\",\"city^2\",\"segment\"]}", null, TENANT,
                "dev-user", null, before.get("row_version").asString());
        assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
        assertThat(json.readTree(patch.body()).get("mapping_version").asInt()).isEqualTo(2);
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"premium\"}", 200))).isEmpty(); // old mapping still live

        JsonNode job = post("/v1/search/indexes/" + customers + "/reindex", null, 200);
        assertThat(job.get("job_type").asString()).isEqualTo("FULL_REINDEX");
        assertThat(job.get("target_index").asString()).startsWith("customers_v2_");
        JsonNode done = awaitJob(job.get("id").asString());
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("documents_processed").asLong()).isEqualTo(4); // journal replay: c1, c2, c3, stale c1
        JsonNode after = get("/v1/search/indexes/" + customers, TENANT);
        assertThat(after.get("physical_index").asString()).isEqualTo(job.get("target_index").asString()).isNotEqualTo(oldPhysical);
        assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("document_count").asLong()).isEqualTo(3);
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"premium\"}", 200))).containsExactly("c3");
        assertThat(post("/v1/search/jobs/" + job.get("id").asString() + "/cancel", null, 409).get("code").asString())
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(8)
    void deletesAndCatchUp() throws Exception {
        HttpResponse<String> removed = send("DELETE", "/v1/search/documents/CUSTOMER/c2", null, UUID.randomUUID().toString(),
                TENANT, "dev-user", null);
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);
        assertThat(json.readTree(removed.body()).get("operation").asString()).isEqualTo("DELETE");
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"hassan\"}", 200))).isEmpty();
        assertThat(post("/v1/search/indexes/" + customers + "/catch-up",
                "{\"from_timestamp\":\"" + OffsetDateTime.now().plusHours(1) + "\"}", 422).get("code").asString())
                .isEqualTo("from_timestamp_in_future");
        JsonNode job = post("/v1/search/indexes/" + customers + "/catch-up",
                "{\"from_timestamp\":\"" + OffsetDateTime.now().minusHours(1) + "\"}", 200);
        JsonNode done = awaitJob(job.get("id").asString());
        assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.get("documents_processed").asLong()).isEqualTo(5); // 3 + stale c1 + delete c2
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"hassan\"}", 200))).isEmpty();
        assertThat(ids(post("/v1/search/query", "{\"query_text\":\"mohammed\"}", 200))).containsExactly("c1");
    }

    @Test
    @Order(9)
    void statsFlagDegradedIndexOnLag() throws Exception {
        JsonNode healthy = get("/v1/search/indexes/" + customers + "/stats", TENANT);
        assertThat(healthy.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(healthy.get("document_count").asLong()).isEqualTo(2);
        post("/v1/search/documents", "{\"documents\":[{\"entity_type\":\"CUSTOMER\",\"entity_id\":\"c9\",\"occurred_at\":\""
                + OffsetDateTime.now().minusHours(3) + "\",\"document\":{\"full_name\":\"Late Event\"}}]}", 200);
        JsonNode degraded = get("/v1/search/indexes/" + customers + "/stats", TENANT);
        assertThat(degraded.get("status").asString()).isEqualTo("DEGRADED");
        assertThat(degraded.get("lag_seconds").asInt()).isGreaterThan(300);
        List<String> types = jdbc.sql("select distinct event_type from outbox").query(String.class).list();
        assertThat(types).contains("operations.search_index.reindexed", "operations.search_index.degraded");
    }

    @Test
    @Order(10)
    void otherTenantSeesNothing() throws Exception {
        HttpResponse<String> r = send("POST", "/v1/search/query", "{\"query_text\":\"jane\"}", UUID.randomUUID().toString(),
                OTHER_TENANT, "x", "pii:read");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json.readTree(r.body()).get("data")).isEmpty();
        assertThat(send("GET", "/v1/search/indexes/" + customers, null, null, OTHER_TENANT, "x", null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/search/saved/" + savedId + "/run", "{}", UUID.randomUUID().toString(), OTHER_TENANT, "x",
                null).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private static String index(String type, String alias, String mapping, String fields, String topics) {
        return """
                {"entity_type":"%s","alias_name":"%s","mapping":%s,"searchable_fields":%s,
                 "source_topics":%s,"masked_fields":["email","phone"]}"""
                .formatted(type, alias, mapping, fields, topics);
    }

    private static List<String> ids(JsonNode res) {
        List<String> out = new ArrayList<>();
        res.get("data").forEach(d -> out.add(d.get("entity_id").asString()));
        return out;
    }

    private static JsonNode hit(JsonNode res, String id) {
        for (JsonNode d : res.get("data")) {
            if (d.get("entity_id").asString().equals(id)) {
                return d;
            }
        }
        throw new AssertionError("no hit " + id + " in " + res);
    }

    private JsonNode awaitJob(String id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode j = get("/v1/search/jobs/" + id, TENANT);
            String s = j.get("status").asString();
            if (!s.equals("QUEUED") && !s.equals("RUNNING")) {
                return j;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, "dev-user", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "dev-user", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user,
                                      String permissions) throws Exception {
        return send(method, path, body, idemKey, tenant, user, permissions, null);
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user,
                                      String permissions, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (permissions != null) {
            b.header("X-Permissions", permissions);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
