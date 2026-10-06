package io.meridian.sanctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, dispositioning a match TRUE_MATCH opens
 * a confirmation request in workflow-service (ids and scores, no names), and the decision arriving from
 * workflow-service is applied with the approver as principal, still under the four-eyes rule against the analyst who
 * dispositioned the match.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SanctionsWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d9";
    static final String LIST_CSV = """
            external_id,entry_type,primary_name,aliases,date_of_birth,nationality,countries,identifiers,programs
            SDN-1,INDIVIDUAL,Osama bin Laden,Usama bin Muhammad bin Awad BIN LADIN;Abu Abdallah,1957-03-10,SA,SA;AF,,SDGT
            SDN-2,ENTITY,Bank Melli Iran,Melli Bank PLC,,IR,IR,BIC:BMJIIRTH,IRAN
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<Map<String, Object>> lastRequest = new AtomicReference<>();

    @BeforeAll
    void list() throws Exception {
        String list = post("/v1/risk/sanctions/lists", """
                {"source":"INTERNAL","code":"WF_WATCH","name":"Workflow watch list"}""", 201, "compliance").get("id").asString();
        post("/v1/risk/sanctions/lists/" + list + "/sync",
                json.writeValueAsString(Map.of("content", LIST_CSV, "list_version", "2026-10-01")), 200, "compliance");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubDownstream() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
        // case-management-service, as in SanctionsFlowIT (a confirmed match opens a case)
        when(serviceClient.post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"OPEN\"}"));
        when(serviceClient.post(eq("case-management-service"), startsWith("/v1/risk/cases/"), any(), anyString()))
                .thenReturn(json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
    }

    @Test
    void trueMatchOpensConfirmationRequestAndApprovalFromWorkflowConfirms() throws Exception {
        String result = potentialMatch();
        JsonNode match = firstMatch(result);
        trueMatch(match, "analyst-1");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "screening_result").containsEntry("resource_id", result)
                .containsEntry("action", "confirm_match").containsEntry("priority", "HIGH");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("true_match_ids")).isEqualTo(List.of(match.get("id").asString()));
        assertThat(payload.get("fingerprint")).isNotNull();
        assertThat(json.writeValueAsString(req)).doesNotContainIgnoringCase("laden").doesNotContainIgnoringCase("ladin");

        // the analyst who dispositioned the match cannot confirm it from the inbox
        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, payload, "analyst-1"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sanctions/results/" + result).get("status").asString()).isEqualTo("POTENTIAL_MATCH");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, payload, "mlro"), Outcome.APPROVED);
        JsonNode confirmed = get("/v1/risk/sanctions/results/" + result);
        assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED_MATCH");
        assertThat(confirmed.get("reviewed_by").asString()).isEqualTo("mlro");
        assertThat(confirmed.get("aml_case_id").asString()).isNotBlank();

        // redelivery of the same decision is harmless (no second case)
        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, payload, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sanctions/results/" + result).get("status").asString()).isEqualTo("CONFIRMED_MATCH");
        verify(serviceClient, times(1)).post(eq("case-management-service"), eq("/v1/risk/cases"), any(), anyString());
    }

    @Test
    void rejectionFromWorkflowReturnsMatchesForReviewAndStaleDecisionIsIgnored() throws Exception {
        String result = potentialMatch();
        JsonNode match = trueMatch(firstMatch(result), "analyst-1");
        Map<?, ?> first = (Map<?, ?>) lastRequest.get().get("payload");

        // the analyst cannot reject (clear) their own true match from the inbox either
        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, first, "analyst-1"), Outcome.REJECTED);
        assertThat(firstMatch(result).get("disposition").asString()).isEqualTo("TRUE_MATCH");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, first, "mlro"), Outcome.REJECTED);
        JsonNode returned = firstMatch(result);
        assertThat(returned.get("disposition").asString()).isEqualTo("PENDING");
        assertThat(returned.hasNonNull("dispositioned_by")).isFalse();
        assertThat(get("/v1/risk/sanctions/results/" + result).get("status").asString()).isEqualTo("POTENTIAL_MATCH");

        // dispositioned again: a decision on the earlier request does not apply
        trueMatch(returned, "analyst-2");
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(second.get("fingerprint")).isNotEqualTo(first.get("fingerprint"));
        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, first, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sanctions/results/" + result).get("status").asString()).isEqualTo("POTENTIAL_MATCH");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(result, second, "mlro"), Outcome.APPROVED);
        assertThat(get("/v1/risk/sanctions/results/" + result).get("status").asString()).isEqualTo("CONFIRMED_MATCH");
        assertThat(match.get("id").asString()).isEqualTo(returned.get("id").asString());
    }

    @Test
    void directReviewKeepsFourEyesAndClosesTheInboxItemAfterCommit() throws Exception {
        String result = potentialMatch();
        trueMatch(firstMatch(result), "analyst-1");
        assertThat(post("/v1/risk/sanctions/results/" + result + "/review",
                "{\"status\":\"CONFIRMED_MATCH\",\"decision_reason\":\"confirmed\"}", 422, "analyst-1").get("code").asString())
                .isEqualTo("four_eyes_violation");
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post("/v1/risk/sanctions/results/" + result + "/review",
                "{\"status\":\"CONFIRMED_MATCH\",\"decision_reason\":\"confirmed by MLRO\"}", 200, "mlro");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=screening_result").contains("resource_id=" + result)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String result, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "screening_result");
        row.put("resource_id", result);
        row.put("action", "confirm_match");
        row.put("maker", "analyst-1");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String potentialMatch() throws Exception {
        JsonNode req = post("/v1/risk/sanctions/screenings", """
                {"subject_type":"CUSTOMER","subject_id":"%s","trigger":"ONBOARDING","full_name":"Usama Bin Ladin",
                 "date_of_birth":"1957-03-10","nationality":"SA","requested_by_service":"onboarding-service"}"""
                .formatted(UUID.randomUUID()), 201, "onboarding");
        assertThat(req.get("status").asString()).isEqualTo("POTENTIAL_MATCH");
        return req.get("screening_result_id").asString();
    }

    private JsonNode firstMatch(String result) throws Exception {
        return get("/v1/risk/sanctions/results/" + result + "/matches").get("data").get(0);
    }

    private JsonNode trueMatch(JsonNode match, String analyst) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/risk/sanctions/matches/" + match.get("id").asString()))
                .header("Content-Type", "application/json").header("X-Tenant-Id", TENANT).header("X-User", analyst)
                .header("If-Match", match.get("row_version").asString())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        "{\"disposition\":\"TRUE_MATCH\",\"disposition_reason\":\"DOB, nationality and name agree\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
