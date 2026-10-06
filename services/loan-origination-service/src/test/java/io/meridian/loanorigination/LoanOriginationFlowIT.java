package io.meridian.loanorigination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.application.CallbackSecurity;
import io.meridian.loanorigination.infrastructure.SandboxESignatureProvider;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * loan-origination-service end to end over HTTP against PostgreSQL 18 (RLS enforced), with product-catalog-service
 * and loan-service stubbed behind a mocked {@link ServiceClient} ({@link FakeServices}): scorecards with
 * champion/challenger, applications, sandbox bureau (sync and async callback), scoring, affordability, automatic
 * and four-eyes manual decisions, priced offers, e-signature with signed provider callbacks and loan booking.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.loan-origination.callback-secret=it-secret"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanOriginationFlowIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000d001";
    static final String OTHER_TENANT = "0199b000-0000-7000-8000-00000000d002";
    static final String PRODUCT = FakeServices.PRODUCT;
    static final String VERSION = FakeServices.VERSION;
    /** Sandbox bureau profiles by last hex digit: c/b/d clean (800/780/820), f delinquent, a async. */
    static final String GOOD = "0199b000-0000-7000-8000-00000000c01c";
    static final String GOOD_B = "0199b000-0000-7000-8000-00000000c02b";
    static final String BAD = "0199b000-0000-7000-8000-00000000c03f";
    static final String LOW_INCOME = "0199b000-0000-7000-8000-00000000c04d";
    static final String ASYNC = "0199b000-0000-7000-8000-00000000c05c";
    static final String WITHDRAWER = "0199b000-0000-7000-8000-00000000c06b";
    static final String ACCOUNT = "0199b000-0000-7000-8000-00000000a001";

    static final String FEATURES = """
            [{"name":"bureau_score","missing_points":0,"reason_code":"R01_BUREAU_SCORE","description":"Credit bureau score",
              "bins":[{"max":600,"points":0},{"min":600,"max":700,"points":40},{"min":700,"points":80}]},
             {"name":"months_employed","missing_points":5,"reason_code":"R02_EMPLOYMENT",
              "bins":[{"max":12,"points":5},{"min":12,"max":36,"points":20},{"min":36,"points":30}]},
             {"name":"residential_status","missing_points":0,"reason_code":"R03_RESIDENCE",
              "bins":[{"values":["OWNER"],"points":25},{"values":["TENANT","WITH_PARENTS"],"points":10}]},
             {"name":"dti","missing_points":0,"reason_code":"R04_DTI",
              "bins":[{"max":"0.5","points":30},{"min":"0.5","max":"1.0","points":15},{"min":"1.0","points":0}]}]""";
    static final String CUTOFFS = """
            [{"min_score":700,"outcome":"APPROVE","risk_grade":"A1","rate_adjustment":"-0.01"},
             {"min_score":640,"outcome":"APPROVE","risk_grade":"B1","rate_adjustment":"0.00"},
             {"min_score":600,"outcome":"REFER","risk_grade":"C1","rate_adjustment":"0.03"},
             {"min_score":0,"outcome":"DECLINE","risk_grade":"E1"}]""";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CallbackSecurity callbacks;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeServices fake;
    String scorecard;
    String champion;
    String app;
    String offer;
    String envelope;
    String counterApp;

    @BeforeAll
    void createFakes() {
        fake = new FakeServices(json);
    }

    @BeforeEach
    void stub() {
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> fake.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    // ------------------------------------------------------------------ 1. scorecards

    @Test
    @Order(1)
    void scorecardVersionsWithModelRiskFourEyesAndChallenger() throws Exception {
        scorecard = post("/v1/scorecards", """
                {"code":"APP-RETAIL","name":"Retail application","scorecard_type":"APPLICATION","owner":"mrm"}""",
                "modeler", 201).get("id").asString();
        String base = "/v1/scorecards/" + scorecard + "/versions";
        assertThat(code(send("POST", base, "{\"scorecard_id\":\"" + scorecard + "\",\"version\":\"bad\",\"features\":" + FEATURES
                + ",\"cutoffs\":[{\"min_score\":1,\"outcome\":\"MAYBE\",\"risk_grade\":\"X\"}]}", key(), "modeler", TENANT), 422))
                .isEqualTo("invalid_scorecard");
        JsonNode v1 = post(base, "{\"scorecard_id\":\"" + scorecard + "\",\"version\":\"1.0\",\"base_points\":550,\"features\":" + FEATURES + ",\"cutoffs\":" + CUTOFFS + "}",
                "modeler", 201);
        champion = v1.get("id").asString();
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v1.get("features")).hasSize(4);
        // the author cannot approve their own model
        assertThat(code(send("POST", base + "/" + champion + "/activate", "{}", key(), "modeler", TENANT), 422))
                .isEqualTo("four_eyes_violation");
        JsonNode active = post(base + "/" + champion + "/activate", "{}", "mrm", 200);
        assertThat(active.get("status").asString()).isEqualTo("CHAMPION");
        assertThat(active.get("approved_by").asString()).isEqualTo("mrm");
        assertThat(code(send("PATCH", base + "/" + champion, "{\"base_points\":600}", null, "modeler", TENANT), 409))
                .isEqualTo("version_not_editable");

        // explainable test scoring without persistence
        JsonNode test = post(base + "/" + champion + "/test", """
                {"inputs":{"bureau_score":650,"months_employed":6,"residential_status":"TENANT","dti":"0.8"}}""", "analyst", 200);
        assertThat(test.get("score").asInt()).isEqualTo(550 + 40 + 5 + 10 + 15);
        assertThat(test.get("risk_grade").asString()).isEqualTo("C1");
        assertThat(test.get("reason_codes").get(0).get("code").asString()).isEqualTo("R01_BUREAU_SCORE");
        assertThat(test.get("contributions")).hasSize(4);

        // a challenger takes at most 50% of the traffic; retiring it restores champion-only routing
        String v2 = post(base, "{\"scorecard_id\":\"" + scorecard + "\",\"version\":\"2.0\",\"base_points\":560,\"features\":" + FEATURES + ",\"cutoffs\":" + CUTOFFS + "}",
                "modeler", 201).get("id").asString();
        assertThat(code(send("POST", base + "/" + v2 + "/set-challenger", "{\"challenger_traffic_pct\":\"0.6\"}", key(), "mrm", TENANT), 422))
                .isEqualTo("invalid_traffic_share");
        JsonNode challenger = post(base + "/" + v2 + "/set-challenger", "{\"challenger_traffic_pct\":\"0.2\"}", "mrm", 200);
        assertThat(challenger.get("status").asString()).isEqualTo("CHALLENGER");
        assertThat(new BigDecimal(challenger.get("challenger_traffic_pct").asString())).isEqualByComparingTo("0.2");
        assertThat(post(base + "/" + v2 + "/retire", null, "mrm", 200).get("status").asString()).isEqualTo("RETIRED");
        assertThat(code(send("POST", base + "/" + v2 + "/retire", null, key(), "mrm", TENANT), 409)).isEqualTo("invalid_transition");
        assertThat(events("lending.scorecard_version.activated", champion)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 2. intake

    @Test
    @Order(2)
    void applicationIntakeWithProductChecksAndIdempotency() throws Exception {
        String key = key();
        HttpResponse<String> created = send("POST", "/v1/loan-applications", appBody(GOOD, "10000", 24), key, "agent", TENANT);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode a = json.readTree(created.body());
        app = a.get("id").asString();
        assertThat(a.get("status").asString()).isEqualTo("DRAFT");
        assertThat(a.get("application_number").asString()).matches("LA-\\d{4}-[0-9A-Z]{8}");
        assertThat(a.get("product_version_id").asString()).isEqualTo(VERSION);
        assertThat(a.get("legal_entity_id").asString()).isEqualTo(FakeServices.LEGAL_ENTITY);
        assertThat(a.hasNonNull("consent_at")).isTrue();
        assertThat(a.has("ip_address")).isFalse();
        // idempotent replay returns the same application
        HttpResponse<String> replay = send("POST", "/v1/loan-applications", appBody(GOOD, "10000", 24), key, "agent", TENANT);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(app);

        assertThat(code(send("POST", "/v1/loan-applications", appBody(GOOD, "100", 24), key(), "agent", TENANT), 422))
                .isEqualTo("amount_out_of_range");
        assertThat(code(send("POST", "/v1/loan-applications", appBody(GOOD, "10000", 120), key(), "agent", TENANT), 422))
                .isEqualTo("term_out_of_range");
        assertThat(code(send("POST", "/v1/loan-applications", appBody(GOOD, "10000", 24).replace("\"EUR\"", "\"USD\""), key(),
                "agent", TENANT), 422)).isEqualTo("currency_mismatch");
        assertThat(code(send("POST", "/v1/loan-applications", appBody(GOOD, "10000", 24).replace(PRODUCT, FakeServices.DRAFT_PRODUCT),
                key(), "agent", TENANT), 422)).isEqualTo("product_version_not_available");
        assertThat(send("POST", "/v1/loan-applications", "{\"customer_id\":\"" + GOOD + "\"}", key(), "agent", TENANT).statusCode())
                .isEqualTo(400);

        // drafts can be edited, but not their product or parties
        JsonNode patched = json.readTree(send("PATCH", "/v1/loan-applications/" + app,
                "{\"purpose_detail\":\"Kitchen renovation\"}", null, "agent", TENANT).body());
        assertThat(patched.get("purpose_detail").asString()).isEqualTo("Kitchen renovation");
        assertThat(code(send("PATCH", "/v1/loan-applications/" + app, "{\"product_id\":\"" + FakeServices.DRAFT_PRODUCT + "\"}",
                null, "agent", TENANT), 422)).isEqualTo("field_not_updatable");
        assertThat(events("lending.loan_application.created", app)).isEqualTo(1);
    }

    @Test
    @Order(3)
    void applicantsFinancesDocumentsAndSubmission() throws Exception {
        addPrimary(app, GOOD, "4000", "OWNER");
        // only one PRIMARY applicant
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/applicants",
                "{\"loan_application_id\":\"" + app + "\",\"customer_id\":\"" + GOOD + "\",\"role\":\"PRIMARY\"}", key(), "agent", TENANT), 409)).isEqualTo("primary_applicant_exists");
        String guarantor = post("/v1/loan-applications/" + app + "/applicants", """
                {"loan_application_id":"%s","customer_id":"0199b000-0000-7000-8000-00000000c0aa","role":"GUARANTOR",
                 "share_of_liability":"0.5"}""".formatted(app),
                "agent", 201).get("id").asString();
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/applicants",
                "{\"loan_application_id\":\"" + app + "\",\"customer_id\":\"" + GOOD + "\",\"role\":\"CO_APPLICANT\",\"share_of_liability\":\"1.5\"}", key(), "agent", TENANT), 422))
                .isEqualTo("invalid_share_of_liability");
        JsonNode items = get("/v1/loan-applications/" + app + "/financial-items");
        assertThat(items.get("data")).hasSize(2);
        // the financial items of another application are not visible under this one
        assertThat(code(send("GET", "/v1/loan-applications/" + UUID.randomUUID() + "/financial-items/"
                + items.get("data").get(0).get("id").asString(), null, null, "agent", TENANT), 404)).isEqualTo("not_found");
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/financial-items", """
                {"loan_applicant_id":"%s","item_type":"SALARY","amount":"10","currency":"EUR","frequency":"MONTHLY"}"""
                .formatted(UUID.randomUUID()), key(), "agent", TENANT), 422)).isEqualTo("applicant_not_found");
        send("DELETE", "/v1/loan-applications/" + app + "/applicants/" + guarantor, null, null, "agent", TENANT);

        // documents: uploaded by one user, verified by another
        String doc = post("/v1/loan-applications/" + app + "/documents", """
                {"loan_application_id":"%s","document_type":"ID_PROOF","document_id":"%s"}""".formatted(app, UUID.randomUUID()), "agent", 201).get("id").asString();
        assertThat(get("/v1/loan-applications/" + app + "/documents/" + doc).get("status").asString()).isEqualTo("UPLOADED");
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/documents/" + doc + "/verify", null, key(), "agent", TENANT), 422))
                .isEqualTo("four_eyes_violation");
        JsonNode verified = post("/v1/loan-applications/" + app + "/documents/" + doc + "/verify", null, "checker", 200);
        assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(verified.get("verified_by").asString()).isEqualTo("checker");
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/documents/" + doc + "/reject",
                "{\"rejection_reason\":\"blurred\"}", key(), "checker", TENANT), 409)).isEqualTo("invalid_transition");
        String income = post("/v1/loan-applications/" + app + "/documents", "{\"loan_application_id\":\"" + app + "\",\"document_type\":\"INCOME_PROOF\",\"is_mandatory\":false}",
                "agent", 201).get("id").asString();
        assertThat(post("/v1/loan-applications/" + app + "/documents/" + income + "/reject", "{\"rejection_reason\":\"missing\"}",
                "checker", 200).get("status").asString()).isEqualTo("REJECTED");

        JsonNode submitted = post("/v1/loan-applications/" + app + "/submit", null, "agent", 200);
        assertThat(submitted.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.hasNonNull("submitted_at")).isTrue();
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/submit", null, key(), "agent", TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(code(send("PATCH", "/v1/loan-applications/" + app, "{\"purpose_detail\":\"x\"}", null, "agent", TENANT), 409))
                .isEqualTo("application_not_editable");
        assertThat(events("lending.loan_application.submitted", app)).isEqualTo(1);
        assertThat(events("lending.loan_application.status_changed", app)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. bureau, score, decision, offer

    @Test
    @Order(4)
    void bureauPullIsReusedWhileValid() throws Exception {
        String applicant = primaryId(app);
        JsonNode report = post("/v1/loan-applications/" + app + "/bureau-pull",
                "{\"loan_applicant_id\":\"" + applicant + "\"}", "underwriter", 200);
        assertThat(report.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(report.get("provider").asString()).isEqualTo("SANDBOX");
        assertThat(report.get("bureau_score").asInt()).isEqualTo(800);
        assertThat(report.get("tradelines")).isNotEmpty();
        assertThat(report.hasNonNull("valid_until")).isTrue();
        JsonNode again = post("/v1/loan-applications/" + app + "/bureau-pull",
                "{\"loan_applicant_id\":\"" + applicant + "\"}", "underwriter", 200);
        assertThat(again.get("id").asString()).isEqualTo(report.get("id").asString());
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/bureau-pull",
                "{\"loan_applicant_id\":\"" + applicant + "\",\"provider\":\"NOPE\"}", key(), "underwriter", TENANT), 422))
                .isEqualTo("unsupported_bureau_provider");
        assertThat(events("lending.bureau_report.received", report.get("id").asString())).isEqualTo(1);
    }

    @Test
    @Order(5)
    void automaticDecisionApprovesAndIssuesAPricedOffer() throws Exception {
        JsonNode decision = post("/v1/loan-applications/" + app + "/auto-decide", null, "system", 200);
        assertThat(decision.get("outcome").asString()).isEqualTo("APPROVED");
        assertThat(decision.get("method").asString()).isEqualTo("AUTOMATIC");
        assertThat(decision.get("risk_grade").asString()).isEqualTo("A1");
        assertThat(new BigDecimal(decision.get("approved_rate").asString())).isEqualByComparingTo("0.09");
        assertThat(decision.get("policy_rules_fired").size()).isGreaterThanOrEqualTo(5);
        assertThat(decision.get("reason_codes")).isEmpty();

        JsonNode score = get("/v1/loan-applications/" + app + "/scores").get("data").get(0);
        assertThat(score.get("score").asInt()).isEqualTo(550 + 80 + 30 + 25 + 30);
        assertThat(score.get("routing").asString()).isEqualTo("CHAMPION");
        assertThat(score.get("scorecard_version_id").asString()).isEqualTo(champion);
        assertThat(score.get("inputs").get("bureau_score").asInt()).isEqualTo(800);
        assertThat(score.get("inputs_hash").asString()).hasSize(64);
        JsonNode aff = get("/v1/affordability-assessments?loan_application_id=" + app).get("data").get(0);
        assertThat(aff.get("result").asString()).isEqualTo("PASS");
        assertThat(new BigDecimal(aff.get("verified_monthly_income").asString())).isEqualByComparingTo("4000");

        JsonNode application = get("/v1/loan-applications/" + app);
        assertThat(application.get("status").asString()).isEqualTo("APPROVED");
        assertThat(application.get("risk_grade").asString()).isEqualTo("A1");
        JsonNode offers = get("/v1/loan-applications/" + app + "/offers").get("data");
        assertThat(offers).hasSize(1);
        JsonNode o = offers.get(0);
        offer = o.get("id").asString();
        assertThat(o.get("status").asString()).isEqualTo("ISSUED");
        assertThat(new BigDecimal(o.get("interest_rate").asString())).isEqualByComparingTo("0.09");
        assertThat(new BigDecimal(o.get("upfront_fees").asString())).isEqualByComparingTo("150.00"); // 50 + 1%
        assertThat(new BigDecimal(o.get("apr").asString())).isGreaterThan(new BigDecimal("0.09"));
        assertThat(o.get("schedule_preview")).hasSize(24);
        assertThat(new BigDecimal(o.get("instalment_amount").asString())).isEqualByComparingTo("456.85");
        BigDecimal principal = BigDecimal.ZERO;
        for (JsonNode i : o.get("schedule_preview")) {
            principal = principal.add(new BigDecimal(i.get("principal").asString()));
        }
        assertThat(principal).isEqualByComparingTo("10000");
        JsonNode preview = get("/v1/loan-offers/" + offer + "/schedule-preview");
        assertThat(preview.get("apr").asString()).isEqualTo(o.get("apr").asString());
        assertThat(preview.get("installments")).hasSize(24);
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/auto-decide", null, key(), "system", TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(events("lending.credit_decision.made", decision.get("id").asString())).isEqualTo(1);
        assertThat(events("lending.loan_offer.issued", offer)).isEqualTo(1);
    }

    @Test
    @Order(6)
    void acceptanceSignatureCallbackAndBooking() throws Exception {
        JsonNode env = post("/v1/loan-offers/" + offer + "/accept", "{}", "customer", 200);
        envelope = env.get("id").asString();
        assertThat(env.get("status").asString()).isEqualTo("SENT");
        assertThat(env.get("provider").asString()).isEqualTo("SANDBOX");
        assertThat(env.get("send_count").asInt()).isEqualTo(1);
        String envelopeId = env.get("provider_envelope_id").asString();
        assertThat(envelopeId).isEqualTo(SandboxESignatureProvider.envelopeId(UUID.fromString(envelope)));
        assertThat(env.get("signers").get(0).get("role").asString()).isEqualTo("PRIMARY");
        assertThat(get("/v1/loan-applications/" + app).get("status").asString()).isEqualTo("OFFER_ACCEPTED");
        assertThat(code(send("POST", "/v1/loan-offers/" + offer + "/accept", "{}", key(), "customer", TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/e-signature-requests/" + envelope + "/resend", null, "ops", 200).get("send_count").asInt()).isEqualTo(2);

        // public callback: signature checked over the raw body, tenant resolved from the envelope
        String viewed = "{\"provider_envelope_id\":\"" + envelopeId + "\",\"provider_status\":\"VIEWED\"}";
        assertThat(callback("e-signature-requests", "SANDBOX", viewed, "sha256=deadbeef").statusCode()).isEqualTo(401);
        assertThat(json.readTree(callback("e-signature-requests", "SANDBOX", viewed, sign(viewed)).body()).get("status").asString())
                .isEqualTo("VIEWED");
        String signed = "{\"provider_envelope_id\":\"" + envelopeId + "\",\"provider_status\":\"SIGNED\",\"signed_document_id\":\""
                + UUID.randomUUID() + "\"}";
        HttpResponse<String> r = callback("e-signature-requests", "SANDBOX", signed, sign(signed));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode result = json.readTree(r.body());
        assertThat(result.get("status").asString()).isEqualTo("SIGNED");
        assertThat(result.get("application_status").asString()).isEqualTo("BOOKED");

        JsonNode booked = get("/v1/loan-applications/" + app);
        assertThat(booked.get("status").asString()).isEqualTo("BOOKED");
        assertThat(booked.get("loan_id").asString()).isEqualTo(result.get("loan_id").asString());
        List<FakeServices.Call> bookings = fake.bookings();
        assertThat(bookings).hasSize(1);
        FakeServices.Call call = bookings.getFirst();
        assertThat(call.key()).isEqualTo("loan-application:" + app);
        JsonNode body = call.body();
        assertThat(body.get("customer_id").asString()).isEqualTo(GOOD);
        assertThat(body.get("loan_offer_id").asString()).isEqualTo(offer);
        assertThat(body.get("product_version_id").asString()).isEqualTo(VERSION);
        assertThat(body.get("legal_entity_id").asString()).isEqualTo(FakeServices.LEGAL_ENTITY);
        assertThat(new BigDecimal(body.get("principal_amount").asString())).isEqualByComparingTo("10000");
        assertThat(new BigDecimal(body.get("interest_rate").asString())).isEqualByComparingTo("0.09");
        assertThat(body.get("interest_rate_type").asString()).isEqualTo("FIXED");
        assertThat(body.get("schedule_type").asString()).isEqualTo("ANNUITY");
        assertThat(body.get("repayment_frequency").asString()).isEqualTo("MONTHLY");
        assertThat(body.get("term_months").asInt()).isEqualTo(24);
        assertThat(new BigDecimal(body.get("origination_fee").asString())).isEqualByComparingTo("150");
        assertThat(body.get("disbursement_account_id").asString()).isEqualTo(ACCOUNT);
        assertThat(body.get("risk_grade").asString()).isEqualTo("A1");

        // the provider redelivers: acknowledged, nothing booked twice
        JsonNode duplicate = json.readTree(callback("e-signature-requests", "SANDBOX", signed, sign(signed)).body());
        assertThat(duplicate.get("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(fake.bookings()).hasSize(1);
        String unknown = "{\"provider_envelope_id\":\"sbx-env-unknown\",\"provider_status\":\"SIGNED\"}";
        assertThat(callback("e-signature-requests", "SANDBOX", unknown, sign(unknown)).statusCode()).isEqualTo(404);
        assertThat(get("/v1/loan-offers/" + offer).get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(events("lending.e_signature_request.signed", envelope)).isEqualTo(1);
        assertThat(events("lending.loan_application.booked", app)).isEqualTo(1);
    }

    @Test
    @Order(7)
    void failedBookingIsKeptAndRetried() throws Exception {
        String a = approvedApplication(GOOD_B, "5000", 12);
        String o = get("/v1/loan-applications/" + a + "/offers").get("data").get(0).get("id").asString();
        String envId = post("/v1/loan-offers/" + o + "/accept", "{\"provider\":\"SANDBOX\"}", "customer", 200)
                .get("provider_envelope_id").asString();
        fake.failNextBookings.set(1);
        String signed = "{\"provider_envelope_id\":\"" + envId + "\",\"provider_status\":\"SIGNED\"}";
        JsonNode result = json.readTree(callback("e-signature-requests", "SANDBOX", signed, sign(signed)).body());
        assertThat(result.get("application_status").asString()).isEqualTo("SIGNED");
        assertThat(result.get("booking_error").asString()).contains("upstream_error");
        assertThat(get("/v1/loan-applications/" + a).get("booking_error").asString()).contains("loan-service unavailable");
        JsonNode booked = post("/v1/loan-applications/" + a + "/book", null, "ops", 200);
        assertThat(booked.get("status").asString()).isEqualTo("BOOKED");
        assertThat(booked.hasNonNull("loan_id")).isTrue();
        assertThat(booked.hasNonNull("booking_error")).isFalse();
        // booking again is a no-op
        assertThat(post("/v1/loan-applications/" + a + "/book", null, "ops", 200).get("loan_id").asString())
                .isEqualTo(booked.get("loan_id").asString());
    }

    // ------------------------------------------------------------------ 4. decline, override, counter-offer

    @Test
    @Order(8)
    void declineWithAdverseReasonsAndFourEyesManualOverride() throws Exception {
        String a = submittedApplication(BAD, "8000", 24, "3500", "OWNER");
        JsonNode decision = post("/v1/loan-applications/" + a + "/auto-decide", null, "system", 200);
        assertThat(decision.get("outcome").asString()).isEqualTo("DECLINED");
        List<String> codes = decision.get("decline_reasons").valueStream().map(n -> n.get("code").asString()).toList();
        assertThat(codes).contains("SERIOUS_DELINQUENCY", "MULTIPLE_DELINQUENT_ACCOUNTS");
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("DECLINED");
        assertThat(get("/v1/loan-applications/" + a + "/offers").get("data")).isEmpty();

        // override: an underwriter proposes, a credit officer approves
        assertThat(code(send("POST", "/v1/loan-applications/" + a + "/decide", "{\"outcome\":\"DECLINED\"}", key(),
                "underwriter", TENANT), 422)).isEqualTo("reason_codes_required");
        JsonNode request = post("/v1/loan-applications/" + a + "/decide", """
                {"outcome":"APPROVED","approved_amount":"6000","approved_term_months":24,"approved_rate":"0.14",
                 "reason_codes":[{"code":"SECURED_BY_SALARY_ASSIGNMENT"}],"notes":"Arrears settled last month"}""", "underwriter", 200);
        assertThat(request.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(request.get("is_override").asBoolean()).isTrue();
        assertThat(request.get("requested_by").asString()).isEqualTo("underwriter");
        String requestId = request.get("id").asString();
        assertThat(code(send("POST", "/v1/loan-applications/" + a + "/decide", "{\"outcome\":\"REFER\"}", key(), "underwriter", TENANT), 409))
                .isEqualTo("decision_request_pending");
        assertThat(code(send("POST", "/v1/credit-decision-requests/" + requestId + "/approve", "{}", key(), "underwriter", TENANT), 422))
                .isEqualTo("four_eyes_violation");
        JsonNode manual = post("/v1/credit-decision-requests/" + requestId + "/approve", "{\"comment\":\"ok\"}", "credit-head", 200);
        assertThat(manual.get("method").asString()).isEqualTo("MANUAL");
        assertThat(manual.get("outcome").asString()).isEqualTo("APPROVED");
        assertThat(manual.get("decided_by").asString()).isEqualTo("underwriter");
        assertThat(manual.get("approved_by").asString()).isEqualTo("credit-head");
        assertThat(get("/v1/credit-decision-requests/" + requestId).get("status").asString()).isEqualTo("APPROVED");
        assertThat(code(send("POST", "/v1/credit-decision-requests/" + requestId + "/reject", "{\"reason\":\"x\"}", key(),
                "auditor", TENANT), 409)).isEqualTo("invalid_transition");
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("APPROVED");
        JsonNode o = get("/v1/loan-applications/" + a + "/offers").get("data").get(0);
        assertThat(new BigDecimal(o.get("offer_amount").asString())).isEqualByComparingTo("6000");
        assertThat(new BigDecimal(o.get("interest_rate").asString())).isEqualByComparingTo("0.14");
        assertThat(get("/v1/loan-applications/" + a + "/decisions").get("data")).hasSize(2);
    }

    @Test
    @Order(9)
    void unaffordableRequestBecomesACounterOfferAndManualOfferRules() throws Exception {
        counterApp = submittedApplication(LOW_INCOME, "30000", 24, "1500", "OWNER");
        JsonNode decision = post("/v1/loan-applications/" + counterApp + "/auto-decide", null, "system", 200);
        assertThat(decision.get("outcome").asString()).isEqualTo("COUNTER_OFFER");
        BigDecimal counter = new BigDecimal(decision.get("approved_amount").asString());
        assertThat(counter).isLessThan(new BigDecimal("30000")).isGreaterThanOrEqualTo(new BigDecimal("1000"));
        assertThat(counter.remainder(new BigDecimal("100"))).isEqualByComparingTo("0");
        assertThat(get("/v1/loan-applications/" + counterApp).get("status").asString()).isEqualTo("COUNTER_OFFERED");
        JsonNode first = get("/v1/loan-applications/" + counterApp + "/offers").get("data").get(0);
        assertThat(new BigDecimal(first.get("offer_amount").asString())).isEqualByComparingTo(counter);

        String manual = """
                {"loan_application_id":"%s","credit_decision_id":"%s","offer_amount":"%s","currency":"EUR","term_months":36,
                 "repayment_frequency":"MONTHLY","schedule_type":"EQUAL_PRINCIPAL","interest_rate":"0.095","upfront_fees":"0"}""";
        assertThat(code(send("POST", "/v1/loan-offers", manual.formatted(counterApp, decision.get("id").asString(),
                counter.add(BigDecimal.ONE).toPlainString()), key(), "underwriter", TENANT), 422)).isEqualTo("amount_exceeds_approval");
        JsonNode second = post("/v1/loan-offers", manual.formatted(counterApp, decision.get("id").asString(),
                counter.toPlainString()), "underwriter", 201);
        assertThat(second.get("schedule_type").asString()).isEqualTo("EQUAL_PRINCIPAL");
        assertThat(second.get("schedule_preview")).hasSize(36);
        assertThat(get("/v1/loan-offers/" + first.get("id").asString()).get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(post("/v1/loan-offers/" + second.get("id").asString() + "/decline", "{\"reason\":\"too expensive\"}", "customer", 200)
                .get("status").asString()).isEqualTo("DECLINED");
        assertThat(code(send("POST", "/v1/loan-offers/" + second.get("id").asString() + "/accept", "{}", key(), "customer", TENANT), 409))
                .isEqualTo("invalid_transition");
    }

    // ------------------------------------------------------------------ 5. async bureau, assignment, withdrawal

    @Test
    @Order(10)
    void asynchronousBureauCompletesThroughTheSignedCallback() throws Exception {
        String a = submittedApplication(ASYNC, "4000", 12, "3000", "TENANT");
        JsonNode assigned = post("/v1/loan-applications/" + a + "/assign", "{\"assigned_to\":\"uw-7\"}", "lead", 200);
        assertThat(assigned.get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(assigned.get("assigned_to").asString()).isEqualTo("uw-7");
        JsonNode report = post("/v1/loan-applications/" + a + "/bureau-pull",
                "{\"loan_applicant_id\":\"" + primaryId(a) + "\",\"provider\":\"SANDBOX_ASYNC\",\"pull_type\":\"SOFT\"}", "uw-7", 200);
        assertThat(report.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("BUREAU_PENDING");
        assertThat(code(send("POST", "/v1/loan-applications/" + a + "/auto-decide", null, key(), "system", TENANT), 409))
                .isEqualTo("bureau_pending");
        String ref = report.get("bureau_reference").asString();
        String body = """
                {"bureau_reference":"%s","status":"RECEIVED","bureau_score":745,"score_band":"GOOD","total_debt":"2000",
                 "monthly_debt_service":"120","open_accounts":2,"delinquent_accounts":0,"worst_dpd_24m":0,"inquiries_6m":1,
                 "tradelines":[{"lender":"NORTHBANK","balance":"2000","monthly_payment":"120"}]}""".formatted(ref);
        assertThat(callback("bureau-reports", "SANDBOX_ASYNC", body, "sha256=00").statusCode()).isEqualTo(401);
        JsonNode applied = json.readTree(callback("bureau-reports", "SANDBOX_ASYNC", body, sign(body)).body());
        assertThat(applied.get("outcome").asString()).isEqualTo("APPLIED");
        JsonNode completed = get("/v1/bureau-reports/" + report.get("id").asString());
        assertThat(completed.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(completed.get("bureau_score").asInt()).isEqualTo(745);
        assertThat(completed.get("tradelines")).hasSize(1);
        assertThat(get("/v1/loan-applications/" + a).get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(json.readTree(callback("bureau-reports", "SANDBOX_ASYNC", body, sign(body)).body()).get("outcome").asString())
                .isEqualTo("DUPLICATE");
        JsonNode decision = post("/v1/loan-applications/" + a + "/auto-decide", null, "system", 200);
        assertThat(decision.get("outcome").asString()).isIn("APPROVED", "REFER");
    }

    @Test
    @Order(11)
    void withdrawalClosesOpenOffersAndIsFinal() throws Exception {
        String a = approvedApplication(WITHDRAWER, "3000", 12);
        String o = get("/v1/loan-applications/" + a + "/offers").get("data").get(0).get("id").asString();
        JsonNode env = post("/v1/loan-offers/" + o + "/accept", "{}", "customer", 200);
        JsonNode w = post("/v1/loan-applications/" + a + "/withdraw", "{\"reason\":\"Found cheaper credit\"}", "customer", 200);
        assertThat(w.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(w.get("withdrawal_reason").asString()).isEqualTo("Found cheaper credit");
        assertThat(get("/v1/loan-offers/" + o).get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(get("/v1/e-signature-requests/" + env.get("id").asString()).get("status").asString()).isEqualTo("VOIDED");
        assertThat(code(send("POST", "/v1/loan-applications/" + a + "/withdraw", "{}", key(), "customer", TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(code(send("POST", "/v1/loan-applications/" + app + "/withdraw", "{}", key(), "customer", TENANT), 409))
                .isEqualTo("invalid_transition");
        assertThat(events("lending.loan_application.withdrawn", a)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 6. quote, expiry, isolation

    @Test
    @Order(12)
    void quickQuoteIsIndicativeAndIdempotent() throws Exception {
        String body = """
                {"product_id":"%s","amount":"12000","currency":"EUR","term_months":36,"risk_grade":"A1"}""".formatted(PRODUCT);
        String key = key();
        HttpResponse<String> r = send("POST", "/v1/loan-applications/quick-quote", body, key, "web", TENANT);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode q = json.readTree(r.body());
        assertThat(q.get("indicative").asBoolean()).isTrue();
        assertThat(new BigDecimal(q.get("interest_rate").asString())).isEqualByComparingTo("0.09");
        assertThat(new BigDecimal(q.get("upfront_fees").asString())).isEqualByComparingTo("170.00");
        assertThat(new BigDecimal(q.get("apr").asString())).isGreaterThan(new BigDecimal("0.09"));
        assertThat(q.get("installment_count").asInt()).isEqualTo(36);
        assertThat(send("POST", "/v1/loan-applications/quick-quote", body, key, "web", TENANT).headers()
                .firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(send("POST", "/v1/loan-applications/quick-quote", body.replace("12000", "99999"), key(), "web", TENANT), 422))
                .isEqualTo("amount_out_of_range");
    }

    @Test
    @Order(13)
    void endOfDayExpiresLapsedOffersAndApplications() throws Exception {
        String stale = post("/v1/loan-applications", appBody("0199b000-0000-7000-8000-00000000c07c", "2000", 12), "agent", 201)
                .get("id").asString();
        asTenant(() -> jdbc.sql("update loan_application set expires_at = now() - interval '1 hour' where id = ?::uuid")
                .param(stale).update());
        asTenant(() -> jdbc.sql("""
                update loan_offer set expires_at = now() - interval '1 minute'
                where loan_application_id = ?::uuid and status = 'ISSUED'""").param(counterApp).update());
        // the counter-offer application's offers are closed; open one more and let it lapse
        String date = LocalDate.now().toString();
        JsonNode run = post("/v1/loan-applications/eod/expire?business_date=" + date, null, "business-day", 200);
        assertThat(run.get("applications_expired").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/v1/loan-applications/" + stale).get("status").asString()).isEqualTo("EXPIRED");
        JsonNode again = post("/v1/loan-applications/eod/expire?business_date=" + date, null, "business-day", 200);
        assertThat(again.get("applications_expired").asInt()).isZero();
        assertThat(again.get("offers_expired").asInt()).isZero();
        assertThat(code(send("POST", "/v1/loan-applications/" + stale + "/submit", null, key(), "agent", TENANT), 409))
                .isEqualTo("invalid_transition");
    }

    @Test
    @Order(14)
    void otherTenantSeesNothing() throws Exception {
        assertThat(send("GET", "/v1/loan-applications/" + app, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/v1/loan-applications/" + app + "/withdraw", "{}", key(), "ops", OTHER_TENANT).statusCode())
                .isEqualTo(404);
        assertThat(json.readTree(send("GET", "/v1/loan-applications", null, null, "ops", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(json.readTree(send("GET", "/v1/scorecards", null, null, "ops", OTHER_TENANT).body()).get("data")).isEmpty();
        assertThat(send("GET", "/v1/loan-offers/" + offer, null, null, "ops", OTHER_TENANT).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private String appBody(String customer, String amount, int term) {
        return """
                {"customer_id":"%s","product_id":"%s","channel":"WEB","loan_type":"INSTALMENT","purpose":"PERSONAL",
                 "requested_amount":"%s","currency":"EUR","requested_term_months":%d,"requested_frequency":"MONTHLY",
                 "consent_bureau":true,"disbursement_account_id":"%s","repayment_account_id":"%s",
                 "ip_address":"10.0.0.1","metadata":{"campaign":"autumn"}}""".formatted(customer, PRODUCT, amount, term, ACCOUNT, ACCOUNT);
    }

    private void addPrimary(String appId, String customer, String salary, String residence) throws Exception {
        String applicant = post("/v1/loan-applications/" + appId + "/applicants", """
                {"loan_application_id":"%s","customer_id":"%s","role":"PRIMARY","date_of_birth":"1985-04-12","employment_status":"EMPLOYED",
                 "employer_name":"Acme","employment_start_date":"2018-01-01","monthly_net_income":"%s","dependants":1,
                 "residential_status":"%s","share_of_liability":"1"}""".formatted(appId, customer, salary, residence), "agent", 201)
                .get("id").asString();
        post("/v1/loan-applications/" + appId + "/financial-items", """
                {"loan_applicant_id":"%s","item_type":"SALARY","amount":"%s","currency":"EUR","frequency":"MONTHLY",
                 "is_verified":true,"verification_source":"PAYROLL"}""".formatted(applicant, salary), "agent", 201);
        post("/v1/loan-applications/" + appId + "/financial-items", """
                {"loan_applicant_id":"%s","item_type":"LIVING_COSTS","amount":"600","currency":"EUR","frequency":"MONTHLY"}"""
                .formatted(applicant), "agent", 201);
    }

    private String submittedApplication(String customer, String amount, int term, String salary, String residence)
            throws Exception {
        String a = post("/v1/loan-applications", appBody(customer, amount, term), "agent", 201).get("id").asString();
        addPrimary(a, customer, salary, residence);
        post("/v1/loan-applications/" + a + "/submit", null, "agent", 200);
        return a;
    }

    private String approvedApplication(String customer, String amount, int term) throws Exception {
        String a = submittedApplication(customer, amount, term, "4500", "OWNER");
        assertThat(post("/v1/loan-applications/" + a + "/auto-decide", null, "system", 200).get("outcome").asString())
                .isEqualTo("APPROVED");
        return a;
    }

    private String primaryId(String appId) throws Exception {
        return get("/v1/loan-applications/" + appId + "/applicants?role=PRIMARY").get("data").get(0).get("id").asString();
    }

    private String sign(String body) {
        return callbacks.sign(body.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> callback(String resource, String provider, String body, String signature) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/" + resource + "/callbacks/"
                        + provider + "/events"))
                .header("Content-Type", "application/json").header(CallbackSecurity.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private <T> T asTenant(Callable<T> body) throws Exception {
        return TenantContext.callAs(UUID.fromString(TENANT), "test", body);
    }

    private int events(String type, String aggregateId) throws Exception {
        return asTenant(() -> jdbc.sql("""
                select count(*) from outbox where tenant_id = ?::uuid and event_type = ?
                  and (?::uuid is null or aggregate_id = ?::uuid)""")
                .params(TENANT, type, aggregateId, aggregateId).query(Integer.class).single());
    }

    private String code(HttpResponse<String> r, int expected) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body()).path("code").asString();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, key(), user, TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "ops", TENANT);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String tenant)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if ("PATCH".equals(method)) {
            String current = send("GET", path, null, null, user, tenant).headers().firstValue("ETag").orElse("\"1\"");
            b.header("If-Match", current);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
