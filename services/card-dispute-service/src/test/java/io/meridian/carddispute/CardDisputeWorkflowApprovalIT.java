package io.meridian.carddispute;

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
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, a dispute creator's close that would
 * write off an issuer loss is refused as before but submitted to workflow-service; the decision arriving from
 * workflow-service replays the close with the approver as principal, still under the four-eyes rule. card-service
 * and ledger-service stay behind the stateful {@link FakeDownstream}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.cards.ledger-id=0199c000-0000-7000-8000-0000000000a1",
        "meridian.cards.dispute.suspense-account=0199c000-0000-7000-8000-0000000000d1",
        "meridian.cards.dispute.loss-expense-account=0199c000-0000-7000-8000-0000000000d2",
        "meridian.cards.dispute.scheme-receivable-account=0199c000-0000-7000-8000-0000000000d3"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardDisputeWorkflowApprovalIT {
    static final String TENANT = "0199c000-0000-7000-8000-00000000d071";
    static final UUID LOSS = UUID.fromString("0199c000-0000-7000-8000-0000000000d2");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final AtomicReference<Map<String, Object>> lastRequest = new AtomicReference<>();
    final List<String> requestKeys = new ArrayList<>();
    FakeDownstream fake;
    LocalDate today;

    @BeforeAll
    void setUp() {
        fake = new FakeDownstream(json);
        today = LocalDate.parse(jdbc.sql("select current_date::text").query(String.class).single());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                if ("/v1/approval-requests".equals(i.getArgument(1))) {
                    lastRequest.set((Map<String, Object>) i.getArgument(2));
                    requestKeys.add(i.getArgument(3));
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return fake.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> "workflow-service".equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : fake.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    void creatorWriteOffIsSubmittedAndApprovalFromWorkflowCloses() throws Exception {
        String id = disputeWithProvisionalCredit("100");
        String lost = "{\"outcome\":\"LOST\",\"customer_liable\":false,\"note\":\"merchant out of business\"}";
        JsonNode refused = post("/v1/card-disputes/" + id + "/close", lost, "maker", 422);
        assertThat(refused.get("code").asString()).isEqualTo("four_eyes_violation");
        assertThat(refused.get("detail").asString()).contains("sent for approval as workflow request");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "card_dispute").containsEntry("resource_id", id)
                .containsEntry("action", "write_off").containsEntry("currency", "USD");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("100");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("outcome")).isEqualTo("LOST");
        assertThat(new BigDecimal((String) payload.get("write_off_amount"))).isEqualByComparingTo("100");
        assertThat(get("/v1/card-disputes/" + id).get("stage").asString()).isNotEqualTo("CLOSED");

        // the same proposal again uses the same idempotency key (no duplicate inbox item)
        post("/v1/card-disputes/" + id + "/close", lost, "maker", 422);
        assertThat(requestKeys.get(requestKeys.size() - 1)).isEqualTo(requestKeys.get(requestKeys.size() - 2));

        // the creator approving in the inbox is still refused by the four-eyes rule
        int keysBefore = requestKeys.size();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "maker"), Outcome.APPROVED);
        assertThat(get("/v1/card-disputes/" + id).get("stage").asString()).isNotEqualTo("CLOSED");

        BigDecimal lossBefore = fake.balance(LOSS);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        assertThat(requestKeys).hasSize(keysBefore); // the replayed close opens no new request
        JsonNode closed = get("/v1/card-disputes/" + id);
        assertThat(closed.get("stage").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("outcome").asString()).isEqualTo("LOST");
        assertThat(new BigDecimal(closed.get("written_off_amount").asString())).isEqualByComparingTo("100");
        assertThat(fake.balance(LOSS).subtract(lossBefore)).isEqualByComparingTo("-100");
        assertThat(timeline(id, "LOST")).singleElement().satisfies(e ->
                assertThat(e.get("actor_id").asString()).isEqualTo("checker"));

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/card-disputes/" + id).get("row_version").asInt()).isEqualTo(closed.get("row_version").asInt());
        assertThat(fake.balance(LOSS).subtract(lossBefore)).isEqualByComparingTo("-100");
    }

    @Test
    void acceptRepresentmentWriteOffApprovedFromWorkflow() throws Exception {
        String id = disputeWithProvisionalCredit("60");
        post("/v1/card-disputes/" + id + "/submit-chargeback", "{\"reason_code\":\"13.1\",\"amount\":\"60\"}", "analyst", 200);
        post("/v1/card-disputes/" + id + "/record-representment", "{\"amount\":\"60\"}", "analyst", 200);
        post("/v1/card-disputes/" + id + "/accept-representment", "{\"customer_liable\":false}", "maker", 422);
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(payload.get("close_action")).isEqualTo("accept_representment");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        JsonNode closed = get("/v1/card-disputes/" + id);
        assertThat(closed.get("status").asString()).isEqualTo("LOST");
        assertThat(new BigDecimal(closed.get("written_off_amount").asString())).isEqualByComparingTo("60");
    }

    @Test
    void rejectionFromWorkflowKeepsDisputeOpenAndStaleDecisionIsIgnored() throws Exception {
        String id = disputeWithProvisionalCredit("40");
        post("/v1/card-disputes/" + id + "/close", "{\"outcome\":\"LOST\",\"customer_liable\":false}", "maker", 422);
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");
        int version = get("/v1/card-disputes/" + id).get("row_version").asInt();

        ObjectNode rejection = decision(id, payload, "checker");
        dispatcher.dispatch(UUID.fromString(TENANT), rejection, Outcome.REJECTED);
        dispatcher.dispatch(UUID.fromString(TENANT), rejection, Outcome.REJECTED); // redelivered
        JsonNode d = get("/v1/card-disputes/" + id);
        assertThat(d.get("stage").asString()).isEqualTo("INQUIRY");
        assertThat(d.get("row_version").asInt()).isEqualTo(version);
        assertThat(timeline(id, "NOTE")).singleElement().satisfies(e ->
                assertThat(e.get("note").asString()).contains("write-off rejected in workflow request"));

        // the dispute changed after the proposal: an approval for the old proposal does nothing
        post("/v1/card-disputes/" + id + "/assign", "{\"assigned_to\":\"alice\"}", "lead", 200);
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "checker"), Outcome.APPROVED);
        assertThat(get("/v1/card-disputes/" + id).get("stage").asString()).isEqualTo("INQUIRY");
    }

    @Test
    void directCloseByAnotherUserClosesTheInboxItemAfterCommit() throws Exception {
        String id = disputeWithProvisionalCredit("30");
        post("/v1/card-disputes/" + id + "/close", "{\"outcome\":\"LOST\",\"customer_liable\":false}", "maker", 422);
        verify(serviceClient, never()).get(eq("workflow-service"), anyString());
        post("/v1/card-disputes/" + id + "/close", "{\"outcome\":\"LOST\",\"customer_liable\":false}", "checker", 200);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=card_dispute").contains("resource_id=" + id)
                .contains("status=PENDING");
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    private String disputeWithProvisionalCredit(String amount) throws Exception {
        UUID card = UUID.randomUUID();
        fake.addCard(card, UUID.randomUUID(), UUID.randomUUID());
        String id = post("/v1/card-disputes", "{\"card_id\":\"" + card + "\",\"scheme\":\"VISA\",\"category\":\"NOT_RECEIVED\","
                + "\"disputed_amount\":\"" + amount + "\",\"currency\":\"USD\",\"transaction_date\":\"" + today.minusDays(5)
                + "\"}", "maker", 201).get("id").asString();
        post("/v1/card-disputes/" + id + "/provisional-credit", "{\"amount\":\"" + amount + "\"}", "analyst", 200);
        return id;
    }

    private List<JsonNode> timeline(String id, String type) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        get("/v1/card-disputes/" + id + "/events").get("data").forEach(e -> {
            if (type.equals(e.get("event_type").asString())) {
                out.add(e);
            }
        });
        return out;
    }

    private ObjectNode decision(String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "card_dispute");
        row.put("resource_id", id);
        row.put("action", "write_off");
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
