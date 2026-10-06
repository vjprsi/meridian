package io.meridian.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Central maker-checker (G11) for disbursement batches with {@code meridian.approvals.mode=workflow}: submit opens
 * the level-1 request in workflow-service, each non-final approval opens the next level, and decisions arriving from
 * workflow-service are applied with the approver as principal under the batch's own rules (submitter excluded, a
 * different approver per level, funding checked and payouts executed on the final level). wallet-service is the
 * {@link FakeWalletService}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.disbursement.default-calling-code=254",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class DisbursementWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000d50c7";
    static final AtomicInteger SEQ = new AtomicInteger(10);

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    ApprovalEventsDispatcher dispatcher;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final List<Map<String, Object>> approvalRequests = new CopyOnWriteArrayList<>();
    static FakeWalletService wallets;
    static UUID fund;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stub() {
        if (wallets == null) {
            wallets = new FakeWalletService(json);
            fund = wallets.wallet("+254788888888", "Relief Fund", "KES", "ACTIVE", 3, "100000");
        }
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                if ("/v1/approval-requests".equals(i.getArgument(1))) {
                    approvalRequests.add((Map<String, Object>) i.getArgument(2));
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return wallets.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> "workflow-service".equals(i.getArgument(0))
                ? json.readTree("{\"data\":[]}") : wallets.get(i.getArgument(0), i.getArgument(1)));
    }

    @Test
    void twoLevelsApprovedFromWorkflowOneRequestPerLevelWithFourEyes() throws Exception {
        String batch = submittedBatch(2);
        Map<String, Object> l1 = last(batch);
        assertThat(l1).containsEntry("resource_type", "disbursement_batch").containsEntry("action", "approve-level-1")
                .containsEntry("amount", "150").containsEntry("currency", "KES");
        Map<?, ?> p1 = (Map<?, ?>) l1.get("payload");
        assertThat(p1.get("level")).isEqualTo(1);
        assertThat(p1.get("approvals_required")).isEqualTo(2);
        assertThat(p1.get("submitted_at")).isNotNull();

        // the submitter approving in the inbox is still refused
        dispatch(batch, "approve-level-1", p1, "maker", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("approvals_received").asInt()).isZero();

        dispatch(batch, "approve-level-1", p1, "checker1", Outcome.APPROVED);
        JsonNode b = get("/v1/disbursement-batches/" + batch);
        assertThat(b.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(b.get("approvals_received").asInt()).isEqualTo(1);
        Map<String, Object> l2 = last(batch);
        assertThat(l2).containsEntry("action", "approve-level-2");
        Map<?, ?> p2 = (Map<?, ?>) l2.get("payload");
        assertThat(p2.get("level")).isEqualTo(2);
        assertThat(p2.get("earlier_approvers").toString()).isEqualTo("[checker1]");

        // redelivery of the level-1 decision does not count as a second level
        dispatch(batch, "approve-level-1", p1, "checker2", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("approvals_received").asInt()).isEqualTo(1);

        // the level-1 approver cannot also approve level 2
        dispatch(batch, "approve-level-2", p2, "checker1", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("approvals_received").asInt()).isEqualTo(1);

        int payoutsBefore = wallets.payouts().size();
        dispatch(batch, "approve-level-2", p2, "checker2", Outcome.APPROVED);
        b = get("/v1/disbursement-batches/" + batch);
        assertThat(b.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(b.get("approved_by").asString()).isEqualTo("checker2");
        assertThat(wallets.payouts()).hasSize(payoutsBefore + 2);
        JsonNode approvals = get("/v1/disbursement-batches/" + batch + "/approvals");
        assertThat(approvals.get("data")).extracting(a -> a.get("decided_by").asString())
                .containsExactlyInAnyOrder("checker1", "checker2");

        // redelivery of the final decision pays nobody twice
        dispatch(batch, "approve-level-2", p2, "checker2", Outcome.APPROVED);
        assertThat(wallets.payouts()).hasSize(payoutsBefore + 2);
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionsAreIgnored() throws Exception {
        String batch = submittedBatch(2);
        Map<?, ?> p1 = (Map<?, ?>) last(batch).get("payload");
        dispatch(batch, "approve-level-1", p1, "checker1", Outcome.APPROVED);
        Map<?, ?> p2 = (Map<?, ?>) last(batch).get("payload");

        // a decision whose level does not match the action, or for another submission, is ignored
        dispatch(batch, "approve-level-1", p2, "checker2", Outcome.REJECTED);
        ObjectNode stale = json.valueToTree(p2);
        stale.put("submitted_at", "2001-01-01T00:00:00Z");
        dispatch(batch, "approve-level-2", json.treeToValue(stale, Map.class), "checker2", Outcome.REJECTED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatch(batch, "approve-level-2", p2, "checker2", Outcome.REJECTED);
        JsonNode b = get("/v1/disbursement-batches/" + batch);
        assertThat(b.get("status").asString()).isEqualTo("REJECTED");
        assertThat(b.get("rejection_reason").asString()).contains("level 2");

        // a late approval after the rejection does nothing
        dispatch(batch, "approve-level-2", p2, "checker3", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void directDecisionsOpenTheNextLevelAndCloseInboxItems() throws Exception {
        String batch = submittedBatch(2);
        Map<?, ?> p1 = (Map<?, ?>) last(batch).get("payload");
        post("/v1/disbursement-batches/" + batch + "/approve", "{\"comment\":\"l1\"}", "checker1", 200);
        assertThat(last(batch)).containsEntry("action", "approve-level-2");
        verify(serviceClient, atLeastOnce()).get(eq("workflow-service"), contains("resource_id=" + batch));

        // a late inbox decision for level 1 is ignored; level 2 from the inbox completes the batch
        Map<?, ?> p2 = (Map<?, ?>) last(batch).get("payload");
        dispatch(batch, "approve-level-1", p1, "checker2", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("approvals_received").asInt()).isEqualTo(1);
        dispatch(batch, "approve-level-2", p2, "checker2", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void singleLevelBatchExecutesOnTheWorkflowApproval() throws Exception {
        String batch = submittedBatch(1);
        Map<String, Object> l1 = last(batch);
        dispatch(batch, "approve-level-1", (Map<?, ?>) l1.get("payload"), "checker1", Outcome.APPROVED);
        assertThat(get("/v1/disbursement-batches/" + batch).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(approvalRequests.stream().filter(r -> batch.equals(r.get("resource_id")))).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private String submittedBatch(int levels) throws Exception {
        int n = SEQ.incrementAndGet();
        String program = post("/v1/disbursement-programs", """
                {"code":"WF-%d","name":"Relief","sponsor_name":"Relief Agency","program_type":"SOCIAL_TRANSFER",
                 "funding_wallet_id":"%s","currency":"KES","max_amount_per_beneficiary":"5000",
                 "approval_levels":%d,"auto_create_wallets":true,"require_name_match":false}""".formatted(n, fund, levels),
                "maker", 201).get("id").asString();
        post("/v1/disbursement-programs/" + program + "/activate", null, "ops", 200);
        String batch = post("/v1/disbursement-batches", "{\"disbursement_program_id\":\"" + program
                + "\",\"currency\":\"KES\",\"description\":\"Relief\"}", "maker", 201).get("id").asString();
        String file = "msisdn,amount,full_name\n+2547100%05d,100,Person A\n+2547200%05d,50,Person B\n".formatted(n, n);
        post("/v1/disbursement-batches/" + batch + "/upload",
                json.writeValueAsString(Map.of("content", file, "file_name", "wf-" + n + ".csv")), "maker", 200);
        assertThat(post("/v1/disbursement-batches/" + batch + "/validate", null, "maker", 200).get("valid_count").asInt())
                .isEqualTo(2);
        post("/v1/disbursement-batches/" + batch + "/submit", null, "maker", 200);
        return batch;
    }

    private Map<String, Object> last(String batch) {
        return approvalRequests.reversed().stream().filter(r -> batch.equals(r.get("resource_id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no approval request for batch " + batch));
    }

    private void dispatch(String batch, String action, Map<?, ?> payload, String approver, Outcome outcome)
            throws Exception {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "disbursement_batch");
        row.put("resource_id", batch);
        row.put("action", action);
        row.put("maker", "maker");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        assertThat(dispatcher.dispatch(UUID.fromString(TENANT), row, outcome)).isTrue();
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "viewer").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
