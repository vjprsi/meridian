package io.meridian.alm;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, capturing a behavioural assumption
 * opens an approval request in workflow-service, and the decision arriving from workflow-service is applied with
 * the approver as principal, still under the assumption's four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class AlmWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000a1c7";
    static final String RESOURCE = "alm_behavioural_assumption";

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
    final List<String> keys = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubWorkflow() {
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            keys.add(inv.getArgument(3));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void captureOpensWorkflowRequestAndApprovalFromWorkflowApproves() throws Exception {
        String id = create("SEG_A");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", RESOURCE).containsEntry("resource_id", id)
                .containsEntry("action", "approve");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("row_version")).isNotNull();
        assertThat(payload.get("assumption_type")).isEqualTo("DEPOSIT_DECAY");
        assertThat(payload.get("parameters")).isNotNull();
        assertThat(keys.getLast()).contains("submit:" + id + ":");

        // the maker approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "modeller"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/alm/assumptions/" + id).hasNonNull("approved_by")).isFalse();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "risk"), Outcome.APPROVED);
        JsonNode a = get("/v1/treasury/alm/assumptions/" + id);
        assertThat(a.get("approved_by").asString()).isEqualTo("risk");
        String approvedAt = a.get("approved_at").asString();

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "risk2"), Outcome.APPROVED);
        JsonNode again = get("/v1/treasury/alm/assumptions/" + id);
        assertThat(again.get("approved_by").asString()).isEqualTo("risk");
        assertThat(again.get("approved_at").asString()).isEqualTo(approvedAt);
    }

    @Test
    void rejectionFromWorkflowLeavesItUnapprovedAndStaleDecisionIsIgnored() throws Exception {
        String id = create("SEG_B");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "risk"), Outcome.REJECTED);
        JsonNode a = get("/v1/treasury/alm/assumptions/" + id);
        assertThat(a.hasNonNull("approved_by")).isFalse();
        assertThat(a.get("rejected_by").asString()).isEqualTo("risk");
        assertThat(a.get("rejection_reason").asString()).contains("workflow");
        // a rejected assumption cannot be approved until reworked
        assertThat(post("/v1/treasury/alm/assumptions/" + id + "/approve", "{}", 409, "risk").get("code").asString())
                .isEqualTo("assumption_rejected");

        // rework clears the rejection and resubmits with the new row_version
        HttpResponse<String> patched = send("PATCH", "/v1/treasury/alm/assumptions/" + id,
                "{\"parameters\":{\"core_ratio\":0.7,\"decay_rate\":0.2,\"pass_through\":0.5}}", null, "modeller",
                a.get("row_version").asString());
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        assertThat(json.readTree(patched.body()).hasNonNull("rejected_by")).isFalse();
        Map<?, ?> resubmitted = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(resubmitted.get("row_version")).isNotEqualTo(payload.get("row_version"));

        // an approval for the old submission (old row_version) does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, payload, "risk"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/alm/assumptions/" + id).hasNonNull("approved_by")).isFalse();

        dispatcher.dispatch(UUID.fromString(TENANT), decision(id, resubmitted, "risk"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/alm/assumptions/" + id).get("approved_by").asString()).isEqualTo("risk");
    }

    @Test
    void directDecisionClosesTheInboxItemAfterCommit() throws Exception {
        String id = create("SEG_C");
        post("/v1/treasury/alm/assumptions/" + id + "/approve", "{}", 200, "risk");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_id=" + id).contains("status=PENDING")
                .contains("resource_type=" + RESOURCE);
        verify(serviceClient, never()).post(eq("workflow-service"), startsWith("/v1/approval-requests/"), any(), anyString());
    }

    @Test
    void ftpCurveSubmissionIsPublishedOrRejectedFromWorkflow() throws Exception {
        String curve = post("/v1/treasury/ftp/curves", """
                {"code":"WF_BASE","currency":"USD","curve_type":"BASE","effective_date":"2026-01-01"}""", 201, "modeller")
                .get("id").asString();
        assertThat(post("/v1/treasury/ftp/curves/" + curve + "/submit", "{}", 422, "modeller").get("code").asString())
                .isEqualTo("curve_has_no_points");
        post("/v1/treasury/ftp/curve-points", """
                {"ftp_curve_id":"%s","tenor_days":30,"tenor_label":"1M","rate":"0.05"}""".formatted(curve), 201, "modeller");
        JsonNode submitted = post("/v1/treasury/ftp/curves/" + curve + "/submit", "{\"comment\":\"ALCO pack\"}", 200, "modeller");
        assertThat(submitted.get("submitted_by").asString()).isEqualTo("modeller");
        assertThat(lastRequest.get()).containsEntry("resource_type", "ftp_curve").containsEntry("resource_id", curve)
                .containsEntry("action", "publish");
        Map<?, ?> first = (Map<?, ?>) lastRequest.get().get("payload");
        assertThat(first.get("checksum")).isNotNull();
        assertThat((List<?>) first.get("points")).hasSize(1);

        // rejected: stays unpublished with the rejection recorded
        dispatcher.dispatch(UUID.fromString(TENANT), curveDecision(curve, first, "alco"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/treasury/ftp/curves/" + curve);
        assertThat(rejected.get("is_published").asBoolean()).isFalse();
        assertThat(rejected.get("rejected_by").asString()).isEqualTo("alco");
        assertThat(rejected.hasNonNull("submitted_at")).isFalse();

        // reworked points and resubmitted: the old decision is stale, the new one publishes (maker refused)
        post("/v1/treasury/ftp/curve-points", """
                {"ftp_curve_id":"%s","tenor_days":365,"tenor_label":"1Y","rate":"0.055"}""".formatted(curve), 201, "modeller");
        post("/v1/treasury/ftp/curves/" + curve + "/submit", "{}", 200, "modeller");
        Map<?, ?> second = (Map<?, ?>) lastRequest.get().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), curveDecision(curve, first, "alco"), Outcome.APPROVED);
        dispatcher.dispatch(UUID.fromString(TENANT), curveDecision(curve, second, "modeller"), Outcome.APPROVED);
        assertThat(get("/v1/treasury/ftp/curves/" + curve).get("is_published").asBoolean()).isFalse();
        dispatcher.dispatch(UUID.fromString(TENANT), curveDecision(curve, second, "alco"), Outcome.APPROVED);
        JsonNode published = get("/v1/treasury/ftp/curves/" + curve);
        assertThat(published.get("is_published").asBoolean()).isTrue();
        assertThat(published.get("approved_by").asString()).isEqualTo("alco");
        assertThat(post("/v1/treasury/ftp/curves/" + curve + "/reject", "{\"reason\":\"x\"}", 409, "alco").get("code")
                .asString()).isEqualTo("already_published");
    }

    @Test
    void directAssumptionRejectionKeepsFourEyes() throws Exception {
        String id = create("SEG_D");
        assertThat(post("/v1/treasury/alm/assumptions/" + id + "/reject", "{\"reason\":\"mine\"}", 422, "modeller")
                .get("code").asString()).isEqualTo("four_eyes_violation");
        JsonNode r = post("/v1/treasury/alm/assumptions/" + id + "/reject", "{\"reason\":\"decay too slow\"}", 200, "risk");
        assertThat(r.get("rejection_reason").asString()).isEqualTo("decay too slow");
        assertThat(post("/v1/treasury/alm/assumptions/" + id + "/reject", "{\"reason\":\"again\"}", 409, "risk")
                .get("code").asString()).isEqualTo("already_rejected");
    }

    private ObjectNode curveDecision(String curve, Map<?, ?> payload, String approver) {
        ObjectNode row = decision(curve, payload, approver);
        row.put("resource_type", "ftp_curve");
        row.put("action", "publish");
        return row;
    }

    private ObjectNode decision(String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", RESOURCE);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", "modeller");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String create(String segment) throws Exception {
        return post("/v1/treasury/alm/assumptions", """
                {"assumption_type":"DEPOSIT_DECAY","parameters":{"core_ratio":0.6,"decay_rate":0.2,"pass_through":0.5},
                 "segment":"%s","valid_from":"2025-01-01","model_version":"v1"}""".formatted(segment), 201, "modeller")
                .get("id").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
        HttpResponse<String> r = send("POST", path, body, UUID.randomUUID().toString(), user, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send("GET", path, null, null, "viewer", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> send(String method, String path, String body, String idemKey, String user,
                                      String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json");
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        if (ifMatch != null) {
            b.header("If-Match", "\"" + ifMatch + "\"");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
