package io.meridian.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Public applicant surface end to end: anonymous start with rate limits, applicant tokens bound to one application
 * (hash at rest, expiry, rotation), typed step fields, OTP contact verification with lockout, documents through
 * document-service, server-generated application numbers. notification-, document- and customer-service are stubbed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.onboarding.public.client-ip-header=X-Forwarded-For",
                "meridian.onboarding.public.starts-per-device-per-hour=3",
                "meridian.onboarding.public.starts-per-ip-per-hour=4",
                "meridian.onboarding.public.otp-max-sends=4",
                "meridian.onboarding.public.otp-resend-seconds=1"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingApplicantIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c5";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-0000000000c6";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-00000000d005";
    static final String CUSTOMER = "0199a000-0000-7000-8000-00000000c005";
    static final Pattern NUMBER = Pattern.compile("APP-\\d{8}-[0-9A-HJKMNP-TV-Z]{8}");
    static final Pattern CODE = Pattern.compile("code is (\\d{6})");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient client;

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> sms = new CopyOnWriteArrayList<>();
    final Map<String, String> documentOwners = new ConcurrentHashMap<>();
    final AtomicInteger ipSeq = new AtomicInteger(10);

    String journey;
    String appA;
    String tokenA;
    String appB;
    String tokenB;

    @BeforeAll
    void publishJourney() throws Exception {
        String definition = """
                {"steps":[
                  {"code":"phone","type":"PHONE_VERIFICATION",
                   "fields":[{"name":"phone_number","type":"PHONE","label_key":"onb.phone"}]},
                  {"code":"details","type":"PERSONAL_DETAILS","fields":[
                    {"name":"first_name","type":"STRING","label_key":"onb.first_name","max_length":40,"pattern":"[A-Za-z' -]+"},
                    {"name":"last_name","type":"STRING","max_length":40},
                    {"name":"date_of_birth","type":"DATE"},
                    {"name":"gender","type":"ENUM","enum":["F","M","X"],"required":false},
                    {"name":"annual_income","type":"DECIMAL","min":0,"required":false}]},
                  {"code":"id_doc","type":"DOCUMENT_CAPTURE","fields":[{"name":"id_front","type":"DOCUMENT"}]},
                  {"code":"terms","type":"TERMS_ACCEPTANCE","fields":[{"name":"accepted","type":"BOOLEAN"}]}]}""";
        JsonNode created = staff("POST", "/v1/onboarding/journeys", """
                {"code":"SELF_SERVICE","name":"Self-service wallet","customer_type":"INDIVIDUAL","country":"KE",
                 "channels":["MOBILE_APP","WEB"],"target_tier":"TIER_1","expiry_days":30,"legal_entity_id":"%s",
                 "definition":%s}""".formatted(LEGAL_ENTITY, definition), "maker", 201);
        journey = created.get("id").asString();
        staff("POST", "/v1/onboarding/journeys/" + journey + "/publish", null, "checker", 200);
        JsonNode bad = staff("POST", "/v1/onboarding/journeys", """
                {"code":"BAD_FIELDS","name":"x","customer_type":"INDIVIDUAL","channels":["WEB"],"target_tier":"TIER_1",
                 "definition":{"steps":[{"code":"a","type":"CUSTOM","fields":[{"name":"x","type":"COLOUR"},
                   {"name":"y","type":"ENUM"}]}]}}""", "maker", 422);
        assertThat(bad.get("errors").toString()).contains("invalid_type", "definition.steps[0].fields[1].enum");
    }

    @BeforeEach
    void stubs() {
        when(client.post(eq("notification-service"), eq("/v1/notifications"), any(), anyString())).thenAnswer(inv -> {
            Map<?, ?> body = inv.getArgument(2);
            sms.add(String.valueOf(body.get("body")));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}");
        });
        when(client.post(eq("document-service"), eq("/v1/documents"), any(), anyString())).thenAnswer(inv -> {
            Map<?, ?> body = inv.getArgument(2);
            String id = UUID.randomUUID().toString();
            documentOwners.put(id, String.valueOf(body.get("owner_id")));
            return json.readTree("{\"id\":\"" + id + "\",\"status\":\"UPLOADING\"}");
        });
        when(client.post(eq("document-service"), startsWith("/v1/documents/"), isNull(), anyString())).thenAnswer(inv ->
                json.readTree("{\"method\":\"PUT\",\"url\":\"https://blob.example/upload\",\"expires_at\":\"2030-01-01T00:00:00Z\"}"));
        when(client.post(eq("document-service"), startsWith("/v1/documents/"), any(Map.class), anyString())).thenAnswer(inv ->
                json.readTree("{\"status\":\"SCANNING\",\"size_bytes\":1234}"));
        when(client.get(eq("document-service"), startsWith("/v1/documents?"))).thenAnswer(inv -> json.readTree("{\"data\":[]}"));
        when(client.get(eq("document-service"), startsWith("/v1/documents/"))).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            String id = path.substring(path.lastIndexOf('/') + 1);
            String owner = documentOwners.get(id);
            if (owner == null) {
                throw ApiException.notFound("document", id);
            }
            return json.readTree("{\"id\":\"" + id + "\",\"owner_type\":\"ONBOARDING_APPLICATION\",\"owner_id\":\"" + owner
                    + "\",\"status\":\"AVAILABLE\"}");
        });
        when(client.post(eq("customer-service"), eq("/v1/customers"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + CUSTOMER + "\"}"));
    }

    @Test
    @Order(1)
    void startIssuesABoundHashedTokenAndAServerNumber() throws Exception {
        assertThat(start("NOPE", "WEB", nextIp(), "dev-x", 422).get("code").asString()).isEqualTo("journey_not_found");
        assertThat(start("SELF_SERVICE", "AGENT", nextIp(), "dev-x", 422).get("code").asString()).isEqualTo("channel_not_allowed");

        JsonNode a = start("SELF_SERVICE", "MOBILE_APP", nextIp(), "device-a", 201);
        appA = a.get("onboarding_application_id").asString();
        tokenA = a.get("applicant_token").asString();
        assertThat(tokenA).startsWith("oat_");
        assertThat(a.has("token_hash")).isFalse();
        assertThat(a.get("current_step").asString()).isEqualTo("phone");
        assertThat(a.hasNonNull("expires_at")).isTrue();
        assertThat(NUMBER.matcher(a.get("application_number").asString()).matches())
                .as(a.get("application_number").asString()).isTrue();
        assertThat(adminCount("select count(*) from onboarding.onboarding_applicant_token where token_hash = ?", sha256(tokenA)))
                .as("only the hash is stored").isEqualTo(1);
        assertThat(adminCount("select count(*) from onboarding.onboarding_applicant_token where token_hash = ?", tokenA)).isZero();

        JsonNode b = start("SELF_SERVICE", "WEB", nextIp(), "device-b", 201);
        appB = b.get("onboarding_application_id").asString();
        tokenB = b.get("applicant_token").asString();
        assertThat(b.get("application_number").asString()).isNotEqualTo(a.get("application_number").asString());
    }

    @Test
    @Order(2)
    void tokenIsRequiredAndBoundToItsApplication() throws Exception {
        assertThat(pub("GET", appA, "", null, null, 401).get("code").asString()).isEqualTo("applicant_token_required");
        assertThat(pub("GET", appA, "", null, "oat_forged", 401).get("code").asString()).isEqualTo("applicant_token_invalid");
        assertThat(pub("GET", appB, "", null, tokenA, 404).get("code").asString()).isEqualTo("not_found");
        pub("GET", appA, "/next-step", null, tokenB, 404);
        pub("POST", appA, "/steps/details", "{\"input\":{}}", tokenB, 404);
        pub("POST", appA, "/submit", null, tokenB, 404);
        HttpResponse<String> otherTenant = send("GET", "/v1/onboarding/public/applications/" + appA + "?tenant_id=" + OTHER_TENANT,
                null, tokenA, null);
        assertThat(otherTenant.statusCode()).isEqualTo(401);

        JsonNode view = pub("GET", appA, "", null, tokenA, 200);
        assertThat(view.get("status").asString()).isEqualTo("STARTED");
        for (String hidden : List.of("risk_score", "decision", "decision_reasons", "ip_address", "device_fingerprint", "created_by")) {
            assertThat(view.has(hidden)).as(hidden).isFalse();
        }
    }

    @Test
    @Order(3)
    void nextStepReturnsTypedFieldsAndContactStepsNeedAnOtp() throws Exception {
        JsonNode next = pub("GET", appA, "/next-step", null, tokenA, 200);
        assertThat(next.get("step_code").asString()).isEqualTo("phone");
        JsonNode field = next.get("fields").get(0);
        assertThat(field.get("name").asString()).isEqualTo("phone_number");
        assertThat(field.get("type").asString()).isEqualTo("PHONE");
        assertThat(field.get("label_key").asString()).isEqualTo("onb.phone");
        assertThat(field.get("required").asBoolean()).isTrue();
        assertThat(pub("POST", appA, "/steps/phone", "{\"input\":{\"phone_number\":\"+254700000001\"}}", tokenA, 409)
                .get("code").asString()).isEqualTo("otp_required");
    }

    @Test
    @Order(4)
    void otpVerifiesTheContactStep() throws Exception {
        HttpResponse<String> invalid = send("POST", path(appA, "/otp/send"), "{\"step_code\":\"phone\",\"destination\":\"0700\"}",
                tokenA, null);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(invalid.body()).get("errors").get(0).get("field").asString()).isEqualTo("destination");
        assertThat(pub("POST", appA, "/otp/send", "{\"step_code\":\"details\",\"destination\":\"+254700000001\"}", tokenA, 422)
                .get("code").asString()).isEqualTo("not_a_contact_step");

        JsonNode otp = pub("POST", appA, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000001\"}", tokenA, 200);
        assertThat(otp.get("status").asString()).isEqualTo("PENDING");
        assertThat(otp.get("destination_masked").asString()).isEqualTo("+2547******01");
        assertThat(otp.has("code_hash")).isFalse();
        assertThat(otp.hasNonNull("notification_id")).isTrue();
        String code = lastCode();
        assertThat(adminCount("select count(*) from onboarding.onboarding_otp where code_hash = ?", code))
                .as("the code itself is never stored").isZero();

        JsonNode wrong = pub("POST", appA, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + other(code) + "\"}", tokenA, 422);
        assertThat(wrong.get("code").asString()).isEqualTo("otp_invalid");
        assertThat(adminCount("select attempts from onboarding.onboarding_otp where id = ?::uuid", otp.get("id").asString()))
                .as("the failed attempt is committed").isEqualTo(1);
        JsonNode step = pub("POST", appA, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + code + "\"}", tokenA, 200);
        assertThat(step.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(step.get("input").get("phone_number").asString()).isEqualTo("+254700000001");
        assertThat(staff("GET", "/v1/onboarding/applications/" + appA, null, "maker", 200).get("phone").asString())
                .isEqualTo("+254700000001");
        assertThat(pub("POST", appA, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + code + "\"}", tokenA, 422)
                .get("code").asString()).as("a code is single-use").isEqualTo("otp_not_requested");
    }

    @Test
    @Order(5)
    void otpLockoutExpiryCooldownAndSendLimit() throws Exception {
        pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 200);
        String code = lastCode();
        assertThat(pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 429)
                .get("code").asString()).isEqualTo("otp_resend_too_soon");
        for (int i = 1; i <= 4; i++) {
            assertThat(pub("POST", appB, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + other(code) + "\"}", tokenB, 422)
                    .get("code").asString()).isEqualTo("otp_invalid");
        }
        assertThat(pub("POST", appB, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + other(code) + "\"}", tokenB, 422)
                .get("code").asString()).isEqualTo("otp_locked");
        assertThat(pub("POST", appB, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + code + "\"}", tokenB, 422)
                .get("code").asString()).as("locked even for the right code").isEqualTo("otp_locked");

        Thread.sleep(1100);
        pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 200);
        String second = lastCode();
        adminUpdate("update onboarding.onboarding_otp set expires_at = now() - interval '1 second' "
                + "where onboarding_application_id = ?::uuid and status = 'PENDING'", appB);
        assertThat(pub("POST", appB, "/otp/verify", "{\"step_code\":\"phone\",\"code\":\"" + second + "\"}", tokenB, 422)
                .get("code").asString()).isEqualTo("otp_expired");

        Thread.sleep(1100);
        pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 200);
        Thread.sleep(1100);
        pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 200);
        Thread.sleep(1100);
        assertThat(pub("POST", appB, "/otp/send", "{\"step_code\":\"phone\",\"destination\":\"+254700000002\"}", tokenB, 429)
                .get("code").asString()).isEqualTo("otp_send_limit");
    }

    @Test
    @Order(6)
    void stepFieldsAreValidatedWithFieldErrors() throws Exception {
        HttpResponse<String> r = send("POST", path(appA, "/steps/details"), """
                {"input":{"first_name":123,"last_name":"Otieno","date_of_birth":"12/04/1991","gender":"Q",
                          "annual_income":"-5","is_vip":true,"kyc_profile_id":"%s"}}""".formatted(UUID.randomUUID()), tokenA, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(422);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        JsonNode problem = json.readTree(r.body());
        assertThat(problem.get("code").asString()).isEqualTo("invalid_fields");
        Map<String, String> errors = new java.util.HashMap<>();
        problem.get("errors").forEach(e -> errors.put(e.get("field").asString(), e.get("code").asString()));
        assertThat(errors).containsEntry("input.first_name", "invalid_type").containsEntry("input.date_of_birth", "invalid_date")
                .containsEntry("input.gender", "invalid_value").containsEntry("input.annual_income", "below_min")
                .containsEntry("input.is_vip", "unknown_field").containsEntry("input.kyc_profile_id", "server_managed");
        JsonNode pattern = pub("POST", appA, "/steps/details", """
                {"input":{"first_name":"R2-D2","last_name":"Otieno","date_of_birth":"1991-04-12"}}""", tokenA, 422);
        assertThat(pattern.get("errors").get(0).get("code").asString()).isEqualTo("pattern_mismatch");
        JsonNode ok = pub("POST", appA, "/steps/details", """
                {"input":{"first_name":"Amina","last_name":"Otieno","date_of_birth":"1991-04-12","annual_income":"250000.50"}}""",
                tokenA, 200);
        assertThat(ok.get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @Order(7)
    void documentsAreUploadedThroughDocumentServiceAndBoundToTheApplication() throws Exception {
        assertThat(pub("POST", appA, "/documents", """
                {"step_code":"id_doc","document_type":"ID_CARD","file_name":"id.exe","content_type":"application/x-msdownload"}""",
                tokenA, 422).get("code").asString()).isEqualTo("invalid_fields");
        JsonNode upload = pub("POST", appA, "/documents", """
                {"step_code":"id_doc","document_type":"ID_CARD","file_name":"id-front.jpg","content_type":"image/jpeg"}""",
                tokenA, 200);
        String doc = upload.get("document_id").asString();
        assertThat(upload.get("url").asString()).startsWith("https://");
        assertThat(documentOwners.get(doc)).isEqualTo(appA);
        assertThat(pub("POST", appA, "/documents/" + doc + "/complete", "{\"size_bytes\":1234,\"checksum\":\"" + "a".repeat(64)
                + "\"}", tokenA, 200).get("status").asString()).isEqualTo("SCANNING");

        String foreign = pub("POST", appB, "/documents", """
                {"step_code":"id_doc","document_type":"PASSPORT","file_name":"p.pdf","content_type":"application/pdf"}""",
                tokenB, 200).get("document_id").asString();
        pub("POST", appA, "/documents/" + foreign + "/complete", "{\"size_bytes\":1,\"checksum\":\"" + "b".repeat(64) + "\"}",
                tokenA, 404);
        JsonNode stolen = pub("POST", appA, "/steps/id_doc", "{\"input\":{\"id_front\":\"" + foreign + "\"}}", tokenA, 422);
        assertThat(stolen.get("errors").get(0).get("code").asString()).isEqualTo("document_not_found");
        pub("POST", appA, "/steps/id_doc", "{\"input\":{\"id_front\":\"" + doc + "\"}}", tokenA, 200);
    }

    @Test
    @Order(8)
    void submitDecidesAndRevokesTheToken() throws Exception {
        pub("POST", appA, "/steps/terms", "{\"input\":{\"accepted\":true}}", tokenA, 200);
        JsonNode submitted = pub("POST", appA, "/submit", null, tokenA, 200);
        assertThat(submitted.get("status").asString()).isEqualTo("APPROVED");
        assertThat(submitted.has("decision")).isFalse();
        assertThat(submitted.has("risk_score")).isFalse();
        JsonNode staffView = staff("GET", "/v1/onboarding/applications/" + appA, null, "maker", 200);
        assertThat(staffView.get("legal_entity_id").asString()).as("journey default").isEqualTo(LEGAL_ENTITY);
        assertThat(staffView.get("customer_id").asString()).isEqualTo(CUSTOMER);
        assertThat(staffView.get("submitted_by").asString()).isEqualTo("applicant:" + appA);
        assertThat(pub("GET", appA, "", null, tokenA, 401).get("code").asString()).isEqualTo("applicant_token_invalid");
    }

    @Test
    @Order(9)
    void tokensExpireAndRotate() throws Exception {
        JsonNode c = start("SELF_SERVICE", "WEB", nextIp(), "device-c", 201);
        String app = c.get("onboarding_application_id").asString();
        String token = c.get("applicant_token").asString();
        JsonNode rotated = pub("POST", app, "/token/refresh", null, token, 200);
        String fresh = rotated.get("applicant_token").asString();
        assertThat(fresh).isNotEqualTo(token);
        assertThat(pub("GET", app, "", null, token, 401).get("code").asString()).as("rotated token").isEqualTo("applicant_token_invalid");
        pub("GET", app, "", null, fresh, 200);
        adminUpdate("update onboarding.onboarding_applicant_token set expires_at = now() - interval '1 second' "
                + "where onboarding_application_id = ?::uuid", app);
        assertThat(pub("GET", app, "", null, fresh, 401).get("code").asString()).isEqualTo("applicant_token_expired");
        assertThat(pub("POST", app, "/token/refresh", null, fresh, 401).get("code").asString()).isEqualTo("applicant_token_expired");
    }

    @Test
    @Order(10)
    void startsAreRateLimitedPerDeviceAndPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            start("SELF_SERVICE", "WEB", nextIp(), "device-limited", 201);
        }
        assertThat(start("SELF_SERVICE", "WEB", nextIp(), "device-limited", 429).get("code").asString()).isEqualTo("rate_limited");
        String ip = nextIp();
        for (int i = 0; i < 4; i++) {
            start("SELF_SERVICE", "WEB", ip, "device-ip-" + i, 201);
        }
        assertThat(start("SELF_SERVICE", "WEB", ip, "device-ip-x", 429).get("code").asString()).isEqualTo("rate_limited");
    }

    @Test
    @Order(11)
    void staffCreatedApplicationsGetServerNumbersToo() throws Exception {
        JsonNode app = staff("POST", "/v1/onboarding/applications", """
                {"application_number":"MY-OWN-NUMBER","onboarding_journey_id":"%s","channel":"WEB"}""".formatted(journey),
                "maker", 201);
        assertThat(NUMBER.matcher(app.get("application_number").asString()).matches()).isTrue();
        JsonNode next = staff("GET", "/v1/onboarding/applications/" + app.get("id").asString() + "/next-step", null, "maker", 200);
        assertThat(next.get("fields").get(0).get("type").asString()).isEqualTo("PHONE");
    }

    // ------------------------------------------------------------------ helpers

    private String nextIp() {
        return "203.0.113." + ipSeq.incrementAndGet();
    }

    private String lastCode() {
        Matcher m = CODE.matcher(sms.getLast());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String other(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private JsonNode start(String journeyCode, String channel, String ip, String device, int expected) throws Exception {
        HttpResponse<String> r = send("POST", "/v1/onboarding/public/applications/start?tenant_id=" + TENANT, """
                {"journey_code":"%s","channel":"%s","device_fingerprint":"%s","attestation_token":"captcha-ok"}"""
                .formatted(journeyCode, channel, device), null, ip);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private static String path(String app, String suffix) {
        return "/v1/onboarding/public/applications/" + app + suffix + "?tenant_id=" + TENANT;
    }

    private JsonNode pub(String method, String app, String suffix, String body, String token, int expected) throws Exception {
        HttpResponse<String> r = send(method, path(app, suffix), body, token, null);
        assertThat(r.statusCode()).as(method + " " + suffix + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    /** Public calls carry no tenant header and no user: the tenant comes from ?tenant_id only. */
    private HttpResponse<String> send(String method, String path, String body, String token, String ip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", ip == null ? "198.51.100.1" : ip);
        if (token != null) {
            b.header("X-Applicant-Token", token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode staff(String method, String path, String body, String user, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
        if (!"GET".equals(method)) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(method + " " + path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private static int adminCount(String sql, String arg) throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void adminUpdate(String sql, String arg) throws Exception {
        try (Connection c = DriverManager.getConnection(MeridianTestContainers.adminJdbcUrl(), "meridian", "meridian");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            assertThat(ps.executeUpdate()).isPositive();
        }
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unused")
    private static List<String> list(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asString()));
        return out;
    }
}
