package io.meridian.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Central maker-checker (G11): with {@code meridian.approvals.mode=workflow}, an uploaded KYC document opens a review
 * request in workflow-service, and the decision arriving from workflow-service is applied with the reviewer as
 * principal, still under the four-eyes rule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class KycWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c9";
    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubOtherServices() {
        when(serviceClient.get(eq("customer-service"), anyString())).thenAnswer(inv -> {
            String path = inv.getArgument(1);
            String id = path.substring(path.lastIndexOf('/') + 1);
            return json.readTree("{\"id\":\"" + id + "\",\"display_name\":\"Workflow Customer\"}");
        });
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void uploadOpensWorkflowRequestAndApprovalFromWorkflowAccepts() throws Exception {
        String doc = uploadedDocument();
        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "kyc_document").containsEntry("resource_id", doc)
                .containsEntry("action", "accept");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("document_type")).isEqualTo("PASSPORT");
        assertThat(payload.get("document_id")).isNotNull();

        // the uploader accepting in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision(doc, payload, "maker.anna"), Outcome.APPROVED);
        assertThat(get("/v1/kyc/documents/" + doc).get("status").asString()).isEqualTo("UPLOADED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(doc, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/kyc/documents/" + doc).get("status").asString()).isEqualTo("ACCEPTED");

        // redelivery is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision(doc, payload, "checker.ben"), Outcome.APPROVED);
        assertThat(get("/v1/kyc/documents/" + doc).get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void rejectionFromWorkflowRejectsAndStaleDecisionIsIgnored() throws Exception {
        String doc = uploadedDocument();
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // a decision about a different document reference is ignored
        ObjectNode stale = decision(doc, payload, "checker.ben");
        ((ObjectNode) stale.get("payload")).put("document_id", UUID.randomUUID().toString());
        dispatcher.dispatch(UUID.fromString(TENANT), stale, Outcome.APPROVED);
        assertThat(get("/v1/kyc/documents/" + doc).get("status").asString()).isEqualTo("UPLOADED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(doc, payload, "checker.ben"), Outcome.REJECTED);
        JsonNode d = get("/v1/kyc/documents/" + doc);
        assertThat(d.get("status").asString()).isEqualTo("REJECTED");
        assertThat(d.get("rejection_reason").asString()).startsWith("rejected in workflow request");

        // a late approval after the rejection does nothing
        dispatcher.dispatch(UUID.fromString(TENANT), decision(doc, payload, "checker.cy"), Outcome.APPROVED);
        assertThat(get("/v1/kyc/documents/" + doc).get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void directReviewClosesTheInboxItemAfterCommit() throws Exception {
        String doc = uploadedDocument();
        post("/v1/kyc/documents/" + doc + "/accept", "{}", 200, "checker.ben");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(serviceClient).get(eq("workflow-service"), path.capture());
        assertThat(path.getValue()).contains("resource_type=kyc_document").contains("resource_id=" + doc);
    }

    private ObjectNode decision(String doc, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", "kyc_document");
        row.put("resource_id", doc);
        row.put("action", "accept");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String uploadedDocument() throws Exception {
        String customer = UUID.randomUUID().toString();
        String profile = post("/v1/kyc/profiles", "{\"customer_id\":\"" + customer + "\"}", 201, "maker.anna")
                .get("id").asString();
        return post("/v1/kyc/documents", """
                {"kyc_profile_id":"%s","customer_id":"%s","document_type":"PASSPORT","document_id":"%s",
                 "issuing_country":"GB","issue_date":"%s","expiry_date":"%s"}"""
                .formatted(profile, customer, UUID.randomUUID(), TODAY.minusYears(1), TODAY.plusYears(5)), 201, "maker.anna")
                .get("id").asString();
    }

    private JsonNode post(String path, String body, int expected, String user) throws Exception {
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
