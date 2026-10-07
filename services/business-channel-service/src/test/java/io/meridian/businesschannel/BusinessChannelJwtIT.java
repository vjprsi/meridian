package io.meridian.businesschannel;

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
 * The real security chain (JWT mode, decoder mocked): only business portal tokens reach /v1/business, the business and
 * the user come from the token's claims (never from the X-Business-* headers), staff, service, customer and agent
 * tokens are refused, and step-up uses the token's auth_time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt", "meridian.business-date.source=clock"})
@Import(MeridianTestContainers.class)
class BusinessChannelJwtIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000b502";
    static final String A = "0199a000-0000-7000-8000-0000000b10a2";
    static final String B = "0199a000-0000-7000-8000-0000000b10b2";
    static final String ADMIN_A = "0199a000-0000-7000-8000-0000000b1a02";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JwtDecoder decoder;

    @MockitoBean
    ServiceClient serviceClient;

    final HttpClient http = HttpClient.newHttpClient();
    FakeBank bank;
    String accountA;
    String accountB;

    @BeforeEach
    void setUp() {
        bank = new FakeBank(json);
        for (String c : new String[] {A, B}) {
            bank.add("customer-service", "/v1/customers",
                    "{\"id\":\"%s\",\"customer_type\":\"COMPANY\",\"status\":\"ACTIVE\",\"display_name\":\"C\"}".formatted(c));
        }
        accountA = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(A)).path("id").asString();
        accountB = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(B)).path("id").asString();
        bank.wire(serviceClient);
        when(decoder.decode(anyString())).thenAnswer(i -> token(i.getArgument(0)));
    }

    /** Token values: business-fresh, business-stale, business-weak (no auth_time), staff, service, customer, agent. */
    private static Jwt token(String value) {
        Jwt.Builder b = Jwt.withTokenValue(value).header("alg", "RS256").subject("kc-" + value)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        String biz = "openid profile channels:business";
        return switch (value) {
            case "business-fresh" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", ADMIN_A)
                    .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond()).build();
            case "business-stale" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", ADMIN_A)
                    .claim("auth_time", Instant.now().minusSeconds(3600).getEpochSecond()).build();
            case "business-weak" -> b.claim("scope", biz).claim("business_customer_id", A).claim("business_user_id", ADMIN_A)
                    .build();
            case "staff" -> b.claim("scope", "accounts:* payments:* channels:* meridian:admin")
                    .claim("business_customer_id", B).claim("business_user_id", ADMIN_A).build();
            case "service" -> b.claim("scope", "meridian:internal channels:business")
                    .claim("business_customer_id", B).claim("business_user_id", ADMIN_A).build();
            case "customer" -> b.claim("scope", "openid channels:customer").claim("customer_id", A).build();
            case "agent" -> b.claim("scope", "openid channels:agent").claim("agent_id", A).build();
            default -> throw new BadJwtException("bad token");
        };
    }

    @Test
    void onlyBusinessTokensGetInAndTheBusinessComesFromTheToken() throws Exception {
        HttpResponse<String> mine = call("business-fresh", "GET", "/v1/business/accounts", null, B);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        JsonNode page = json.readTree(mine.body());
        assertThat(page.path("data")).hasSize(1);
        assertThat(page.path("data").get(0).path("customer_id").asString()).isEqualTo(A);   // header B ignored
        assertThat(call("business-fresh", "GET", "/v1/business/accounts/" + accountB, null, B).statusCode()).isEqualTo(404);

        for (String other : new String[] {"staff", "service", "customer", "agent"}) {
            assertThat(call(other, "GET", "/v1/business/accounts", null, null).statusCode()).as(other).isEqualTo(403);
        }
        assertThat(call("nonsense", "GET", "/v1/business/accounts", null, null).statusCode()).isEqualTo(401);
        assertThat(call(null, "GET", "/v1/business/accounts", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void stepUpUsesTheTokensAuthTime() throws Exception {
        String user = "{\"display_name\":\"Mo\",\"email\":\"mo@a.test\",\"role\":\"MAKER\"}";
        for (String t : new String[] {"business-stale", "business-weak"}) {
            HttpResponse<String> r = call(t, "POST", "/v1/business/users", user, null);
            assertThat(r.statusCode()).as(t).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
            assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).contains("insufficient_user_authentication");
        }
        HttpResponse<String> ok = call("business-fresh", "POST", "/v1/business/users", user, null);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("business_customer_id").asString()).isEqualTo(A);
    }

    private HttpResponse<String> call(String token, String method, String path, String body, String businessHeader)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // dev-mode headers must be ignored in JWT mode
                .header("X-Auth-Time", String.valueOf(Instant.now().getEpochSecond()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (businessHeader != null) {
            b.header("X-Business-Customer-Id", businessHeader).header("X-Business-User-Id", UUID.randomUUID().toString())
                    .header("X-Tenant-Id", UUID.randomUUID().toString());
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
