package io.meridian.liquidity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, calculating an LCR / NSFR snapshot and
 * creating or reworking a stress scenario open approval requests in workflow-service, and decisions arriving from
 * workflow-service are applied with the approver as principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class LiquidityWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000011c7";
    static final String ENTITY = "0199a000-0000-7000-8000-0000000011e7";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            requests.add((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void lcrCalculationOpensRequestAndApprovalFromWorkflowApproves() throws Exception {
        String id = post("/v1/treasury/liquidity/lcr/calculate", lcr("2026-11-10"), "risk-maker", 200).get("id").asString();

        Map<String, Object> req = last("lcr_calculation", id);
        assertThat(req).containsEntry("action", "approve").containsEntry("currency", "EUR");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("166.67");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isEqualTo(1);
        assertThat(payload.get("lcr_ratio")).isNotNull();

        // the calculator approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("lcr_calculation", id, payload, "risk-maker"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/lcr/" + id).get("status").asString()).isEqualTo("CALCULATED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("lcr_calculation", id, payload, "risk-checker"), Outcome.APPROVED);
        JsonNode approved = get("/v1/treasury/liquidity/lcr/" + id);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("approved_by").asString()).isEqualTo("risk-checker");

        // redelivery of the same decision (by another approver even) is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("lcr_calculation", id, payload, "risk-boss"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/lcr/" + id).get("approved_by").asString()).isEqualTo("risk-checker");
    }

    @Test
    void nsfrRejectionIsRecordedAndStaleDecisionAfterRecalculationIsIgnored() throws Exception {
        String id = post("/v1/treasury/liquidity/nsfr/calculate", nsfr("2026-11-11", "1000"), "risk-maker", 200)
                .get("id").asString();
        Map<?, ?> first = (Map<?, ?>) last("nsfr_calculation", id).get("payload");

        // the calculator rejecting in the inbox is refused (four-eyes); the checker's rejection is recorded
        dispatcher.dispatch(UUID.fromString(TENANT), decision("nsfr_calculation", id, first, "risk-maker"), Outcome.REJECTED);
        assertThat(get("/v1/treasury/liquidity/nsfr/" + id).get("status").asString()).isEqualTo("CALCULATED");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("nsfr_calculation", id, first, "risk-checker"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/treasury/liquidity/nsfr/" + id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("risk-checker");
        assertThat(rejected.hasNonNull("approved_by")).isFalse();
        assertThat(post("/v1/treasury/liquidity/nsfr/" + id + "/approve", "{}", "risk-checker", 409).get("code").asString())
                .isEqualTo("invalid_transition");

        // recalculation replaces the snapshot (new row_version, rejection cleared) and opens a new request
        JsonNode recalculated = post("/v1/treasury/liquidity/nsfr/calculate", nsfr("2026-11-11", "900"), "risk-maker", 200);
        assertThat(recalculated.get("status").asString()).isEqualTo("CALCULATED");
        assertThat(recalculated.hasNonNull("rejected_by")).isFalse();
        Map<?, ?> second = (Map<?, ?>) last("nsfr_calculation", id).get("payload");
        assertThat(second.get("row_version")).isNotEqualTo(first.get("row_version"));

        // an approval for the earlier calculation does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision("nsfr_calculation", id, first, "risk-checker"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/nsfr/" + id).get("status").asString()).isEqualTo("CALCULATED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("nsfr_calculation", id, second, "risk-checker"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/nsfr/" + id).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void lcrRejectedDirectlyByAChecker() throws Exception {
        String id = post("/v1/treasury/liquidity/lcr/calculate", lcr("2026-11-13"), "risk-maker", 200).get("id").asString();
        assertThat(post("/v1/treasury/liquidity/lcr/" + id + "/reject", "{\"reason\":\"mine\"}", "risk-maker", 422)
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode r = post("/v1/treasury/liquidity/lcr/" + id + "/reject", "{\"reason\":\"HQLA haircut wrong\"}", "risk-checker", 200);
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(r.get("rejection_reason").asString()).isEqualTo("HQLA haircut wrong");
    }

    @Test
    void stressScenarioApprovalFromWorkflowAndReworkMakesOldDecisionStale() throws Exception {
        String id = post("/v1/treasury/liquidity/stress-scenarios", """
                {"code":"WF-IDIO","name":"Idiosyncratic","severity":"IDIOSYNCRATIC","horizon_days":30,
                 "assumptions":{"runoff_rates":{"RETAIL_LESS_STABLE":"0.30"},"runoff_profile":"LINEAR"}}""",
                "risk-maker", 201).get("id").asString();
        Map<?, ?> first = (Map<?, ?>) last("liquidity_stress_scenario", id).get("payload");
        assertThat(first.get("code")).isEqualTo("WF-IDIO");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("liquidity_stress_scenario", id, first, "alco"), Outcome.REJECTED);
        assertThat(get("/v1/treasury/liquidity/stress-scenarios/" + id).hasNonNull("approved_by")).isFalse();

        // rework: material change opens a new request; a decision on the old content is ignored
        HttpResponse<String> patched = send("PATCH", "/v1/treasury/liquidity/stress-scenarios/" + id,
                "{\"assumptions\":{\"runoff_profile\":\"UPFRONT\"}}", null, "risk-maker", "\"1\"");
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        Map<?, ?> second = (Map<?, ?>) last("liquidity_stress_scenario", id).get("payload");
        assertThat(second.get("row_version")).isEqualTo(2);
        dispatcher.dispatch(UUID.fromString(TENANT), decision("liquidity_stress_scenario", id, first, "alco"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/stress-scenarios/" + id).hasNonNull("approved_by")).isFalse();

        // four-eyes still enforced, then the checker's decision applies
        dispatcher.dispatch(UUID.fromString(TENANT), decision("liquidity_stress_scenario", id, second, "risk-maker"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/stress-scenarios/" + id).hasNonNull("approved_by")).isFalse();
        dispatcher.dispatch(UUID.fromString(TENANT), decision("liquidity_stress_scenario", id, second, "alco"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/stress-scenarios/" + id).get("approved_by").asString()).isEqualTo("alco");

        // redelivered decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("liquidity_stress_scenario", id, second, "other"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/liquidity/stress-scenarios/" + id).get("approved_by").asString()).isEqualTo("alco");
    }

    @Test
    void directApprovalClosesTheInboxItemAfterCommit() throws Exception {
        String id = post("/v1/treasury/liquidity/lcr/calculate", lcr("2026-11-12"), "risk-maker", 200).get("id").asString();
        post("/v1/treasury/liquidity/lcr/" + id + "/approve", "{}", "risk-checker", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=lcr_calculation").contains("resource_id=" + id)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private Map<String, Object> last(String type, String id) {
        Map<String, Object> found = null;
        for (Map<String, Object> r : requests) {
            if (type.equals(r.get("resource_type")) && id.equals(r.get("resource_id"))) {
                found = r;
            }
        }
        assertThat(found).as("approval request for " + type + " " + id).isNotNull();
        return found;
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "risk-maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private static String lcr(String date) {
        return """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"line":"govies","code":"L1","category":"HQLA","amount":"100"},
                  {"line":"covered","code":"L2A","amount":"100"},
                  {"line":"rmbs","code":"L2B","amount":"100"},
                  {"line":"retail","code":"RETAIL_STABLE","category":"OUTFLOW","amount":"1000"},
                  {"line":"interbank","code":"FINANCIAL_INSTITUTION","amount":"100"},
                  {"line":"loans","code":"CORPORATE_LOANS","amount":"400"}]}""".formatted(ENTITY, date);
    }

    private static String nsfr(String date, String retail) {
        return """
                {"legal_entity_id":"%s","as_of_date":"%s","currency":"EUR","positions":[
                  {"code":"REGULATORY_CAPITAL","side":"ASF","amount":"100"},{"code":"RETAIL_STABLE","amount":"%s"},
                  {"code":"LOANS_LONG","side":"RSF","amount":"800"}]}""".formatted(ENTITY, date, retail);
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "viewer", null);
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user, String ifMatch)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
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
