package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.when;

import io.meridian.customer.application.CustomerService;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.customer.application.CustomerTierApprovalHandler;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * customer-service end to end over HTTP against PostgreSQL 18 with row-level security. Other services
 * (notification-service for OTP delivery, document-service for privacy exports) are stubbed via ServiceClient.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.listener-enabled=false",
                "meridian.contact-lookup.key=test-contact-lookup-key-0123456789"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String THIRD_TENANT = "0199a000-0000-7000-8000-0000000000c3";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-0000000000e1";
    static final String ORIGIN = "https://app.meridian.example";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    @Autowired
    CustomerService customerService;

    final List<String> sentMessages = new ArrayList<>();
    final List<JsonNode> approvalRequests = new ArrayList<>();
    String alice;
    String aliceMobile;
    String deviceId;

    @BeforeEach
    void stubOtherServices() {
        when(serviceClient.post(eq("notification-service"), eq("/v1/notifications"), any(), anyString())).thenAnswer(inv -> {
            Map<?, ?> body = inv.getArgument(2);
            sentMessages.add(String.valueOf(body.get("body")));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}");
        });
        when(serviceClient.post(eq("document-service"), eq("/v1/documents"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
        when(serviceClient.post(eq("document-service"), matches("/v1/documents/.*/upload-url"), any(), anyString()))
                .thenAnswer(inv -> json.createObjectNode());
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            ObjectNode approval = (ObjectNode) json.valueToTree(inv.getArgument(2));
            approval.put("id", UUID.randomUUID().toString());
            approval.put("status", "PENDING");
            approval.put("maker", "maker");
            approval.put("required_approvals", 1);
            approval.put("approvals_received", 0);
            approvalRequests.add(approval);
            return approval;
        });
    }

    /** Delivers a workflow-service decision the way Kafka would (binary CloudEvent on meridian.platform.v1). */
    private void deliverApproval(JsonNode approval, String outcome) throws Exception {
        ObjectNode decided = (ObjectNode) approval.deepCopy();
        decided.put("status", outcome.toUpperCase(java.util.Locale.ROOT));
        decided.put("updated_by", "checker");
        ConsumerRecord<String, String> record = new ConsumerRecord<>("meridian.platform.v1", 0, 0L,
                decided.get("id").asString(), json.writeValueAsString(decided));
        record.headers().add("ce_type", ("platform.approval_request." + outcome).getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_tenantid", TENANT.getBytes(StandardCharsets.UTF_8));
        new ApprovalEventsDispatcher(List.of(new CustomerTierApprovalHandler(customerService)), json).onEvent(record);
    }

    // ------------------------------------------------------------------ lifecycle, OTP and tier maker-checker

    @Test
    @Order(1)
    void customerLifecycleWithOtpAndTierMakerChecker() throws Exception {
        String body = individual("CUS-0001", "Alice", "Smith", "1990-04-12");
        HttpResponse<String> created = send("POST", "/v1/customers", body, "create-alice", TENANT, "maker");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode c = json.readTree(created.body());
        alice = c.get("id").asString();
        assertThat(c.get("status").asString()).isEqualTo("PROSPECT");
        assertThat(c.get("kyc_tier").asString()).isEqualTo("TIER_0");
        assertThat(c.get("is_minor").asBoolean()).isFalse();
        // Server-generated customer number (C + 9-digit tenant sequence + Luhn digit) and derived display name.
        String aliceNumber = c.get("customer_number").asString();
        assertThat(aliceNumber).matches("C\\d{10}");
        assertThat(luhnValid(aliceNumber.substring(1))).isTrue();
        assertThat(c.get("display_name").asString()).isEqualTo("Alice Smith");

        // Minimal create: only type, names, date of birth, nationality and legal entity (what the console sends).
        JsonNode minimal = post("/v1/customers", """
                {"customer_type":"INDIVIDUAL","first_name":"Mina","last_name":"Okafor","date_of_birth":"1992-03-04",
                 "nationality":"NG","legal_entity_id":"%s"}""".formatted(LEGAL_ENTITY), 201);
        assertThat(minimal.get("display_name").asString()).isEqualTo("Mina Okafor");
        assertThat(minimal.get("customer_number").asString()).matches("C\\d{10}").isNotEqualTo(aliceNumber);
        assertThat(Long.parseLong(minimal.get("customer_number").asString().substring(1, 10)))
                .isEqualTo(Long.parseLong(aliceNumber.substring(1, 10)) + 1);
        JsonNode business = post("/v1/customers", """
                {"customer_type":"COMPANY","legal_name":"Okafor Trading Ltd","trading_name":"Okafor Foods",
                 "legal_entity_id":"%s"}""".formatted(LEGAL_ENTITY), 201);
        assertThat(business.get("display_name").asString()).isEqualTo("Okafor Foods");
        assertThat(business.get("legal_name").asString()).isEqualTo("Okafor Trading Ltd");
        assertThat(code(post("/v1/customers", """
                {"customer_type":"COMPANY","legal_entity_id":"%s"}""".formatted(LEGAL_ENTITY), 422))).isEqualTo("invalid_customer");
        // A client-sent customer number is not part of the contract and is ignored.
        JsonNode ignored = post("/v1/customers", """
                {"customer_number":"MY-OWN","customer_type":"INDIVIDUAL","first_name":"Ian","last_name":"Gnored",
                 "legal_entity_id":"%s"}""".formatted(LEGAL_ENTITY), 201);
        assertThat(ignored.get("customer_number").asString()).matches("C\\d{10}");
        // Customer numbers are sequenced per tenant.
        HttpResponse<String> otherTenant = send("POST", "/v1/customers", individual("OT-1", "Oscar", "Other", "1980-01-01"),
                key(), THIRD_TENANT, "maker");
        assertThat(otherTenant.statusCode()).as(otherTenant.body()).isEqualTo(201);
        assertThat(json.readTree(otherTenant.body()).get("customer_number").asString()).isEqualTo("C0000000018");

        // Idempotent replay, and key reuse with a different body.
        HttpResponse<String> replay = send("POST", "/v1/customers", body, "create-alice", TENANT, "maker");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(alice);
        assertThat(code(send("POST", "/v1/customers", individual("CUS-0009", "X", "Y", "1990-01-01"), "create-alice", TENANT, "maker")))
                .isEqualTo("idempotency_key_reused");

        // Validation.
        assertThat(send("POST", "/v1/customers", "{\"customer_number\":\"CUS-X\",\"customer_type\":\"INDIVIDUAL\"}",
                key(), TENANT, "maker").statusCode()).isEqualTo(400);
        assertThat(code(post("/v1/customers", individual("CUS-0002", "Future", "Kid", LocalDate.now().plusDays(3).toString()), 422)))
                .isEqualTo("invalid_customer");
        assertThat(code(post("/v1/customers", """
                {"customer_number":"CUS-0003","customer_type":"INDIVIDUAL","legal_entity_id":"%s","display_name":"Z",
                 "status":"ACTIVE"}""".formatted(LEGAL_ENTITY), 422))).isEqualTo("invalid_initial_status");

        // Cannot activate at TIER_0.
        assertThat(code(post("/v1/customers/" + alice + "/activate", null, 422))).isEqualTo("kyc_requirements_not_met");

        // Contacts: validation, then OTP verification.
        assertThat(code(post("/v1/customers/" + alice + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"not-an-email"}""".formatted(alice), 400))).isEqualTo("validation_failed");
        aliceMobile = post("/v1/customers/" + alice + "/contacts", """
                {"customer_id":"%s","contact_type":"MOBILE","phone":"+447700900123","is_primary":true}""".formatted(alice), 201)
                .get("id").asString();
        JsonNode aliceEmail = post("/v1/customers/" + alice + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"Alice.Smith@Example.com"}""".formatted(alice), 201);
        assertThat(aliceEmail.get("masked_value").asString()).isEqualTo("a***h@example.com");
        assertThat(aliceEmail.get("lookup_hash").asString()).hasSize(64);
        assertThat(code(post("/v1/customers/" + alice + "/contacts/" + aliceMobile + "/verify", "{\"code\":\"123456\"}", 409)))
                .isEqualTo("no_active_verification");
        sentMessages.clear();
        JsonNode challenge = post("/v1/customers/" + alice + "/contacts/" + aliceMobile + "/send-verification", null, 200);
        assertThat(challenge.get("status").asString()).isEqualTo("PENDING");
        assertThat(challenge.get("channel").asString()).isEqualTo("SMS");
        assertThat(challenge.get("destination_masked").asString()).isEqualTo("+44*******123");
        assertThat(challenge.get("max_attempts").asInt()).isEqualTo(5);
        assertThat(challenge.has("code_hash")).isFalse();
        String otp = otpFromLastMessage();
        assertThat(challenge.toString()).doesNotContain(otp);
        assertThat(code(send("POST", "/v1/customers/" + alice + "/contacts/" + aliceMobile + "/send-verification", null, key(), TENANT, "maker")))
                .isEqualTo("otp_cooldown");
        String wrong = otp.equals("000000") ? "111111" : "000000";
        assertThat(code(post("/v1/customers/" + alice + "/contacts/" + aliceMobile + "/verify", "{\"code\":\"" + wrong + "\"}", 422)))
                .isEqualTo("invalid_otp");
        JsonNode verified = post("/v1/customers/" + alice + "/contacts/" + aliceMobile + "/verify", "{\"code\":\"" + otp + "\"}", 200);
        assertThat(verified.get("is_verified").asBoolean()).isTrue();
        assertThat(verified.get("phone").asString()).isEqualTo("+447700900123");
        JsonNode challenges = get("/v1/customers/" + alice + "/contacts/" + aliceMobile + "/verifications", TENANT).get("data");
        assertThat(challenges).hasSize(1);
        assertThat(challenges.get(0).get("status").asString()).isEqualTo("VERIFIED");
        assertThat(challenges.get(0).get("attempts").asInt()).isEqualTo(1);
        assertThat(outbox("party.customer_contact.verified", aliceMobile)).isEqualTo(1);
        // search gets the masked contact and the keyed lookup hash, never the number itself
        assertThat(verified.get("masked_value").asString()).isEqualTo("+44*******123");
        assertThat(verified.get("lookup_hash").asString())
                .isEqualTo("55e7a67e242c662f26bcc7ed625f7efd6fbd87005cc1c68c6a6e27188f8ee1e3"); // HMAC of 447700900123
        JsonNode verifiedEvent = outboxPayload("party.customer_contact.verified", aliceMobile);
        assertThat(verifiedEvent.has("phone")).isFalse();
        assertThat(verifiedEvent.has("email")).isFalse();
        assertThat(verifiedEvent.get("lookup_hash").asString()).isEqualTo(verified.get("lookup_hash").asString());
        assertThat(verifiedEvent.get("customer_id").asString()).isEqualTo(alice);
        assertThat(verifiedEvent.toString()).doesNotContain("7700900");

        // a verified contact whose value changes, or that is deleted, leaves search (masked payload)
        String spare = post("/v1/customers/" + alice + "/contacts", """
                {"customer_id":"%s","contact_type":"WHATSAPP","phone":"+447700900999"}""".formatted(alice), 201).get("id").asString();
        sentMessages.clear();
        post("/v1/customers/" + alice + "/contacts/" + spare + "/send-verification", null, 200);
        JsonNode spareVerified = post("/v1/customers/" + alice + "/contacts/" + spare + "/verify",
                "{\"code\":\"" + otpFromLastMessage() + "\"}", 200);
        JsonNode changed = patch("/v1/customers/" + alice + "/contacts/" + spare, "{\"phone\":\"+447700900998\"}",
                spareVerified.get("row_version").asInt(), 200);
        assertThat(changed.get("is_verified").asBoolean()).isFalse();
        assertThat(changed.get("masked_value").asString()).isEqualTo("+44*******998");
        assertThat(changed.get("lookup_hash").asString()).isNotEqualTo(spareVerified.get("lookup_hash").asString());
        assertThat(outbox("party.customer_contact.removed", spare)).isEqualTo(1);
        JsonNode removedEvent = outboxPayload("party.customer_contact.removed", spare);
        assertThat(removedEvent.has("phone")).isFalse();
        assertThat(removedEvent.get("lookup_hash").asString()).isEqualTo(spareVerified.get("lookup_hash").asString());
        assertThat(send("DELETE", "/v1/customers/" + alice + "/contacts/" + spare, null, null, TENANT, "u").statusCode()).isEqualTo(204);
        assertThat(outbox("party.customer_contact.removed", spare)).isEqualTo(1); // no longer verified: nothing indexed
        assertThat(jdbc.sql("select count(*) from customer_credential where credential_type = 'SMS_OTP'")
                .query(Integer.class).single()).isZero();

        // Tier upgrade (maker-checker): the request opens a workflow-service approval and parks the target tier.
        assertThat(code(send("POST", "/v1/customers/" + alice + "/change-tier",
                "{\"to_tier\":\"TIER_2\",\"reason\":\"more\"}", key(), TENANT, "maker"))).isEqualTo("tier_requirements_not_met");
        HttpResponse<String> requested = send("POST", "/v1/customers/" + alice + "/change-tier",
                "{\"to_tier\":\"TIER_1\",\"reason\":\"phone verified\"}", key(), TENANT, "maker");
        assertThat(requested.statusCode()).as(requested.body()).isEqualTo(202);
        JsonNode pending = json.readTree(requested.body());
        assertThat(pending.get("kyc_tier").asString()).isEqualTo("TIER_0");
        assertThat(pending.get("pending_tier").asString()).isEqualTo("TIER_1");
        assertThat(approvalRequests).hasSize(1);
        JsonNode approval = approvalRequests.getFirst();
        assertThat(pending.get("tier_approval_request_id").asString()).isEqualTo(approval.get("id").asString());
        assertThat(approval.get("resource_type").asString()).isEqualTo("customer_kyc_tier");
        assertThat(approval.get("resource_id").asString()).isEqualTo(alice);
        assertThat(approval.get("payload").get("to_tier").asString()).isEqualTo("TIER_1");
        assertThat(code(send("POST", "/v1/customers/" + alice + "/change-tier",
                "{\"to_tier\":\"TIER_1\",\"reason\":\"again\"}", key(), TENANT, "checker"))).isEqualTo("tier_change_pending");
        assertThat(outbox("party.customer.tier_change_requested", alice)).isEqualTo(1);
        // An unrelated approval has no effect; the linked approval applies the tier (redelivery is a no-op).
        ObjectNode stranger = (ObjectNode) approval.deepCopy();
        stranger.put("id", UUID.randomUUID().toString());
        deliverApproval(stranger, "approved");
        assertThat(get("/v1/customers/" + alice, TENANT).get("kyc_tier").asString()).isEqualTo("TIER_0");
        deliverApproval(approval, "approved");
        deliverApproval(approval, "approved");
        JsonNode upgraded = get("/v1/customers/" + alice, TENANT);
        assertThat(upgraded.get("kyc_tier").asString()).isEqualTo("TIER_1");
        assertThat(upgraded.path("pending_tier").isMissingNode() || upgraded.get("pending_tier").isNull()).isTrue();
        assertThat(upgraded.hasNonNull("tier_approval_request_id")).isFalse();
        JsonNode history = get("/v1/customers/" + alice + "/tier-history", TENANT).get("data");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("from_tier").asString()).isEqualTo("TIER_0");
        assertThat(history.get(0).get("to_tier").asString()).isEqualTo("TIER_1");

        // Lifecycle.
        JsonNode active = post("/v1/customers/" + alice + "/activate", null, 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("onboarded_at").isNull()).isFalse();
        assertThat(code(post("/v1/customers/" + alice + "/activate", null, 409))).isEqualTo("invalid_transition");
        assertThat(post("/v1/customers/" + alice + "/suspend", "{\"reason\":\"fraud review\"}", 200).get("status").asString())
                .isEqualTo("SUSPENDED");
        assertThat(code(post("/v1/customers/" + alice + "/suspend", "{\"reason\":\"again\"}", 409))).isEqualTo("invalid_transition");
        assertThat(send("POST", "/v1/customers/" + alice + "/suspend", "{}", key(), TENANT, "maker").statusCode()).isEqualTo(400);
        JsonNode reactivated = post("/v1/customers/" + alice + "/reactivate", "{\"reason\":\"cleared\"}", 200);
        assertThat(reactivated.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(reactivated.get("status_reason").asString()).isEqualTo("cleared");
        assertThat(reactivated.get("status_changed_by").asString()).isEqualTo("maker");
        assertThat(reactivated.has("metadata") && reactivated.get("metadata").has("_lifecycle")).isFalse();

        // Status is not patchable; details are, with optimistic locking.
        int version = get("/v1/customers/" + alice, TENANT).get("row_version").asInt();
        assertThat(code(patch("/v1/customers/" + alice, "{\"status\":\"CLOSED\"}", version, 422))).isEqualTo("status_change_not_allowed");
        JsonNode patched = patch("/v1/customers/" + alice, "{\"occupation\":\"Engineer\"}", version, 200);
        assertThat(patched.get("occupation").asString()).isEqualTo("Engineer");
        assertThat(code(patch("/v1/customers/" + alice, "{\"occupation\":\"Pilot\"}", version, 409))).isEqualTo("version_conflict");

        assertThat(outbox("party.customer.created", alice)).isEqualTo(1);
        assertThat(outbox("party.customer.status_changed", alice)).isEqualTo(3);
        assertThat(outbox("party.customer.updated", alice)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox where event_type = 'party.customer.tier_changed'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ search and duplicates

    @Test
    @Order(2)
    void searchAndDuplicateDetection() throws Exception {
        String amelie = post("/v1/customers", individual("CUS-0100", "Amélie", "Durand", "1985-07-01"), 201).get("id").asString();
        post("/v1/customers/" + amelie + "/identifiers", """
                {"customer_id":"%s","identifier_type":"PASSPORT","identifier_value":"FR-12 345 678","issuing_country":"FR",
                 "expiry_date":"%s"}""".formatted(amelie, LocalDate.now().plusYears(5)), 201);
        post("/v1/customers/" + amelie + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"amelie@example.fr"}""".formatted(amelie), 201);
        // The same passport cannot belong to another customer.
        assertThat(send("POST", "/v1/customers/" + alice + "/identifiers", """
                {"customer_id":"%s","identifier_type":"PASSPORT","identifier_value":"fr12345678","issuing_country":"FR"}"""
                .formatted(alice), key(), TENANT, "maker").statusCode()).isEqualTo(409);
        assertThat(code(post("/v1/customers/" + amelie + "/identifiers", """
                {"customer_id":"%s","identifier_type":"NATIONAL_ID","identifier_value":"X99","issuing_country":"FR",
                 "expiry_date":"2001-01-01"}""".formatted(amelie), 422))).isEqualTo("identifier_expired");

        assertThat(ids(get("/v1/customers/search?q=durand", TENANT))).contains(amelie);
        assertThat(ids(get("/v1/customers/search?q=AMELIE@EXAMPLE.FR", TENANT))).containsExactly(amelie);
        assertThat(ids(get("/v1/customers/search?q=FR12345678", TENANT))).containsExactly(amelie);
        String amelieNumber = get("/v1/customers/" + amelie, TENANT).get("customer_number").asString();
        assertThat(ids(get("/v1/customers/search?q=" + amelieNumber.toLowerCase(java.util.Locale.ROOT), TENANT)))
                .containsExactly(amelie);
        assertThat(ids(get("/v1/customers/search?q=CUS-0100", TENANT))).containsExactly(amelie); // external_ref
        assertThat(ids(get("/v1/customers/search?q=%25%25", TENANT))).isEmpty();
        assertThat(send("GET", "/v1/customers/search?q=a", null, null, TENANT, "maker").statusCode()).isEqualTo(400);

        JsonNode dup = post("/v1/customers/duplicates-check", """
                {"first_name":"amelie","last_name":"DURAND","date_of_birth":"1985-07-01"}""", 200).get("data");
        assertThat(dup).hasSize(1);
        assertThat(dup.get(0).get("id").asString()).isEqualTo(amelie);
        assertThat(dup.get(0).get("match_score").asInt()).isEqualTo(60);
        JsonNode strong = post("/v1/customers/duplicates-check", """
                {"identifier_value":"fr 12345678","email":"amelie@example.fr"}""", 200).get("data");
        assertThat(strong.get(0).get("match_score").asInt()).isEqualTo(95);
        assertThat(strong.get(0).get("match_reasons").toString()).contains("IDENTIFIER", "EMAIL");
        assertThat(post("/v1/customers/duplicates-check", "{\"last_name\":\"Nobody\"}", 200).get("data")).isEmpty();
        assertThat(code(post("/v1/customers/duplicates-check", "{}", 422))).isEqualTo("no_criteria");
    }

    // ------------------------------------------------------------------ relationships, ownership, view

    @Test
    @Order(3)
    void relationshipsOwnershipTreeAndView() throws Exception {
        String opco = post("/v1/customers", company("CUS-0200", "OpCo Ltd"), 201).get("id").asString();
        String holdco = post("/v1/customers", company("CUS-0201", "HoldCo Ltd"), 201).get("id").asString();
        String bob = post("/v1/customers", individual("CUS-0202", "Bob", "Jones", "1970-02-02"), 201).get("id").asString();

        assertThat(code(post("/v1/customer-relationships", rel(opco, opco, "SHAREHOLDER", "10"), 422))).isEqualTo("self_relationship");
        assertThat(code(post("/v1/customer-relationships", rel(alice, bob, "SHAREHOLDER", "10"), 422))).isEqualTo("invalid_relationship");
        post("/v1/customer-relationships", rel(opco, holdco, "SHAREHOLDER", "60"), 201);
        post("/v1/customer-relationships", rel(opco, bob, "SHAREHOLDER", "40"), 201);
        assertThat(code(post("/v1/customer-relationships", rel(opco, alice, "SHAREHOLDER", "1"), 422))).isEqualTo("ownership_exceeds_100");
        post("/v1/customer-relationships", rel(holdco, alice, "SHAREHOLDER", "50"), 201);
        post("/v1/customer-relationships", rel(holdco, opco, "SHAREHOLDER", "10"), 201); // cross-holding (cycle)
        post("/v1/customer-relationships", rel(opco, bob, "DIRECTOR", null), 201);
        assertThat(code(post("/v1/customer-relationships", rel(opco, bob, "DIRECTOR", "5"), 422))).isEqualTo("invalid_percentage");

        JsonNode tree = get("/v1/customers/" + opco + "/ownership-tree", TENANT);
        JsonNode ubos = tree.get("ultimate_beneficial_owners");
        assertThat(ubos).hasSize(2);
        assertThat(ubos.get(0).get("customer_id").asString()).isEqualTo(bob);
        assertThat(ubos.get(0).get("effective_percentage").asString()).isEqualTo("40");
        assertThat(ubos.get(1).get("customer_id").asString()).isEqualTo(alice);
        assertThat(ubos.get(1).get("effective_percentage").asString()).isEqualTo("30");
        assertThat(tree.toString()).contains("\"cycle\":true");
        assertThat(tree.get("controllers").get(0).get("customer_id").asString()).isEqualTo(bob);

        assertThat(get("/v1/customers/" + bob + "/relationships", TENANT).get("data")).hasSize(2);

        JsonNode view = get("/v1/customers/" + opco + "/view", TENANT);
        assertThat(view.get("customer").get("id").asString()).isEqualTo(opco);
        assertThat(view.get("relationships")).hasSize(4);
        assertThat(view.get("kyc").get("tier_limits").get("max_balance").asString()).isEqualTo("0");
        JsonNode aliceView = get("/v1/customers/" + alice + "/view", TENANT);
        assertThat(aliceView.get("contacts")).hasSize(2);
        assertThat(aliceView.get("kyc").get("kyc_tier").asString()).isEqualTo("TIER_1");
        assertThat(outbox("party.customer_relationship.changed", null)).isEqualTo(5);

        // A company needs a director or signatory to activate; OpCo has one but no tier.
        assertThat(code(post("/v1/customers/" + opco + "/activate", null, 422))).isEqualTo("kyc_requirements_not_met");
    }

    // ------------------------------------------------------------------ merge

    @Test
    @Order(4)
    void mergeDuplicateIntoSurvivor() throws Exception {
        String dup = post("/v1/customers", individual("CUS-0300", "Alice", "Smith", "1990-04-12"), 201).get("id").asString();
        post("/v1/customers/" + dup + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"alice.second@example.com"}""".formatted(dup), 201);
        post("/v1/customers/" + dup + "/contacts", """
                {"customer_id":"%s","contact_type":"MOBILE","phone":"+447700900123"}""".formatted(dup), 201);
        post("/v1/consents", consent(dup, "ANALYTICS", null), 201);

        assertThat(code(post("/v1/customers/" + alice + "/merge", "{\"duplicate_customer_id\":\"" + alice + "\",\"reason\":\"x\"}", 422)))
                .isEqualTo("self_merge");
        JsonNode survivor = post("/v1/customers/" + alice + "/merge",
                "{\"duplicate_customer_id\":\"" + dup + "\",\"reason\":\"same person\"}", 200);
        assertThat(survivor.hasNonNull("merged_into_customer_id")).isFalse();
        JsonNode merged = get("/v1/customers/" + dup, TENANT);
        assertThat(merged.get("status").asString()).isEqualTo("CLOSED");
        assertThat(merged.get("merged_into_customer_id").asString()).isEqualTo(alice);
        assertThat(merged.get("status_reason").asString()).isEqualTo("same person");
        // The duplicate mobile collapsed into the survivor's; the new email moved over.
        assertThat(get("/v1/customers/" + alice + "/contacts", TENANT).get("data")).hasSize(3);
        assertThat(get("/v1/customers/" + dup + "/contacts", TENANT).get("data")).isEmpty();
        assertThat(get("/v1/consents?customer_id=" + alice, TENANT).get("data")).hasSize(1);
        assertThat(code(post("/v1/customers/" + alice + "/merge",
                "{\"duplicate_customer_id\":\"" + dup + "\",\"reason\":\"again\"}", 409))).isEqualTo("invalid_transition");
        assertThat(outbox("party.customer.merged", alice)).isEqualTo(1);
        // Merged records are not reported as duplicates any more.
        assertThat(ids(post("/v1/customers/duplicates-check", """
                {"first_name":"Alice","last_name":"Smith","date_of_birth":"1990-04-12"}""", 200))).containsExactly(alice);
    }

    // ------------------------------------------------------------------ devices and credentials

    @Test
    @Order(5)
    void devicesAndCredentials() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        KeyPair keys = gen.generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertThat(code(post("/v1/customers/" + alice + "/devices", """
                {"customer_id":"%s","device_fingerprint":"fp-1","platform":"IOS","public_key":"bm90LWEta2V5"}""".formatted(alice), 422)))
                .isEqualTo("invalid_public_key");
        JsonNode device = post("/v1/customers/" + alice + "/devices", """
                {"customer_id":"%s","device_fingerprint":"fp-1","platform":"IOS","model":"iPhone","public_key":"%s"}"""
                .formatted(alice, publicKey), 201);
        deviceId = device.get("id").asString();
        assertThat(device.get("status").asString()).isEqualTo("PENDING");

        String challenge = "meridian-device-trust:" + deviceId + ":" + device.get("row_version").asInt();
        KeyPair other = gen.generateKeyPair();
        assertThat(code(post("/v1/customers/" + alice + "/devices/" + deviceId + "/trust",
                "{\"challenge_response\":\"" + sign(other, challenge) + "\"}", 422))).isEqualTo("invalid_challenge_response");
        JsonNode trusted = post("/v1/customers/" + alice + "/devices/" + deviceId + "/trust",
                "{\"challenge_response\":\"" + sign(keys, challenge) + "\"}", 200);
        assertThat(trusted.get("status").asString()).isEqualTo("TRUSTED");
        assertThat(trusted.get("bound_at").isNull()).isFalse();
        assertThat(code(post("/v1/customers/" + alice + "/devices/" + deviceId + "/trust",
                "{\"challenge_response\":\"" + sign(keys, challenge) + "\"}", 409))).isEqualTo("invalid_transition");

        // PIN: policy, change with lockout, unlock.
        assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin", "{\"new_pin_block\":\"1234\"}", key(), TENANT, "u")))
                .isEqualTo("weak_pin");
        assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin", "{\"new_pin_block\":\"1204\"}", key(), TENANT, "u")))
                .isEqualTo("weak_pin"); // DDMM of the date of birth
        assertThat(send("POST", "/v1/customers/" + alice + "/credentials/pin", "{\"new_pin_block\":\"4826\"}", key(), TENANT, "u")
                .statusCode()).isEqualTo(200);
        assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin", "{\"new_pin_block\":\"7391\"}", key(), TENANT, "u")))
                .isEqualTo("old_pin_required");
        for (int i = 0; i < 2; i++) {
            assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin",
                    "{\"new_pin_block\":\"7391\",\"old_pin_block\":\"9999\"}", key(), TENANT, "u"))).isEqualTo("invalid_pin");
        }
        assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin",
                "{\"new_pin_block\":\"7391\",\"old_pin_block\":\"9999\"}", key(), TENANT, "u"))).isEqualTo("credential_locked");
        JsonNode pin = get("/v1/customers/" + alice + "/credentials?credential_type=PIN", TENANT).get("data").get(0);
        assertThat(pin.get("status").asString()).isEqualTo("LOCKED");
        assertThat(pin.has("secret_hash")).isFalse();
        assertThat(code(send("POST", "/v1/customers/" + alice + "/credentials/pin",
                "{\"new_pin_block\":\"7391\",\"old_pin_block\":\"4826\"}", key(), TENANT, "u"))).isEqualTo("credential_locked");
        String pinId = pin.get("id").asString();
        assertThat(post("/v1/customers/" + alice + "/credentials/" + pinId + "/unlock", "{\"reason\":\"called in\"}", 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        assertThat(code(post("/v1/customers/" + alice + "/credentials/" + pinId + "/unlock", "{\"reason\":\"again\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(send("POST", "/v1/customers/" + alice + "/credentials/pin",
                "{\"new_pin_block\":\"7391\",\"old_pin_block\":\"4826\"}", key(), TENANT, "u").statusCode()).isEqualTo(200);
        assertThat(outbox("party.customer_credential.locked", pinId)).isEqualTo(1);

        // Passkey registration ceremony bound to the trusted device.
        JsonNode options = post("/v1/customers/" + alice + "/credentials/passkeys/registration-options",
                "{\"customer_device_id\":\"" + deviceId + "\"}", 200);
        assertThat(options.get("rp").get("id").asString()).isEqualTo("meridian.example");
        String ch = options.get("challenge").asString();
        String passkey = post("/v1/customers/" + alice + "/credentials/passkeys", passkeyBody("cred-1", ch, ORIGIN, deviceId), 201)
                .get("id").asString();
        assertThat(code(post("/v1/customers/" + alice + "/credentials/passkeys", passkeyBody("cred-1", ch, ORIGIN, deviceId), 409)))
                .isEqualTo("credential_exists");
        assertThat(code(post("/v1/customers/" + alice + "/credentials/passkeys", passkeyBody("cred-2", ch, "https://evil.example", deviceId), 422)))
                .isEqualTo("invalid_origin");
        assertThat(code(post("/v1/customers/" + alice + "/credentials/passkeys", passkeyBody("cred-3", ch.substring(2) + "AA", ORIGIN, deviceId), 422)))
                .isEqualTo("invalid_challenge");

        // Revoking the device revokes credentials bound to it.
        JsonNode revoked = post("/v1/customers/" + alice + "/devices/" + deviceId + "/revoke", "{\"reason\":\"lost phone\"}", 200);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(get("/v1/customers/" + alice + "/credentials/" + passkey, TENANT).get("status").asString()).isEqualTo("REVOKED");
        assertThat(code(post("/v1/customers/" + alice + "/devices/" + deviceId + "/revoke", "{\"reason\":\"x\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(outbox("party.customer_device.trusted", deviceId)).isEqualTo(1);
        assertThat(outbox("party.customer_device.revoked", deviceId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ consents and privacy

    @Test
    @Order(6)
    void consentsAndPrivacyRequests() throws Exception {
        String marketing = post("/v1/consents", consent(alice, "MARKETING", null), 201).get("id").asString();
        assertThat(code(post("/v1/consents", consent(alice, "MARKETING", null), 409))).isEqualTo("consent_exists");
        assertThat(post("/v1/consents/" + marketing + "/withdraw", "{\"reason\":\"too many emails\"}", 200)
                .get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(code(post("/v1/consents/" + marketing + "/withdraw", "{}", 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/consents/" + marketing + "/renew",
                "{\"expires_at\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusYears(1) + "\"}", 409))).isEqualTo("invalid_transition");
        String profiling = post("/v1/consents", consent(alice, "PROFILING", OffsetDateTime.now(ZoneOffset.UTC).plusDays(10).toString()), 201)
                .get("id").asString();
        assertThat(code(post("/v1/consents/" + profiling + "/renew",
                "{\"expires_at\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusDays(5) + "\"}", 422))).isEqualTo("invalid_expires_at");
        JsonNode renewed = post("/v1/consents/" + profiling + "/renew",
                "{\"expires_at\":\"" + OffsetDateTime.now(ZoneOffset.UTC).plusYears(1) + "\",\"evidence\":{\"ip\":\"10.0.0.1\"}}", 200);
        assertThat(renewed.get("status").asString()).isEqualTo("GIVEN");
        String terms = post("/v1/consents", consent(alice, "TERMS_AND_CONDITIONS", null), 201).get("id").asString();
        assertThat(code(post("/v1/consents/" + terms + "/withdraw", "{}", 422))).isEqualTo("consent_required");

        // Export: identity must be verified first; the package is stored in document-service.
        String export = post("/v1/privacy-requests", privacy(alice, "EXPORT"), 201).get("id").asString();
        assertThat(code(post("/v1/privacy-requests/" + export + "/execute", null, 409))).isEqualTo("invalid_transition");
        assertThat(post("/v1/privacy-requests/" + export + "/verify-identity", "{\"method\":\"VIDEO_CALL\"}", 200)
                .get("status").asString()).isEqualTo("IN_PROGRESS");
        JsonNode done = post("/v1/privacy-requests/" + export + "/execute", null, 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("result_document_id").isNull()).isFalse();
        assertThat(code(post("/v1/privacy-requests/" + export + "/execute", null, 409))).isEqualTo("invalid_transition");

        // Erasure of an active customer is partial: the contract and AML retention block the identity record.
        String erase = post("/v1/privacy-requests", privacy(alice, "ERASURE"), 201).get("id").asString();
        post("/v1/privacy-requests/" + erase + "/verify-identity", "{\"method\":\"BRANCH\"}", 200);
        JsonNode partial = post("/v1/privacy-requests/" + erase + "/execute", null, 200);
        assertThat(partial.get("status").asString()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(partial.get("retention_blockers").toString()).contains("CONTRACT");
        assertThat(get("/v1/consents/" + profiling, TENANT).get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(get("/v1/customers/" + alice, TENANT).get("display_name").asString()).isEqualTo("Alice Smith");

        // Erasure of a never-onboarded prospect is complete.
        String prospect = post("/v1/customers", individual("CUS-0400", "Peter", "Prospect", "1999-09-09"), 201).get("id").asString();
        post("/v1/customers/" + prospect + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"peter@example.com"}""".formatted(prospect), 201);
        String full = post("/v1/privacy-requests", privacy(prospect, "ERASURE"), 201).get("id").asString();
        post("/v1/privacy-requests/" + full + "/verify-identity", "{\"method\":\"EMAIL_LINK\"}", 200);
        assertThat(post("/v1/privacy-requests/" + full + "/execute", null, 200).get("status").asString()).isEqualTo("COMPLETED");
        JsonNode erased = get("/v1/customers/" + prospect, TENANT);
        assertThat(erased.get("display_name").asString()).isEqualTo("ERASED");
        assertThat(erased.has("first_name")).isFalse();
        assertThat(erased.get("status").asString()).isEqualTo("CLOSED");
        assertThat(get("/v1/customers/" + prospect + "/contacts", TENANT).get("data")).isEmpty();

        // Rejection.
        String rejected = post("/v1/privacy-requests", privacy(alice, "RECTIFICATION"), 201).get("id").asString();
        assertThat(post("/v1/privacy-requests/" + rejected + "/reject", "{\"reason\":\"identity not proven\"}", 200)
                .get("status").asString()).isEqualTo("REJECTED");
        assertThat(code(post("/v1/privacy-requests/" + rejected + "/reject", "{\"reason\":\"x\"}", 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/privacy-requests", """
                {"customer_id":"%s","request_type":"EXPORT","due_at":"%s"}""".formatted(alice, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1)), 422)))
                .isEqualTo("invalid_due_at");
        assertThat(outbox("party.consent.given", null)).isGreaterThanOrEqualTo(4);
        assertThat(outbox("party.consent.withdrawn", marketing)).isEqualTo(1);
        assertThat(outbox("party.privacy_request.completed", null)).isEqualTo(3);
        assertThat(outbox("party.privacy_request.rejected", rejected)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ groups and segments

    @Test
    @Order(7)
    void groupsAndSegments() throws Exception {
        String opco = ids(get("/v1/customers/search?q=OpCo", TENANT)).getFirst();
        String household = post("/v1/customer-groups", "{\"group_type\":\"HOUSEHOLD\",\"name\":\"Smith household\"}", 201)
                .get("id").asString();
        String member = post("/v1/customer-groups/" + household + "/members",
                "{\"customer_id\":\"" + alice + "\",\"member_role\":\"HEAD\",\"joined_on\":\"" + LocalDate.now() + "\"}", 201)
                .get("id").asString();
        assertThat(code(post("/v1/customer-groups/" + household + "/members",
                "{\"customer_id\":\"" + opco + "\",\"joined_on\":\"" + LocalDate.now() + "\"}", 422))).isEqualTo("invalid_member");
        assertThat(code(post("/v1/customer-groups/" + household + "/members",
                "{\"customer_id\":\"" + alice + "\",\"joined_on\":\"" + LocalDate.now() + "\"}", 409))).isEqualTo("already_member");
        assertThat(get("/v1/customer-groups/" + household + "/members", TENANT).get("data")).hasSize(1);
        JsonNode exposure = get("/v1/customer-groups/" + household + "/exposure", TENANT);
        assertThat(exposure.get("member_count").asInt()).isEqualTo(1);
        assertThat(send("DELETE", "/v1/customer-groups/" + household, null, null, TENANT, "u").statusCode()).isEqualTo(409);
        assertThat(send("DELETE", "/v1/customer-groups/" + household + "/members/" + member, null, key(), TENANT, "u").statusCode())
                .isEqualTo(204);
        assertThat(send("DELETE", "/v1/customer-groups/" + household, null, null, TENANT, "u").statusCode()).isEqualTo(204);

        assertThat(code(post("/v1/customer-segments", """
                {"code":"bad","name":"Bad","is_automatic":true,"criteria":{"field":"password","op":"eq","value":"x"}}""", 422)))
                .isEqualTo("invalid_criteria");
        String segment = post("/v1/customer-segments", """
                {"code":"verified_retail","name":"Verified retail","is_automatic":true,"priority":10,
                 "criteria":{"all":[{"field":"customer_type","op":"eq","value":"INDIVIDUAL"},
                                    {"field":"kyc_tier","op":"gte","value":"TIER_1"},
                                    {"field":"age","op":"gte","value":18}]}}""", 201).get("id").asString();
        JsonNode result = post("/v1/customer-segments/" + segment + "/evaluate", null, 200);
        assertThat(result.get("added").asInt()).isEqualTo(1);
        assertThat(get("/v1/customers/" + alice + "/segments", TENANT).get("data")).hasSize(1);
        assertThat(post("/v1/customer-segments/" + segment + "/evaluate", null, 200).get("added").asInt()).isZero();
        String manual = post("/v1/customer-segments", "{\"code\":\"vip\",\"name\":\"VIP\"}", 201).get("id").asString();
        assertThat(code(post("/v1/customer-segments/" + manual + "/evaluate", null, 409))).isEqualTo("segment_not_automatic");
        post("/v1/customers/" + alice + "/segments", "{\"customer_id\":\"" + alice + "\",\"customer_segment_id\":\"" + manual
                + "\",\"valid_from\":\"" + LocalDate.now() + "\"}", 201);
        assertThat(code(post("/v1/customers/" + alice + "/segments", "{\"customer_id\":\"" + alice + "\",\"customer_segment_id\":\""
                + manual + "\",\"valid_from\":\"" + LocalDate.now() + "\"}", 409))).isEqualTo("already_assigned");
    }

    // ------------------------------------------------------------------ tenant isolation and closure

    @Test
    @Order(8)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/customers/" + alice, null, null, OTHER_TENANT, "u").statusCode()).isEqualTo(404);
        assertThat(send("GET", "/v1/customers/" + alice + "/view", null, null, OTHER_TENANT, "u").statusCode()).isEqualTo(404);
        assertThat(ids(get("/v1/customers/search?q=smith", OTHER_TENANT))).isEmpty();
        assertThat(send("POST", "/v1/customers/" + alice + "/suspend", "{\"reason\":\"x\"}", key(), OTHER_TENANT, "u").statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/customers", OTHER_TENANT).get("data")).isEmpty();
    }

    @Test
    @Order(9)
    void closeAndDeceased() throws Exception {
        String opco = ids(get("/v1/customers/search?q=OpCo", TENANT)).getFirst();
        assertThat(code(post("/v1/customers/" + opco + "/mark-deceased", deceased(LocalDate.now().minusDays(1)), 422)))
                .isEqualTo("not_a_natural_person");
        String bob = ids(get("/v1/customers/search?q=bob jones", TENANT)).getFirst();
        assertThat(code(post("/v1/customers/" + bob + "/mark-deceased", deceased(LocalDate.now().plusDays(1)), 422)))
                .isEqualTo("invalid_deceased_on");
        assertThat(post("/v1/customers/" + bob + "/mark-deceased", deceased(LocalDate.now().minusDays(2)), 200)
                .get("status").asString()).isEqualTo("DECEASED");
        assertThat(code(post("/v1/customers/" + bob + "/close", "{\"reason\":\"estate settled\"}", 409))).isEqualTo("invalid_transition");
        JsonNode closed = post("/v1/customers/" + alice + "/close", "{\"reason\":\"customer request\"}", 200);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("closure_reason").asString()).isEqualTo("customer request");
        assertThat(get("/v1/customers/" + alice + "/credentials?status=ACTIVE", TENANT).get("data")).isEmpty();
        assertThat(code(post("/v1/customers/" + alice + "/reactivate", "{\"reason\":\"oops\"}", 409))).isEqualTo("invalid_transition");
        assertThat(code(post("/v1/customers/" + alice + "/contacts", """
                {"customer_id":"%s","contact_type":"EMAIL","email":"late@example.com"}""".formatted(alice), 409))).isEqualTo("customer_closed");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean luhnValid(String digits) {
        int sum = 0;
        for (int i = digits.length() - 1, k = 0; i >= 0; i--, k++) {
            int d = digits.charAt(i) - '0';
            if (k % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    /** The customer number is server-generated; {@code ref} is the client's migration reference (external_ref). */
    private static String individual(String ref, String first, String last, String dob) {
        return """
                {"external_ref":"%s","customer_type":"INDIVIDUAL","legal_entity_id":"%s",
                 "first_name":"%s","last_name":"%s","date_of_birth":"%s","residence_country":"GB"}"""
                .formatted(ref, LEGAL_ENTITY, first, last, dob);
    }

    private static String company(String ref, String name) {
        return """
                {"external_ref":"%s","customer_type":"COMPANY","legal_entity_id":"%s","legal_name":"%s",
                 "registration_number":"%s"}""".formatted(ref, LEGAL_ENTITY, name, ref.replace("-", ""));
    }

    private static String rel(String subject, String related, String type, String pct) {
        return """
                {"customer_id":"%s","related_customer_id":"%s","relationship_type":"%s",%s"valid_from":"2020-01-01"}"""
                .formatted(subject, related, type, pct == null ? "" : "\"ownership_percentage\":\"" + pct + "\",");
    }

    private static String consent(String customer, String purpose, String expires) {
        return """
                {"customer_id":"%s","purpose":"%s","policy_version":"v3","given_at":"%s"%s}"""
                .formatted(customer, purpose, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                        expires == null ? "" : ",\"expires_at\":\"" + expires + "\"");
    }

    private static String privacy(String customer, String type) {
        return """
                {"customer_id":"%s","request_type":"%s","due_at":"%s","channel":"WEB"}"""
                .formatted(customer, type, OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
    }

    private static String deceased(LocalDate on) {
        return "{\"deceased_on\":\"" + on + "\",\"document_id\":\"" + UUID.randomUUID() + "\"}";
    }

    private static String passkeyBody(String credentialId, String challenge, String origin, String device) {
        String clientData = "{\"type\":\"webauthn.create\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\"}";
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(clientData.getBytes(StandardCharsets.UTF_8));
        return """
                {"attestation":{"id":"%s","public_key":"pQECAyYgASFYIA","client_data_json":"%s","sign_count":0},
                 "customer_device_id":"%s"}""".formatted(credentialId, b64, device);
    }

    private static String sign(KeyPair keys, String message) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(keys.getPrivate());
        s.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private String otpFromLastMessage() {
        assertThat(sentMessages).isNotEmpty();
        Matcher m = Pattern.compile("(\\d{6})").matcher(sentMessages.getLast());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private JsonNode outboxPayload(String type, String aggregateId) {
        return json.readTree(jdbc.sql("""
                select payload::text from outbox where event_type = ? and aggregate_id = ?::uuid order by id desc limit 1""")
                .params(type, aggregateId).query(String.class).single());
    }

    private int outbox(String type, String aggregateId) {
        return aggregateId == null
                ? jdbc.sql("select count(*) from outbox where event_type = ? and tenant_id = ?::uuid")
                        .params(type, TENANT).query(Integer.class).single()
                : jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                        .params(type, aggregateId).query(Integer.class).single();
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("data").forEach(n -> out.add(n.get("id").asString()));
        return out;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private String code(JsonNode problem) {
        return problem.path("code").asString();
    }

    private String code(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body()).path("code").asString();
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), TENANT, "maker");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, int version, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "maker").header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "reader");
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant, String user)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path.replace(" ", "%20")))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
