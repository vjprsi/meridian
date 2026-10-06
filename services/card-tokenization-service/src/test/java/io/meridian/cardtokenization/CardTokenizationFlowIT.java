package io.meridian.cardtokenization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.cardtokenization.domain.Crypto;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * card-tokenization-service end to end over HTTP against PostgreSQL 18 (RLS enforced), with card-service, the vault
 * and notification-service behind a mocked ServiceClient ({@link FakeCardPlatform}). Covers push provisioning, the
 * signed token-service callbacks (green / yellow / red paths, lifecycle notifications), OTP step-up, issuer lifecycle
 * actions, card status propagation, idempotency, tenant isolation and the absence of PAN data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev",
        "meridian.cards.callback-secret=test-secret", "meridian.cards.tokens.max-per-card=3"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardTokenizationFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000e101";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000e102";
    static final String SECRET = "test-secret";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeCardPlatform platform;

    @BeforeEach
    void wire() {
        platform = new FakeCardPlatform(json);
        when(serviceClient.get(anyString(), anyString()))
                .thenAnswer(i -> platform.get(i.getArgument(0), i.getArgument(1)));
        when(serviceClient.post(anyString(), anyString(), any(), any()))
                .thenAnswer(i -> platform.post(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
    }

    // ------------------------------------------------------------------ push provisioning

    @Test
    void pushProvisioningActivatesAGreenTokenAndIsIdempotent() {
        UUID card = platform.addCard(pan(), "ACTIVE", 9, 2030);
        String body = """
                {"card_id":"%s","token_requestor":"APPLE_PAY","token_requestor_id":"40010030273",
                 "device_info":{"device_type":"PHONE","device_name":"Ana's iPhone","device_id":"dev-123"}}""".formatted(card);
        String key = "push-" + UUID.randomUUID();
        HttpResponse<String> first = send("POST", "/v1/card-tokens/push-provision", body, TENANT, key, null);
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode token = json.readTree(first.body());
        assertThat(token.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(token.get("provisioning_decision").asString()).isEqualTo("GREEN");
        assertThat(token.get("network_token_ref").asString()).startsWith("DNIT").hasSize(32);
        assertThat(token.get("dpan_last4").asString()).hasSize(4);
        assertThat(token.get("dpan_expiry_year").asInt()).isEqualTo(2030);
        assertThat(token.get("device_id_hash").asString()).hasSize(64);
        assertThat(token.has("otp_hash")).isFalse();
        String opened = Crypto.open("dev-token-payload-key", token.get("provisioning_payload").asString());
        assertThat(json.readTree(opened).get("card_id").asString()).isEqualTo(card.toString());

        HttpResponse<String> replay = send("POST", "/v1/card-tokens/push-provision", body, TENANT, key, null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json.readTree(replay.body()).get("id").asString()).isEqualTo(token.get("id").asString());
        assertThat(send("POST", "/v1/card-tokens/push-provision", body.replace("APPLE_PAY", "GOOGLE_PAY"), TENANT, key,
                null).statusCode()).isEqualTo(422);

        JsonNode events = get("/v1/card-tokens/" + token.get("id").asString() + "/events", TENANT);
        assertThat(types(events)).containsExactlyInAnyOrder("PROVISIONING_REQUESTED", "PROVISIONING_APPROVED", "ACTIVATED");
        assertThat(outboxTypes(token.get("id").asString())).containsExactly("cards.card_token.provisioned");
    }

    @Test
    void pushProvisioningRejectsInactiveCardsAndEnforcesTheTokenLimit() {
        UUID frozen = platform.addCard(pan(), "FROZEN", 1, 2031);
        assertThat(code(post("/v1/card-tokens/push-provision", push(frozen), 422))).isEqualTo("card_not_active");
        UUID card = platform.addCard(pan(), "ACTIVE", 1, 2031);
        for (int i = 0; i < 3; i++) {
            post("/v1/card-tokens/push-provision", push(card), 200);
        }
        assertThat(code(post("/v1/card-tokens/push-provision", push(card), 422))).isEqualTo("token_limit_reached");
        assertThat(code(post("/v1/card-tokens/push-provision", push(UUID.randomUUID()), 422))).isEqualTo("card_not_found");
    }

    // ------------------------------------------------------------------ token service callbacks

    @Test
    void networkGreenPathActivatesAndDeduplicatesAndNeverStoresThePan() {
        String pan = pan();
        UUID card = platform.addCard(pan, "ACTIVE", 6, 2029);
        String body = provision(pan, 6, 2029, "DNITGREEN" + UUID.randomUUID().toString().substring(0, 8), 5, 5);
        JsonNode token = callback("/v1/card-tokens/network/provision-requests", body, 200);
        assertThat(token.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(token.get("provisioning_decision").asString()).isEqualTo("GREEN");
        assertThat(token.get("card_id").asString()).isEqualTo(card.toString());
        assertThat(token.get("activated_at")).isNotNull();

        JsonNode again = callback("/v1/card-tokens/network/provision-requests", body, 200);
        assertThat(again.get("id").asString()).isEqualTo(token.get("id").asString());
        assertThat(platform.calls.stream().filter(c -> c.path().equals("/v1/card-vault/lookup")).count()).isEqualTo(1);

        // the PAN is never stored: not in tokens, token events or outbox payloads
        String digits = pan;
        assertThat(asTenant(() -> jdbc.sql("""
                select (select count(*) from card_token t where row_to_json(t)::text like ?)
                     + (select count(*) from card_token_event e where row_to_json(e)::text like ?)
                     + (select count(*) from outbox o where o.payload::text like ?)""")
                .params("%" + digits + "%", "%" + digits + "%", "%" + digits + "%").query(Long.class).single()))
                .isZero();
    }

    @Test
    void callbacksRequireAValidSignature() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", 6, 2029);
        String body = provision(pan, 6, 2029, "DNITSIG" + UUID.randomUUID().toString().substring(0, 8), 5, 5);
        HttpResponse<String> unsigned = send("POST", "/v1/card-tokens/network/provision-requests?tenant_id=" + TENANT,
                body, null, null, null);
        assertThat(unsigned.statusCode()).isEqualTo(401);
        HttpResponse<String> forged = send("POST", "/v1/card-tokens/network/provision-requests?tenant_id=" + TENANT,
                body, null, null, Crypto.hmacHex("wrong", body.getBytes(StandardCharsets.UTF_8)));
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(json.readTree(forged.body()).get("code").asString()).isEqualTo("invalid_signature");
        HttpResponse<String> tampered = send("POST", "/v1/card-tokens/network/provision-requests?tenant_id=" + TENANT,
                body.replace("\"device_score\":5", "\"device_score\":4"), null, null, sign(body));
        assertThat(tampered.statusCode()).isEqualTo(401);
    }

    @Test
    void yellowPathStepUpWithWrongThenRightOtp() {
        String pan = pan();
        UUID card = platform.addCard(pan, "ACTIVE", 3, 2030);
        JsonNode token = callback("/v1/card-tokens/network/provision-requests",
                provision(pan, 3, 2030, "DNITYEL" + UUID.randomUUID().toString().substring(0, 8), 3, 4), 200);
        assertThat(token.get("status").asString()).isEqualTo("PENDING_VERIFICATION");
        assertThat(token.get("provisioning_decision").asString()).isEqualTo("YELLOW");
        assertThat(token.get("step_up_method").asString()).isEqualTo("OTP_SMS");
        JsonNode sms = platform.notifications.getLast();
        assertThat(sms.get("template_code").asString()).isEqualTo("CARD_TOKEN_STEP_UP_OTP");
        assertThat(sms.get("recipient_id").asString()).isEqualTo(platform.get("card-service", "/v1/cards/" + card)
                .get("customer_id").asString());
        String otp = platform.lastOtp();
        String id = token.get("id").asString();
        assertThat(outboxTypes(id)).containsExactly("cards.card_token.step_up_required");

        String wrong = otp.equals("000000") ? "111111" : "000000";
        assertThat(code(post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + wrong + "\"}", 422)))
                .isEqualTo("invalid_otp");
        assertThat(get("/v1/card-tokens/" + id, TENANT).get("step_up_attempts").asInt()).isEqualTo(1);
        JsonNode active = post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + otp + "\"}", 200);
        assertThat(active.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.get("activated_at")).isNotNull();
        assertThat(code(post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + otp + "\"}", 409)))
                .isEqualTo("invalid_transition");
        assertThat(types(get("/v1/card-tokens/" + id + "/events", TENANT)))
                .contains("STEP_UP_SENT", "STEP_UP_FAILED", "STEP_UP_VERIFIED", "ACTIVATED");
        assertThat(outboxTypes(id)).containsExactly("cards.card_token.step_up_required", "cards.card_token.provisioned");
    }

    @Test
    void yellowPathDeclinesAfterThreeWrongOtpsAndRejectsExpiredOtps() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", 3, 2030);
        String id = callback("/v1/card-tokens/network/provision-requests",
                provision(pan, 3, 2030, "DNITX" + UUID.randomUUID().toString().substring(0, 8), null, null), 200)
                .get("id").asString();
        String wrong = platform.lastOtp().equals("999999") ? "888888" : "999999";
        assertThat(code(post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + wrong + "\"}", 422))).isEqualTo("invalid_otp");
        assertThat(code(post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + wrong + "\"}", 422))).isEqualTo("invalid_otp");
        assertThat(code(post("/v1/card-tokens/" + id + "/verify-step-up", "{\"otp\":\"" + wrong + "\"}", 422)))
                .isEqualTo("step_up_attempts_exceeded");
        JsonNode declined = get("/v1/card-tokens/" + id, TENANT);
        assertThat(declined.get("status").asString()).isEqualTo("DECLINED");
        assertThat(declined.get("status_reason").asString()).isEqualTo("STEP_UP_ATTEMPTS_EXCEEDED");
        assertThat(outboxTypes(id)).containsExactly("cards.card_token.step_up_required", "cards.card_token.declined");

        String pan2 = pan();
        platform.addCard(pan2, "ACTIVE", 3, 2030);
        String id2 = callback("/v1/card-tokens/network/provision-requests",
                provision(pan2, 3, 2030, "DNITE" + UUID.randomUUID().toString().substring(0, 8), 2, 5), 200)
                .get("id").asString();
        String otp = platform.lastOtp();
        asTenant(() -> jdbc.sql("update card_token set otp_expires_at = now() - interval '1 minute' where id = ?::uuid")
                .param(id2).update());
        assertThat(code(post("/v1/card-tokens/" + id2 + "/verify-step-up", "{\"otp\":\"" + otp + "\"}", 422)))
                .isEqualTo("otp_expired");
    }

    @Test
    void redPathDeclinesOnExpiryMismatchLowScoresAndInactiveCards() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", 3, 2030);
        JsonNode mismatch = callback("/v1/card-tokens/network/provision-requests",
                provision(pan, 4, 2030, "DNITR1" + UUID.randomUUID().toString().substring(0, 8), 5, 5), 200);
        assertThat(mismatch.get("status").asString()).isEqualTo("DECLINED");
        assertThat(mismatch.get("provisioning_decision").asString()).isEqualTo("RED");
        assertThat(mismatch.get("status_reason").asString()).isEqualTo("EXPIRY_MISMATCH");
        assertThat(outboxTypes(mismatch.get("id").asString())).containsExactly("cards.card_token.declined");

        JsonNode risky = callback("/v1/card-tokens/network/provision-requests",
                provision(pan, 3, 2030, "DNITR2" + UUID.randomUUID().toString().substring(0, 8), 1, 5), 200);
        assertThat(risky.get("status_reason").asString()).isEqualTo("HIGH_RISK_SCORE");

        String blockedPan = pan();
        platform.addCard(blockedPan, "BLOCKED", 3, 2030);
        assertThat(callback("/v1/card-tokens/network/provision-requests",
                provision(blockedPan, 3, 2030, "DNITR3" + UUID.randomUUID().toString().substring(0, 8), 5, 5), 200)
                .get("status_reason").asString()).isEqualTo("CARD_NOT_ACTIVE");

        HttpResponse<String> unknown = sendSigned("/v1/card-tokens/network/provision-requests",
                provision("4000000000000002", 3, 2030, "DNITR4" + UUID.randomUUID().toString().substring(0, 8), 5, 5));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).get("code").asString()).isEqualTo("card_not_found");
    }

    @Test
    void networkLifecycleNotificationsFollowTheStateMachine() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", 6, 2029);
        String ref = "DNITLC" + UUID.randomUUID().toString().substring(0, 8);
        String id = callback("/v1/card-tokens/network/provision-requests", provision(pan, 6, 2029, ref, 5, 5), 200)
                .get("id").asString();
        assertThat(callback("/v1/card-tokens/network/events", lifecycle(ref, "SUSPENDED"), 200)
                .get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(callback("/v1/card-tokens/network/events", lifecycle(ref, "SUSPENDED"), 200)
                .get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(callback("/v1/card-tokens/network/events", lifecycle(ref, "RESUMED"), 200)
                .get("status").asString()).isEqualTo("ACTIVE");
        callback("/v1/card-tokens/network/events", lifecycle(ref, "PAN_UPDATED"), 200);
        assertThat(callback("/v1/card-tokens/network/events", lifecycle(ref, "DEACTIVATED"), 200)
                .get("deactivated_at")).isNotNull();
        assertThat(json.readTree(sendSigned("/v1/card-tokens/network/events", lifecycle(ref, "RESUMED")).body())
                .get("code").asString()).isEqualTo("invalid_transition");
        assertThat(sendSigned("/v1/card-tokens/network/events", lifecycle("DNITNOPE", "SUSPENDED")).statusCode())
                .isEqualTo(404);
        assertThat(sendSigned("/v1/card-tokens/network/events", lifecycle(ref, "STEP_UP_SENT")).statusCode())
                .isEqualTo(422);
        JsonNode events = get("/v1/card-tokens/" + id + "/events", TENANT);
        assertThat(types(events)).contains("SUSPENDED", "RESUMED", "PAN_UPDATED", "DEACTIVATED");
        assertThat(outboxTypes(id)).containsExactly("cards.card_token.provisioned", "cards.card_token.status_changed",
                "cards.card_token.status_changed", "cards.card_token.status_changed");
    }

    // ------------------------------------------------------------------ issuer lifecycle and card status sync

    @Test
    void issuerSuspendResumeDeactivateWithInvalidTransitions() {
        UUID card = platform.addCard(pan(), "ACTIVE", 1, 2032);
        String id = post("/v1/card-tokens/push-provision", push(card), 200).get("id").asString();
        assertThat(code(post("/v1/card-tokens/" + id + "/resume", null, 409))).isEqualTo("invalid_transition");
        JsonNode suspended = post("/v1/card-tokens/" + id + "/suspend", "{\"reason\":\"lost phone\"}", 200);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("status_reason").asString()).isEqualTo("lost phone");
        assertThat(suspended.get("suspended_at")).isNotNull();
        assertThat(post("/v1/card-tokens/" + id + "/resume", null, 200).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(post("/v1/card-tokens/" + id + "/deactivate", "{}", 200).get("status").asString()).isEqualTo("DEACTIVATED");
        assertThat(code(post("/v1/card-tokens/" + id + "/suspend", "{}", 409))).isEqualTo("invalid_transition");
        assertThat(send("POST", "/v1/card-tokens/" + id + "/suspend", "{}", TENANT, null, null).statusCode()).isEqualTo(400);
    }

    @Test
    void cardStatusChangesPropagateToTokens() {
        UUID card = platform.addCard(pan(), "ACTIVE", 1, 2032);
        String a = post("/v1/card-tokens/push-provision", push(card), 200).get("id").asString();
        String b = post("/v1/card-tokens/push-provision", push(card), 200).get("id").asString();
        post("/v1/card-tokens/" + b + "/suspend", "{\"reason\":\"cardholder request\"}", 200);

        JsonNode frozen = post("/v1/card-tokens/card-status-sync", sync(card, "FROZEN"), 200);
        assertThat(frozen.get("updated").asInt()).isEqualTo(1);
        assertThat(get("/v1/card-tokens/" + a, TENANT).get("status").asString()).isEqualTo("SUSPENDED");
        JsonNode unfrozen = post("/v1/card-tokens/card-status-sync", sync(card, "ACTIVE"), 200);
        assertThat(unfrozen.get("updated").asInt()).isEqualTo(1);
        assertThat(get("/v1/card-tokens/" + a, TENANT).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(get("/v1/card-tokens/" + b, TENANT).get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(post("/v1/card-tokens/card-status-sync", sync(card, "CREATED"), 200).get("updated").asInt()).isZero();
        assertThat(post("/v1/card-tokens/card-status-sync", sync(card, "CLOSED"), 200).get("updated").asInt()).isEqualTo(2);
        JsonNode list = get("/v1/card-tokens?card_id=" + card, TENANT);
        assertThat(list.get("data")).allSatisfy(t -> assertThat(t.get("status").asString()).isEqualTo("DEACTIVATED"));
    }

    @Test
    void tenantsAreIsolated() {
        UUID card = platform.addCard(pan(), "ACTIVE", 1, 2032);
        String id = post("/v1/card-tokens/push-provision", push(card), 200).get("id").asString();
        assertThat(send("GET", "/v1/card-tokens/" + id, null, OTHER_TENANT, null, null).statusCode()).isEqualTo(404);
        assertThat(get("/v1/card-tokens?card_id=" + card, OTHER_TENANT).get("data")).isEmpty();
        assertThat(send("POST", "/v1/card-tokens/" + id + "/suspend", "{}", OTHER_TENANT, "k-" + UUID.randomUUID(), null)
                .statusCode()).isEqualTo(404);
        assertThat(get("/v1/card-tokens?card_id=" + card, TENANT).get("data")).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    static int panSeq = 100;

    /** A distinct Luhn-valid test PAN. */
    static synchronized String pan() {
        String base = "411111" + String.format("%09d", panSeq++);
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int d = base.charAt(base.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return base + (10 - sum % 10) % 10;
    }

    static String push(UUID card) {
        return "{\"card_id\":\"" + card + "\",\"token_requestor\":\"GOOGLE_PAY\",\"device_info\":{\"device_type\":\"PHONE\"}}";
    }

    static String sync(UUID card, String status) {
        return "{\"card_id\":\"" + card + "\",\"card_status\":\"" + status + "\"}";
    }

    static String provision(String pan, int month, int year, String ref, Integer wallet, Integer device) {
        return "{\"pan\":\"" + pan + "\",\"expiry_month\":" + month + ",\"expiry_year\":" + year
                + ",\"token_requestor\":\"APPLE_PAY\",\"network_token_ref\":\"" + ref + "\",\"dpan_last4\":\"9876\""
                + ",\"device_type\":\"WATCH\"" + (wallet == null ? "" : ",\"wallet_account_score\":" + wallet)
                + (device == null ? "" : ",\"device_score\":" + device) + "}";
    }

    static String lifecycle(String ref, String type) {
        return "{\"network_token_ref\":\"" + ref + "\",\"event_type\":\"" + type + "\",\"reason\":\"scheme\"}";
    }

    static String sign(String body) {
        return Crypto.hmacHex(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    JsonNode callback(String path, String body, int expected) {
        HttpResponse<String> r = sendSigned(path, body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    HttpResponse<String> sendSigned(String path, String body) {
        return send("POST", path + "?tenant_id=" + TENANT, body, null, null, sign(body));
    }

    JsonNode post(String path, String body, int expected) {
        HttpResponse<String> r = send("POST", path, body, TENANT, "k-" + UUID.randomUUID(), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    JsonNode get(String path, String tenant) {
        HttpResponse<String> r = send("GET", path, null, tenant, null, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    static String code(JsonNode problem) {
        return problem.get("code").asString();
    }

    static List<String> types(JsonNode page) {
        return page.get("data").valueStream().map(e -> e.get("event_type").asString()).toList();
    }

    List<String> outboxTypes(String aggregateId) {
        return asTenant(() -> jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by created_at, id")
                .param(aggregateId).query(String.class).list());
    }

    <T> T asTenant(Callable<T> work) {
        try {
            return TenantContext.callAs(UUID.fromString(TENANT), "test", work);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> send(String method, String path, String body, String tenant, String key, String signature) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (tenant != null) {
            b.header("X-Tenant-Id", tenant);
            b.header("X-User", "ops-user");
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
