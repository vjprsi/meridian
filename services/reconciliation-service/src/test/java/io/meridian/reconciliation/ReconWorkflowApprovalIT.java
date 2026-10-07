package io.meridian.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11) for reconciliation: rule set publication and break write-offs open approval requests
 * in workflow-service (write-offs with their amount for threshold policies); decisions coming back are applied with
 * the approver as principal under the same four-eyes rule, and stale decisions are ignored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class ReconWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000e2";
    static final String MAKER = "maker.anna";
    static final String CHECKER = "checker.ben";

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
    final String nostro = UUID.randomUUID().toString();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() throws Exception {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
        when(serviceClient.get(eq("ledger-service"), startsWith("/v1/ledger-entries?ledger_account_id=" + nostro)))
                .thenReturn(json.readTree("{\"data\":[],\"has_more\":false}"));
    }

    @Test
    void ruleSetPublicationIsDecidedInTheCentralInbox() throws Exception {
        String rs = post("/v1/reconciliation/rule-sets", "{\"name\":\"Tenant default\"}", 201, MAKER).get("id").asString();
        JsonNode submitted = post("/v1/reconciliation/rule-sets/" + rs + "/submit", null, 200, MAKER);
        assertThat(submitted.hasNonNull("approval_request_id")).isTrue();
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "recon_rule_set").containsEntry("resource_id", rs)
                .containsEntry("action", "publish");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("checksum")).isEqualTo(submitted.get("checksum").asString());

        dispatcher.dispatch(UUID.fromString(TENANT), decision("recon_rule_set", rs, payload, MAKER), Outcome.APPROVED);
        assertThat(get("/v1/reconciliation/rule-sets/" + rs).get("status").asString())
                .as("the maker approving in the inbox is refused").isEqualTo("PENDING_APPROVAL");
        ObjectNode stale = decision("recon_rule_set", rs, payload, CHECKER);
        ((ObjectNode) stale.get("payload")).put("checksum", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/reconciliation/rule-sets/" + rs).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("recon_rule_set", rs, payload, CHECKER), Outcome.APPROVED);
        JsonNode published = get("/v1/reconciliation/rule-sets/" + rs);
        assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.get("approved_by").asString()).isEqualTo(CHECKER);
    }

    @Test
    void writeOffIsRoutedWithItsAmountAndDecidedCentrally() throws Exception {
        String source = post("/v1/reconciliation/sources", """
                {"name":"WF nostro","source_type":"BANK_STATEMENT","file_format":"JSON","currency":"EUR","ledger_account_id":"%s"}"""
                .formatted(nostro), 201, MAKER).get("id").asString();
        post("/v1/reconciliation/files/import", """
                {"recon_source_id":"%s","file_name":"wf.json","records":[
                  {"value_date":"2026-03-02","direction":"DEBIT","amount":"12.00","currency":"EUR","external_ref":"FEE-1"},
                  {"value_date":"2026-03-02","direction":"DEBIT","amount":"7.00","currency":"EUR","external_ref":"FEE-2"}]}"""
                .formatted(source), 200, MAKER);
        String run = post("/v1/reconciliation/runs", """
                {"recon_source_id":"%s","from_date":"2026-03-01","to_date":"2026-03-05"}""".formatted(source), 201, MAKER)
                .get("id").asString();
        JsonNode breaks = get("/v1/reconciliation/exceptions?recon_run_id=" + run).get("data");
        assertThat(breaks).hasSize(2);
        String b1 = breaks.get(0).get("id").asString();
        String b2 = breaks.get(1).get("id").asString();

        String wo = post("/v1/reconciliation/adjustments", """
                {"recon_exception_id":"%s","kind":"WRITE_OFF","amount":"%s","currency":"EUR","reason":"bank fee"}"""
                .formatted(b1, breaks.get(0).get("amount").asString()), 201, MAKER).get("id").asString();
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "recon_adjustment").containsEntry("resource_id", wo)
                .containsEntry("action", "write_off").containsKey("amount").containsEntry("currency", "EUR");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("recon_adjustment", wo, (Map<?, ?>) req.get("payload"), CHECKER),
                Outcome.APPROVED);
        assertThat(get("/v1/reconciliation/adjustments/" + wo).get("status").asString()).isEqualTo("APPROVED");
        assertThat(get("/v1/reconciliation/exceptions/" + b1).get("status").asString()).isEqualTo("WRITTEN_OFF");

        String wo2 = post("/v1/reconciliation/adjustments", """
                {"recon_exception_id":"%s","kind":"WRITE_OFF","amount":"7.00","currency":"EUR","reason":"bank fee"}"""
                .formatted(b2), 201, MAKER).get("id").asString();
        dispatcher.dispatch(UUID.fromString(TENANT), decision("recon_adjustment", wo2,
                (Map<?, ?>) lastRequest.get().get("payload"), CHECKER), Outcome.REJECTED);
        assertThat(get("/v1/reconciliation/adjustments/" + wo2).get("status").asString()).isEqualTo("REJECTED");
        assertThat(get("/v1/reconciliation/exceptions/" + b2).get("status").asString()).isEqualTo("OPEN");
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("maker", MAKER);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        b.method("POST", body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", MAKER).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
