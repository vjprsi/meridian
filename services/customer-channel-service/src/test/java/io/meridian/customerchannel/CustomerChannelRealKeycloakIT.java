package io.meridian.customerchannel;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The channel against a real Keycloak with the generated realm (MERIDIAN_KEYCLOAK_TEST_URL, e.g. a throwaway
 * Keycloak 26.4 the realm was imported into; see tools/keycloak_mfa_check.py): real token validation (issuer,
 * signature, audience), customer_id / sid / acr claims from Keycloak, step-up refusal for an acr 1 token, and
 * revocation that ends the session at Keycloak through the meridian-customer-sessions client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt"})
@Import(MeridianTestContainers.class)
@EnabledIfEnvironmentVariable(named = "MERIDIAN_KEYCLOAK_TEST_URL", matches = "http.+")
class CustomerChannelRealKeycloakIT {
    static final String KC = System.getenv("MERIDIAN_KEYCLOAK_TEST_URL");
    static final String DEMO_CUSTOMER = "0199a000-0000-7000-8000-0000000c0001";

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KC + "/realms/meridian");
        r.add("meridian.channels.sessions.identity-provider.base-url", () -> KC);
        r.add("meridian.channels.sessions.identity-provider.client-id", () -> "meridian-customer-sessions");
        r.add("meridian.channels.sessions.identity-provider.client-secret", () -> "meridian-customer-sessions-secret");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void realKeycloakTokensSessionsAndStepUp() throws Exception {
        FakeBank bank = new FakeBank(json);
        bank.add("customer-service", "/v1/customers", "{\"id\":\"%s\",\"display_name\":\"Demo\"}".formatted(DEMO_CUSTOMER));
        String a1 = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(DEMO_CUSTOMER))
                .path("id").asString();
        String a2 = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(DEMO_CUSTOMER))
                .path("id").asString();
        bank.add("card-service", "/v1/cards", "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\"}".formatted(DEMO_CUSTOMER));
        bank.add("payment-service", "/v1/payments", "{\"customer_id\":\"%s\"}".formatted(DEMO_CUSTOMER));
        bank.wire(serviceClient);

        JsonNode tokens = passwordGrant();
        String access = tokens.path("access_token").asString();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(access.split("\\.")[1]));
        assertThat(claims.path("customer_id").asString()).isEqualTo(DEMO_CUSTOMER);
        assertThat(claims.path("acr").asString()).isEqualTo("1");
        String sid = claims.path("sid").asString();

        // real validation: the customer and the session come from Keycloak's token
        HttpResponse<String> accounts = call(access, "GET", "/v1/me/cards", null);
        assertThat(accounts.statusCode()).as(accounts.body()).isEqualTo(200);
        JsonNode sessions = json.readTree(call(access, "GET", "/v1/me/sessions", null).body());
        JsonNode current = null;
        for (JsonNode s : sessions.path("data")) {
            if (s.path("is_current").asBoolean()) {
                current = s;
            }
        }
        assertThat(current).isNotNull();
        assertThat(current.path("session_id").asString()).isEqualTo(sid);
        assertThat(current.path("subject").asString()).isEqualTo(claims.path("sub").asString());
        JsonNode security = json.readTree(call(access, "GET", "/v1/me/security", null).body());
        assertThat(security.path("factors_available").asBoolean()).isTrue();
        assertThat(security.path("has_second_factor").asBoolean()).isTrue();          // demo authenticator app

        // acr 1 (and no auth_time from the password grant): money movement asks for acr 2
        HttpResponse<String> pay = call(access, "POST", "/v1/me/payments",
                "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"5\"}"
                        .formatted(a1, a2));
        assertThat(pay.statusCode()).isEqualTo(401);
        assertThat(pay.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h ->
                assertThat(h).contains("acr_values=\"2\"").contains("max_age=300"));

        // sign out everywhere: refused by the channel at once and ended at Keycloak (refresh token dead)
        HttpResponse<String> all = call(access, "POST", "/v1/me/sessions/revoke-all", "{}");
        assertThat(all.statusCode()).as(all.body()).isEqualTo(200);
        assertThat(json.readTree(all.body()).path("data").get(0).path("idp_session_ended").asBoolean()).isTrue();
        HttpResponse<String> after = call(access, "GET", "/v1/me/cards", null);
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(json.readTree(after.body()).path("code").asString()).isEqualTo("session_revoked");
        HttpResponse<String> refresh = token(Map.of("grant_type", "refresh_token", "client_id", "meridian-customer-web",
                "refresh_token", tokens.path("refresh_token").asString()));
        assertThat(refresh.statusCode()).isEqualTo(400);
    }

    private JsonNode passwordGrant() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "password");
        f.put("client_id", "meridian-customer-web");
        f.put("username", "demo.customer");
        f.put("password", "Meridian#Customer2026");
        f.put("scope", "openid");
        f.put("totp", totp("MeridianDemoTotp2026".getBytes(StandardCharsets.UTF_8)));
        HttpResponse<String> r = token(f);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    private HttpResponse<String> token(Map<String, String> fields) throws Exception {
        StringBuilder sb = new StringBuilder();
        fields.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return http.send(HttpRequest.newBuilder(URI.create(KC + "/realms/meridian/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> call(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String totp(byte[] secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array());
        int o = h[h.length - 1] & 15;
        int code = ((h[o] & 0x7f) << 24 | (h[o + 1] & 0xff) << 16 | (h[o + 2] & 0xff) << 8 | (h[o + 3] & 0xff))
                % 1_000_000;
        return String.format("%06d", code);
    }
}
