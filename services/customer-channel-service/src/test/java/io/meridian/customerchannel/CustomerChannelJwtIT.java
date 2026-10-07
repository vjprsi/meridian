package io.meridian.customerchannel;

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
 * The real security chain (JWT mode, decoder mocked): only customer tokens reach /v1/me, the customer and the
 * authentication time come from the token (never from X-Customer-Id / X-Auth-Time headers), and step-up uses the
 * token's auth_time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "meridian.events.relay-enabled=false", "meridian.security-mode=jwt"})
@Import(MeridianTestContainers.class)
class CustomerChannelJwtIT {
    static final String TENANT = "0199a000-0000-7000-8000-00000000c502";
    static final String A = "0199a000-0000-7000-8000-0000000000a2";
    static final String B = "0199a000-0000-7000-8000-0000000000b2";

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
    String accountA2;
    String cardB;

    @BeforeEach
    void setUp() {
        bank = new FakeBank(json);
        for (String c : new String[] {A, B}) {
            bank.add("customer-service", "/v1/customers", "{\"id\":\"%s\",\"display_name\":\"C\"}".formatted(c));
        }
        accountA = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(A)).path("id").asString();
        accountA2 = bank.add("deposit-service", "/v1/deposit-accounts",
                "{\"customer_id\":\"%s\",\"currency\":\"KES\",\"status\":\"ACTIVE\"}".formatted(A)).path("id").asString();
        bank.add("card-service", "/v1/cards", "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\"}".formatted(A));
        cardB = bank.add("card-service", "/v1/cards", "{\"customer_id\":\"%s\",\"status\":\"ACTIVE\"}".formatted(B))
                .path("id").asString();
        bank.add("payment-service", "/v1/payments", "{\"customer_id\":\"%s\"}".formatted(A));
        bank.wire(serviceClient);
        when(decoder.decode(anyString())).thenAnswer(i -> token(i.getArgument(0)));
    }

    /** Token values: customer-fresh, customer-stale, customer-weak (no auth_time), staff-admin, service. */
    private static Jwt token(String value) {
        Jwt.Builder b = Jwt.withTokenValue(value).header("alg", "RS256").subject("kc-" + value)
                .claim("tenant_id", TENANT).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        return switch (value) {
            case "customer-fresh" -> b.claim("scope", "openid profile channels:customer").claim("customer_id", A)
                    .claim("auth_time", Instant.now().minusSeconds(30).getEpochSecond()).build();
            case "customer-stale" -> b.claim("scope", "openid profile channels:customer").claim("customer_id", A)
                    .claim("auth_time", Instant.now().minusSeconds(3600).getEpochSecond()).build();
            case "customer-weak" -> b.claim("scope", "openid channels:customer").claim("customer_id", A).build();
            case "staff-admin" -> b.claim("scope", "cards:* payments:* meridian:admin").claim("customer_id", B).build();
            case "service" -> b.claim("scope", "meridian:internal channels:customer").claim("customer_id", B).build();
            default -> throw new BadJwtException("bad token");
        };
    }

    @Test
    void onlyCustomerTokensGetInAndTheCustomerComesFromTheToken() throws Exception {
        HttpResponse<String> mine = call("customer-fresh", "GET", "/v1/me/cards", null, B);
        assertThat(mine.statusCode()).isEqualTo(200);
        JsonNode page = json.readTree(mine.body());
        assertThat(page.path("data")).hasSize(1);
        assertThat(page.path("data").get(0).path("customer_id").asString()).isEqualTo(A);   // header B ignored
        assertThat(call("customer-fresh", "GET", "/v1/me/cards/" + cardB, null, B).statusCode()).isEqualTo(404);

        HttpResponse<String> staff = call("staff-admin", "GET", "/v1/me/cards", null, null);
        assertThat(staff.statusCode()).isEqualTo(403);
        assertThat(call("service", "GET", "/v1/me/cards", null, null).statusCode()).isEqualTo(403);
        assertThat(call("nonsense", "GET", "/v1/me/cards", null, null).statusCode()).isEqualTo(401);
        assertThat(call(null, "GET", "/v1/me/cards", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void stepUpUsesTheTokensAuthTime() throws Exception {
        String transfer = "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"5\"}"
                .formatted(accountA, accountA2);
        for (String t : new String[] {"customer-stale", "customer-weak"}) {
            HttpResponse<String> r = call(t, "POST", "/v1/me/payments", transfer, null);
            assertThat(r.statusCode()).as(t).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("code").asString()).isEqualTo("step_up_required");
        }
        assertThat(bank.writes()).isEmpty();
        HttpResponse<String> ok = call("customer-fresh", "POST", "/v1/me/payments", transfer, null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).path("customer_id").asString()).isEqualTo(A);
    }

    /** GAP-10: the Prometheus scrape needs no token (jwt mode) and carries histograms and step-up challenges. */
    @Test
    void prometheusIsScrapableWithoutATokenAndCountsStepUps() throws Exception {
        String transfer = "{\"kind\":\"OWN_TRANSFER\",\"from_account_id\":\"%s\",\"to_account_id\":\"%s\",\"amount\":\"5\"}"
                .formatted(accountA, accountA2);
        assertThat(call("customer-weak", "POST", "/v1/me/payments", transfer, null).statusCode()).isEqualTo(401);
        HttpResponse<String> scrape = call(null, "GET", "/actuator/prometheus", null, null);
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("http_server_requests_seconds_bucket")
                .containsPattern("meridian_auth_step_up_challenges_total\\{[^}]*channel=\"customer\"");
        // everything else on the actuator still needs a token
        assertThat(call(null, "GET", "/actuator/metrics", null, null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> call(String token, String method, String path, String body, String customerHeader)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                // dev-mode headers must be ignored in JWT mode
                .header("X-Auth-Time", String.valueOf(Instant.now().getEpochSecond()));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (customerHeader != null) {
            b.header("X-Customer-Id", customerHeader).header("X-Tenant-Id", UUID.randomUUID().toString());
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
