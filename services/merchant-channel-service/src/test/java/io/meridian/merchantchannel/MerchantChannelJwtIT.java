package io.meridian.merchantchannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.meridian.test.MeridianTestContainers;
import io.meridian.web.client.ServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real security chain (JWT mode, decoder mocked): only merchant app tokens reach /v1/merchant, the merchant and the
 * user come from the token's claims (never from the X-Merchant-* headers), staff, service, customer, agent and business
 * tokens are refused, and step-up uses the token's auth_time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt", "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
class MerchantChannelJwtIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000d502";
    static final String A = "0199a000-0000-7000-8000-0000000d10a2";
    static final String B = "0199a000-0000-7000-8000-0000000d10b2";
    static final String OWNER_A = "0199a000-0000-7000-8000-0000000d1a02";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JwtDecoder decoder;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeMerchantBank bank;
    String storeA;
    String storeB;

    @BeforeEach
    void setUp() {
        bank = new FakeMerchantBank(json);
        for (String m : new String[] {A, B}) {
            bank.add(FakeMerchantBank.MS, "/v1/merchants", """
                    {"id":"%s","merchant_code":"C%s","trading_name":"T","currency":"KES","status":"ACTIVE"}"""
                    .formatted(m, m.substring(m.length() - 3)));
        }
        storeA = bank.add(FakeMerchantBank.MS, "/v1/merchant-stores",
                "{\"merchant_id\":\"%s\",\"name\":\"A\",\"status\":\"ACTIVE\"}".formatted(A)).path("id").asString();
        storeB = bank.add(FakeMerchantBank.MS, "/v1/merchant-stores",
                "{\"merchant_id\":\"%s\",\"name\":\"B\",\"status\":\"ACTIVE\"}".formatted(B)).path("id").asString();
        bank.wire(serviceClient);
        when(decoder.decode(anyString())).thenAnswer(i -> token(i.getArgument(0)));
    }

    /** Token values: merchant-fresh, merchant-stale, merchant-weak (no auth_time), staff, service, customer, agent, business. */
    private static Jwt token(String value) {
        Jwt.Builder b = Jwt.withTokenValue(value).header("alg", "RS256").subject("kc-" + value)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        String mer = "openid profile channels:merchant";
        return switch (value) {
            case "merchant-fresh" -> b.claim("scope", mer).claim("merchant_id", A).claim("merchant_user_id", OWNER_A)
                    .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond()).build();
            case "merchant-stale" -> b.claim("scope", mer).claim("merchant_id", A).claim("merchant_user_id", OWNER_A)
                    .claim("auth_time", Instant.now().minusSeconds(3600).getEpochSecond()).build();
            case "merchant-weak" -> b.claim("scope", mer).claim("merchant_id", A).claim("merchant_user_id", OWNER_A)
                    .build();
            case "staff" -> b.claim("scope", "wallets:* channels:* meridian:admin")
                    .claim("merchant_id", B).claim("merchant_user_id", OWNER_A).build();
            case "service" -> b.claim("scope", "meridian:internal channels:merchant")
                    .claim("merchant_id", B).claim("merchant_user_id", OWNER_A).build();
            case "customer" -> b.claim("scope", "openid channels:customer").claim("customer_id", A).build();
            case "agent" -> b.claim("scope", "openid channels:agent").claim("agent_id", A).build();
            case "business" -> b.claim("scope", "openid channels:business").claim("business_customer_id", A)
                    .claim("business_user_id", OWNER_A).build();
            default -> throw new BadJwtException("bad token");
        };
    }

    @Test
    void onlyMerchantTokensGetInAndTheMerchantComesFromTheToken() throws Exception {
        HttpResponse<String> mine = call("merchant-fresh", "GET", "/v1/merchant/stores", null, B);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        JsonNode page = json.readTree(mine.body());
        assertThat(page.path("data")).hasSize(1);
        assertThat(page.path("data").get(0).path("merchant_id").asString()).isEqualTo(A);   // header B ignored
        assertThat(call("merchant-fresh", "GET", "/v1/merchant/stores/" + storeB, null, B).statusCode()).isEqualTo(404);

        for (String other : new String[] {"staff", "service", "customer", "agent", "business"}) {
            assertThat(call(other, "GET", "/v1/merchant/stores", null, null).statusCode()).as(other).isEqualTo(403);
        }
        assertThat(call("nonsense", "GET", "/v1/merchant/stores", null, null).statusCode()).isEqualTo(401);
        assertThat(call(null, "GET", "/v1/merchant/stores", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void stepUpUsesTheTokensAuthTime() throws Exception {
        String user = "{\"display_name\":\"Cash\",\"email\":\"c@a.test\",\"role\":\"CASHIER\"}";
        for (String t : new String[] {"merchant-stale", "merchant-weak"}) {
            HttpResponse<String> r = call(t, "POST", "/v1/merchant/staff", user, null);
            assertThat(r.statusCode()).as(t).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
            assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).contains("insufficient_user_authentication");
        }
        HttpResponse<String> ok = call("merchant-fresh", "POST", "/v1/merchant/staff", user, null);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("merchant_id").asString()).isEqualTo(A);
    }

    private HttpResponse<String> call(String token, String method, String path, String body, String merchantHeader)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // dev-mode headers must be ignored in JWT mode
                .header("X-Auth-Time", String.valueOf(Instant.now().getEpochSecond()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (merchantHeader != null) {
            b.header("X-Merchant-Id", merchantHeader).header("X-Merchant-User-Id", UUID.randomUUID().toString())
                    .header("X-Tenant-Id", UUID.randomUUID().toString());
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
