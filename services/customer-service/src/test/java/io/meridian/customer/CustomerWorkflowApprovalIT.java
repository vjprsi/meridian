package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Central maker-checker (G11) for KYC tier upgrades: the upgrade opens an approval request in workflow-service through
 * the shared library, and the decision arriving from workflow-service is applied by {@code CustomerTierApprovalHandler}
 * with the approver as principal, under the four-eyes rule and only for the request still linked to the customer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true"})
@Import(MeridianTestContainers.class)
class CustomerWorkflowApprovalIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000c8";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-0000000000e1";

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
    final AtomicReference<String> lastRequestId = new AtomicReference<>();
    final List<String> sentMessages = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubOtherServices() {
        when(serviceClient.post(eq("notification-service"), eq("/v1/notifications"), any(), anyString())).thenAnswer(inv -> {
            Map<?, ?> body = inv.getArgument(2);
            sentMessages.add(String.valueOf(body.get("body")));
            return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"QUEUED\"}");
        });
        when(serviceClient.post(eq("document-service"), eq("/v1/documents"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
        when(serviceClient.post(eq("document-service"), matches("/v1/documents/.*/upload-url"), any(), anyString()))
                .thenAnswer(inv -> json.createObjectNode());
        when(serviceClient.post(eq("workflow-service"), eq("/v1/approval-requests"), any(), anyString())).thenAnswer(inv -> {
            lastRequest.set((Map<String, Object>) inv.getArgument(2));
            lastRequestId.set(UUID.randomUUID().toString());
            return json.readTree("{\"id\":\"" + lastRequestId.get() + "\",\"status\":\"PENDING\"}");
        });
        when(serviceClient.get(eq("workflow-service"), startsWith("/v1/approval-requests?")))
                .thenReturn(json.readTree("{\"data\":[]}"));
    }

    @Test
    void upgradeOpensWorkflowRequestAndApprovalFromWorkflowAppliesTier() throws Exception {
        String customer = customerWithVerifiedPhone("Ada", "+447700900201");
        JsonNode pending = post("/v1/customers/" + customer + "/change-tier",
                "{\"to_tier\":\"TIER_1\",\"reason\":\"phone verified\"}", 202, "maker.anna");
        assertThat(pending.get("pending_tier").asString()).isEqualTo("TIER_1");

        Map<String, Object> req = lastRequest.get();
        assertThat(req).containsEntry("resource_type", "customer_kyc_tier").containsEntry("resource_id", customer)
                .containsEntry("action", "change_tier");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("to_tier")).isEqualTo("TIER_1");
        assertThat(payload.get("requested_by")).isEqualTo("maker.anna");
        assertThat(pending.get("tier_approval_request_id").asString()).isEqualTo(lastRequestId.get());

        ObjectNode approved = decision(lastRequestId.get(), customer, payload, "checker.ben");
        dispatcher.dispatch(UUID.fromString(TENANT), approved, Outcome.APPROVED);
        JsonNode c = get("/v1/customers/" + customer);
        assertThat(c.get("kyc_tier").asString()).isEqualTo("TIER_1");
        assertThat(c.hasNonNull("pending_tier")).isFalse();

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), approved, Outcome.APPROVED);
        assertThat(get("/v1/customers/" + customer + "/tier-history").get("data")).hasSize(1);
    }

    @Test
    void rejectionFromWorkflowDropsUpgradeAndStaleDecisionIsIgnored() throws Exception {
        String customer = customerWithVerifiedPhone("Bea", "+447700900202");
        post("/v1/customers/" + customer + "/change-tier", "{\"to_tier\":\"TIER_1\",\"reason\":\"first\"}", 202, "maker.anna");
        String first = lastRequestId.get();
        Map<?, ?> firstPayload = (Map<?, ?>) lastRequest.get().get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision(first, customer, firstPayload, "checker.ben"), Outcome.REJECTED);
        JsonNode c = get("/v1/customers/" + customer);
        assertThat(c.get("kyc_tier").asString()).isEqualTo("TIER_0");
        assertThat(c.hasNonNull("pending_tier")).isFalse();

        // a late approval of the rejected request, after a new request, does nothing
        post("/v1/customers/" + customer + "/change-tier", "{\"to_tier\":\"TIER_1\",\"reason\":\"second\"}", 202, "maker.anna");
        String second = lastRequestId.get();
        dispatcher.dispatch(UUID.fromString(TENANT), decision(first, customer, firstPayload, "checker.ben"), Outcome.APPROVED);
        c = get("/v1/customers/" + customer);
        assertThat(c.get("kyc_tier").asString()).isEqualTo("TIER_0");
        assertThat(c.get("pending_tier").asString()).isEqualTo("TIER_1");
        assertThat(c.get("tier_approval_request_id").asString()).isEqualTo(second);
    }

    @Test
    void fourEyesStillEnforcedForDecisionsFromWorkflow() throws Exception {
        String customer = customerWithVerifiedPhone("Cy", "+447700900203");
        post("/v1/customers/" + customer + "/change-tier", "{\"to_tier\":\"TIER_1\",\"reason\":\"x\"}", 202, "maker.anna");
        Map<?, ?> payload = (Map<?, ?>) lastRequest.get().get("payload");

        // the requester approving their own upgrade in the inbox is not applied
        dispatcher.dispatch(UUID.fromString(TENANT), decision(lastRequestId.get(), customer, payload, "maker.anna"),
                Outcome.APPROVED);
        JsonNode c = get("/v1/customers/" + customer);
        assertThat(c.get("kyc_tier").asString()).isEqualTo("TIER_0");
        assertThat(get("/v1/customers/" + customer + "/tier-history").get("data")).isEmpty();
    }

    private ObjectNode decision(String requestId, String customer, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", requestId);
        row.put("resource_type", "customer_kyc_tier");
        row.put("resource_id", customer);
        row.put("action", "change_tier");
        row.put("maker", "maker.anna");
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String customerWithVerifiedPhone(String name, String phone) throws Exception {
        String id = post("/v1/customers", """
                {"customer_type":"INDIVIDUAL","first_name":"%s","last_name":"Workflow","date_of_birth":"1990-01-01",
                 "nationality":"GB","legal_entity_id":"%s"}""".formatted(name, LEGAL_ENTITY), 201, "maker.anna")
                .get("id").asString();
        String contact = post("/v1/customers/" + id + "/contacts", """
                {"customer_id":"%s","contact_type":"MOBILE","phone":"%s","is_primary":true}""".formatted(id, phone),
                201, "maker.anna").get("id").asString();
        sentMessages.clear();
        post("/v1/customers/" + id + "/contacts/" + contact + "/send-verification", null, 200, "maker.anna");
        Matcher m = Pattern.compile("(\\d{6})").matcher(sentMessages.getLast());
        assertThat(m.find()).isTrue();
        post("/v1/customers/" + id + "/contacts/" + contact + "/verify", "{\"code\":\"" + m.group(1) + "\"}", 200, "maker.anna");
        return id;
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
