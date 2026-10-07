package io.meridian.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.when;

import io.meridian.common.error.ApiException;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-08: data subject requests fan out from customer-service to the services holding personal data (their internal
 * data-lifecycle API, faked here through ServiceClient), with legal-hold and retention checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"meridian.events.relay-enabled=false", "meridian.security-mode=dev",
                "meridian.approvals.listener-enabled=false",
                "meridian.contact-lookup.key=test-contact-lookup-key-0123456789",
                "meridian.privacy.services=kyc-service,payment-service,wallet-service"})
@Import(MeridianTestContainers.class)
class PrivacyFanoutIT {
    static final String TENANT = "0199a000-0000-7000-8000-0000000000f1";
    static final String LEGAL_ENTITY = "0199a000-0000-7000-8000-0000000000e1";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, JsonNode> eraseCalls = new ConcurrentHashMap<>();
    final Set<String> exportCalls = ConcurrentHashMap.newKeySet();
    final Set<String> heldCustomers = ConcurrentHashMap.newKeySet();
    volatile String failingService;
    volatile boolean allCallsAsService = true;

    @BeforeEach
    void fakes() {
        eraseCalls.clear();
        exportCalls.clear();
        failingService = null;
        when(serviceClient.post(eq("document-service"), eq("/v1/documents"), any(), anyString()))
                .thenAnswer(inv -> json.readTree("{\"id\":\"" + UUID.randomUUID() + "\"}"));
        when(serviceClient.post(eq("document-service"), matches("/v1/documents/.*/upload-url"), any(), anyString()))
                .thenAnswer(inv -> json.createObjectNode());
        when(serviceClient.get(eq("archive-service"), matches("/v1/archive/legal-holds/check.*"))).thenAnswer(inv -> {
            allCallsAsService &= ServiceClient.asServiceActive();
            String path = inv.getArgument(1);
            boolean held = heldCustomers.stream().anyMatch(path::contains);
            return json.readTree(held ? "{\"on_hold\":true,\"holds\":[{\"legal_hold_id\":\"h1\",\"name\":\"Matter 42\"}]}"
                    : "{\"on_hold\":false,\"holds\":[]}");
        });
        when(serviceClient.post(anyString(), eq("/v1/internal/data-lifecycle/export"), any(), anyString())).thenAnswer(inv -> {
            String svc = inv.getArgument(0);
            allCallsAsService &= ServiceClient.asServiceActive();
            if (svc.equals(failingService)) {
                throw new ApiException(502, "upstream_error", svc + " is not reachable");
            }
            exportCalls.add(svc);
            return json.readTree("{\"service\":\"" + svc + "\",\"tables\":{\"t_" + svc.replace('-', '_') + "\":[{\"id\":\"x\"}]}}");
        });
        when(serviceClient.post(anyString(), eq("/v1/internal/data-lifecycle/erase"), any(), anyString())).thenAnswer(inv -> {
            String svc = inv.getArgument(0);
            allCallsAsService &= ServiceClient.asServiceActive();
            if (svc.equals(failingService)) {
                throw new ApiException(502, "upstream_error", svc + " is not reachable");
            }
            eraseCalls.put(svc, json.valueToTree(inv.getArgument(2)));
            return "payment-service".equals(svc)
                    ? json.readTree("""
                        {"tables":[{"table":"payee","action":"ANONYMISED","records":2},
                                   {"table":"payment","action":"RETAINED","records":5,"lawful_basis":"LEGAL_OBLIGATION",
                                    "reason":"AML/CFT and accounting: payment records"}]}""")
                    : json.readTree("{\"tables\":[{\"table\":\"x\",\"action\":\"ANONYMISED\",\"records\":1}]}");
        });
    }

    @Test
    void accessExportCollectsEveryServiceAndFailsWhenOneCannotAnswer() throws Exception {
        String customer = customer("PRV-1");
        String request = verified(customer, "ACCESS");
        failingService = "wallet-service";
        JsonNode failed = post("/v1/privacy-requests/" + request + "/execute", null, 502);
        assertThat(failed.get("code").asString()).isEqualTo("export_incomplete");
        assertThat(get("/v1/privacy-requests/" + request).get("status").asString()).isEqualTo("IN_PROGRESS");

        failingService = null;
        JsonNode done = post("/v1/privacy-requests/" + request + "/execute", null, 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(exportCalls).containsExactlyInAnyOrder("kyc-service", "payment-service", "wallet-service");
        assertThat(allCallsAsService).isTrue();
    }

    @Test
    void legalHoldBlocksErasureEverywhere() throws Exception {
        String customer = customer("PRV-2");
        heldCustomers.add(customer);
        String request = verified(customer, "ERASURE");
        JsonNode done = post("/v1/privacy-requests/" + request + "/execute", null, 200);
        assertThat(done.get("status").asString()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(done.get("retention_blockers").toString()).contains("LEGAL_HOLD").contains("Matter 42");
        assertThat(eraseCalls).isEmpty();
        assertThat(get("/v1/customers/" + customer).get("display_name").asString()).isNotEqualTo("ERASED");
    }

    @Test
    void erasureOfAFormerProspectReachesEveryServiceAndReportsRetainedAndUnreachableData() throws Exception {
        String customer = customer("PRV-3");
        String request = verified(customer, "ERASURE");
        failingService = "kyc-service";
        JsonNode done = post("/v1/privacy-requests/" + request + "/execute", null, 200);
        assertThat(done.get("status").asString()).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(eraseCalls.keySet()).containsExactlyInAnyOrder("payment-service", "wallet-service");
        // never onboarded: no statutory retention of the relationship applies
        assertThat(eraseCalls.get("wallet-service").get("within_retention").asBoolean()).isFalse();
        assertThat(eraseCalls.get("wallet-service").get("customer_id").asString()).isEqualTo(customer);
        String blockers = done.get("retention_blockers").toString();
        assertThat(blockers).contains("payment-service/payment").contains("AML/CFT and accounting");
        assertThat(blockers).contains("kyc-service").contains("NOT_EXECUTED");
        assertThat(blockers).doesNotContain("payee");
        assertThat(get("/v1/customers/" + customer).get("display_name").asString()).isEqualTo("ERASED");
        assertThat(allCallsAsService).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private String customer(String ref) throws Exception {
        return post("/v1/customers", """
                {"external_ref":"%s","customer_type":"INDIVIDUAL","legal_entity_id":"%s",
                 "first_name":"Pia","last_name":"Private","date_of_birth":"1990-01-01","residence_country":"GB"}"""
                .formatted(ref + "-" + UUID.randomUUID().toString().substring(0, 8), LEGAL_ENTITY), 201).get("id").asString();
    }

    private String verified(String customer, String type) throws Exception {
        String id = post("/v1/privacy-requests", """
                {"customer_id":"%s","request_type":"%s","due_at":"%s","channel":"WEB"}"""
                .formatted(customer, type, OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)), 201).get("id").asString();
        post("/v1/privacy-requests/" + id + "/verify-identity", "{\"method\":\"BRANCH\"}", 200);
        return id;
    }

    private JsonNode post(String path, String body, int expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "dpo").header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT).header("X-User", "dpo").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(path + " -> " + r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }
}
