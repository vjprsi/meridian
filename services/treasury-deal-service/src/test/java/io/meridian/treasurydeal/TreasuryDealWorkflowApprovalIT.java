package io.meridian.treasurydeal;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, counterparty onboarding, counterparty
 * limits (new and increased) and SSI verification open approval requests in workflow-service, and decisions arriving
 * from workflow-service are applied with the approver as principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class TreasuryDealWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000d7";
    static final String ENTITY = "0199a000-0000-7000-8000-00000000b001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        requests.clear();
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            requests.add((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void counterpartyApprovedFromWorkflowUnderFourEyesAndRedeliveryIsHarmless() throws Exception {
        String cp = counterparty("WFCP1");
        Map<String, Object> req = last("treasury_counterparty", cp);
        assertThat(req).containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("code")).isEqualTo("WFCP1");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty", cp, "approve", payload, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/treasury/counterparties/" + cp).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty", cp, "approve", payload, "checker.ben"),
                Outcome.APPROVED);
        JsonNode row = get("/v1/treasury/counterparties/" + cp);
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.get("approved_by").asString()).isEqualTo("checker.ben");

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty", cp, "approve", payload, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/treasury/counterparties/" + cp).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void limitRejectedFromWorkflowStaysPendingAndStaleApprovalIsIgnoredAfterRework() throws Exception {
        String cp = counterparty("WFCP2");
        String limit = post("/v1/treasury/limits", """
                {"treasury_counterparty_id":"%s","limit_type":"MONEY_MARKET","currency":"EUR","limit_amount":"5000000",
                 "valid_from":"2020-01-01"}""".formatted(cp), 201, "maker.anna").get("id").asString();
        Map<String, Object> req = last("treasury_counterparty_limit", limit);
        assertThat(req).containsEntry("currency", "EUR");
        assertThat(new java.math.BigDecimal((String) req.get("amount"))).isEqualByComparingTo("5000000");
        Map<?, ?> first = (Map<?, ?>) req.get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty_limit", limit, "approve", first,
                "checker.ben"), Outcome.REJECTED);
        assertThat(get("/v1/treasury/limits/" + limit).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        // rework opens a new request for the changed content
        patch("/v1/treasury/limits/" + limit, "{\"limit_amount\":\"4000000\"}", "maker.anna");
        Map<String, Object> second = last("treasury_counterparty_limit", limit);
        assertThat(new java.math.BigDecimal((String) second.get("amount"))).isEqualByComparingTo("4000000");

        // an approval of the first submission (old row_version) does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty_limit", limit, "approve", first,
                "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/limits/" + limit).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty_limit", limit, "approve",
                (Map<?, ?>) second.get("payload"), "checker.ben"), Outcome.APPROVED);
        JsonNode row = get("/v1/treasury/limits/" + limit);
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.get("approved_by").asString()).isEqualTo("checker.ben");

        // an increase of the active limit needs a new approval through workflow-service
        patch("/v1/treasury/limits/" + limit, "{\"limit_amount\":\"6000000\"}", "maker.anna");
        assertThat(get("/v1/treasury/limits/" + limit).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        Map<String, Object> increase = last("treasury_counterparty_limit", limit);
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_counterparty_limit", limit, "approve",
                (Map<?, ?>) increase.get("payload"), "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/limits/" + limit).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void ssiVerifiedFromWorkflowAndReverificationAfterPaymentDetailChange() throws Exception {
        String cp = counterparty("WFCP3");
        String ssi = post("/v1/treasury/ssis", """
                {"treasury_counterparty_id":"%s","currency":"EUR","beneficiary_bic":"DEUTDEFFXXX",
                 "beneficiary_account":"DE89370400440532013000","valid_from":"2020-01-01"}""".formatted(cp), 201,
                "maker.anna").get("id").asString();
        Map<String, Object> req = last("treasury_ssi", ssi);
        assertThat(req).containsEntry("action", "verify");
        Map<?, ?> first = (Map<?, ?>) req.get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_ssi", ssi, "verify", first, "maker.anna"),
                Outcome.APPROVED);
        assertThat(get("/v1/treasury/ssis/" + ssi).hasNonNull("verified_by")).isFalse();
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_ssi", ssi, "verify", first, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/treasury/ssis/" + ssi).get("verified_by").asString()).isEqualTo("checker.ben");

        // new payment details clear the verification and open a new request
        patch("/v1/treasury/ssis/" + ssi, "{\"beneficiary_account\":\"DE02120300000000202051\"}", "maker.anna");
        Map<?, ?> second = (Map<?, ?>) last("treasury_ssi", ssi).get("payload");
        assertThat(second.get("beneficiary_account")).isEqualTo("DE02120300000000202051");
        // redelivered approval of the old details is ignored
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_ssi", ssi, "verify", first, "checker.ben"),
                Outcome.APPROVED);
        assertThat(get("/v1/treasury/ssis/" + ssi).hasNonNull("verified_by")).isFalse();
        // a rejection leaves it unverified
        dispatcher.dispatch(UUID.fromString(TENANT), decision("treasury_ssi", ssi, "verify", second, "checker.ben"),
                Outcome.REJECTED);
        assertThat(get("/v1/treasury/ssis/" + ssi).hasNonNull("verified_by")).isFalse();
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String cp = counterparty("WFCP4");
        post("/v1/treasury/counterparties/" + cp + "/approve", "{}", 422, "maker.anna");
        post("/v1/treasury/counterparties/" + cp + "/approve", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=treasury_counterparty").contains("resource_id=" + cp)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private Map<String, Object> last(String resourceType, String id) {
        synchronized (requests) {
            return requests.stream().filter(r -> resourceType.equals(r.get("resource_type")) && id.equals(r.get("resource_id")))
                    .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no approval request for " + resourceType + " " + id));
        }
    }

    private ObjectNode decision(String resourceType, String id, String action, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", resourceType);
        row.put("resource_id", id);
        row.put("action", action);
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String counterparty(String code) throws Exception {
        return post("/v1/treasury/counterparties", """
                {"legal_entity_id":"%s","code":"%s","name":"Deutsche Bank AG","counterparty_type":"BANK","bic":"DEUTDEFF",
                 "lei":"529900T8BM49AURSDO55","country_code":"DE","isda_signed":true,"external_rating":"A-"}"""
                .formatted(ENTITY, code), 201, "maker.anna").get("id").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode patch(String path, String body, String user) throws Exception {
        String version = get(path).get("row_version").asString();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("If-Match", "\"" + version + "\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
