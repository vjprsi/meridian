package io.meridian.gl;

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
 * Central maker-checker (G11) for gl-service: with {@code meridian.approvals.mode=workflow}, a manual GL journal and a
 * soft close of an accounting period open approval requests in workflow-service; decisions arriving from
 * workflow-service are applied with the approver as principal, still under the four-eyes rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c8";
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
    final String le = UUID.randomUUID().toString();
    String cash;
    String impairment;
    String jan;

    @BeforeAll
    void chart() throws Exception {
        String assets = account("1000", "Assets", "ASSET", null, false);
        cash = account("1100", "Cash", "ASSET", assets, true);
        String expenses = account("5000", "Expenses", "EXPENSE", null, false);
        impairment = account("5100", "Impairment expense", "EXPENSE", expenses, true);
        jan = period("2026-01-01", "2026-01-31");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void manualJournalApprovedFromWorkflowPostsUnderFourEyes() throws Exception {
        String id = journal("50.00");
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "gl_journal").containsEntry("resource_id", id)
                .containsEntry("action", "approve").containsEntry("currency", "USD");
        assertThat(new java.math.BigDecimal((String) req.get("amount"))).isEqualByComparingTo("50");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("checksum")).isNotNull();

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("gl_journal", id, payload, MAKER), Outcome.APPROVED);
        assertThat(approvals(id)).isEmpty();

        dispatcher.dispatch(UUID.fromString(TENANT), decision("gl_journal", id, payload, CHECKER), Outcome.APPROVED);
        JsonNode approvals = approvals(id);
        assertThat(approvals).hasSize(1);
        assertThat(approvals.get(0).get("approved_by").asString()).isEqualTo(CHECKER);

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("gl_journal", id, payload, CHECKER), Outcome.APPROVED);
        assertThat(approvals(id)).hasSize(1);
    }

    @Test
    void journalRejectionAndStaleDecisionsLeaveItUnposted() throws Exception {
        String id = journal("20.00");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("gl_journal", id, payload, CHECKER), Outcome.REJECTED);
        assertThat(approvals(id)).isEmpty();

        ObjectNode stale = decision("gl_journal", id, payload, CHECKER);
        ((ObjectNode) stale.get("payload")).put("checksum", "stale");
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(approvals(id)).isEmpty();
    }

    @Test
    void periodCloseApprovedOrRejectedFromWorkflowAndStaleRunIgnored() throws Exception {
        String feb = period("2026-02-01", "2026-02-28");
        post("/v1/gl/periods/" + feb + "/close", null, MAKER, 200);
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "accounting_period_close").containsEntry("resource_id", feb)
                .containsEntry("action", "approve_close");
        Map<?, ?> first = (Map<?, ?>) req.get("payload");
        assertThat(first.get("close_run_id")).isNotNull();

        // rejection in the inbox reopens the soft-closed period
        dispatcher.dispatch(UUID.fromString(TENANT), decision("accounting_period_close", feb, first, CHECKER), Outcome.REJECTED);
        assertThat(get("/v1/gl/periods/" + feb).get("status").asString()).isEqualTo("REOPENED");

        post("/v1/gl/periods/" + feb + "/close", null, MAKER, 200);
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(second.get("close_run_id")).isNotEqualTo(first.get("close_run_id"));
        // an approval of the earlier close run is stale
        dispatcher.dispatch(UUID.fromString(TENANT), decision("accounting_period_close", feb, first, CHECKER), Outcome.APPROVED);
        assertThat(get("/v1/gl/periods/" + feb).get("status").asString()).isEqualTo("SOFT_CLOSED");
        // four-eyes: the maker of the close cannot approve it from the inbox
        dispatcher.dispatch(UUID.fromString(TENANT), decision("accounting_period_close", feb, second, MAKER), Outcome.APPROVED);
        assertThat(get("/v1/gl/periods/" + feb).get("status").asString()).isEqualTo("SOFT_CLOSED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("accounting_period_close", feb, second, CHECKER), Outcome.APPROVED);
        JsonNode closed = get("/v1/gl/periods/" + feb);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("approved_by").asString()).isEqualTo(CHECKER);
        // redelivered rejection after the close is ignored
        dispatcher.dispatch(UUID.fromString(TENANT), decision("accounting_period_close", feb, second, CHECKER), Outcome.REJECTED);
        assertThat(get("/v1/gl/periods/" + feb).get("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    void directApprovalClosesTheInboxItemAfterCommit() throws Exception {
        String id = journal("5.00");
        post("/v1/gl/journals/" + id + "/approve", "{}", CHECKER, 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + id).contains("resource_type=gl_journal").contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "accounting_period_close".equals(type) ? "approve_close" : "approve");
        row.put("maker", MAKER);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String journal(String amount) throws Exception {
        return post("/v1/gl/journals", """
                {"legal_entity_id":"%s","accounting_period_id":"%s","journal_type":"MANUAL_ADJUSTMENT","value_date":"2026-01-20",
                 "description":"adjustment","lines":[
                  {"gl_account_id":"%s","direction":"DEBIT","amount":"%s","currency":"USD"},
                  {"gl_account_id":"%s","direction":"CREDIT","amount":"%s","currency":"USD"}]}"""
                .formatted(le, jan, impairment, amount, cash, amount), MAKER, 201).get("id").asString();
    }

    private JsonNode approvals(String journalId) throws Exception {
        return get("/v1/gl/journal-approvals?gl_journal_id=" + journalId).get("data");
    }

    private String account(String code, String name, String cls, String parent, boolean postable) throws Exception {
        return post("/v1/gl/accounts", """
                {"legal_entity_id":"%s","code":"%s","name":"%s","account_class":"%s","is_postable":%s%s}"""
                .formatted(le, code, name, cls, postable, parent == null ? "" : ",\"parent_id\":\"" + parent + "\""),
                MAKER, 201).get("id").asString();
    }

    private String period(String start, String end) throws Exception {
        return post("/v1/gl/periods", """
                {"legal_entity_id":"%s","period_type":"MONTH","start_date":"%s","end_date":"%s"}"""
                .formatted(le, start, end), MAKER, 201).get("id").asString();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
