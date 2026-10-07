package io.meridian.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The indexing feed end to end over real Kafka and PostgreSQL: binary CloudEvents published on the domain topics
 * become searchable for their tenant only (customer by name and number, account by number, payment by reference
 * and counterparty, card by last4 — never the PAN), with replay / out-of-order idempotency, lifecycle (closed,
 * erased), and dead-lettering of unmapped or broken events without blocking the partition.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.search.indexer.enabled=true", "meridian.search.indexer.group-id=search-indexer-it",
                "meridian.search.indexer.metadata-max-age-ms=500",
                "meridian.contact-lookup.key=test-contact-lookup-key-0123456789"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchIndexerIT {
    static final String TENANT_A = "0199a000-0000-7000-8000-0000000000d1";
    static final String TENANT_B = "0199a000-0000-7000-8000-0000000000d2";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String CUSTOMER_B = "0199a000-0000-7000-8000-00000000c002";
    static final String ACCOUNT = "0199a000-0000-7000-8000-00000000a001";
    static final String PAYMENT = "0199a000-0000-7000-8000-00000000b001";
    static final String CARD = "0199a000-0000-7000-8000-00000000d001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    MeterRegistry meters;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Order(1)
    void eventsBecomeSearchableForTheirTenantOnly() throws Exception {
        publish("meridian.party.v1", "party.customer.created", TENANT_A, CUSTOMER, """
                {"id":"%s","customer_number":"CUS-000123","customer_type":"INDIVIDUAL","status":"ACTIVE",
                 "first_name":"Amina","last_name":"Odhiambo","date_of_birth":"1990-04-01","annual_income":"85000",
                 "updated_at":"2026-10-01T10:00:00.000001+00:00","row_version":1}""".formatted(CUSTOMER));
        publish("meridian.party.v1", "party.customer.created", TENANT_B, CUSTOMER_B, """
                {"id":"%s","customer_number":"CUS-900001","customer_type":"INDIVIDUAL","status":"ACTIVE",
                 "display_name":"Amina Kamau","updated_at":"2026-10-01T10:00:00Z","row_version":1}""".formatted(CUSTOMER_B));
        publish("meridian.accounts.v1", "accounts.deposit_account.opened", TENANT_A, ACCOUNT, """
                {"id":"%s","account_number":"0012345678","iban":"KE12MRDN0012345678","account_type":"SAVINGS",
                 "nickname":"Rainy day","currency":"KES","status":"ACTIVE","customer_id":"%s",
                 "updated_at":"2026-10-01T10:01:00Z","row_version":1}""".formatted(ACCOUNT, CUSTOMER));
        publish("meridian.payments.v1", "payments.payment.initiated", TENANT_A, PAYMENT, """
                {"id":"%s","reference":"INV-7781","end_to_end_id":"E2E-55AA","payment_type":"DOMESTIC","direction":"OUTBOUND",
                 "debtor_name":"Amina Odhiambo","creditor_name":"Acme Supplies","creditor_account_number":"9988776655",
                 "amount":"1500.00","currency":"KES","status":"INITIATED","customer_id":"%s",
                 "remittance_unstructured":"rent for flat 4B","updated_at":"2026-10-01T10:02:00Z","row_version":1}"""
                .formatted(PAYMENT, CUSTOMER));
        publish("meridian.cards.v1", "cards.card.created", TENANT_A, CARD, """
                {"id":"%s","customer_id":"%s","pan":"4111111111111111","pan_token":"tok_9f8e7d","cvv":"123",
                 "last4":"1111","bin":"411111","cardholder_name":"Amina Odhiambo","form_factor":"PHYSICAL","status":"CREATED",
                 "expiry_month":12,"expiry_year":2030,"updated_at":"2026-10-01T10:03:00Z","row_version":1}"""
                .formatted(CARD, CUSTOMER));

        // customer by name and by number
        JsonNode byName = await(TENANT_A, "{\"query_text\":\"odhiambo\",\"entity_types\":[\"CUSTOMER\"]}", r -> ids(r).size() == 1);
        assertThat(ids(byName)).containsExactly(CUSTOMER);
        JsonNode customer = byName.get("data").get(0);
        assertThat(customer.get("title").asString()).isEqualTo("Amina Odhiambo");
        assertThat(customer.get("document").get("doc_kind").asString()).isEqualTo("customer");
        assertThat(customer.get("document").toString()).doesNotContain("1990-04-01", "85000");
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"CUS-000123\"}"))).containsExactly(CUSTOMER);

        // account by number, its IBAN masked for callers without PII permission
        JsonNode account = await(TENANT_A, "{\"query_text\":\"0012345678\",\"entity_types\":[\"DEPOSIT_ACCOUNT\"]}",
                r -> ids(r).size() == 1).get("data").get(0);
        assertThat(account.get("entity_id").asString()).isEqualTo(ACCOUNT);
        assertThat(account.get("document").get("identifiers").get(0).asString()).isEqualTo("********5678");

        // payment by reference and by counterparty name; free-text remittance is not indexed
        await(TENANT_A, "{\"query_text\":\"INV-7781\"}", r -> ids(r).contains(PAYMENT));
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"acme\"}"))).containsExactly(PAYMENT);
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"rent flat\"}"))).isEmpty();

        // card by last4; nothing of the PAN, token or CVV is stored
        JsonNode card = await(TENANT_A, "{\"query_text\":\"1111\",\"entity_types\":[\"CARD\"]}", r -> ids(r).size() == 1)
                .get("data").get(0);
        assertThat(card.get("entity_id").asString()).isEqualTo(CARD);
        assertThat(card.get("title").asString()).isEqualTo("Amina Odhiambo •••• 1111");
        assertThat(card.get("document").toString()).doesNotContain("4111111111111111", "tok_9f8e7d", "411111", "cvv");
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"4111111111111111\"}"))).isEmpty();
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"tok_9f8e7d\"}"))).isEmpty();

        // all of the customer's documents by filter; the other tenant sees only its own customer
        assertThat(ids(query(TENANT_A, "{\"filters\":{\"customer_id\":\"" + CUSTOMER + "\"}}")))
                .containsExactlyInAnyOrder(CUSTOMER, ACCOUNT, PAYMENT, CARD);
        assertThat(ids(await(TENANT_B, "{\"query_text\":\"amina\"}", r -> !ids(r).isEmpty()))).containsExactly(CUSTOMER_B);
        assertThat(ids(query(TENANT_B, "{\"query_text\":\"odhiambo\"}"))).isEmpty();
        assertThat(ids(query(TENANT_B, "{\"query_text\":\"1111\"}"))).isEmpty();
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"kamau\"}"))).isEmpty();

        // indexes were provisioned per tenant from the feed profile
        JsonNode indexes = get("/v1/search/indexes", TENANT_A);
        List<String> types = new ArrayList<>();
        indexes.get("data").forEach(i -> types.add(i.get("entity_type").asString()));
        assertThat(types).containsExactlyInAnyOrder("CUSTOMER", "DEPOSIT_ACCOUNT", "PAYMENT", "CARD");
        assertThat(count("indexed", "customer")).isGreaterThanOrEqualTo(2);
        assertThat(meters.find("meridian.search.indexer.lag").timer().count()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @Order(2)
    void replaysAndOutOfOrderEventsAreIdempotent() throws Exception {
        String renamed = """
                {"id":"%s","customer_number":"CUS-000123","customer_type":"INDIVIDUAL","status":"ACTIVE",
                 "first_name":"Amina","last_name":"Wanjiru","updated_at":"2026-10-02T09:00:00Z","row_version":2}"""
                .formatted(CUSTOMER);
        String ceId = UUID.randomUUID().toString();
        publish("meridian.party.v1", "party.customer.updated", TENANT_A, CUSTOMER, renamed, ceId);
        await(TENANT_A, "{\"query_text\":\"wanjiru\"}", r -> ids(r).contains(CUSTOMER));
        // redelivery of the same event, then a late replay of the original creation (older version)
        publish("meridian.party.v1", "party.customer.updated", TENANT_A, CUSTOMER, renamed, ceId);
        publish("meridian.party.v1", "party.customer.created", TENANT_A, CUSTOMER, """
                {"id":"%s","customer_number":"CUS-000123","customer_type":"INDIVIDUAL","status":"ACTIVE",
                 "first_name":"Amina","last_name":"Odhiambo","updated_at":"2026-10-01T10:00:00.000001+00:00","row_version":1}"""
                .formatted(CUSTOMER));
        awaitCount("stale", "customer", 1);
        JsonNode r = query(TENANT_A, "{\"query_text\":\"amina\",\"entity_types\":[\"CUSTOMER\"]}");
        assertThat(ids(r)).containsExactly(CUSTOMER); // one document per (tenant, type, id)
        assertThat(r.get("data").get(0).get("title").asString()).isEqualTo("Amina Wanjiru");
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"odhiambo\",\"entity_types\":[\"CUSTOMER\"]}"))).isEmpty();

        // two changes in one transaction share updated_at: row_version orders them
        publish("meridian.payments.v1", "payments.payment.settled", TENANT_A, PAYMENT, payment("SETTLED", 3));
        publish("meridian.payments.v1", "payments.payment.submitted", TENANT_A, PAYMENT, payment("SUBMITTED", 2));
        JsonNode p = await(TENANT_A, "{\"query_text\":\"INV-7781\"}",
                x -> "SETTLED".equals(x.at("/data/0/document/status").asString(null)));
        awaitCount("stale", "payment", 1);
        assertThat(p.at("/data/0/document/lifecycle").asString()).isEqualTo("CLOSED");
        assertThat(query(TENANT_A, "{\"query_text\":\"INV-7781\"}").at("/data/0/document/status").asString())
                .isEqualTo("SETTLED");
    }

    @Test
    @Order(3)
    void closedStaysSearchableErasedIsRemovedForGood() throws Exception {
        publish("meridian.accounts.v1", "accounts.deposit_account.closed", TENANT_A, ACCOUNT, """
                {"id":"%s","account_number":"0012345678","account_type":"SAVINGS","currency":"KES","status":"CLOSED",
                 "customer_id":"%s","updated_at":"2026-10-03T08:00:00Z","row_version":5}""".formatted(ACCOUNT, CUSTOMER));
        JsonNode closed = await(TENANT_A, "{\"query_text\":\"0012345678\",\"filters\":{\"lifecycle\":\"CLOSED\"}}",
                r -> ids(r).contains(ACCOUNT));
        assertThat(closed.at("/data/0/document/status").asString()).isEqualTo("CLOSED");

        publish("meridian.party.v1", "party.customer.status_changed", TENANT_A, CUSTOMER, """
                {"id":"%s","customer_number":"CUS-000123","status":"CLOSED","erased_at":"2026-10-04T08:00:00Z",
                 "updated_at":"2026-10-04T08:00:00Z","row_version":7}""".formatted(CUSTOMER));
        await(TENANT_A, "{\"query_text\":\"CUS-000123\",\"entity_types\":[\"CUSTOMER\"]}", r -> ids(r).isEmpty());
        // a late replay of an earlier change must not bring the erased customer back
        publish("meridian.party.v1", "party.customer.updated", TENANT_A, CUSTOMER, """
                {"id":"%s","customer_number":"CUS-000123","customer_type":"INDIVIDUAL","status":"ACTIVE",
                 "first_name":"Amina","last_name":"Wanjiru","updated_at":"2026-10-02T09:00:00Z","row_version":2}"""
                .formatted(CUSTOMER));
        awaitCount("stale", "customer", 2);
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"wanjiru\",\"entity_types\":[\"CUSTOMER\"]}"))).isEmpty();
    }

    @Test
    @Order(4)
    void unmappedAndBrokenEventsAreDeadLetteredWithoutBlockingThePartition() throws Exception {
        double unmapped = count("unmapped", "none");
        double rejected = count("rejected", "card");
        String key = "0199a000-0000-7000-8000-00000000d002";
        publish("meridian.cards.v1", "cards.card_program.activated", TENANT_A, key, "{\"id\":\"" + key + "\"}");
        publish("meridian.cards.v1", "cards.card.created", TENANT_A, key, "not json {");
        publish("meridian.cards.v1", "cards.card.created", TENANT_A, key, "{\"last4\":\"2222\"}"); // no id
        publish("meridian.cards.v1", "cards.card.created", "not-a-tenant", key, "{\"id\":\"" + key + "\"}");
        // same key = same partition: the next good event must still be indexed
        publish("meridian.cards.v1", "cards.card.created", TENANT_A, key, """
                {"id":"%s","last4":"4242","cardholder_name":"Brian Otieno","status":"CREATED",
                 "updated_at":"2026-10-05T08:00:00Z","row_version":1}""".formatted(key));
        await(TENANT_A, "{\"query_text\":\"4242\"}", r -> ids(r).contains(key));
        assertThat(count("unmapped", "none")).isGreaterThan(unmapped);
        assertThat(count("rejected", "card") - rejected).isEqualTo(3);
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"2222\"}"))).isEmpty();
    }

    @Test
    @Order(5)
    void customersAreFoundByExactEmailOrPhoneWithoutIndexingThem() throws Exception {
        String customer = "0199a000-0000-7000-8000-00000000c003";
        String email = "0199a000-0000-7000-8000-00000000e001";
        String phone = "0199a000-0000-7000-8000-00000000e002";
        // payloads as customer-service publishes them: masked value and keyed lookup hash, never the contact itself
        publish("meridian.party.v1", "party.customer_contact.verified", TENANT_A, email, """
                {"id":"%s","customer_id":"%s","contact_type":"EMAIL","masked_value":"a***o@example.com",
                 "lookup_hash":"%s","is_primary":true,"is_verified":true,
                 "updated_at":"2026-10-05T09:00:00Z","row_version":2}""".formatted(email, customer, "0770f7b9dfe6a8fb4683deac301825418e615d486de412fbb85a3729de785c8f"));
        publish("meridian.party.v1", "party.customer_contact.verified", TENANT_A, phone, """
                {"id":"%s","customer_id":"%s","contact_type":"MOBILE","masked_value":"+25*******678",
                 "lookup_hash":"%s","is_primary":true,"is_verified":true,
                 "updated_at":"2026-10-05T09:00:00Z","row_version":2}""".formatted(phone, customer, "6376a913caa0cb1e19483ec430b1a474dc67a65a5556a556e1eeff7d0a93af07"));

        JsonNode byEmail = await(TENANT_A, "{\"query_text\":\"Amina.Odhiambo@Example.com\"}", r -> ids(r).contains(email));
        assertThat(ids(byEmail)).containsExactly(email);
        JsonNode hit = byEmail.get("data").get(0);
        assertThat(hit.get("entity_type").asString()).isEqualTo("CUSTOMER_CONTACT");
        assertThat(hit.get("title").asString()).isEqualTo("a***o@example.com");
        assertThat(hit.get("document").get("customer_id").asString()).isEqualTo(customer);
        assertThat(hit.toString()).doesNotContain("amina.odhiambo", "Amina.Odhiambo");
        // phone in any common international notation
        await(TENANT_A, "{\"query_text\":\"+254 712 345 678\"}", r -> ids(r).contains(phone));
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"00254712345678\"}"))).containsExactly(phone);
        // only an exact contact matches; other tenants see nothing
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"amina.odhiambo@example.org\"}"))).isEmpty();
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"+254 712 345 679\"}"))).isEmpty();
        assertThat(ids(query(TENANT_B, "{\"query_text\":\"Amina.Odhiambo@Example.com\"}"))).isEmpty();

        // a removed contact (deleted, changed or erased) leaves the index
        publish("meridian.party.v1", "party.customer_contact.removed", TENANT_A, email, """
                {"id":"%s","customer_id":"%s","contact_type":"EMAIL","masked_value":"a***o@example.com",
                 "lookup_hash":"%s","is_verified":true,"updated_at":"2026-10-05T09:00:00Z","row_version":2}"""
                .formatted(email, customer, "0770f7b9dfe6a8fb4683deac301825418e615d486de412fbb85a3729de785c8f"));
        await(TENANT_A, "{\"query_text\":\"Amina.Odhiambo@Example.com\"}", r -> ids(r).isEmpty());
        assertThat(ids(query(TENANT_A, "{\"query_text\":\"+254712345678\"}"))).containsExactly(phone);
    }

    // ------------------------------------------------------------------ helpers

    private String payment(String status, int rowVersion) {
        return """
                {"id":"%s","reference":"INV-7781","payment_type":"DOMESTIC","creditor_name":"Acme Supplies",
                 "amount":"1500.00","currency":"KES","status":"%s","customer_id":"%s",
                 "updated_at":"2026-10-02T12:00:00.123456Z","row_version":%d}""".formatted(PAYMENT, status, CUSTOMER, rowVersion);
    }

    private void publish(String topic, String type, String tenant, String key, String payload) throws Exception {
        publish(topic, type, tenant, key, payload, UUID.randomUUID().toString());
    }

    private void publish(String topic, String type, String tenant, String key, String payload, String ceId) throws Exception {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, key, payload);
        header(rec, "ce_specversion", "1.0");
        header(rec, "ce_id", ceId);
        header(rec, "ce_type", type);
        header(rec, "ce_source", "test-producer");
        header(rec, "ce_time", java.time.OffsetDateTime.now().toString());
        header(rec, "ce_tenantid", tenant);
        header(rec, "content-type", "application/json");
        kafka.send(rec).get(10, TimeUnit.SECONDS);
    }

    private static void header(ProducerRecord<String, String> rec, String k, String v) {
        rec.headers().add(k, v.getBytes(StandardCharsets.UTF_8));
    }

    private double count(String outcome, String kind) {
        Counter c = meters.find("meridian.search.indexer.events").tag("outcome", outcome).tag("doc_kind", kind).counter();
        return c == null ? 0 : c.count();
    }

    private void awaitCount(String outcome, String kind, double atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (count(outcome, kind) < atLeast) {
            assertThat(System.currentTimeMillis()).as("counter " + outcome + "/" + kind).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private JsonNode await(String tenant, String body, Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> r = send("POST", "/v1/search/query", body, tenant);
            if (r.statusCode() == 200) {
                last = json.readTree(r.body());
                if (done.test(last)) {
                    return last;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("search " + body + " for " + tenant + " never satisfied the condition; last: " + last);
    }

    private JsonNode query(String tenant, String body) throws Exception {
        HttpResponse<String> r = send("POST", "/v1/search/query", body, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, tenant);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private static List<String> ids(JsonNode res) {
        List<String> out = new ArrayList<>();
        if (res != null && res.has("data")) {
            res.get("data").forEach(d -> out.add(d.get("entity_id").asString()));
        }
        return out;
    }

    private HttpResponse<String> send(String method, String path, String body, String tenant) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", "ops-user").header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
