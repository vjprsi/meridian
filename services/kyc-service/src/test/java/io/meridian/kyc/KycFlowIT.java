package io.meridian.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * KYC flows over HTTP against PostgreSQL 18 with row-level security: providers, documents with maker-checker,
 * sandbox provider checks (sync and async callback), screening disposition, profile evaluation into tier/risk/status,
 * enhanced due diligence, manual risk assessments and periodic reviews.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.kyc.webhook-secrets.SANDBOX_KYC=whsec_test"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KycFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c1";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c2";
    static final String MAKER = "maker";
    static final String CHECKER = "checker";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> customerNames = new ConcurrentHashMap<>();

    String provider;
    String alice = UUID.randomUUID().toString();
    String aliceProfile = UUID.randomUUID().toString();
    String bob = UUID.randomUUID().toString();
    String bobProfile = UUID.randomUUID().toString();
    String passport;

    @BeforeEach
    void stubCustomerService() {
        when(serviceClient.get(eq("customer-service"), anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            String id = path.substring(path.lastIndexOf('/') + 1);
            String name = customerNames.get(id);
            if (name == null) {
                throw new io.meridian.common.error.ApiException(404, "not_found", "customer not found");
            }
            return json.readTree("{\"id\":\"" + id + "\",\"display_name\":\"" + name + "\"}");
        });
    }

    @Test
    @Order(1)
    void providersAreValidatedAndConnectionTested() throws Exception {
        JsonNode p = post("/v1/kyc/providers", """
                {"code":"sandbox_kyc","name":"Sandbox","capabilities":{"values":["DOCUMENT","DATABASE","LIVENESS",
                 "FACE_MATCH","ADDRESS","SCREENING"]},"countries":{"values":["ke","GB"]},"priority":10}""", 201, MAKER);
        provider = p.get("id").asString();
        assertThat(p.get("code").asString()).isEqualTo("SANDBOX_KYC");
        assertThat(p.has("credentials_ref")).isFalse();
        assertThat(p.get("countries").get("values").get(0).asString()).isEqualTo("KE");

        post("/v1/kyc/providers", """
                {"code":"SANDBOX_FB","name":"Fallback","capabilities":{"DATABASE":true},"is_fallback":true,"priority":50}""",
                201, MAKER);
        JsonNode bad = post("/v1/kyc/providers", """
                {"code":"BAD","name":"Bad","capabilities":{"values":["TELEPATHY"]}}""", 422, MAKER);
        assertThat(bad.get("code").asString()).isEqualTo("invalid_capabilities");
        JsonNode dup = post("/v1/kyc/providers", """
                {"code":"SANDBOX_KYC","name":"Again","capabilities":{"values":["DOCUMENT"]}}""", 409, MAKER);
        assertThat(dup.get("code").asString()).isEqualTo("duplicate");

        JsonNode test = post("/v1/kyc/providers/" + provider + "/test-connection", null, 200, MAKER);
        assertThat(test.get("ok").asBoolean()).isTrue();

        String inactive = post("/v1/kyc/providers", """
                {"code":"SANDBOX_OFF","name":"Off","capabilities":{"values":["DOCUMENT"]},"is_active":false}""", 201, MAKER)
                .get("id").asString();
        assertThat(post("/v1/kyc/providers/" + inactive + "/test-connection", null, 409, MAKER).get("code").asString())
                .isEqualTo("provider_inactive");
    }

    @Test
    @Order(2)
    void documentsAreProvisionedAndReviewedWithFourEyes() throws Exception {
        JsonNode doc = post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"PASSPORT","document_id":"%s",
                 "issuing_country":"GB","issue_date":"%s","expiry_date":"%s",
                 "extracted_data":{"full_name":"Alice Good"}}"""
                .formatted(aliceProfile, alice, UUID.randomUUID(), TODAY.minusYears(1), TODAY.plusYears(5)), 201, MAKER);
        passport = doc.get("id").asString();
        assertThat(doc.get("status").asString()).isEqualTo("UPLOADED");
        assertThat(get("/v1/kyc/profiles/" + aliceProfile, TENANT).get("status").asString()).isEqualTo("NOT_STARTED");

        JsonNode same = post("/v1/kyc/documents/" + passport + "/accept", "{}", 422, MAKER);
        assertThat(same.get("code").asString()).isEqualTo("four_eyes_violation");
        assertThat(post("/v1/kyc/documents/" + passport + "/accept", "{\"comment\":\"ok\"}", 200, CHECKER)
                .get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(post("/v1/kyc/documents/" + passport + "/reject", "{\"reason\":\"late\"}", 409, CHECKER)
                .get("code").asString()).isEqualTo("invalid_transition");

        JsonNode expired = post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"NATIONAL_ID","document_id":"%s",
                 "expiry_date":"%s"}""".formatted(aliceProfile, alice, UUID.randomUUID(), TODAY.minusDays(1)), 422, MAKER);
        assertThat(expired.get("code").asString()).isEqualTo("document_expired");

        String expiring = post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"DRIVING_LICENCE","document_id":"%s",
                 "expiry_date":"%s"}""".formatted(aliceProfile, alice, UUID.randomUUID(), TODAY.plusDays(10)), 201, MAKER)
                .get("id").asString();
        assertThat(outbox("party.kyc_document.expiring", expiring)).isEqualTo(1);
        JsonNode rejected = post("/v1/kyc/documents/" + expiring + "/reject", "{\"reason\":\"blurred\"}", 200, CHECKER);
        assertThat(rejected.get("rejection_reason").asString()).isEqualTo("blurred");

        JsonNode mismatch = post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"PASSPORT","document_id":"%s"}"""
                .formatted(UUID.randomUUID(), alice, UUID.randomUUID()), 409, MAKER);
        assertThat(mismatch.get("code").asString()).isEqualTo("profile_mismatch");

        JsonNode missing = post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","document_type":"PASSPORT"}""".formatted(aliceProfile), 400, MAKER);
        assertThat(missing.get("code").asString()).isEqualTo("validation_failed");
    }

    @Test
    @Order(3)
    void sandboxChecksPassFailReferAndCompleteAsynchronously() throws Exception {
        JsonNode passed = check(aliceProfile, "DOCUMENT_AUTHENTICITY", passport, 201);
        assertThat(passed.get("status").asString()).isEqualTo("PASSED");
        assertThat(passed.get("provider_reference").asString()).startsWith("sbx_");
        assertThat(passed.has("request_payload")).isFalse();
        assertThat(outbox("party.kyc_check.completed", passed.get("id").asString())).isEqualTo(1);

        // Bob: profile provisioned by a document without OCR data; the subject name comes from customer-service
        post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"PASSPORT","document_id":"%s"}"""
                .formatted(bobProfile, bob, UUID.randomUUID()), 201, MAKER);
        customerNames.put(bob, "Bob REJECT Smith");
        JsonNode failed = check(bobProfile, "DATABASE_VERIFICATION", null, 201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("reason_codes").get("values").get(0).asString()).isEqualTo("DATA_MISMATCH");

        customerNames.put(bob, "Bob Smith");
        JsonNode retried = post("/v1/kyc/checks/" + failed.get("id").asString() + "/retry",
                "{\"use_fallback\":true}", 200, MAKER);
        assertThat(retried.get("status").asString()).isEqualTo("PASSED");
        assertThat(retried.get("kyc_provider_id").asString()).isNotEqualTo(provider);
        assertThat(post("/v1/kyc/checks/" + retried.get("id").asString() + "/retry", "{}", 409, MAKER)
                .get("code").asString()).isEqualTo("invalid_transition");

        customerNames.put(bob, "Bob REVIEW Smith");
        assertThat(check(bobProfile, "LIVENESS", null, 201).get("status").asString()).isEqualTo("INCONCLUSIVE");

        customerNames.put(bob, "Bob ASYNC Smith");
        JsonNode pending = check(bobProfile, "FACE_MATCH", null, 201);
        assertThat(pending.get("status").asString()).isEqualTo("IN_PROGRESS");
        String ref = pending.get("provider_reference").asString();
        String badSig = callback("SANDBOX_KYC", ref, "PASSED", "00ff", 401);
        assertThat(badSig).contains("invalid_signature");
        callback("SANDBOX_KYC", ref, "PASSED", sign(ref + ":PASSED"), 204);
        JsonNode done = get("/v1/kyc/checks/" + pending.get("id").asString(), TENANT);
        assertThat(done.get("status").asString()).isEqualTo("PASSED");
        assertThat(done.hasNonNull("completed_at")).isTrue();
        callback("SANDBOX_KYC", ref, "FAILED", sign(ref + ":FAILED"), 204); // late duplicate is ignored
        assertThat(get("/v1/kyc/checks/" + pending.get("id").asString(), TENANT).get("status").asString())
                .isEqualTo("PASSED");
        callback("SANDBOX_KYC", "sbx_unknown", "PASSED", sign("sbx_unknown:PASSED"), 404);

        JsonNode noCapability = check(bobProfile, "REGISTRY_LOOKUP", null, 422);
        assertThat(noCapability.get("code").asString()).isEqualTo("provider_capability_missing");
        JsonNode noProfile = check(UUID.randomUUID().toString(), "LIVENESS", null, 422);
        assertThat(noProfile.get("code").asString()).isEqualTo("profile_not_found");
        assertThat(get("/v1/kyc/profiles/" + bobProfile + "/checks", TENANT).get("data")).hasSize(3);
    }

    @Test
    @Order(4)
    void screeningMatchIsDispositionedByAnotherUser() throws Exception {
        JsonNode s = post("/v1/kyc/screenings", """
                {"kyc_profile_id":"%s","customer_id":"%s","screening_type":"PEP","trigger":"onboarding",
                 "screened_name":"Alice Good","status":"POTENTIAL_MATCH",
                 "matches":{"values":[{"list":"PEP-GLOBAL","score":0.71}]}}""".formatted(aliceProfile, alice), 201, MAKER);
        String id = s.get("id").asString();
        assertThat(s.get("match_count").asInt()).isEqualTo(1);
        assertThat(outbox("party.kyc_screening.match_found", id)).isEqualTo(1);

        String body = "{\"status\":\"FALSE_POSITIVE\",\"review_comment\":\"Different date of birth\"}";
        assertThat(post("/v1/kyc/screenings/" + id + "/disposition", body, 422, MAKER).get("code").asString())
                .isEqualTo("four_eyes_violation");
        assertThat(post("/v1/kyc/screenings/" + id + "/disposition",
                "{\"status\":\"CLEAR\",\"review_comment\":\"x\"}", 422, CHECKER).get("code").asString())
                .isEqualTo("invalid_disposition");
        JsonNode disposed = post("/v1/kyc/screenings/" + id + "/disposition", body, 200, CHECKER);
        assertThat(disposed.get("status").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(disposed.hasNonNull("reviewed_at")).isTrue();
        assertThat(outbox("party.kyc_screening.disposed", id)).isEqualTo(1);
        assertThat(post("/v1/kyc/screenings/" + id + "/disposition", body, 409, CHECKER).get("code").asString())
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(5)
    void evaluationVerifiesAndSchedulesReviewByRisk() throws Exception {
        HttpResponse<String> first = send("POST", "/v1/kyc/profiles/" + aliceProfile + "/evaluate", null,
                "eval-alice-1", TENANT, CHECKER);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode p = json.readTree(first.body());
        assertThat(p.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(p.get("identity_verified").asBoolean()).isTrue();
        assertThat(p.get("screening_clear").asBoolean()).isTrue();
        assertThat(p.get("achieved_tier").asString()).isEqualTo("TIER_2");
        assertThat(p.get("risk_level").asString()).isEqualTo("LOW");
        assertThat(p.get("next_review_date").asString()).isEqualTo(TODAY.plusYears(3).toString());
        assertThat(outbox("party.kyc_profile.verified", aliceProfile)).isEqualTo(1);
        assertThat(outbox("party.kyc_profile.status_changed", aliceProfile)).isEqualTo(1);

        JsonNode reviews = get("/v1/kyc/reviews?kyc_profile_id=" + aliceProfile, TENANT).get("data");
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(reviews.get(0).get("trigger").asString()).isEqualTo("PERIODIC");
        assertThat(get("/v1/kyc/profiles/" + aliceProfile + "/risk-assessments", TENANT).get("data")).hasSize(1);

        HttpResponse<String> replay = send("POST", "/v1/kyc/profiles/" + aliceProfile + "/evaluate", null,
                "eval-alice-1", TENANT, CHECKER);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(get("/v1/kyc/profiles/" + aliceProfile + "/risk-assessments", TENANT).get("data")).hasSize(1);
        HttpResponse<String> reused = send("POST", "/v1/kyc/profiles/" + bobProfile + "/evaluate", null,
                "eval-alice-1", TENANT, CHECKER);
        assertThat(reused.statusCode()).isEqualTo(422);

        JsonNode bobEval = post("/v1/kyc/profiles/" + bobProfile + "/evaluate", null, 200, CHECKER);
        assertThat(bobEval.get("status").asString()).isEqualTo("REFER"); // inconclusive liveness
    }

    @Test
    @Order(6)
    void manualRiskAssessmentAndEnhancedDueDiligence() throws Exception {
        JsonNode noReason = post("/v1/kyc/risk-assessments", """
                {"kyc_profile_id":"%s","customer_id":"%s","model_version":"analyst","score":750,"risk_level":"HIGH",
                 "factors":{"source_of_wealth":"unclear"},"trigger":"MANUAL","override_level":"PROHIBITED"}"""
                .formatted(aliceProfile, alice), 422, CHECKER);
        assertThat(noReason.get("code").asString()).isEqualTo("override_reason_required");
        assertThat(post("/v1/kyc/risk-assessments", """
                {"kyc_profile_id":"%s","customer_id":"%s","model_version":"analyst","score":2000,"risk_level":"HIGH",
                 "factors":{},"trigger":"MANUAL"}""".formatted(aliceProfile, alice), 422, CHECKER)
                .get("code").asString()).isEqualTo("invalid_score");

        JsonNode ra = post("/v1/kyc/risk-assessments", """
                {"kyc_profile_id":"%s","customer_id":"%s","model_version":"analyst","score":750,"risk_level":"HIGH",
                 "factors":{"source_of_wealth":"unclear"},"trigger":"RISK_CHANGE"}""".formatted(aliceProfile, alice),
                201, CHECKER);
        assertThat(outbox("party.kyc_profile.risk_changed", ra.get("id").asString())).isEqualTo(1);
        JsonNode p = get("/v1/kyc/profiles/" + aliceProfile, TENANT);
        assertThat(p.get("risk_level").asString()).isEqualTo("HIGH");
        assertThat(p.get("edd_required").asBoolean()).isTrue();

        assertThat(post("/v1/kyc/profiles/" + aliceProfile + "/require-edd", "{\"reason\":\"again\"}", 409, CHECKER)
                .get("code").asString()).isEqualTo("edd_already_required");
        JsonNode completed = post("/v1/kyc/profiles/" + aliceProfile + "/complete-edd",
                "{\"notes\":\"Source of wealth evidenced\"}", 200, CHECKER);
        assertThat(completed.hasNonNull("edd_completed_at")).isTrue();
        assertThat(completed.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(post("/v1/kyc/profiles/" + aliceProfile + "/complete-edd", "{\"notes\":\"x\"}", 409, CHECKER)
                .get("code").asString()).isEqualTo("edd_not_required");
        JsonNode required = post("/v1/kyc/profiles/" + aliceProfile + "/require-edd",
                "{\"reason\":\"adverse media\"}", 200, CHECKER);
        assertThat(required.get("status").asString()).isEqualTo("REFER");
        assertThat(required.get("edd_required").asBoolean()).isTrue();
    }

    @Test
    @Order(7)
    void reviewsAreWorkedAndCompletionSchedulesTheNextOne() throws Exception {
        JsonNode manual = post("/v1/kyc/reviews", """
                {"kyc_profile_id":"%s","customer_id":"%s","trigger":"MANUAL","due_date":"%s"}"""
                .formatted(aliceProfile, alice, TODAY.minusDays(1)), 201, MAKER);
        String id = manual.get("id").asString();
        assertThat(manual.get("status").asString()).isEqualTo("DUE");
        assertThat(outbox("party.kyc_review.due", id)).isEqualTo(1);

        String analyst = UUID.randomUUID().toString();
        JsonNode assigned = post("/v1/kyc/reviews/" + id + "/assign", "{\"assigned_to\":\"" + analyst + "\"}", 200, MAKER);
        assertThat(assigned.get("status").asString()).isEqualTo("IN_PROGRESS");
        JsonNode outreach = post("/v1/kyc/reviews/" + id + "/request-update", "{\"message\":\"Send new utility bill\"}",
                200, MAKER);
        assertThat(outreach.hasNonNull("customer_outreach_at")).isTrue();

        HttpResponse<String> patch = patch("/v1/kyc/reviews/" + id, "{\"status\":\"COMPLETED\"}",
                outreach.get("row_version").asString());
        assertThat(patch.statusCode()).isEqualTo(422);
        assertThat(post("/v1/kyc/reviews/" + id + "/complete", "{\"outcome\":\"MAYBE\"}", 422, analyst)
                .get("code").asString()).isEqualTo("invalid_outcome");
        JsonNode done = post("/v1/kyc/reviews/" + id + "/complete",
                "{\"outcome\":\"NO_CHANGE\",\"outcome_notes\":\"All good\"}", 200, analyst);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(outbox("party.kyc_review.completed", id)).isEqualTo(1);
        assertThat(post("/v1/kyc/reviews/" + id + "/complete", "{\"outcome\":\"NO_CHANGE\"}", 409, analyst)
                .get("code").asString()).isEqualTo("invalid_transition");
        JsonNode profile = get("/v1/kyc/profiles/" + aliceProfile, TENANT);
        assertThat(profile.get("last_reviewed_by").asString()).isEqualTo(analyst);
        assertThat(profile.get("next_review_date").asString()).isEqualTo(TODAY.plusYears(3).toString()); // LOW after EDD re-evaluation

        // the periodic review created at verification is still open; completing it schedules the next one
        JsonNode periodic = get("/v1/kyc/reviews?kyc_profile_id=" + aliceProfile + "&status=SCHEDULED", TENANT)
                .get("data").get(0);
        post("/v1/kyc/reviews/" + periodic.get("id").asString() + "/complete", "{\"outcome\":\"UPDATED\"}", 200, analyst);
        JsonNode next = get("/v1/kyc/reviews?kyc_profile_id=" + aliceProfile + "&status=SCHEDULED", TENANT).get("data");
        assertThat(next).hasSize(1);
        assertThat(next.get(0).get("due_date").asString()).isEqualTo(TODAY.plusYears(3).toString());
        assertThat(next.get(0).get("id").asString()).isNotEqualTo(periodic.get("id").asString());
    }

    @Test
    @Order(8)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/kyc/profiles/" + aliceProfile, null, null, OTHER_TENANT, MAKER).statusCode())
                .isEqualTo(404);
        assertThat(send("POST", "/v1/kyc/profiles/" + aliceProfile + "/evaluate", null, UUID.randomUUID().toString(),
                OTHER_TENANT, MAKER).statusCode()).isEqualTo(404);
        assertThat(get("/v1/kyc/documents", OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/kyc/documents/" + passport + "/reject", "{\"reason\":\"x\"}",
                UUID.randomUUID().toString(), OTHER_TENANT, CHECKER).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode check(String profile, String type, String document, int expected) throws Exception {
        return post("/v1/kyc/checks", """
                {"kyc_profile_id":"%s","check_type":"%s","kyc_provider_id":"%s"%s}"""
                .formatted(profile, type, provider, document == null ? "" : ",\"kyc_document_id\":\"" + document + "\""),
                expected, MAKER);
    }

    private String callback(String code, String ref, String status, String signature, int expected) throws Exception {
        HttpResponse<String> r = send("POST", "/v1/kyc/checks/provider-callbacks/" + code + "/events", """
                {"payload":{"provider_reference":"%s","status":"%s","score":0.9,"signature":"%s"}}"""
                .formatted(ref, status, signature), UUID.randomUUID().toString(), TENANT, "provider");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body();
    }

    private static String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private long outbox(String eventType, String aggregateId) {
        return jdbc.sql("select count(*) from outbox where event_type = ? and aggregate_id = ?::uuid")
                .params(eventType, aggregateId).query(Long.class).single();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), TENANT, user);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path, String tenant) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, tenant, MAKER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> patch(String path, String body, String version) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", MAKER).header("Content-Type", "application/json")
                .header("If-Match", version)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String tenant,
                                      String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
