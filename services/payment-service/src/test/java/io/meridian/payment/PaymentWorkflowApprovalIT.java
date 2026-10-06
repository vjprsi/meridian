package io.meridian.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.approvals.ApprovalDecision.Outcome;
import io.meridian.approvals.ApprovalEventsDispatcher;
import io.meridian.payment.domain.Iban;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Central maker-checker (G11) for payment-service: with {@code meridian.approvals.mode=workflow}, a payment or bulk
 * payment pending approval has an approval request in workflow-service (routed by amount) for its next approval;
 * decisions from workflow-service are applied with the approver as principal under the same four-eyes, distinct
 * approver and level rules. Downstream services are the {@link FakeBank}; workflow-service is stubbed here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.approvals.mode=workflow", "meridian.approvals.listener-enabled=true",
        "meridian.payment.ledger-id=0199b000-0000-7000-8000-0000000000ff",
        "meridian.payment.gl.clearing=0199b000-0000-7000-8000-0000000000c1",
        "meridian.payment.gl.fee-income=0199b000-0000-7000-8000-0000000000c2",
        "meridian.payment.gl.direct-debit-clearing=0199b000-0000-7000-8000-0000000000c3",
        "meridian.payment.fees.DOMESTIC=1.50"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentWorkflowApprovalIT {
    static final String TENANT = "0199b000-0000-7000-8000-00000000e0a1";
    static final UUID ALICE = UUID.fromString("0199b000-0000-7000-8000-00000000a0a1");
    static final String A_IBAN = Iban.of("GB", "MERI00000100000091");
    static final String EXT_IBAN = Iban.of("GB", "NWBK60161331926819");
    static final String DE_IBAN = Iban.of("DE", "370400440532013000");
    static final String MAKER = "maker";

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
    final List<String> searches = new CopyOnWriteArrayList<>();
    FakeBank bank;
    UUID a;

    @BeforeAll
    void world() {
        bank = new FakeBank(json);
        bank.customer(ALICE, "Alice", "Smith");
        a = bank.openAccount(ALICE, "GBP", A_IBAN, "5000000.00");
    }

    /** workflow-service is stubbed here; every other downstream service is the stateful FakeBank. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubServiceClient() {
        requests.clear();
        searches.clear();
        when(serviceClient.post(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                if ("/v1/approval-requests".equals(i.getArgument(1))) {
                    requests.add((Map<String, Object>) i.getArgument(2));
                }
                return json.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
            }
            return bank.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3));
        });
        when(serviceClient.get(anyString(), anyString())).thenAnswer(i -> {
            if ("workflow-service".equals(i.getArgument(0))) {
                searches.add(i.getArgument(1));
                return json.readTree("{\"data\":[]}");
            }
            return bank.get(i.getArgument(0), i.getArgument(1));
        });
    }

    @Test
    void paymentApprovedFromWorkflowUnderFourEyesAndRedeliveryIgnored() throws Exception {
        String id = post("/v1/payments", outbound("20000.00", "Frank Supplier Ltd"), MAKER, 201).get("id").asString();
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        Map<String, Object> req = last();
        assertThat(req).containsEntry("resource_type", "payment").containsEntry("resource_id", id)
                .containsEntry("action", "approve").containsEntry("currency", "GBP");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("20000");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");
        assertThat(payload.get("approvals_received")).isEqualTo(0);
        assertThat(payload.toString()).doesNotContain(EXT_IBAN).doesNotContain(A_IBAN);

        // the initiator approving in the inbox is still refused by the four-eyes rule
        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, payload, MAKER), Outcome.APPROVED);
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, payload, "checker1"), Outcome.APPROVED);
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("SUBMITTED");
        JsonNode level = get("/v1/payments/" + id + "/approvals").get("data").get(0);
        assertThat(level.get("approved_by").toString()).contains("checker1");
        assertThat(searches).anyMatch(s -> s.contains("resource_type=payment") && s.contains("resource_id=" + id));

        // redelivery of the same decision is harmless
        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, payload, "checker1"), Outcome.APPROVED);
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    void multiLevelPaymentProgressesThroughRequestsAndRejectionFromWorkflowRejects() throws Exception {
        String id = post("/v1/payments", outbound("120000.00", "Grace Holdings"), MAKER, 201).get("id").asString();
        Map<?, ?> first = (Map<?, ?>) last().get("payload");
        assertThat(first.get("levels")).isEqualTo(2L);

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, first, "checker1"), Outcome.APPROVED);
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");
        // the next approval is requested with the new progress
        Map<?, ?> second = (Map<?, ?>) last().get("payload");
        assertThat(last()).containsEntry("resource_id", id);
        assertThat(second.get("approvals_received")).isEqualTo(1);
        assertThat(second.get("approver_level")).isEqualTo(2);

        // a stale decision (earlier progress) is ignored, even a rejection
        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, first, "checker2"), Outcome.REJECTED);
        assertThat(get("/v1/payments/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("payment", id, second, "checker2"), Outcome.REJECTED);
        JsonNode rejected = get("/v1/payments/" + id);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("rejection_code").asString()).isEqualTo("AG01");
    }

    @Test
    void bulkPaymentApprovedFromWorkflowUnderFourEyes() throws Exception {
        String id = bulk("Payroll WF A");
        post("/v1/bulk-payments/" + id + "/submit", null, MAKER, 200);
        Map<String, Object> req = last();
        assertThat(req).containsEntry("resource_type", "bulk_payment").containsEntry("resource_id", id)
                .containsEntry("currency", "GBP");
        assertThat(new BigDecimal((String) req.get("amount"))).isEqualByComparingTo("350.50");
        Map<?, ?> payload = (Map<?, ?>) req.get("payload");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("bulk_payment", id, payload, MAKER), Outcome.APPROVED);
        assertThat(get("/v1/bulk-payments/" + id).get("status").asString()).isEqualTo("PENDING_APPROVAL");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("bulk_payment", id, payload, "checker1"), Outcome.APPROVED);
        String status = get("/v1/bulk-payments/" + id).get("status").asString();
        assertThat(status).isIn("PROCESSING", "COMPLETED", "PARTIALLY_COMPLETED");

        dispatcher.dispatch(UUID.fromString(TENANT), decision("bulk_payment", id, payload, "checker1"), Outcome.REJECTED);
        assertThat(get("/v1/bulk-payments/" + id).get("status").asString()).isEqualTo(status);
    }

    @Test
    void bulkRejectedFromWorkflowAndDirectDecisionClosesInbox() throws Exception {
        String id = bulk("Payroll WF B");
        post("/v1/bulk-payments/" + id + "/submit", null, MAKER, 200);
        Map<?, ?> payload = (Map<?, ?>) last().get("payload");
        dispatcher.dispatch(UUID.fromString(TENANT), decision("bulk_payment", id, payload, "checker1"), Outcome.REJECTED);
        assertThat(get("/v1/bulk-payments/" + id).get("status").asString()).isEqualTo("REJECTED");

        // a direct approval on the payment endpoint closes the open inbox item
        String pay = post("/v1/payments", outbound("15000.00", "Ivy Traders"), MAKER, 201).get("id").asString();
        searches.clear();
        assertThat(post("/v1/payments/" + pay + "/approve", "{}", "checker1", 200).get("status").asString())
                .isEqualTo("SUBMITTED");
        assertThat(searches).anyMatch(s -> s.contains("resource_id=" + pay) && s.contains("status=PENDING"));
    }

    private Map<String, Object> last() {
        assertThat(requests).isNotEmpty();
        return requests.getLast();
    }

    private ObjectNode decision(String type, String id, Map<?, ?> payload, String approver) {
        ObjectNode row = json.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("resource_type", type);
        row.put("resource_id", id);
        row.put("action", "approve");
        row.put("maker", MAKER);
        row.put("updated_by", approver);
        row.set("payload", json.valueToTree(payload));
        return row;
    }

    private String bulk(String name) throws Exception {
        String csv = "creditor_name,creditor_account_number,amount,currency,end_to_end_id,remittance\n"
                + "Ann Lee," + EXT_IBAN + ",100.00,GBP," + name.replace(' ', '-') + "-1,Salary\n"
                + "Ben Kim," + DE_IBAN + ",250.50,GBP," + name.replace(' ', '-') + "-2,Salary\n";
        JsonNode b = post("/v1/bulk-payments/upload", json.writeValueAsString(json.createObjectNode()
                .put("content", csv).put("file_format", "CSV").put("customer_id", ALICE.toString())
                .put("debtor_account_id", a.toString()).put("name", name)), MAKER, 201);
        assertThat(b.get("status").asString()).isEqualTo("VALIDATED");
        return b.get("id").asString();
    }

    private String outbound(String amount, String name) {
        return """
                {"payment_type":"DOMESTIC","direction":"OUTBOUND","channel":"WEB","debtor_account_id":"%s",
                 "creditor_name":"%s","creditor_account_id_type":"IBAN","creditor_account_number":"%s",
                 "amount":"%s","currency":"GBP","remittance_unstructured":"Invoice 42"}""".formatted(a, name, EXT_IBAN, amount);
    }

    private JsonNode post(String path, String body, String user, int expected) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", user).header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "ops").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
