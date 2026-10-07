package io.meridian.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Onboarding end to end over HTTP against PostgreSQL 18 with RLS. kyc-service, customer-service and
 * notification-service are stubbed through the ServiceClient. Users: "maker" prepares, "checker" approves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String PASS_PROFILE = "0199a000-0000-7000-8000-00000000a001";
    static final String REVIEW_PROFILE = "0199a000-0000-7000-8000-00000000a002";
    static final String FAIL_PROFILE = "0199a000-0000-7000-8000-00000000a003";
    static final String PROVIDER = "0199a000-0000-7000-8000-00000000b001";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c001";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-00000000d001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient client;

    final List<JsonNode> customerRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

    final HttpClient http = HttpClient.newHttpClient();
    String journeyId;
    String journeyV1;

    @BeforeAll
    void setUp() throws Exception {
        journeyV1 = createJourney("RETAIL_KE", "maker", 201).get("id").asString();
    }

    @BeforeEach
    void stubs() {
        when(client.post(eq("kyc-service"), eq("/v1/kyc/checks"), any(), anyString())).thenAnswer(inv -> {
            Map<?, ?> body = inv.getArgument(2);
            String profile = String.valueOf(body.get("kyc_profile_id"));
            String status = PASS_PROFILE.equals(profile) ? "PASSED" : REVIEW_PROFILE.equals(profile) ? "INCONCLUSIVE" : "FAILED";
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"" + status + "\"}");
        });
        when(client.post(eq("customer-service"), eq("/v1/customers"), any(), anyString())).thenAnswer(inv -> {
            customerRequests.add(json.valueToTree(inv.getArgument(2)));
            return json.readTree("{\"id\":\"" + CUSTOMER + "\"}");
        });
        when(client.post(eq("notification-service"), eq("/v1/notifications"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
    }

    // ------------------------------------------------------------------ journeys

    @Test
    @Order(1)
    void journeyValidationPublicationFourEyesAndVersions() throws Exception {
        JsonNode invalid = createJourneyWith("BAD", """
                {"steps":[{"code":"a","type":"CUSTOM","next":"b"},{"code":"b","type":"NOPE","next":"a"},
                          {"code":"c","type":"CUSTOM","next":"zzz"}]}""", 422);
        assertThat(invalid.get("code").asString()).isEqualTo("invalid_definition");
        assertThat(invalid.get("errors").toString()).contains("invalid_type");
        JsonNode cyclic = createJourneyWith("CYCLE", """
                {"steps":[{"code":"a","type":"CUSTOM","next":"b"},{"code":"b","type":"CUSTOM","next":"a"}]}""", 422);
        assertThat(cyclic.get("errors").toString()).contains("cycle");

        JsonNode dup = createJourney("RETAIL_KE", "maker", 409);
        assertThat(dup.get("code").asString()).isEqualTo("journey_code_exists");

        JsonNode self = post("/v1/onboarding/journeys/" + journeyV1 + "/publish", null, "maker", 422);
        assertThat(self.get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode published = post("/v1/onboarding/journeys/" + journeyV1 + "/publish", null, "checker", 200);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.hasNonNull("published_at")).isTrue();
        assertThat(post("/v1/onboarding/journeys/" + journeyV1 + "/publish", null, "checker", 409)
                .get("code").asString()).isEqualTo("invalid_transition");

        HttpResponse<String> edit = send("PATCH", "/v1/onboarding/journeys/" + journeyV1, "{\"name\":\"x\"}", null,
                TENANT, "maker", published.get("row_version").asString());
        assertThat(edit.statusCode()).isEqualTo(409);

        JsonNode v2 = post("/v1/onboarding/journeys/" + journeyV1 + "/new-version", null, "maker", 201);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("DRAFT");
        assertThat(post("/v1/onboarding/journeys/" + journeyV1 + "/new-version", null, "maker", 409)
                .get("code").asString()).isEqualTo("draft_exists");
        HttpResponse<String> patched = send("PATCH", "/v1/onboarding/journeys/" + v2.get("id").asString(),
                "{\"name\":\"Retail wallet Kenya v2\",\"expiry_days\":10}", null, TENANT, "maker",
                v2.get("row_version").asString());
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        HttpResponse<String> stale = send("PATCH", "/v1/onboarding/journeys/" + v2.get("id").asString(),
                "{\"name\":\"stale\"}", null, TENANT, "maker", v2.get("row_version").asString());
        assertThat(stale.statusCode()).isEqualTo(409);

        journeyId = post("/v1/onboarding/journeys/" + v2.get("id").asString() + "/publish", null, "checker", 200)
                .get("id").asString();
        assertThat(get("/v1/onboarding/journeys/" + journeyV1, TENANT).get("status").asString()).isEqualTo("RETIRED");
        assertThat(outboxEvents(journeyId)).contains("party.onboarding_journey.published");
    }

    @Test
    @Order(2)
    void simulateAndRetire() throws Exception {
        JsonNode sim = post("/v1/onboarding/journeys/" + journeyId + "/simulate", """
                {"sample_data":{"phone_number":"+254700000001","first_name":"A","last_name":"B","date_of_birth":"1990-01-01",
                 "resident":false,"annual_income":5000000}}""", "maker", 200);
        List<String> path = new ArrayList<>();
        sim.get("path").forEach(p -> path.add(p.get("step_code").asString()));
        assertThat(path).containsExactly("phone", "details", "tax", "id_check", "marketing", "terms");
        assertThat(sim.get("complete").asBoolean()).isFalse();
        assertThat(sim.get("decision").asString()).isEqualTo("REFER");

        String other = createJourney("OLD_JOURNEY", "maker", 201).get("id").asString();
        JsonNode retired = post("/v1/onboarding/journeys/" + other + "/retire", "{\"reason\":\"superseded\"}", "maker", 200);
        assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
        assertThat(retired.get("retirement_reason").asString()).isEqualTo("superseded");
        assertThat(retired.hasNonNull("retired_at")).isTrue();
        assertThat(outboxEvents(other)).contains("party.onboarding_journey.retired");
        assertThat(post("/v1/onboarding/journeys/" + other + "/retire", "{}", "maker", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        JsonNode notPublished = post("/v1/onboarding/applications", appBody(other, "MOBILE_APP", "+254700000099"), "maker", 422);
        assertThat(notPublished.get("code").asString()).isEqualTo("journey_not_published");
    }

    // ------------------------------------------------------------------ applications

    @Test
    @Order(3)
    void happyPathAutoApprovesAndCreatesCustomer() throws Exception {
        String body = appBody(journeyId, "MOBILE_APP", "+254700000001");
        HttpResponse<String> created = send("POST", "/v1/onboarding/applications", body, "app-create-1", TENANT, "maker", null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String app = json.readTree(created.body()).get("id").asString();
        assertThat(json.readTree(created.body()).get("application_number").asString())
                .as("server-generated; the client's number is ignored").matches("APP-\\d{8}-[0-9A-Z]{8}");
        HttpResponse<String> replay = send("POST", "/v1/onboarding/applications", body, "app-create-1", TENANT, "maker", null);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(app);

        assertThat(get("/v1/onboarding/applications/" + app + "/next-step", TENANT).get("step_code").asString()).isEqualTo("phone");
        assertThat(step(app, "terms", "{\"accepted\":true}", 409).get("code").asString()).isEqualTo("step_out_of_order");
        JsonNode missing = step(app, "phone", "{}", 422);
        assertThat(missing.get("code").asString()).isEqualTo("missing_fields");

        step(app, "phone", "{\"phone_number\":\"+254700000001\"}", 200);
        step(app, "details", personal(true), 200);
        assertThat(get("/v1/onboarding/applications/" + app + "/next-step", TENANT).get("step_code").asString()).isEqualTo("id_check");
        JsonNode kyc = step(app, "id_check", "{\"kyc_profile_id\":\"" + PASS_PROFILE + "\"}", 200);
        assertThat(kyc.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(kyc.get("output").get("kyc_check_status").asString()).isEqualTo("PASSED");
        assertThat(get("/v1/onboarding/applications/" + app + "/next-step", TENANT).get("step_code").asString()).isEqualTo("marketing");
        step(app, "terms", "{\"accepted\":true}", 200); // optional marketing step passed over
        HttpResponse<String> none = send("GET", "/v1/onboarding/applications/" + app + "/next-step", null, null, TENANT, "maker", null);
        assertThat(none.statusCode()).isEqualTo(404);
        JsonNode inProgress = get("/v1/onboarding/applications/" + app, TENANT);
        assertThat(inProgress.get("progress_percent").asInt()).isEqualTo(100);

        JsonNode submitted = post("/v1/onboarding/applications/" + app + "/submit", null, "maker", 200);
        assertThat(submitted.get("status").asString()).isEqualTo("APPROVED");
        assertThat(submitted.get("decision").asString()).isEqualTo("AUTO_APPROVE");
        assertThat(submitted.get("risk_level").asString()).isEqualTo("LOW");
        assertThat(submitted.get("customer_id").asString()).isEqualTo(CUSTOMER);
        verify(client, atLeastOnce()).post(eq("customer-service"), eq("/v1/customers"), any(), eq("onb-cust:" + app));
        JsonNode customerBody = customerRequests.getLast();
        assertThat(customerBody.has("customer_number")).isFalse(); // assigned by customer-service
        assertThat(customerBody.get("legal_entity_id").asString()).isEqualTo(LEGAL_ENTITY);
        assertThat(customerBody.get("first_name").asString()).isEqualTo("Amina");
        assertThat(submitted.get("decision_reasons").isArray()).isTrue();
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("maker");
        assertThat(submitted.get("risk_factors").isObject()).isTrue();
        assertThat(submitted.get("legal_entity_id").asString()).isEqualTo(LEGAL_ENTITY);

        JsonNode steps = get("/v1/onboarding/applications/" + app + "/steps", TENANT).get("data");
        assertThat(steps).hasSize(6);
        steps.forEach(s -> {
            String code = s.get("step_code").asString();
            if (code.equals("tax") || code.equals("marketing")) {
                assertThat(s.get("status").asString()).isEqualTo("SKIPPED");
            }
        });
        assertThat(post("/v1/onboarding/applications/" + app + "/submit", null, "maker", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(outboxEvents(app)).contains("party.onboarding_application.started",
                "party.onboarding_application.submitted", "party.onboarding_application.approved");
    }

    @Test
    @Order(4)
    void referralRequestInfoAndFourEyesApproval() throws Exception {
        String app = post("/v1/onboarding/applications", appBody(journeyId, "WEB", "+254700000002"), "maker", 201)
                .get("id").asString();
        step(app, "phone", "{\"phone_number\":\"+254700000002\"}", 200);
        step(app, "details", personal(false), 200);
        assertThat(get("/v1/onboarding/applications/" + app + "/next-step", TENANT).get("step_code").asString())
                .isEqualTo("tax"); // non-resident branch
        step(app, "tax", "{\"tax_country\":\"UG\"}", 200);
        JsonNode review = step(app, "id_check", "{\"kyc_profile_id\":\"" + REVIEW_PROFILE + "\"}", 200);
        assertThat(review.get("status").asString()).isEqualTo("WAITING_REVIEW");
        step(app, "terms", "{\"accepted\":true}", 200);

        JsonNode referred = post("/v1/onboarding/applications/" + app + "/submit", null, "maker", 200);
        assertThat(referred.get("status").asString()).isEqualTo("REFERRED");
        assertThat(referred.get("decision").asString()).isEqualTo("REFER");

        assertThat(post("/v1/onboarding/applications/" + app + "/approve", "{\"comment\":\"ok\"}", "maker", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        assertThat(post("/v1/onboarding/applications/" + app + "/request-info",
                "{\"message\":\"Please re-accept\",\"step_codes\":[\"nope\"]}", "checker", 422)
                .get("code").asString()).isEqualTo("unknown_step");
        JsonNode info = post("/v1/onboarding/applications/" + app + "/request-info",
                "{\"message\":\"Please re-accept the terms\",\"step_codes\":[\"terms\"]}", "checker", 200);
        assertThat(info.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(info.get("info_requests")).hasSize(1);
        assertThat(info.get("info_requests").get(0).get("step_codes").get(0).asString()).isEqualTo("terms");
        assertThat(info.get("info_requests").get(0).get("requested_by").asString()).isEqualTo("checker");
        assertThat(outboxEvents(app)).contains("party.onboarding_application.info_requested");
        assertThat(get("/v1/onboarding/applications/" + app + "/next-step", TENANT).get("step_code").asString()).isEqualTo("terms");
        step(app, "terms", "{\"accepted\":true}", 200);
        assertThat(post("/v1/onboarding/applications/" + app + "/submit", null, "maker", 200).get("status").asString())
                .isEqualTo("REFERRED");
        JsonNode approved = post("/v1/onboarding/applications/" + app + "/approve", "{\"comment\":\"docs fine\"}", "checker", 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.hasNonNull("reviewed_at")).isTrue();
        assertThat(approved.get("reviewed_by").asString()).isEqualTo("checker");
        assertThat(approved.get("review_comment").asString()).isEqualTo("docs fine");
        assertThat(post("/v1/onboarding/applications/" + app + "/abandon", null, "maker", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(outboxEvents(app)).contains("party.onboarding_application.referred", "party.onboarding_application.approved");
    }

    @Test
    @Order(5)
    void failedKycCheckRejects() throws Exception {
        String app = post("/v1/onboarding/applications", appBody(journeyId, "MOBILE_APP", "+254700000003"), "maker", 201)
                .get("id").asString();
        step(app, "phone", "{\"phone_number\":\"+254700000003\"}", 200);
        step(app, "details", personal(true), 200);
        assertThat(step(app, "id_check", "{}", 422).get("code").asString()).isEqualTo("missing_fields");
        JsonNode failed = step(app, "id_check", "{\"kyc_profile_id\":\"" + FAIL_PROFILE + "\"}", 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        step(app, "terms", "{\"accepted\":true}", 200);
        JsonNode rejected = post("/v1/onboarding/applications/" + app + "/submit", null, "maker", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_reason").asString()).contains("KYC_CHECK_FAILED");
        assertThat(rejected.get("risk_level").asString()).isEqualTo("MEDIUM");
        assertThat(outboxEvents(app)).contains("party.onboarding_application.rejected");
    }

    @Test
    @Order(6)
    void abandonResumeAndStaffReject() throws Exception {
        String app = post("/v1/onboarding/applications", appBody(journeyId, "AGENT", "+254700000004"), "maker", 201)
                .get("id").asString();
        assertThat(post("/v1/onboarding/applications/" + app + "/abandon", null, "maker", 200).get("status").asString())
                .isEqualTo("ABANDONED");
        assertThat(send("GET", "/v1/onboarding/applications/" + app + "/next-step", null, null, TENANT, "maker", null)
                .statusCode()).isEqualTo(409);
        JsonNode resumed = post("/v1/onboarding/applications/" + app + "/resume", null, "maker", 202);
        assertThat(resumed.get("channel").asString()).isEqualTo("SMS");
        assertThat(resumed.get("status").asString()).isEqualTo("IN_PROGRESS");
        verify(client, atLeastOnce()).post(eq("notification-service"), eq("/v1/notifications"), any(), anyString());
        JsonNode rejected = post("/v1/onboarding/applications/" + app + "/reject", "{\"reason\":\"suspected fraud\"}", "maker", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(post("/v1/onboarding/applications/" + app + "/resume", null, "maker", 409).get("code").asString())
                .isEqualTo("invalid_transition");
        assertThat(outboxEvents(app)).contains("party.onboarding_application.abandoned");
    }

    @Test
    @Order(7)
    void idleApplicationExpiresLazily() throws Exception {
        String app = post("/v1/onboarding/applications", appBody(journeyId, "WEB", "+254700000005"), "maker", 201)
                .get("id").asString();
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             PreparedStatement ps = c.prepareStatement(
                     "update onboarding.onboarding_application set expires_at = now() - interval '1 day' where id = ?::uuid")) {
            ps.setString(1, app);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        HttpResponse<String> r = send("GET", "/v1/onboarding/applications/" + app + "/next-step", null, null, TENANT, "maker", null);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(json.readTree(r.body()).get("code").asString()).isEqualTo("application_expired");
        assertThat(get("/v1/onboarding/applications/" + app, TENANT).get("status").asString()).isEqualTo("EXPIRED");
    }

    @Test
    @Order(8)
    void validationErrors() throws Exception {
        JsonNode channel = post("/v1/onboarding/applications", appBody(journeyId, "BRANCH", "+254700000006"), "maker", 422);
        assertThat(channel.get("code").asString()).isEqualTo("channel_not_allowed");
        HttpResponse<String> noKey = send("POST", "/v1/onboarding/applications",
                appBody(journeyId, "WEB", "+254700000006"), null, TENANT, "maker", null);
        assertThat(noKey.statusCode()).isEqualTo(400);
        JsonNode reused = json.readTree(send("POST", "/v1/onboarding/applications",
                appBody(journeyId, "WEB", "+254700000007"), "app-create-1", TENANT, "maker", null).body());
        assertThat(reused.get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    // ------------------------------------------------------------------ offline batches

    @Test
    @Order(9)
    void offlineBatchIsIdempotentPerRecord() throws Exception {
        String openApp = post("/v1/onboarding/applications", appBody(journeyId, "AGENT", "+254700000010"), "maker", 201)
                .get("id").asString();
        OffsetDateTime from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        OffsetDateTime to = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        String inside = from.plusHours(5).toString();
        String payload = """
                {"applications":[
                 {"client_ref":"r1","onboarding_journey_id":"%s","channel":"AGENT","phone":"+254700000011",
                  "collected_data":{"first_name":"Off"},"captured_at":"%s"},
                 {"client_ref":"r2","onboarding_journey_id":"%s","channel":"AGENT","phone":"+254700000012",
                  "captured_at":"%s"},
                 {"client_ref":"r3","onboarding_journey_id":"%s","channel":"AGENT","phone":"+254700000010",
                  "captured_at":"%s"}]}""".formatted(journeyId, inside, journeyId, to.plusDays(1), journeyId, inside);
        String agent = UUID.randomUUID().toString();
        String body = """
                {"agent_id":"%s","device_fingerprint":"agent-device-1","captured_from":"%s","captured_to":"%s",
                 "application_count":3,"payload_uri":"data:application/gzip;base64,%s","payload_signature":"c2ln"}"""
                .formatted(agent, from, to, gzipBase64(json.writeValueAsString(json.readTree(payload))));
        HttpResponse<String> r = send("POST", "/v1/onboarding/offline-batches", body, "batch-1", TENANT, "agent", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        JsonNode batch = json.readTree(r.body());
        String batchId = batch.get("id").asString();
        assertThat(batch.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(batch.get("result").get("applied").asInt()).isEqualTo(1);
        assertThat(batch.get("result").get("conflicts").asInt()).isEqualTo(1);
        assertThat(batch.get("result").get("rejected").asInt()).isEqualTo(1);
        HttpResponse<String> replay = send("POST", "/v1/onboarding/offline-batches", body, "batch-1", TENANT, "agent", null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");

        JsonNode again = post("/v1/onboarding/offline-batches/" + batchId + "/reprocess", null, "agent", 200);
        assertThat(again.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(again.get("result").toString()).contains("ALREADY_APPLIED");
        assertThat(get("/v1/onboarding/applications?agent_id=" + agent, TENANT).get("data")).hasSize(1);

        post("/v1/onboarding/applications/" + openApp + "/abandon", null, "maker", 200);
        JsonNode resolved = post("/v1/onboarding/offline-batches/" + batchId + "/reprocess", null, "agent", 200);
        assertThat(resolved.get("status").asString()).isEqualTo("APPLIED");
        assertThat(get("/v1/onboarding/applications?agent_id=" + agent, TENANT).get("data")).hasSize(2);
        assertThat(post("/v1/onboarding/offline-batches/" + batchId + "/reprocess", null, "agent", 409)
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(outboxEvents(batchId)).contains("party.onboarding_offline_batch.processed");

        String unreachable = body.replaceAll("data:application/gzip;base64,[A-Za-z0-9+/=]+", "s3://bucket/batch.enc");
        JsonNode rejected = post("/v1/onboarding/offline-batches", unreachable, "agent", 201);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("result").get("error").asString()).isEqualTo("payload_unavailable");
    }

    @Test
    @Order(10)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/onboarding/journeys/" + journeyId, null, null, OTHER_TENANT, "maker", null)
                .statusCode()).isEqualTo(404);
        assertThat(get("/v1/onboarding/applications", OTHER_TENANT).get("data")).isEmpty();
        HttpResponse<String> publish = send("POST", "/v1/onboarding/journeys/" + journeyV1 + "/retire", "{}",
                UUID.randomUUID().toString(), OTHER_TENANT, "checker", null);
        assertThat(publish.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private static final String DEFINITION = """
            {"steps":[
              {"code":"phone","type":"PHONE_VERIFICATION","fields":["phone_number"]},
              {"code":"details","type":"PERSONAL_DETAILS","fields":["first_name","last_name","date_of_birth"],
               "next":{"rules":[{"when":{"field":"resident","op":"eq","value":false},"goto":"tax"}],"default":"id_check"}},
              {"code":"tax","type":"TAX_DECLARATION","fields":["tax_country"]},
              {"code":"id_check","type":"DOCUMENT_CAPTURE","fields":["kyc_profile_id"],
               "kyc_check":{"check_type":"DOCUMENT_AUTHENTICITY","kyc_provider_id":"%s"}},
              {"code":"marketing","type":"CUSTOM","required":false},
              {"code":"terms","type":"TERMS_ACCEPTANCE","fields":["accepted"]}]}""".formatted(PROVIDER);

    private JsonNode createJourney(String code, String user, int expected) throws Exception {
        return createJourneyWith(code, DEFINITION, expected, user);
    }

    private JsonNode createJourneyWith(String code, String definition, int expected) throws Exception {
        return createJourneyWith(code, definition, expected, "maker");
    }

    private JsonNode createJourneyWith(String code, String definition, int expected, String user) throws Exception {
        return post("/v1/onboarding/journeys", """
                {"code":"%s","name":"Retail wallet Kenya","customer_type":"INDIVIDUAL","country":"KE",
                 "channels":["MOBILE_APP","WEB","AGENT"],"target_tier":"TIER_1","expiry_days":30,
                 "definition":%s,
                 "decision_rules":{"refer_if":[{"field":"annual_income","op":"gt","value":1000000,"reason":"HIGH_INCOME"}]}}"""
                .formatted(code, definition), user, expected);
    }

    private static String appBody(String journey, String channel, String phone) {
        return """
                {"application_number":"APP-%s","onboarding_journey_id":"%s","channel":"%s","phone":"%s",
                 "email":"applicant@example.com","legal_entity_id":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 12), journey, channel, phone, LEGAL_ENTITY);
    }

    private static String personal(boolean resident) {
        return """
                {"first_name":"Amina","last_name":"Otieno","date_of_birth":"1991-04-12","resident":%s}""".formatted(resident);
    }

    private JsonNode step(String app, String code, String input, int expected) throws Exception {
        return post("/v1/onboarding/applications/" + app + "/steps/" + code, "{\"input\":" + input + "}", "maker", expected);
    }

    private static String gzipBase64(String text) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private List<String> outboxEvents(String aggregateId) {
        return jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at")
                .param(aggregateId).query(String.class).list();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, "maker", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String user, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
