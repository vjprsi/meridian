package io.meridian.cardthreeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.cardthreeds.domain.Crypto;
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
 * card-threeds-service end to end over HTTP against PostgreSQL 18 (RLS enforced), with the vault, card-service and
 * notification-service behind a mocked ServiceClient ({@link FakeCardPlatform}). Covers signed AReq handling
 * (frictionless, challenge, reject), OTP / app / biometric challenges, the results request with CAVV, replay
 * de-duplication, idempotent cardholder actions, tenant isolation and the absence of PAN / CAVV leaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=dev", "meridian.cards.callback-secret=ds-secret"})
@Import(MeridianTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CardThreedsFlowIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000e301";
    static final String OTHER_TENANT = "0199a000-0000-7000-8000-00000000e302";
    static final String SECRET = "ds-secret";

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

    @Test
    void frictionlessAuthenticationReturnsEciAndCavvAndDeduplicates() {
        String pan = pan();
        UUID card = platform.addCard(pan, "ACTIVE", "VISA");
        String ds = ds();
        String body = areq(pan, ds, "25.00", "5411", "GB", "BROWSER", null);
        JsonNode auth = areq(body, 200);
        assertThat(auth.get("result").asString()).isEqualTo("AUTHENTICATED");
        assertThat(auth.get("challenge_type").asString()).isEqualTo("FRICTIONLESS");
        assertThat(auth.get("eci").asString()).isEqualTo("05");
        assertThat(auth.get("cavv").asString()).hasSize(28);
        assertThat(auth.get("card_id").asString()).isEqualTo(card.toString());
        assertThat(auth.get("card_last4").asString()).isEqualTo(pan.substring(12));
        assertThat(auth.get("risk_score").asInt()).isZero();
        assertThat(auth.has("authentication_value")).isFalse();
        assertThat(auth.get("completed_at")).isNotNull();

        JsonNode replay = areq(body, 200);
        assertThat(replay.get("id").asString()).isEqualTo(auth.get("id").asString());
        assertThat(replay.get("cavv").asString()).isEqualTo(auth.get("cavv").asString());
        assertThat(platform.calls.stream().filter(c -> c.path().equals("/v1/card-vault/lookup")).count()).isEqualTo(1);

        String id = auth.get("id").asString();
        JsonNode result = result(id, ds, TENANT, 200);
        assertThat(result.get("cavv").asString()).isEqualTo(auth.get("cavv").asString());
        assertThat(result(id, "other-ds", TENANT, 404).get("code").asString()).isEqualTo("not_found");
        assertThat(send("GET", "/v1/threeds-authentications/" + id + "/result?tenant_id=" + TENANT + "&ds_transaction_id="
                + ds, null, null, null, sign("other")).statusCode()).isEqualTo(401);
        // cardholder-facing reads never carry the CAVV
        assertThat(get("/v1/threeds-authentications/" + id, TENANT).has("cavv")).isFalse();

        assertThat(outbox(id)).containsExactly("cards.threeds_authentication.completed");
        String cavv = auth.get("cavv").asString();
        assertThat(asTenant(() -> jdbc.sql("""
                select (select count(*) from threeds_authentication t where row_to_json(t)::text like ?)
                     + (select count(*) from outbox o where o.payload::text like ? or o.payload::text like ?)""")
                .params("%" + pan + "%", "%" + pan + "%", "%" + cavv + "%").query(Long.class).single())).isZero();
    }

    @Test
    void mastercardUsesItsEciValues() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", "MASTERCARD");
        assertThat(areq(areq(pan, ds(), "10.00", "5411", "FR", "APP", null), 200).get("eci").asString()).isEqualTo("02");
    }

    @Test
    void signatureIsRequired() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", "VISA");
        String body = areq(pan, ds(), "10.00", "5411", "FR", "APP", null);
        assertThat(send("POST", "/v1/threeds-authentications/authenticate?tenant_id=" + TENANT, body, null, null, null)
                .statusCode()).isEqualTo(401);
        HttpResponse<String> forged = send("POST", "/v1/threeds-authentications/authenticate?tenant_id=" + TENANT,
                body.replace("10.00", "1.00"), null, null, sign(body));
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(json.readTree(forged.body()).get("code").asString()).isEqualTo("invalid_signature");
    }

    @Test
    void otpChallengeWithWrongThenRightCode() {
        String pan = pan();
        UUID card = platform.addCard(pan, "ACTIVE", "VISA");
        String ds = ds();
        JsonNode auth = areq(areq(pan, ds, "600.00", "7995", "MT", "BROWSER", null), 200);
        assertThat(auth.get("result").asString()).isEqualTo("CHALLENGE_PENDING");
        assertThat(auth.get("challenge_type").asString()).isEqualTo("OTP_SMS");
        assertThat(auth.get("risk_score").asInt()).isEqualTo(500);
        assertThat(auth.get("risk_reasons").valueStream().map(JsonNode::asString).toList())
                .containsExactly("ELEVATED_AMOUNT", "HIGH_RISK_MCC");
        assertThat(auth.has("cavv")).isFalse();
        assertThat(auth.get("challenge_expires_at")).isNotNull();
        JsonNode sms = platform.notifications.getLast();
        assertThat(sms.get("template_code").asString()).isEqualTo("CARD_3DS_OTP");
        assertThat(sms.get("channel").asString()).isEqualTo("SMS");
        assertThat(sms.get("variables").get("amount").asString()).isEqualTo("600.00");
        assertThat(sms.get("recipient_id").asString()).isEqualTo(platform.get("card-service",
                "/v1/cards/" + card + "/auth-context").get("card").get("customer_id").asString());
        String otp = platform.lastOtp();
        String id = auth.get("id").asString();
        assertThat(outbox(id)).containsExactly("cards.threeds_authentication.challenge_required");

        String wrong = otp.equals("000000") ? "111111" : "000000";
        assertThat(code(post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(wrong), "k-" + UUID.randomUUID(), 422)))
                .isEqualTo("invalid_otp");
        assertThat(get("/v1/threeds-authentications/" + id, TENANT).get("attempts").asInt()).isEqualTo(1);

        String key = "k-" + UUID.randomUUID();
        JsonNode ok = post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(otp), key, 200);
        assertThat(ok.get("result").asString()).isEqualTo("AUTHENTICATED");
        assertThat(ok.get("eci").asString()).isEqualTo("05");
        assertThat(ok.has("cavv")).isFalse();
        HttpResponse<String> replay = send("POST", "/v1/threeds-authentications/" + id + "/verify-otp", otpBody(otp),
                TENANT, key, null);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(code(post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(otp), "k-" + UUID.randomUUID(), 409)))
                .isEqualTo("invalid_transition");
        assertThat(result(id, ds, TENANT, 200).get("cavv").asString()).hasSize(28);
        assertThat(outbox(id)).containsExactly("cards.threeds_authentication.challenge_required",
                "cards.threeds_authentication.completed");
    }

    @Test
    void otpChallengeFailsAfterThreeWrongCodesOrWhenExpired() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", "VISA");
        String id = areq(areq(pan, ds(), "900.00", "5411", null, "BROWSER", "OTP_EMAIL"), 200).get("id").asString();
        assertThat(platform.notifications.getLast().get("channel").asString()).isEqualTo("EMAIL");
        String wrong = platform.lastOtp().equals("123456") ? "654321" : "123456";
        assertThat(code(post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(wrong), "k-" + UUID.randomUUID(), 422))).isEqualTo("invalid_otp");
        assertThat(code(post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(wrong), "k-" + UUID.randomUUID(), 422))).isEqualTo("invalid_otp");
        assertThat(code(post("/v1/threeds-authentications/" + id + "/verify-otp", otpBody(wrong), "k-" + UUID.randomUUID(), 422)))
                .isEqualTo("otp_attempts_exhausted");
        JsonNode failed = get("/v1/threeds-authentications/" + id, TENANT);
        assertThat(failed.get("result").asString()).isEqualTo("FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(3);
        assertThat(failed.has("eci")).isFalse();

        String pan2 = pan();
        platform.addCard(pan2, "ACTIVE", "VISA");
        String ds2 = ds();
        String id2 = areq(areq(pan2, ds2, "800.00", "5411", null, "BROWSER", null), 200).get("id").asString();
        String otp = platform.lastOtp();
        asTenant(() -> jdbc.sql("update threeds_authentication set challenge_expires_at = now() - interval '1 second' "
                + "where id = ?::uuid").param(id2).update());
        assertThat(code(post("/v1/threeds-authentications/" + id2 + "/verify-otp", otpBody(otp), "k-" + UUID.randomUUID(), 422)))
                .isEqualTo("challenge_expired");
        assertThat(get("/v1/threeds-authentications/" + id2, TENANT).get("result").asString()).isEqualTo("FAILED");
        assertThat(result(id2, ds2, TENANT, 200).has("cavv")).isFalse();
    }

    @Test
    void appBiometricAndDeclineChallenges() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", "VISA");
        int before = platform.notifications.size();
        String push = areq(areq(pan, ds(), "700.00", "5411", null, "APP", "APP_PUSH"), 200).get("id").asString();
        assertThat(platform.notifications).hasSize(before);
        assertThat(code(post("/v1/threeds-authentications/" + push + "/verify-otp", otpBody("123456"), "k-" + UUID.randomUUID(), 409)))
                .isEqualTo("invalid_transition");
        assertThat(post("/v1/threeds-authentications/" + push + "/approve", "{}", "k-" + UUID.randomUUID(), 200)
                .get("result").asString()).isEqualTo("AUTHENTICATED");
        assertThat(code(post("/v1/threeds-authentications/" + push + "/decline", "{}", "k-" + UUID.randomUUID(), 409)))
                .isEqualTo("invalid_transition");

        String bio = areq(areq(pan, ds(), "700.00", "5411", null, "APP", "BIOMETRIC"), 200).get("id").asString();
        assertThat(code(post("/v1/threeds-authentications/" + bio + "/approve", "{}", "k-" + UUID.randomUUID(), 422)))
                .isEqualTo("biometric_assertion_required");
        assertThat(post("/v1/threeds-authentications/" + bio + "/approve", "{\"biometric_assertion\":\"eyJhbGciOi\"}",
                "k-" + UUID.randomUUID(), 200).get("result").asString()).isEqualTo("AUTHENTICATED");

        String dec = areq(areq(pan, ds(), "700.00", "5411", null, "APP", "DECOUPLED"), 200).get("id").asString();
        JsonNode declined = post("/v1/threeds-authentications/" + dec + "/decline", "{\"reason\":\"not me\"}",
                "k-" + UUID.randomUUID(), 200);
        assertThat(declined.get("result").asString()).isEqualTo("FAILED");
        assertThat(declined.get("decline_reason").asString()).isEqualTo("not me");
        assertThat(outbox(dec)).containsExactly("cards.threeds_authentication.challenge_required",
                "cards.threeds_authentication.completed");
    }

    @Test
    void highRiskInactiveCardsAndLargeMerchantInitiatedRequestsAreRejected() {
        String pan = pan();
        platform.addCard(pan, "ACTIVE", "VISA");
        JsonNode risky = areq(areq(pan, ds(), "2500.00", "7995", "MT", "BROWSER", null), 200);
        assertThat(risky.get("result").asString()).isEqualTo("REJECTED");
        assertThat(risky.get("risk_score").asInt()).isEqualTo(900);
        assertThat(risky.has("eci")).isFalse();
        assertThat(risky.has("cavv")).isFalse();
        assertThat(risky.get("decline_reason").asString()).contains("HIGH_AMOUNT");

        assertThat(areq(areq(pan, ds(), "40.00", "5411", "GB", "3RI", null), 200).get("result").asString())
                .isEqualTo("AUTHENTICATED");
        assertThat(areq(areq(pan, ds(), "450.00", "5411", "GB", "3RI", null), 200).get("result").asString())
                .isEqualTo("REJECTED");

        String frozen = pan();
        platform.addCard(frozen, "FROZEN", "VISA");
        JsonNode rejected = areq(areq(frozen, ds(), "5.00", "5411", "GB", "BROWSER", null), 200);
        assertThat(rejected.get("result").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("risk_reasons").get(0).asString()).isEqualTo("CARD_NOT_ACTIVE");

        HttpResponse<String> unknown = sendSigned(areq("4000000000000002", ds(), "5.00", "5411", "GB", "APP", null));
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).get("code").asString()).isEqualTo("card_not_found");
    }

    @Test
    void tenantsAreIsolated() {
        String pan = pan();
        UUID card = platform.addCard(pan, "ACTIVE", "VISA");
        String ds = ds();
        String id = areq(areq(pan, ds, "15.00", "5411", "GB", "APP", null), 200).get("id").asString();
        assertThat(send("GET", "/v1/threeds-authentications/" + id, null, OTHER_TENANT, null, null).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/threeds-authentications?card_id=" + card, OTHER_TENANT).get("data")).isEmpty();
        assertThat(get("/v1/threeds-authentications?card_id=" + card, TENANT).get("data")).hasSize(1);
        assertThat(result(id, ds, OTHER_TENANT, 404).get("code").asString()).isEqualTo("not_found");
        assertThat(send("POST", "/v1/threeds-authentications/" + id + "/decline", "{}", OTHER_TENANT,
                "k-" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    static int panSeq = 500;

    static synchronized String pan() {
        String base = "520000" + String.format("%09d", panSeq++);
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

    static String ds() {
        return UUID.randomUUID().toString();
    }

    static String areq(String pan, String ds, String amount, String mcc, String country, String channel, String preferred) {
        return "{\"pan\":\"" + pan + "\",\"ds_transaction_id\":\"" + ds + "\",\"threeds_server_transaction_id\":\""
                + UUID.randomUUID() + "\",\"protocol_version\":\"2.2.0\",\"merchant_name\":\"Shop\""
                + (country == null ? "" : ",\"merchant_country\":\"" + country + "\"")
                + ",\"mcc\":\"" + mcc + "\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"device_channel\":\""
                + channel + "\"" + (preferred == null ? "" : ",\"preferred_challenge\":\"" + preferred + "\"") + "}";
    }

    static String otpBody(String otp) {
        return "{\"otp\":\"" + otp + "\"}";
    }

    static String sign(String data) {
        return Crypto.hmacHex(SECRET, data.getBytes(StandardCharsets.UTF_8));
    }

    JsonNode areq(String body, int expected) {
        HttpResponse<String> r = sendSigned(body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    HttpResponse<String> sendSigned(String body) {
        return send("POST", "/v1/threeds-authentications/authenticate?tenant_id=" + TENANT, body, null, null, sign(body));
    }

    JsonNode result(String id, String ds, String tenant, int expected) {
        HttpResponse<String> r = send("GET", "/v1/threeds-authentications/" + id + "/result?tenant_id=" + tenant
                + "&ds_transaction_id=" + ds, null, null, null, sign(ds));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return json.readTree(r.body());
    }

    JsonNode post(String path, String body, String key, int expected) {
        HttpResponse<String> r = send("POST", path, body, TENANT, key, null);
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

    List<String> outbox(String aggregateId) {
        return asTenant(() -> jdbc.sql("select event_type from outbox where aggregate_id = ?::uuid order by id")
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
            b.header("X-User", "cardholder-app");
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
